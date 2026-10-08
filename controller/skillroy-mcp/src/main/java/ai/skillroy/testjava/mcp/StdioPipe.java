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
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stands between the client's streams and the SDK's stdio transport, so that the server decides when the transport
 * sees the end of its input: the SDK stops writing the moment its input ends, which would drop the answer to a call in
 * flight. A daemon thread ({@code skillroy-mcp-stdin}) reads the client's lines and hands them to the transport,
 * counting the requests among them, and reports the end of the client's stream; the transport's output is written
 * through to the client while the responses among its lines are counted. The server ends the transport's input
 * ({@link #endInput}) once every request has been answered ({@link #awaitAnswered}, bounded) or the wait gave up.
 *
 * <p>The reading thread ends with the client's stream; after the server ended for another reason (a lost agent) it
 * may stay blocked on a stream that never ends until the process exits, which is why it is a daemon. Thread-safe.
 */
final class StdioPipe {

    private static final byte[] END = new byte[0];

    private final InputStream source;
    private final OutputStream sink;
    private final Runnable onSourceEnd;
    private final BlockingQueue<byte[]> lines = new LinkedBlockingQueue<>();
    private final AtomicBoolean inputEnded = new AtomicBoolean();
    private final Object answers = new Object();
    private final Thread pump;
    private final ServerIn serverIn = new ServerIn();
    private final ServerOut serverOut = new ServerOut();
    /** Guarded by {@link #answers}. */
    private long requests;
    /** Guarded by {@link #answers}. */
    private long responses;
    /** Guarded by {@link #answers}: the client's stream refused a write, so no further answer can be delivered. */
    private boolean broken;

    /**
     * @param source the client's stream (stdin)
     * @param sink the stream to the client (stdout); written through and flushed, never closed
     * @param onSourceEnd called once on the reading thread when the client's stream ended (or failed)
     */
    StdioPipe(InputStream source, OutputStream sink, Runnable onSourceEnd) {
        this.source = source;
        this.sink = sink;
        this.onSourceEnd = onSourceEnd;
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

    /** Ends the transport's input (it then closes its session); idempotent. */
    void endInput() {
        if (inputEnded.compareAndSet(false, true)) {
            lines.add(END);
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

    /** The requests read and the responses written, for the log. */
    String counts() {
        synchronized (answers) {
            return requests + " requests, " + responses + " responses";
        }
    }

    // ------------------------------------------------------------------------------------------------ reading

    private void pump() {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            int n;
            while (!inputEnded.get() && (n = source.read(buffer)) >= 0) {
                int from = 0;
                for (int i = 0; i < n; i++) {
                    if (buffer[i] == '\n') {
                        line.write(buffer, from, i + 1 - from);
                        forward(line.toByteArray());
                        line.reset();
                        from = i + 1;
                    }
                }
                line.write(buffer, from, n - from);
            }
        } catch (IOException | RuntimeException e) {
            // The client's stream failed: as its end.
        } finally {
            if (line.size() > 0 && !inputEnded.get()) {
                line.write('\n');
                forward(line.toByteArray());
            }
            onSourceEnd.run();
        }
    }

    private void forward(byte[] bytes) {
        int count = requestCount(bytes);
        if (count > 0) {
            synchronized (answers) {
                requests += count;
            }
        }
        lines.add(bytes);
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
                    next = lines.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted while reading the client's stream");
                }
                if (next == END) {
                    ended = true;
                    lines.add(END);
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
