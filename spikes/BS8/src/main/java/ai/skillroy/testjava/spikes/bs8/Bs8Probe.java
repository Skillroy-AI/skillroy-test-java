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

package ai.skillroy.testjava.spikes.bs8;

import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.IProgressHandler;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;

/**
 * Spike BS8: a JCEF host that answers, by running them, the "not verified" items of the research note
 * {@code docs/research/embedded-browser-spi.md} (F1, F8-F12, F14, F15) under the WM-less Xvfb of the Linux test image.
 *
 * <p>The program is a <em>target application</em> for the purpose of the spike: it embeds JCEF the way an application
 * would (through jcefmaven's {@code CefAppBuilder}) and shows two browsers in one frame, one visible and one in a
 * hidden card of a {@code CardLayout} (a cached, hidden browser). Every measurement goes through the public
 * {@code CefBrowser.getDevToolsClient()} channel, except the reflective enumeration, which uses the private members
 * the research note names. Nothing here is agent code; the agent module of Phase 4 is written against what this
 * program establishes.
 *
 * <p>Arguments: {@code --mode windowed|osr} (default windowed), {@code --out <dir>} (results: result.json, PNGs,
 * cef.log), {@code --install <dir>} (where jcefmaven extracts the natives), {@code --port <n>} (CEF's
 * remote-debugging port; 0 = none), {@code --arg <chromium switch>} (repeatable), {@code --budget <seconds>} (the
 * watchdog; default 240). Every step is recorded with its duration and either a result or the error it threw, so one
 * failing step never hides the others. Exit code 0 when the program ran to its end, 2 when the watchdog ended it.
 */
public final class Bs8Probe {

    private static final long STEP_TIMEOUT_MS = 30000L;
    private static final String VISIBLE = "visible";
    private static final String HIDDEN = "hidden";

    private final Map<String, Object> result = new LinkedHashMap<String, Object>();
    private final List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
    private final List<String> events = new CopyOnWriteArrayList<String>();
    private final Path out;
    private final boolean osr;
    private final int port;
    private final long started = System.nanoTime();
    private PrintStream log;
    private String settingsToApply = "port,cache,log";
    private boolean acceptTos = true;
    private boolean createHidden = false;
    private static boolean SIMPLE_PAGE = true;
    private static boolean IFRAME;
    private JFrame frame;
    private CardLayout cards;
    private JPanel deck;

    private Bs8Probe(Path out, boolean osr, int port) {
        this.out = out;
        this.osr = osr;
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        String mode = "windowed";
        Path out = Paths.get("bs8-out");
        Path install = Paths.get("jcef-bundle");
        int port = 0;
        long budgetSeconds = 240;
        List<String> switches = new ArrayList<String>();
        String settingsToApply = "port,cache,log";
        boolean acceptTos = true;
        boolean createHidden = false;
        boolean richPage = false;
        boolean iframe = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String v = i + 1 < args.length ? args[i + 1] : null;
            if ("--mode".equals(a)) {
                mode = v;
                i++;
            } else if ("--out".equals(a)) {
                out = Paths.get(v);
                i++;
            } else if ("--install".equals(a)) {
                install = Paths.get(v);
                i++;
            } else if ("--port".equals(a)) {
                port = Integer.parseInt(v);
                i++;
            } else if ("--arg".equals(a)) {
                switches.add(v);
                i++;
            } else if ("--budget".equals(a)) {
                budgetSeconds = Long.parseLong(v);
                i++;
            } else if ("--accept-tos".equals(a)) {
                // default true: pre-create the sentinel files "First Run" and "EULA Accepted" in the cache root, which
                // is what accepting Chromium 152's first-run "Additional Terms of Service" dialog writes; without them
                // CefInitialize blocks until someone clicks Accept (found by BS8, see RESULT.md)
                acceptTos = Boolean.parseBoolean(v);
                i++;
            } else if ("--rich-page".equals(a)) {
                // the first page of the spike (an iframe with srcdoc, an inline script, a form): a renderer never
                // finished loading it in the image (RESULT.md), so the default is the plain page
                richPage = Boolean.parseBoolean(v);
                i++;
            } else if ("--iframe".equals(a)) {
                // adds an iframe whose src is a data: URL to the plain page (the frame question, F7)
                iframe = Boolean.parseBoolean(v);
                i++;
            } else if ("--create-hidden".equals(a)) {
                // call createImmediately() on the hidden browser when its native browser did not appear by itself;
                // default false, because in off-screen mode that call stalled every later DevTools reply (RESULT.md)
                createHidden = Boolean.parseBoolean(v);
                i++;
            } else if ("--settings".equals(a)) {
                // which CefSettings the probe sets beside the rendering mode: a comma-separated subset of
                // port,cache,log, or "none" (jcefmaven's defaults for everything else)
                settingsToApply = v;
                i++;
            } else {
                throw new IllegalArgumentException("unknown argument " + a);
            }
        }
        final boolean osr = "osr".equalsIgnoreCase(mode);
        Files.createDirectories(out);
        final Bs8Probe probe = new Bs8Probe(out, osr, port);
        probe.settingsToApply = settingsToApply;
        probe.acceptTos = acceptTos;
        probe.createHidden = createHidden;
        SIMPLE_PAGE = !richPage;
        IFRAME = iframe;
        probe.result.put("page", richPage ? "rich" : iframe ? "plain+iframe" : "plain");
        probe.log = new PrintStream(new FileOutputStream(out.resolve("probe.log").toFile()), true, "UTF-8");
        probe.watchdog(budgetSeconds);
        int code;
        try {
            probe.run(install, switches);
            code = 0;
        } catch (Throwable t) {
            probe.result.put("fatal", String.valueOf(t));
            probe.say("FATAL " + t);
            t.printStackTrace(probe.log);
            code = 1;
        }
        probe.finish();
        // CEF keeps non-daemon threads alive; the result is written, so end the JVM here.
        Runtime.getRuntime().halt(code);
    }

    // ------------------------------------------------------------------------------------------------- the run

    private void run(Path install, List<String> switches) throws Exception {
        result.put("mode", osr ? "osr" : "windowed");
        result.put("remoteDebuggingPort", port);
        result.put("java.version", System.getProperty("java.version"));
        result.put("java.vendor", System.getProperty("java.vendor"));
        result.put("java.home", System.getProperty("java.home"));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        result.put("display", System.getenv("DISPLAY"));
        result.put("java.awt.headless", System.getProperty("java.awt.headless"));
        result.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("chromiumSwitches", switches);
        result.put("steps", steps);
        result.put("devtoolsEvents", events);

        // 1. CEF start-up. Windowed mode on Linux needs CefApp.startup() before the first AWT call (F4, F15), and
        //    jcefmaven's build() does exactly that, so nothing of AWT may be touched before this returns.
        final Map<String, Object> startup = new LinkedHashMap<String, Object>();
        result.put("startup", startup);
        final long t0 = System.nanoTime();
        final List<String> phases = new ArrayList<String>();
        startup.put("phases", phases);
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(install.toFile());
        builder.setProgressHandler(new IProgressHandler() {
            @Override
            public void handleProgress(EnumProgress state, float percent) {
                String line = state + (percent >= 0 ? " " + percent + "%" : "") + " @" + ms(t0);
                if (percent < 0 || percent >= 100f) {
                    phases.add(line);
                }
                say("jcefmaven " + line);
            }
        });
        for (String s : switches) {
            builder.addJcefArgs(s);
        }
        CefSettings settings = builder.getCefSettings();
        settings.windowless_rendering_enabled = osr;
        result.put("settingsApplied", settingsToApply);
        if (settingsToApply.contains("port")) {
            settings.remote_debugging_port = port;
        }
        if (settingsToApply.contains("cache")) {
            // Chromium's cache root goes to the JVM's temporary directory, not under --out: --out may be a directory
            // shared with the container's host (see RESULT.md).
            Path cache = Files.createTempDirectory("bs8-cef-cache-");
            settings.root_cache_path = cache.toAbsolutePath().toString();
            result.put("cacheDir", cache.toString());
            if (acceptTos) {
                Files.createFile(cache.resolve("First Run"));
                Files.createFile(cache.resolve("EULA Accepted"));
            }
        }
        result.put("acceptTos", acceptTos);
        if (settingsToApply.contains("log")) {
            settings.log_file = out.resolve("cef.log").toAbsolutePath().toString();
            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_INFO;
        }
        final CefApp app = builder.build();
        startup.put("buildMs", ms(t0));
        startup.put("stateAfterBuild", String.valueOf(CefApp.getState()));
        startup.put("version", String.valueOf(app.getVersion()));
        startup.put("installDirBytes", dirSize(install));
        say("CefApp built in " + ms(t0) + " ms, state " + CefApp.getState());

        // 2. Two browsers in one frame: one visible, one in a hidden card.
        final CefClient client = app.createClient();
        final CefBrowser visible = client.createBrowser(pageUrl("one"), osr, false);
        final CefBrowser hidden = client.createBrowser(pageUrl("two"), osr, false);
        final long tUi = System.nanoTime();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                frame = new JFrame("BS8 " + (osr ? "osr" : "windowed"));
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                cards = new CardLayout();
                deck = new JPanel(cards);
                deck.add(visible.getUIComponent(), VISIBLE);
                deck.add(hidden.getUIComponent(), HIDDEN);
                frame.getContentPane().add(deck);
                frame.setSize(800, 600);
                frame.setLocation(0, 0);
                frame.setVisible(true);
                cards.show(deck, VISIBLE);
            }
        });
        startup.put("frameShownMs", ms(tUi));
        say("frame shown " + ms(tUi) + " ms after the browsers were created");
        result.put("uiComponent", describe(visible.getUIComponent()));

        // 3. The native browsers and their documents.
        final Map<String, Object> browsers = new LinkedHashMap<String, Object>();
        result.put("browsers", browsers);
        browsers.put(VISIBLE, awaitBrowser(visible, tUi));
        browsers.put(HIDDEN, awaitBrowser(hidden, tUi));
        result.put("edtMarkerMsBeforeChannel", edtMarkerMs());
        if (hidden.getDevToolsClient() == null && createHidden) {
            step("hidden.createImmediately", new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    hidden.createImmediately();
                    return awaitBrowser(hidden, System.nanoTime());
                }
            });
        }

        // 4. The DevTools channel on the visible browser.
        final CefDevToolsClient dt = visible.getDevToolsClient();
        result.put("devtoolsClientNull", dt == null);
        if (dt != null) {
            dt.addEventListener(new CefDevToolsClient.EventListener() {
                @Override
                public void onEvent(String eventName, String messageAsJson) {
                    if (events.size() < 500) {
                        events.add(eventName + " " + abbreviate(messageAsJson, 160) + " on " + Thread.currentThread().getName());
                    }
                }
            });
            probeChannel(dt, visible, hidden);
        }

        // 5. Screens: protocol screenshots in both modes, the off-screen-only createScreenshot, Swing's printAll.
        probeImages(visible, hidden);

        result.put("edtMarkerMsAfterChannel", edtMarkerMs());

        // 6. Enumeration without a public API (F5): CefApp.self -> clients_ -> getAllBrowser().
        step("reflectiveEnumeration", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return enumerate(app, visible, hidden);
            }
        });

        // 7. Process facts.
        result.put("process", processFacts());

        // 8. Shutdown.
        final long tClose = System.nanoTime();
        step("close", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                visible.close(true);
                hidden.close(true);
                Thread.sleep(500);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("devToolsClientAfterClose.visible", visible.getDevToolsClient() == null ? "null" : "present");
                client.dispose();
                app.dispose();
                m.put("disposeMs", ms(tClose));
                m.put("stateAfterDispose", String.valueOf(CefApp.getState()));
                return m;
            }
        });
    }

    // ------------------------------------------------------------------------------------- the browser life cycle

    private Map<String, Object> awaitBrowser(final CefBrowser b, long since) throws Exception {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        // The native browser exists once getDevToolsClient() answers (F9); bounded, because a browser whose component
        // never becomes displayable (windowed mode, no window) has no native browser.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (b.getDevToolsClient() == null && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        m.put("devToolsClientAfterMs", b.getDevToolsClient() == null ? "never" : ms(since));
        say("browser " + b.getIdentifier() + ": devtools client " + m.get("devToolsClientAfterMs") + " ms");
        // The document: JCEF's hasDocument()/isLoading() are recorded as facts; the readiness that counts is what the
        // page itself answers through the channel (document.readyState), bounded at 30 s.
        long docDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        String ready = null;
        CefDevToolsClient dt = b.getDevToolsClient();
        while (dt != null && System.nanoTime() < docDeadline) {
            try {
                String r = dt.executeDevToolsMethod("Runtime.evaluate",
                        "{\"expression\":\"document.readyState + ' ' + document.title\",\"returnByValue\":true}")
                        .get(5, TimeUnit.SECONDS);
                if (r != null && r.contains("complete")) {
                    ready = r;
                    break;
                }
            } catch (Exception e) {
                m.put("readyStateError", String.valueOf(e));
            }
            Thread.sleep(100);
        }
        m.put("documentCompleteAfterMs", ready == null ? "never" : ms(since));
        m.put("readyStateReply", ready);
        m.put("jcef.hasDocument", b.hasDocument());
        m.put("jcef.isLoading", b.isLoading());
        say("browser " + b.getIdentifier() + ": document " + m.get("documentCompleteAfterMs") + " ms, hasDocument="
                + b.hasDocument() + " isLoading=" + b.isLoading());
        m.put("url", b.getURL());
        m.put("identifier", b.getIdentifier());
        m.put("isLoading", b.isLoading());
        m.put("frameCount", b.getFrameCount());
        m.put("frameIdentifiers", b.getFrameIdentifiers() == null ? "null" : new ArrayList<String>(b.getFrameIdentifiers()));
        m.put("frameNames", b.getFrameNames() == null ? "null" : new ArrayList<String>(b.getFrameNames()));
        final Component c = b.getUIComponent();
        final Map<String, Object> comp = new LinkedHashMap<String, Object>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                comp.put("showing", c.isShowing());
                comp.put("displayable", c.isDisplayable());
                comp.put("size", c.getWidth() + "x" + c.getHeight());
                comp.put("lightweight", c.isLightweight());
            }
        });
        m.put("component", comp);
        return m;
    }

    // ---------------------------------------------------------------------------------------- the DevTools channel

    private void probeChannel(final CefDevToolsClient dt, final CefBrowser visible, final CefBrowser hidden)
            throws Exception {
        // F8: Runtime.evaluate with a value, timed.
        step("evaluate.roundTrip", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                long[] nanos = new long[20];
                String last = null;
                for (int i = 0; i < nanos.length; i++) {
                    long t = System.nanoTime();
                    last = rpc(dt, "Runtime.evaluate", "{\"expression\":\"1+1\",\"returnByValue\":true}");
                    nanos[i] = System.nanoTime() - t;
                }
                Arrays.sort(nanos);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("result", last);
                m.put("p50Ms", nanos[nanos.length / 2] / 1e6);
                m.put("minMs", nanos[0] / 1e6);
                m.put("maxMs", nanos[nanos.length - 1] / 1e6);
                return m;
            }
        });
        // On which thread does the future complete (C1: with off-screen rendering the research note expects the EDT)?
        step("evaluate.completionThread", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                final AtomicReference<String> thread = new AtomicReference<String>();
                CompletableFuture<String> f = dt.executeDevToolsMethod("Runtime.evaluate",
                        "{\"expression\":\"new Promise(r => setTimeout(() => r(7), 30))\",\"awaitPromise\":true,\"returnByValue\":true}");
                boolean doneAtRegistration = f.isDone();
                f.whenComplete((r, e) -> thread.set(Thread.currentThread().getName()));
                String r = f.get(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                Thread.sleep(50);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("awaitPromiseResult", r);
                m.put("doneAtRegistration", doneAtRegistration);
                m.put("completedOn", thread.get());
                m.put("edtName", edtName());
                return m;
            }
        });
        step("evaluate.scriptError", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return rpc(dt, "Runtime.evaluate", "{\"expression\":\"throw new Error('bs8 boom')\",\"returnByValue\":true}");
            }
        });
        step("evaluate.rejectedPromise", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return rpc(dt, "Runtime.evaluate",
                        "{\"expression\":\"Promise.reject(new Error('bs8 rejected'))\",\"awaitPromise\":true,\"returnByValue\":true}");
            }
        });
        step("evaluate.undefinedAndObject", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("undefined", rpc(dt, "Runtime.evaluate", "{\"expression\":\"undefined\",\"returnByValue\":true}"));
                m.put("object", rpc(dt, "Runtime.evaluate",
                        "{\"expression\":\"({a:1,b:[1,2],c:'x'})\",\"returnByValue\":true}"));
                m.put("jsonText", rpc(dt, "Runtime.evaluate",
                        "{\"expression\":\"JSON.stringify({t:document.title,u:location.href.slice(0,20)})\",\"returnByValue\":true}"));
                return m;
            }
        });
        // F14: the CDP target id, and whether /json/list knows it when a port is configured.
        step("targetInfo", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                if (port > 0) {
                    m.put("jsonVersion", abbreviate(httpGet("http://127.0.0.1:" + port + "/json/version"), 600));
                    m.put("jsonList", abbreviate(httpGet("http://127.0.0.1:" + port + "/json/list"), 2000));
                }
                m.put("Page.getFrameTree.mainFrameId", abbreviate(rpc(dt, "Page.getFrameTree", "{}"), 120));
                result.put("targetInfo.partial", m);
                try {
                    m.put("Target.getTargetInfo", rpc(dt, "Target.getTargetInfo", "{}"));
                } catch (Exception e) {
                    m.put("Target.getTargetInfo", "failed: " + e);
                }
                try {
                    m.put("Target.getTargets", rpc(dt, "Target.getTargets", "{}"));
                } catch (Exception e) {
                    m.put("Target.getTargets", "failed: " + e);
                }
                return m;
            }
        });
        // F10: events through the shared client.
        step("events.enable", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("Page.enable", rpc(dt, "Page.enable", "{}"));
                m.put("Runtime.enable", rpc(dt, "Runtime.enable", "{}"));
                return m;
            }
        });
        step("events.console", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                int before = events.size();
                rpc(dt, "Runtime.evaluate", "{\"expression\":\"console.log('bs8-console-marker')\",\"returnByValue\":true}");
                String seen = awaitEvent("bs8-console-marker", before, 10000);
                return seen == null ? "console event NOT seen within 10 s" : seen;
            }
        });
        // F10: a document-start script, proven by a page whose own inline script looks for it.
        step("initScript.documentStart", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                result.put("initScript.partial", m);
                m.put("add", rpc(dt, "Page.addScriptToEvaluateOnNewDocument",
                        "{\"source\":\"window.__bs8Init = Date.now(); window.__bs8InitReadyState = document.readyState;\"}"));
                int before = events.size();
                long t = System.nanoTime();
                visible.loadURL(pageUrl("three"));
                String load = awaitEvent("Page.loadEventFired", before, 20000);
                m.put("loadEventFired", load != null);
                m.put("loadMs", ms(t));
                // The page's own inline script copied typeof window.__bs8Init into window.__seenAtPageScript. The
                // first evaluate after a navigation has been seen to time out (RESULT.md): one bounded retry.
                String check = "{\"expression\":\"JSON.stringify({initType: typeof window.__bs8Init, seenAtPageScript: window.__seenAtPageScript, readyStateAtInit: window.__bs8InitReadyState, title: document.title})\",\"returnByValue\":true}";
                for (int attempt = 1; attempt <= 2; attempt++) {
                    try {
                        m.put("check", dt.executeDevToolsMethod("Runtime.evaluate", check).get(10, TimeUnit.SECONDS));
                        m.put("checkAttempts", attempt);
                        break;
                    } catch (Exception e) {
                        m.put("checkAttempt" + attempt, String.valueOf(e));
                        Thread.sleep(500);
                    }
                }
                return m;
            }
        });
        // F7: CEF frame identifiers against the protocol's frame tree and execution contexts.
        step("frames", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("cef.frameIdentifiers", visible.getFrameIdentifiers() == null ? "null" : new ArrayList<String>(visible.getFrameIdentifiers()));
                m.put("cef.frameNames", visible.getFrameNames() == null ? "null" : new ArrayList<String>(visible.getFrameNames()));
                m.put("cef.mainFrameId", visible.getMainFrame() == null ? null : visible.getMainFrame().getIdentifier());
                m.put("Page.getFrameTree", abbreviate(rpc(dt, "Page.getFrameTree", "{}"), 1500));
                List<String> contexts = new ArrayList<String>();
                for (String e : events) {
                    if (e.startsWith("Runtime.executionContextCreated")) {
                        contexts.add(e);
                    }
                }
                m.put("executionContextCreated", contexts);
                return m;
            }
        });
        // F12: a viewport override on the hidden browser, read back from the page.
        step("hidden.viewportOverride", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                CefDevToolsClient hdt = hidden.getDevToolsClient();
                if (hdt == null) {
                    return "no devtools client on the hidden browser";
                }
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                String size = "{\"expression\":\"JSON.stringify({w:innerWidth,h:innerHeight,vis:document.visibilityState,title:document.title})\",\"returnByValue\":true}";
                m.put("before", rpc(hdt, "Runtime.evaluate", size));
                m.put("override", rpc(hdt, "Emulation.setDeviceMetricsOverride",
                        "{\"width\":1024,\"height\":768,\"deviceScaleFactor\":1,\"mobile\":false}"));
                Thread.sleep(300);
                m.put("after", rpc(hdt, "Runtime.evaluate", size));
                m.put("clear", rpc(hdt, "Emulation.clearDeviceMetricsOverride", "{}"));
                Thread.sleep(300);
                m.put("cleared", rpc(hdt, "Runtime.evaluate", size));
                return m;
            }
        });
        // F16: trusted input through the protocol.
        step("input.click", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                String rect = rpc(dt, "Runtime.evaluate",
                        "{\"expression\":\"JSON.stringify((r => ({x: r.left + r.width / 2, y: r.top + r.height / 2}))(document.getElementById('go').getBoundingClientRect()))\",\"returnByValue\":true}");
                m.put("rect", rect);
                String inner = unquoteValue(rect);
                double x = Double.parseDouble(inner.replaceAll(".*\"x\":([0-9.]+).*", "$1"));
                double y = Double.parseDouble(inner.replaceAll(".*\"y\":([0-9.]+).*", "$1"));
                rpc(dt, "Input.dispatchMouseEvent", "{\"type\":\"mouseMoved\",\"x\":" + x + ",\"y\":" + y + "}");
                rpc(dt, "Input.dispatchMouseEvent", "{\"type\":\"mousePressed\",\"x\":" + x + ",\"y\":" + y
                        + ",\"button\":\"left\",\"clickCount\":1}");
                rpc(dt, "Input.dispatchMouseEvent", "{\"type\":\"mouseReleased\",\"x\":" + x + ",\"y\":" + y
                        + ",\"button\":\"left\",\"clickCount\":1}");
                Thread.sleep(300);
                m.put("clicks", rpc(dt, "Runtime.evaluate", "{\"expression\":\"window.__clicks\",\"returnByValue\":true}"));
                return m;
            }
        });
        // F8: the hidden browser evaluates too (a cached browser is reachable without being shown).
        step("hidden.evaluate", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                CefDevToolsClient hdt = hidden.getDevToolsClient();
                return hdt == null ? "no devtools client" : rpc(hdt, "Runtime.evaluate",
                        "{\"expression\":\"document.title + ' ' + document.visibilityState\",\"returnByValue\":true}");
            }
        });
        // F9: the client is one per browser and AutoCloseable; is a second getDevToolsClient() the same object?
        step("devtools.identity", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("sameInstance", visible.getDevToolsClient() == dt);
                m.put("isClosed", dt.isClosed());
                return m;
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------ images

    private void probeImages(final CefBrowser visible, final CefBrowser hidden) {
        step("screenshot.protocol.visible", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return protocolScreenshot(visible.getDevToolsClient(), "protocol-visible.png");
            }
        });
        step("screenshot.protocol.hidden", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return protocolScreenshot(hidden.getDevToolsClient(), "protocol-hidden.png");
            }
        });
        step("screenshot.createScreenshot", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                CompletableFuture<BufferedImage> f = visible.createScreenshot(false);
                BufferedImage img = f.get(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return imageFacts(img, "createScreenshot-visible.png");
            }
        });
        step("printAll.component", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return printAll(visible.getUIComponent(), "printAll-component.png");
            }
        });
        step("printAll.frame", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return printAll(frame, "printAll-frame.png");
            }
        });
    }

    private Object protocolScreenshot(CefDevToolsClient dt, String file) throws Exception {
        if (dt == null) {
            return "no devtools client";
        }
        long t = System.nanoTime();
        String reply = rpc(dt, "Page.captureScreenshot", "{\"format\":\"png\"}");
        int i = reply.indexOf("\"data\":\"");
        if (i < 0) {
            return "no data in reply: " + abbreviate(reply, 300);
        }
        int j = reply.indexOf('"', i + 8);
        byte[] png = Base64.getDecoder().decode(reply.substring(i + 8, j));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        Map<String, Object> m = imageFacts(img, file);
        m.put("ms", (System.nanoTime() - t) / 1e6);
        m.put("pngBytes", png.length);
        return m;
    }

    private Object printAll(final Component c, final String file) throws Exception {
        final AtomicReference<Object> ref = new AtomicReference<Object>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    int w = Math.max(1, c.getWidth());
                    int h = Math.max(1, c.getHeight());
                    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    try {
                        c.printAll(g);
                    } finally {
                        g.dispose();
                    }
                    ref.set(imageFacts(img, file));
                } catch (Throwable t) {
                    ref.set("printAll failed: " + t);
                }
            }
        });
        return ref.get();
    }

    private Map<String, Object> imageFacts(BufferedImage img, String file) throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("width", img.getWidth());
        m.put("height", img.getHeight());
        Set<Integer> colours = new HashSet<Integer>();
        int stepX = Math.max(1, img.getWidth() / 200);
        int stepY = Math.max(1, img.getHeight() / 200);
        for (int y = 0; y < img.getHeight(); y += stepY) {
            for (int x = 0; x < img.getWidth(); x += stepX) {
                colours.add(img.getRGB(x, y) & 0xffffff);
                if (colours.size() > 64) {
                    break;
                }
            }
        }
        m.put("distinctColoursSampled", colours.size() > 64 ? "more than 64" : String.valueOf(colours.size()));
        ImageIO.write(img, "png", out.resolve(file).toFile());
        m.put("file", file);
        return m;
    }

    // ------------------------------------------------------------------------------------------------ enumeration

    private static Object enumerate(CefApp app, CefBrowser visible, CefBrowser hidden) throws Exception {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("CefApp.getState", String.valueOf(CefApp.getState()));
        Field self = CefApp.class.getDeclaredField("self");
        self.setAccessible(true);
        Object instance = self.get(null);
        m.put("self.isTheBuiltApp", instance == app);
        Field clientsField = CefApp.class.getDeclaredField("clients_");
        clientsField.setAccessible(true);
        Set<?> clients = (Set<?>) clientsField.get(instance);
        m.put("clients", clients.size());
        Method getAll = CefClient.class.getDeclaredMethod("getAllBrowser");
        getAll.setAccessible(true);
        List<Integer> ids = new ArrayList<Integer>();
        List<String> classes = new ArrayList<String>();
        for (Object c : clients) {
            Object[] all = (Object[]) getAll.invoke(c);
            for (Object b : all) {
                ids.add(((CefBrowser) b).getIdentifier());
                classes.add(b.getClass().getName());
            }
        }
        Collections.sort(ids);
        m.put("browserIdentifiers", ids);
        m.put("browserClasses", classes);
        m.put("expectedIdentifiers", Arrays.asList(visible.getIdentifier(), hidden.getIdentifier()));
        Field settings = CefApp.class.getDeclaredField("settings_");
        settings.setAccessible(true);
        CefSettings s = (CefSettings) settings.get(instance);
        m.put("settings_.remote_debugging_port", s == null ? null : s.remote_debugging_port);
        m.put("settings_.windowless_rendering_enabled", s == null ? null : s.windowless_rendering_enabled);
        return m;
    }

    // ---------------------------------------------------------------------------------------------- the process

    private static Map<String, Object> processFacts() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        Runtime rt = Runtime.getRuntime();
        m.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        m.put("threads", Thread.activeCount());
        List<String> names = new ArrayList<String>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            names.add(t.getName());
        }
        Collections.sort(names);
        m.put("threadNames", names);
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.UTF_8)) {
                if (line.startsWith("VmRSS") || line.startsWith("VmHWM")) {
                    m.put(line.substring(0, line.indexOf(':')), line.substring(line.indexOf(':') + 1).trim());
                }
            }
            int helpers = 0;
            long helperRss = 0;
            File[] procs = new File("/proc").listFiles();
            if (procs != null) {
                for (File p : procs) {
                    if (!p.getName().matches("[0-9]+")) {
                        continue;
                    }
                    try {
                        byte[] cmd = Files.readAllBytes(p.toPath().resolve("cmdline"));
                        if (new String(cmd, StandardCharsets.UTF_8).contains("jcef_helper")) {
                            helpers++;
                            for (String line : Files.readAllLines(p.toPath().resolve("status"), StandardCharsets.UTF_8)) {
                                if (line.startsWith("VmRSS")) {
                                    helperRss += Long.parseLong(line.replaceAll("[^0-9]", ""));
                                }
                            }
                        }
                    } catch (IOException ignored) {
                        // a process that ended in between
                    }
                }
            }
            m.put("jcefHelperProcesses", helpers);
            m.put("jcefHelperRssKbTotal", helperRss);
        } catch (IOException e) {
            m.put("proc", "unreadable: " + e);
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static String rpc(CefDevToolsClient dt, String method, String params) throws Exception {
        return dt.executeDevToolsMethod(method, params).get(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private String awaitEvent(String contains, int from, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            for (int i = from; i < events.size(); i++) {
                if (events.get(i).contains(contains)) {
                    return events.get(i);
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    private void step(String name, Callable<Object> body) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("step", name);
        long t = System.nanoTime();
        try {
            m.put("result", body.call());
        } catch (Throwable e) {
            m.put("error", String.valueOf(e) + (e.getCause() != null ? " <- " + e.getCause() : ""));
            e.printStackTrace(log);
        }
        m.put("ms", ms(t));
        steps.add(m);
        say(name + " -> " + abbreviate(String.valueOf(m.containsKey("error") ? m.get("error") : m.get("result")), 300));
    }

    private static String unquoteValue(String evaluateReply) {
        // {"result":{"type":"string","value":"{\"x\":1,\"y\":2}"}} -> {"x":1,"y":2}
        int i = evaluateReply.indexOf("\"value\":\"");
        if (i < 0) {
            return evaluateReply;
        }
        int j = evaluateReply.indexOf("\"}", i + 9);
        String raw = evaluateReply.substring(i + 9, j < 0 ? evaluateReply.length() : j);
        return raw.replace("\\\"", "\"");
    }

    /** How long a marker posted to the EDT takes to run (bounded at 5 s): a stalled pump shows here. */
    private static Object edtMarkerMs() {
        final long t = System.nanoTime();
        final AtomicReference<Long> done = new AtomicReference<Long>();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                done.set(System.nanoTime() - t);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (done.get() == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
        }
        return done.get() == null ? "EDT did not run a marker within 5 s" : done.get() / 1e6;
    }

    private static String edtName() throws Exception {
        final AtomicReference<String> name = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                name.set(Thread.currentThread().getName());
            }
        });
        return name.get();
    }

    private static Object describe(Component c) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("class", c.getClass().getName());
        m.put("lightweight", c.isLightweight());
        if (c instanceof Container) {
            List<String> kids = new ArrayList<String>();
            for (Component k : ((Container) c).getComponents()) {
                kids.add(k.getClass().getName() + (k.isLightweight() ? " (lightweight)" : " (heavyweight)"));
            }
            m.put("children", kids);
        }
        return m;
    }

    private static String httpGet(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            InputStream in = c.getInputStream();
            try {
                return new String(readAll(in), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "GET failed: " + e;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static long dirSize(Path dir) {
        final long[] total = {0};
        try {
            Files.walk(dir).forEach(p -> {
                if (Files.isRegularFile(p)) {
                    try {
                        total[0] += Files.size(p);
                    } catch (IOException ignored) {
                        // counted as zero
                    }
                }
            });
        } catch (IOException e) {
            return -1;
        }
        return total[0];
    }

    /** A small page: a title, a counting button, an iframe, and an inline script that looks for the init script. */
    static String pageUrl(String name) {
        if (SIMPLE_PAGE) {
            // The plain page: title, heading, a counting button, the init-script witness, and optionally an iframe
            // whose src is a data: URL.
            return "data:text/html,<title>bs8 " + name + "</title><body style='background:%23"
                    + ("one".equals(name) ? "dfe9ff" : "two".equals(name) ? "ffe9df" : "e9ffdf") + "'>"
                    + "<h1 id='h'>Page " + name + "</h1>"
                    + "<button id='go' style='font-size:20px;padding:12px' onclick='window.__clicks=(window.__clicks||0)+1'>Count</button>"
                    + (IFRAME ? "<iframe name='inner' src='data:text/html,<p>inner frame of " + name + "</p>' style='width:300px;height:80px'></iframe>" : "")
                    + "<script>window.__seenAtPageScript = typeof window.__bs8Init; window.__pageName = '" + name + "';</script>";
        }
        String html = "<!doctype html><html><head><meta charset='utf-8'><title>bs8 " + name + "</title></head>"
                + "<body style='margin:0;background:#" + ("one".equals(name) ? "dfe9ff" : "two".equals(name) ? "ffe9df" : "e9ffdf")
                + ";font:16px sans-serif'>"
                + "<h1 id='h'>Page " + name + "</h1>"
                + "<button id='go' style='font-size:20px;padding:12px' onclick='window.__clicks=(window.__clicks||0)+1;document.getElementById(\"n\").textContent=window.__clicks'>Count</button>"
                + " <span id='n'>0</span>"
                + "<p><input id='t' value='hello'></p>"
                + "<iframe name='inner' srcdoc='&lt;p&gt;inner frame of " + name + "&lt;/p&gt;' style='width:300px;height:80px'></iframe>"
                + "<script>window.__seenAtPageScript = typeof window.__bs8Init; window.__pageName = '" + name + "';</script>"
                + "</body></html>";
        try {
            return "data:text/html;charset=utf-8," + URLEncoder.encode(html, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void watchdog(final long budgetSeconds) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(budgetSeconds));
                } catch (InterruptedException e) {
                    return;
                }
                result.put("watchdog", "budget of " + budgetSeconds + " s exhausted; partial results written");
                say("WATCHDOG: budget exhausted");
                finish();
                Runtime.getRuntime().halt(2);
            }
        }, "bs8-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void finish() {
        result.put("totalMs", ms(started));
        try {
            byte[] json = Json.write(result).getBytes(StandardCharsets.UTF_8);
            OutputStream o = new FileOutputStream(out.resolve("result.json").toFile());
            try {
                o.write(json);
            } finally {
                o.close();
            }
            say("result.json written (" + json.length + " bytes)");
        } catch (IOException e) {
            say("could not write result.json: " + e);
        }
    }

    private void say(String s) {
        String line = "[bs8 " + ms(started) + " ms] " + s;
        System.out.println(line);
        if (log != null) {
            log.println(line);
        }
    }

    private static long ms(long sinceNanos) {
        return (System.nanoTime() - sinceNanos) / 1000000L;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...[" + (s.length() - max) + " more]";
    }

    /** A minimal JSON writer for the result (maps, lists, strings, numbers, booleans, null). */
    static final class Json {
        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            write(sb, value, 0);
            return sb.toString();
        }

        private static void write(StringBuilder sb, Object v, int depth) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof String) {
                quote(sb, (String) v);
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof Map) {
                sb.append("{\n");
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    if (!first) {
                        sb.append(",\n");
                    }
                    first = false;
                    indent(sb, depth + 1);
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(": ");
                    write(sb, e.getValue(), depth + 1);
                }
                sb.append('\n');
                indent(sb, depth);
                sb.append('}');
            } else if (v instanceof Iterable) {
                sb.append('[');
                boolean first = true;
                for (Object o : (Iterable<?>) v) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    write(sb, o, depth + 1);
                }
                sb.append(']');
            } else {
                quote(sb, String.valueOf(v));
            }
        }

        private static void indent(StringBuilder sb, int depth) {
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }
    }
}
