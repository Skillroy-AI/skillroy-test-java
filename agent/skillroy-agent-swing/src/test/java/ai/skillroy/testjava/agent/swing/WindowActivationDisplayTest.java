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

import java.awt.Window;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JFrame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.fired;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment A10: the COMPONENT rung can only focus a component in the focused window. With {@code activateWindow} it
 * first brings the component's window to the front, requests the focus for it and waits for it to be the focused
 * window, so a click into an unfocused second frame succeeds; without it the rung reports FOCUS_FAILED and clicks
 * nothing. Every listener the rung adds is removed again.
 */
class WindowActivationDisplayTest extends DisplayTestBase {

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
        settle();
    }

    @Test
    void componentClickIntoAnUnfocusedFrameNeedsActivateWindow() throws Exception {
        final JFrame other = focusThief("Focus thief", true);
        final int clicks = ui.okClicks.get();
        final int focusListeners = onEdt(() -> ui.ok.getFocusListeners().length);
        final int windowListeners = onEdt(() -> ui.frame.getWindowFocusListeners().length);

        Map<String, Object> without = call("swing.click", "target", ref(ui.ok), "rung", "COMPONENT",
                "focusTimeoutMs", 300L);
        assertEquals("FOCUS_FAILED", without.get("rung"), without.toString());
        assertEquals(Boolean.FALSE, without.get("fired"));
        settle();
        assertEquals(clicks, ui.okClicks.get(), "nothing is clicked without focus");
        assertTrue(onEdt(other::isFocused), "and the other frame keeps the focus");

        Map<String, Object> with = call("swing.click", "target", ref(ui.ok), "rung", "COMPONENT",
                "focusTimeoutMs", FOCUS_MS, "activateWindow", true);
        assertEquals(fired("COMPONENT", "COMPONENT"), with);
        await("clicked once the window was activated", WAIT_MS, () -> ui.okClicks.get() == clicks + 1);
        assertTrue(onEdt(ui.frame::isFocused), "the component's window is the focused window");
        assertTrue(onEdt(ui.ok::hasFocus), "the component owns the focus");
        settle();
        assertEquals(focusListeners, (int) onEdt(() -> ui.ok.getFocusListeners().length), "focus listener removed");
        assertEquals(windowListeners, (int) onEdt(() -> ui.frame.getWindowFocusListeners().length),
                "window focus listener removed");
    }

    @Test
    void activateWindowOnTheFocusedWindowJustClicks() throws Exception {
        activate("the test frame is the focused window", ui.frame);
        final int clicks = ui.cancelClicks.get();
        assertEquals(fired("COMPONENT", "COMPONENT"), call("swing.click", "by", map("text", "Cancel"),
                "rung", "COMPONENT", "focusTimeoutMs", FOCUS_MS, "activateWindow", true));
        await("clicked", WAIT_MS, () -> ui.cancelClicks.get() == clicks + 1);
        // other rungs ignore the flag
        assertEquals(fired("ACTION", "AUTO"),
                call("swing.click", "by", map("text", "Cancel"), "activateWindow", true));
        await("clicked again", WAIT_MS, () -> ui.cancelClicks.get() == clicks + 2);
    }

    @Test
    void aWindowThatCannotTakeTheFocusIsReportedAtOnce() throws Exception {
        focusThief("Focus thief", true);
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
        Map<String, Object> r = call("swing.click", "target", ref(button[0]), "rung", "COMPONENT",
                "focusTimeoutMs", FOCUS_MS, "activateWindow", true);
        assertEquals("FOCUS_FAILED", r.get("rung"));
        assertEquals(Boolean.FALSE, r.get("fired"));
        assertTrue(String.valueOf(r.get("reason")).contains("not a focusable window"), r.toString());
        settle();
        assertEquals(0, clicks.get());
    }

    /** Shows a second frame and waits until it, not the test frame, is the focused window. */
    private static JFrame focusThief(final String title, final boolean focusable) throws Exception {
        final JFrame[] other = new JFrame[1];
        onEdt(() -> {
            other[0] = new JFrame(title);
            other[0].setFocusableWindowState(focusable);
            other[0].add(new JButton("Thief"));
            other[0].setBounds(660, 150, 200, 100);
            other[0].setVisible(true);
            return null;
        });
        awaitFocusedWindow("the second frame is the focused window", other[0]);
        return other[0];
    }
}
