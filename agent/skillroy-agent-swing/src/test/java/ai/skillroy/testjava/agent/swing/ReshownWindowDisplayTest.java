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

import ai.skillroy.testjava.protocol.Event;

import java.awt.Window;
import java.util.List;
import java.util.Map;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment A9: AWT raises {@code WINDOW_OPENED} only the first time a window is shown, so the tap compares the showing
 * windows with those it reported. A window hidden gets {@code window.closed}, shown again {@code window.opened} with
 * {@code reshown: true}; the reading primitives and {@code swing.settle} compare at their start, so they see a change
 * that raised no window event at all (an unfocusable window).
 */
class ReshownWindowDisplayTest extends DisplayTestBase {

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
    void aDialogHiddenAndShownAgainIsReportedClosedAndOpenedAgain() throws Exception {
        final long since = ctx.events.lastSeq();
        final JDialog d = onEdt(() -> {
            JDialog dialog = new JDialog(ui.frame, "Reused", false);
            dialog.setBounds(660, 0, 200, 90);
            dialog.setVisible(true);
            return dialog;
        });
        final String ref = ref(d);
        await("window.opened", WAIT_MS, () -> count("window.opened", since, ref) == 1);
        setVisible(d, false);
        await("window.closed for the hidden dialog", WAIT_MS, () -> count("window.closed", since, ref) == 1);
        setVisible(d, true);
        await("window.opened again", WAIT_MS, () -> count("window.opened", since, ref) == 2);
        List<Event> opened = forRef("window.opened", since, ref);
        assertEquals(map("ref", ref, "type", "dialog", "title", "Reused", "modal", false), opened.get(0).payload(),
                "the first opening carries no reshown key");
        assertEquals(map("ref", ref, "type", "dialog", "title", "Reused", "modal", false, "reshown", true),
                opened.get(1).payload());

        // disposed: closed once (WINDOW_CLOSED and the comparison do not both report it); shown again: reshown
        onEdt(() -> {
            d.dispose();
            return null;
        });
        await("window.closed for the disposed dialog", WAIT_MS, () -> count("window.closed", since, ref) == 2);
        setVisible(d, true);
        await("window.opened after the dispose", WAIT_MS, () -> count("window.opened", since, ref) == 3);
        assertEquals(Boolean.TRUE, forRef("window.opened", since, ref).get(2).payload().get("reshown"));
        settle();
        assertEquals(3, count("window.opened", since, ref), "no duplicates");
        assertEquals(2, count("window.closed", since, ref), "no duplicates");
    }

    @Test
    void readingPrimitivesSeeAWindowThatChangedWithoutAnyWindowEvent() throws Exception {
        // an unfocusable dialog: hiding or showing it moves no focus, so AWT raises nothing the tap could hear
        final JDialog d = onEdt(() -> {
            JDialog dialog = new JDialog(ui.frame, "Quiet", false);
            dialog.setFocusableWindowState(false);
            dialog.setBounds(660, 120, 200, 90);
            dialog.setVisible(true);
            return dialog;
        });
        final String ref = ref(d);
        await("quiet dialog showing", WAIT_MS, d::isShowing);
        settle();
        for (String primitive : new String[] {"swing.windows", "swing.snapshot", "swing.dialogs", "swing.settle"}) {
            long since = ctx.events.lastSeq();
            hide(d, false);
            Map<String, Object> result = call(primitive);
            assertEquals(1, count("window.closed", since, ref), primitive + " compared before answering");
            if (result.containsKey("seq")) {
                long closedSeq = forRef("window.closed", since, ref).get(0).seq();
                assertTrue((Long) result.get("seq") >= closedSeq, "the snapshot's seq covers the closing");
            }
            since = ctx.events.lastSeq();
            hide(d, true);
            call(primitive);
            List<Event> reopened = forRef("window.opened", since, ref);
            assertEquals(1, reopened.size(), primitive + " compared before answering");
            assertEquals(Boolean.TRUE, reopened.get(0).payload().get("reshown"));
        }
    }

    @Test
    void settleCountsAWindowHiddenDuringItsQuietPeriod() throws Exception {
        final JDialog d = onEdt(() -> {
            JDialog dialog = new JDialog(ui.frame, "Hidden later", false);
            dialog.setFocusableWindowState(false);
            dialog.setBounds(660, 240, 200, 90);
            dialog.setVisible(true);
            return dialog;
        });
        final String ref = ref(d);
        await("dialog showing", WAIT_MS, d::isShowing);
        settle();
        final long since = ctx.events.lastSeq();
        SwingUtilities.invokeLater(() -> {
            Timer t = new Timer(300, e -> d.setVisible(false));
            t.setRepeats(false);
            t.start();
        });
        Map<String, Object> r = call("swing.settle", "quietMs", 2500L, "timeoutMs", 25000L);
        assertEquals(Boolean.TRUE, r.get("settled"));
        assertTrue((Long) r.get("ms") >= 2500, "a full quiet period: " + r);
        assertEquals(1, count("window.closed", since, ref), "settle saw the hidden dialog before it settled");
    }

    /** Shows or hides the window and waits until AWT agrees, without asking the module anything. */
    private static void hide(final Window w, final boolean visible) throws Exception {
        onEdt(() -> {
            w.setVisible(visible);
            return null;
        });
        await("showing=" + visible, WAIT_MS, () -> w.isShowing() == visible);
    }

    private static void setVisible(final Window w, final boolean visible) throws Exception {
        hide(w, visible);
        settle();
    }

    private static List<Event> forRef(String topic, long since, String ref) {
        List<Event> out = new java.util.ArrayList<Event>();
        for (Event e : events(topic, since)) {
            if (ref.equals(e.payload().get("ref"))) {
                out.add(e);
            }
        }
        return out;
    }

    private static int count(String topic, long since, String ref) {
        return forRef(topic, since, ref).size();
    }
}
