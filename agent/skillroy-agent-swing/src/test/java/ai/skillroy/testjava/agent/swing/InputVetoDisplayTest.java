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
import ai.skillroy.testjava.agent.api.spi.InputVeto;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input veto on a real display, the agent-level half of phase8-contracts.md 13.2 item 2 (and the readers of item
 * 4): a frame of its own with a vetoed panel ({@code closeMenu}: a button that is the root pane's default button, a
 * custom key an {@link ActionFirer} accepts, a text field), allowed siblings, and a dialog the veto names. Every route
 * that resolves a target (R1-R4, R6-R13; the Robot route is {@code RobotPathDisplayTest}'s) is refused
 * {@code INPUT_REJECTED vetoed}, {@code force} or not, with the data of rule 5; the firer never fires; a focused
 * vetoed button gets no SPACE from a target-less {@code swing.key}; ENTER's default button is asked; readers flag the
 * components; the in-task re-ask withholds a click a veto switched on between the read and the task (one warning,
 * {@code vetoWithheld} 1); the routed pointer's per-event check refuses a release the press made vetoed.
 */
class InputVetoDisplayTest extends DisplayTestBase {

    static final String TEXT = "closes the desk (the application would end)";

    static JFrame frame;
    static JPanel closeMenu;
    static JButton closeDesk;
    static Key closeKey;
    static JTextField closeNote;
    static JButton fallbackOnly;
    static JButton printTestSlip;
    static JButton pressArms;
    static JTextField toolsNote;
    static final AtomicInteger closeRequests = new AtomicInteger();
    static final AtomicInteger testSlips = new AtomicInteger();
    static final AtomicInteger pressArmsFired = new AtomicInteger();
    static final AtomicInteger fallbackFired = new AtomicInteger();
    static final AtomicBoolean pressed = new AtomicBoolean();
    static TestVeto veto;
    static CountingFirer keyFirer;
    static AutoCloseable vetoRegistration;
    static AutoCloseable firerRegistration2;

    /** A custom key (no button): only an {@link ActionFirer} fires it. */
    static final class Key extends JComponent {
        private static final long serialVersionUID = 1L;

        Key() {
            setName("closeKey");
            setPreferredSize(new java.awt.Dimension(60, 30));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    closeRequests.incrementAndGet();
                }
            });
        }
    }

    /** Fires {@link Key}s; counts every fire. */
    static final class CountingFirer implements ActionFirer {
        final AtomicInteger fired = new AtomicInteger();

        @Override
        public boolean canFire(Component c) {
            return c instanceof Key;
        }

        @Override
        public void fire(Component c) {
            fired.incrementAndGet();
            closeRequests.incrementAndGet();
        }
    }

    /**
     * Refuses by name: {@code closeMenu} and the dialog {@code vetoedDialog} on every route; {@code fallbackOnly} on
     * ACTION and ANY only; {@code printTestSlip} on ACTION from its second ask (the in-task re-ask) while
     * {@code armTask}; {@code pressArms} on the routed pointer once it was pressed.
     */
    static final class TestVeto implements InputVeto {
        final Set<String> always = Collections.synchronizedSet(new HashSet<String>());
        final AtomicBoolean armTask = new AtomicBoolean();
        final AtomicInteger slipAsks = new AtomicInteger();

        @Override
        public String refuse(Component c, String route) {
            String n = c.getName();
            if (n == null) {
                return null;
            }
            if (always.contains(n)) {
                return TEXT;
            }
            if ("fallbackOnly".equals(n) && (CLICK_ACTION.equals(route) || ANY.equals(route))) {
                return "no action rung for this one";
            }
            if ("printTestSlip".equals(n) && CLICK_ACTION.equals(route) && armTask.get()
                    && slipAsks.incrementAndGet() >= 2) {
                return "switched on between the read and the task";
            }
            if ("pressArms".equals(n) && (POINTER_ROUTED.equals(route) || ANY.equals(route)) && pressed.get()) {
                return "the press armed it";
            }
            return null;
        }
    }

    @BeforeAll
    static void showFixture() throws Exception {
        veto = new TestVeto();
        veto.always.add("closeMenu");
        veto.always.add("vetoedDialog");
        vetoRegistration = ctx.spi.register(InputVeto.class, veto);
        keyFirer = new CountingFirer();
        firerRegistration2 = ctx.spi.register(ActionFirer.class, keyFirer);
        onEdt(() -> {
            JFrame f = new JFrame("Veto fixture");
            f.setName("vetoFixture");
            f.setAutoRequestFocus(false);
            JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JPanel menu = new JPanel(new FlowLayout(FlowLayout.LEFT));
            menu.setName("closeMenu");
            menu.setBorder(BorderFactory.createTitledBorder("Close"));
            JButton close = new JButton("Close the desk");
            close.setName("closeDesk");
            close.addActionListener(e -> closeRequests.incrementAndGet());
            Key key = new Key();
            JTextField note = new JTextField("note", 8);
            note.setName("closeNote");
            menu.add(close);
            menu.add(key);
            menu.add(note);
            JButton fallback = new JButton("Fall back");
            fallback.setName("fallbackOnly");
            fallback.setFocusable(false);
            fallback.addActionListener(e -> fallbackFired.incrementAndGet());
            JButton slip = new JButton("Print test slip");
            slip.setName("printTestSlip");
            slip.addActionListener(e -> testSlips.incrementAndGet());
            JButton arms = new JButton("Press arms");
            arms.setName("pressArms");
            arms.addActionListener(e -> pressArmsFired.incrementAndGet());
            arms.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    pressed.set(true);
                }
            });
            JTextField tools = new JTextField(10);
            tools.setName("toolsNote");
            content.add(menu);
            content.add(fallback);
            content.add(slip);
            content.add(arms);
            content.add(tools);
            f.setContentPane(content);
            f.getRootPane().setDefaultButton(close);
            f.setBounds(420, 300, 560, 260);
            f.setVisible(true);
            frame = f;
            closeMenu = menu;
            closeDesk = close;
            closeKey = key;
            closeNote = note;
            fallbackOnly = fallback;
            printTestSlip = slip;
            pressArms = arms;
            toolsNote = tools;
            return null;
        });
        await("veto fixture showing", WAIT_MS, () -> printTestSlip.isShowing() && closeDesk.isShowing());
        settle();
    }

    @AfterAll
    static void closeFixture() throws Exception {
        if (frame == null) {
            return;
        }
        onEdt(() -> {
            frame.dispose();
            return null;
        });
        vetoRegistration.close();
        firerRegistration2.close();
    }

    @AfterEach
    void closeDialogs() throws Exception {
        onEdt(() -> {
            for (Window w : Window.getWindows()) {
                if (w instanceof JDialog && w.isDisplayable()) {
                    w.dispose();
                }
            }
            return null;
        });
        settle();
    }

    private static RpcException vetoed(String method, String route, Object... kv) {
        RpcException e = expectError(ErrorCodes.INPUT_REJECTED, () -> call(method, kv));
        Map<String, Object> data = e.error().data();
        assertEquals("vetoed", data.get("reason"), method + " " + data);
        assertEquals(route, data.get("route"), method + " " + data);
        assertTrue(e.getMessage().contains("takes no input from the agent (" + route + ")"), e.getMessage());
        return e;
    }

    private static Map<String, Object> windowPoint(Component c) throws Exception {
        Point p = onEdt(() -> HitTest.centre(c));
        return map("x", (long) p.x, "y", (long) p.y);
    }

    @Test
    void everyClickRungIsRefusedForceOrNotAndTheFirerNeverFires() throws Exception {
        int before = closeRequests.get();
        for (boolean force : new boolean[] {false, true}) {
            for (String rung : new String[] {"ACTION", "AUTO", "DISPATCH"}) {
                String route = "DISPATCH".equals(rung) ? InputVeto.CLICK_DISPATCH : InputVeto.CLICK_ACTION;
                RpcException e = vetoed("swing.click", route, "target", ref(closeDesk), "rung", rung, "force",
                        force);
                assertEquals(TEXT, e.error().data().get("veto"));
                assertEquals(ref(closeDesk), e.error().data().get("ref"));
                assertEquals(ref(closeMenu), e.error().data().get("vetoed"), "the ancestor rule names the panel");
                vetoed("swing.click", route, "by", map("name", "closeKey"), "rung", rung, "force", force);
            }
            vetoed("swing.click", InputVeto.CLICK_COMPONENT, "target", ref(closeDesk), "rung", "COMPONENT",
                    "activateWindow", true, "focusTimeoutMs", 2000L, "force", force);
            vetoed("swing.click", InputVeto.CLICK_COMPONENT, "target", ref(closeDesk), "rung", "COMPONENT",
                    "fallback", "ACTION", "force", force);
        }
        settle();
        assertFalse(onEdt(() -> frame.isFocused()), "COMPONENT is refused before the window activation");
        assertEquals(0, keyFirer.fired.get(), "the ActionFirer that accepts the key never fires");
        assertEquals(before, closeRequests.get(), "nothing reached the close controls");
    }

    @Test
    void theFallBackToActionAsksTheVetoAgain() throws Exception {
        vetoed("swing.click", InputVeto.CLICK_ACTION, "target", ref(fallbackOnly), "rung", "COMPONENT", "fallback",
                "ACTION", "focusTimeoutMs", 300L);
        settle();
        assertEquals(0, fallbackFired.get(), "the fall-back's read refuses (R3)");
    }

    @Test
    void typeAndKeyAreRefusedOnEveryTarget() throws Exception {
        int before = closeRequests.get();
        vetoed("swing.type", InputVeto.TYPE_SET, "target", ref(closeNote), "text", "x");
        vetoed("swing.type", InputVeto.TYPE_DISPATCH, "target", ref(closeNote), "text", "x", "mode", "DISPATCH",
                "force", true);
        vetoed("swing.key", InputVeto.KEY, "target", ref(closeDesk), "key", "SPACE");
        vetoed("swing.key", InputVeto.KEY, "target", ref(closeDesk), "key", "ENTER", "force", true);
        settle();
        assertEquals("note", onEdt(() -> closeNote.getText()), "nothing was typed");
        assertEquals(before, closeRequests.get());
    }

    @Test
    void enterIsRefusedWhenItWouldPressAVetoedDefaultButton() throws Exception {
        int before = closeRequests.get();
        RpcException key = vetoed("swing.key", InputVeto.KEY, "target", ref(toolsNote), "key", "ENTER");
        assertEquals(ref(toolsNote), key.error().data().get("ref"));
        assertEquals(ref(closeMenu), key.error().data().get("vetoed"));
        assertEquals(ref(closeDesk), key.error().data().get("defaultButton"));
        RpcException type = vetoed("swing.type", InputVeto.TYPE_SET, "target", ref(toolsNote), "text", "x",
                "enter", true);
        assertEquals(ref(closeDesk), type.error().data().get("defaultButton"));
        RpcException ctrl = vetoed("swing.key", InputVeto.KEY, "target", ref(toolsNote), "key", "ENTER",
                "modifiers", java.util.Arrays.asList("CTRL"));
        assertEquals(ref(closeDesk), ctrl.error().data().get("defaultButton"), "ctrl ENTER presses it too");
        call("swing.type", "target", ref(toolsNote), "text", "typed");
        settle();
        assertEquals("typed", onEdt(() -> toolsNote.getText()), "typing without ENTER goes in");
        assertEquals(before, closeRequests.get());
    }

    @Test
    void aFocusedVetoedButtonGetsNoKeyWithoutATarget() throws Exception {
        int before = closeRequests.get();
        try {
            activate("the veto fixture is the focused window", frame);
            onEdt(() -> closeDesk.requestFocusInWindow());
            await("the vetoed button owns the focus", WAIT_MS, () -> closeDesk.isFocusOwner());
            RpcException e = vetoed("swing.key", InputVeto.KEY, "key", "SPACE");
            assertEquals(ref(closeDesk), e.error().data().get("ref"), "the default target is the focus owner");
            vetoed("swing.type", InputVeto.TYPE_DISPATCH, "text", " ", "mode", "DISPATCH");
            settle();
            assertEquals(before, closeRequests.get(), "a focused vetoed key takes SPACE like a click: refused");
        } finally {
            activate("the test frame is the focused window again", ui.frame);
            settle();
        }
    }

    @Test
    void thePointerIsRefusedByComponentByPointAndForADragEndingOnAVetoedComponent() throws Exception {
        int before = closeRequests.get();
        RpcException byComponent = vetoed("swing.pointer", InputVeto.POINTER_ROUTED, "target", ref(closeDesk),
                "action", "CLICK", "force", true);
        Map<String, Object> hit = obj(byComponent.error().data().get("hit"));
        assertEquals(Boolean.TRUE, hit.get("vetoed"), String.valueOf(hit));
        assertEquals(ref(closeDesk), byComponent.error().data().get("ref"));
        vetoed("swing.pointer", InputVeto.POINTER_ROUTED, "point", windowPoint(closeDesk), "space",
                map("window", ref(frame)), "action", "CLICK");
        vetoed("swing.pointer", InputVeto.POINTER_ROUTED, "point", windowPoint(printTestSlip), "space",
                map("window", ref(frame)), "to", windowPoint(closeDesk), "action", "DRAG");
        vetoed("swing.pointer", InputVeto.POINTER_ROUTED, "target", ref(closeKey), "action", "DOUBLE_CLICK");
        settle();
        assertEquals(before, closeRequests.get());
        assertEquals(0, testSlips.get(), "the drag's press on the allowed button was never sent");
    }

    @Test
    void dismissIsRefusedForAVetoedDialogsButtonAndClose() throws Exception {
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger pressedClose = new AtomicInteger();
        final JDialog[] d = new JDialog[1];
        onEdt(() -> {
            JDialog dialog = new JDialog(frame, "Close the desk?", false);
            dialog.setName("vetoedDialog");
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    closed.incrementAndGet();
                }
            });
            JButton yes = new JButton("Close");
            yes.addActionListener(e -> pressedClose.incrementAndGet());
            dialog.getContentPane().add(yes);
            dialog.setBounds(460, 340, 200, 100);
            dialog.setVisible(true);
            d[0] = dialog;
            return null;
        });
        await("the vetoed dialog shows", WAIT_MS, () -> d[0].isShowing());
        settle();
        vetoed("swing.dismiss", InputVeto.DISMISS_BUTTON, "dialog", ref(d[0]), "button", "Close");
        vetoed("swing.dismiss", InputVeto.DISMISS_CLOSE, "dialog", ref(d[0]), "close", true);
        Map<String, Object> listed = null;
        for (Object o : arr(call("swing.dialogs").get("dialogs"))) {
            if (ref(d[0]).equals(obj(o).get("ref"))) {
                listed = obj(o);
            }
        }
        assertEquals(Boolean.TRUE, obj(arr(listed.get("buttons")).get(0)).get("vetoed"), String.valueOf(listed));
        settle();
        assertEquals(0, closed.get());
        assertEquals(0, pressedClose.get());
    }

    @Test
    void readersFlagVetoedComponentsAndCaptureOffersThemNoMark() throws Exception {
        Map<String, Object> root = obj(arr(call("swing.snapshot", "root", ref(frame)).get("windows")).get(0));
        assertEquals(Boolean.TRUE, SwingDisplayTest.node(root, n -> ref(closeMenu).equals(n.get("ref")))
                .get("vetoed"));
        assertEquals(Boolean.TRUE, SwingDisplayTest.node(root, n -> ref(closeDesk).equals(n.get("ref")))
                .get("vetoed"));
        assertEquals(Boolean.TRUE, SwingDisplayTest.node(root, n -> ref(closeKey).equals(n.get("ref")))
                .get("vetoed"));
        assertFalse(SwingDisplayTest.node(root, n -> ref(printTestSlip).equals(n.get("ref"))).containsKey("vetoed"));
        Map<String, Object> found = obj(arr(call("swing.find", "by", map("id", "closeDesk")).get("matches")).get(0));
        assertEquals(Boolean.TRUE, found.get("vetoed"));
        assertEquals(Boolean.TRUE, call("swing.hitTest", "target", ref(closeDesk)).get("vetoed"));
        assertEquals(Boolean.TRUE, call("swing.hitTest", "point", windowPoint(closeDesk), "space",
                map("window", ref(frame))).get("vetoed"));
        assertEquals(Boolean.FALSE, call("swing.hitTest", "target", ref(printTestSlip)).get("vetoed"));
        Map<String, Object> shot = call("swing.capture", "target", ref(frame), "annotateRefs", true);
        List<Object> marked = new ArrayList<Object>();
        for (Object m : arr(shot.get("marks"))) {
            marked.add(obj(m).get("ref"));
        }
        assertFalse(marked.contains(ref(closeDesk)), "no mark for a vetoed button: " + marked);
        assertFalse(marked.contains(ref(closeKey)), "no mark for a vetoed key: " + marked);
        assertTrue(marked.contains(ref(printTestSlip)), "an allowed sibling is marked: " + marked);
    }

    @Test
    void anAllowedSiblingIsClickedAndTheInTaskReAskWithholdsAVetoSwitchedOnMeanwhile() throws Exception {
        int slips = testSlips.get();
        call("swing.click", "target", ref(printTestSlip));
        settle();
        assertEquals(slips + 1, testSlips.get(), "an allowed sibling fires");
        long withheld = ((Number) module.info().get("vetoWithheld")).longValue();
        int warnings = countWithheldWarnings();
        veto.slipAsks.set(0);
        veto.armTask.set(true);
        try {
            Map<String, Object> r = call("swing.click", "target", ref(printTestSlip), "rung", "ACTION");
            assertEquals(Boolean.TRUE, r.get("fired"), "the call answered posted: " + r);
            settle();
        } finally {
            veto.armTask.set(false);
        }
        assertEquals(slips + 1, testSlips.get(), "the task re-asked and acted on nothing");
        assertEquals(withheld + 1, ((Number) module.info().get("vetoWithheld")).longValue());
        assertEquals(warnings + 1, countWithheldWarnings(), "one warning");
    }

    @Test
    void thePerEventCheckRefusesWhatThePressMadeVetoed() throws Exception {
        pressed.set(false);
        try {
            Map<String, Object> r = call("swing.pointer", "target", ref(pressArms), "action", "CLICK", "force", true);
            assertEquals(Boolean.FALSE, r.get("fired"), String.valueOf(r));
            assertEquals("VETOED", r.get("reason"));
            assertEquals(map("ref", ref(pressArms), "veto", "the press armed it", "route",
                    InputVeto.POINTER_ROUTED), r.get("vetoed"));
            Map<String, Object> dispatched = obj(r.get("dispatched"));
            long of = ((Number) dispatched.get("of")).longValue();
            assertEquals(of - 2, ((Number) dispatched.get("sent")).longValue(), "the release and the click withheld: "
                    + r);
            assertEquals(Boolean.TRUE, r.get("balanced"), "the press is balanced");
            settle();
            assertEquals(0, pressArmsFired.get(), "no action fired");
            assertFalse(onEdt(() -> pressArms.getModel().isPressed()));
        } finally {
            pressed.set(false);
        }
    }

    private static int countWithheldWarnings() {
        int n = 0;
        for (String w : ctx.log.warnings()) {
            if (w.contains("withheld inside the task")) {
                n++;
            }
        }
        return n;
    }

    @Test
    void theModuleInfoReportsTheCount() {
        assertTrue(module.info().get("vetoWithheld") instanceof Long, String.valueOf(module.info()));
        assertNull(new SwingModule().info().get("vetoWithheld"), "empty before install");
    }
}
