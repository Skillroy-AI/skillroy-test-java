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

package ai.skillroy.testjava.agent.web.jcef;

import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.web.CdpEndpoint;
import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.agent.web.reflect.AdapterThreads;
import ai.skillroy.testjava.agent.web.reflect.Descriptor;
import ai.skillroy.testjava.agent.web.reflect.LoadedClasses;
import ai.skillroy.testjava.agent.web.reflect.ReflectiveApi;
import ai.skillroy.testjava.agent.web.reflect.ReflectiveException;
import ai.skillroy.testjava.agent.web.reflect.ResolvedApi;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in adapter for JCEF (phase4-contracts.md section 4.5): operations on {@code CefBrowser.getDevToolsClient()}
 * and nothing else (no debug port, no message router, no handler slot), every class and member reached by reflection
 * through the host's class loader and named by the descriptor {@value #DESCRIPTOR}; no JCEF type appears in its
 * signatures.
 *
 * <p><b>Probe.</b> The gate class {@code org.cef.CefApp} must already be loaded by the host ({@link LoadedClasses},
 * asked without loading it; a gate the host has not loaded counts as absent and nothing is loaded: "not present" when
 * the class file cannot even be found, else "present but not loaded by the application yet"); only then is the
 * descriptor resolved, which may load the descriptor's classes and the types their members' signatures and linking
 * need, but initialises none of them ({@code Class.forName(name, false, hostLoader)}). Without
 * {@code getDevToolsClient} and the DevTools client's members the result is UNAVAILABLE({@value #OLD_JCEF}).
 * {@code CefApp.getState()} is read by reflection (the gate's own state, the one static member the probe calls);
 * {@code CefApp.getInstance()} is never called (it would create CEF). The strategy is
 * {@code "jcef <Chromium major>"} from {@code getVersion()} of the existing instance when the state is
 * {@code INITIALIZED} (else {@code "jcef"}), with {@value #WINDOWED_JAVA8} added for windowed rendering on a Java 8
 * target (BS8; {@link #strategySuffix}). After {@link #detach()} the adapter is inert: its probe answers
 * UNAVAILABLE({@value #DETACHED}) and it lists no browser.
 *
 * <p><b>Enumeration.</b> Seeds first (a {@code CefBrowser}, a {@code CefClient} through {@code getAllBrowser()}, a
 * {@code CefApp} through its clients, a component equal to the {@code getUIComponent()} of a browser the adapter
 * already knows or inside it: JCEF has no component-to-browser accessor, so a component keeps a known browser listed
 * but finds no new one), then, only in state {@code INITIALIZED}, the walk {@code CefApp.self -> clients_ ->
 * getAllBrowser()}; a missing private member, a refused {@code setAccessible} or another state makes
 * {@link #enumerationState()} DEGRADED("seeds only: &lt;reason&gt;").
 * A browser that left the process-wide list while the walk works is marked closed. A seed that fails is skipped and
 * named in {@link #enumerationNotes()}.
 *
 * <p><b>Endpoints.</b> {@code settings_.remote_debugging_port} of the existing instance when it holds a port, else the
 * port in a {@code DevToolsActivePort} file under {@code root_cache_path} or {@code cache_path} that is not older than
 * this JVM (its modification time at least the JVM's start less {@value #ACTIVE_PORT_TOLERANCE_MS} ms, for coarse
 * file times: an older file is a previous run's, whose port may now belong to another program); host
 * {@code 127.0.0.1}, label {@code jcef}. Nothing is probed over the network.
 *
 * <p>Thread-safe; called on agent workers. Results of the DevTools channel complete on CEF's UI thread (the EDT with
 * off-screen rendering, BS8): the adapter hands them to its own threads ({@link AdapterThreads}) and runs nothing
 * else there.
 */
public final class JcefAdapter implements WebEngineAdapter {

    /** The adapter id. */
    public static final String ID = "jcef";
    /** The descriptor resource. */
    public static final String DESCRIPTOR = "ai/skillroy/testjava/agent/web/jcef.json";
    /** The probe's reason when the in-process result channel is missing (browser-spi F8). */
    public static final String OLD_JCEF = "JCEF older than 2024-01: no in-process result channel";
    static final String NOT_PRESENT = "not present";
    static final String NOT_LOADED = "present but not loaded by the application yet";
    static final String INITIALIZED = "INITIALIZED";
    /** The probe's reason after {@link #detach()}. */
    static final String DETACHED = "the adapter was detached";
    /** What the strategy says of windowed rendering on a Java 8 target (BS8). */
    static final String WINDOWED_JAVA8 = ", windowed, Java 8: unverified";
    /** A {@code DevToolsActivePort} file may be this much older than the JVM's start (coarse file times). */
    static final long ACTIVE_PORT_TOLERANCE_MS = 2000;
    /** Bound of the adapter's detach (removing init scripts and overrides over the protocol). */
    static final long DETACH_BOUND_MS = 2000;

    private static final Pattern CHROME_IN_TEXT = Pattern.compile("(?i)chrom\\w*[^0-9]{0,20}(\\d{2,4})\\.");

    private final String descriptorResource;
    private final Instrumentation instrumentation;
    final AdapterThreads threads = new AdapterThreads("skillroy-web-jcef");
    private final Map<Object, JcefBrowser> handles = new IdentityHashMap<Object, JcefBrowser>();
    private volatile Descriptor descriptor;
    private volatile LoadedClasses loaded;
    private volatile ResolvedApi api;
    private volatile ProbeResult enumeration = ProbeResult.unavailable("not enumerated yet");
    private volatile List<String> notes = Collections.emptyList();
    private volatile Boolean windowless;
    private volatile boolean detached;

    /** The adapter with its own descriptor and no {@code Instrumentation} (the contract kit's use). */
    public JcefAdapter() {
        this(DESCRIPTOR, null);
    }

    /** The adapter the module makes: its own descriptor, the agent's {@code Instrumentation} (may be null). */
    public JcefAdapter(Instrumentation instrumentation) {
        this(DESCRIPTOR, instrumentation);
    }

    /**
     * An adapter driven by another descriptor resource (a fork, or the neutral fake of the tests); read with this
     * class's own loader on first use, never at construction.
     */
    public JcefAdapter(String descriptorResource, Instrumentation instrumentation) {
        this.descriptorResource = descriptorResource;
        this.instrumentation = instrumentation;
    }

    @Override
    public String id() {
        return ID;
    }

    private Descriptor descriptor() {
        Descriptor d = descriptor;
        if (d == null) {
            synchronized (this) {
                if (descriptor == null) {
                    descriptor = Descriptor.load(descriptorResource, JcefAdapter.class.getClassLoader());
                }
                d = descriptor;
            }
        }
        return d;
    }

    private LoadedClasses loaded() {
        LoadedClasses l = loaded;
        if (l == null) {
            l = new LoadedClasses(instrumentation);
            loaded = l;
        }
        return l;
    }

    @Override
    public ProbeResult probe(ClassLoader hostLoader) {
        if (detached) {
            return ProbeResult.unavailable(DETACHED);
        }
        Descriptor d = descriptor();
        if (!loaded().isLoaded(d.gate(), hostLoader)) {
            return ProbeResult.unavailable(LoadedClasses.isReachable(d.gate(), hostLoader) ? NOT_LOADED
                    : NOT_PRESENT);
        }
        ResolvedApi r = ReflectiveApi.resolve(d, hostLoader);
        if (!r.complete("devtools")) {
            notes = Collections.singletonList("in-process channel missing: " + r.firstMissing("devtools"));
            return ProbeResult.unavailable(OLD_JCEF);
        }
        String core = r.firstMissing(Descriptor.CORE);
        if (core != null) {
            return ProbeResult.unavailable("missing member " + core);
        }
        api = r;
        threads.contextLoader(hostLoader);
        String state;
        try {
            state = state(r);
        } catch (ReflectiveException e) {
            return ProbeResult.degraded("CefApp.getState() failed: " + e.getMessage());
        }
        if (!INITIALIZED.equals(state)) {
            return ProbeResult.ok("jcef");
        }
        return ProbeResult.ok(strategy(r));
    }

    /** {@code "jcef <major>"}, with the windowed-on-Java-8 note. */
    private String strategy(ResolvedApi r) {
        String strategy = "jcef";
        Object app = self(r);
        if (app != null && r.has("app.getVersion")) {
            try {
                Integer major = chromeMajor(r, r.invoke("app.getVersion", app));
                if (major != null) {
                    strategy = "jcef " + major;
                }
            } catch (ReflectiveException e) {
                // the version stays unknown
            }
        }
        return strategy + strategySuffix(readWindowless(r, app), System.getProperty("java.specification.version"));
    }

    /**
     * {@value #WINDOWED_JAVA8} when the process renders windowed ({@code windowless_rendering_enabled} false) on a
     * Java 8 target (a specification version {@code 1.x}), else "" (BS8: windowed JCEF on Java 8 created no native
     * browser; off-screen, an unknown mode and every later Java are not marked).
     *
     * @param windowless {@code windowless_rendering_enabled}, or null when unknown
     * @param javaSpecificationVersion the target's {@code java.specification.version}, or null
     */
    static String strategySuffix(Boolean windowless, String javaSpecificationVersion) {
        boolean windowed = windowless != null && !windowless.booleanValue();
        boolean java8 = javaSpecificationVersion != null && javaSpecificationVersion.startsWith("1.");
        return windowed && java8 ? WINDOWED_JAVA8 : "";
    }

    private static Integer chromeMajor(ResolvedApi r, Object version) {
        if (version == null) {
            return null;
        }
        if (r.has("version.chrome") && r.isInstance("version.chrome", version)) {
            try {
                Integer major = leadingInt(r.get("version.chrome", version));
                if (major != null) {
                    return major;
                }
            } catch (ReflectiveException e) {
                // fall back to the text
            }
        }
        Matcher m = CHROME_IN_TEXT.matcher(String.valueOf(version));
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static Integer leadingInt(Object value) {
        if (value instanceof Number) {
            return Integer.valueOf(((Number) value).intValue());
        }
        if (value == null) {
            return null;
        }
        Matcher m = Pattern.compile("^\\s*(\\d+)").matcher(String.valueOf(value));
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** {@code CefApp.getState()} as text ({@code "INITIALIZED"}, ...). */
    static String state(ResolvedApi r) throws ReflectiveException {
        Object s = r.invoke("app.getState", null);
        if (s instanceof Enum) {
            return ((Enum<?>) s).name();
        }
        return String.valueOf(s);
    }

    /** The existing {@code CefApp} instance through the private {@code self}, or null (never {@code getInstance}). */
    private static Object self(ResolvedApi r) {
        if (!r.has("app.self")) {
            return null;
        }
        try {
            if (!INITIALIZED.equals(state(r))) {
                return null;
            }
            return r.read("app.self", null);
        } catch (ReflectiveException e) {
            return null;
        }
    }

    private Boolean readWindowless(ResolvedApi r, Object app) {
        if (app == null || !r.has("app.settings") || !r.has("settings.windowless")) {
            return windowless;
        }
        try {
            Object settings = r.read("app.settings", app);
            if (settings != null) {
                Object w = r.read("settings.windowless", settings);
                if (w instanceof Boolean) {
                    windowless = (Boolean) w;
                }
            }
        } catch (ReflectiveException e) {
            // unknown
        }
        return windowless;
    }

    /** Whether the process renders off-screen ({@code windowless_rendering_enabled}), or null when unknown. */
    Boolean windowless() {
        return windowless;
    }

    @Override
    public List<WebBrowser> browsers(Collection<?> seeds) {
        ResolvedApi r = api;
        if (r == null || detached) {
            enumeration = ProbeResult.unavailable(detached ? DETACHED
                    : "the adapter has not found JCEF (probe it first)");
            return Collections.emptyList();
        }
        List<String> newNotes = new ArrayList<String>();
        List<Object> found = new ArrayList<Object>();
        List<Object> others = new ArrayList<Object>();
        Map<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        for (Object seed : seeds) {
            if (seed == null) {
                continue;
            }
            try {
                if (r.isInstance("browser", seed)) {
                    add(seed, found, seen);
                } else if (r.isInstance("client", seed)) {
                    if (r.has("client.getAllBrowser")) {
                        for (Object b : allBrowsers(r, seed)) {
                            add(b, found, seen);
                        }
                    } else {
                        newNotes.add("client seed skipped: " + r.firstMissing("walk"));
                    }
                } else if (r.isInstance("app", seed)) {
                    if (r.has("app.clients") && r.has("client.getAllBrowser")) {
                        for (Object c : clients(r, seed)) {
                            for (Object b : allBrowsers(r, c)) {
                                add(b, found, seen);
                            }
                        }
                    } else {
                        newNotes.add("CefApp seed skipped: " + r.firstMissing("walk"));
                    }
                } else {
                    others.add(seed);
                }
            } catch (ReflectiveException e) {
                newNotes.add("seed " + seed.getClass().getName() + " skipped: " + e.getMessage());
            } catch (RuntimeException e) {
                newNotes.add("seed " + seed.getClass().getName() + " skipped: " + e);
            }
        }
        ProbeResult state;
        boolean walked = false;
        String appState;
        try {
            appState = state(r);
        } catch (ReflectiveException e) {
            appState = "unknown (" + e.getMessage() + ")";
        }
        if (!INITIALIZED.equals(appState)) {
            state = ProbeResult.degraded("seeds only: CefApp is " + appState);
        } else if (!r.complete("walk")) {
            state = ProbeResult.degraded("seeds only: " + r.firstMissing("walk"));
        } else {
            try {
                Object app = r.read("app.self", null);
                if (app == null) {
                    state = ProbeResult.degraded("seeds only: CefApp.self is null");
                } else {
                    for (Object c : clients(r, app)) {
                        for (Object b : allBrowsers(r, c)) {
                            add(b, found, seen);
                        }
                    }
                    readWindowless(r, app);
                    walked = true;
                    state = ProbeResult.ok();
                }
            } catch (ReflectiveException e) {
                state = ProbeResult.degraded("seeds only: " + e.getMessage());
            }
        }
        if (!walked && !others.isEmpty()) {
            // After a walk the process-wide list is the truth: a known browser it lacks has closed.
            List<Object> known;
            synchronized (handles) {
                known = new ArrayList<Object>(handles.keySet());
            }
            known.removeAll(seen.keySet());
            if (!known.isEmpty()) {
                for (Object b : JcefComponents.byComponent(r, known, others)) {
                    add(b, found, seen);
                }
            }
        }
        List<WebBrowser> out = new ArrayList<WebBrowser>();
        List<JcefBrowser> gone = new ArrayList<JcefBrowser>();
        synchronized (handles) {
            for (Object b : found) {
                JcefBrowser h = handles.get(b);
                if (h == null) {
                    h = new JcefBrowser(this, r, b);
                    handles.put(b, h);
                }
                out.add(h);
            }
            for (Iterator<Map.Entry<Object, JcefBrowser>> it = handles.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Object, JcefBrowser> e = it.next();
                if (walked && !seen.containsKey(e.getKey())) {
                    gone.add(e.getValue());
                    it.remove();
                } else if (e.getValue().closedFlag()) {
                    it.remove();
                }
            }
        }
        for (WebBrowser h : out) {
            ((JcefBrowser) h).refresh();
        }
        for (JcefBrowser h : gone) {
            h.markClosed("it left the process-wide list of browsers");
        }
        enumeration = state;
        notes = Collections.unmodifiableList(newNotes);
        return out;
    }

    private static void add(Object browser, List<Object> found, Map<Object, Boolean> seen) {
        if (browser != null && !seen.containsKey(browser)) {
            seen.put(browser, Boolean.TRUE);
            found.add(browser);
        }
    }

    /** The clients of a {@code CefApp} ({@code clients_}, a set the application may change concurrently). */
    private static List<Object> clients(ResolvedApi r, Object app) throws ReflectiveException {
        Object set = r.read("app.clients", app);
        if (!(set instanceof Collection)) {
            return Collections.emptyList();
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return new ArrayList<Object>((Collection<?>) set);
            } catch (ConcurrentModificationException e) {
                if (attempt >= 3) {
                    return Collections.emptyList();
                }
            }
        }
    }

    /** {@code CefClient.getAllBrowser()}: the client's live browsers. */
    private static List<Object> allBrowsers(ResolvedApi r, Object client) throws ReflectiveException {
        Object all = r.invoke("client.getAllBrowser", client);
        if (all instanceof Object[]) {
            List<Object> out = new ArrayList<Object>();
            for (Object b : (Object[]) all) {
                if (b != null) {
                    out.add(b);
                }
            }
            return out;
        }
        if (all instanceof Collection) {
            return new ArrayList<Object>((Collection<?>) all);
        }
        return Collections.emptyList();
    }

    @Override
    public List<CdpEndpoint> cdpEndpoints(Collection<?> seeds) {
        ResolvedApi r = api;
        if (r == null) {
            return Collections.emptyList();
        }
        Object app = self(r);
        if (app == null) {
            for (Object seed : seeds) {
                if (r.isInstance("app", seed)) {
                    app = seed;
                    break;
                }
            }
        }
        if (app == null || !r.has("app.settings")) {
            return Collections.emptyList();
        }
        try {
            Object settings = r.read("app.settings", app);
            if (settings == null) {
                return Collections.emptyList();
            }
            if (r.has("settings.remoteDebuggingPort")) {
                Object port = r.read("settings.remoteDebuggingPort", settings);
                if (port instanceof Number && ((Number) port).intValue() > 0 && ((Number) port).intValue() < 65536) {
                    return Collections.singletonList(new CdpEndpoint("127.0.0.1", ((Number) port).intValue(), ID));
                }
            }
            long notBefore = JvmStart.MILLIS > 0 ? JvmStart.MILLIS - ACTIVE_PORT_TOLERANCE_MS : 0;
            for (String role : new String[] {"settings.rootCachePath", "settings.cachePath"}) {
                if (!r.has(role)) {
                    continue;
                }
                Object dir = r.read(role, settings);
                Integer port = activePort(dir, notBefore);
                if (port != null) {
                    return Collections.singletonList(new CdpEndpoint("127.0.0.1", port.intValue(), ID));
                }
            }
        } catch (ReflectiveException e) {
            // unknown: no endpoint reported
        }
        return Collections.emptyList();
    }

    /**
     * The port on the first line of {@code <dir>/DevToolsActivePort}, or null: also null when the file was last
     * modified before {@code notBeforeMillis} (a previous run's file) or its time cannot be read; a
     * {@code notBeforeMillis} of 0 or less checks no time.
     */
    static Integer activePort(Object dir, long notBeforeMillis) {
        if (!(dir instanceof String) || ((String) dir).trim().isEmpty()) {
            return null;
        }
        File f = new File((String) dir, "DevToolsActivePort");
        if (!f.isFile()) {
            return null;
        }
        if (notBeforeMillis > 0) {
            long modified = f.lastModified();
            if (modified <= 0 || modified < notBeforeMillis) {
                return null;
            }
        }
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }
            int port = Integer.parseInt(lines.get(0).trim());
            return port > 0 && port < 65536 ? Integer.valueOf(port) : null;
        } catch (IOException e) {
            return null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public ProbeResult enumerationState() {
        return enumeration;
    }

    @Override
    public List<String> enumerationNotes() {
        return notes;
    }

    /**
     * Removes the adapter's listeners, init scripts and viewport overrides from every browser it handed out (bounded
     * in total), forgets the cached reflection and ends its threads for good ({@link AdapterThreads#close()}: a
     * technology callback that arrives later is dropped and starts no thread); the adapter is inert afterwards (see
     * the class comment). Closes nothing of the application: the shared DevTools client stays open and the protocol
     * domains it enabled stay enabled (browser-spi C3). Never throws.
     */
    @Override
    public void detach() {
        detached = true;
        List<JcefBrowser> all;
        synchronized (handles) {
            all = new ArrayList<JcefBrowser>(handles.values());
            handles.clear();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DETACH_BOUND_MS);
        for (JcefBrowser h : all) {
            try {
                h.detach(deadline);
            } catch (Throwable t) {
                // detach never throws
            }
        }
        threads.close();
        Descriptor d = descriptor;
        if (d != null) {
            ReflectiveApi.forget(d);
        }
        api = null;
    }

    /** The JVM's start time, read once when first needed (0 when it cannot be read). */
    private static final class JvmStart {
        static final long MILLIS = read();

        private static long read() {
            try {
                return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
            } catch (RuntimeException e) {
                return 0;
            } catch (LinkageError e) {
                return 0;
            }
        }
    }
}
