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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.protocol.Json;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fake remote-debugging endpoint for {@link TargetProbe}, on one port as a browser's is: {@code /json/version} and
 * {@code /json/list} answered over HTTP/1.1 ({@code Connection: close}), and the targets' WebSockets on the same port
 * (the opening handshake with {@code Sec-WebSocket-Accept}, masked client frames, unmasked server frames, ping and
 * close), told apart by the request's {@code Sec-WebSocket-Key} (the probe dials only the endpoint's own authority,
 * security fix S44). Each target answers {@code Runtime.evaluate} as its {@link Behaviour} says; an answering target
 * first sends an unrelated event, which the probe must ignore. Every client frame is checked for its mask.
 * {@link #versionBytes} makes {@code /json/version} stream that many bytes instead, with or without a
 * {@code Content-Length} ({@link #versionDeclared}); {@link #versionWritten} counts what it could write.
 */
final class FakeDevTools implements AutoCloseable {

    /** How a target's socket behaves. */
    enum Behaviour { ANSWER, SILENT, ERROR_ANSWER, CLOSE }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    final List<Map<String, Object>> targets = new CopyOnWriteArrayList<>();
    final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    final List<String> received = new CopyOnWriteArrayList<>();
    final AtomicInteger unmaskedFrames = new AtomicInteger();
    final AtomicInteger openSockets = new AtomicInteger();
    /** The most target sockets that were open at the same time. */
    final AtomicInteger peakOpenSockets = new AtomicInteger();
    volatile int versionStatus = 200;
    volatile String versionBody = "{\"Browser\":\"FakeChrome/152.0.0.0\",\"Protocol-Version\":\"1.3\"}";
    volatile String listBody;
    final CountDownLatch hang = new CountDownLatch(1);
    volatile boolean versionHangs;
    /** When positive, {@code /json/version} streams this many bytes of a JSON string instead of its body. */
    volatile long versionBytes;
    /** Whether that stream declares its {@code Content-Length}. */
    volatile boolean versionDeclared = true;
    /** The bytes of that stream written before the client went away. */
    final AtomicLong versionWritten = new AtomicLong();
    /** Every HTTP request line, in order. */
    final List<String> httpRequests = new CopyOnWriteArrayList<>();

    private final ServerSocket ws;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    FakeDevTools() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ws = new ServerSocket(0, 50, loopback);
        threads.submit(this::acceptLoop);
    }

    CdpEndpoint endpoint() {
        return CdpEndpoint.local(ws.getLocalPort());
    }

    int wsPort() {
        return ws.getLocalPort();
    }

    /** Adds a target; pages get a WebSocket URL on the fake socket server unless {@code withWsUrl} is false. */
    FakeDevTools target(String id, String type, Behaviour behaviour, boolean withWsUrl) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("description", "");
        t.put("id", id);
        t.put("title", "title of " + id);
        t.put("type", type);
        t.put("url", "data:text/html,page-" + id);
        if (withWsUrl) {
            t.put("webSocketDebuggerUrl", "ws://127.0.0.1:" + wsPort() + "/devtools/page/" + id);
        }
        targets.add(t);
        behaviours.put(id, behaviour);
        return this;
    }

    FakeDevTools page(String id, Behaviour behaviour) {
        return target(id, "page", behaviour, true);
    }

    int requestsTo(String id) {
        AtomicInteger n = requests.get(id);
        return n == null ? 0 : n.get();
    }

    @Override
    public void close() {
        hang.countDown();
        try {
            ws.close();
        } catch (IOException ignored) {
            // closing
        }
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        threads.shutdownNow();
    }

    /** Answers one HTTP request (the socket is closed afterwards). */
    private void http(String path, OutputStream out) throws IOException {
        if (path.startsWith("/json/version")) {
            if (versionHangs) {
                try {
                    hang.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            long stream = versionBytes;
            if (stream > 0) {
                streamVersion(out, stream);
                return;
            }
            reply(out, versionStatus, versionBody);
        } else if (path.startsWith("/json/list")) {
            reply(out, 200, listBody != null ? listBody : Json.write(targets));
        } else {
            reply(out, 404, "{}");
        }
    }

    /** {@code "aaaa…"}: a JSON string of {@code total} bytes, written in 64 KiB pieces until the client goes away. */
    private void streamVersion(OutputStream out, long total) throws IOException {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n"
                + (versionDeclared ? "Content-Length: " + total + "\r\n" : "") + "\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        byte[] piece = new byte[64 * 1024];
        java.util.Arrays.fill(piece, (byte) 'a');
        piece[0] = '"';
        long left = total;
        boolean first = true;
        while (left > 0) {
            int n = (int) Math.min(piece.length, left);
            if (!first) {
                piece[0] = 'a';
            }
            if (left == n) {
                piece[n - 1] = '"';
            }
            out.write(piece, 0, n);
            out.flush();
            versionWritten.addAndGet(n);
            left -= n;
            first = false;
        }
    }

    private static void reply(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\nContent-Type: application/json;"
                + " charset=UTF-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private void acceptLoop() {
        while (!ws.isClosed()) {
            try {
                Socket s = ws.accept();
                sockets.add(s);
                threads.submit(() -> serve(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        boolean counted = false;
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            List<String> head = readHead(in);
            String[] requestLine = head.get(0).split(" ");
            String path = requestLine[1];
            String id = path.substring(path.lastIndexOf('/') + 1);
            String key = null;
            for (String line : head) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Key")) {
                    key = line.substring(colon + 1).trim();
                }
            }
            if (key == null && path.startsWith("/json")) {
                httpRequests.add(head.get(0));
                http(path, out);
                return;
            }
            counted = true;
            peakOpenSockets.accumulateAndGet(openSockets.incrementAndGet(), Math::max);
            Behaviour behaviour = behaviours.get(id);
            if (key == null || behaviour == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                    (key + GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.read();
                if (b0 < 0) {
                    return;
                }
                int b1 = read(in);
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long length = b1 & 0x7f;
                if (length == 126) {
                    length = (read(in) << 8) | read(in);
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | read(in);
                    }
                }
                byte[] mask = new byte[4];
                if (masked) {
                    readFully(in, mask);
                } else {
                    unmaskedFrames.incrementAndGet();
                }
                byte[] payload = new byte[(int) length];
                readFully(in, payload);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                switch (opcode) {
                    case 0x8 -> {
                        writeFrame(out, 0x8, new byte[0]);
                        return;
                    }
                    case 0x9 -> writeFrame(out, 0xA, payload);
                    case 0x1, 0x0 -> {
                        message.write(payload);
                        if (fin) {
                            String text = message.toString(StandardCharsets.UTF_8);
                            message.reset();
                            if (!onText(id, behaviour, text, out)) {
                                return;
                            }
                        }
                    }
                    default -> {
                        // ignored
                    }
                }
            }
        } catch (Exception e) {
            // the probe aborts sockets; that ends here
        } finally {
            if (counted) {
                openSockets.decrementAndGet();
            }
        }
    }

    /** Handles one client message; false ends the connection. */
    private boolean onText(String id, Behaviour behaviour, String text, OutputStream out) throws IOException {
        received.add(text);
        requests.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        Map<String, Object> request = Json.parseObject(text);
        Object requestId = request.get("id");
        switch (behaviour) {
            case ANSWER -> {
                writeFrame(out, 0x1, "{\"method\":\"Runtime.executionContextCreated\",\"params\":{\"id\":7}}"
                        .getBytes(StandardCharsets.UTF_8));
                writeFrame(out, 0x1, ("{\"id\":" + requestId + ",\"result\":{\"result\":{\"type\":\"number\","
                        + "\"value\":1,\"description\":\"1\"}}}").getBytes(StandardCharsets.UTF_8));
                return true;
            }
            case ERROR_ANSWER -> {
                writeFrame(out, 0x1, ("{\"id\":" + requestId + ",\"error\":{\"code\":-32000,\"message\":"
                        + "\"Cannot find default execution context\"}}").getBytes(StandardCharsets.UTF_8));
                return true;
            }
            case CLOSE -> {
                writeFrame(out, 0x8, new byte[] {0x03, (byte) 0xe8});
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length < 65536) {
            frame.write(126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length & 0xff);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((long) payload.length >>> (8 * i)) & 0xff);
            }
        }
        frame.write(payload);
        synchronized (out) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    private static List<String> readHead(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        while (true) {
            int c = read(in);
            if (c == '\n') {
                String l = line.toString();
                if (l.endsWith("\r")) {
                    l = l.substring(0, l.length() - 1);
                }
                if (l.isEmpty()) {
                    return lines;
                }
                lines.add(l);
                line.setLength(0);
            } else {
                line.append((char) c);
            }
        }
    }

    private static int read(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] into) throws IOException {
        int off = 0;
        while (off < into.length) {
            int n = in.read(into, off, into.length - off);
            if (n < 0) {
                throw new EOFException();
            }
            off += n;
        }
    }
}
