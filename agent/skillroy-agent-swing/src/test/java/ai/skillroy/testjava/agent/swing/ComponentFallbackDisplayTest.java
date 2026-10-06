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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Window;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.fired;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment A28: {@code swing.click} with rung COMPONENT and {@code fallback: "ACTION"} fires through the ACTION rung
 * when the focus does not arrive and says so ({@code fidelity: "DEGRADED"}, {@code requestedRung: "COMPONENT"}, the
 * focus failure as the reason); a click that gets the focus fires on COMPONENT with {@code fidelity: "FULL"}; without
 * the fallback nothing changes. The fall-back clicks exactly once and leaves no listener behind, and it still honours
 * modal blocking.
 */
class ComponentFallbackDisplayTest extends DisplayTestBase {

    /** Short enough to keep the test quick; the focus never arrives in these cases anyway. */
    private static final long SHORT_FOCUS_MS = 300L;
    /** Generous: window activation took up to about 400 ms on a window-manager-less Xvfb, more under emulation. */
    private static final long FOCUS_MS = 20000L;

    @AfterEach
    void closeExtraWindows() throws Exception {
        onEdt(() -> {
            for (Window w : Window.getWindows()) {
                if (w != ui.frame && w.isDisplayable()) {
                    w.dispose();
                }
            }
            return null;
        });
        await("modal dialogs gone", WAIT_MS, () -> Windows.showingModalDialogs().isEmpty());
        settle();
    }

    @Test
    void aClickWhoseFocusDoesNotArriveFallsBackToActionOnce() throws Exception {
        final JFrame thief = focusThief();
        final int clicks = ui.okClicks.get();
        final int focusListeners = onEdt(() -> ui.ok.getFocusListeners().length);

        Map<String, Object> r = call("swing.click", "target", ref(ui.ok), "rung", "COMPONENT",
                "focusTimeoutMs", SHORT_FOCUS_MS, "fallback", "ACTION");
        assertEquals("ACTION", r.get("rung"), r.toString());
        assertEquals("COMPONENT", r.get("requestedRung"));
        assertEquals("DEGRADED", r.get("fidelity"));
        assertEquals(Boolean.TRUE, r.get("fired"));
        String reason = String.valueOf(r.get("reason"));
        assertTrue(reason.startsWith("FOCUS_FAILED: focus did not arrive within"), reason);
        assertTrue(reason.contains("requestFocusInWindow() returned false"), reason);
        await("clicked through the ACTION rung", WAIT_MS, () -> ui.okClicks.get() == clicks + 1);
        settle();
        assertEquals(clicks + 1, ui.okClicks.get(), "clicked exactly once");
        assertTrue(onEdt(thief::isFocused), "the fall-back does not move the focus");
        assertEquals(focusListeners, (int) onEdt(() -> ui.ok.getFocusListeners().length), "focus listener removed");
    }

    @Test
    void withoutTheFallbackNothingChanges() throws Exception {
        focusThief();
        final int clicks = ui.okClicks.get();
        Map<String, Object> r = call("swing.click", "target", ref(ui.ok), "rung", "COMPONENT",
                "focusTimeoutMs", SHORT_FOCUS_MS);
        assertEquals("FOCUS_FAILED", r.get("rung"));
        assertEquals(Boolean.FALSE, r.get("fired"));
        assertFalse(r.containsKey("fidelity"), "a click that did not fire has no fidelity: " + r);
        assertFalse(r.containsKey("requestedRung"), r.toString());
        settle();
        assertEquals(clicks, ui.okClicks.get(), "nothing is clicked without focus");
    }

    @Test
    void anUnfocusableComponentFallsBackToo() throws Exception {
        final int clicks = ui.unfocusableClicks.get();
        Map<String, Object> r = call("swing.click", "target", ref(ui.unfocusable), "rung", "COMPONENT",
                "focusTimeoutMs", SHORT_FOCUS_MS, "fallback", "action");
        assertEquals("DEGRADED", r.get("fidelity"), r.toString());
        assertEquals("ACTION", r.get("rung"));
        await("the unfocusable button clicked through ACTION", WAIT_MS,
                () -> ui.unfocusableClicks.get() == clicks + 1);
    }

    @Test
    void aWindowThatCannotTakeTheFocusFallsBackAtOnce() throws Exception {
        focusThief();
        final AtomicInteger clicks = new AtomicInteger();
        final JButton[] button = new JButton[1];
        onEdt(() -> {
            JFrame f = new JFrame("Never focused");
            f.setFocusableWindowState(false);
            button[0] = new JButton("Unreachable");
            button[0].addActionListener(e -> clicks.incrementAndGet());
            f.add(button[0]);
            f.setBounds(660, 300, 200, 80);
            f.setVisible(true);
            return null;
        });
        await("unfocusable frame showing", WAIT_MS, button[0]::isShowing);
        long start = System.nanoTime();
        Map<String, Object> r = call("swing.click", "target", ref(button[0]), "rung", "COMPONENT",
                "focusTimeoutMs", FOCUS_MS, "activateWindow", true, "fallback", "ACTION");
        long ms = (System.nanoTime() - start) / 1000000L;
        assertEquals("DEGRADED", r.get("fidelity"), r.toString());
        assertTrue(String.valueOf(r.get("reason")).startsWith("FOCUS_FAILED: "), r.toString());
        assertTrue(String.valueOf(r.get("reason")).contains("not a focusable window"), r.toString());
        assertTrue(ms < FOCUS_MS, "reported at once, not after the focus timeout: " + ms + " ms");
        await("clicked through the ACTION rung", WAIT_MS, () -> clicks.get() == 1);
        settle();
        assertEquals(1, clicks.get());
    }

    @Test
    void aClickThatGetsTheFocusReportsFullFidelity() throws Exception {
        focusThief();
        final int clicks = ui.cancelClicks.get();
        assertEquals(fired("COMPONENT", "COMPONENT"), call("swing.click", "by", map("text", "Cancel"),
                "rung", "COMPONENT", "focusTimeoutMs", FOCUS_MS, "activateWindow", true, "fallback", "ACTION"));
        await("clicked on the COMPONENT rung", WAIT_MS, () -> ui.cancelClicks.get() == clicks + 1);
        assertTrue(onEdt(ui.cancel::hasFocus), "the component rung, not the fall-back, did the click");
    }

    @Test
    void theFallbackHonoursModalBlocking() throws Exception {
        final JDialog[] modal = new JDialog[1];
        onEdt(() -> {
            modal[0] = new JDialog(ui.frame, "Blocking", true);
            modal[0].add(new JButton("Inside"));
            modal[0].setBounds(660, 0, 220, 90);
            return null;
        });
        SwingUtilities.invokeLater(() -> modal[0].setVisible(true)); // blocks in the modal loop
        await("modal dialog showing", WAIT_MS, modal[0]::isShowing);
        settle();
        final int clicks = ui.okClicks.get();
        RpcException e = expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.click", "target", ref(ui.ok),
                "rung", "COMPONENT", "focusTimeoutMs", SHORT_FOCUS_MS, "fallback", "ACTION"));
        assertEquals(ref(modal[0]), e.error().data().get("dialog"));
        // forced: a blocked window cannot get the focus, so the forced click falls back and still goes through
        Map<String, Object> r = call("swing.click", "target", ref(ui.ok), "rung", "COMPONENT",
                "focusTimeoutMs", SHORT_FOCUS_MS, "fallback", "ACTION", "force", true);
        assertEquals("DEGRADED", r.get("fidelity"), r.toString());
        await("forced fall-back click delivered under the modal dialog", WAIT_MS,
                () -> ui.okClicks.get() == clicks + 1);
    }

    @Test
    void fallbackIsRejectedWithOtherRungsAndOtherValues() throws Exception {
        final int clicks = ui.okClicks.get();
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.click", "target", ref(ui.ok), "fallback", "ACTION"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.click", "target", ref(ui.ok), "rung", "DISPATCH",
                "fallback", "ACTION"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.click", "target", ref(ui.ok), "rung", "ROBOT",
                "fallback", "ACTION"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.click", "target", ref(ui.ok), "rung", "COMPONENT",
                "fallback", "DISPATCH"));
        settle();
        assertEquals(clicks, ui.okClicks.get(), "a rejected call clicks nothing");
    }

    /** Shows a second frame and waits until it, not the test frame, is the focused window. */
    private static JFrame focusThief() throws Exception {
        final JFrame[] other = new JFrame[1];
        onEdt(() -> {
            other[0] = new JFrame("Focus thief");
            other[0].add(new JButton("Thief"));
            other[0].setBounds(660, 150, 200, 100);
            other[0].setVisible(true);
            return null;
        });
        await("the second frame is the focused window", WAIT_MS, () -> other[0].isFocused());
        return other[0];
    }
}
