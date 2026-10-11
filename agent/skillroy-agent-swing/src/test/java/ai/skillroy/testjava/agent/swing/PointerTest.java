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
import ai.skillroy.testjava.protocol.JsonView;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComponent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code swing.pointer}'s routed sequence without a display (phase7-contracts.md section 13.1 item 1): the events per
 * action (masks, counts, the popup trigger per {@code os.name}, the drag's release with click count 0 and no click, the
 * wheel's rotation and amount), the parameter checks, the {@code cancelled} flag (a task after the deadline dispatches
 * nothing; a dispatched press is disarmed by a drag to (-1, -1) and released there, {@code balanced}) and the per-event
 * modal re-read ({@code MODAL_OPENED}, {@code dispatched n of m}). A recording component stands in for the window; that
 * no action fires on a real button is the display tests'.
 */
class PointerTest {

    private final FakeContext ctx = new FakeContext();

    @AfterEach
    void tearDown() {
        ctx.shutdown();
    }

    /** Records every mouse event delivered to it. */
    static final class Recorder extends JComponent {
        private static final long serialVersionUID = 1L;
        final List<MouseEvent> seen = new ArrayList<MouseEvent>();

        Recorder() {
            setBounds(0, 0, 200, 100);
            MouseAdapter all = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    seen.add(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    seen.add(e);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    seen.add(e);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    seen.add(e);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    seen.add(e);
                }

                @Override
                public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    seen.add(e);
                }
            };
            addMouseListener(all);
            addMouseMotionListener(all);
            addMouseWheelListener(all);
        }
    }

    private static Pointer.Request request(Object... kv) {
        Map<String, Object> m = map(kv);
        if (!m.containsKey("point") && !m.containsKey("by")) {
            m.put("by", map("id", "x"));
        }
        return Pointer.parse(JsonView.of(m));
    }

    private static List<MouseEvent> events(Pointer.Request r, Point last, String os) {
        return Pointer.events(r, new Recorder(), new Point(10, 20), new Point(50, 60), new Point(100, 200), last, 5L,
                !os.toLowerCase().contains("windows"));
    }

    private static List<Integer> ids(List<MouseEvent> es) {
        List<Integer> out = new ArrayList<Integer>();
        for (MouseEvent e : es) {
            out.add(e.getID());
        }
        return out;
    }

    @Test
    void aClickIsMovePressReleaseClickWithTheButtonMasksAndCounts() {
        List<MouseEvent> es = events(request("action", "CLICK"), null, "Linux");
        assertEquals(list(MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED,
                MouseEvent.MOUSE_CLICKED), new ArrayList<Object>(ids(es)));
        MouseEvent press = es.get(1);
        assertEquals(InputEvent.BUTTON1_DOWN_MASK, press.getModifiersEx());
        assertEquals(1, press.getClickCount());
        assertEquals(MouseEvent.BUTTON1, press.getButton());
        assertEquals(new Point(10, 20), press.getPoint());
        assertEquals(new Point(110, 220), press.getLocationOnScreen());
        assertEquals(5L, press.getWhen(), "the call's own when, which the listener recognises");
        assertEquals(0, es.get(2).getModifiersEx(), "the release carries the remaining buttons' mask");
        assertEquals(1, es.get(3).getClickCount());
        assertEquals(3, events(request("action", "CLICK"), new Point(10, 20), "Linux").size(),
                "no move when the last routed position is the point");
    }

    @Test
    void aDoubleClickIsTwoClicksCountedOneAndTwo() {
        List<MouseEvent> es = events(request("action", "DOUBLE_CLICK"), new Point(10, 20), "Linux");
        assertEquals(6, es.size());
        assertEquals(1, es.get(0).getClickCount());
        assertEquals(1, es.get(2).getClickCount());
        assertEquals(2, es.get(3).getClickCount());
        assertEquals(2, es.get(5).getClickCount());
        assertEquals(MouseEvent.MOUSE_CLICKED, es.get(5).getID());
    }

    @Test
    void thePopupTriggerFollowsThePlatform() {
        List<MouseEvent> linux = events(request("action", "CLICK", "button", "RIGHT"), null, "Linux");
        assertTrue(linux.get(1).isPopupTrigger(), "on the press on X11");
        assertFalse(linux.get(2).isPopupTrigger());
        assertEquals(InputEvent.BUTTON3_DOWN_MASK, linux.get(1).getModifiersEx() & InputEvent.BUTTON3_DOWN_MASK);
        List<MouseEvent> windows = events(request("action", "CLICK", "button", "RIGHT"), null, "Windows 11");
        assertFalse(windows.get(1).isPopupTrigger());
        assertTrue(windows.get(2).isPopupTrigger(), "on the release on Windows");
        List<MouseEvent> left = events(request("action", "CLICK"), null, "Linux");
        assertFalse(left.get(1).isPopupTrigger());
    }

    @Test
    void aDragEndsWithAReleaseOfClickCountZeroAndNoClick() {
        List<MouseEvent> es = events(request("action", "DRAG", "to", map("fx", 1.0, "fy", 1.0), "steps", 4L),
                new Point(10, 20), "Linux");
        assertEquals(6, es.size());
        assertEquals(MouseEvent.MOUSE_PRESSED, es.get(0).getID());
        for (int i = 1; i <= 4; i++) {
            assertEquals(MouseEvent.MOUSE_DRAGGED, es.get(i).getID());
            assertEquals(InputEvent.BUTTON1_DOWN_MASK, es.get(i).getModifiersEx());
        }
        assertEquals(new Point(50, 60), es.get(4).getPoint(), "the last sample is at 'to'");
        MouseEvent release = es.get(5);
        assertEquals(MouseEvent.MOUSE_RELEASED, release.getID());
        assertEquals(0, release.getClickCount());
        assertEquals(new Point(50, 60), release.getPoint());
    }

    @Test
    void aWheelIsOneUnitScrollPerNotch() {
        List<MouseEvent> es = events(request("action", "WHEEL", "wheel", map("notches", -3L)), new Point(10, 20),
                "Linux");
        assertEquals(3, es.size());
        for (MouseEvent e : es) {
            MouseWheelEvent w = (MouseWheelEvent) e;
            assertEquals(-1, w.getWheelRotation());
            assertEquals(3, w.getScrollAmount());
            assertEquals(MouseWheelEvent.WHEEL_UNIT_SCROLL, w.getScrollType());
        }
    }

    @Test
    void modifiersAreOnEveryEvent() {
        List<MouseEvent> es = events(request("action", "CLICK", "modifiers", list("SHIFT", "CTRL")), null, "Linux");
        int mods = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK;
        for (MouseEvent e : es) {
            assertEquals(mods, e.getModifiersEx() & mods, "event " + e.getID());
        }
        assertEquals(1, events(request("action", "MOVE"), new Point(10, 20), "Linux").size(), "a move alone");
    }

    @Test
    void malformedParametersAreRefused() {
        Object[][] bad = {
            {"action", "POKE"},
            {"action", "DRAG", "to", map("fx", 0.5, "fy", 0.5), "steps", 51L},
            {"action", "CLICK", "to", map("fx", 0.5, "fy", 0.5)},
            {"action", "DRAG"},
            {"action", "WHEEL"},
            {"action", "WHEEL", "wheel", map("notches", 51L)},
            {"action", "CLICK", "clickCount", 4L},
            {"action", "CLICK", "button", "FOURTH"},
            {"action", "CLICK", "via", "TELEPATHY"},
            {"action", "CLICK", "point", map("x", 1L, "y", 2L), "space", "SCREEN", "region", "key:1"},
            {"action", "CLICK", "point", map("x", 1L, "y", 2L)},
            {"action", "CLICK", "point", map("x", 1L, "y", 2L), "space", map("planet", "x")},
            {"action", "CLICK", "region", "bad region!"},
            {"action", "CLICK", "at", map("fx", 2.0, "fy", 0.5)},
            {"action", "CLICK", "region", "key:1", "row", 1L},
        };
        for (Object[] kv : bad) {
            expectError(ErrorCodes.INVALID_PARAMS, () -> request(kv));
        }
    }

    // ------------------------------------------------------------------------------------ the call's state

    private Pointer.Plan plan(Recorder source, AtomicBoolean blocked, List<MouseEvent> events) {
        Pointer.Plan p = new Pointer.Plan();
        p.source = source;
        p.blocked = blocked::get;
        p.events.addAll(events);
        return p;
    }

    private static Pointer.State state(int of, boolean pressIntended) {
        Pointer.State st = new Pointer.State(of, pressIntended);
        st.button = MouseEvent.BUTTON1;
        st.pressMods = InputEvent.BUTTON1_DOWN_MASK;
        return st;
    }

    @Test
    void aTaskAfterTheDeadlineDispatchesNothingAndAPressIsBalanced() throws Exception {
        final Recorder rec = new Recorder();
        final AtomicBoolean blocked = new AtomicBoolean();
        final List<MouseEvent> es = Pointer.events(request("action", "CLICK"), rec, new Point(10, 20), null,
                new Point(0, 0), null, 9L, true);
        final Pointer pointer = new Pointer(ctx, new Windows(), new RecentCaptures(), "Linux");
        final Pointer.Plan plan = plan(rec, blocked, es);
        final Pointer.State st = state(es.size(), false);
        onEdt(() -> {
            pointer.dispatch(plan, st, 0, es.get(0), false);
            pointer.dispatch(plan, st, 1, es.get(1), false);
            return null;
        });
        assertTrue(Pointer.cancel(st), "the dispatched press is promised a balance");
        onEdt(() -> {
            pointer.dispatch(plan, st, 2, es.get(2), false);
            pointer.dispatch(plan, st, 3, es.get(3), false);
            return null;
        });
        assertEquals(2, st.sent);
        assertTrue(st.balanced);
        assertEquals(Pointer.EDT_BUSY, st.reason);
        assertEquals(4, rec.seen.size(), "move, press, then the balance only: " + rec.seen);
        MouseEvent drag = rec.seen.get(2);
        assertEquals(MouseEvent.MOUSE_DRAGGED, drag.getID());
        assertEquals(new Point(-1, -1), drag.getPoint());
        MouseEvent release = rec.seen.get(3);
        assertEquals(MouseEvent.MOUSE_RELEASED, release.getID());
        assertEquals(new Point(-1, -1), release.getPoint());
        assertEquals(0, release.getClickCount());
        for (MouseEvent e : rec.seen) {
            assertFalse(e.getID() == MouseEvent.MOUSE_CLICKED, "no click after a failed call");
        }
    }

    @Test
    void aSequenceCancelledBeforeItsPressDispatchesNothing() throws Exception {
        final Recorder rec = new Recorder();
        final List<MouseEvent> es = Pointer.events(request("action", "CLICK"), rec, new Point(10, 20), null,
                new Point(0, 0), null, 9L, true);
        final Pointer pointer = new Pointer(ctx, new Windows(), new RecentCaptures(), "Linux");
        final Pointer.Plan plan = plan(rec, new AtomicBoolean(), es);
        final Pointer.State st = state(es.size(), false);
        assertFalse(Pointer.cancel(st));
        onEdt(() -> {
            for (int i = 0; i < es.size(); i++) {
                pointer.dispatch(plan, st, i, es.get(i), false);
            }
            return null;
        });
        assertEquals(0, rec.seen.size());
        assertFalse(st.balanced);
    }

    @Test
    void aModalDialogOpenedByThePressWithholdsTheRestAndBalancesThePress() throws Exception {
        final Recorder rec = new Recorder();
        final AtomicBoolean blocked = new AtomicBoolean();
        final List<MouseEvent> es = Pointer.events(request("action", "CLICK"), rec, new Point(10, 20), null,
                new Point(0, 0), null, 9L, true);
        final Pointer pointer = new Pointer(ctx, new Windows(), new RecentCaptures(), "Linux");
        final Pointer.Plan plan = plan(rec, blocked, es);
        final Pointer.State st = state(es.size(), false);
        onEdt(() -> {
            pointer.dispatch(plan, st, 0, es.get(0), false);
            pointer.dispatch(plan, st, 1, es.get(1), false);
            blocked.set(true); // the press showed a modal dialog
            pointer.dispatch(plan, st, 2, es.get(2), false);
            pointer.dispatch(plan, st, 3, es.get(3), false);
            return null;
        });
        assertEquals(Pointer.MODAL_OPENED, st.reason);
        assertEquals(2, st.sent, "dispatched 2 of 4");
        assertTrue(st.balanced);
        assertEquals(0, st.done.getCount(), "the caller is woken at once");
        assertEquals(MouseEvent.MOUSE_DRAGGED, rec.seen.get(2).getID());
        assertEquals(4, rec.seen.size());
    }

    @Test
    void forceSkipsTheModalReRead() throws Exception {
        final Recorder rec = new Recorder();
        final List<MouseEvent> es = Pointer.events(request("action", "CLICK"), rec, new Point(10, 20), null,
                new Point(0, 0), null, 9L, true);
        final Pointer pointer = new Pointer(ctx, new Windows(), new RecentCaptures(), "Linux");
        final Pointer.Plan plan = plan(rec, new AtomicBoolean(true), es);
        final Pointer.State st = state(es.size(), false);
        onEdt(() -> {
            for (int i = 0; i < es.size(); i++) {
                pointer.dispatch(plan, st, i, es.get(i), true);
            }
            return null;
        });
        assertEquals(4, st.sent);
        assertEquals(4, rec.seen.size());
        assertEquals(0, st.done.getCount());
    }

    @Test
    void anIntendedPressIsNeverBalanced() throws Exception {
        final Recorder rec = new Recorder();
        final List<MouseEvent> es = Pointer.events(request("action", "PRESS"), rec, new Point(10, 20), null,
                new Point(0, 0), null, 9L, true);
        final Pointer pointer = new Pointer(ctx, new Windows(), new RecentCaptures(), "Linux");
        final Pointer.Plan plan = plan(rec, new AtomicBoolean(), es);
        final Pointer.State st = state(es.size(), true);
        onEdt(() -> {
            for (int i = 0; i < es.size(); i++) {
                pointer.dispatch(plan, st, i, es.get(i), false);
            }
            return null;
        });
        assertFalse(Pointer.cancel(st), "a PRESS is meant to stay down (routed only)");
        assertEquals(2, rec.seen.size());
    }
}
