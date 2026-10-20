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
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.arr;
import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.fired;
import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/** Every primitive against a real frame on a display (Xvfb in the Linux container). */
class SwingDisplayTest extends DisplayTestBase {

    // ------------------------------------------------------------------------------------------------- windows

    @Test
    void windowsListsTheFrame() throws Exception {
        List<Object> windows = arr(call("swing.windows").get("windows"));
        Map<String, Object> w = only(windows, o -> TestUi.TITLE.equals(obj(o).get("title")));
        assertEquals(ref(ui.frame), w.get("ref"));
        assertEquals("frame", w.get("type"));
        assertEquals("JFrame", w.get("class"));
        assertEquals(TestUi.TITLE, w.get("name"), "a frame's accessible name is its title");
        assertEquals("mainFrame", w.get("id"), "a frame's id is its explicit getName()");
        assertEquals(Boolean.TRUE, w.get("visible"));
        assertEquals(Boolean.FALSE, w.get("modal"));
        assertTrue(w.containsKey("active") && w.containsKey("focused"));
        assertEquals(map("x", 0L, "y", 0L, "w", 640L, "h", 480L), w.get("bounds"));
        assertEquals(w.get("ref"), obj(arr(call("swing.windows").get("windows")).get(0)).get("ref"));
    }

    // ------------------------------------------------------------------------------------------------ snapshot

    @Test
    void snapshotShapeNamesTextsAndValues() throws Exception {
        Map<String, Object> snap = call("swing.snapshot");
        assertEquals(Boolean.FALSE, snap.get("truncated"));
        assertTrue(snap.get("seq") instanceof Long);
        List<Object> windows = arr(snap.get("windows"));
        assertEquals(1, windows.size(), "only showing windows");
        Map<String, Object> root = obj(windows.get(0));
        assertEquals("window", root.get("role"));
        assertEquals(map("x", 0L, "y", 0L, "w", 640L, "h", 480L), root.get("bounds"));

        Map<String, Object> ok = node(root, n -> ref(ui.ok).equals(n.get("ref")));
        assertEquals("button", ok.get("role"));
        assertEquals("OK", ok.get("text"));
        assertEquals("OK", ok.get("name"), "the accessible name (the text) wins over getName()");
        assertEquals("okButton", ok.get("id"), "the id is the explicit getName()");
        assertFalse(node(root, n -> "Cancel".equals(n.get("text"))).containsKey("id"), "no id without a name");
        assertEquals("JButton", ok.get("class"));
        assertEquals(Boolean.TRUE, ok.get("showing"));
        Map<String, Object> b = obj(ok.get("bounds"));
        assertTrue((Long) b.get("x") >= 0 && (Long) b.get("x") + (Long) b.get("w") <= 640, "window-relative " + b);

        Map<String, Object> pin = node(root, n -> "pin".equals(n.get("name")));
        assertEquals("password", pin.get("role"));
        assertNull(pin.get("text"), "password content never leaves the JVM");
        assertTrue(pin.containsKey("text"));
        assertEquals(Boolean.TRUE, pin.get("masked"), "A61: the built-in rule marks the node");

        assertEquals("textbox", node(root, n -> "entry".equals(n.get("name"))).get("role"));
        Map<String, Object> notes = node(root, n -> "notes".equals(n.get("name")));
        assertEquals("Read only notes", notes.get("text"));
        Map<String, Object> remember = node(root, n -> "Remember".equals(n.get("text")));
        assertEquals("checkbox", remember.get("role"));
        assertEquals(Boolean.FALSE, remember.get("selected"));
        Map<String, Object> colour = node(root, n -> "colour".equals(n.get("name")));
        assertEquals("combobox", colour.get("role"));
        assertEquals("Red", colour.get("text"));
        assertEquals(0L, colour.get("value"));
        assertEquals(list(), node(root, n -> "items".equals(n.get("name"))).get("value"));
        Map<String, Object> grid = obj(node(root, n -> "grid".equals(n.get("name"))).get("value"));
        assertEquals(2L, grid.get("rowCount"));
        assertEquals(list("Code", "Deposit"), grid.get("columns"));
        assertEquals(list(list("YRD-001", "3.50"), list("PNT-002", "4.10")), grid.get("cells"));
        assertEquals("Bold text", node(root, n -> "label".equals(n.get("role"))
                && "Bold text".equals(n.get("text"))).get("text"));
        Map<String, Object> due = node(root, n -> ref(ui.due).equals(n.get("ref")));
        assertEquals("DUE", due.get("name"), "NameEnricher names the icon-only key");
        assertNull(due.get("text"));
        Map<String, Object> nested = node(root, n -> "nested".equals(n.get("name")));
        assertEquals("panel", nested.get("role"));
        assertEquals(2, arr(nested.get("children")).size());
        assertNull(nodeOrNull(root, n -> "Hidden".equals(n.get("text"))), "hidden components are left out");
    }

    @Test
    void refsAreStableAcrossSnapshots() throws Exception {
        List<String> first = refs(obj(arr(call("swing.snapshot").get("windows")).get(0)), new ArrayList<String>());
        List<String> second = refs(obj(arr(call("swing.snapshot").get("windows")).get(0)), new ArrayList<String>());
        assertEquals(first, second);
        assertTrue(first.size() > 20);
        assertEquals(first.size(), new java.util.HashSet<String>(first).size(), "refs are unique");
    }

    @Test
    void snapshotLimitsRootsAndHidden() throws Exception {
        Map<String, Object> shallow = call("swing.snapshot", "maxDepth", 1L);
        assertEquals(Boolean.TRUE, shallow.get("truncated"));
        for (Object child : arr(obj(arr(shallow.get("windows")).get(0)).get("children"))) {
            assertFalse(obj(child).containsKey("children"), "depth 1 has no grandchildren");
        }
        Map<String, Object> few = call("swing.snapshot", "maxNodes", 3L);
        assertEquals(Boolean.TRUE, few.get("truncated"));
        assertEquals(3, refs(obj(arr(few.get("windows")).get(0)), new ArrayList<String>()).size());
        assertEquals(Boolean.FALSE, call("swing.snapshot", "maxDepth", 500L).get("truncated"), "clamped, not error");
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.snapshot", "maxNodes", 0L));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.snapshot", "maxDepth", -1L));

        Map<String, Object> sub = call("swing.snapshot", "root", ref(ui.nested));
        Map<String, Object> nested = obj(arr(sub.get("windows")).get(0));
        assertEquals(ref(ui.nested), nested.get("ref"));
        assertEquals(2, arr(nested.get("children")).size());

        Map<String, Object> all = call("swing.snapshot", "includeHidden", true);
        assertNotNull(nodeOrNull(obj(arr(all.get("windows")).get(0)), n -> "Hidden".equals(n.get("text"))));
        expectError(ErrorCodes.STALE_HANDLE, () -> call("swing.snapshot", "root", "h987654"));
    }

    /**
     * Phase 8 (phase8-contracts.md 3.2, 3.3, 3.4): a tree without a veto, a collapsed pane or a heavyweight container
     * answers as before: no node carries {@code vetoed} or {@code collapsed}, and the hit-test says {@code vetoed:
     * false} with the window as the routed {@code source}.
     */
    @Test
    void aTreeWithoutVetoesOrCollapsedPanesKeepsItsShape() throws Exception {
        Map<String, Object> root = obj(arr(call("swing.snapshot", "root", ref(ui.frame)).get("windows")).get(0));
        assertNull(nodeOrNull(root, n -> n.containsKey("vetoed") || n.containsKey("collapsed")),
                "no flag on a desk without vetoes or collapsed panes");
        Map<String, Object> hit = call("swing.hitTest", "target", ref(ui.ok));
        assertEquals(Boolean.FALSE, hit.get("vetoed"));
        assertEquals(ref(ui.frame), hit.get("source"));
        List<String> keys = new ArrayList<String>(hit.keySet());
        assertEquals(keys.indexOf("masked") + 1, keys.indexOf("vetoed"), "vetoed right after masked: " + keys);
        assertEquals(keys.indexOf("surface") + 1, keys.indexOf("source"), "source right after surface: " + keys);
    }

    @Test
    void snapshotSeqIsTheBusPosition() throws Exception {
        long seq = ctx.events.publish("trace.mark", "test", map("label", "x"));
        assertEquals(seq, call("swing.snapshot", "maxDepth", 0L).get("seq"));
    }

    // ---------------------------------------------------------------------------------------------------- find

    @Test
    void findStrategies() throws Exception {
        assertEquals(ref(ui.entry), match(call("swing.find", "by", map("name", "entry"))));
        assertEquals(ref(ui.entry), match(call("swing.find", "by", map("name", map("contains", "ntr")))));
        assertEquals(ref(ui.cancel), match(call("swing.find", "by", map("text", map("regex", "^Canc")))));
        assertEquals(ref(ui.pin), match(call("swing.find", "by", map("class", "JPasswordField"))));
        assertEquals(ref(ui.ok), match(call("swing.find", "by", map("ref", ref(ui.ok)))));
        assertEquals(ref(ui.due), match(call("swing.find", "by", map("action", "DUE"))));
        assertEquals(ref(ui.deposit), match(call("swing.find", "by", map("name", "DEPOSIT"))));
        assertEquals(ref(ui.inner), match(call("swing.find", "by", map("text", "Inner"), "root", ref(ui.nested))));
        assertEquals(ref(ui.entry), match(call("swing.find", "by", map("path", "0/1/0/0"))),
                "frame > root pane > layered pane > content pane > first child");
        assertEquals(ref(ui.inner), match(call("swing.find", "by", map("class", "JButton", "index", 4L))));
        Map<String, Object> m = obj(arr(call("swing.find", "by", map("name", "entry")).get("matches")).get(0));
        assertFalse(m.containsKey("children"), "matches carry no children");

        List<Object> buttons = arr(call("swing.find", "by", map("class", "JButton"), "all", true).get("matches"));
        assertTrue(buttons.size() >= 5, "all showing JButtons: " + buttons.size());
        assertEquals(list(), call("swing.find", "by", map("name", "nothing"), "all", true).get("matches"));
        assertEquals(1, arr(call("swing.find", "by", map("text", "Hidden"), "includeHidden", true)
                .get("matches")).size());
        expectError(ErrorCodes.NOT_FOUND, () -> call("swing.find", "by", map("text", "Hidden")));
        RpcException e = expectError(ErrorCodes.AMBIGUOUS, () -> call("swing.find", "by", map("class", "JButton")));
        assertEquals((long) buttons.size(), e.error().data().get("count"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.find", "by", map("bogus", "x")));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.find", "by", map("name", map("regex", "["))));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.find"));
        assertEquals(ref(ui.due), match(call("swing.find", "by", map("name", "DUE", "role", "button"))));
        expectError(ErrorCodes.NOT_FOUND, () -> call("swing.find", "by", map("name", "DUE", "role", "label")));
    }

    // --------------------------------------------------------------------------------------------------- click

    @Test
    void idLocatorFindsAndClicksByTheExplicitName() throws Exception {
        assertEquals(ref(ui.ok), match(call("swing.find", "by", map("id", "okButton"))));
        assertEquals(ref(ui.frame), match(call("swing.find", "by", map("id", "mainFrame"))));
        assertTrue(arr(call("swing.find", "by", map("name", "okButton"), "all", true).get("matches")).isEmpty(),
                "the accessible name hides getName() from {name}");
        expectError(ErrorCodes.NOT_FOUND, () -> call("swing.find", "by", map("id", "OK")));
        int before = ui.okClicks.get();
        assertEquals(fired("ACTION", "AUTO"), call("swing.click", "by", map("id", "okButton")));
        await("clicked by id", WAIT_MS, () -> ui.okClicks.get() == before + 1);
        call("swing.type", "target", map("id", "entry"), "text", "by id");
        settle();
        assertEquals("by id", onEdt(() -> ui.entry.getText()));
    }

    @Test
    void actionRungClicksButtonsWithoutFocus() throws Exception {
        int before = ui.okClicks.get();
        assertEquals(fired("ACTION", "AUTO"), call("swing.click", "target", ref(ui.ok)));
        await("OK clicked", WAIT_MS, () -> ui.okClicks.get() == before + 1);
        assertEquals(fired("ACTION", "AUTO"),
                call("swing.click", "by", map("text", "OK", "role", "button"), "rung", "AUTO"));
        await("OK clicked twice", WAIT_MS, () -> ui.okClicks.get() == before + 2);
        assertEquals(fired("ACTION", "ACTION"), call("swing.click", "target", map("text", "Inner"),
                "rung", "action"));
        await("Inner clicked", WAIT_MS, () -> ui.innerClicks.get() == 1);
    }

    @Test
    void actionRungUsesTheActionFirerForCustomWidgets() throws Exception {
        int delivered = ui.due.delivered.get();
        int fired = firer.fired.get();
        call("swing.click", "by", map("action", "DUE"));
        await("DUE delivered through the ActionFirer", WAIT_MS, () -> ui.due.delivered.get() == delivered + 1);
        assertEquals(fired + 1, firer.fired.get());
    }

    @Test
    void withoutTheFirerDoClickReachesAFocusGatedKeyButIsGated() throws Exception {
        firerRegistration.close();
        try {
            int presses = ui.deposit.presses.get();
            int delivered = ui.deposit.delivered.get();
            call("swing.click", "by", map("action", "DEPOSIT"));
            await("DEPOSIT pressed by doClick", WAIT_MS, () -> ui.deposit.presses.get() == presses + 1);
            settleAfterTimer();
            if (!ui.deposit.hasFocus()) {
                assertEquals(delivered, ui.deposit.delivered.get(), "a doClick on an unfocused gated key does nothing");
            }
        } finally {
            firerRegistration = ctx.spi.register(ai.skillroy.testjava.agent.api.spi.ActionFirer.class, firer);
        }
    }

    @Test
    void dispatchRungDeliversMouseEvents() throws Exception {
        int before = ui.cancelClicks.get();
        assertEquals(fired("DISPATCH", "DISPATCH"), call("swing.click", "target", ref(ui.cancel),
                "rung", "DISPATCH"));
        await("Cancel clicked by dispatched mouse events", WAIT_MS, () -> ui.cancelClicks.get() == before + 1);
        int presses = ui.due.presses.get();
        call("swing.click", "by", map("action", "DUE"), "rung", "DISPATCH");
        await("DUE pressed by dispatched mouse events", WAIT_MS, () -> ui.due.presses.get() == presses + 1);
    }

    @Test
    void componentRungReportsFocusFailedForAnUnfocusableButton() throws Exception {
        Map<String, Object> r = call("swing.click", "target", ref(ui.unfocusable), "rung", "COMPONENT");
        assertEquals("FOCUS_FAILED", r.get("rung"));
        assertEquals(Boolean.FALSE, r.get("fired"));
        assertTrue(r.get("reason") instanceof String);
        settle();
        assertEquals(0, ui.unfocusableClicks.get(), "nothing is clicked when focus fails");
    }

    @Test
    void componentRungIsHonestAboutFocus() throws Exception {
        int warnings = ctx.log.warnings().size();
        int before = ui.okClicks.get();
        Map<String, Object> r = call("swing.click", "target", ref(ui.ok), "rung", "COMPONENT",
                "focusTimeoutMs", 3000L);
        if (Boolean.TRUE.equals(r.get("fired"))) {
            assertEquals("COMPONENT", r.get("rung"));
            await("OK clicked by the component rung", WAIT_MS, () -> ui.okClicks.get() == before + 1);
        } else {
            assertEquals("FOCUS_FAILED", r.get("rung"));
            settle();
            assertEquals(before, ui.okClicks.get());
        }
        assertEquals(warnings, ctx.log.warnings().size(), "no posted action failed: " + ctx.log.warnings());
    }

    @Test
    void clickErrors() throws Exception {
        RpcException robot = expectError(ErrorCodes.CAPABILITY_UNAVAILABLE,
                () -> call("swing.click", "target", ref(ui.ok), "rung", "ROBOT"));
        assertEquals("opt-in rung, not implemented", robot.error().data().get("reason"));
        RpcException disabled = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.click", "target", ref(ui.disabled)));
        assertEquals("disabled", disabled.error().data().get("reason"));
        RpcException hidden = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.click", "target", ref(ui.hidden)));
        assertEquals("not showing", hidden.error().data().get("reason"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.click", "target", ref(ui.ok), "rung", "MAGIC"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.click", "target", ref(ui.ok),
                "by", map("text", "OK")));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.click"));
        expectError(ErrorCodes.STALE_HANDLE, () -> call("swing.click", "target", "h99999"));
        expectError(ErrorCodes.NOT_FOUND, () -> call("swing.click", "by", map("text", "Nope")));
        expectError(ErrorCodes.AMBIGUOUS, () -> call("swing.click", "by", map("class", "JButton")));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.click", "target", ref(ui.ok),
                "focusTimeoutMs", -1L));
    }

    // ---------------------------------------------------------------------------------------------------- type

    @Test
    void typeSetFiresTheFieldAction() throws Exception {
        int actions = ui.entryActions.get();
        assertEquals(map("mode", "SET", "typed", 5L), call("swing.type", "target", ref(ui.entry), "text", "hello",
                "enter", true));
        await("entry action", WAIT_MS, () -> ui.entryActions.get() == actions + 1);
        assertEquals("hello", ui.lastEntry.get());
        call("swing.type", "by", map("name", "entry"), "text", " world", "clear", false);
        settle();
        assertEquals("hello world", onEdt(() -> ui.entry.getText()));
        call("swing.type", "by", map("name", "pin"), "text", "9876");
        settle();
        assertEquals("9876", onEdt(() -> new String(ui.pin.getPassword())));
    }

    @Test
    void typeDispatchSendsKeyEventsWithoutFocus() throws Exception {
        int actions = ui.entryActions.get();
        assertEquals(map("mode", "DISPATCH", "typed", 6L), call("swing.type", "target", ref(ui.entry),
                "text", "Ab3 x!", "mode", "DISPATCH", "enter", true));
        await("entry action from a dispatched ENTER", WAIT_MS, () -> ui.entryActions.get() == actions + 1);
        assertEquals("Ab3 x!", ui.lastEntry.get());
        call("swing.type", "target", ref(ui.entry), "text", "?", "mode", "dispatch", "clear", false);
        settle();
        assertEquals("Ab3 x!?", onEdt(() -> ui.entry.getText()));
    }

    @Test
    void typeErrors() throws Exception {
        RpcException notText = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.type", "target", ref(ui.ok), "text", "x"));
        assertEquals("not a text component", notText.error().data().get("reason"));
        RpcException readOnly = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.type", "target", ref(ui.notes), "text", "x"));
        assertEquals("not editable", readOnly.error().data().get("reason"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.type", "target", ref(ui.entry)));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.type", "target", ref(ui.entry), "text", "x",
                "mode", "ROBOT"));
    }

    // ----------------------------------------------------------------------------------------------------- key

    @Test
    void keyReachesTheTargetWithoutFocus() throws Exception {
        call("swing.type", "target", ref(ui.entry), "text", "keyed");
        int actions = ui.entryActions.get();
        assertEquals(map("dispatchedTo", ref(ui.entry)), call("swing.key", "key", "ENTER", "target", ref(ui.entry)));
        await("ENTER fired the field action", WAIT_MS, () -> ui.entryActions.get() == actions + 1);
        assertEquals("keyed", ui.lastEntry.get());

        boolean selectAllBound = onEdt(() -> ui.entry.getInputMap().get(KeyStroke.getKeyStroke("ctrl A")) != null);
        if (selectAllBound) {
            call("swing.key", "key", "A", "modifiers", list("CTRL"), "by", map("name", "entry"));
            settle();
            assertEquals("keyed", onEdt(() -> ui.entry.getSelectedText()));
        }
    }

    @Test
    void keyWithoutTargetGoesToTheFocusOwnerOrTheWindow() throws Exception {
        String to = (String) call("swing.key", "key", "F2").get("dispatchedTo");
        assertNotNull(to);
        Object target = ctx.handles.resolve(to);
        assertTrue(target instanceof java.awt.Component);
        assertTrue(SwingUtilities.isDescendingFrom((java.awt.Component) target, ui.frame), "inside the only window");
    }

    @Test
    void keyErrors() throws Exception {
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.key", "key", "RETURN"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.key", "key", "ENTER", "modifiers", list("HYPER")));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.key"));
        RpcException e = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.key", "key", "ENTER", "target", ref(ui.unfocusable)));
        assertEquals("not focusable", e.error().data().get("reason"));
    }

    // ------------------------------------------------------------------------------------------------- capture

    @Test
    void captureReturnsADecodablePngOfTheRightSize() throws Exception {
        Map<String, Object> shot = call("swing.capture");
        assertEquals("png", shot.get("format"));
        // A61: a window is the root pane's size (640 x 480 only where the insets are 0, as on the WM-less display)
        final int rw = onEdt(() -> ui.frame.getRootPane().getWidth());
        final int rh = onEdt(() -> ui.frame.getRootPane().getHeight());
        assertEquals((long) rw, shot.get("width"));
        assertEquals((long) rh, shot.get("height"));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode((String) shot
                .get("base64"))));
        assertNotNull(img, "PNG decodes");
        assertEquals(rw, img.getWidth());
        assertEquals(rh, img.getHeight());
        assertTrue(distinctColours(img) > 2, "something was painted");

        int w = onEdt(() -> ui.ok.getWidth());
        int h = onEdt(() -> ui.ok.getHeight());
        Map<String, Object> button = call("swing.capture", "target", ref(ui.ok), "mode", "paint");
        assertEquals((long) w, button.get("width"));
        assertEquals((long) h, button.get("height"));
        // A61: mode ROBOT is the opt-in glass capture; without the agent option robot its capability is unavailable
        RpcException robot = expectError(ErrorCodes.CAPABILITY_UNAVAILABLE, () -> call("swing.capture", "mode",
                "ROBOT"));
        assertEquals("swing.capture.robot", robot.error().data().get("capability"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.capture", "target", ref(ui.hidden)));
    }

    @Test
    void captureScalesHugeComponentsDown() throws Exception {
        final JTextArea huge = onEdt(() -> {
            JTextArea a = new JTextArea("big");
            ui.nested.add(a);
            a.setBounds(0, 0, 5000, 100);
            return a;
        });
        try {
            Map<String, Object> shot = call("swing.capture", "target", ref(huge));
            assertEquals(4096L, shot.get("width"));
            assertEquals(81L, shot.get("height"), "aspect ratio kept: 100 * 4096 / 5000, rounded down");
        } finally {
            onEdt(() -> {
                ui.nested.remove(huge);
                return null;
            });
        }
    }

    private static int distinctColours(BufferedImage img) {
        java.util.Set<Integer> colours = new java.util.HashSet<Integer>();
        for (int y = 0; y < img.getHeight(); y += 7) {
            for (int x = 0; x < img.getWidth(); x += 7) {
                colours.add(img.getRGB(x, y));
            }
        }
        return colours.size();
    }

    // ------------------------------------------------------------------------------------------------ edtState

    @Test
    void edtStateWhenIdle() throws Exception {
        Map<String, Object> s = call("swing.edtState");
        assertEquals(Boolean.TRUE, s.get("dispatchThreadAlive"));
        assertEquals(Boolean.FALSE, s.get("edtBlocked"));
        assertEquals(list(), s.get("modalDialogs"));
        assertTrue((Long) s.get("windowCount") >= 1);
    }

    @Test
    void edtStateReportsABlockedEdtWithinItsBound() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch blocking = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            blocking.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(blocking.await(WAIT_MS, TimeUnit.MILLISECONDS));
            long start = System.nanoTime();
            Map<String, Object> s = call("swing.edtState");
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(Boolean.TRUE, s.get("edtBlocked"));
            assertEquals(Boolean.TRUE, s.get("dispatchThreadAlive"));
            assertTrue(ms < 2000, "bounded by its 500 ms budget plus scheduling: " + ms + " ms");
            assertTrue(s.containsKey("modalDialogs") && s.containsKey("windowCount"));
        } finally {
            release.countDown();
        }
        settle();
    }

    // -------------------------------------------------------------------------------------------------- settle

    @Test
    void settleWhenIdle() throws Exception {
        Map<String, Object> r = call("swing.settle");
        assertEquals(Boolean.TRUE, r.get("settled"));
        assertTrue(r.get("ms") instanceof Long);
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.settle", "timeoutMs", -1L));
    }

    @Test
    void settleWaitsForADialogThatOpensDuringTheQuietPeriod() throws Exception {
        final JDialog[] dialog = new JDialog[1];
        long since = ctx.events.lastSeq();
        SwingUtilities.invokeLater(() -> {
            Timer t = new Timer(200, e -> {
                JDialog d = new JDialog(ui.frame, "Late dialog", false);
                d.setBounds(700, 0, 200, 100);
                dialog[0] = d;
                d.setVisible(true);
            });
            t.setRepeats(false);
            t.start();
        });
        try {
            Map<String, Object> r = call("swing.settle", "quietMs", 2500L, "timeoutMs", 25000L);
            assertEquals(Boolean.TRUE, r.get("settled"));
            assertNotNull(dialog[0], "settle did not return before the dialog opened");
            assertTrue(onEdt(() -> dialog[0].isShowing()));
            List<Event> opened = events("window.opened", since);
            assertEquals(1, opened.size());
            assertEquals(ref(dialog[0]), opened.get(0).payload().get("ref"));
            assertTrue((Long) r.get("ms") >= 2500, "a full quiet period after the dialog: " + r);
        } finally {
            if (dialog[0] != null) {
                onEdt(() -> {
                    dialog[0].dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void settleTimesOutWhileWindowsKeepOpening() throws Exception {
        final Timer[] timer = new Timer[1];
        onEdt(() -> {
            timer[0] = new Timer(40, e -> {
                JWindow w = new JWindow(ui.frame);
                w.setBounds(700, 200, 50, 50);
                w.setVisible(true);
                w.dispose();
            });
            timer[0].start();
            return null;
        });
        try {
            Map<String, Object> r = call("swing.settle", "timeoutMs", 1500L, "quietMs", 400L);
            assertEquals(Boolean.FALSE, r.get("settled"));
            assertTrue((Long) r.get("ms") >= 1000, "ran to its bound: " + r);
        } finally {
            onEdt(() -> {
                timer[0].stop();
                return null;
            });
        }
        settle();
    }

    // -------------------------------------------------------------------------------------- events and handles

    @Test
    void windowEventsCarryRefTypeTitleModal() throws Exception {
        long since = ctx.events.lastSeq();
        final JDialog d = onEdt(() -> {
            JDialog dialog = new JDialog(ui.frame, "Events", false);
            dialog.setBounds(700, 300, 150, 80);
            dialog.setVisible(true);
            return dialog;
        });
        await("window.opened", WAIT_MS, () -> !events("window.opened", since).isEmpty());
        Event opened = events("window.opened", since).get(0);
        assertEquals("swing", opened.source());
        assertEquals(map("ref", ref(d), "type", "dialog", "title", "Events", "modal", false), opened.payload());
        onEdt(() -> {
            d.dispose();
            return null;
        });
        await("window.closed", WAIT_MS, () -> !events("window.closed", since).isEmpty());
        assertEquals(ref(d), events("window.closed", since).get(0).payload().get("ref"));
    }

    @Test
    void refBecomesStaleOnceTheComponentIsCollected() throws Exception {
        final java.lang.ref.WeakReference<?>[] button = new java.lang.ref.WeakReference<?>[1];
        String buttonRef = makeAndDisposeFrame("Short-lived", false, button);
        // JDK 21 keeps the most recently disposed frame's content reachable for a while (seen without the agent
        // too); later throwaway frames release the earlier ones
        makeAndDisposeFrame("Flush one", true, new java.lang.ref.WeakReference<?>[1]);
        makeAndDisposeFrame("Flush two", true, new java.lang.ref.WeakReference<?>[1]);
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            System.gc();
            if (button[0].get() == null) {
                // collected: the module kept no strong reference, and the ref is now stale everywhere
                expectError(ErrorCodes.STALE_HANDLE, () -> ctx.handles.resolve(buttonRef));
                expectError(ErrorCodes.STALE_HANDLE, () -> call("swing.click", "target", buttonRef));
                expectError(ErrorCodes.STALE_HANDLE, () -> call("swing.find", "by", map("ref", buttonRef)));
                return;
            }
            Thread.sleep(100);
        }
        abort("the disposed frame's button was not garbage-collected within 20 s; STALE_HANDLE not observable");
    }

    /** Shows a frame, finds its button through the module, then empties and disposes the frame. */
    private static String makeAndDisposeFrame(final String title, final boolean focusable,
                                              final java.lang.ref.WeakReference<?>[] button) throws Exception {
        onEdt(() -> {
            JFrame f = new JFrame(title);
            f.setFocusableWindowState(focusable);
            JButton b = new JButton(title + " button");
            b.setFocusable(focusable);
            f.getContentPane().add(b);
            f.setBounds(700, 400, 120, 80);
            f.setVisible(true);
            button[0] = new java.lang.ref.WeakReference<JButton>(b);
            return null;
        });
        await("short-lived frame showing", WAIT_MS, () -> button[0].get() != null
                && ((JButton) button[0].get()).isShowing());
        String ref = match(call("swing.find", "by", map("text", title + " button")));
        call("swing.snapshot");
        onEdt(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof JFrame && title.equals(((JFrame) w).getTitle())) {
                    ((JFrame) w).getContentPane().removeAll();
                    w.dispose();
                }
            }
            return null;
        });
        settle();
        return ref;
    }

    @Test
    void uninstallRemovesTheTap() throws Exception {
        FakeContext other = new FakeContext();
        SwingModule second = new SwingModule();
        try {
            second.install(other);
            second.onTargetReady(other);
            assertTrue(second.tapInstalled());
            assertTrue(tapCount() >= 2, "this test's module and the shared one");
            int before = tapCount();
            second.uninstall();
            assertFalse(second.tapInstalled());
            assertEquals(before - 1, tapCount());
            assertTrue(other.primitives.list().isEmpty());
        } finally {
            second.uninstall();
            other.shutdown();
        }
    }

    private static int tapCount() {
        int n = 0;
        for (java.awt.event.AWTEventListener l : java.awt.Toolkit.getDefaultToolkit().getAWTEventListeners()) {
            Object inner = l instanceof java.awt.event.AWTEventListenerProxy
                    ? ((java.awt.event.AWTEventListenerProxy) l).getListener() : l;
            if (inner instanceof WindowTap) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /** Lets a GatedKey's 60 ms timer fire before asserting on it. */
    private static void settleAfterTimer() throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            Timer t = new Timer(300, e -> done.countDown());
            t.setRepeats(false);
            t.start();
        });
        assertTrue(done.await(WAIT_MS, TimeUnit.MILLISECONDS));
        settle();
    }

    private static String match(Map<String, Object> findResult) {
        List<Object> matches = arr(findResult.get("matches"));
        assertEquals(1, matches.size(), "one match: " + matches);
        return (String) obj(matches.get(0)).get("ref");
    }

    private static Map<String, Object> only(List<Object> list, Predicate<Object> p) {
        Map<String, Object> found = null;
        for (Object o : list) {
            if (p.test(o)) {
                assertNull(found, "more than one match");
                found = obj(o);
            }
        }
        assertNotNull(found, "no match");
        return found;
    }

    static Map<String, Object> node(Map<String, Object> root, Predicate<Map<String, Object>> p) {
        Map<String, Object> n = nodeOrNull(root, p);
        assertNotNull(n, "node not found");
        return n;
    }

    static Map<String, Object> nodeOrNull(Map<String, Object> n, Predicate<Map<String, Object>> p) {
        if (p.test(n)) {
            return n;
        }
        if (n.containsKey("children")) {
            for (Object c : arr(n.get("children"))) {
                Map<String, Object> found = nodeOrNull(obj(c), p);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> refs(Map<String, Object> n, List<String> into) {
        into.add((String) n.get("ref"));
        if (n.containsKey("children")) {
            for (Object c : arr(n.get("children"))) {
                refs(obj(c), into);
            }
        }
        return into;
    }
}
