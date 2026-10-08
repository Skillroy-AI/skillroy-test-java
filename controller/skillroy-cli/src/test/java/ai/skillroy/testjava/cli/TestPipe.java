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

package ai.skillroy.testjava.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An in-memory byte pipe for in-process tests: what is written to {@link #out()} is read from {@link #in()}, in
 * order; {@link #closeWriter()} (or closing the output) ends the input. Unlike {@code PipedInputStream} it does not
 * care which threads write (Reactor's threads come and go). Everything written is also kept, for the scans of the
 * masking tests. A copy of the MCP module's test helper (test classes are not shared between modules).
 */
final class TestPipe {

    private static final byte[] END = new byte[0];

    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream all = new ByteArrayOutputStream();
    private final Duration readTimeout;
    private volatile boolean writerClosed;

    /** A pipe whose reads wait for data as long as it takes. */
    TestPipe() {
        this(null);
    }

    /** A pipe whose reads fail with {@link InterruptedIOException} when no data comes within {@code readTimeout}. */
    TestPipe(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (writerClosed) {
                throw new IOException("the pipe is closed");
            }
            byte[] copy = Arrays.copyOfRange(b, off, off + len);
            synchronized (all) {
                all.write(copy, 0, copy.length);
            }
            chunks.add(copy);
        }

        @Override
        public void close() {
            closeWriter();
        }
    };

    private final InputStream in = new InputStream() {
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
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (!ended && position >= chunk.length) {
                byte[] next;
                try {
                    next = readTimeout == null ? chunks.take()
                            : chunks.poll(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted");
                }
                if (next == null) {
                    throw new InterruptedIOException("no data within " + readTimeout);
                }
                if (next == END) {
                    ended = true;
                    chunks.add(END);
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
    };

    OutputStream out() {
        return out;
    }

    InputStream in() {
        return in;
    }

    /** Ends the input once what was written has been read. Idempotent. */
    void closeWriter() {
        if (!writerClosed) {
            writerClosed = true;
            chunks.add(END);
        }
    }

    /** Everything written so far, as UTF-8. */
    String written() {
        synchronized (all) {
            return all.toString(StandardCharsets.UTF_8);
        }
    }
}
