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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.ProductionMarker;
import ai.skillroy.testjava.agent.api.spi.ReadinessSource;
import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.Topics;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The running agent (phase1-contracts.md 5): control server, registries, modules, readiness and discovery. The
 * bootstrap creates it reflectively in the isolated agent loader; in-process tests and single-loader embeddings may
 * call {@link #start} directly.
 *
 * <p>Start sequence: resolve options, check {@code enabled} (nothing is started otherwise), create the registries,
 * create this start's session id (hardening item H2: new at every start, never configurable, published with the
 * discovery file and as {@code skillroy.agent.session}), start the control server (the agent's own loopback HTTP
 * listener, H1), register the core primitives, discover and install modules, publish discovery,
 * publish {@code agent.started}; then, on the {@code skillroy-ready} thread, wait for every {@code ReadinessSource},
 * check every {@code ProductionMarker} (a match closes the agent), call {@code onTargetReady}, run the probes and
 * publish {@code target.ready}. When the sources are not ready within {@code readyTimeoutMs} the capability
 * {@code target.ready} becomes UNAVAILABLE and {@code target.ready {ready:false}} is published once; the poll goes
 * on, so a target that becomes ready later still completes the sequence.
 *
 * <p>Options beyond phase1-contracts.md 2.4 (contract amendments A2, A3, A5): {@code httpThreads} (default 32,
 * 2 to 1024) bounds the control server's connections and threads; {@code httpIdleTimeoutMs} (default
 * {@value #DEFAULT_HTTP_IDLE_TIMEOUT_MS}, {@value #MIN_HTTP_IDLE_TIMEOUT_MS} to {@value #MAX_HTTP_IDLE_TIMEOUT_MS})
 * is how long an authenticated keep-alive connection may stay idle before the agent closes it. The default is kept
 * well above the 30 s for which the JDK's {@code HttpClient} pools an idle connection
 * ({@code jdk.httpclient.keepalive.timeout}), so that the controller always drops an idle connection first: if the
 * agent closed it at the same moment, a request the controller was just sending on it would meet a closed
 * connection. The controller additionally retries such a request once (see its {@code AgentClient}), because a client
 * may pool for longer than the agent waits; a smaller value only makes that retry more frequent. {@code discoveryDir}
 * defaults to the per-user directory of {@link SecureFiles#defaultDiscoveryDir()} (findings S8/S19), and the token
 * and discovery files are
 * written only into a directory the current user owns and nobody else may write to (otherwise the problem is logged
 * and recorded in {@code skillroy.agent.error}; a generated token that cannot be written stops the start);
 * {@code redact} adds a key regex to the secret redaction of {@code jvm.info}, {@code jvm.properties} and
 * {@code agent.info}, as does every registered {@code RedactionKeys} (A21). Finding S14: {@code allowFailedModules}
 * (default false) and {@code requireProductionMarker} (default false) decide what the production gate does when no
 * {@code ProductionMarker} is registered: by default the agent does not become ready if a module FAILED (the module
 * that carries the marker may be the one that failed), and with {@code requireProductionMarker=true} it refuses.
 *
 * <p>Host safety (findings S20, S29): the host's calling thread only resolves options and creates the runtime's
 * objects and threads. The pid (read without a host-name lookup, see {@link JvmIds}), the start time, the token and
 * nonce, and the token file are resolved on {@code skillroy-start}, inside the start bound; the agent's own HTTP
 * listener means no {@code java.util.logging} initialisation at all. In JAVAAGENT mode the listener still binds
 * before {@code main}, so the {@code java.net} stack properties are read then; the agent records them and logs a
 * warning at {@code target.ready} if the application changed them afterwards.
 *
 * <p>Pools (finding S30): workers ({@code 2 * httpThreads + 8} threads) and CALLER threads ({@code httpThreads + 8})
 * are bounded; a call that finds its pool full, or whose primitive has an earlier timed-out call still running,
 * answers {@code BUSY}. Module {@code onTargetReady} and {@code uninstall} run on threads of their own, so a full pool
 * never keeps a module wired into the host.
 *
 * <p>Discovery properties (finding S9): on close, {@code port}, {@code nonce} and {@code tokenFile} get back the
 * values they had before the agent first published (cleared if they had none); {@code protocol}, {@code loadMode},
 * {@code discoveryFile} and {@code session} are cleared; nothing is touched if the agent never published. An
 * operator's fixed port or token file therefore survives a stop and a later start in the same JVM.
 *
 * <p>Nothing here initialises AWT (amendment A4): the start, close and readiness paths and the core primitives do
 * not touch it; only primitives that declare an EDT thread policy do, when they are called (see {@link Awt}).
 *
 * <p>Threading: {@link #start} runs the sequence on a {@code skillroy-start} thread and waits for it at most
 * {@link #START_TIMEOUT_MS}; module {@code install} runs there. If the wait ends early (timeout, interrupt), the
 * agent is closed first and the start thread interrupted; whatever the start thread still does sees the close and
 * undoes what it creates (finding S32), and the module loaders and the {@code onReleased} hook are released only once
 * both the close and the start thread have finished, so code still running from them can load its classes.
 * {@link #close} is idempotent and may be called from any thread; called from the event-dispatch thread or a thread a
 * {@code ThreadPolicyProvider} forbids, it returns at once and closes on a {@code skillroy-stop} thread. All agent
 * threads are daemon threads named {@code skillroy-*} whose context class loader is the host's loader (S34).
 */
public final class AgentRuntime implements AutoCloseable {

    /** Bound on the whole start sequence (module installs included). */
    public static final long START_TIMEOUT_MS = 60000L;
    /** Test seam: {@link #START_TIMEOUT_MS}, shortened by tests of a start that overruns. */
    static volatile long startTimeoutMs = START_TIMEOUT_MS;
    /** Threads beyond the pools' proportional share, for nested calls and lifecycle work (finding S30). */
    static final int POOL_HEADROOM = 8;
    static final long READY_POLL_MS = 250L;
    static final long ON_READY_TIMEOUT_MS = 60000L;
    static final long UNINSTALL_TIMEOUT_MS = 10000L;
    static final long STOP_GRACE_MS = 2000L;
    static final long CLOSE_WAIT_MS = 30000L;
    /** Upper bound of the readyTimeoutMs option (one day). */
    static final long MAX_READY_TIMEOUT_MS = 86400000L;
    /** Default of the httpThreads option: the most threads the control server's executor may have. */
    static final int DEFAULT_HTTP_THREADS = 32;
    static final int MIN_HTTP_THREADS = 2;
    static final int MAX_HTTP_THREADS = 1024;
    /**
     * Default of the httpIdleTimeoutMs option: two minutes, clearly longer than the JDK HttpClient's 30 s pool timeout
     * for idle connections, so that the controller, not the agent, closes an idle keep-alive connection.
     */
    static final long DEFAULT_HTTP_IDLE_TIMEOUT_MS = 120000L;
    static final long MIN_HTTP_IDLE_TIMEOUT_MS = 100L;
    /** Upper bound of the httpIdleTimeoutMs option (one hour): an idle connection holds one of httpThreads threads. */
    static final long MAX_HTTP_IDLE_TIMEOUT_MS = 3600000L;
    static final String TARGET_READY = "target.ready";
    static final String JVM_INSTRUMENTATION = "jvm.instrumentation";
    private static final String PROP_ERROR = Protocol.PROP_PREFIX + "error";
    private static final String[] DISCOVERY_PROPERTIES = {
        "port", "nonce", "tokenFile", "protocol", "loadMode", "discoveryFile", "session"
    };
    /** Discovery properties that are also operator options: restored on close, not cleared (finding S9). */
    private static final String[] OPERATOR_PROPERTIES = {"port", "nonce", "tokenFile"};
    /** Read once by the JDK's network stack, which binding the listener before {@code main} initialises (S29). */
    private static final String[] NETWORK_PROPERTIES = {"java.net.preferIPv4Stack", "java.net.preferIPv6Addresses"};
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile String agentVersion;

    private final Map<String, String> options;
    private final LoadMode loadMode;
    private final RingLog log = new RingLog();
    private final ThreadGroup group;
    private final ClassLoader hostLoader;
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor http;
    private final ThreadPoolExecutor calls;
    private final int httpThreads;
    private final Redactor redactor;
    private final CoreContext ctx;
    private final long startNanos = System.nanoTime();
    private final boolean tokenGenerated;
    private final boolean discoveryDirDefaulted;
    // Resolved on the start thread (findings S20, S29), before anything reads them.
    private volatile long pid = -1L;
    private volatile long startTimeMs;
    private volatile String token;
    private volatile String nonce;
    /** This start's session id (hardening item H2); set on the start thread before the control server opens. */
    private volatile String sessionId;
    private volatile String discoveryDir;
    private volatile String tokenFile;
    private volatile boolean tokenFileOwned;
    private volatile Map<String, String> networkAtStart;
    private final Object lifecycle = new Object();
    /** The operator values of {@link #OPERATOR_PROPERTIES} before the first publish; guarded by lifecycle (S9). */
    private Map<String, String> propertiesBeforePublish;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch closeSignal = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    /** Counted down when the start thread has ended (or never started). */
    private final CountDownLatch startEnded = new CountDownLatch(1);
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean disableRequested = new AtomicBoolean();
    private final AtomicReference<Runnable> closedHook = new AtomicReference<Runnable>();
    private final AtomicLong localIds = new AtomicLong();
    private volatile Thread closer;
    private volatile boolean ready;
    private volatile String readinessReason = "starting";
    private volatile ControlServer server;
    private volatile Dispatcher dispatcher;
    private volatile List<ModuleLoader.Record> modules = Collections.emptyList();
    private final List<ModuleLoader.Record> installOrder = new CopyOnWriteArrayList<ModuleLoader.Record>();
    private volatile ModuleLoader moduleLoader;
    private volatile String discoveryFile;
    private volatile Thread shutdownHook;

    /**
     * Creates the runtime's objects on the host's calling thread: nothing here does I/O, a name lookup or anything
     * else that could block (findings S20, S29); identity, token and files are resolved later on the start thread.
     */
    private AgentRuntime(Map<String, String> options, Instrumentation inst, ClassLoader hostLoader, LoadMode loadMode,
                         Object hostContext, ThreadGroup group, boolean discoveryDirDefaulted) {
        this.options = options;
        this.loadMode = loadMode;
        this.group = group;
        this.hostLoader = hostLoader;
        this.discoveryDirDefaulted = discoveryDirDefaulted;
        this.httpThreads = Integer.parseInt(options.get("httpThreads"));
        // Bounded (finding S30): with headroom for nested calls (a primitive that calls WORKER, a capability probe).
        this.workers = DaemonThreads.boundedPool(group, "skillroy-worker-", 2 * httpThreads + POOL_HEADROOM, log,
                hostLoader);
        // Bounded: every connection owns a thread, which it needs before it can authenticate (contract amendment A2,
        // hardening item H1). The control server decides what happens to a connection that finds the pool full.
        this.http = DaemonThreads.boundedPool(group, "skillroy-http-", httpThreads, log, hostLoader);
        // CALLER primitives run here so that their timeout can be enforced. Reached only by authenticated requests;
        // a call that timed out may still hold its thread, which the dispatcher's abandoned-call gate caps at one per
        // primitive, and the bound caps in total.
        this.calls = DaemonThreads.boundedPool(group, "skillroy-http-call-", httpThreads + POOL_HEADROOM, log,
                hostLoader);
        String givenToken = options.get("token");
        this.tokenGenerated = givenToken == null || givenToken.isEmpty();
        RingEventBus bus = new RingEventBus(Integer.parseInt(options.get("eventRing")),
                Long.parseLong(options.get("eventRingChars")), log);
        CoreSpiRegistry spi = new CoreSpiRegistry();
        this.redactor = Redactor.withExtraKeys(options.get("redact"), spi, log);
        CoreThreadBroker broker = new CoreThreadBroker(workers, spi, log);
        CoreCapabilityRegistry capabilities = new CoreCapabilityRegistry(log);
        this.ctx = new CoreContext(this, inst, loadMode, options, hostLoader, hostContext,
                new CorePrimitiveRegistry(), bus, new WeakHandleRegistry(), broker, capabilities, spi, log,
                "true".equalsIgnoreCase(options.get("readOnly")), redactor);
        capabilities.attach(broker, ctx);
    }

    /**
     * Starts the agent; as {@link #start(Map, Instrumentation, ClassLoader, String, Object, Runnable)} without a
     * release hook.
     */
    public static AgentRuntime start(Map<String, String> options, Instrumentation instOrNull, ClassLoader hostLoader,
                                     String loadMode, Object hostContextOrNull) throws Exception {
        return start(options, instOrNull, hostLoader, loadMode, hostContextOrNull, null);
    }

    /**
     * Starts the agent.
     *
     * @param options merged options (the bootstrap merges system properties, the options file and the agent
     *     arguments); missing keys take the defaults of phase1-contracts.md 2.4. Unknown keys are kept and visible
     *     to modules through {@link AgentContext#options()}.
     * @param instOrNull the JVM's Instrumentation, null in EMBEDDED mode
     * @param hostLoader the loader of the target application's classes (null: the system loader)
     * @param loadMode {@code EMBEDDED}, {@code JAVAAGENT} or {@code ATTACH}
     * @param hostContextOrNull what the host passed to {@code EmbeddedAgent.start}
     * @param onReleased run exactly once when no agent code runs any more (finding S32): at once when the start
     *     fails before the start thread exists, otherwise once the agent has closed and the start thread has ended,
     *     however the start ends. The bootstrap releases the agent class loader in it. May be null.
     * @return the running agent
     * @throws IllegalStateException if {@code enabled} is not {@code true} (nothing is started) or the start sequence
     *     does not finish within {@link #START_TIMEOUT_MS}
     * @throws IllegalArgumentException for an invalid option value or load mode
     * @throws Exception whatever made the start fail (the partly started agent is closed first)
     */
    public static AgentRuntime start(Map<String, String> options, Instrumentation instOrNull, ClassLoader hostLoader,
                                     String loadMode, Object hostContextOrNull, Runnable onReleased)
            throws Exception {
        final AgentRuntime runtime;
        try {
            Map<String, String> resolved = resolveOptions(options);
            if (!"true".equalsIgnoreCase(resolved.get("enabled"))) {
                throw new IllegalStateException("the skillroy agent is not enabled (enabled=true is required)");
            }
            if (loadMode == null) {
                throw new IllegalArgumentException("loadMode must not be null");
            }
            LoadMode mode = LoadMode.valueOf(loadMode.trim().toUpperCase(Locale.ROOT));
            ClassLoader host = hostLoader != null ? hostLoader : ClassLoader.getSystemClassLoader();
            ThreadGroup group = new ThreadGroup("skillroy");
            group.setDaemon(false);
            String givenDir = options == null ? null : options.get("discoveryDir");
            boolean defaulted = givenDir == null || givenDir.trim().isEmpty();
            runtime = new AgentRuntime(resolved, instOrNull, host, mode, hostContextOrNull, group, defaulted);
        } catch (Throwable t) {
            runQuietly(onReleased);
            throw t;
        }
        runtime.closedHook.set(onReleased);
        final FutureTask<Void> task = new FutureTask<Void>(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                runtime.startUp();
                return null;
            }
        });
        Thread thread;
        try {
            thread = DaemonThreads.named(runtime.group, "skillroy-start", new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } finally {
                        runtime.startEnded.countDown();
                        runtime.releaseWhenQuiet();
                    }
                }
            }, runtime.log, runtime.hostLoader);
            thread.start();
        } catch (Throwable t) {
            runtime.startEnded.countDown();
            runtime.close("start failed");
            throw t;
        }
        long timeoutMs = startTimeoutMs;
        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS);
            return runtime;
        } catch (TimeoutException e) {
            runtime.log.warn("start did not finish within " + timeoutMs + " ms");
            runtime.close("start timed out");
            thread.interrupt();
            throw new IllegalStateException("agent start did not finish within " + timeoutMs + " ms");
        } catch (ExecutionException e) {
            runtime.close("start failed");
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        } catch (InterruptedException e) {
            // Close first, so that the start thread sees the close at its next check, then interrupt it; no join.
            runtime.close("start interrupted");
            thread.interrupt();
            throw e;
        }
    }

    private static void runQuietly(Runnable hook) {
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable ignored) {
                // The bootstrap's hook does not throw; nothing may reach the host.
            }
        }
    }

    /** Defaults plus the given options; validates the values the core interprets. */
    static Map<String, String> resolveOptions(Map<String, String> given) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("port", "0");
        out.put("readOnly", "false");
        out.put("discoveryDir", SecureFiles.defaultDiscoveryDir().getPath());
        out.put("readyTimeoutMs", "120000");
        out.put("eventRing", "10000");
        out.put("eventRingChars", String.valueOf(RingEventBus.DEFAULT_MAX_CHARS));
        out.put("httpThreads", String.valueOf(DEFAULT_HTTP_THREADS));
        out.put("httpIdleTimeoutMs", String.valueOf(DEFAULT_HTTP_IDLE_TIMEOUT_MS));
        out.put("allowFailedModules", "false");
        out.put("requireProductionMarker", "false");
        if (given != null) {
            for (Map.Entry<String, String> entry : given.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(entry.getKey().trim(), entry.getValue().trim());
                }
            }
        }
        long port = longOption(out, "port", 0L, 65535L);
        out.put("port", String.valueOf(port));
        longOption(out, "readyTimeoutMs", 1L, MAX_READY_TIMEOUT_MS);
        longOption(out, "eventRing", 1L, 1000000L);
        // Security fix S43 (P1 2.4): the ring's total payload characters, 1 Mi to 1 Gi.
        longOption(out, "eventRingChars", 1L << 20, 1L << 30);
        longOption(out, "httpThreads", MIN_HTTP_THREADS, MAX_HTTP_THREADS);
        longOption(out, "httpIdleTimeoutMs", MIN_HTTP_IDLE_TIMEOUT_MS, MAX_HTTP_IDLE_TIMEOUT_MS);
        Redactor.withExtraKeys(out.get("redact"));
        booleanOption(out, "readOnly");
        booleanOption(out, "allowFailedModules");
        booleanOption(out, "requireProductionMarker");
        if (out.get("discoveryDir").isEmpty()) {
            throw new IllegalArgumentException("option discoveryDir must not be empty");
        }
        return Collections.unmodifiableMap(out);
    }

    private static void booleanOption(Map<String, String> options, String key) {
        String value = options.get(key);
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("option " + key + " must be true or false: " + value);
        }
    }

    private static long longOption(Map<String, String> options, String key, long min, long max) {
        String text = options.get(key);
        try {
            long value = Long.parseLong(text);
            if (value < min || value > max) {
                throw new IllegalArgumentException("option " + key + " must be between " + min + " and " + max
                        + ": " + text);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + key + " must be an integer: " + text);
        }
    }

    private void startUp() throws Exception {
        resolveIdentity();
        log.info("starting: loadMode=" + loadMode + " pid=" + pid + " java=" + System.getProperty("java.version"));
        IOException directoryProblem = prepareDiscoveryDirectory();
        if (directoryProblem == null) {
            SecureFiles.deleteStaleOwnFiles(new File(discoveryDir).toPath(), pid, startTimeMs, log);
        }
        if (tokenFileOwned && tokenGenerated) {
            // Checked first, so that an unsafe directory stops the start before a port opens or a module installs.
            File parent = new File(tokenFile).getAbsoluteFile().getParentFile();
            try {
                if (parent.equals(new File(discoveryDir).getAbsoluteFile())) {
                    if (directoryProblem != null) {
                        throw directoryProblem;
                    }
                } else {
                    SecureFiles.prepareDirectory(parent.toPath());
                }
            } catch (IOException e) {
                throw new IOException("cannot write the token file " + tokenFile + ": " + reason(e));
            }
        }
        String argsWarning = options.get("argsWarning");
        if (argsWarning != null) {
            log.warn(argsWarning);
        }
        if (ctx.instrumentation() != null) {
            ctx.capabilities.set(JVM_INSTRUMENTATION, CapabilityState.OK, null, "Instrumentation");
        } else {
            ctx.capabilities.set(JVM_INSTRUMENTATION, CapabilityState.UNAVAILABLE,
                    "no Instrumentation in " + loadMode + " mode", null);
        }
        ctx.capabilities.markPending(TARGET_READY, "waiting for readiness");

        // A new session id at every start, never configurable: nothing captured from an earlier instance with the
        // same token is valid against this one (H2). Computing one MAC here makes a JVM whose crypto provider does not
        // load fail the start, rather than every request.
        String session = Auth.newSessionId();
        Auth.helloMac(token, session);
        sessionId = session;
        Dispatcher d = new Dispatcher(this, ctx, calls);
        ControlServer s = new ControlServer(this, ctx, d, token, session, http, httpThreads, group,
                Long.parseLong(options.get("httpIdleTimeoutMs")));
        if (loadMode == LoadMode.JAVAAGENT) {
            networkAtStart = snapshot(NETWORK_PROPERTIES);
        }
        // A start that has already lost (closed by a timeout or an interrupt) must not bind (S32).
        checkOpen();
        s.start(Integer.parseInt(options.get("port")));
        try {
            dispatcher = d;
            // Published before the check below: close() either sees the server and stops it, or this check sees the
            // close; the catch stops the server in that case.
            server = s;
            log.info("control server on 127.0.0.1:" + s.port());
            checkOpen();
            servedStartUp(s);
        } catch (Throwable t) {
            s.stop(0L);
            throw t;
        }
    }

    /** The rest of the start once the control server listens; any failure stops the server. */
    private void servedStartUp(ControlServer s) throws Exception {
        new CorePrimitives(this, ctx, redactor).registerAll();

        ModuleLoader loader = new ModuleLoader(log);
        moduleLoader = loader;
        List<ModuleLoader.Record> found = loader.discover(options, AgentRuntime.class.getClassLoader());
        modules = Collections.unmodifiableList(found);
        loader.installAll(found, new ModuleLoader.Installer() {
            @Override
            public void install(ModuleLoader.Record record) {
                installModule(record);
            }
        });
        checkOpen();

        if (tokenFileOwned) {
            // Under the lifecycle lock with a close check, so a close that already retracted discovery is never
            // followed by a token file nobody deletes (S32).
            synchronized (lifecycle) {
                checkOpen();
                try {
                    SecureFiles.writeOwnerOnly(new File(tokenFile).toPath(), token.getBytes(UTF8));
                } catch (IOException e) {
                    String message = "cannot write the token file " + tokenFile + ": " + reason(e);
                    if (tokenGenerated) {
                        // Nobody could ever authenticate: refuse to start. The reason is in the message already.
                        throw new IOException(message);
                    }
                    // The controller supplied the token and knows it; the file is only a convenience.
                    log.warn(message);
                    reportError(message);
                }
            }
        }
        publishDiscovery();
        addShutdownHook();
        checkOpen();

        Map<String, Object> started = new LinkedHashMap<String, Object>();
        started.put("pid", Long.valueOf(pid));
        started.put("port", Long.valueOf(s.port()));
        started.put("loadMode", loadMode.name());
        started.put("protocol", Protocol.VERSION);
        List<Object> installed = new ArrayList<Object>();
        for (ModuleLoader.Record record : found) {
            if (record.installed()) {
                installed.add(record.id);
            }
        }
        started.put("modules", installed);
        ctx.events.publish(Topics.AGENT_STARTED, "core", started);
        log.info("started; modules " + installed);

        DaemonThreads.named(group, "skillroy-ready", new Runnable() {
            @Override
            public void run() {
                awaitReadiness();
            }
        }, log, hostLoader).start();
    }

    /**
     * Resolves what the constructor must not touch on the host's thread (findings S20, S29): the pid without a host
     * name lookup, the start time, the token and nonce (a generated token needs {@code SecureRandom}, which may wait
     * for entropy), the discovery directory and the token file.
     */
    private void resolveIdentity() {
        long id = JvmIds.pid();
        if (id <= 0) {
            throw new IllegalStateException("cannot determine the JVM pid");
        }
        pid = id;
        startTimeMs = JvmIds.startTimeMs();
        token = tokenGenerated ? SecureFiles.hex(32) : options.get("token");
        String givenNonce = options.get("nonce");
        nonce = givenNonce == null || givenNonce.isEmpty() ? SecureFiles.hex(16) : givenNonce;
        discoveryDir = new File(options.get("discoveryDir")).getAbsolutePath();
        if (discoveryDirDefaulted) {
            noteDefaultSubstitution();
        }
        resolveTokenFile();
    }

    private void resolveTokenFile() {
        String givenTokenFile = options.get("tokenFile");
        if (givenTokenFile != null && !givenTokenFile.isEmpty()) {
            tokenFile = new File(givenTokenFile).getAbsolutePath();
            tokenFileOwned = tokenGenerated;
        } else {
            tokenFile = new File(discoveryDir, baseName() + ".token").getAbsolutePath();
            tokenFileOwned = true;
        }
    }

    /** Logs and records (S8) that the default directory is the home fall-back because another user holds (a). */
    private void noteDefaultSubstitution() {
        Path b = SecureFiles.homeCandidate(System.getProperty("user.home"));
        if (b == null || !b.toAbsolutePath().normalize().equals(new File(discoveryDir).toPath().normalize())) {
            return;
        }
        Path a = SecureFiles.tmpCandidate(System.getProperty("java.io.tmpdir"), System.getProperty("user.name"))
                .toAbsolutePath();
        String owner;
        try {
            owner = Files.getOwner(a, LinkOption.NOFOLLOW_LINKS).getName();
        } catch (IOException | RuntimeException e) {
            owner = "another user";
        }
        String message = "default discovery directory " + a + " is owned by " + owner + "; using " + discoveryDir;
        log.warn(message);
        reportError(message);
    }

    /**
     * Creates and checks the discovery directory. With the default directory, when candidate (a) cannot be used
     * because another user created it in the meantime (a lost race, findings S8/S19), switches to candidate (b) as
     * the controller does.
     *
     * @return null when the directory can be used, else why not
     */
    private IOException prepareDiscoveryDirectory() {
        try {
            SecureFiles.prepareDirectory(new File(discoveryDir).toPath());
            return null;
        } catch (IOException e) {
            if (!discoveryDirDefaulted) {
                return e;
            }
            Path a = new File(discoveryDir).toPath();
            Path b = SecureFiles.homeCandidate(System.getProperty("user.home"));
            if (b == null || a.equals(b.toAbsolutePath()) || !SecureFiles.occupiedByAnotherUser(a)) {
                return e;
            }
            discoveryDir = b.toAbsolutePath().toString();
            noteDefaultSubstitution();
            resolveTokenFile();
            try {
                SecureFiles.prepareDirectory(b);
                return null;
            } catch (IOException fallback) {
                return fallback;
            }
        }
    }

    private static Map<String, String> snapshot(String[] names) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String name : names) {
            try {
                values.put(name, System.getProperty(name));
            } catch (RuntimeException e) {
                values.put(name, null);
            }
        }
        return values;
    }

    private void checkOpen() {
        if (closing.get()) {
            throw new IllegalStateException("agent closed during start");
        }
    }

    private void installModule(ModuleLoader.Record record) {
        if (closing.get()) {
            record.fail("the agent closed during start");
            return;
        }
        ModuleContext moduleContext = new ModuleContext(ctx);
        try {
            record.module.install(moduleContext);
        } catch (Throwable t) {
            record.fail("install failed: " + ModuleLoader.describe(t));
            log.warn("module " + record.id + " install failed", t);
            try {
                record.module.uninstall();
            } catch (Throwable u) {
                log.warn("module " + record.id + " uninstall after failed install failed", u);
            }
            moduleContext.rollback();
            return;
        }
        record.context = moduleContext;
        record.state = "INSTALLED";
        installOrder.add(record);
        if (closing.get()) {
            // close() may already have walked installOrder: this module must not stay wired into the host.
            uninstallNow(record);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("id", record.id);
        payload.put("version", record.version);
        ctx.events.publish(Topics.MODULE_INSTALLED, "core", payload);
        log.info("module " + record.id + " " + record.version + " installed from " + record.origin);
    }

    /** The readiness sequence, on the skillroy-ready thread. */
    private void awaitReadiness() {
        try {
            long deadline = startNanos + TimeUnit.MILLISECONDS.toNanos(Long.parseLong(options.get("readyTimeoutMs")));
            boolean timedOut = false;
            while (true) {
                if (closing.get()) {
                    return;
                }
                List<String> waiting = pendingReadiness();
                if (waiting.isEmpty()) {
                    break;
                }
                String reason = "waiting for " + join(waiting);
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (!timedOut && remaining <= 0) {
                    // Reported once. The poll goes on: a target that becomes ready late is still served, and
                    // nothing that needs readiness (production markers, onTargetReady, probes) runs before it.
                    timedOut = true;
                    String timeout = "readiness timeout after " + options.get("readyTimeoutMs") + " ms; " + reason;
                    log.warn(timeout);
                    Map<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("ready", Boolean.FALSE);
                    payload.put("reason", timeout);
                    ctx.events.publish(Topics.TARGET_READY, "core", payload);
                }
                if (timedOut) {
                    reason = "readiness timeout after " + options.get("readyTimeoutMs") + " ms; " + reason;
                    ctx.capabilities.set(TARGET_READY, CapabilityState.UNAVAILABLE, reason, null);
                } else {
                    ctx.capabilities.markPending(TARGET_READY, reason);
                }
                readinessReason = reason;
                long waitMs = timedOut ? READY_POLL_MS : Math.min(READY_POLL_MS, remaining);
                if (closeSignal.await(waitMs, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
            readinessReason = "checking production markers";
            List<ProductionMarker> markers = ctx.spi.all(ProductionMarker.class);
            if (markers.isEmpty() && !productionGateWithoutMarker()) {
                return;
            }
            for (ProductionMarker marker : markers) {
                String reason;
                try {
                    reason = marker.productionReason();
                } catch (Throwable t) {
                    reason = "ProductionMarker " + marker.getClass().getName() + " failed: " + ModuleLoader.describe(t);
                }
                if (reason != null) {
                    refuse(reason);
                    return;
                }
            }
            readinessReason = "running onTargetReady";
            for (ModuleLoader.Record record : installOrder) {
                if (closing.get()) {
                    return;
                }
                targetReady(record);
            }
            if (closing.get()) {
                return;
            }
            readinessReason = "probing capabilities";
            ctx.capabilities.probeAll();
            if (closing.get()) {
                return;
            }
            warnIfNetworkPropertiesChanged();
            ctx.capabilities.set(TARGET_READY, CapabilityState.OK, null, null);
            ready = true;
            readinessReason = null;
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("ready", Boolean.TRUE);
            payload.put("waitedMs", Long.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)));
            ctx.events.publish(Topics.TARGET_READY, "core", payload);
            log.info("target ready");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.error("readiness sequence failed", t);
            readinessReason = "readiness failed: " + ModuleLoader.describe(t);
            ctx.capabilities.set(TARGET_READY, CapabilityState.UNAVAILABLE, readinessReason, null);
        }
    }

    /**
     * The production gate when no {@code ProductionMarker} is registered (finding S14). With
     * {@code requireProductionMarker=true} the agent refuses. Otherwise, if a module FAILED (the module that carries
     * the marker may be the one that failed, so "no marker" cannot be trusted) and {@code allowFailedModules} is not
     * true, the target is never declared ready: mutating primitives keep answering NOT_READY with the reason, and
     * read-only ones stay available so that {@code agent.info} shows which module failed. Else it serves with a
     * warning, as before.
     *
     * @return true if the readiness sequence may continue
     */
    private boolean productionGateWithoutMarker() {
        String failed = failedModules();
        if ("true".equalsIgnoreCase(options.get("requireProductionMarker"))) {
            refuse("no ProductionMarker registered (requireProductionMarker=true)"
                    + (failed.isEmpty() ? "" : "; FAILED modules: " + failed));
            return false;
        }
        if (!failed.isEmpty() && !"true".equalsIgnoreCase(options.get("allowFailedModules"))) {
            String reason = "production gate not evaluated: no ProductionMarker registered and module(s) " + failed
                    + " FAILED (set allowFailedModules=true to serve anyway)";
            log.error(reason, null);
            readinessReason = reason;
            ctx.capabilities.set(TARGET_READY, CapabilityState.UNAVAILABLE, reason, null);
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("ready", Boolean.FALSE);
            payload.put("reason", reason);
            ctx.events.publish(Topics.TARGET_READY, "core", payload);
            reportError(reason);
            return false;
        }
        log.warn("no ProductionMarker registered: nothing guards against a production target");
        return true;
    }

    /** {@code "id: message; id: message"} of every FAILED module record, or "". */
    private String failedModules() {
        StringBuilder sb = new StringBuilder();
        for (ModuleLoader.Record record : modules) {
            if ("FAILED".equals(record.state)) {
                sb.append(sb.length() == 0 ? "" : "; ").append(record.id).append(": ").append(record.message);
            }
        }
        return sb.toString();
    }

    /**
     * Finding S29: in JAVAAGENT mode the listener binds before {@code main}, which fixes the JDK's reading of the
     * network-stack properties. An application that sets one of them itself later silently gets the earlier value;
     * say so in the log.
     */
    private void warnIfNetworkPropertiesChanged() {
        Map<String, String> before = networkAtStart;
        if (before == null) {
            return;
        }
        Map<String, String> now = snapshot(NETWORK_PROPERTIES);
        for (Map.Entry<String, String> e : before.entrySet()) {
            String later = now.get(e.getKey());
            if (later == null ? e.getValue() != null : !later.equals(e.getValue())) {
                log.warn("the application set " + e.getKey() + " to " + later + " after the agent had started the "
                        + "network stack with " + e.getValue() + " (JAVAAGENT starts before main): the new value has "
                        + "no effect; pass it as -D" + e.getKey() + "=... on the command line instead");
            }
        }
    }

    private List<String> pendingReadiness() {
        List<String> waiting = new ArrayList<String>();
        for (ReadinessSource source : ctx.spi.all(ReadinessSource.class)) {
            String id = source.getClass().getName();
            try {
                id = source.id();
                if (!source.isReady()) {
                    waiting.add(id + " (" + source.describe() + ")");
                }
            } catch (Throwable t) {
                waiting.add(id + " (failed: " + ModuleLoader.describe(t) + ")");
            }
        }
        return waiting;
    }

    /** Calls the module's {@code onTargetReady} on a thread of its own (not a pool thread, S30), bounded. */
    private void targetReady(final ModuleLoader.Record record) {
        try {
            onOwnThread("skillroy-on-ready-" + record.id, new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    // The record's monitor keeps uninstall out until this returns, and this out after uninstall.
                    synchronized (record) {
                        if (record.installed() && !closing.get()) {
                            record.module.onTargetReady(record.context);
                        }
                    }
                    return null;
                }
            }, ON_READY_TIMEOUT_MS);
        } catch (TimeoutException e) {
            record.readyMessage = "onTargetReady did not finish within " + ON_READY_TIMEOUT_MS + " ms";
            log.warn("module " + record.id + " " + record.readyMessage);
        } catch (Throwable t) {
            record.readyMessage = "onTargetReady failed: " + ModuleLoader.describe(t);
            log.warn("module " + record.id + " onTargetReady failed", t);
        }
    }

    /**
     * Runs lifecycle work on a new agent thread and waits at most {@code timeoutMs}; on timeout the thread is
     * interrupted and left to end on its own. Lifecycle work never competes with primitives for a pool thread, so a
     * pool full of stuck calls cannot keep a module from being wired or unwired (finding S30).
     *
     * @throws TimeoutException when the wait expires
     * @throws Exception what the task threw
     */
    private <T> T onOwnThread(String name, Callable<T> work, long timeoutMs) throws Exception {
        FutureTask<T> task = new FutureTask<T>(work);
        DaemonThreads.named(group, name, task, log, hostLoader).start();
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
    }

    private void refuse(String reason) {
        String message = "production marker: " + reason;
        log.error("refusing to serve: " + message, null);
        try {
            System.setProperty(PROP_ERROR, "refused: " + message);
        } catch (RuntimeException ignored) {
            // Diagnostics only.
        }
        close(message);
    }

    /** Writes the discovery file and sets the discovery system properties. Safe to call again (re-attach). */
    public void republishDiscovery() {
        publishDiscovery();
    }

    private void publishDiscovery() {
        synchronized (lifecycle) {
            ControlServer s = server;
            if (closing.get() || s == null) {
                return;
            }
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("pid", Long.valueOf(pid));
            json.put("startTimeMs", Long.valueOf(startTimeMs));
            json.put("port", Long.valueOf(s.port()));
            json.put("tokenFile", tokenFile);
            json.put("nonce", nonce);
            json.put(Protocol.KEY_SESSION_ID, sessionId);
            json.put("protocol", Protocol.VERSION);
            json.put("loadMode", loadMode.name());
            json.put("mainCommand", JvmIds.mainCommand());
            File file = new File(discoveryDir, baseName() + ".json").getAbsoluteFile();
            try {
                SecureFiles.writeOwnerOnly(file.toPath(), (Json.write(json) + "\n").getBytes(UTF8));
                discoveryFile = file.getPath();
            } catch (IOException | RuntimeException e) {
                // The agent serves on: a controller that launched or attached it reads the system properties.
                String message = "cannot write the discovery file " + file + ": " + reason(e);
                log.warn(message);
                reportError(message);
            }
            if (propertiesBeforePublish == null) {
                // The operator's own values (finding S9), restored on close; a re-publish keeps the first snapshot.
                propertiesBeforePublish = snapshot(prefixed(OPERATOR_PROPERTIES));
            }
            System.setProperty(Protocol.PROP_PREFIX + "port", String.valueOf(s.port()));
            System.setProperty(Protocol.PROP_PREFIX + "nonce", nonce);
            System.setProperty(Protocol.PROP_SESSION, sessionId);
            System.setProperty(Protocol.PROP_PREFIX + "tokenFile", tokenFile);
            System.setProperty(Protocol.PROP_PREFIX + "protocol", Protocol.VERSION);
            System.setProperty(Protocol.PROP_PREFIX + "loadMode", loadMode.name());
            if (discoveryFile != null) {
                System.setProperty(Protocol.PROP_PREFIX + "discoveryFile", discoveryFile);
            }
        }
    }

    /** A file problem in one phrase: the message of a plain IOException, else the exception's class and message. */
    private static String reason(Exception e) {
        return e.getClass() == IOException.class && e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /** Records a problem that does not stop the agent in {@code skillroy.agent.error}. */
    private void reportError(String message) {
        try {
            System.setProperty(PROP_ERROR, message);
        } catch (RuntimeException ignored) {
            // Diagnostics only; the agent log has the message.
        }
    }

    private void retractDiscovery() {
        synchronized (lifecycle) {
            SecureFiles.deleteQuietly(discoveryFile);
            if (tokenFileOwned) {
                SecureFiles.deleteQuietly(tokenFile);
            }
            ControlServer s = server;
            Map<String, String> before = propertiesBeforePublish;
            // Only what this agent published: never the operator's properties of an agent that did not publish.
            if (before != null && s != null
                    && String.valueOf(s.port()).equals(System.getProperty(Protocol.PROP_PREFIX + "port"))) {
                for (String name : DISCOVERY_PROPERTIES) {
                    String key = Protocol.PROP_PREFIX + name;
                    String prior = before.get(key);   // null for protocol, loadMode, discoveryFile, session
                    if (prior != null) {
                        System.setProperty(key, prior);
                    } else {
                        System.clearProperty(key);
                    }
                }
                propertiesBeforePublish = null;
            }
        }
    }

    private static String[] prefixed(String[] names) {
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = Protocol.PROP_PREFIX + names[i];
        }
        return out;
    }

    private void addShutdownHook() {
        final String discovery = discoveryFile;
        final String ownedToken = tokenFileOwned ? tokenFile : null;
        Thread hook = DaemonThreads.named(group, "skillroy-shutdown", new Runnable() {
            @Override
            public void run() {
                SecureFiles.deleteQuietly(discovery);
                SecureFiles.deleteQuietly(ownedToken);
            }
        }, log, hostLoader);
        synchronized (lifecycle) {
            if (closing.get()) {
                return;   // close() has already removed whatever hook there was (S32)
            }
            try {
                Runtime.getRuntime().addShutdownHook(hook);
                shutdownHook = hook;
            } catch (IllegalStateException | SecurityException e) {
                log.warn("cannot register the shutdown hook", e);
            }
        }
    }

    private void removeShutdownHook() {
        synchronized (lifecycle) {
            Thread hook = shutdownHook;
            shutdownHook = null;
            if (hook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException | SecurityException ignored) {
                    // The JVM is already shutting down; the hook deletes the files itself.
                }
            }
        }
    }

    /** Asks for {@code agent.disable}'s shutdown once the current response has been sent. */
    void requestDisable() {
        disableRequested.set(true);
    }

    /** Called after every response: starts a pending disable on a {@code skillroy-stop} thread. */
    void afterResponse() {
        if (disableRequested.compareAndSet(true, false)) {
            stopAsync("agent.disable");
        }
    }

    private void stopAsync(final String reason) {
        try {
            DaemonThreads.named(group, "skillroy-stop", new Runnable() {
                @Override
                public void run() {
                    close(reason);
                }
            }, log, hostLoader).start();
        } catch (Throwable t) {
            log.warn("cannot start the stop thread; closing inline", t);
            close(reason);
        }
    }

    /**
     * Stops the agent: uninstalls modules in reverse order, publishes {@code agent.stopping}, stops the server,
     * deletes the discovery file and the generated token file, clears the discovery system properties and shuts the
     * thread pools down. Idempotent; a second caller waits (bounded) for the first to finish. The application keeps
     * running. Called on the event-dispatch thread or on a thread a {@code ThreadPolicyProvider} forbids, it returns
     * at once and the agent closes on a {@code skillroy-stop} thread: those threads must never wait for agent work.
     */
    @Override
    public void close() {
        if (onEventDispatchThread() || ctx.threads.isForbiddenThread(Thread.currentThread())) {
            stopAsync("closed");
            return;
        }
        close("closed");
    }

    /**
     * True on an AWT event-dispatch thread. The name test comes first (and {@link Awt#EDT_PREFIX} is a constant
     * inlined by the compiler), so closing from any other thread does not even load {@link Awt}.
     */
    private static boolean onEventDispatchThread() {
        return Thread.currentThread().getName().startsWith(Awt.EDT_PREFIX) && Awt.onEventDispatchThread();
    }

    void close(String reason) {
        if (!closing.compareAndSet(false, true)) {
            if (Thread.currentThread() != closer) {
                try {
                    closed.await(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }
        closer = Thread.currentThread();
        try {
            ready = false;
            readinessReason = "agent is stopping";
            closeSignal.countDown();
            log.info("stopping: " + reason);
            List<ModuleLoader.Record> installed = new ArrayList<ModuleLoader.Record>(installOrder);
            Collections.reverse(installed);
            for (ModuleLoader.Record record : installed) {
                if (record.installed()) {
                    uninstall(record);
                }
            }
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("reason", reason);
            ctx.events.publish(Topics.AGENT_STOPPING, "core", payload);
            ControlServer s = server;
            if (s != null) {
                s.stop(STOP_GRACE_MS);
            }
            retractDiscovery();
            removeShutdownHook();
            workers.shutdownNow();
            calls.shutdownNow();
            http.shutdownNow();
            awaitTermination(workers);
            awaitTermination(calls);
            awaitTermination(http);
            if (startEnded.getCount() != 0) {
                log.warn("the start thread is still running; the module and agent loaders are released when it ends");
            }
            log.info("stopped");
        } catch (Throwable t) {
            log.warn("close failed", t);
        } finally {
            closed.countDown();
            releaseWhenQuiet();
        }
    }

    /**
     * Once both the close and the start thread have finished (finding S32), and only once: closes the module loaders
     * and runs the close hook (the bootstrap releases the agent loader there). Earlier, code still running from those
     * loaders, a slow module install that outlived a start timeout for example, could fail to load its classes.
     */
    private void releaseWhenQuiet() {
        if (closed.getCount() != 0 || startEnded.getCount() != 0 || !released.compareAndSet(false, true)) {
            return;
        }
        ModuleLoader loader = moduleLoader;
        if (loader != null) {
            for (ModuleClassLoader moduleClassLoader : loader.loaders()) {
                ModuleLoader.closeQuietly(moduleClassLoader);
            }
        }
        runClosedHook();
    }

    /**
     * Registers what must happen once the agent has finished closing, however it closes ({@link #close}, a close
     * handed to the {@code skillroy-stop} thread, {@code agent.disable}, a production marker). The bootstrap uses it
     * to release the agent class loader's jars only after the last agent code has run: closing the loader earlier
     * would break a shutdown that is still in progress on another thread.
     *
     * <p>The hook runs once, when the agent has closed and its start thread has ended (on whichever of the two
     * threads finishes last), or at once on the calling thread if that has already happened. A later call replaces a
     * hook that has not run yet, including the {@code onReleased} hook given to {@link #start}. The bootstrap also
     * forgets the runtime in it (finding S31), so that a closed agent's loaders and objects become collectable.
     */
    public void onClosed(Runnable hook) {
        closedHook.set(hook);
        if (released.get()) {
            runClosedHook();
        }
    }

    private void runClosedHook() {
        Runnable hook = closedHook.getAndSet(null);
        if (hook != null) {
            try {
                hook.run();
            } catch (Throwable t) {
                log.warn("the close hook failed", t);
            }
        }
    }

    /**
     * Uninstalls a module on a thread of its own (S30), waiting at most {@link #UNINSTALL_TIMEOUT_MS}; then drops its
     * registrations, capabilities included (A18).
     */
    private void uninstall(final ModuleLoader.Record record) {
        try {
            onOwnThread("skillroy-uninstall-" + record.id, new Callable<Void>() {
                @Override
                public Void call() {
                    uninstallNow(record);
                    return null;
                }
            }, UNINSTALL_TIMEOUT_MS);
        } catch (TimeoutException e) {
            log.warn("module " + record.id + " uninstall did not finish within " + UNINSTALL_TIMEOUT_MS + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.warn("module " + record.id + " uninstall could not run", t);
        } finally {
            ModuleContext moduleContext = record.context;
            if (moduleContext != null) {
                moduleContext.rollback();
            }
        }
    }

    /**
     * Calls the module's {@code uninstall} exactly once, on the calling thread, after a running {@code onTargetReady}
     * of the same module has returned (a listener it adds must not outlive the uninstall that removes it); then
     * drops what the module registered. Never throws.
     */
    private void uninstallNow(ModuleLoader.Record record) {
        synchronized (record) {
            if (!record.installed()) {
                return;
            }
            record.state = "UNINSTALLED";
            try {
                record.module.uninstall();
            } catch (Throwable t) {
                log.warn("module " + record.id + " uninstall failed", t);
            }
        }
        ModuleContext moduleContext = record.context;
        if (moduleContext != null) {
            moduleContext.rollback();
        }
    }

    private void awaitTermination(ThreadPoolExecutor pool) {
        try {
            if (!pool.awaitTermination(1000L, TimeUnit.MILLISECONDS)) {
                log.warn("some agent threads are still running after shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * In-process JSON-RPC: runs one call through the full dispatcher (the same gates, thread policy and result
     * envelope as {@code POST /rpc}) without HTTP. For tests and embeddings that hold this object.
     *
     * @return the JSON-RPC response object ({@code {"jsonrpc","id","result"|"error"}})
     */
    public Map<String, Object> call(String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", "local-" + localIds.incrementAndGet());
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        Dispatcher d = dispatcher;
        try {
            if (d == null) {
                throw new IllegalStateException("agent not started");
            }
            return d.handleOne(request);
        } finally {
            afterResponse();
        }
    }

    /** The control server's port. */
    public int port() {
        ControlServer s = server;
        return s == null ? -1 : s.port();
    }

    /** Absolute path of the file holding the token (the key of the control channel's MACs). */
    public String tokenFile() {
        return tokenFile;
    }

    /** The nonce {@code /ping} echoes as {@code agentNonce}. */
    public String nonce() {
        return nonce;
    }

    /**
     * The session id of this start (hardening item H2): 32 lower-case hex characters, new at every start, published
     * in the discovery file and as {@link Protocol#PROP_SESSION}; null before the start sequence created it.
     */
    public String sessionId() {
        return sessionId;
    }

    /** Absolute path of the discovery file, or null if it could not be written. */
    public String discoveryFile() {
        return discoveryFile;
    }

    /** The agent context modules receive (core view); for in-process tests and embeddings. */
    public AgentContext context() {
        return ctx;
    }

    /**
     * True if a {@code ThreadPolicyProvider} forbids the thread: the agent must never block or run work on it. The
     * bootstrap asks this (reflectively) to decide whether a stop may wait for another start or stop (finding S33).
     * Never throws.
     */
    public boolean isForbiddenThread(Thread thread) {
        try {
            return ctx.threads.isForbiddenThread(thread);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True once {@link #close} has begun (including a self-close by agent.disable or a production marker). */
    public boolean isClosed() {
        return closing.get();
    }

    /** True once the target is ready, the production markers passed and the probes ran; false after close. */
    public boolean isReady() {
        return ready && !closing.get();
    }

    long pid() {
        return pid;
    }

    long startTimeMs() {
        return startTimeMs;
    }

    /** The control server, or null before it started (for tests). */
    ControlServer controlServer() {
        return server;
    }

    /**
     * {@code {workers:{active,max}, callers:{active,max}, abandoned:{<primitive>:n}}} for {@code agent.info}
     * (finding S30): how busy the bounded pools are and which primitives have a timed-out call still running.
     */
    Map<String, Object> threadsJson() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("workers", poolJson(workers));
        out.put("callers", poolJson(calls));
        Dispatcher d = dispatcher;
        out.put("abandoned", d == null ? new LinkedHashMap<String, Object>() : d.abandonedJson());
        return out;
    }

    private static Map<String, Object> poolJson(ThreadPoolExecutor pool) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("active", Long.valueOf(pool.getActiveCount()));
        out.put("max", Long.valueOf(pool.getMaximumPoolSize()));
        return out;
    }

    /** Why the target is not ready, or null when it is. */
    String readinessReason() {
        return readinessReason;
    }

    List<Object> modulesJson() {
        List<Object> out = new ArrayList<Object>();
        for (ModuleLoader.Record record : modules) {
            out.add(record.toJson());
        }
        return out;
    }

    private String baseName() {
        return "skillroy-" + pid + "-" + startTimeMs;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /** The agent version from the build ({@code agent.properties}), or "unknown". */
    static String agentVersion() {
        String version = agentVersion;
        if (version == null) {
            version = "unknown";
            InputStream in = AgentRuntime.class.getResourceAsStream("agent.properties");
            if (in != null) {
                try {
                    Properties props = new Properties();
                    props.load(in);
                    version = props.getProperty("agentVersion", "unknown");
                } catch (IOException ignored) {
                    // Keep "unknown".
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // Nothing to do.
                    }
                }
            }
            agentVersion = version;
        }
        return version;
    }

    @Override
    public String toString() {
        return "AgentRuntime{loadMode=" + loadMode + ", port=" + port() + ", ready=" + isReady() + ", closed="
                + isClosed() + "}";
    }
}
