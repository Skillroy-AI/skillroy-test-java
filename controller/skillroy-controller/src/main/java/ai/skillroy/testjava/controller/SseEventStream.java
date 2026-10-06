/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.Topics;

import java.net.ConnectException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The {@link EventStream} returned by {@link AgentClient#events}: one daemon reader thread per stream that holds an
 * SSE connection to {@code /events}, parses it and hands events to the consumer, and reconnects with
 * {@code Last-Event-ID} when the connection drops or goes quiet.
 *
 * <p>Every connection is authenticated like any other request (hardening item H2): a fresh request nonce, the request
 * MAC over {@code GET}, the path and query and an empty body, and the reply's {@code X-Skillroy-Mac} over the status
 * and an empty body, checked on the response head before a single body byte is read. The stream then rides that
 * verified connection.
 *
 * <p>The HTTP client delivers body bytes on its own threads; a byte-level splitter (finding S25) cuts them into lines
 * with a hard length cap and queues them for the reader, with a bounded window, so a slow consumer slows the
 * connection instead of filling memory. The reader thread polls that queue with the idle timeout, so a silent
 * connection is noticed without a watchdog and {@link #close()} needs no interrupt.
 *
 * <p>The stream ends for good (and, where the agent is evidently gone, marks the client lost) on a refused
 * connection, HTTP 401, a missing or wrong reply MAC, HTTP 403 or 404, an {@code agent.stopping} event, the
 * endpoint's process gone, a client that is lost or closed, or a line or event over its limit. The one exception to
 * the MAC rule is the one {@link AgentClient} applies to every request ({@link AgentClient#isUnsignedRefusal}): a 400,
 * 413, 431, 501 or 503 without any MAC header is a refusal the agent sends before it has authenticated the request
 * (503: its budget for unauthenticated request bodies is spent), so it is a failed attempt, retried with the usual
 * capped back-off, and does not latch; its body is not used. A wrong MAC on such a status, and an unsigned 200, still
 * latch.
 *
 * <p>Thread-safe: {@link #isOpen()}, {@link #lastSeq()} and {@link #close()} may be called from any thread, including
 * from inside the consumer.
 */
final class SseEventStream implements EventStream {

    private static final System.Logger LOG = System.getLogger(SseEventStream.class.getName());
    private static final AtomicLong THREAD_IDS = new AtomicLong();
    private static final int WINDOW = 64;
    private static final long MAX_ERROR_BODY_BYTES = 64 * 1024;

    private static final Object CLOSE = new Object();
    private static final Object CONNECTED = new Object();
    private static final Object UNVERIFIED = new Object();
    private static final Object OVERSIZE = new Object();
    private static final Object END = new Object();

    private enum Outcome { RETRY, PROGRESS, FATAL }

    /**
     * A non-200 reply; {@code verified} when its MAC (over the body) was right, {@code unsignedRefusal} for a refusal
     * before authentication ({@link AgentClient#isUnsignedRefusal}).
     */
    private record Status(int code, boolean verified, boolean unsignedRefusal) {
    }

    private record Failure(Throwable cause) {
    }

    private final AgentClient client;
    private final String topics;
    private final Consumer<Event> onEvent;
    private final ClientSettings settings;
    private final Thread reader;
    private final CountDownLatch closeSignal = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile boolean finished;
    private volatile boolean lost;
    private volatile String lostReason;
    private volatile long lastSeq;
    private volatile boolean haveResumePoint;
    private volatile Connection current;
    private int attempts;

    SseEventStream(AgentClient client, long sinceSeq, String topics, Consumer<Event> onEvent,
                   ClientSettings settings) {
        this.client = client;
        this.topics = topics == null || topics.isBlank() ? null : topics.strip();
        this.onEvent = onEvent;
        this.settings = settings;
        this.lastSeq = sinceSeq;
        this.haveResumePoint = sinceSeq >= 0;
        this.reader = new Thread(this::run, "skillroy-sse-" + THREAD_IDS.incrementAndGet());
        this.reader.setDaemon(true);
    }

    void start() {
        reader.start();
    }

    @Override
    public boolean isOpen() {
        return !closing.get() && !lost && !finished;
    }

    @Override
    public long lastSeq() {
        return lastSeq;
    }

    /** Why the stream stopped by itself (agent lost), or null while it is open or after {@link #close()}. */
    String lostReason() {
        return lostReason;
    }

    /** Stops the stream and waits up to 2 s for the reader thread (not when called from the consumer). */
    @Override
    public void close() {
        signalClose();
        awaitTermination(TimeUnit.SECONDS.toNanos(2));
    }

    /** Asks the reader to stop without waiting. Idempotent. */
    void signalClose() {
        if (closing.compareAndSet(false, true)) {
            closeSignal.countDown();
            Connection connection = current;
            if (connection != null) {
                connection.abort();
            }
        }
    }

    /** Waits up to {@code nanos} for the reader thread to end; returns at once when called on that thread. */
    boolean awaitTermination(long nanos) {
        if (Thread.currentThread() == reader || !reader.isAlive() && finished) {
            return finished;
        }
        try {
            reader.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !reader.isAlive();
    }

    private void run() {
        try {
            long backoffMs = settings.sseInitialBackoff().toMillis();
            while (!closing.get()) {
                if (client.isClosed()) {
                    break;
                }
                // The latch and the liveness check come before every attempt, the first one included: a lost agent
                // gets no further connection.
                String clientLost = client.lostReason();
                if (clientLost != null) {
                    lostReason = clientLost;
                    lost = true;
                    break;
                }
                Endpoint endpoint = client.endpoint();
                if (endpoint.pid() > 0 && !Discovery.isLive(endpoint.pid(), endpoint.startTimeMs())) {
                    lostReason = "process " + endpoint.pid() + " is no longer running";
                    client.markLost(lostReason);
                    lost = true;
                    break;
                }
                Outcome outcome = connectAndRead();
                if (closing.get()) {
                    break;
                }
                if (outcome == Outcome.FATAL) {
                    lost = true;
                    break;
                }
                if (outcome == Outcome.PROGRESS) {
                    backoffMs = settings.sseInitialBackoff().toMillis();
                }
                LOG.log(System.Logger.Level.DEBUG, () -> reader.getName() + ": reconnecting to /events after "
                        + lastSeq);
                if (closeSignal.await(backoffMs, TimeUnit.MILLISECONDS)) {
                    break;
                }
                backoffMs = Math.min(backoffMs * 2, settings.sseMaxBackoff().toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error e) {
            lostReason = "event stream failed: " + e;
            lost = true;
            LOG.log(System.Logger.Level.WARNING, reader.getName() + ": event stream failed", e);
        } finally {
            finished = true;
            current = null;
            client.forget(this);
        }
    }

    /** One connection: open it, read lines until it ends, goes quiet, is refused, or the stream is closed. */
    private Outcome connectAndRead() throws InterruptedException {
        BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        LineSplitter sink = new LineSplitter(queue, settings.sseMaxLineBytes());
        String reqNonce = Auth.newNonce();
        HttpRequest request;
        try {
            request = buildRequest(reqNonce);
        } catch (AgentLostException e) {
            lostReason = e.getMessage();
            return Outcome.FATAL;
        }
        HttpResponse.BodyHandler<byte[]> handler = info -> {
            if (info.statusCode() != 200) {
                return AgentClient.capped(MAX_ERROR_BODY_BYTES).apply(info);
            }
            if (!client.verifyReply(200, info.headers(), reqNonce, Auth.EMPTY_BODY_SHA256)) {
                queue.add(UNVERIFIED);
                return new Refusing();
            }
            queue.add(CONNECTED);
            return HttpResponse.BodySubscribers.fromSubscriber(sink, s -> (byte[]) null);
        };
        CompletableFuture<HttpResponse<byte[]>> future;
        try {
            future = client.http().sendAsync(request, handler);
        } catch (RuntimeException e) {
            return Outcome.RETRY;
        }
        attempts++;
        future.whenComplete((response, failure) -> {
            if (failure != null) {
                queue.add(new Failure(failure));
            } else if (response.statusCode() == 200) {
                queue.add(END);
            } else {
                byte[] body = response.body() == null ? new byte[0] : response.body();
                int code = response.statusCode();
                queue.add(new Status(code, client.verifyReply(code, response.headers(), reqNonce,
                        Auth.sha256hex(body)), AgentClient.isUnsignedRefusal(code, response.headers())));
            }
        });
        Connection connection = new Connection(future, sink, queue);
        current = connection;
        if (closing.get()) {
            connection.abort();
            return Outcome.RETRY;
        }
        SseParser parser = new SseParser(settings.sseMaxEventChars());
        boolean connected = false;
        boolean progressed = false;
        try {
            while (!closing.get()) {
                long waitMs = connected ? settings.sseIdleTimeout().toMillis()
                        : settings.sseHeaderTimeout().toMillis();
                Object item = queue.poll(waitMs, TimeUnit.MILLISECONDS);
                if (item == null) {
                    LOG.log(System.Logger.Level.DEBUG, () -> reader.getName() + ": no data for " + waitMs + " ms");
                    return progressed ? Outcome.PROGRESS : Outcome.RETRY;
                }
                if (item == CLOSE) {
                    return Outcome.RETRY;
                }
                if (item == CONNECTED) {
                    connected = true;
                } else if (item == UNVERIFIED) {
                    return fatal("/events: the reply carries no valid " + Auth.HEADER_MAC + ": "
                            + client.notTheAgent(), true);
                } else if (item == OVERSIZE) {
                    return fatal("/events sent a line longer than " + settings.sseMaxLineBytes() + " bytes", false);
                } else if (item instanceof Status status) {
                    int code = status.code();
                    if (code == 401) {
                        return fatal("/events answered HTTP 401 (authentication rejected)", true);
                    }
                    if (status.unsignedRefusal()) {
                        LOG.log(System.Logger.Level.DEBUG, () -> reader.getName() + ": /events refused before "
                                + "authentication (HTTP " + code + "); retrying");
                        return Outcome.RETRY;
                    }
                    if (!status.verified()) {
                        return fatal("/events: the reply (HTTP " + code + ") carries no valid " + Auth.HEADER_MAC
                                + ": " + client.notTheAgent(), true);
                    }
                    if (code == 403 || code == 404) {
                        return fatal("/events answered HTTP " + code, false);
                    }
                    return Outcome.RETRY;
                } else if (item instanceof Failure failure) {
                    if (AgentClient.causedBy(failure.cause(), ConnectException.class)) {
                        return fatal("/events: connection refused: nothing listens there any more", true);
                    }
                    return progressed ? Outcome.PROGRESS : Outcome.RETRY;
                } else if (item == END) {
                    return progressed ? Outcome.PROGRESS : Outcome.RETRY;
                } else if (item instanceof String line) {
                    progressed = true;
                    SseParser.Frame frame = parser.line(line);
                    sink.consumed();
                    if (parser.overflowed()) {
                        return fatal("/events sent an event with more than " + settings.sseMaxEventChars()
                                + " characters of data", false);
                    }
                    if (frame != null && dispatch(frame)) {
                        return fatal("the agent announced " + Topics.AGENT_STOPPING, true);
                    }
                }
            }
            return Outcome.RETRY;
        } finally {
            connection.abort();
            if (current == connection) {
                current = null;
            }
        }
    }

    /** Records why the stream ends; with {@code latch} the client is marked lost too. */
    private Outcome fatal(String reason, boolean latch) {
        lostReason = reason;
        if (latch) {
            client.markLost(reason);
        } else {
            LOG.log(System.Logger.Level.WARNING, reader.getName() + ": " + reason);
        }
        return Outcome.FATAL;
    }

    private HttpRequest buildRequest(String reqNonce) throws AgentLostException {
        StringBuilder path = new StringBuilder(Protocol.PATH_EVENTS);
        char separator = '?';
        long resumeFrom = lastSeq;
        boolean resume = haveResumePoint;
        if (resume) {
            path.append(separator).append("since=").append(resumeFrom);
            separator = '&';
        }
        if (topics != null) {
            path.append(separator).append("topics=").append(URLEncoder.encode(topics, StandardCharsets.UTF_8));
        }
        HttpRequest.Builder builder = client.authorizedGet(path.toString(), reqNonce, null)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache");
        if (resume && attempts > 0) {
            builder.header("Last-Event-ID", Long.toString(resumeFrom));
        }
        return builder.build();
    }

    /** Delivers one frame; true when it was the agent's {@code agent.stopping} event (the stream then ends). */
    private boolean dispatch(SseParser.Frame frame) {
        Event event;
        boolean gap;
        try {
            Map<String, Object> json = Json.parseObject(frame.data());
            if (Topics.GAP.equals(frame.event()) && !(json.containsKey("seq") && json.containsKey("topic"))) {
                // The agent sends a gap as a bare {from,to}; as an event its seq is the end of the lost range.
                long end = json.get("to") instanceof Long to ? to : lastSeq;
                long seq = frame.id() == null ? end : parseSeq(frame.id(), end);
                event = new Event(seq, System.currentTimeMillis(), Topics.GAP, null, null, null, json);
            } else {
                event = Event.fromJson(json);
            }
            gap = Topics.GAP.equals(event.topic());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, reader.getName() + ": skipping a malformed event: " + e.getMessage());
            return false;
        }
        if (!gap && haveResumePoint && event.seq() <= lastSeq) {
            return false;
        }
        if (closing.get()) {
            return false;
        }
        try {
            onEvent.accept(event);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, reader.getName() + ": event consumer threw", t);
        }
        // A gap also moves the resume point (past the lost range): otherwise a reconnect would ask for the lost events
        // again and be answered with the same gap again.
        if (!gap || event.seq() >= 0 && (!haveResumePoint || event.seq() > lastSeq)) {
            lastSeq = event.seq();
            haveResumePoint = true;
        }
        return Topics.AGENT_STOPPING.equals(event.topic());
    }

    private static long parseSeq(String text, long fallback) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** One HTTP exchange: its future, its line splitter and the queue the reader polls. */
    private record Connection(CompletableFuture<HttpResponse<byte[]>> future, LineSplitter sink,
                              BlockingQueue<Object> queue) {
        void abort() {
            queue.add(CLOSE);
            sink.cancel();
            future.cancel(true);
        }
    }

    /** The body of a {@code 200} whose MAC failed: cancelled at once, nothing of it is read. */
    private static final class Refusing implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            s.cancel();
            body.complete(null);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            // Cancelled: nothing is wanted.
        }

        @Override
        public void onError(Throwable t) {
            body.complete(null);
        }

        @Override
        public void onComplete() {
            body.complete(null);
        }
    }

    /**
     * Splits the body bytes into lines on {@code \n}, {@code \r\n} and {@code \r} on HTTP client threads and queues
     * them, decoded as UTF-8, for the reader. A line longer than the cap cancels the exchange and queues
     * {@link #OVERSIZE} (finding S25). Back-pressure: the next chunk is requested only while fewer than
     * {@link #WINDOW} lines wait in the queue. A trailing line without terminator at the end of the body is dropped,
     * as the SSE format says.
     *
     * <p>{@code onNext} runs serially (reactive streams); {@link #consumed()} runs on the reader thread; the demand
     * bookkeeping is guarded by this object's monitor.
     */
    private static final class LineSplitter implements Flow.Subscriber<List<ByteBuffer>> {
        private final BlockingQueue<Object> queue;
        private final int maxLineBytes;
        private byte[] line = new byte[256];
        private int length;
        private boolean afterCr;
        private boolean overflowed;
        private volatile Flow.Subscription subscription;
        private volatile boolean cancelled;
        private int queued;
        private boolean waiting;

        LineSplitter(BlockingQueue<Object> queue, int maxLineBytes) {
            this.queue = queue;
            this.maxLineBytes = maxLineBytes;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            if (cancelled) {
                s.cancel();
            } else {
                s.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (overflowed || cancelled) {
                return;
            }
            int lines = 0;
            for (ByteBuffer buffer : items) {
                while (buffer.hasRemaining()) {
                    byte b = buffer.get();
                    if (afterCr) {
                        afterCr = false;
                        if (b == '\n') {
                            continue;
                        }
                    }
                    if (b == '\n' || b == '\r') {
                        queue.add(new String(line, 0, length, StandardCharsets.UTF_8));
                        lines++;
                        length = 0;
                        if (line.length > 64 * 1024) {
                            line = new byte[256];
                        }
                        afterCr = b == '\r';
                        continue;
                    }
                    if (length == maxLineBytes) {
                        overflowed = true;
                        line = new byte[0];
                        queue.add(OVERSIZE);
                        cancel();
                        return;
                    }
                    if (length == line.length) {
                        line = Arrays.copyOf(line, (int) Math.min((long) maxLineBytes, 2L * line.length));
                    }
                    line[length++] = b;
                }
            }
            boolean more;
            synchronized (this) {
                queued += lines;
                more = queued < WINDOW;
                waiting = !more;
            }
            if (more) {
                request();
            }
        }

        @Override
        public void onError(Throwable t) {
            queue.add(new Failure(t));
        }

        @Override
        public void onComplete() {
            queue.add(END);
        }

        /** The reader took one line off the queue. */
        void consumed() {
            boolean more;
            synchronized (this) {
                queued--;
                more = waiting && queued < WINDOW;
                if (more) {
                    waiting = false;
                }
            }
            if (more) {
                request();
            }
        }

        private void request() {
            Flow.Subscription s = subscription;
            if (s != null && !cancelled) {
                s.request(1);
            }
        }

        void cancel() {
            cancelled = true;
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }
}
