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
import ai.skillroy.testjava.protocol.web.WebLib;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.JSHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ViewportSize;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A browser of {@link Fakes}: a fake Playwright, browser, context and pages whose page scripts are answered by a
 * per-page {@link Fakes.Answer} (the "engine"), with everything the CDP path does to them recorded. {@link #attach}
 * builds a real {@link CdpAttach} (with its real Playwright thread) around them.
 */
final class FakeWorld {

    /** One page with its main frame, its child frames and its recordings. */
    final class FakePage {

        final String targetId;
        volatile String url;
        volatile String title;
        volatile boolean closed;
        /** Answers {@code evaluate} of the main frame: receives the script text. */
        volatile Fakes.Answer engine = args -> {
            throw new UnsupportedOperationException("no engine for " + args[0]);
        };
        final List<String> evaluated = new CopyOnWriteArrayList<>();
        final List<String> initScripts = new CopyOnWriteArrayList<>();
        final AtomicInteger initScriptsRemoved = new AtomicInteger();
        volatile ViewportSize override;
        /** The window's own inner size, what the page reads without a device-metrics override. */
        volatile Map<String, Object> innerSize = Map.of("w", 1L, "h", 1L);
        /** The device-metrics override of the page's CDP sessions as {w, h}, which the page then reads; or null. */
        volatile Map<String, Object> metrics;
        /** Every {@code Emulation.*} call on a CDP session of the page: {method, params, the session}. */
        final List<Object[]> emulation = new CopyOnWriteArrayList<>();
        /** Every {@code Input.dispatchMouseEvent} on a CDP session of the page (Phase 7): its parameters as sent. */
        final List<String> mouse = new CopyOnWriteArrayList<>();
        /** The index of the mouse event the page fails (a protocol error), or -1. */
        volatile int failMouseAt = -1;
        final List<int[]> resized = new CopyOnWriteArrayList<>();
        volatile byte[] png;
        final List<Page.ScreenshotOptions> screenshots = new CopyOnWriteArrayList<>();
        final List<Consumer<Page>> loadListeners = new CopyOnWriteArrayList<>();
        final List<Consumer<Frame>> navigationListeners = new CopyOnWriteArrayList<>();
        final List<String> locators = new CopyOnWriteArrayList<>();
        final List<Object[]> roles = new CopyOnWriteArrayList<>();
        final List<Integer> nths = new CopyOnWriteArrayList<>();
        final List<Locator.WaitForOptions> waits = new CopyOnWriteArrayList<>();
        volatile RuntimeException waitFailure;
        volatile Fakes.Answer waitForFunction;
        final List<Object[]> waitForFunctions = new CopyOnWriteArrayList<>();
        volatile Map<String, Object> frameTree;
        final List<Frame> children = new CopyOnWriteArrayList<>();
        final Page page;
        final Frame main;

        FakePage(String targetId, String url, String title) {
            this.targetId = targetId;
            this.url = url;
            this.title = title;
            Map<String, Fakes.Answer> pageAnswers = new HashMap<>();
            Map<String, Fakes.Answer> frameAnswers = new HashMap<>();
            this.page = Fakes.fake(Page.class, pageAnswers);
            this.main = Fakes.fake(Frame.class, frameAnswers);
            frameAnswers.put("evaluate(String,Object)", a -> {
                if (Scripts.innerSize().equals(a[0])) {
                    Map<String, Object> emulated = metrics;
                    return emulated != null ? emulated : innerSize;
                }
                evaluated.add((String) a[0]);
                return guarded((String) a[0], engine.answer(a));
            });
            frameAnswers.put("url()", a -> this.url);
            frameAnswers.put("name()", a -> "");
            frameAnswers.put("parentFrame()", a -> null);
            frameAnswers.put("page()", a -> page);
            frameAnswers.put("isDetached()", a -> false);
            frameAnswers.put("locator(String,LocatorOptions)", a -> {
                locators.add((String) a[0]);
                return locator();
            });
            frameAnswers.put("getByRole(AriaRole,GetByRoleOptions)", a -> {
                roles.add(new Object[] {(AriaRole) a[0], a[1]});
                return locator();
            });
            frameAnswers.put("waitForFunction(String,Object,WaitForFunctionOptions)", a -> {
                waitForFunctions.add(a);
                return waitForFunction.answer(a);
            });
            pageAnswers.put("url()", a -> this.url);
            pageAnswers.put("title()", a -> this.title);
            pageAnswers.put("isClosed()", a -> closed);
            pageAnswers.put("mainFrame()", a -> main);
            pageAnswers.put("frames()", a -> {
                List<Frame> all = new ArrayList<>();
                all.add(main);
                all.addAll(children);
                return all;
            });
            pageAnswers.put("evaluate(String,Object)", a -> main.evaluate((String) a[0], a[1]));
            pageAnswers.put("addInitScript(String)", a -> {
                initScripts.add((String) a[0]);
                return (AutoCloseable) initScriptsRemoved::incrementAndGet;
            });
            pageAnswers.put("setViewportSize(int,int)", a -> {
                resized.add(new int[] {(Integer) a[0], (Integer) a[1]});
                override = new ViewportSize((Integer) a[0], (Integer) a[1]);
                return null;
            });
            pageAnswers.put("viewportSize()", a -> override);
            pageAnswers.put("screenshot(ScreenshotOptions)", a -> {
                screenshots.add((Page.ScreenshotOptions) a[0]);
                return png;
            });
            pageAnswers.put("onLoad(Consumer)", a -> {
                @SuppressWarnings("unchecked")
                Consumer<Page> c = (Consumer<Page>) a[0];
                loadListeners.add(c);
                return null;
            });
            pageAnswers.put("onFrameNavigated(Consumer)", a -> {
                @SuppressWarnings("unchecked")
                Consumer<Frame> c = (Consumer<Frame>) a[0];
                navigationListeners.add(c);
                return null;
            });
            pageAnswers.put("context()", a -> context);
        }

        private Locator locator() {
            Map<String, Fakes.Answer> answers = new HashMap<>();
            Locator[] self = new Locator[1];
            answers.put("waitFor(WaitForOptions)", a -> {
                waits.add((Locator.WaitForOptions) a[0]);
                if (waitFailure != null) {
                    throw waitFailure;
                }
                return null;
            });
            answers.put("nth(int)", a -> {
                nths.add((Integer) a[0]);
                return self[0];
            });
            self[0] = Fakes.fake(Locator.class, answers);
            return self[0];
        }

        /** Adds a child frame with its own evaluate answer. */
        Frame child(String frameUrl, String name, Fakes.Answer childEngine) {
            Map<String, Fakes.Answer> answers = new HashMap<>();
            Frame frame = Fakes.fake(Frame.class, answers);
            answers.put("evaluate(String,Object)", a -> {
                evaluated.add("child:" + a[0]);
                return guarded((String) a[0], childEngine.answer(a));
            });
            answers.put("url()", a -> frameUrl);
            answers.put("name()", a -> name);
            answers.put("parentFrame()", a -> main);
            answers.put("page()", a -> page);
            children.add(frame);
            return frame;
        }

        /** Fires the page's load listeners as Playwright would on the Playwright thread. */
        void fireLoad() {
            for (Consumer<Page> c : loadListeners) {
                c.accept(page);
            }
        }

        Map<String, Object> defaultFrameTree() {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("id", targetId);
            frame.put("url", url);
            return Map.of("frameTree", Map.of("frame", frame, "childFrames", List.of()));
        }
    }

    final List<FakePage> pages = new CopyOnWriteArrayList<>();
    final List<Consumer<Page>> pageListeners = new CopyOnWriteArrayList<>();
    final List<Tracing.StartOptions> traceStarts = new CopyOnWriteArrayList<>();
    final List<Tracing.StopOptions> traceStops = new CopyOnWriteArrayList<>();
    final AtomicInteger playwrightClosed = new AtomicInteger();
    final AtomicInteger browserClosed = new AtomicInteger();
    final AtomicInteger pumps = new AtomicInteger();
    final AtomicInteger sessions = new AtomicInteger();
    final Playwright playwright;
    final Browser browser;
    final BrowserContext context;

    FakeWorld() {
        Map<String, Fakes.Answer> pw = new HashMap<>();
        pw.put("close()", a -> {
            playwrightClosed.incrementAndGet();
            return null;
        });
        playwright = Fakes.fake(Playwright.class, pw);
        Map<String, Fakes.Answer> ctx = new HashMap<>();
        context = Fakes.fake(BrowserContext.class, ctx);
        ctx.put("pages()", a -> {
            List<Page> open = new ArrayList<>();
            for (FakePage p : pages) {
                if (!p.closed) {
                    open.add(p.page);
                }
            }
            return open;
        });
        ctx.put("newCDPSession(Page)", a -> session(find((Page) a[0])));
        ctx.put("onPage(Consumer)", a -> {
            @SuppressWarnings("unchecked")
            Consumer<Page> c = (Consumer<Page>) a[0];
            pageListeners.add(c);
            return null;
        });
        Map<String, Fakes.Answer> tr = new HashMap<>();
        tr.put("start(StartOptions)", a -> {
            traceStarts.add((Tracing.StartOptions) a[0]);
            return null;
        });
        tr.put("stop(StopOptions)", a -> {
            Tracing.StopOptions stop = (Tracing.StopOptions) a[0];
            traceStops.add(stop);
            if (stop.path != null) {
                Files.writeString(stop.path, "trace");
            }
            return null;
        });
        Tracing tracing = Fakes.fake(Tracing.class, tr);
        ctx.put("tracing()", a -> tracing);
        Map<String, Fakes.Answer> br = new HashMap<>();
        br.put("contexts()", a -> List.of(context));
        br.put("close()", a -> {
            browserClosed.incrementAndGet();
            return null;
        });
        br.put("newBrowserCDPSession()", a -> {
            Map<String, Fakes.Answer> s = new HashMap<>();
            s.put("send(String,JsonObject)", x -> {
                pumps.incrementAndGet();
                return JsonParser.parseString("{\"protocolVersion\":\"1.3\"}").getAsJsonObject();
            });
            return Fakes.fake(CDPSession.class, s);
        });
        browser = Fakes.fake(Browser.class, br);
    }

    FakePage page(String targetId, String url, String title) {
        FakePage p = new FakePage(targetId, url, title);
        pages.add(p);
        return p;
    }

    /** A real attach around the fakes, with a real Playwright thread. */
    CdpAttach attach(TargetProbe probe, CdpOptions options) {
        return new CdpAttach(probe, options, new PwThread("test-playwright"), playwright, browser, context, 7);
    }

    private FakePage find(Page page) {
        for (FakePage p : pages) {
            if (p.page == page) {
                return p;
            }
        }
        throw new IllegalArgumentException("not a page of this world");
    }

    /**
     * A CDP session of a page: the target id, the frame tree, and the device-metrics override (recorded with the
     * session that sent it; the fake applies a removal from any session, Chromium only from the one that set it).
     */
    private CDPSession session(FakePage p) {
        sessions.incrementAndGet();
        Map<String, Fakes.Answer> s = new HashMap<>();
        CDPSession[] self = new CDPSession[1];
        s.put("send(String,JsonObject)", a -> {
            String method = (String) a[0];
            Map<String, Object> params = a[1] == null ? Map.of() : Json.parseObject(a[1].toString());
            Object answer = switch (method) {
                case "Target.getTargetInfo" -> Map.of("targetInfo", Map.of("targetId", p.targetId, "type", "page",
                        "url", p.url));
                case "Page.getFrameTree" -> p.frameTree == null ? p.defaultFrameTree() : p.frameTree;
                case "Emulation.setDeviceMetricsOverride" -> {
                    p.emulation.add(new Object[] {method, params, self[0]});
                    p.metrics = Map.of("w", params.get("width"), "h", params.get("height"));
                    yield Map.of();
                }
                case "Emulation.clearDeviceMetricsOverride" -> {
                    p.emulation.add(new Object[] {method, params, self[0]});
                    p.metrics = null;
                    yield Map.of();
                }
                case "Input.dispatchMouseEvent" -> {
                    int index = p.mouse.size();
                    p.mouse.add(a[1] == null ? "{}" : a[1].toString());
                    if (index == p.failMouseAt) {
                        throw new com.microsoft.playwright.PlaywrightException("Protocol error (Input.dispatchMouseEvent)"
                                + ": the page did not take the event");
                    }
                    yield Map.of();
                }
                default -> throw new UnsupportedOperationException("no fake protocol answer for " + method);
            };
            return JsonParser.parseString(Json.write(answer)).getAsJsonObject();
        });
        s.put("detach()", a -> null);
        self[0] = Fakes.fake(CDPSession.class, s);
        return self[0];
    }

    /** A fake JS handle whose {@code jsonValue} is the given value. */
    static JSHandle handle(Object value) {
        Map<String, Fakes.Answer> answers = new HashMap<>();
        answers.put("jsonValue()", a -> value);
        answers.put("dispose()", a -> null);
        return Fakes.fake(JSHandle.class, answers);
    }

    /**
     * What a page answers a guarded library call (security fix P2-L4-F2): an engine's {@code {ok: value}} whose value
     * is not a string has the value replaced by the guard's text of it ({@link #libraryText}), as the guard of
     * {@code WebLib.boundedCallExpression} returns it in a browser; anything else, a string {@code ok} included (what a
     * replaced library's {@code stringify} could answer), is returned as it is.
     */
    static Object guarded(String script, Object answer) {
        if (script == null || !script.contains(WebLib.boundedOpen()) || !(answer instanceof Map<?, ?> m)
                || !m.containsKey("ok") || m.get("ok") instanceof String) {
            return answer;
        }
        return ok(libraryText(m.get("ok")));
    }

    /**
     * The guard's answer for a library value: the text the library's {@code stringify} makes of it, or the guard's
     * refusal when that text exceeds {@code WebLib.MAX_JSON_CHARS}.
     */
    static String libraryText(Object value) {
        String json = Json.write(value);
        boolean truncated = json.length() > WebLib.MAX_JSON_CHARS;
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("json", truncated ? json.substring(0, WebLib.MAX_JSON_CHARS) : json);
        wrapper.put("typeofResult", value instanceof String ? "string" : value instanceof Number ? "number"
                : value instanceof Boolean ? "boolean" : "object");
        wrapper.put("truncated", truncated);
        String text = Json.write(wrapper);
        return text.length() > WebLib.MAX_JSON_CHARS ? WebLib.GUARD_PREFIX + "oversized" : text;
    }

    /** {@code {ok: value}}, what a guarded script returns. */
    static Map<String, Object> ok(Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", value);
        return m;
    }
}
