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

import ai.skillroy.testjava.agent.api.spi.web.WebBrowser;
import ai.skillroy.testjava.agent.api.spi.web.WebEval;
import ai.skillroy.testjava.agent.api.spi.web.WebEvent;
import ai.skillroy.testjava.agent.api.spi.web.WebEventListener;
import ai.skillroy.testjava.agent.api.spi.web.WebFrame;
import ai.skillroy.testjava.agent.web.jcef.JcefAdapter;
import ai.skillroy.testjava.agent.web.kit.KitPages;
import ai.skillroy.testjava.agent.web.kit.KitScripts;
import ai.skillroy.testjava.agent.web.reflect.EventBudget;
import ai.skillroy.testjava.protocol.CapabilityState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security fix P2-L4-S1 in the {@code jcef} adapter, against the fake: a page that floods its console stops the
 * browser's console capture once its output exceeds the receive budget ({@link EventBudget}): the adapter sends
 * {@code Runtime.disable} once, so that the page's output stops crossing into the JVM, its subscribers get the
 * warning once and no console event after it, a sub-frame evaluation answers {@code UnsupportedOperationException}
 * (whose contexts the {@code Runtime} domain reports) while the main frame still evaluates; and capture resumes, with
 * {@code Runtime.enable}, at the first main-frame navigation {@link EventBudget#RESUME_AFTER_MS} ms or more after it
 * stopped. On both channels: the raw observer and the shared client.
 */
class JcefConsoleBudgetFakeTest {

    private static final long BOUND_MS = 10000;
    private static final int CHUNK = 1 << 20;

    private FakeCefFixture fx;
    private JcefAdapter adapter;

    private WebBrowser start(String descriptor) throws Exception {
        fx = new FakeCefFixture(descriptor);
        adapter = (JcefAdapter) fx.newAdapter();
        assertEquals(CapabilityState.OK, adapter.probe(fx.hostLoader()).state());
        for (WebBrowser w : adapter.browsers(Collections.singletonList(fx.visible))) {
            if (w.vendorObject() == fx.visible) {
                return w;
            }
        }
        throw new AssertionError("not found");
    }

    @AfterEach
    void stop() {
        if (adapter != null) {
            adapter.detach();
        }
        FakeCefApp.dispose();
    }

    @Test
    void aConsoleFloodStopsCaptureOnceOnTheRawChannel() throws Exception {
        flood(start(FakeCefFixture.RAW_DESCRIPTOR));
    }

    @Test
    void aConsoleFloodStopsCaptureOnceOnTheSharedClient() throws Exception {
        flood(start(FakeCefFixture.DESCRIPTOR));
    }

    @Test
    void aMainFrameNavigationResumesCaptureNoSoonerThanItsDelay() throws Exception {
        WebBrowser b = start(FakeCefFixture.RAW_DESCRIPTOR);
        List<WebEvent> console = flood(b);
        int enables = fx.visible.runtimeEnables();
        Thread.sleep(EventBudget.RESUME_AFTER_MS);
        fx.load(b, KitPages.second());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
        while (fx.visible.runtimeEnables() == enables && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(enables + 1, fx.visible.runtimeEnables(), "Runtime.enable once more");
        fx.visible.pageConsole("log", "back");
        WebEvent back = await(console, "back");
        assertNotNull(back, "console capture resumed");
    }

    /** Floods the page's console with 30 Mi characters and checks the stop; returns the console events seen. */
    private List<WebEvent> flood(WebBrowser b) throws Exception {
        fx.load(b, KitPages.main());
        final List<WebEvent> console = new CopyOnWriteArrayList<WebEvent>();
        b.subscribe(new WebEventListener() {
            @Override
            public void onEvent(WebBrowser browser, WebEvent event) {
                if (event.kind() == WebEvent.Kind.CONSOLE) {
                    console.add(event);
                }
            }
        });
        char[] chars = new char[CHUNK];
        Arrays.fill(chars, 'x');
        String chunk = new String(chars);
        for (int i = 0; i < 30; i++) {
            fx.visible.pageConsole("log", chunk);
        }
        WebEvent warning = await(console, EventBudget.STOPPED_TEXT);
        assertNotNull(warning, "the subscribers are told once that capture stopped");
        assertEquals("warning", warning.level());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
        while (fx.visible.runtimeDisables() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, fx.visible.runtimeDisables(), "Runtime.disable once: the page's output stops at its source");
        int delivered = 0;
        int warnings = 0;
        for (WebEvent e : console) {
            if (EventBudget.STOPPED_TEXT.equals(e.text())) {
                warnings++;
            } else {
                delivered++;
                assertTrue(e.text().length() <= (1 << 16), "a console text is built up to 64 KiB only");
            }
        }
        assertEquals(1, warnings);
        assertTrue(delivered <= EventBudget.CONSOLE_BURST_CHARS / CHUNK + 1, "within the burst: " + delivered);
        int seen = console.size();
        fx.visible.pageConsole("log", "after the stop");
        Thread.sleep(300);
        assertEquals(seen, console.size(), "nothing after the warning");

        WebFrame inner = null;
        for (WebFrame f : b.frames()) {
            if (!f.main()) {
                inner = f;
            }
        }
        assertNotNull(inner, "the page has an iframe");
        final WebFrame frame = inner;
        ExecutionException refused = assertThrows(ExecutionException.class, () -> b.evaluate(WebEval.of(
                KitScripts.TITLE).frameKey(frame.key()).timeoutMs(BOUND_MS).build()).get(BOUND_MS,
                TimeUnit.MILLISECONDS));
        assertTrue(refused.getCause() instanceof UnsupportedOperationException, String.valueOf(refused.getCause()));
        assertEquals("42", b.evaluate(WebEval.of("6 * 7").timeoutMs(BOUND_MS).build()).get(BOUND_MS,
                TimeUnit.MILLISECONDS), "the main frame still evaluates");
        assertEquals(Collections.emptyList(), FakeCefBrowserBase.violations());
        return console;
    }

    private static WebEvent await(List<WebEvent> events, String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOUND_MS);
        while (System.nanoTime() < deadline) {
            for (WebEvent e : events) {
                if (text.equals(e.text())) {
                    return e;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }
}
