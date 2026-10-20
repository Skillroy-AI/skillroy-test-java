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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

import org.junit.jupiter.api.AfterAll;
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
 * The effective visible area on a real display, the agent-level half of phase8-contracts.md 13.2 item 6: a frame of
 * its own with a frame set (a visible pane and a zero-width pane, each with a "Print slip" button and the hidden one a
 * text field), a scroll pane whose button lies scrolled out of view, a list with a row scrolled out, and a window
 * placed partly off its owner. Every click rung, type, key and the target forms are refused
 * {@code INVALID_PARAMS collapsed} with {@code by}; locators are unique; snapshots prune and {@code includeHidden}
 * shows the copy flagged; a scrolled-out button is still clicked and matched; the pointer's component form refuses a
 * point its component does not show; a dialog's collapsed button is not found; and {@code onScreen} answers for a
 * showing window's children (the case the headless {@code VisibilityTest} cannot reach).
 */
class CollapsedPaneDisplayTest extends DisplayTestBase {

    static JFrame frame;
    static JPanel visiblePane;
    static JPanel collapsedPane;
    static JButton printSlipVisible;
    static JButton printSlipHidden;
    static JTextField hiddenField;
    static JScrollPane toolsScroll;
    static JButton reprintSlip;
    static JList<String> rows;
    static final AtomicInteger slipsVisible = new AtomicInteger();
    static final AtomicInteger slipsHidden = new AtomicInteger();
    static final AtomicInteger reprints = new AtomicInteger();

    @BeforeAll
    static void showFixture() throws Exception {
        onEdt(() -> {
            JFrame f = new JFrame("Collapsed panes");
            f.setAutoRequestFocus(false);
            JPanel content = new JPanel(new BorderLayout());
            JPanel frameSet = new JPanel(null);
            frameSet.setPreferredSize(new Dimension(360, 60));
            JPanel shown = new JPanel(null);
            shown.setName("visiblePane");
            shown.setBounds(0, 0, 180, 60);
            JPanel gone = new JPanel(null);
            gone.setName("collapsedPane");
            gone.setBounds(180, 0, 0, 60);
            JButton a = new JButton("Print slip");
            a.setName("printSlipVisible");
            a.setBounds(10, 10, 120, 30);
            a.addActionListener(e -> slipsVisible.incrementAndGet());
            JButton b = new JButton("Print slip");
            b.setName("printSlipHidden");
            b.setBounds(10, 10, 120, 30);
            b.addActionListener(e -> slipsHidden.incrementAndGet());
            JTextField field = new JTextField("hidden");
            field.setName("hiddenField");
            field.setBounds(10, 40, 100, 20);
            shown.add(a);
            gone.add(b);
            gone.add(field);
            frameSet.add(shown);
            frameSet.add(gone);
            JPanel tall = new JPanel(null);
            tall.setPreferredSize(new Dimension(200, 400));
            JButton reprint = new JButton("Reprint slip");
            reprint.setName("reprintSlip");
            reprint.setBounds(10, 300, 140, 30);
            reprint.addActionListener(e -> reprints.incrementAndGet());
            tall.add(reprint);
            JScrollPane scroll = new JScrollPane(tall);
            scroll.setName("toolsScroll");
            scroll.setPreferredSize(new Dimension(220, 120));
            DefaultListModel<String> model = new DefaultListModel<String>();
            for (int i = 0; i < 40; i++) {
                model.addElement("Loan line " + i);
            }
            JList<String> list = new JList<String>(model);
            list.setName("rows");
            JScrollPane listScroll = new JScrollPane(list);
            listScroll.setPreferredSize(new Dimension(160, 120));
            content.add(frameSet, BorderLayout.NORTH);
            content.add(scroll, BorderLayout.WEST);
            content.add(listScroll, BorderLayout.EAST);
            f.setContentPane(content);
            f.setBounds(380, 260, 420, 260);
            f.setVisible(true);
            frame = f;
            visiblePane = shown;
            collapsedPane = gone;
            printSlipVisible = a;
            printSlipHidden = b;
            hiddenField = field;
            toolsScroll = scroll;
            reprintSlip = reprint;
            rows = list;
            return null;
        });
        await("collapsed fixture showing", WAIT_MS, () -> printSlipVisible.isShowing() && reprintSlip.isShowing());
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

    private static void collapsed(Object... kv) {
        RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> call((String) kv[0], java.util.Arrays.copyOfRange(
                kv, 1, kv.length)));
        Map<String, Object> data = e.error().data();
        assertEquals("collapsed", data.get("reason"), kv[0] + " " + data);
        assertEquals(ref(collapsedPane), data.get("by"), kv[0] + " " + data);
        assertTrue(e.getMessage().contains("is collapsed:") && e.getMessage().contains("leaves it no visible area"),
                e.getMessage());
    }

    @Test
    void theHiddenCopyIsShowingButNotOnScreen() throws Exception {
        assertTrue(onEdt(() -> printSlipHidden.isShowing()), "AWT calls it showing");
        assertFalse(onEdt(() -> Visibility.onScreen(printSlipHidden)));
        assertTrue(onEdt(() -> Visibility.onScreen(printSlipVisible)));
        assertTrue(onEdt(() -> Visibility.onScreen(reprintSlip)), "scrolled out of view is still on screen");
        assertTrue(onEdt(() -> Visibility.onScreen(frame)), "a window keeps isShowing()");
    }

    @Test
    void everyInputRouteRefusesACollapsedComponent() throws Exception {
        for (String rung : new String[] {"ACTION", "AUTO", "COMPONENT", "DISPATCH"}) {
            collapsed("swing.click", "target", ref(printSlipHidden), "rung", rung, "force", true);
        }
        collapsed("swing.type", "target", ref(hiddenField), "text", "x");
        collapsed("swing.key", "target", ref(printSlipHidden), "key", "SPACE");
        collapsed("swing.pointer", "target", ref(printSlipHidden), "action", "CLICK");
        collapsed("swing.hitTest", "target", ref(printSlipHidden));
        collapsed("swing.capture", "target", ref(printSlipHidden));
        settle();
        assertEquals(0, slipsHidden.get());
        assertEquals("hidden", onEdt(() -> hiddenField.getText()));
    }

    @Test
    void locatorsMatchOnlyTheCopyOnScreenUnlessIncludeHidden() throws Exception {
        List<Object> matches = arr(call("swing.find", "by", map("text", "Print slip")).get("matches"));
        assertEquals(1, matches.size(), "unique with the rule: " + matches);
        assertEquals(ref(printSlipVisible), obj(matches.get(0)).get("ref"));
        List<Object> both = arr(call("swing.find", "by", map("text", "Print slip"), "all", true, "includeHidden",
                true).get("matches"));
        assertEquals(2, both.size());
        assertEquals(Boolean.TRUE, obj(both.get(1)).get("collapsed"), "the hidden copy is flagged: " + both);
        expectError(ErrorCodes.NOT_FOUND, () -> call("swing.find", "by", map("id", "printSlipHidden")));
        int before = slipsVisible.get();
        call("swing.click", "by", map("text", "Print slip"));
        settle();
        assertEquals(before + 1, slipsVisible.get());
        assertEquals(0, slipsHidden.get());
        List<Object> locators = arr(call("swing.hitTest", "target", ref(printSlipVisible)).get("locators"));
        assertTrue(locators.contains(map("text", "Print slip", "role", "button")),
                "the visible copy's {text, role} is unique now: " + locators);
    }

    @Test
    void snapshotsPruneCollapsedSubtreesUnlessIncludeHidden() throws Exception {
        Map<String, Object> root = obj(arr(call("swing.snapshot", "root", ref(frame)).get("windows")).get(0));
        assertNull(SwingDisplayTest.nodeOrNull(root, n -> ref(printSlipHidden).equals(n.get("ref"))),
                "the hidden copy is left out");
        assertNull(SwingDisplayTest.nodeOrNull(root, n -> ref(collapsedPane).equals(n.get("ref"))),
                "the zero-width pane is pruned with its subtree");
        SwingDisplayTest.node(root, n -> ref(visiblePane).equals(n.get("ref")));
        Map<String, Object> all = obj(arr(call("swing.snapshot", "root", ref(frame), "includeHidden", true)
                .get("windows")).get(0));
        assertEquals(Boolean.TRUE, SwingDisplayTest.node(all, n -> ref(collapsedPane).equals(n.get("ref")))
                .get("collapsed"));
        Map<String, Object> hidden = SwingDisplayTest.node(all, n -> ref(printSlipHidden).equals(n.get("ref")));
        assertEquals(Boolean.TRUE, hidden.get("collapsed"));
        assertFalse(SwingDisplayTest.node(all, n -> ref(printSlipVisible).equals(n.get("ref")))
                .containsKey("collapsed"));
    }

    @Test
    void aScrolledOutButtonIsClickedAndMatchedButThePointerRefusesThePointItDoesNotShow() throws Exception {
        int before = reprints.get();
        call("swing.click", "by", map("id", "reprintSlip"));
        settle();
        assertEquals(before + 1, reprints.get(), "ACTION reaches a component scrolled out of view");
        assertEquals(1, arr(call("swing.find", "by", map("id", "reprintSlip")).get("matches")).size());
        RpcException e = expectError(ErrorCodes.INPUT_REJECTED, () -> call("swing.pointer", "by",
                map("id", "reprintSlip"), "action", "CLICK"));
        assertEquals("not visible", e.error().data().get("reason"));
        assertEquals(ref(reprintSlip), e.error().data().get("ref"));
        assertEquals("at", e.error().data().get("param"));
        assertTrue(e.getMessage().contains("does not show the point"), e.getMessage());
        RpcException row = expectError(ErrorCodes.INPUT_REJECTED, () -> call("swing.pointer", "target", ref(rows),
                "row", 35L, "action", "CLICK"));
        assertEquals("not visible", row.error().data().get("reason"), "a row scrolled out of the list's view");
        Map<String, Object> shown = call("swing.pointer", "target", ref(rows), "row", 0L, "action", "CLICK");
        assertEquals(Boolean.TRUE, shown.get("fired"), "a row in view is clicked: " + shown);
        settle();
        assertEquals(before + 1, reprints.get(), "the refused pointer clicked nothing");
    }

    @Test
    void aDialogsCollapsedButtonIsNotFound() throws Exception {
        final JDialog[] d = new JDialog[1];
        final AtomicInteger pressed = new AtomicInteger();
        onEdt(() -> {
            JDialog dialog = new JDialog(frame, "Slip options", false);
            JPanel content = new JPanel(null);
            JButton ok = new JButton("Keep");
            ok.setBounds(10, 10, 80, 30);
            JPanel gone = new JPanel(null);
            gone.setBounds(100, 10, 0, 30);
            JButton hidden = new JButton("Discard");
            hidden.setBounds(0, 0, 80, 30);
            hidden.addActionListener(e -> pressed.incrementAndGet());
            gone.add(hidden);
            content.add(ok);
            content.add(gone);
            dialog.setContentPane(content);
            dialog.setBounds(420, 300, 240, 120);
            dialog.setVisible(true);
            d[0] = dialog;
            return null;
        });
        try {
            await("dialog showing", WAIT_MS, () -> d[0].isShowing());
            settle();
            expectError(ErrorCodes.NOT_FOUND, () -> call("swing.dismiss", "dialog", ref(d[0]), "button", "Discard"));
            Map<String, Object> listed = null;
            for (Object o : arr(call("swing.dialogs").get("dialogs"))) {
                if (ref(d[0]).equals(obj(o).get("ref"))) {
                    listed = obj(o);
                }
            }
            assertEquals(1, arr(listed.get("buttons")).size(), "only the button on screen: " + listed);
            settle();
            assertEquals(0, pressed.get());
        } finally {
            onEdt(() -> {
                for (Window w : Window.getWindows()) {
                    if (w instanceof JDialog) {
                        w.dispose();
                    }
                }
                return null;
            });
            settle();
        }
    }
}
