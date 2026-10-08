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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.agent.api.spi.ActionFirer;
import ai.skillroy.testjava.agent.api.spi.DialogTextExtractor;
import ai.skillroy.testjava.agent.api.spi.LocatorResolver;
import ai.skillroy.testjava.agent.api.spi.NameEnricher;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Shared set-up of the display tests: a fake agent context, the installed module (tap included), the test SPIs, and
 * the main test frame. Skipped in a headless JVM; run under Xvfb with -Dskillroy.headless=false.
 */
abstract class DisplayTestBase {

    /** Generous: the Linux container may run x86-64 under emulation. */
    static final long WAIT_MS = 30000;
    static final long CALL_MS = 30000;
    /** How often {@link #awaitFocusedWindow} re-issues {@code toFront()} + {@code requestFocus()} while it waits. */
    static final long REACTIVATE_MS = 1000;

    static FakeContext ctx;
    static SwingModule module;
    static TestWidgets.Firer firer;
    static AutoCloseable firerRegistration;
    static AutoCloseable extractorRegistration;
    static TestUi ui;

    @BeforeAll
    static void startDisplay() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(),
                "needs a display (run under Xvfb with -Dskillroy.headless=false)");
        ctx = new FakeContext();
        module = new SwingModule();
        module.install(ctx);
        module.onTargetReady(ctx);
        ctx.spi.register(NameEnricher.class, new TestWidgets.Enricher());
        ctx.spi.register(LocatorResolver.class, new TestWidgets.ActionLocator());
        firer = new TestWidgets.Firer();
        firerRegistration = ctx.spi.register(ActionFirer.class, firer);
        extractorRegistration = ctx.spi.register(DialogTextExtractor.class, new TestWidgets.PromptExtractor());
        ui = TestUi.show();
        // the tap goes in once AWT runs (at install, or shortly after from the tap thread) and then publishes
        // window.opened for the windows already showing; let both happen before a test looks at the events
        JsonCheck.await("AWT tap installed", WAIT_MS, module::tapInstalled);
        LocatorTest.onEdt(() -> null);
        // a freshly shown frame takes the focus by itself, but its activation ends a few milliseconds after the
        // frame shows (the focused-window event, then the focus moves to its first component with one more native
        // request); a window a test shows inside that interval loses the focus to the frame again, or leaves the
        // frame's first component without the focus, so wait for the frame and let the focus transfer finish
        awaitFocusedWindow("the test frame is the focused window", ui.frame);
        settle();
    }

    @AfterAll
    static void stopDisplay() throws Exception {
        if (module == null) {
            return;
        }
        TestUi.disposeAll();
        module.uninstall();
        ctx.shutdown();
        module = null;
    }

    static Map<String, Object> call(String method, Object... kv) throws Exception {
        return ctx.call(method, map(kv), CALL_MS);
    }

    /**
     * Makes the window the focused window: {@code toFront()} + {@code requestFocus()} on the EDT, then
     * {@link #awaitFocusedWindow}. Every display test that activates a window itself does it through this helper.
     */
    static void activate(String what, Window w) throws Exception {
        requestActivation(w);
        awaitFocusedWindow(what, w);
    }

    /**
     * Waits (at most {@link #WAIT_MS}) until the window is the focused window, whether it was just shown and should
     * take the focus by itself or was activated with {@link #activate}, and re-issues {@code toFront()} +
     * {@code requestFocus()} on the EDT once every {@link #REACTIVATE_MS} while it waits. Under the WM-less Xvfb a
     * single activation sometimes never makes the window the focused window (the open flake recorded in
     * {@code docs/TODO.md}); the re-issue is the test-side mitigation, and the failure message counts the re-issues.
     */
    static void awaitFocusedWindow(String what, final Window w) {
        long start = System.currentTimeMillis();
        long deadline = start + WAIT_MS;
        long nextActivation = start + REACTIVATE_MS;
        int reissued = 0;
        while (!w.isFocused()) {
            long now = System.currentTimeMillis();
            if (now > deadline) {
                fail("timed out after " + WAIT_MS + " ms waiting for " + what + " (toFront() + requestFocus() "
                        + "re-issued " + reissued + " times)");
            }
            if (now >= nextActivation) {
                requestActivation(w);
                reissued++;
                nextActivation = now + REACTIVATE_MS;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + what);
            }
        }
    }

    /** Posts {@code toFront()} + {@code requestFocus()} to the EDT; never waits for the EDT, so the wait stays bounded. */
    private static void requestActivation(final Window w) {
        SwingUtilities.invokeLater(() -> {
            if (w.isDisplayable() && !w.isFocused()) {
                w.toFront();
                w.requestFocus();
            }
        });
    }

    /** Settles the EDT (two markers, a short quiet period) so that posted actions have run. */
    static void settle() throws Exception {
        call("swing.settle", "timeoutMs", 20000L, "quietMs", 100L);
    }

    static String ref(Object component) {
        return ctx.handles.handleFor(component, ComponentView.KIND);
    }

    static List<Event> events(String topic, long since) {
        return ctx.events.since(since, TopicFilter.parse(topic), 1000);
    }
}
