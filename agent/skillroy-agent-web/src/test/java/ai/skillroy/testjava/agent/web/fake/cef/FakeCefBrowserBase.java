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

package ai.skillroy.testjava.agent.web.fake.cef;

import ai.skillroy.testjava.agent.web.fake.FakeDocument;
import ai.skillroy.testjava.agent.web.fake.FakeScript;
import ai.skillroy.testjava.agent.web.kit.ClassInitialisations;
import ai.skillroy.testjava.agent.web.kit.KitPages;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * The fake CEF browser and the part of the DevTools protocol the {@code jcef} adapter speaks: {@code Page.enable},
 * {@code Runtime.enable} (which reports the execution contexts that exist), {@code Runtime.disable} (no
 * {@code Runtime.*} event on that session until it is enabled again), {@code Runtime.evaluate} (by value, with
 * {@code awaitPromise} and {@code contextId}), {@code Page.getFrameTree}, {@code Page.addScriptToEvaluateOnNewDocument}
 * and its removal, {@code Emulation.setDeviceMetricsOverride} and its clearing, {@code Page.captureScreenshot},
 * {@code Page.reload}; the events of a load ({@code Runtime.executionContextsCleared}, {@code Page.frameNavigated},
 * {@code Runtime.executionContextCreated}, {@code Runtime.consoleAPICalled}, {@code Page.frameStoppedLoading},
 * {@code Page.loadEventFired}). Like JCEF off-screen (BS8) a browser has no native browser until it is shown or
 * {@link #createImmediately()} is called, and then a 1 x 1 viewport unless shown; answers and events arrive on the
 * EDT; a browser created with {@code createImmediately()} gives a first client that reports "Client is closed". A
 * frozen page ({@link #freeze}) queues the calls it gets and runs them, in order, when it thaws, as a busy renderer
 * does. For the tests of the adapter's re-send rule, the next evaluation can be run and then answered with a failure
 * (Chromium's answer to a call in flight across a navigation), or held and failed later.
 *
 * <p>Below the client, as JCEF has it package-private: {@link #executeDevToolsMethod} (the message id) and
 * {@link #addDevToolsMessageObserver} (every answer by id and every event), on one session of the browser's own. Like
 * JCEF 152 in the Linux image, every second answer reaches the observers before its id is given out (JCEF assigns the
 * id on the calling thread), which JCEF's own client does not survive.
 */
abstract class FakeCefBrowserBase implements FakeCefBrowser {

    static {
        ClassInitialisations.record(FakeCefBrowserBase.class);
    }

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final List<String> VIOLATIONS = Collections.synchronizedList(new ArrayList<String>());
    private static final int BLOCK_RGB = (240 << 16) | (200 << 8) | 40;

    final FakeCefApp app;
    private final FakeCefClient client;
    private final FakeGlCanvas canvas = new FakeGlCanvas();
    private final String mainFrameId = "MAIN" + Integer.toHexString(System.identityHashCode(this)).toUpperCase();
    private final Map<FakeDocument, String> frameIds = new IdentityHashMap<FakeDocument, String>();
    private final Map<Long, FakeDocument> contexts = new LinkedHashMap<Long, FakeDocument>();
    private volatile int identifier = -1;
    private volatile boolean frozen;
    private volatile boolean closed;
    private volatile boolean createdHidden;
    private volatile int[] natural = {1, 1};
    private volatile FakeDocument document;
    private volatile String html;
    private FakeDevToolsClient devtools;
    private long nextContext;
    private int nextFrame;
    private final AtomicInteger reloads = new AtomicInteger();
    private final List<FakeDevToolsMessageObserver> observers = new CopyOnWriteArrayList<FakeDevToolsMessageObserver>();
    private final AtomicInteger messageIds = new AtomicInteger();
    /** The session of the raw channel (the browser's own; its scripts and override count as the client's do). */
    private final FakeDevToolsClient rawSession;
    private final AtomicInteger evaluations = new AtomicInteger();
    private final AtomicInteger scriptAdditions = new AtomicInteger();
    /** The {@code Input.dispatchMouseEvent} calls received (Phase 7). */
    private final AtomicInteger inputs = new AtomicInteger();
    private final AtomicInteger scriptRemovals = new AtomicInteger();
    private final AtomicInteger runtimeEnables = new AtomicInteger();
    private final AtomicInteger runtimeDisables = new AtomicInteger();
    private final List<Object[]> queued = new ArrayList<Object[]>();
    private final List<CompletableFuture<String>> held = new ArrayList<CompletableFuture<String>>();
    private volatile String failNextEvaluation;
    private volatile boolean holdNextEvaluation;

    FakeCefBrowserBase(FakeCefApp app, FakeCefClient client) {
        this.app = app;
        this.client = client;
        this.document = FakeDocument.parse(host(), "about:blank", "<html><head><title></title></head><body></body>"
                + "</html>");
        frameIds.put(document, mainFrameId);
        this.rawSession = new FakeDevToolsClient(this, false);
        rawSession.addEventListener(new FakeDevToolsClient.EventListener() {
            @Override
            public void onEvent(String eventName, String messageAsJson) {
                for (FakeDevToolsMessageObserver o : observers) {
                    o.onDevToolsEvent(FakeCefBrowserBase.this, eventName, messageAsJson);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ raw channel

    /**
     * JCEF's {@code CefBrowser_N.executeDevToolsMethod}: the message id (on the calling thread), the answer to the
     * observers on the EDT; every second answer is delivered before the id. A closed browser, or one without a
     * native browser, fails the id future.
     */
    CompletableFuture<Integer> executeDevToolsMethod(final String method, final String parametersAsJson) {
        final CompletableFuture<Integer> id = new CompletableFuture<Integer>();
        if (identifier < 0 || closed) {
            id.completeExceptionally(new FakeDevToolsException("Failed to execute DevTools method " + method));
            return id;
        }
        final int messageId = messageIds.incrementAndGet();
        // A frozen page answers nothing, but its call still has an id (JCEF assigns it on the calling thread).
        final boolean answerFirst = messageId % 2 == 0 && !frozen;
        final CompletableFuture<String> answer = new CompletableFuture<String>();
        answer.whenComplete(new java.util.function.BiConsumer<String, Throwable>() {
            @Override
            public void accept(String json, Throwable failure) {
                String text = failure == null ? json : ai.skillroy.testjava.protocol.Json.write(
                        java.util.Collections.singletonMap("message", String.valueOf(failure.getMessage())));
                for (FakeDevToolsMessageObserver o : observers) {
                    o.onDevToolsMethodResult(FakeCefBrowserBase.this, messageId, failure == null, text);
                }
                if (answerFirst) {
                    id.complete(Integer.valueOf(messageId));
                }
            }
        });
        app.renderer.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    execute(rawSession, method, ai.skillroy.testjava.protocol.Json.parseObject(parametersAsJson),
                            answer);
                } catch (RuntimeException e) {
                    FakeDevToolsClient.fail(answer, String.valueOf(e.getMessage()));
                }
            }
        });
        if (!answerFirst) {
            id.complete(Integer.valueOf(messageId));
        }
        return id;
    }

    /** JCEF's {@code addDevToolsMessageObserver}: null while there is no native browser, or once closed. */
    synchronized FakeRegistration addDevToolsMessageObserver(final FakeDevToolsMessageObserver observer) {
        if (identifier < 0 || closed) {
            return null;
        }
        observers.add(observer);
        return new FakeRegistration() {
            @Override
            public void dispose() {
                observers.remove(observer);
            }
        };
    }

    /** How many observers are registered (for tests). */
    public int observerCount() {
        return observers.size();
    }

    static void violation(String what) {
        VIOLATIONS.add(what);
    }

    /** Violations of the adapter rules seen by any fake CEF browser (for tests). */
    public static List<String> violations() {
        synchronized (VIOLATIONS) {
            return new ArrayList<String>(VIOLATIONS);
        }
    }

    // ------------------------------------------------------------------------------------------------ CefBrowser

    @Override
    public synchronized FakeDevToolsClient getDevToolsClient() {
        if (identifier < 0 || closed) {
            return null;
        }
        if (devtools == null || devtools.isClosed()) {
            boolean quirk = createdHidden && devtools == null;
            devtools = new FakeDevToolsClient(this, quirk);
        }
        return devtools;
    }

    @Override
    public int getIdentifier() {
        return identifier;
    }

    @Override
    public String getURL() {
        FakeDocument d = document;
        return identifier < 0 || d == null ? "" : d.url();
    }

    @Override
    public Component getUIComponent() {
        return canvas;
    }

    @Override
    public void reload() {
        reloads.incrementAndGet();
        String h = html;
        if (h != null) {
            loadHtml(h);
        }
    }

    @Override
    public void reloadIgnoreCache() {
        reload();
    }

    /** How many reloads were asked (for tests). */
    public int reloads() {
        return reloads.get();
    }

    @Override
    public synchronized void createImmediately() {
        if (identifier < 0) {
            identifier = IDS.incrementAndGet();
            createdHidden = true;
        }
    }

    /** The application shows the browser: the native browser exists with the component's size. */
    public synchronized void show(int width, int height) {
        if (identifier < 0) {
            identifier = IDS.incrementAndGet();
        }
        natural = new int[] {width, height};
        canvas.shown(true);
    }

    @Override
    public void close(boolean force) {
        FakeDevToolsClient c;
        synchronized (this) {
            closed = true;
            c = devtools;
            devtools = null;
        }
        if (c != null) {
            c.closeInternal();
        }
        observers.clear();
        client.removed(this);
    }

    /** Closes the current DevTools client the way an application could (the next one is new). */
    public void closeClientByApplication() {
        FakeDevToolsClient c;
        synchronized (this) {
            c = devtools;
        }
        if (c != null) {
            c.closeByApplication();
        }
    }

    // ------------------------------------------------------------------------------------------------ loading

    /** Loads the HTML (the application's action); completes once the load's events were delivered on the EDT. */
    public CompletableFuture<Void> loadHtml(final String newHtml) {
        final CompletableFuture<Void> done = new CompletableFuture<Void>();
        app.renderer.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    load(newHtml);
                    EventQueue.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            done.complete(null);
                        }
                    });
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            }
        });
        return done;
    }

    /** The main document (for tests). */
    public FakeDocument document() {
        return document;
    }

    private void load(String newHtml) {
        FakeDocument old = document;
        if (old != null) {
            for (FakeDocument d : old.tree()) {
                d.markGone();
            }
        }
        synchronized (contexts) {
            contexts.clear();
            frameIds.clear();
        }
        emit("Runtime.executionContextsCleared", new LinkedHashMap<String, Object>());
        html = newHtml;
        String url = KitPages.dataUrl(newHtml);
        FakeDocument doc = FakeDocument.parse(host(), url, newHtml);
        document = doc;
        FakeDevToolsClient c = currentClient();
        List<String> scripts = new ArrayList<String>(c == null ? Collections.<String>emptyList()
                : c.scriptSources());
        scripts.addAll(rawSession.scriptSources());
        for (FakeDocument d : doc.tree()) {
            String id = d == doc ? mainFrameId : "F" + (++nextFrame);
            String parent = d == doc ? null : parentId(doc, d);
            synchronized (contexts) {
                frameIds.put(d, id);
            }
            Map<String, Object> frame = new LinkedHashMap<String, Object>();
            frame.put("id", id);
            if (parent != null) {
                frame.put("parentId", parent);
            }
            frame.put("url", d.url());
            frame.put("name", d.name());
            Map<String, Object> navigated = new LinkedHashMap<String, Object>();
            navigated.put("frame", frame);
            navigated.put("type", "Navigation");
            emit("Page.frameNavigated", navigated);
            long ctx;
            synchronized (contexts) {
                ctx = ++nextContext;
                contexts.put(Long.valueOf(ctx), d);
            }
            emit("Runtime.executionContextCreated", context(ctx, id));
            for (String source : scripts) {
                try {
                    FakeScript.evaluate(d, source);
                } catch (FakeScript.Thrown ignored) {
                    // an init script that throws does not stop the document
                }
            }
            d.runInlineScripts();
        }
        for (FakeDocument d : doc.tree()) {
            if (d != doc) {
                Map<String, Object> stopped = new LinkedHashMap<String, Object>();
                stopped.put("frameId", frameIds.get(d));
                emit("Page.frameStoppedLoading", stopped);
            }
        }
        Map<String, Object> fired = new LinkedHashMap<String, Object>();
        fired.put("timestamp", Double.valueOf(System.currentTimeMillis() / 1000.0));
        emit("Page.loadEventFired", fired);
    }

    private String parentId(FakeDocument root, FakeDocument child) {
        for (FakeDocument d : root.tree()) {
            if (d.children().contains(child)) {
                return frameIds.get(d);
            }
        }
        return null;
    }

    private static Map<String, Object> context(long id, String frameId) {
        Map<String, Object> aux = new LinkedHashMap<String, Object>();
        aux.put("isDefault", Boolean.TRUE);
        aux.put("type", "default");
        aux.put("frameId", frameId);
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        context.put("id", Long.valueOf(id));
        context.put("origin", "null");
        context.put("name", "");
        context.put("auxData", aux);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("context", context);
        return out;
    }

    private synchronized FakeDevToolsClient currentClient() {
        return devtools == null || devtools.isClosed() ? null : devtools;
    }

    private void emit(String name, Map<String, Object> params) {
        FakeDevToolsClient c = currentClient();
        if (c != null) {
            c.fire(name, params);
        }
        if (!observers.isEmpty() && !closed) {
            rawSession.fire(name, params);
        }
    }

    // ------------------------------------------------------------------------------------------------ protocol

    /**
     * A frozen page (a busy loop in its renderer, P4 section 12.2 item 6): no protocol call is answered until it
     * thaws; the calls it got meanwhile then run in order.
     */
    public void freeze(boolean on) {
        List<Object[]> thawed = new ArrayList<Object[]>();
        synchronized (queued) {
            frozen = on;
            if (!on) {
                thawed.addAll(queued);
                queued.clear();
            }
        }
        for (final Object[] q : thawed) {
            app.renderer.execute(new Runnable() {
                @Override
                @SuppressWarnings("unchecked")
                public void run() {
                    execute((FakeDevToolsClient) q[0], (String) q[1], (Map<String, Object>) q[2],
                            (CompletableFuture<String>) q[3]);
                }
            });
        }
    }

    /** How many calls a frozen page has queued (for tests). */
    public int queuedCalls() {
        synchronized (queued) {
            return queued.size();
        }
    }

    /** How many {@code Runtime.evaluate} calls reached the page (run or held). */
    public int evaluations() {
        return evaluations.get();
    }

    /** How many {@code Page.addScriptToEvaluateOnNewDocument} calls reached the page. */
    public int scriptAdditions() {
        return scriptAdditions.get();
    }

    /** How many {@code Input.dispatchMouseEvent} calls the browser received (Phase 7; for tests). */
    public int inputs() {
        return inputs.get();
    }

    /** How many {@code Runtime.enable} calls the page has carried out (P2-L4-S1). */
    public int runtimeEnables() {
        return runtimeEnables.get();
    }

    /** How many {@code Runtime.disable} calls the page has carried out (P2-L4-S1). */
    public int runtimeDisables() {
        return runtimeDisables.get();
    }

    /** The page logs {@code text} on its console ({@code Runtime.consoleAPICalled}, as a page script would). */
    public void pageConsole(String level, String text) {
        host().console(document, level, text);
    }

    /** How many {@code Page.removeScriptToEvaluateOnNewDocument} calls the page has carried out. */
    public int scriptRemovals() {
        return scriptRemovals.get();
    }

    /**
     * The next {@code Runtime.evaluate} runs in the page and is then answered with a protocol failure of this message
     * (what Chromium answers a call in flight across a navigation).
     */
    public void answerNextEvaluationWithFailure(String message) {
        failNextEvaluation = message;
    }

    /** The next {@code Runtime.evaluate} reaches the page but is held, not run, until {@link #failHeld}. */
    public void holdNextEvaluation() {
        holdNextEvaluation = true;
    }

    /** Answers every held evaluation with a protocol failure of this message (a late answer). */
    public void failHeld(String message) {
        List<CompletableFuture<String>> all;
        synchronized (held) {
            all = new ArrayList<CompletableFuture<String>>(held);
            held.clear();
        }
        for (CompletableFuture<String> f : all) {
            FakeDevToolsClient.fail(f, message);
        }
    }

    /** One protocol call, on the renderer thread; the answer is delivered on the EDT. */
    void execute(FakeDevToolsClient c, String method, Map<String, Object> params, CompletableFuture<String> out) {
        synchronized (queued) {
            if (frozen) {
                queued.add(new Object[] {c, method, params, out});
                return;
            }
        }
        if ("Page.addScriptToEvaluateOnNewDocument".equals(method)) {
            scriptAdditions.incrementAndGet();
        }
        if ("Runtime.evaluate".equals(method)) {
            evaluations.incrementAndGet();
            if (holdNextEvaluation) {
                holdNextEvaluation = false;
                synchronized (held) {
                    held.add(out);
                }
                return;
            }
            String failure = failNextEvaluation;
            if (failure != null) {
                failNextEvaluation = null;
                evaluate(c, params, new CompletableFuture<String>());
                FakeDevToolsClient.fail(out, failure);
                return;
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if ("Page.enable".equals(method)) {
            FakeDevToolsClient.complete(out, result);
        } else if ("Runtime.disable".equals(method)) {
            c.runtimeOff = true;
            runtimeDisables.incrementAndGet();
            FakeDevToolsClient.complete(out, result);
        } else if ("Runtime.enable".equals(method)) {
            c.runtimeOff = false;
            runtimeEnables.incrementAndGet();
            List<Map.Entry<Long, FakeDocument>> live;
            synchronized (contexts) {
                live = new ArrayList<Map.Entry<Long, FakeDocument>>(contexts.entrySet());
            }
            for (Map.Entry<Long, FakeDocument> e : live) {
                c.fire("Runtime.executionContextCreated", context(e.getKey().longValue(), frameIds.get(
                        e.getValue())));
            }
            FakeDevToolsClient.complete(out, result);
        } else if ("Runtime.evaluate".equals(method)) {
            evaluate(c, params, out);
        } else if ("Page.getFrameTree".equals(method)) {
            result.put("frameTree", tree(document));
            FakeDevToolsClient.complete(out, result);
        } else if ("Page.addScriptToEvaluateOnNewDocument".equals(method)) {
            result.put("identifier", c.addScript(String.valueOf(params.get("source"))));
            FakeDevToolsClient.complete(out, result);
        } else if ("Page.removeScriptToEvaluateOnNewDocument".equals(method)) {
            c.removeScript(String.valueOf(params.get("identifier")));
            scriptRemovals.incrementAndGet();
            FakeDevToolsClient.complete(out, result);
        } else if ("Emulation.setDeviceMetricsOverride".equals(method)) {
            c.override = new int[] {((Number) params.get("width")).intValue(),
                ((Number) params.get("height")).intValue()};
            FakeDevToolsClient.complete(out, result);
        } else if ("Emulation.clearDeviceMetricsOverride".equals(method)) {
            c.override = null;
            FakeDevToolsClient.complete(out, result);
        } else if ("Page.captureScreenshot".equals(method)) {
            result.put("data", screenshot());
            FakeDevToolsClient.complete(out, result);
        } else if ("Page.reload".equals(method)) {
            reload();
            FakeDevToolsClient.complete(out, result);
        } else if ("Input.dispatchMouseEvent".equals(method)) {
            inputs.incrementAndGet();
            FakeDocument d = document;
            if (d != null) {
                d.pointer(String.valueOf(params.get("type")), number(params.get("x")), number(params.get("y")),
                        String.valueOf(params.get("button")), number(params.get("clickCount")),
                        number(params.get("modifiers")), number(params.get("deltaX")), number(params.get("deltaY")));
            }
            FakeDevToolsClient.complete(out, result);
        } else {
            FakeDevToolsClient.fail(out, "'" + method + "' wasn't found");
        }
    }

    private static long number(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    private void evaluate(final FakeDevToolsClient c, Map<String, Object> params,
                          final CompletableFuture<String> out) {
        FakeDocument doc = document;
        Object contextId = params.get("contextId");
        if (contextId instanceof Number) {
            synchronized (contexts) {
                doc = contexts.get(Long.valueOf(((Number) contextId).longValue()));
            }
            if (doc == null) {
                FakeDevToolsClient.fail(out, "Cannot find context with specified id");
                return;
            }
        }
        Object value;
        try {
            value = FakeScript.evaluate(doc, String.valueOf(params.get("expression")));
        } catch (FakeScript.Thrown t) {
            FakeDevToolsClient.complete(out, thrown(t));
            return;
        }
        if (value instanceof FakeScript.Promise && Boolean.TRUE.equals(params.get("awaitPromise"))) {
            final FakeScript.Promise p = (FakeScript.Promise) value;
            app.renderer.schedule(new Runnable() {
                @Override
                public void run() {
                    if (p.error() != null) {
                        FakeDevToolsClient.complete(out, thrown(p.error()));
                    } else {
                        FakeDevToolsClient.complete(out, byValue(p.value()));
                    }
                }
            }, p.remainingMs());
            return;
        }
        FakeDevToolsClient.complete(out, byValue(value));
    }

    private static Map<String, Object> byValue(Object v) {
        Map<String, Object> remote = new LinkedHashMap<String, Object>();
        if (v == FakeScript.UNDEFINED) {
            remote.put("type", "undefined");
        } else if (v == null) {
            remote.put("type", "object");
            remote.put("subtype", "null");
            remote.put("value", null);
        } else if (v instanceof String) {
            remote.put("type", "string");
            remote.put("value", v);
        } else if (v instanceof Number) {
            remote.put("type", "number");
            remote.put("value", v);
        } else if (v instanceof Boolean) {
            remote.put("type", "boolean");
            remote.put("value", v);
        } else if (v instanceof FakeScript.Promise) {
            remote.put("type", "object");
            remote.put("className", "Promise");
            remote.put("description", "Promise");
        } else {
            remote.put("type", "object");
            remote.put("value", v);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("result", remote);
        return out;
    }

    private static Map<String, Object> thrown(FakeScript.Thrown t) {
        String description = t.stack() != null ? t.stack() : t.getMessage();
        Map<String, Object> exception = new LinkedHashMap<String, Object>();
        exception.put("type", "object");
        exception.put("subtype", "error");
        exception.put("className", "Error");
        exception.put("description", description);
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("exceptionId", Long.valueOf(1));
        details.put("text", "Uncaught");
        details.put("lineNumber", Long.valueOf(0));
        details.put("columnNumber", Long.valueOf(0));
        details.put("exception", exception);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("result", exception);
        out.put("exceptionDetails", details);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tree(FakeDocument d) {
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        String id;
        synchronized (contexts) {
            id = frameIds.get(d);
        }
        frame.put("id", id == null ? mainFrameId : id);
        frame.put("url", d.url());
        frame.put("name", d.name());
        List<Object> children = new ArrayList<Object>();
        for (FakeDocument c : d.children()) {
            Map<String, Object> child = tree(c);
            ((Map<String, Object>) child.get("frame")).put("parentId", frame.get("id"));
            children.add(child);
        }
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("frame", frame);
        if (!children.isEmpty()) {
            node.put("childFrames", children);
        }
        return node;
    }

    private String screenshot() {
        int[] size = innerSize();
        BufferedImage image = new BufferedImage(Math.max(1, size[0]), Math.max(1, size[1]),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            FakeDocument d = document;
            g.setColor(new java.awt.Color(d == null ? 0xffffff : d.background()));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(new java.awt.Color(BLOCK_RGB));
            g.fillRect(24, 24, 120, 60);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    int[] innerSize() {
        int[] raw = rawSession.override;
        if (raw != null) {
            return raw;
        }
        FakeDevToolsClient c = currentClient();
        int[] o = c == null ? null : c.override;
        return o != null ? o : natural;
    }

    private FakeDocument.Host host() {
        return new FakeDocument.Host() {
            @Override
            public void console(FakeDocument doc, String level, String text) {
                Map<String, Object> arg = new LinkedHashMap<String, Object>();
                arg.put("type", "string");
                arg.put("value", text);
                List<Object> args = new ArrayList<Object>();
                args.add(arg);
                Map<String, Object> top = new LinkedHashMap<String, Object>();
                top.put("functionName", "");
                top.put("url", doc.url().length() > 60 ? doc.url().substring(0, 60) : doc.url());
                top.put("lineNumber", Long.valueOf(4));
                List<Object> frames = new ArrayList<Object>();
                frames.add(top);
                Map<String, Object> stack = new LinkedHashMap<String, Object>();
                stack.put("callFrames", frames);
                Map<String, Object> params = new LinkedHashMap<String, Object>();
                params.put("type", level);
                params.put("args", args);
                params.put("executionContextId", Long.valueOf(1));
                params.put("stackTrace", stack);
                emit("Runtime.consoleAPICalled", params);
            }

            @Override
            public void reload(boolean ignoreCache) {
                FakeCefBrowserBase.this.reload();
            }

            @Override
            public int[] innerSize() {
                return FakeCefBrowserBase.this.innerSize();
            }
        };
    }
}
