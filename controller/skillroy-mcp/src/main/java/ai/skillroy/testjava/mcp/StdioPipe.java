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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Stands between the client's streams and the SDK's stdio transport, so that the server decides when the transport
 * sees the end of its input: the SDK stops writing the moment its input ends, which would drop the answer to a call in
 * flight. A daemon thread ({@code skillroy-mcp-stdin}) reads the client's lines and hands them to the transport,
 * counting the requests among them, and reports the end of the client's stream; the transport's output is written
 * through to the client while the responses among its lines are counted. The server ends the transport's input
 * ({@link #endInput}) once every request has been answered ({@link #awaitAnswered}, bounded) or the wait gave up.
 *
 * <p>Bounds (phase5-contracts.md 9.3): a line of more than {@value #MAX_LINE_BYTES} bytes (its newline not counted) is
 * a protocol error: the reader stops at once, never hands that line on, and reports the end of the client's stream with
 * the error, so that the server ends cleanly after answering what it read before. At most {@value #MAX_QUEUED_BYTES}
 * bytes of lines wait for the transport (a longer single line waits alone): beyond that the reader waits, and the
 * client's further writes wait in its pipe (back-pressure). Nothing read is dropped, but the line the reader holds when
 * the server ends the transport's input (no answer to it could be written any more).
 *
 * <p>The reading thread ends with the client's stream; after the server ended for another reason (a lost agent) it
 * may stay blocked on a stream that never ends until the process exits, which is why it is a daemon. Thread-safe.
 */
final class StdioPipe {

    /** The longest line the client may send, its newline not counted. */
    static final int MAX_LINE_BYTES = 8 * 1024 * 1024;
    /** The most bytes of the client's lines that wait for the transport. */
    static final long MAX_QUEUED_BYTES = 16L * 1024 * 1024;

    private static final byte[] END = new byte[0];

    private final InputStream source;
    private final OutputStream sink;
    private final Consumer<String> onSourceEnd;
    private final int maxLineBytes;
    private final long maxQueuedBytes;
    /** The lines for the transport, then {@link #END}; guarded by itself. */
    private final ArrayDeque<byte[]> lines = new ArrayDeque<>();
    private final AtomicBoolean inputEnded = new AtomicBoolean();
    private final Object answers = new Object();
    private final Thread pump;
    private final ServerIn serverIn = new ServerIn();
    private final ServerOut serverOut = new ServerOut();
    /** Guarded by {@link #lines}. */
    private long queuedBytes;
    /** Guarded by {@link #lines}: the most bytes that waited at once, for the log. */
    private long peakQueuedBytes;
    /** Guarded by {@link #answers}. */
    private long requests;
    /** Guarded by {@link #answers}. */
    private long responses;
    /** Guarded by {@link #answers}: the client's stream refused a write, so no further answer can be delivered. */
    private boolean broken;

    /**
     * @param source the client's stream (stdin)
     * @param sink the stream to the client (stdout); written through and flushed, never closed
     * @param onSourceEnd called once on the reading thread when the client's stream ended (or failed), with null, or
     *     with the protocol error that stopped the reading
     */
    StdioPipe(InputStream source, OutputStream sink, Consumer<String> onSourceEnd) {
        this(source, sink, onSourceEnd, MAX_LINE_BYTES, MAX_QUEUED_BYTES);
    }

    /** {@link #StdioPipe(InputStream, OutputStream, Consumer)} with other bounds (tests). */
    StdioPipe(InputStream source, OutputStream sink, Consumer<String> onSourceEnd, int maxLineBytes,
              long maxQueuedBytes) {
        this.source = source;
        this.sink = sink;
        this.onSourceEnd = onSourceEnd;
        this.maxLineBytes = maxLineBytes;
        this.maxQueuedBytes = maxQueuedBytes;
        this.pump = new Thread(this::pump, "skillroy-mcp-stdin");
        this.pump.setDaemon(true);
    }

    /** Starts reading the client's stream. */
    void start() {
        pump.start();
    }

    /** What the transport reads. */
    InputStream serverIn() {
        return serverIn;
    }

    /** What the transport writes. */
    OutputStream serverOut() {
        return serverOut;
    }

    /**
     * Ends the transport's input (it then closes its session) and releases a reader that waits for room; idempotent.
     */
    void endInput() {
        if (inputEnded.compareAndSet(false, true)) {
            synchronized (lines) {
                lines.addLast(END);
                lines.notifyAll();
            }
        }
    }

    /**
     * Waits until every request read so far has been answered, the client's stream refused a write, or {@code max}
     * passed.
     *
     * @return true when nothing is left unanswered
     * @throws InterruptedException if the thread is interrupted
     */
    boolean awaitAnswered(Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        synchronized (answers) {
            while (responses < requests && !broken) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(answers, left);
            }
            return responses >= requests;
        }
    }

    /**
     * Waits until at least {@code bytes} bytes of lines wait for the transport, or {@code max} passed (for tests).
     *
     * @return true when they did
     * @throws InterruptedException if the thread is interrupted
     */
    boolean awaitQueued(long bytes, Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        synchronized (lines) {
            while (queuedBytes < bytes) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lines, left);
            }
            return true;
        }
    }

    /** The requests read and the responses written, for the log. */
    String counts() {
        synchronized (answers) {
            return requests + " requests, " + responses + " responses";
        }
    }

    /** The most bytes of lines that waited for the transport at once, for the log. */
    long peakQueuedBytes() {
        synchronized (lines) {
            return peakQueuedBytes;
        }
    }

    // ------------------------------------------------------------------------------------------------ reading

    private void pump() {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        String error = null;
        try {
            int n;
            reading:
            while (!inputEnded.get() && (n = source.read(buffer)) >= 0) {
                int from = 0;
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n') {
                        if (line.size() + i - from > maxLineBytes) {
                            error = tooLong();
                            break reading;
                        }
                        line.write(buffer, from, i + 1 - from);
                        if (!forward(line.toByteArray())) {
                            break reading;
                        }
                        line.reset();
                        from = i + 1;
                    }
                }
                if (line.size() + n - from > maxLineBytes) {
                    error = tooLong();
                    break;
                }
                line.write(buffer, from, n - from);
            }
        } catch (IOException | RuntimeException e) {
            // The client's stream failed: as its end.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (error == null && line.size() > 0 && !inputEnded.get()) {
                line.write('\n');
                try {
                    forward(line.toByteArray());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            onSourceEnd.accept(error);
        }
    }

    private String tooLong() {
        return "a line of more than " + maxLineBytes + " bytes";
    }

    /**
     * Hands one line to the transport, waiting while the lines that wait already hold {@link #maxQueuedBytes} bytes.
     *
     * @return false when the server ended the transport's input first (the line is dropped)
     * @throws InterruptedException if the reading thread is interrupted
     */
    private boolean forward(byte[] bytes) throws InterruptedException {
        int count = requestCount(bytes);
        if (count > 0) {
            synchronized (answers) {
                requests += count;
            }
        }
        synchronized (lines) {
            while (!lines.isEmpty() && queuedBytes + bytes.length > maxQueuedBytes && !inputEnded.get()) {
                lines.wait();
            }
            if (!inputEnded.get()) {
                lines.addLast(bytes);
                queuedBytes += bytes.length;
                peakQueuedBytes = Math.max(peakQueuedBytes, queuedBytes);
                lines.notifyAll();
                return true;
            }
        }
        if (count > 0) {
            synchronized (answers) {
                requests -= count;
                answers.notifyAll();
            }
        }
        return false;
    }

    /** The next line for the transport, or {@link #END}, which stays in place for any later read. */
    private byte[] take() throws InterruptedException {
        synchronized (lines) {
            while (lines.isEmpty()) {
                lines.wait();
            }
            byte[] next = lines.peekFirst();
            if (next != END) {
                lines.removeFirst();
                queuedBytes -= next.length;
                lines.notifyAll();
            }
            return next;
        }
    }

    /** The JSON-RPC requests in one line (a request has a method and an id); 0 for anything else. */
    static int requestCount(byte[] line) {
        Object message = parse(line);
        if (message instanceof List<?> batch) {
            int count = 0;
            for (Object item : batch) {
                count += isRequest(item) ? 1 : 0;
            }
            return count;
        }
        return isRequest(message) ? 1 : 0;
    }

    /** True for one line holding a JSON-RPC response (an id with a result or an error, no method). */
    static boolean isResponse(byte[] line) {
        return parse(line) instanceof Map<?, ?> map && map.get("id") != null && !map.containsKey("method")
                && (map.containsKey("result") || map.containsKey("error"));
    }

    private static boolean isRequest(Object message) {
        return message instanceof Map<?, ?> map && map.get("method") instanceof String && map.get("id") != null;
    }

    private static Object parse(byte[] line) {
        String text = new String(line, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Json.parse(text);
        } catch (JsonException e) {
            return null;
        }
    }

    /** The transport's input: the client's lines, then the end once {@link #endInput} was called. */
    private final class ServerIn extends InputStream {

        private byte[] chunk = new byte[0];
        private int position;
        private boolean ended;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (!ended && position >= chunk.length) {
                byte[] next;
                try {
                    next = take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted while reading the client's stream");
                }
                if (next == END) {
                    ended = true;
                } else {
                    chunk = next;
                    position = 0;
                }
            }
            if (ended) {
                return -1;
            }
            int n = Math.min(len, chunk.length - position);
            System.arraycopy(chunk, position, b, off, n);
            position += n;
            return n;
        }

        @Override
        public int available() {
            return ended ? 0 : chunk.length - position;
        }
    }

    /** The transport's output: written through to the client, the responses counted line by line. */
    private final class ServerOut extends OutputStream {

        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            try {
                sink.write(b, off, len);
            } catch (IOException | RuntimeException e) {
                markBroken();
                throw e;
            }
            int from = off;
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    line.write(b, from, i - from);
                    finishLine();
                    from = i + 1;
                }
            }
            line.write(b, from, off + len - from);
        }

        @Override
        public synchronized void flush() throws IOException {
            try {
                sink.flush();
            } catch (IOException | RuntimeException e) {
                markBroken();
                throw e;
            }
        }

        /** The client's stream is the caller's: flushed, never closed here. */
        @Override
        public void close() throws IOException {
            flush();
        }

        private void finishLine() {
            boolean response = isResponse(line.toByteArray());
            line.reset();
            if (response) {
                synchronized (answers) {
                    responses++;
                    answers.notifyAll();
                }
            }
        }

        private void markBroken() {
            synchronized (answers) {
                broken = true;
                answers.notifyAll();
            }
        }
    }
}
