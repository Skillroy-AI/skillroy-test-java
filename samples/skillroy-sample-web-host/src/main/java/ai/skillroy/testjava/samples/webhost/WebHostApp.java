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


package ai.skillroy.testjava.samples.webhost;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefBuildInfo;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.IProgressHandler;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFrame;

/**
 * The web host: a small Swing application that embeds JCEF (through jcefmaven) the way a desktop application embeds
 * web views, and nothing else. It knows nothing about the framework; the only thing it exposes is the registry
 * {@link WebHostBrowsers}. It has no plugin and no embedded agent start.
 *
 * <h2>What it shows</h2>
 * One {@link JFrame} titled "Web host" with a {@code CardLayout} of two browsers: card {@code front} (shown) and card
 * {@code back} (hidden: a cached browser the Swing tree holds as displayable but not showing; the host calls
 * {@code createImmediately()} on it, because a browser whose component is never shown is otherwise never created),
 * a button "Swap" that shows the other card, and a button "Notice" that shows a modal notice over the page (Phase 7
 * follow-up, gap 95). With off-screen rendering a third browser, {@code detached}, is
 * created with {@code createImmediately()} and never added to any window. The pages are {@link WebHostPages}.
 *
 * <h2>Start-up ({@link #launch})</h2>
 * <ol>
 *   <li>Resolve the options ({@link WebHostOptions}): install directory, cache directory (a fresh temporary directory
 *       unless {@code -Dwebhost.cache} names one).</li>
 *   <li>Create the empty files "First Run" and "EULA Accepted" in the cache directory ({@link FirstRunSentinels}):
 *       Chromium 152's first-run terms dialog otherwise blocks {@code CefInitialize}.</li>
 *   <li>Build the {@code CefApp} with jcefmaven's {@code CefAppBuilder}, before any AWT or Swing class is used
 *       (windowed rendering on Linux needs {@code CefApp.startup} before the first AWT call): install directory, no
 *       download mirrors (the natives come from the class path or an earlier extraction; the host never downloads),
 *       {@code windowless_rendering_enabled} for off-screen rendering, {@code remote_debugging_port} from
 *       {@code -Dwebhost.debugPort} (0: none), {@code root_cache_path} = the cache directory.</li>
 *   <li>Create one client, the host's load handler on it, and the browsers ({@code front}, {@code back}, and
 *       {@code detached} off screen).</li>
 *   <li>On the event dispatch thread: build and show the window with the front card.</li>
 *   <li>{@code createImmediately()} on {@code back} (and {@code detached}).</li>
 *   <li>Wait until every page has loaded (main frame load end), at most 60 s in all ({@link #LOAD_TIMEOUT_MS}); a
 *       page that has not loaded by then is logged as a warning, not an error.</li>
 *   <li>Register the browsers in {@link WebHostBrowsers}: from then on the sample's agent module reports the target
 *       ready.</li>
 * </ol>
 *
 * <h2>JVM arguments</h2>
 * The host needs a display ({@code -Djava.awt.headless=false} where a build sets it; a headless JVM is refused before
 * CEF is touched, the only AWT call made before the CEF application exists). Off-screen rendering on Java 16
 * or newer needs {@link #OFF_SCREEN_JVM_ARGS} ({@code --add-exports java.base/java.lang=ALL-UNNAMED --add-exports
 * java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED}) for JOGL; a Java 8 JVM refuses
 * them (it does not start), Java 9 to 15 accepts them and does not need them, and windowed rendering does not need
 * them. A launcher therefore passes them only when the target Java is 9 or newer, and must not put them where a Java 8
 * test JVM picks them up (a shared {@code argLine}); this module's own tests receive them through the environment
 * variable {@code JDK_JAVA_OPTIONS}, which Java 8 ignores (see the module's POM). The host warns when it renders off
 * screen on Java 16+ without them, and when it renders windowed on Java 8 (unverified: spike BS8 saw no native
 * browser created there).
 *
 * <h2>Log lines</h2>
 * Through {@code java.util.logging}, logger {@code ai.skillroy.testjava.samples.webhost.WebHostApp}; every message
 * starts with {@code "web host: "}. In order: {@code starting (rendering <windowed|osr>, remote-debugging port
 * <n|none>, install directory <dir>, cache directory <dir>, platform <id>, Java <version>)}; the warnings above when
 * they apply;
 * {@code the first-run files "First Run" and "EULA Accepted" are in <dir> (created now: <names|none>)};
 * {@code jcefmaven <locating|extracting|install|initializing|initialized>} per stage; {@code CEF state <state>} per
 * state change; {@code CefApp built in <ms> ms, state <state>, JCEF <v>, CEF <v>, Chromium <v>};
 * {@code browsers created: <names> (<windowed|off-screen>)}; {@code window "Web host" shown with the front card};
 * {@code the <page> page loaded (HTTP status <n>)} per main-frame load (also later ones, such as a reload), or the
 * warning {@code the <page> page failed to load: <code> <text>}; the warning
 * {@code the <page> page did not finish loading within <s> s}; {@code ready; registered in WebHostBrowsers: <names>}.
 * Later: {@code showing the <card> card} per swap; {@code showing the notice} and {@code the notice was closed} per
 * notice; {@code shutting down}, {@code CEF state SHUTTING_DOWN}, the
 * warning {@code the window was not disposed of within 5 s} if so, {@code CEF state TERMINATED} and
 * {@code CEF terminated} (or the warning {@code CEF did not report TERMINATED within <s> s (state <state>)}),
 * {@code removed the cache directory <dir>} (or the warning {@code could not remove the cache directory <dir>: <e>}).
 *
 * <h2>Threads and shutdown</h2>
 * CEF's threads are not daemon threads: a JVM that started CEF does not end by itself. Closing the window shuts the
 * host down (on a thread of its own, never the event dispatch thread) and, when started through {@link #main}, then
 * ends the JVM with {@code System.exit(0)}. Code that launched a host in its own JVM (a test) calls
 * {@link #shutdown()} when done. CEF can be initialised only once per JVM: a second {@link #launch} while a host runs
 * returns that host, and one after it stopped throws, so every test class that starts a host needs a JVM of its own
 * (in this module: a surefire execution of its own, see the POM).
 */
public final class WebHostApp {

    /** The JVM arguments off-screen rendering needs on Java 16 or newer (never on Java 8, which refuses them). */
    public static final List<String> OFF_SCREEN_JVM_ARGS = Collections.unmodifiableList(Arrays.asList(
            "--add-exports", "java.base/java.lang=ALL-UNNAMED",
            "--add-exports", "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-exports", "java.desktop/sun.java2d=ALL-UNNAMED"));
    /** How long {@link #launch} waits, in all, for the pages to load. */
    public static final long LOAD_TIMEOUT_MS = 60000L;
    /** How long {@link #shutdown()} waits for CEF to report {@code TERMINATED}. */
    static final long TERMINATE_TIMEOUT_MS = 10000L;

    private static final Logger LOG = Logger.getLogger(WebHostApp.class.getName());
    private static final Object LAUNCH_LOCK = new Object();
    /** Counted down when CEF reports TERMINATED; there is one CEF per JVM. */
    private static final CountDownLatch TERMINATED = new CountDownLatch(1);
    private static volatile WebHostApp running;
    /** Guarded by LAUNCH_LOCK: a launch has started CEF (or tried to) in this JVM. */
    private static boolean cefStarted;

    private final WebHostOptions options;
    private final boolean exitOnClose;
    private final Path installDir;
    private final Path cacheDir;
    private final boolean ownCacheDir;
    private final LoadTracker loads = new LoadTracker();
    private final AtomicBoolean shutDown = new AtomicBoolean();
    private volatile CefApp cefApp;
    private volatile CefClient client;
    private volatile CefBrowser front;
    private volatile CefBrowser back;
    private volatile CefBrowser detached;
    private volatile WebHostWindow window;

    private WebHostApp(WebHostOptions options, boolean exitOnClose, Path cacheDir, boolean ownCacheDir) {
        this.options = options;
        this.exitOnClose = exitOnClose;
        this.installDir = options.installDir().toAbsolutePath().normalize();
        this.cacheDir = cacheDir;
        this.ownCacheDir = ownCacheDir;
    }

    /**
     * Starts the host with options from the system properties ({@link WebHostOptions}); closing the window ends the
     * JVM. A start-up failure is logged and ends the JVM with status 1.
     */
    public static void main(String[] args) {
        try {
            launch(WebHostOptions.fromSystemProperties(), true);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "web host: start-up failed", t);
            System.exit(1);
        }
    }

    /**
     * Starts a host in this JVM and returns once it is ready (see the class description); closing its window shuts
     * it down without ending the JVM.
     *
     * @return the running host; the one already running when this JVM has one with the same rendering mode and port
     * @throws IllegalStateException when a host with another rendering mode or port runs in this JVM, when a host ran
     *     here before (CEF cannot be initialised twice in one JVM), or when CEF did not start
     * @throws IOException when the cache directory or its first-run files cannot be created
     */
    public static WebHostApp launch(WebHostOptions options) throws IOException, InterruptedException {
        return launch(options, false);
    }

    private static WebHostApp launch(WebHostOptions options, boolean exitOnClose)
            throws IOException, InterruptedException {
        Objects.requireNonNull(options, "options");
        synchronized (LAUNCH_LOCK) {
            WebHostApp app = running;
            if (app != null) {
                if (app.options.rendering() != options.rendering() || app.options.debugPort() != options.debugPort()) {
                    throw new IllegalStateException("a web host already runs in this JVM (" + app.options
                            + "); CEF can be initialised once per JVM");
                }
                LOG.info("web host: already running in this JVM; returning the running host");
                return app;
            }
            if (cefStarted) {
                throw new IllegalStateException("a web host was started in this JVM before (and has stopped or "
                        + "failed to start); CEF can be initialised only once per JVM, so start each host in a JVM "
                        + "of its own");
            }
            if (GraphicsEnvironment.isHeadless()) {
                throw new IllegalStateException("the web host needs a display; this JVM is headless");
            }
            Path configured = options.cacheDir();
            Path cache = configured != null ? configured
                    : Files.createTempDirectory("webhost-cef-cache-");
            app = new WebHostApp(options, exitOnClose, cache.toAbsolutePath().normalize(), configured == null);
            boolean started = false;
            try {
                app.start();
                started = true;
            } finally {
                if (!started) {
                    app.abandon();
                }
            }
            running = app;
            return app;
        }
    }

    /** @return the host running in this JVM, or null */
    public static WebHostApp running() {
        return running;
    }

    private void start() throws IOException, InterruptedException {
        boolean osr = options.offScreen();
        LOG.info("web host: starting (rendering " + options.rendering().id() + ", remote-debugging port "
                + (options.debugPort() == WebHostOptions.NO_DEBUG_PORT ? "none" : String.valueOf(options.debugPort()))
                + ", install directory " + installDir + ", cache directory " + cacheDir + ", platform "
                + options.platformId() + ", Java " + System.getProperty("java.version") + ")");
        int java = JvmExports.javaFeatureVersion();
        if (osr && java >= 16) {
            List<String> missing = JvmExports.missingOffScreenExports();
            if (!missing.isEmpty()) {
                LOG.warning("web host: off-screen rendering on Java " + java + " needs the JVM arguments "
                        + join(OFF_SCREEN_JVM_ARGS, " ") + "; not exported to the unnamed module: " + missing);
            }
        }
        if (!osr && java == 8) {
            LOG.warning("web host: windowed rendering on Java 8 is unverified (spike BS8 saw no native browser "
                    + "created there); use -D" + WebHostOptions.PROP_RENDERING + "=osr");
        }
        List<String> created = FirstRunSentinels.prepare(cacheDir);
        LOG.info("web host: the first-run files \"" + FirstRunSentinels.FIRST_RUN + "\" and \""
                + FirstRunSentinels.EULA_ACCEPTED + "\" are in " + cacheDir + " (created now: "
                + (created.isEmpty() ? "none" : join(created, ", ")) + ")");

        cefApp = buildCefApp(osr);
        client = cefApp.createClient();
        client.addLoadHandler(loads);
        front = createBrowser(WebHostPages.FRONT, osr);
        back = createBrowser(WebHostPages.BACK, osr);
        detached = osr ? createBrowser(WebHostPages.DETACHED, true) : null;
        LOG.info("web host: browsers created: " + join(pages(), ", ") + " (" + (osr ? "off-screen" : "windowed")
                + ")");

        final Component frontView = front.getUIComponent();
        final Component backView = back.getUIComponent();
        onEdt(new Runnable() {
            @Override
            public void run() {
                window = WebHostWindow.open(frontView, backView, new Runnable() {
                    @Override
                    public void run() {
                        closeRequested();
                    }
                });
            }
        });
        LOG.info("web host: window \"" + WebHostWindow.TITLE + "\" shown with the front card");
        back.createImmediately();
        if (detached != null) {
            detached.createImmediately();
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOAD_TIMEOUT_MS);
        for (String page : pages()) {
            if (!loads.await(page, deadline - System.nanoTime())) {
                LOG.warning("web host: the " + page + " page did not finish loading within "
                        + TimeUnit.MILLISECONDS.toSeconds(LOAD_TIMEOUT_MS) + " s");
            }
        }
        if (shutDown.get()) {
            throw new IllegalStateException("the web host was shut down while it started");
        }
        WebHostBrowsers.register(cefApp, client, front, back, detached);
        LOG.info("web host: ready; registered in WebHostBrowsers: " + join(pages(), ", "));
    }

    private CefApp buildCefApp(boolean osr) throws InterruptedException {
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(installDir.toFile());
        builder.setMirrors(Collections.<String>emptyList());
        builder.setProgressHandler(new ProgressLog());
        builder.setAppHandler(new StateLog());
        CefSettings settings = builder.getCefSettings();
        settings.windowless_rendering_enabled = osr;
        settings.remote_debugging_port = options.debugPort();
        settings.root_cache_path = cacheDir.toString();
        long t0 = System.nanoTime();
        CefApp app;
        cefStarted = true; // LAUNCH_LOCK is held: start() runs inside launch()
        try {
            app = builder.build();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            String platform = options.platformId();
            throw new IllegalStateException("web host: CEF did not start (platform " + platform
                    + ", install directory " + installDir + "): " + e + ". The JCEF natives come from the class "
                    + "path (me.friwi:jcef-natives-" + platform + ":" + nativesTag() + ") or from an earlier "
                    + "extraction into the install directory; the web host never downloads them", e);
        }
        CefApp.CefVersion version = app.getVersion();
        LOG.info("web host: CefApp built in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) + " ms, state "
                + CefApp.getState() + (version == null ? "" : ", JCEF " + version.getJcefVersion() + ", CEF "
                + version.getCefVersion() + ", Chromium " + version.getChromeVersion()));
        return app;
    }

    private static String nativesTag() {
        try {
            return CefBuildInfo.fromClasspath().getReleaseTag();
        } catch (IOException | RuntimeException e) {
            return "<the jcef release tag>";
        }
    }

    private CefBrowser createBrowser(String page, boolean osr) {
        CefBrowser browser = client.createBrowser(WebHostPages.url(page), osr, false);
        loads.expect(browser, page);
        return browser;
    }

    private List<String> pages() {
        return options.offScreen() ? WebHostPages.NAMES : Arrays.asList(WebHostPages.FRONT, WebHostPages.BACK);
    }

    /** The window was asked to close (on the EDT): shut down on a thread of its own, then end a JVM main started. */
    private void closeRequested() {
        if (shutDown.get()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
                if (exitOnClose) {
                    System.exit(0);
                }
            }
        }, "webhost-shutdown");
        t.start();
    }

    /**
     * Shuts the host down: clears {@link WebHostBrowsers}, allows the browsers to close (JCEF cancels a browser's
     * close, and asks its window to close instead, unless the application called {@code setCloseAllowed()}), disposes
     * of CEF ({@code CefApp.dispose()}, which closes every browser of every client), then of the window (as JCEF's own
     * samples do: windowed browsers finish closing once their window is gone), waits up to 10 s for CEF to report
     * {@code TERMINATED} and, when it did, removes the cache directory if the host created it. Idempotent. Never call
     * it on the event dispatch thread (CEF needs that thread to terminate). The JVM can end afterwards; CEF cannot be
     * started again in it.
     *
     * @throws IllegalStateException when called on the event dispatch thread
     */
    public void shutdown() {
        if (EventQueue.isDispatchThread()) {
            throw new IllegalStateException("WebHostApp.shutdown() must not run on the event dispatch thread");
        }
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        LOG.info("web host: shutting down");
        CefBrowser f = front;
        if (f != null) {
            WebHostBrowsers.clearIfFront(f);
        }
        boolean terminated = closeAll(true);
        if (ownCacheDir && terminated) {
            deleteTree(cacheDir);
        }
        if (running == this) {
            running = null;
        }
    }

    /** After a failed start: undo what exists, best effort, without waiting for CEF. */
    private void abandon() {
        shutDown.set(true);
        closeAll(false);
    }

    /**
     * Allows the browsers to close, disposes of CEF and of the window; with {@code wait}, waits (bounded) for the
     * window's disposal and for CEF's {@code TERMINATED}.
     *
     * @return whether CEF reported {@code TERMINATED} (true when it never started)
     */
    private boolean closeAll(boolean wait) {
        for (CefBrowser b : Arrays.asList(front, back, detached)) {
            if (b != null) {
                try {
                    b.setCloseAllowed();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "web host: setCloseAllowed() failed", e);
                }
            }
        }
        CefApp app = cefApp;
        if (app != null) {
            try {
                app.dispose();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "web host: CefApp.dispose() failed", e);
            }
        }
        disposeWindow(wait);
        if (app == null || !wait) {
            return app == null;
        }
        boolean terminated = false;
        try {
            terminated = TERMINATED.await(TERMINATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (terminated) {
            LOG.info("web host: CEF terminated");
        } else {
            LOG.warning("web host: CEF did not report TERMINATED within "
                    + TimeUnit.MILLISECONDS.toSeconds(TERMINATE_TIMEOUT_MS) + " s (state " + CefApp.getState() + ")");
        }
        return terminated;
    }

    /** Disposes of the window on the event dispatch thread; with {@code wait}, waits up to 5 s for it. */
    private void disposeWindow(boolean wait) {
        final WebHostWindow w = window;
        if (w == null) {
            return;
        }
        final CountDownLatch disposed = new CountDownLatch(1);
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    w.dispose();
                } finally {
                    disposed.countDown();
                }
            }
        });
        if (!wait) {
            return;
        }
        try {
            if (!disposed.await(5, TimeUnit.SECONDS)) {
                LOG.warning("web host: the window was not disposed of within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteTree(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    if (e != null) {
                        throw e;
                    }
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
            LOG.info("web host: removed the cache directory " + dir);
        } catch (IOException e) {
            LOG.warning("web host: could not remove the cache directory " + dir + ": " + e);
        }
    }

    private static void onEdt(Runnable task) throws InterruptedException {
        try {
            EventQueue.invokeAndWait(task);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------- accessors

    /**
     * Waits until a page's main frame has loaded once.
     *
     * @param page {@link WebHostPages#FRONT}, {@link WebHostPages#BACK} or {@link WebHostPages#DETACHED}
     * @return true once it has (at once if it already had), false when the time ran out first
     */
    public boolean awaitLoaded(String page, long timeout, TimeUnit unit) throws InterruptedException {
        return loads.await(page, unit.toNanos(timeout));
    }

    /** Shows the other card (posted to the event dispatch thread), as the button "Swap" does. */
    public void swap() {
        final WebHostWindow w = window;
        if (w != null) {
            EventQueue.invokeLater(new Runnable() {
                @Override
                public void run() {
                    w.swap();
                }
            });
        }
    }

    public WebHostOptions options() {
        return options;
    }

    /** @return the absolute install directory of the natives */
    public Path installDir() {
        return installDir;
    }

    /** @return the absolute root cache path */
    public Path cacheDir() {
        return cacheDir;
    }

    public CefApp cefApp() {
        return cefApp;
    }

    public CefClient client() {
        return client;
    }

    public CefBrowser front() {
        return front;
    }

    public CefBrowser back() {
        return back;
    }

    /** @return the browser without a view, or null with windowed rendering */
    public CefBrowser detached() {
        return detached;
    }

    /** @return the window's frame, or null before it exists */
    public JFrame frame() {
        WebHostWindow w = window;
        return w == null ? null : w.frame();
    }

    /** @return whether {@link #shutdown()} was called */
    public boolean isShutDown() {
        return shutDown.get();
    }

    // -------------------------------------------------------------------------------------------- CEF callbacks

    /** Logs jcefmaven's stages, once each. Called on the thread that builds the CEF application. */
    private static final class ProgressLog implements IProgressHandler {
        private EnumProgress last;

        @Override
        public void handleProgress(EnumProgress state, float percent) {
            if (state != last) {
                last = state;
                LOG.info("web host: jcefmaven " + state.name().toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Logs CEF's state changes and notes {@code TERMINATED} for {@link #shutdown()}. */
    private static final class StateLog extends MavenCefAppHandlerAdapter {
        @Override
        public void stateHasChanged(CefApp.CefAppState state) {
            super.stateHasChanged(state);
            LOG.info("web host: CEF state " + state);
            if (state == CefApp.CefAppState.TERMINATED) {
                TERMINATED.countDown();
            }
        }
    }

    /**
     * The host's load handler (the host's own handler slot of its client): notes when each page's main frame has
     * loaded. Called on CEF's UI thread (the event dispatch thread with off-screen rendering); never blocks.
     */
    private static final class LoadTracker extends CefLoadHandlerAdapter {
        private final Map<CefBrowser, String> pages =
                Collections.synchronizedMap(new IdentityHashMap<CefBrowser, String>());
        private final ConcurrentMap<String, CountDownLatch> loaded = new ConcurrentHashMap<String, CountDownLatch>();

        void expect(CefBrowser browser, String page) {
            pages.put(browser, page);
            latch(page);
        }

        boolean await(String page, long timeoutNanos) throws InterruptedException {
            return latch(page).await(Math.max(0L, timeoutNanos), TimeUnit.NANOSECONDS);
        }

        private CountDownLatch latch(String page) {
            CountDownLatch latch = loaded.get(page);
            if (latch == null) {
                loaded.putIfAbsent(page, new CountDownLatch(1));
                latch = loaded.get(page);
            }
            return latch;
        }

        @Override
        public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
            String page = pages.get(browser);
            if (page == null || frame == null || !frame.isMain()) {
                return;
            }
            LOG.info("web host: the " + page + " page loaded (HTTP status " + httpStatusCode + ")");
            latch(page).countDown();
        }

        @Override
        public void onLoadError(CefBrowser browser, CefFrame frame, CefLoadHandler.ErrorCode errorCode,
                                String errorText, String failedUrl) {
            String page = pages.get(browser);
            if (page == null || frame == null || !frame.isMain()) {
                return;
            }
            LOG.warning("web host: the " + page + " page failed to load: " + errorCode + " " + errorText);
        }
    }

    /** For tests: the pages this host shows ({@code detached} only off screen). */
    List<String> pageNames() {
        return new ArrayList<String>(pages());
    }
}
