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

import ai.skillroy.testjava.agent.api.spi.SurfaceSource;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.AWTEvent;
import java.awt.Button;
import java.awt.Canvas;
import java.awt.Checkbox;
import java.awt.CheckboxGroup;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Panel;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.arr;
import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The routed pointer into heavyweight containers and AWT controls on the click rungs, on a real display (the
 * agent-level halves of phase8-contracts.md 13.2 items 7 and 8): a frame with an AWT {@code Panel} holding a {@code JButton}, an
 * AWT {@code Button}, a {@code Checkbox} and a listening {@code Canvas}, and a Swing button outside it. A routed click
 * reaches each component inside the panel (the AWT ones through their peers on X11), {@code received} names it and
 * {@code source} is the panel; outside, the window stays the source; a cancelled press into the AWT button fires
 * nothing (the exit-then-release balance); {@code MODAL_OPEN} still applies; a canvas a {@code SurfaceSource} claims
 * keeps the window as source. ACTION and COMPONENT fire the AWT controls semantically (one {@code ActionEvent}, one
 * {@code ItemEvent}, a selected radio left alone, a disabled button refused), and the dialog reader treats an AWT
 * {@code Button} as a button.
 */
class HeavyweightRoutingDisplayTest extends DisplayTestBase {

    static JFrame frame;
    static Panel scanner;
    static JButton reread;
    static Button scan;
    static Checkbox lid;
    static Canvas pad;
    static JButton outside;
    static Checkbox radioA;
    static Checkbox radioB;
    static Button disabled;
    static final AtomicInteger rereads = new AtomicInteger();
    static final List<String> scans = new CopyOnWriteArrayList<String>();
    static final List<Integer> lidChanges = new CopyOnWriteArrayList<Integer>();
    static final AtomicInteger padClicks = new AtomicInteger();
    static final AtomicInteger outsideClicks = new AtomicInteger();
    static final AtomicInteger radioEvents = new AtomicInteger();

    @BeforeAll
    static void showFixture() throws Exception {
        onEdt(() -> {
            JFrame f = new JFrame("Scanner panel");
            f.setAutoRequestFocus(false);
            JPanel content = new JPanel(new java.awt.BorderLayout());
            // a grid, so that every control lies inside the window (a control clipped away would be collapsed)
            Panel p = new Panel(new java.awt.GridLayout(2, 4, 4, 4));
            p.setName("scanner");
            JButton j = new JButton("Re-read member card");
            j.setName("rereadCard");
            j.addActionListener(e -> rereads.incrementAndGet());
            Button b = new Button("Scan");
            b.setName("scanButton");
            b.addActionListener(e -> scans.add(e.getActionCommand()));
            Checkbox c = new Checkbox("Deposit box lid open");
            c.setName("lidOpen");
            c.addItemListener(e -> lidChanges.add(e.getStateChange()));
            Canvas cv = new Canvas();
            cv.setName("pad");
            cv.setPreferredSize(new Dimension(40, 30));
            cv.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    padClicks.incrementAndGet();
                }
            });
            p.add(j);
            p.add(b);
            p.add(c);
            p.add(cv);
            CheckboxGroup group = new CheckboxGroup();
            Checkbox ra = new Checkbox("Front", group, true);
            ra.setName("radioA");
            Checkbox rb = new Checkbox("Back", group, false);
            rb.setName("radioB");
            ra.addItemListener(e -> radioEvents.incrementAndGet());
            rb.addItemListener(e -> radioEvents.incrementAndGet());
            p.add(ra);
            p.add(rb);
            Button off = new Button("Off");
            off.setName("disabledScan");
            off.setEnabled(false);
            p.add(off);
            JButton o = new JButton("Open deposit box");
            o.setName("openDepositBox");
            o.addActionListener(e -> outsideClicks.incrementAndGet());
            content.add(p, java.awt.BorderLayout.CENTER);
            content.add(o, java.awt.BorderLayout.SOUTH);
            f.setContentPane(content);
            // beside the test frame (0, 0, 640, 480), which is ranked above other frames and would cover the points
            f.setBounds(660, 500, 360, 200);
            f.setVisible(true);
            frame = f;
            scanner = p;
            reread = j;
            scan = b;
            lid = c;
            pad = cv;
            outside = o;
            radioA = ra;
            radioB = rb;
            disabled = off;
            return null;
        });
        await("scanner panel showing", WAIT_MS, () -> scan.isShowing() && reread.isShowing() && outside.isShowing());
        settle();
    }

    @AfterAll
    static void closeFixture() throws Exception {
        if (frame != null) {
            onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }

    @AfterEach
    void closeDialogs() throws Exception {
        onEdt(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof Dialog && w.isDisplayable()) {
                    w.dispose();
                }
            }
            return null;
        });
        settle();
    }

    private static Map<String, Object> windowPoint(java.awt.Component c) throws Exception {
        Point p = onEdt(() -> HitTest.centre(c));
        return map("x", (long) p.x, "y", (long) p.y);
    }

    private static Map<String, Object> clickAt(java.awt.Component c, boolean byPoint) throws Exception {
        Map<String, Object> r = byPoint
                ? call("swing.pointer", "point", windowPoint(c), "space", map("window", ref(frame)), "action", "CLICK")
                : call("swing.pointer", "target", ref(c), "action", "CLICK");
        settle();
        assertEquals(Boolean.TRUE, r.get("fired"), String.valueOf(r));
        return r;
    }

    @Test
    void aRoutedClickReachesEveryComponentInsideTheHeavyweightPanel() throws Exception {
        for (boolean byPoint : new boolean[] {false, true}) {
            int r0 = rereads.get();
            Map<String, Object> r = clickAt(reread, byPoint);
            assertEquals(r0 + 1, rereads.get(), "the lightweight inside the panel fired");
            assertEquals(ref(reread), r.get("received"));
            assertEquals(ref(scanner), r.get("source"), "sourced from the panel");
            assertEquals(ref(frame), r.get("window"), "window stays the window");

            int s0 = scans.size();
            r = clickAt(scan, byPoint);
            assertEquals(s0 + 1, scans.size(), "the AWT button fired through its peer (X11): " + scans);
            assertEquals(ref(scan), r.get("received"));
            assertEquals(ref(scan), r.get("source"), "a heavyweight leaf is its own source");

            int l0 = lidChanges.size();
            boolean state = onEdt(() -> lid.getState());
            r = clickAt(lid, byPoint);
            assertEquals(l0 + 1, lidChanges.size(), "one item change");
            assertEquals(!state, onEdt(() -> lid.getState()));
            assertEquals(ref(lid), r.get("received"));

            int p0 = padClicks.get();
            r = clickAt(pad, byPoint);
            assertEquals(p0 + 1, padClicks.get(), "the listening canvas got the click");
            assertEquals(ref(pad), r.get("received"));
        }
        Map<String, Object> hit = call("swing.hitTest", "point", windowPoint(reread), "space", map("window",
                ref(frame)));
        assertEquals(ref(scanner), hit.get("source"));
        assertEquals(ref(reread), obj(hit.get("mouseTarget")).get("ref"), "searched below the heavyweight");
        assertEquals(ref(reread), hit.get("target"));
        Map<String, Object> leaf = call("swing.hitTest", "point", windowPoint(scan), "space", map("window",
                ref(frame)));
        assertEquals(ref(scan), leaf.get("source"));
        assertEquals(ref(scan), leaf.get("target"));
        assertEquals("HEAVYWEIGHT", leaf.get("surface"), "the recorder keeps anchoring such hits");
    }

    @Test
    void aSwingButtonOutsideThePanelIsRoutedFromTheWindowAndASourceChangeExitsTheOldSource() throws Exception {
        int o0 = outsideClicks.get();
        Map<String, Object> r = clickAt(outside, false);
        assertEquals(o0 + 1, outsideClicks.get());
        assertEquals(ref(frame), r.get("source"));
        Map<String, Object> again = clickAt(outside, false);
        assertEquals(3L, obj(again.get("dispatched")).get("of"), "at the last routed point: press, release, click");
        int s0 = scans.size();
        Map<String, Object> move = call("swing.pointer", "target", ref(scan), "action", "MOVE");
        assertEquals(ref(scan), move.get("source"));
        Map<String, Object> back = call("swing.pointer", "target", ref(outside), "action", "CLICK");
        settle();
        assertEquals(Boolean.TRUE, back.get("fired"), String.valueOf(back));
        Map<String, Object> dispatched = obj(back.get("dispatched"));
        assertEquals(4L, dispatched.get("of"), "one larger: the exit to the AWT button first: " + back);
        assertEquals(s0, scans.size(), "the hover on the AWT button fired nothing");
        assertEquals(o0 + 3, outsideClicks.get());
    }

    @Test
    void aCancelledPressIntoTheAwtButtonFiresNothing() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final MouseAdapter blocker = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        onEdt(() -> {
            scan.addMouseListener(blocker);
            return null;
        });
        int s0 = scans.size();
        Map<String, Object> r;
        try {
            r = ctx.call("swing.pointer", map("target", ref(scan), "action", "CLICK"), 4000);
        } finally {
            release.countDown();
        }
        assertEquals(Boolean.FALSE, r.get("fired"), String.valueOf(r));
        assertEquals("EDT_BUSY", r.get("reason"));
        assertEquals(Boolean.TRUE, r.get("balanced"));
        settle();
        onEdt(() -> {
            scan.removeMouseListener(blocker);
            return null;
        });
        assertEquals(s0, scans.size(), "the exit-then-release balance disarmed the peer: no action");
    }

    @Test
    void aModalDialogStillBlocksThePanel() throws Exception {
        final JDialog[] d = new JDialog[1];
        onEdt(() -> {
            JDialog dialog = new JDialog(frame, "Lid check", true);
            dialog.setBounds(660, 300, 200, 80);
            d[0] = dialog;
            javax.swing.SwingUtilities.invokeLater(() -> dialog.setVisible(true));
            return null;
        });
        await("modal dialog showing", WAIT_MS, () -> d[0].isShowing());
        int s0 = scans.size();
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.pointer", "target", ref(scan), "action", "CLICK"));
        settle();
        assertEquals(s0, scans.size());
    }

    @Test
    void aCanvasAClaimedSurfaceOwnsKeepsTheWindowAsSource() throws Exception {
        AutoCloseable reg = ctx.spi.register(SurfaceSource.class, new SurfaceSource() {
            @Override
            public List<Surface> surfaces() {
                return Collections.singletonList(new Surface(pad, "WEB", "b-test"));
            }
        });
        try {
            Map<String, Object> hit = call("swing.hitTest", "point", windowPoint(pad), "space", map("window",
                    ref(frame)));
            assertEquals(ref(frame), hit.get("source"), "page input stays on the web routes' checks");
            assertEquals("WEB", hit.get("surface"));
        } finally {
            reg.close();
        }
    }

    @Test
    void actionFiresTheAwtControlsSemanticallyOnceEach() throws Exception {
        final List<MouseEvent> mice = new CopyOnWriteArrayList<MouseEvent>();
        AWTEventListener spy = e -> {
            if (e.getSource() == scan && e instanceof MouseEvent) {
                mice.add((MouseEvent) e);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(spy, AWTEvent.MOUSE_EVENT_MASK);
        try {
            int s0 = scans.size();
            assertEquals(Boolean.TRUE, call("swing.click", "by", map("id", "scanButton")).get("fired"));
            settle();
            assertEquals(s0 + 1, scans.size(), "exactly one ActionEvent");
            assertEquals("Scan", scans.get(scans.size() - 1), "its command is the label");
            assertTrue(mice.isEmpty(), "no mouse event was delivered to it: " + mice);
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(spy);
        }
        int l0 = lidChanges.size();
        boolean state = onEdt(() -> lid.getState());
        call("swing.click", "by", map("id", "lidOpen"), "rung", "ACTION");
        settle();
        assertEquals(!state, onEdt(() -> lid.getState()));
        call("swing.click", "by", map("id", "lidOpen"));
        settle();
        assertEquals(state, onEdt(() -> lid.getState()), "twice back");
        assertEquals(l0 + 2, lidChanges.size(), "one ItemEvent per click");
        assertEquals(state ? java.awt.event.ItemEvent.SELECTED : java.awt.event.ItemEvent.DESELECTED,
                (int) lidChanges.get(lidChanges.size() - 1));

        int e0 = radioEvents.get();
        call("swing.click", "by", map("id", "radioA"));
        settle();
        assertTrue(onEdt(() -> radioA.getState()), "a selected radio stays selected");
        assertEquals(e0, radioEvents.get(), "and sends no event");
        call("swing.click", "by", map("id", "radioB"));
        settle();
        assertTrue(onEdt(() -> radioB.getState() && !radioA.getState()), "the group moves to the other radio");
        assertEquals(e0 + 1, radioEvents.get());

        RpcException off = expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.click", "by",
                map("id", "disabledScan")));
        assertEquals("disabled", off.error().data().get("reason"));

        Map<String, Object> dispatch = call("swing.click", "by", map("id", "scanButton"), "rung", "DISPATCH");
        assertEquals(Boolean.TRUE, dispatch.get("fired"), "DISPATCH is reported (its effect is toolkit-dependent)");
        settle();
    }

    @Test
    void theDialogReaderListsAndPressesAnAwtDialogsButtons() throws Exception {
        final List<String> answered = new CopyOnWriteArrayList<String>();
        final Dialog[] d = new Dialog[1];
        onEdt(() -> {
            Dialog dialog = new Dialog((Frame) frame, "Scanner jammed", false);
            dialog.setLayout(new FlowLayout());
            Button retry = new Button("Retry");
            retry.addActionListener(e -> answered.add(e.getActionCommand()));
            Button skip = new Button("Skip");
            skip.addActionListener(e -> answered.add(e.getActionCommand()));
            dialog.add(retry);
            dialog.add(skip);
            dialog.setBounds(660, 300, 220, 90);
            dialog.setVisible(true);
            d[0] = dialog;
            return null;
        });
        await("AWT dialog showing", WAIT_MS, () -> d[0].isShowing());
        settle();
        Map<String, Object> listed = null;
        for (Object o : arr(call("swing.dialogs").get("dialogs"))) {
            if (ref(d[0]).equals(obj(o).get("ref"))) {
                listed = obj(o);
            }
        }
        assertEquals(2, arr(listed.get("buttons")).size(), "AWT buttons are buttons: " + listed);
        assertEquals("Retry", obj(arr(listed.get("buttons")).get(0)).get("text"));
        Map<String, Object> r = call("swing.dismiss", "dialog", ref(d[0]), "button", "Skip");
        assertEquals("Skip", r.get("button"));
        await("the button's action ran", WAIT_MS, () -> answered.contains("Skip"));
        assertEquals(1, answered.size(), String.valueOf(answered));
    }

    /** Last (by name order is not guaranteed, so it restores the test frame's focus itself). */
    @Test
    void theComponentRungFocusesThenFiresTheAwtControlsSemantically() throws Exception {
        try {
            int s0 = scans.size();
            Map<String, Object> r = call("swing.click", "by", map("id", "scanButton"), "rung", "COMPONENT",
                    "activateWindow", true, "focusTimeoutMs", 20000L);
            settle();
            assertEquals(Boolean.TRUE, r.get("fired"), String.valueOf(r));
            assertEquals(s0 + 1, scans.size(), "one ActionEvent after the focus arrived");
            int l0 = lidChanges.size();
            r = call("swing.click", "by", map("id", "lidOpen"), "rung", "COMPONENT", "activateWindow", true,
                    "focusTimeoutMs", 20000L);
            settle();
            assertEquals(Boolean.TRUE, r.get("fired"), String.valueOf(r));
            assertEquals(l0 + 1, lidChanges.size());
        } finally {
            activate("the test frame is the focused window again", ui.frame);
            settle();
        }
        assertFalse(scans.isEmpty());
    }
}
