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

package ai.skillroy.testjava.agent.web.jxbrowser;

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
import ai.skillroy.testjava.protocol.web.WebLib;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in adapter for JxBrowser 7, 8 and 9 (phase4-contracts.md section 4.5), on the public members the three
 * lines share (browser-spi F21), named by the descriptor {@value #DESCRIPTOR} and resolved per vendor class loader
 * (a seed from another loader than the host's is resolved in its own). No vendor artifact is a dependency; no vendor
 * type appears in a signature; {@code EngineOptions.licenseKey()} is never read; no {@code Browser.set(...)} callback
 * is taken unless the agent option {@code web.jxbrowser.chainInjectJs=true} asks for it; {@code dispatch(...)} is used
 * for pointer input only, through the descriptor's {@code INPUT} roles, unverified against the vendor's library
 * (Phase 7; the strategy then ends in {@link #INPUT_UNVERIFIED}).
 *
 * <p><b>Probe.</b> The gate class {@code com.teamdev.jxbrowser.engine.Engine} must already be loaded by the host
 * ({@link LoadedClasses}, asked without loading it; a gate the host has not loaded counts as absent and nothing is
 * loaded); only then is the descriptor resolved, which may load the descriptor's classes and what their signatures
 * and linking need, but initialises none of them. The descriptor's core roles must resolve. The strategy is
 * {@code "jxbrowser <line>"} from the gate class's package version, else from the descriptor's version resource (a
 * text that is not a version is shown in parentheses, which is how the neutral fake reads "jxbrowser (fake)"), else
 * {@code "jxbrowser"}. After {@link #detach()} the adapter is inert: its probe answers UNAVAILABLE("the adapter was
 * detached") and it lists no browser.
 *
 * <p><b>Enumeration.</b> Seeds only (browser-spi F23): a {@code BrowserView} ({@code getBrowser()}; the view becomes
 * the browser's component), a {@code Browser}, an {@code Engine} ({@code browsers()} and every profile's
 * {@code browsers()}), a {@code Profile}. {@link #enumerationState()} is OK when a seed resolved, else
 * DEGRADED("no seed: an engine without a view in a window is invisible"). The vendor calls of an enumeration run on
 * the adapter's threads and are waited for at most {@value #ENUMERATION_BOUND_MS} ms (the product's calls are IPC and
 * can hang, browser-spi C1): an enumeration that does not answer in time keeps the browsers found before, reports
 * DEGRADED, and no second one is started while it hangs.
 *
 * <p><b>Endpoints.</b> {@code engine().options().remoteDebuggingPort()} per distinct engine, labelled with the file
 * name of the engine's user-data directory.
 *
 * <p>Thread-safe; called on agent workers. Vendor calls that may block (bitmaps, resizing) and the handling of
 * every result and event run on the adapter's own threads.
 */
public final class JxBrowserAdapter implements WebEngineAdapter {

    /** The adapter id. */
    public static final String ID = "jxbrowser";
    /**
     * Appended to the strategy when the descriptor's {@code INPUT} roles resolve (phase7-contracts.md section 4.5, W2):
     * the pointer input is built from the descriptor and unverified against the vendor's library, and whether its
     * {@code Point} is in CSS or device pixels on a HiDPI view is not known.
     */
    public static final String INPUT_UNVERIFIED = ", input unverified (HiDPI: CSS or device pixels unknown)";
    /** The descriptor resource. */
    public static final String DESCRIPTOR = "ai/skillroy/testjava/agent/web/jxbrowser.json";
    static final String NOT_PRESENT = "not present";
    static final String NOT_LOADED = "present but not loaded by the application yet";
    static final String NO_SEED = "no seed: an engine without a view in a window is invisible";
    /** The probe's reason after {@link #detach()}. */
    static final String DETACHED = "the adapter was detached";
    /** How long the caller waits for an enumeration's vendor calls. */
    static final long ENUMERATION_BOUND_MS = 2000;

    private static final Pattern VERSION = Pattern.compile("(\\d+)(\\.\\d+)*");

    /**
     * The text that ends the wrapper's own function and opens the one that holds the caller's expression (see
     * {@link #wrap}); with {@link #EXPRESSION_CLOSE}, what a fake of the technology looks for.
     */
    public static final String EXPRESSION_OPEN = "})(function(){return (";
    /** The text after the caller's expression in {@link #wrap}. */
    public static final String EXPRESSION_CLOSE = "\n);})";

    /** The agent options the adapter reads (live). */
    public interface Options {
        /** True when the pack opted in to chaining {@code InjectJsCallback} (DOCUMENT_START init scripts). */
        boolean chainInjectJs();
    }

    /** No options: no chaining. */
    public static final Options NO_OPTIONS = new Options() {
        @Override
        public boolean chainInjectJs() {
            return false;
        }
    };

    private final String descriptorResource;
    private final Instrumentation instrumentation;
    private final Options options;
    final AdapterThreads threads = new AdapterThreads("skillroy-web-jxbrowser");
    private final Map<Object, JxBrowserHandle> handles = new IdentityHashMap<Object, JxBrowserHandle>();
    private volatile Descriptor descriptor;
    private volatile LoadedClasses loaded;
    private volatile ResolvedApi hostApi;
    private volatile Integer line;
    private volatile ProbeResult enumeration = ProbeResult.unavailable("not enumerated yet");
    private volatile List<String> notes = Collections.emptyList();
    private volatile boolean detached;
    /** The enumeration running on the adapter's threads, or null; guarded by {@link #handles}. */
    private CompletableFuture<List<WebBrowser>> enumerating;

    /** The adapter with its own descriptor, no {@code Instrumentation} and no options (the contract kit's use). */
    public JxBrowserAdapter() {
        this(DESCRIPTOR, null, NO_OPTIONS);
    }

    /** The adapter the module makes. */
    public JxBrowserAdapter(Instrumentation instrumentation, Options options) {
        this(DESCRIPTOR, instrumentation, options);
    }

    /**
     * An adapter driven by another descriptor resource (another line's names, or the neutral fake of the tests); read
     * with this class's own loader on first use, never at construction.
     */
    public JxBrowserAdapter(String descriptorResource, Instrumentation instrumentation, Options options) {
        this.descriptorResource = descriptorResource;
        this.instrumentation = instrumentation;
        this.options = options == null ? NO_OPTIONS : options;
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * The evaluation wrapper of this adapter (the JxBrowser channel returns strings only, browser-spi F24): installs
     * the script library when absent, evaluates the expression with {@code this} the window and returns a tagged
     * string: {@code "s" + value} for a string, {@code "j" + stringify(value)} for anything else (the library's
     * {@code {json, typeofResult, truncated}}), {@code "e" + stringify({message, stack})} for a thrown error. The
     * expression sits in a function of its own outside the wrapper's function, so no name of the wrapper shadows a
     * page global; trailing semicolons and white space are dropped.
     *
     * @throws IllegalArgumentException if the expression is null or blank
     */
    public static String wrap(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("expression is required");
        }
        int end = expression.length();
        while (end > 0 && (expression.charAt(end - 1) == ';' || Character.isWhitespace(expression.charAt(end - 1)))) {
            end--;
        }
        if (end == 0) {
            throw new IllegalArgumentException("expression is blank");
        }
        return "(function(t){" + WebLib.installExpression() + ";var L=window[" + WebLib.SLOT + "];"
                + "try{var v=t.call(window);return typeof v===\"string\"?\"s\"+v:\"j\"+L.stringify(v);}"
                + "catch(e){var m=\"script error\",k=null;"
                + "try{m=e&&e.message!==undefined?String(e.message):String(e);}catch(x){}"
                + "try{k=e&&e.stack?String(e.stack):null;}catch(x){}"
                + "return \"e\"+L.stringify({message:m,stack:k});}"
                + EXPRESSION_OPEN + expression.substring(0, end) + EXPRESSION_CLOSE;
    }

    Descriptor descriptor() {
        Descriptor d = descriptor;
        if (d == null) {
            synchronized (this) {
                if (descriptor == null) {
                    descriptor = Descriptor.load(descriptorResource, JxBrowserAdapter.class.getClassLoader());
                }
                d = descriptor;
            }
        }
        return d;
    }

    Options options() {
        return options;
    }

    /** The product line from the probe (7, 8, 9), or null when unknown. */
    Integer line() {
        return line;
    }

    @Override
    public ProbeResult probe(ClassLoader hostLoader) {
        if (detached) {
            return ProbeResult.unavailable(DETACHED);
        }
        Descriptor d = descriptor();
        LoadedClasses l = loaded;
        if (l == null) {
            l = new LoadedClasses(instrumentation);
            loaded = l;
        }
        if (!l.isLoaded(d.gate(), hostLoader)) {
            return ProbeResult.unavailable(LoadedClasses.isReachable(d.gate(), hostLoader) ? NOT_LOADED
                    : NOT_PRESENT);
        }
        ResolvedApi r = ReflectiveApi.resolve(d, hostLoader);
        String core = r.firstMissing(Descriptor.CORE);
        if (core != null) {
            return ProbeResult.unavailable("missing member " + core);
        }
        hostApi = r;
        threads.contextLoader(hostLoader);
        return ProbeResult.ok(strategy(d, r) + (r.complete("INPUT") ? INPUT_UNVERIFIED : ""));
    }

    private String strategy(Descriptor d, ResolvedApi r) {
        String version = null;
        Class<?> gate = r.type("engine");
        Package pkg = gate == null ? null : gate.getPackage();
        if (pkg != null) {
            version = pkg.getImplementationVersion();
            if (version == null) {
                version = pkg.getSpecificationVersion();
            }
        }
        if (version == null && d.versionResource() != null) {
            version = resourceText(r.loader(), d.versionResource());
        }
        if (version == null || version.trim().isEmpty()) {
            return "jxbrowser";
        }
        Matcher m = VERSION.matcher(version);
        if (m.find()) {
            line = Integer.valueOf(m.group(1));
            return "jxbrowser " + m.group(1);
        }
        return "jxbrowser (" + version.trim() + ")";
    }

    private static String resourceText(ClassLoader loader, String resource) {
        InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 4096) {
                out.write(buf, 0, n);
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            for (String row : text.split("\\r?\\n")) {
                String t = row.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                return eq >= 0 ? t.substring(eq + 1).trim() : t;
            }
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // a class-path resource
            }
        }
    }

    /**
     * The resolution for a seed: the host's when the seed is of the host's classes, else the descriptor resolved in
     * the loader of the seed's vendor class (found by name in the seed's class hierarchy), or null for a seed of
     * another technology.
     */
    ResolvedApi apiFor(Object seed) {
        ResolvedApi host = hostApi;
        if (host != null && matches(host, seed)) {
            return host;
        }
        Descriptor d = descriptor();
        Set<String> names = new HashSet<String>(Arrays.asList(
                d.role("view").className(), d.role("browser").className(), d.role("engine").className(),
                d.role("profile").className()));
        for (Class<?> c = seed.getClass(); c != null; c = c.getSuperclass()) {
            ClassLoader l = loaderOfNamed(c, names);
            if (l != null) {
                ResolvedApi r = ReflectiveApi.resolve(d, l);
                return r.firstMissing(Descriptor.CORE) == null && matches(r, seed) ? r : null;
            }
        }
        return null;
    }

    private static ClassLoader loaderOfNamed(Class<?> c, Set<String> names) {
        if (names.contains(c.getName())) {
            return c.getClassLoader();
        }
        for (Class<?> i : c.getInterfaces()) {
            ClassLoader l = loaderOfNamed(i, names);
            if (l != null) {
                return l;
            }
        }
        return null;
    }

    private static boolean matches(ResolvedApi r, Object seed) {
        return r.isInstance("view", seed) || r.isInstance("browser", seed) || r.isInstance("engine", seed)
                || r.isInstance("profile", seed);
    }

    @Override
    public List<WebBrowser> browsers(Collection<?> seeds) {
        if (hostApi == null || detached) {
            enumeration = ProbeResult.unavailable(detached ? DETACHED
                    : "the adapter has not found JxBrowser (probe it first)");
            return Collections.emptyList();
        }
        final List<Object> copy = new ArrayList<Object>(seeds);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ENUMERATION_BOUND_MS);
        CompletableFuture<List<WebBrowser>> previous;
        synchronized (handles) {
            previous = enumerating;
        }
        if (previous != null && !previous.isDone() && !await(previous, deadline)) {
            return kept("an earlier enumeration");
        }
        final CompletableFuture<List<WebBrowser>> mine = new CompletableFuture<List<WebBrowser>>();
        synchronized (handles) {
            enumerating = mine;
        }
        try {
            threads.executor().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        mine.complete(enumerate(copy));
                    } catch (Throwable t) {
                        mine.completeExceptionally(t);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            enumeration = ProbeResult.unavailable(DETACHED);
            return Collections.emptyList();
        }
        if (!await(mine, deadline)) {
            return kept("this enumeration");
        }
        try {
            return mine.getNow(Collections.<WebBrowser>emptyList());
        } catch (CompletionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw c instanceof RuntimeException ? (RuntimeException) c : new IllegalStateException(c);
        }
    }

    /** Waits for an enumeration until the deadline; true when it completed. */
    private static boolean await(CompletableFuture<?> f, long deadlineNanos) {
        try {
            f.get(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())),
                    TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /** The browsers found before, when the engine does not answer an enumeration in time (DEGRADED). */
    private List<WebBrowser> kept(String which) {
        enumeration = ProbeResult.degraded("the engine did not answer " + which + " within " + ENUMERATION_BOUND_MS
                + " ms; the browsers found before are kept");
        List<WebBrowser> out = new ArrayList<WebBrowser>();
        synchronized (handles) {
            for (JxBrowserHandle h : handles.values()) {
                if (!h.closedFlag()) {
                    out.add(h);
                }
            }
        }
        return out;
    }

    /** One enumeration (see the class comment), on the adapter's threads. */
    private List<WebBrowser> enumerate(Collection<?> seeds) {
        List<String> newNotes = new ArrayList<String>();
        List<Object> found = new ArrayList<Object>();
        Map<Object, ResolvedApi> apis = new IdentityHashMap<Object, ResolvedApi>();
        Map<Object, Object> views = new IdentityHashMap<Object, Object>();
        boolean resolved = false;
        for (Object seed : seeds) {
            if (seed == null) {
                continue;
            }
            ResolvedApi r;
            try {
                r = apiFor(seed);
            } catch (RuntimeException e) {
                newNotes.add("seed " + seed.getClass().getName() + " skipped: " + e);
                continue;
            }
            if (r == null) {
                continue;
            }
            try {
                if (r.isInstance("view", seed)) {
                    Object b = r.invoke("view.getBrowser", seed);
                    if (b != null) {
                        views.put(b, seed);
                        add(b, r, found, apis);
                        resolved = true;
                    }
                } else if (r.isInstance("browser", seed)) {
                    add(seed, r, found, apis);
                    resolved = true;
                } else if (r.isInstance("engine", seed)) {
                    for (Object b : engineBrowsers(r, seed, newNotes)) {
                        add(b, r, found, apis);
                    }
                    resolved = true;
                } else if (r.isInstance("profile", seed)) {
                    if (r.has("profile.browsers")) {
                        for (Object b : list(r.invoke("profile.browsers", seed))) {
                            add(b, r, found, apis);
                        }
                        resolved = true;
                    } else {
                        newNotes.add("profile seed skipped: " + r.firstMissing("profiles"));
                    }
                }
            } catch (ReflectiveException e) {
                newNotes.add("seed " + seed.getClass().getName() + " skipped: " + e.getMessage());
            }
        }
        List<WebBrowser> out = new ArrayList<WebBrowser>();
        synchronized (handles) {
            for (Object b : found) {
                JxBrowserHandle h = handles.get(b);
                if (h == null) {
                    h = new JxBrowserHandle(this, apis.get(b), b);
                    handles.put(b, h);
                }
                Object view = views.get(b);
                if (view != null) {
                    h.view(view);
                }
                out.add(h);
            }
            for (Iterator<JxBrowserHandle> it = handles.values().iterator(); it.hasNext(); ) {
                if (it.next().closedFlag()) {
                    it.remove();
                }
            }
        }
        enumeration = resolved ? ProbeResult.ok() : ProbeResult.degraded(NO_SEED);
        notes = Collections.unmodifiableList(newNotes);
        return out;
    }

    private static void add(Object browser, ResolvedApi r, List<Object> found, Map<Object, ResolvedApi> apis) {
        if (browser != null && !apis.containsKey(browser)) {
            apis.put(browser, r);
            found.add(browser);
        }
    }

    private static List<Object> engineBrowsers(ResolvedApi r, Object engine, List<String> notes)
            throws ReflectiveException {
        List<Object> out = new ArrayList<Object>(list(r.invoke("engine.browsers", engine)));
        if (r.complete("profiles")) {
            Object profiles = r.invoke("engine.profiles", engine);
            for (Object profile : list(r.invoke("profiles.list", profiles))) {
                out.addAll(list(r.invoke("profile.browsers", profile)));
            }
        } else {
            notes.add("other profiles not listed: " + r.firstMissing("profiles"));
        }
        return out;
    }

    /** A vendor list (or array, or single Optional value) as a Java list. */
    static List<Object> list(Object value) {
        if (value instanceof Collection) {
            return new ArrayList<Object>((Collection<?>) value);
        }
        if (value instanceof Object[]) {
            return new ArrayList<Object>(Arrays.asList((Object[]) value));
        }
        return Collections.emptyList();
    }

    /** The value inside an {@code Optional}, or the value itself. */
    static Object unwrap(Object value) {
        if (value instanceof Optional) {
            return ((Optional<?>) value).orElse(null);
        }
        return value;
    }

    @Override
    public List<CdpEndpoint> cdpEndpoints(Collection<?> seeds) {
        if (hostApi == null) {
            return Collections.emptyList();
        }
        List<CdpEndpoint> out = new ArrayList<CdpEndpoint>();
        Map<Object, Boolean> engines = new IdentityHashMap<Object, Boolean>();
        for (Object seed : seeds) {
            if (seed == null) {
                continue;
            }
            ResolvedApi r = apiFor(seed);
            if (r == null || !r.complete("options") || !r.complete("endpoints")) {
                continue;
            }
            try {
                Object engine = engineOf(r, seed);
                if (engine == null || engines.containsKey(engine)) {
                    continue;
                }
                engines.put(engine, Boolean.TRUE);
                Object options = r.invoke("engine.options", engine);
                Object port = unwrap(r.invoke("options.remoteDebuggingPort", options));
                if (port instanceof Number && ((Number) port).intValue() > 0 && ((Number) port).intValue() < 65536) {
                    out.add(new CdpEndpoint("127.0.0.1", ((Number) port).intValue(), label(unwrap(r.invoke(
                            "options.userDataDir", options)))));
                }
            } catch (ReflectiveException e) {
                // no endpoint known for this engine
            }
        }
        return out;
    }

    private static Object engineOf(ResolvedApi r, Object seed) throws ReflectiveException {
        if (r.isInstance("engine", seed)) {
            return seed;
        }
        Object browser = seed;
        if (r.isInstance("view", seed)) {
            browser = r.invoke("view.getBrowser", seed);
        }
        if (browser != null && r.isInstance("browser", browser)) {
            return r.invoke("browser.engine", browser);
        }
        return null;
    }

    /** The file name of a user-data directory (a {@code Path}, a {@code File} or text), or null. */
    static String label(Object dir) {
        if (dir == null) {
            return null;
        }
        String text = String.valueOf(dir);
        if (text.isEmpty()) {
            return null;
        }
        String trimmed = text.replaceAll("[/\\\\]+$", "");
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
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
     * Unsubscribes the observers, stops the init scripts, restores a chained {@code InjectJsCallback}'s previous
     * occupant and the size before the first resize (each browser's vendor calls on the adapter's threads, bounded in
     * total), forgets the cached reflection and ends the adapter's threads for good ({@link AdapterThreads#close()}: a
     * vendor callback that arrives later is dropped and starts no thread); the adapter is inert afterwards (see the
     * class comment). Closes nothing of the application. Never throws.
     */
    @Override
    public void detach() {
        detached = true;
        List<JxBrowserHandle> all;
        synchronized (handles) {
            all = new ArrayList<JxBrowserHandle>(handles.values());
            handles.clear();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
        for (JxBrowserHandle h : all) {
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
        hostApi = null;
    }
}
