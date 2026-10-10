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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The loopback control server (phase1-contracts.md 5; hardening items H1 and H2): the agent's own minimal HTTP/1.1
 * server on a {@link ServerSocket} bound to 127.0.0.1, Java 8 API only. It replaces the JDK's
 * {@code com.sun.net.httpserver}, which on Java 8 reads request heads without any size or time limit before a
 * handler can authenticate them, initialises {@code java.util.logging} in {@code premain}, and keeps silent
 * connections without a cap (security findings S3, S4, S10, S11, S12, S16).
 *
 * <p><b>Connections.</b> One daemon acceptor thread {@code skillroy-http-accept} hands every accepted socket at once
 * to the bounded {@code skillroy-http-N} pool (no queue), whose thread then owns the connection; so at most
 * {@code httpThreads} connections are open. When every thread is busy, the oldest connection that has not
 * authenticated yet is closed and its thread takes the new connection over; only when every open connection has
 * authenticated is the new one closed unserved. A process without the key can therefore hold slots only for the
 * head deadline, and a controller normally still gets in. An {@code accept} failure (for example descriptors
 * exhausted by the host) backs off for a bounded time instead of spinning. Every socket has {@code TCP_NODELAY}, and
 * each response (status line, header fields and body) is written in one write.
 *
 * <p><b>Limits.</b> The request head (request line and fields, at most {@link #MAX_HEADER_FIELDS} fields) must fit
 * {@link #MAX_HEAD_BYTES} (else 431 and close) and arrive within the absolute {@link #headDeadlineMs} (else the
 * connection is closed); the socket timeout is re-armed with the remaining time before every read, so a client that
 * trickles bytes cannot extend it. A request with {@code Transfer-Encoding} is 501; a body needs
 * {@code Content-Length} (a {@code POST} without one is 411), above {@link #MAX_BODY_BYTES} it is 413 without being
 * read, and it must arrive within the absolute {@link #bodyDeadlineMs}. A body is read only for a request whose MAC
 * verified on the head (P2-L1-F1). Body bytes of a connection that has not authenticated yet are buffered as they
 * arrive, never by the declared length, and all such connections together may buffer at most
 * {@link #UNAUTHENTICATED_BODY_BUDGET} bytes (else 503; defence in depth, since only a holder of the key, or of a
 * head captured within this session, can reach it). An authenticated connection is kept
 * alive (HTTP/1.1, unless the client sends {@code Connection: close}) and closed when no byte of a next request
 * arrives within the option {@code httpIdleTimeoutMs} (default two minutes). That is deliberately longer than the
 * 30 s for which the JDK's {@code HttpClient} keeps an idle connection in its pool: the client then always drops an
 * idle connection first, and a request it sends can never meet a connection the agent is closing at that instant.
 * (The controller still retries a request whose connection closed before any reply byte, for clients that pool
 * longer.) After any 400, 401, 403, 411, 413, 431, 501 or 503 the reply says {@code Connection: close}
 * and the connection is closed (after a short bounded drain of what the client still sends, so that it reads the
 * reply instead of a reset): a client that has not authenticated never holds a keep-alive slot. {@code /events}
 * replies are {@code Connection: close} streams; at most {@link #MAX_STREAMS} (at most half the pool) are open, a
 * further one gets 503 with {@code Retry-After}.
 *
 * <p><b>Authentication (H2, {@link Auth}).</b> The token never travels. On the head alone the single
 * {@code Authorization} value must parse as {@code Skillroy-HMAC}, in the hello form on {@code /ping} and in the
 * request form everywhere else; anything else (the old {@code Bearer} included) is the constant 401 with
 * {@code WWW-Authenticate: Skillroy-HMAC}, no MAC and {@code Connection: close}, before any body byte is read. The
 * MAC is then verified on the head alone: a request with a body declares the body's SHA-256 in
 * {@link Auth#HEADER_BODY_SHA256}, which the MAC covers (Phase 6 security fix, pass 2, P2-L1-F1); only a request that
 * verified has its body read, and the SHA-256 of exactly those bytes must equal the declared one (else the same 401).
 * Nothing in the body is used before that. Only then follow {@code Host} (403),
 * {@code Origin} (403), the path (404) and the method (405). Every reply to an authenticated request carries
 * {@link Auth#HEADER_MAC} (over the status and the exact body bytes sent; an empty body for the {@code /events} head
 * and for HEAD) and {@link Auth#HEADER_SESSION}.
 *
 * <p><b>Logging (S2).</b> Nothing derived from a request is logged before it has authenticated. Client I/O failures
 * are counted and reported in at most one constant line every ten seconds, as is a saturated pool.
 *
 * <p>Threading: {@link #start} and {@link #stop} are called by the runtime's lifecycle threads; every connection
 * runs on one {@code skillroy-http-N} thread; {@code /rpc} dispatch happens on that thread. {@link #stop} closes the
 * listener and every connection that is not inside a request at once, ends the event streams, waits up to the grace
 * period for running requests and then closes every remaining socket, so that no thread stays blocked in a read.
 */
final class ControlServer {

    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    /** Upper bound of concurrent {@code /events} streams. */
    static final int MAX_STREAMS = 8;
    /** Request line plus header fields, in bytes, including their line ends. */
    static final int MAX_HEAD_BYTES = 16 * 1024;
    static final int MAX_HEADER_FIELDS = 64;
    /** Body bytes all connections that have not authenticated yet may hold together. */
    static final long UNAUTHENTICATED_BODY_BUDGET = 2L * MAX_BODY_BYTES;
    static final int BACKLOG = 64;
    /** Absolute deadline for a complete request head; package-private and mutable for tests. */
    static volatile long headDeadlineMs = 5000L;
    /** Absolute deadline for a complete request body. */
    static volatile long bodyDeadlineMs = 30000L;
    /** How long a connection closed after an error reply drains what the client still sends. */
    static volatile long drainMs = 2000L;
    static final long ACCEPT_BACKOFF_MIN_MS = 50L;
    static final long ACCEPT_BACKOFF_MAX_MS = 1000L;

    private static final int MAX_DRAIN_BYTES = 16 * 1024 * 1024;
    private static final int FIRST_BODY_CHUNK = 64 * 1024;
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long STOP_JOIN_MS = 1000L;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final String JSON_TYPE = "application/json; charset=utf-8";
    private static final byte[] NO_BODY = new byte[0];
    private static final String UNAUTHORIZED = "missing or invalid " + Auth.SCHEME + " authorization";

    private final AgentRuntime runtime;
    private final CoreContext ctx;
    private final Dispatcher dispatcher;
    private final String token;
    private final String sessionId;
    private final ThreadPoolExecutor pool;
    private final ThreadGroup group;
    private final int httpThreads;
    /** How long a kept-alive connection may wait for the first byte of its next request (option httpIdleTimeoutMs). */
    private final long idleTimeoutMs;
    private final int maxStreams;
    private final Set<SseStream> streams = new CopyOnWriteArraySet<SseStream>();
    private final AtomicInteger openStreams = new AtomicInteger();
    private final AtomicLong unauthenticatedBodyBytes = new AtomicLong();

    /** Guards {@link #connections}, the connection states, {@link #active} and {@link #stopping}. */
    private final Object lock = new Object();
    /** Open connections, oldest first. */
    private final Set<Connection> connections = new LinkedHashSet<Connection>();
    private int active;
    private int maxOpen;
    private volatile boolean stopping;

    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicLong ioFailures = new AtomicLong();
    private final AtomicLong unauthenticatedIoFailures = new AtomicLong();
    private final RateLimit busyLog = new RateLimit();
    private final RateLimit ioLog = new RateLimit();
    private final RateLimit failureLog = new RateLimit();
    private final RateLimit acceptLog = new RateLimit();

    private volatile ServerSocket listener;
    private volatile Thread acceptor;
    private volatile int port;

    /**
     * @param token the shared secret (the key of every MAC; never sent)
     * @param sessionId this start's session id ({@link Auth#newSessionId()})
     * @param pool the bounded executor of {@code httpThreads} threads, without a queue
     * @param httpThreads its size; at most half of it serves {@code /events} streams
     * @param group the agent's thread group, for the acceptor thread
     * @param idleTimeoutMs how long an authenticated keep-alive connection may stay idle (option
     *     {@code httpIdleTimeoutMs}, validated by the runtime)
     */
    ControlServer(AgentRuntime runtime, CoreContext ctx, Dispatcher dispatcher, String token, String sessionId,
                  ThreadPoolExecutor pool, int httpThreads, ThreadGroup group, long idleTimeoutMs) {
        this.runtime = runtime;
        this.ctx = ctx;
        this.dispatcher = dispatcher;
        this.token = token;
        this.sessionId = sessionId;
        this.pool = pool;
        this.httpThreads = httpThreads;
        this.group = group;
        this.idleTimeoutMs = idleTimeoutMs;
        this.maxStreams = maxStreams(httpThreads);
    }

    /** The stream cap for a pool of the given size: {@link #MAX_STREAMS}, at most half the pool, at least one. */
    static int maxStreams(int httpThreads) {
        return Math.max(1, Math.min(MAX_STREAMS, httpThreads / 2));
    }

    /**
     * Binds 127.0.0.1 (no name lookup) and starts the acceptor thread. If anything fails after the bind, the listener
     * is closed again before the failure propagates, so a failed start never leaves a bound port behind (S32).
     */
    void start(int requestedPort) throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        ServerSocket socket = new ServerSocket(requestedPort, BACKLOG, loopback);
        try {
            listener = socket;
            port = socket.getLocalPort();
            Thread thread = DaemonThreads.named(group, "skillroy-http-accept", new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, ctx.log, ctx.hostClassLoader());
            acceptor = thread;
            thread.start();
        } catch (RuntimeException | Error e) {
            closeQuietly(socket);
            throw e;
        }
    }

    int port() {
        return port;
    }

    /**
     * Closes the listener and every connection outside a request at once, ends the event streams, waits up to
     * {@code graceMs} for running requests, then closes every remaining connection. Idempotent; any thread.
     */
    void stop(long graceMs) {
        List<Connection> idle = new ArrayList<Connection>();
        synchronized (lock) {
            stopping = true;
            for (Connection c : connections) {
                if (!c.inRequest) {
                    idle.add(c);
                }
            }
            lock.notifyAll();
        }
        closeQuietly(listener);
        for (Connection c : idle) {
            c.closeSocket();
        }
        for (SseStream stream : streams) {
            stream.close();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs);
        List<Connection> remaining;
        synchronized (lock) {
            while (active > 0) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            remaining = new ArrayList<Connection>(connections);
        }
        for (Connection c : remaining) {
            c.closeSocket();
        }
        Thread thread = acceptor;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- counters for tests and diagnostics ----

    /** The idle timeout of authenticated keep-alive connections, in milliseconds. */
    long idleTimeoutMs() {
        return idleTimeoutMs;
    }

    /** Connections closed unserved because every thread was busy with an authenticated connection. */
    long rejectedCount() {
        return rejected.get();
    }

    /** Connections that had not authenticated and were closed to make room for a new one. */
    long evictedCount() {
        return evicted.get();
    }

    /** Connections open now. */
    int openConnections() {
        synchronized (lock) {
            return connections.size();
        }
    }

    /** The most connections that were open at once. */
    int maxOpenConnections() {
        synchronized (lock) {
            return maxOpen;
        }
    }

    /** Body bytes reserved now by connections that have not authenticated (at most the budget). */
    long unauthenticatedBodyBytes() {
        return unauthenticatedBodyBytes.get();
    }

    // ---- accepting ----

    private void acceptLoop() {
        long backoffMs = ACCEPT_BACKOFF_MIN_MS;
        while (!stopping) {
            Socket socket;
            try {
                socket = listener.accept();
                backoffMs = ACCEPT_BACKOFF_MIN_MS;
            } catch (Throwable t) {
                if (stopping) {
                    return;
                }
                // Descriptors exhausted by the host, for example: never spin on a listener that stays ready.
                if (acceptLog.due()) {
                    log("control server accept failed (" + t.getClass().getName() + "); retrying in " + backoffMs
                            + " ms");
                }
                pause(backoffMs);
                backoffMs = Math.min(backoffMs * 2, ACCEPT_BACKOFF_MAX_MS);
                continue;
            }
            try {
                admit(socket);
            } catch (Throwable t) {
                closeQuietly(socket);
            }
        }
    }

    private void pause(long ms) {
        synchronized (lock) {
            if (!stopping) {
                try {
                    lock.wait(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void admit(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
        } catch (IOException ignored) {
            // Only latency suffers.
        }
        Connection connection = new Connection(socket);
        synchronized (lock) {
            if (stopping) {
                closeQuietly(socket);
                return;
            }
            connections.add(connection);
        }
        try {
            pool.execute(connection);
            synchronized (lock) {
                maxOpen = Math.max(maxOpen, connections.size());
            }
            return;
        } catch (RejectedExecutionException full) {
            // Every thread is busy: make room below.
        }
        Connection victim = null;
        synchronized (lock) {
            connections.remove(connection);
            if (!stopping) {
                for (Connection c : connections) {
                    if (!c.authenticated && !c.evicted && !c.finished) {
                        victim = c;
                        break;
                    }
                }
                if (victim != null) {
                    // Counted as closed from here on, so that at most httpThreads connections are ever open.
                    connections.remove(victim);
                    victim.evicted = true;
                    victim.successor = connection;
                    register(connection);
                }
            }
        }
        if (victim != null) {
            long n = evicted.incrementAndGet();
            victim.closeSocket();
            if (busyLog.due()) {
                log("control server busy: all " + httpThreads + " threads (option httpThreads) in use; closed the "
                        + "oldest connection that had not authenticated to serve a new one (" + n + " so far)");
            }
        } else {
            long n = rejected.incrementAndGet();
            closeQuietly(socket);
            if (!stopping && busyLog.due()) {
                log("control server busy: all " + httpThreads + " threads (option httpThreads) in use by "
                        + "authenticated connections; closed a new connection without serving it (" + n + " so far)");
            }
        }
    }

    /** Under {@link #lock}: adds a connection a thread serves or will serve next. */
    private void register(Connection connection) {
        connections.add(connection);
        maxOpen = Math.max(maxOpen, connections.size());
    }

    // ---- one connection ----

    /** One accepted socket, served on one pool thread from accept until close. */
    private final class Connection implements Runnable {

        final Socket socket;
        final long acceptedNanos = System.nanoTime();
        final byte[] buf = new byte[MAX_HEAD_BYTES];
        int filled;
        /** Set once a request on it authenticated; never cleared. Under {@link #lock}. */
        volatile boolean authenticated;
        /** Closed to make room; {@link #successor} is served next on this thread. Under {@link #lock}. */
        boolean evicted;
        boolean finished;
        Connection successor;
        /** True while a request is being handled (from a complete head to the reply). Under {@link #lock}. */
        boolean inRequest;
        /** Set when the reply said {@code Connection: close} after an error: drain before closing. */
        boolean drainBeforeClose;

        Connection(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            Connection current = this;
            while (current != null) {
                current.serve();
                current = current.finish();
            }
        }

        /** Unregisters; returns the connection this thread serves next, if it was evicted for one. */
        private Connection finish() {
            Connection next;
            synchronized (lock) {
                finished = true;
                connections.remove(this);
                next = successor;
                successor = null;
                if (next != null && stopping) {
                    connections.remove(next);
                    next.closeSocket();
                    next = null;
                }
                lock.notifyAll();
            }
            return next;
        }

        private void serve() {
            try {
                boolean first = true;
                while (!stopping) {
                    HttpHead head = readHead(first);
                    if (head == null) {
                        return;
                    }
                    first = false;
                    if (!handle(head)) {
                        if (drainBeforeClose) {
                            drain();
                        }
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // A reset or a socket closed by stop() or an eviction while reading a head: nothing to report.
            } finally {
                closeSocket();
            }
        }

        void closeSocket() {
            closeQuietly(socket);
        }

        // -- reading --

        /** The next request head, or null when the connection is to be closed (EOF, deadline, 400/431 sent). */
        private HttpHead readHead(boolean first) throws IOException {
            long headDeadline;
            if (first) {
                headDeadline = acceptedNanos + TimeUnit.MILLISECONDS.toNanos(headDeadlineMs);
            } else if (filled > 0) {
                headDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(headDeadlineMs);
            } else {
                if (read(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs)) <= 0) {
                    return null;
                }
                headDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(headDeadlineMs);
            }
            int scanned = 0;
            while (true) {
                int end = headEnd(scanned);
                if (end >= 0) {
                    try {
                        HttpHead head = HttpHead.parse(buf, end);
                        consume(end + 4);
                        return head;
                    } catch (HttpHead.Malformed e) {
                        rejectHead(e.status, e.getMessage());
                        return null;
                    }
                }
                scanned = Math.max(0, filled - 3);
                if (filled == buf.length) {
                    rejectHead(431, "the request head exceeds " + MAX_HEAD_BYTES + " bytes");
                    return null;
                }
                if (read(headDeadline) <= 0) {
                    return null;
                }
            }
        }

        /** Index of the CRLFCRLF ending the head in {@code buf[0, filled)}, searching from {@code from}; or -1. */
        private int headEnd(int from) {
            for (int i = from; i + 3 < filled; i++) {
                if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                    return i;
                }
            }
            return -1;
        }

        private void consume(int n) {
            System.arraycopy(buf, n, buf, 0, filled - n);
            filled -= n;
        }

        /** Reads into the head buffer before the deadline: bytes read, or -1 for EOF or the deadline. */
        private int read(long deadlineNanos) throws IOException {
            int timeout = remainingMs(deadlineNanos);
            if (timeout <= 0) {
                return -1;
            }
            socket.setSoTimeout(timeout);
            try {
                int n = socket.getInputStream().read(buf, filled, buf.length - filled);
                if (n > 0) {
                    filled += n;
                }
                return n;
            } catch (SocketTimeoutException e) {
                return -1;
            }
        }

        /**
         * The body of {@code length} bytes, or null when it did not arrive in time (the connection is then closed
         * without a reply). Buffered as it arrives; until this connection has authenticated, the bytes count against
         * {@link #UNAUTHENTICATED_BODY_BUDGET}, whose exhaustion is reported by {@link BodyBudgetExceeded}.
         */
        private byte[] readBody(int length, long[] reserved) throws IOException, BodyBudgetExceeded {
            int have = Math.min(filled, length);
            int first = Math.min(length, Math.max(have, FIRST_BODY_CHUNK));
            reserve(first, reserved);
            byte[] body = new byte[first];
            System.arraycopy(buf, 0, body, 0, have);
            consume(have);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(bodyDeadlineMs);
            InputStream in = socket.getInputStream();
            while (have < length) {
                if (have == body.length) {
                    int grown = (int) Math.min(length, 2L * body.length);
                    reserve(grown - body.length, reserved);
                    byte[] bigger = new byte[grown];
                    System.arraycopy(body, 0, bigger, 0, have);
                    body = bigger;
                }
                int timeout = remainingMs(deadline);
                if (timeout <= 0) {
                    return null;
                }
                socket.setSoTimeout(timeout);
                int n;
                try {
                    n = in.read(body, have, body.length - have);
                } catch (SocketTimeoutException e) {
                    return null;
                }
                if (n < 0) {
                    return null;
                }
                have += n;
            }
            return body;
        }

        private void reserve(long bytes, long[] reserved) throws BodyBudgetExceeded {
            if (authenticated || bytes <= 0) {
                return;
            }
            if (unauthenticatedBodyBytes.addAndGet(bytes) > UNAUTHENTICATED_BODY_BUDGET) {
                unauthenticatedBodyBytes.addAndGet(-bytes);
                throw new BodyBudgetExceeded();
            }
            reserved[0] += bytes;
        }

        /** After an error reply with {@code Connection: close}: lets the client read it instead of a reset. */
        private void drain() {
            try {
                socket.shutdownOutput();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainMs);
                InputStream in = socket.getInputStream();
                int drained = 0;
                while (drained < MAX_DRAIN_BYTES && !stopping) {
                    int timeout = remainingMs(deadline);
                    if (timeout <= 0) {
                        return;
                    }
                    socket.setSoTimeout(timeout);
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) {
                        return;
                    }
                    drained += n;
                }
            } catch (IOException ignored) {
                // Gone already.
            }
        }

        // -- one request --

        /** Handles one request; true to keep the connection for the next one. */
        private boolean handle(HttpHead head) {
            synchronized (lock) {
                if (stopping || evicted) {
                    return false;
                }
                inRequest = true;
                active++;
            }
            Exchange exchange = new Exchange(this, head);
            try {
                return process(exchange) && !stopping;
            } catch (IOException e) {
                clientWentAway(exchange.authenticated);
                return false;
            } catch (InterruptedException e) {
                // The pools are being shut down (agent close): end quietly.
                Thread.currentThread().interrupt();
                return false;
            } catch (Throwable t) {
                if (exchange.authenticated) {
                    if (failureLog.due()) {
                        ctx.log.warn("request " + clip(head.method, 16) + " " + clip(head.path, 200) + " failed", t);
                    }
                    try {
                        exchange.json(500, message("internal error"), true);
                    } catch (Throwable ignored) {
                        // The reply may already be on its way.
                    }
                } else if (failureLog.due()) {
                    // Nothing derived from an unauthenticated request is logged (S2), not even an exception text.
                    log("an unauthenticated control request failed (" + t.getClass().getName() + ")");
                }
                return false;
            } finally {
                synchronized (lock) {
                    inRequest = false;
                    active--;
                    lock.notifyAll();
                }
                runtime.afterResponse();
            }
        }

        /** Marks the connection authenticated; false if it was evicted or the server stops meanwhile. */
        private boolean markAuthenticated() {
            synchronized (lock) {
                if (evicted || stopping) {
                    return false;
                }
                authenticated = true;
                return true;
            }
        }

        private void rejectHead(int status, String text) throws IOException {
            Exchange exchange = new Exchange(this, null);
            exchange.json(status, message(text), true);
            drain();
        }
    }

    /** Thrown when the unauthenticated body budget is exhausted. */
    private static final class BodyBudgetExceeded extends Exception {
        private static final long serialVersionUID = 1L;

        BodyBudgetExceeded() {
            super(null, null, false, false);
        }
    }

    // ---- authentication and routing ----

    private boolean process(Exchange x) throws IOException, InterruptedException {
        HttpHead head = x.head;
        Connection c = x.connection;
        boolean ping = Protocol.PATH_PING.equals(head.path);
        Auth.Credentials credentials;
        try {
            credentials = Auth.parseAuthorization(Auth.onlyValue(head.all(Auth.HEADER_AUTHORIZATION)));
        } catch (IllegalArgumentException e) {
            credentials = null;
        }
        if (credentials == null || credentials.isHello() != ping) {
            return x.unauthorized();
        }
        if (head.has("Transfer-Encoding")) {
            return x.json(501, message("Transfer-Encoding is not supported on requests; send Content-Length"), true);
        }
        long length = contentLength(head);
        if (length == -2L) {
            return x.json(400, message("invalid Content-Length"), true);
        }
        if (ping && length > 0) {
            // The hello MAC does not cover a body: a /ping with one is not authenticated.
            return x.unauthorized();
        }
        if (length > MAX_BODY_BYTES) {
            return x.json(413, message("request body exceeds " + MAX_BODY_BYTES + " bytes"), true);
        }
        // P2-L1-F1: the MAC is verified on the head alone, over the body hash the head declares, so that a client
        // without the key never gets a body byte read (and can no longer hold the body budget against the controller).
        boolean verified;
        String bodyHash = Auth.EMPTY_BODY_SHA256;
        if (ping) {
            x.replyNonce = pingNonce(head.rawQuery);
            verified = Auth.verifyHello(token, x.replyNonce, credentials);
        } else {
            bodyHash = declaredBodyHash(head, length);
            x.replyNonce = credentials.nonce();
            verified = bodyHash != null && Auth.verifyRequest(token, sessionId, credentials, head.method,
                    head.target, bodyHash);
        }
        if (!verified) {
            return x.unauthorized();
        }
        byte[] body = NO_BODY;
        if (length > 0) {
            long[] reserved = new long[1];
            try {
                body = c.readBody((int) length, reserved);
            } catch (BodyBudgetExceeded e) {
                x.extraHeader("Retry-After", "1");
                return x.json(503, message("too many request bodies from connections that have not authenticated"),
                        true);
            } finally {
                unauthenticatedBodyBytes.addAndGet(-reserved[0]);
            }
            if (body == null) {
                return false;
            }
        }
        if (!ping && !bodyHash.equals(length > 0 ? Auth.sha256hex(body) : Auth.EMPTY_BODY_SHA256)) {
            // The head held the key, the body is not the one it was signed for.
            return x.unauthorized();
        }
        if (!c.markAuthenticated()) {
            return false;
        }
        x.authenticated = true;
        return route(x, length, body);
    }

    private boolean route(Exchange x, long length, byte[] body) throws IOException, InterruptedException {
        HttpHead head = x.head;
        if (!hostAllowed(head.only("Host"))) {
            return x.json(403, message("Host header must be 127.0.0.1:" + port + " or localhost:" + port), true);
        }
        if (head.has("Origin")) {
            return x.json(403, message("requests with an Origin header are refused"), true);
        }
        String path = head.path;
        if (Protocol.PATH_RPC.equals(path)) {
            if (!requireMethod(x, "POST")) {
                return x.keepAlive();
            }
            if (length < 0) {
                return x.json(411, message("POST needs Content-Length"), true);
            }
            Object response = dispatcher.handleBody(new String(body, UTF8));
            if (response == null) {
                return x.send(204, null, NO_BODY, false);
            }
            return x.json(200, response, false);
        } else if (Protocol.PATH_PING.equals(path)) {
            return requireMethod(x, "GET") ? ping(x) : x.keepAlive();
        } else if (Protocol.PATH_EVENTS.equals(path)) {
            return requireMethod(x, "GET") ? events(x) : x.keepAlive();
        } else if (Protocol.PATH_SCHEMA.equals(path)) {
            return requireMethod(x, "GET") ? x.json(200, schema(), false) : x.keepAlive();
        } else if (Protocol.PATH_CAPABILITIES.equals(path)) {
            return requireMethod(x, "GET") ? x.json(200, ctx.capabilities.toJson(), false) : x.keepAlive();
        }
        return x.json(404, message("no such path: " + clip(path, 200)), false);
    }

    /** False after answering 405 (the caller then decides on keep-alive). */
    private boolean requireMethod(Exchange x, String expected) throws IOException {
        if (expected.equals(x.head.method)) {
            return true;
        }
        x.extraHeader("Allow", expected);
        x.json(405, message(x.head.method + " is not allowed here; use " + expected), false);
        return false;
    }

    private boolean hostAllowed(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port);
    }

    private boolean ping(Exchange x) throws IOException {
        Map<String, String> query = query(x.head.rawQuery);
        String clientProtocol = query.get("protocol");
        if (clientProtocol != null && !major(clientProtocol).equals(major(Protocol.VERSION))) {
            Map<String, Object> body = message("protocol major mismatch: agent speaks " + Protocol.VERSION);
            body.put("protocol", Protocol.VERSION);
            return x.json(400, body, true);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("nonce", x.replyNonce);
        body.put("clientNonce", x.replyNonce);
        body.put("agentNonce", runtime.nonce());
        body.put(Protocol.KEY_SESSION_ID, sessionId);
        body.put("protocol", Protocol.VERSION);
        body.put("pid", Long.valueOf(runtime.pid()));
        body.put("startTimeMs", Long.valueOf(runtime.startTimeMs()));
        body.put("loadMode", ctx.loadMode().name());
        body.put("ready", Boolean.valueOf(runtime.isReady()));
        body.put("readOnly", Boolean.valueOf(ctx.readOnly()));
        return x.json(200, body, false);
    }

    private static String major(String version) {
        int dot = version.indexOf('.');
        return (dot < 0 ? version : version.substring(0, dot)).trim();
    }

    private Map<String, Object> schema() {
        List<Object> primitives = new ArrayList<Object>();
        for (PrimitiveSpec spec : ctx.primitives.list()) {
            primitives.add(spec.describe().toJson());
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("protocol", Protocol.VERSION);
        body.put("primitives", primitives);
        return body;
    }

    /** Serves one {@code /events} stream; always ends the connection. */
    private boolean events(Exchange x) throws IOException, InterruptedException {
        if (openStreams.incrementAndGet() > maxStreams) {
            openStreams.decrementAndGet();
            x.extraHeader("Retry-After", "1");
            return x.json(503, message("too many event streams: at most " + maxStreams + " may be open"), true);
        }
        try {
            stream(x);
            return false;
        } finally {
            openStreams.decrementAndGet();
        }
    }

    private void stream(Exchange x) throws IOException, InterruptedException {
        Map<String, String> query = query(x.head.rawQuery);
        long lastSeq = ctx.events.lastSeq();
        long resume = lastSeq;
        Long lastEventId = parseLong(x.head.only("Last-Event-ID"));
        if (lastEventId != null) {
            resume = lastEventId.longValue();
        } else if (query.containsKey("since")) {
            Long since = parseLong(query.get("since"));
            if (since == null) {
                x.json(400, message("since must be an integer"), true);
                return;
            }
            resume = since.longValue();
        }
        resume = Math.max(0L, Math.min(resume, lastSeq));
        TopicFilter filter = TopicFilter.parse(query.get("topics"));
        x.extraHeader("Content-Type", "text/event-stream; charset=utf-8");
        x.extraHeader("Cache-Control", "no-cache");
        x.sendStreamHead();
        SseStream stream = new SseStream(ctx.events, filter, x.connection.socket.getOutputStream(), resume);
        streams.add(stream);
        AutoCloseable subscription = ctx.events.subscribe(null, stream);
        try {
            if (!runtime.isClosed() && !stopping) {
                stream.serve();
            }
        } finally {
            streams.remove(stream);
            try {
                subscription.close();
            } catch (Exception ignored) {
                // The bus's handles do not throw.
            }
        }
    }

    // ---- replies ----

    /** One request and its reply. */
    private final class Exchange {
        final Connection connection;
        /** Null for a reply to a head that could not be parsed. */
        final HttpHead head;
        boolean authenticated;
        String replyNonce;
        private final List<String> extra = new ArrayList<String>();

        Exchange(Connection connection, HttpHead head) {
            this.connection = connection;
            this.head = head;
        }

        void extraHeader(String name, String value) {
            extra.add(name + ": " + value);
        }

        /** Whether the connection may serve another request after a non-error reply. */
        boolean keepAlive() {
            return head != null && head.keepAlive() && authenticated;
        }

        /** The constant 401: no MAC, {@code Connection: close}. */
        boolean unauthorized() throws IOException {
            extraHeader(Auth.HEADER_WWW_AUTHENTICATE, Auth.SCHEME);
            return json(401, message(UNAUTHORIZED), true);
        }

        /** A JSON reply; returns whether the connection stays open. */
        boolean json(int status, Object body, boolean close) throws IOException {
            return send(status, JSON_TYPE, Json.write(body).getBytes(UTF8), close);
        }

        /**
         * Writes status line, fields and body in one write (a HEAD reply without the body, whose MAC then covers
         * no bytes). {@code close} forces {@code Connection: close}; otherwise the connection stays open when the
         * client and the authentication allow it.
         *
         * @return true when the connection stays open
         */
        boolean send(int status, String contentType, byte[] body, boolean close) throws IOException {
            boolean keep = !close && keepAlive();
            boolean headRequest = head != null && "HEAD".equals(head.method);
            byte[] sent = headRequest ? NO_BODY : body;
            StringBuilder sb = new StringBuilder(256);
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            if (contentType != null) {
                sb.append("Content-Type: ").append(contentType).append("\r\n");
                sb.append("Cache-Control: no-store\r\n");
            }
            for (String field : extra) {
                sb.append(field).append("\r\n");
            }
            if (status != 204) {
                sb.append("Content-Length: ").append(body.length).append("\r\n");
            }
            appendMac(sb, status, sent);
            if (!keep) {
                sb.append("Connection: close\r\n");
            }
            sb.append("\r\n");
            byte[] headBytes = sb.toString().getBytes(LATIN1);
            byte[] all = new byte[headBytes.length + sent.length];
            System.arraycopy(headBytes, 0, all, 0, headBytes.length);
            System.arraycopy(sent, 0, all, headBytes.length, sent.length);
            OutputStream out = connection.socket.getOutputStream();
            out.write(all);
            out.flush();
            if (!keep && status >= 400) {
                connection.drainBeforeClose = true;
            }
            return keep;
        }

        /** The head of an event stream: no length, {@code Connection: close}, MAC over an empty body. */
        void sendStreamHead() throws IOException {
            StringBuilder sb = new StringBuilder(256);
            sb.append("HTTP/1.1 200 OK\r\n");
            for (String field : extra) {
                sb.append(field).append("\r\n");
            }
            appendMac(sb, 200, NO_BODY);
            sb.append("Connection: close\r\n\r\n");
            OutputStream out = connection.socket.getOutputStream();
            out.write(sb.toString().getBytes(LATIN1));
            out.flush();
        }

        private void appendMac(StringBuilder sb, int status, byte[] sent) {
            if (!authenticated) {
                return;
            }
            String bodyHash = sent.length == 0 ? Auth.EMPTY_BODY_SHA256 : Auth.sha256hex(sent);
            sb.append(Auth.HEADER_MAC).append(": ")
                    .append(Auth.responseMac(token, sessionId, replyNonce, status, bodyHash)).append("\r\n");
            sb.append(Auth.HEADER_SESSION).append(": ").append(sessionId).append("\r\n");
        }
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Content Too Large";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }

    // ---- helpers ----

    /** Content-Length: -1 when absent, -2 when repeated, negative or not a number. */
    static long contentLength(HttpHead head) {
        List<String> values = head.all("Content-Length");
        if (values.isEmpty()) {
            return -1L;
        }
        if (values.size() > 1) {
            return -2L;
        }
        String text = values.get(0);
        if (text.isEmpty() || text.length() > 18) {
            return -2L;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return -2L;
            }
        }
        return Long.parseLong(text);
    }

    /**
     * The body hash a request (not {@code /ping}) declares in {@link Auth#HEADER_BODY_SHA256}, which its MAC covers
     * (P2-L1-F1): the single well-formed value; {@link Auth#EMPTY_BODY_SHA256} when the header is absent and the
     * request has no body; null (refused) when the header is repeated or malformed, or absent on a request with a body.
     */
    static String declaredBodyHash(HttpHead head, long contentLength) {
        List<String> values = head.all(Auth.HEADER_BODY_SHA256);
        if (values.isEmpty()) {
            return contentLength > 0 ? null : Auth.EMPTY_BODY_SHA256;
        }
        String value = Auth.onlyValue(values);
        return Auth.isHexDigest(value) ? value : null;
    }

    /** The raw value of the single {@code nonce} query parameter of a {@code /ping}, or null. */
    static String pingNonce(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        String found = null;
        for (String pair : rawQuery.split("&", -1)) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (Auth.PING_NONCE_PARAM.equals(name)) {
                if (found != null) {
                    return null;
                }
                found = eq < 0 ? "" : pair.substring(eq + 1);
            }
        }
        return found;
    }

    private void clientWentAway(boolean authenticated) {
        long all = ioFailures.incrementAndGet();
        long unauthenticated = authenticated ? unauthenticatedIoFailures.get()
                : unauthenticatedIoFailures.incrementAndGet();
        if (ioLog.due()) {
            log(all + " control requests ended with a client I/O error (" + unauthenticated + " unauthenticated)");
        }
    }

    private void log(String constantText) {
        try {
            ctx.log.warn(constantText);
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
    }

    /** Text cut to {@code max} characters with control characters replaced, for log lines. */
    static String clip(String text, int max) {
        StringBuilder sb = new StringBuilder(Math.min(text.length(), max) + 16);
        RingLog.appendClean(sb, text, max);
        return sb.toString();
    }

    private static Map<String, Object> message(String text) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", text);
        return body;
    }

    static Map<String, String> query(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (!out.containsKey(key)) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return text;
        }
    }

    private static Long parseLong(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int remainingMs(long deadlineNanos) {
        long left = deadlineNanos - System.nanoTime();
        if (left <= 0) {
            return 0;
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(left)));
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }

    /** At most one event per ten seconds; thread-safe. */
    private static final class RateLimit {
        private final AtomicLong next = new AtomicLong(Long.MIN_VALUE);

        boolean due() {
            long now = System.nanoTime();
            long at = next.get();
            return (at == Long.MIN_VALUE || now - at >= 0) && next.compareAndSet(at, now + LOG_INTERVAL_NANOS);
        }
    }
}
