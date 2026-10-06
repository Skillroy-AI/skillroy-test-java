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

import ai.skillroy.testjava.agent.api.spi.DialogInfo;
import ai.skillroy.testjava.agent.api.spi.DialogLocator;
import ai.skillroy.testjava.agent.api.spi.DialogTextExtractor;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDesktopPane;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.arr;
import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.JsonCheck.obj;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment A27: dialogs an application draws inside another window, as a {@link DialogLocator} reports them. They
 * are listed by {@code swing.dialogs} after the dialog windows, read by {@code extract(Component)} or generically,
 * answered by {@code swing.dismiss} (never "closed"), and while one blocks input, a click, type or key aimed at a
 * component of the same window outside it is {@code MODAL_OPEN} with {@code inWindow: true} unless forced. A locator
 * that throws or reports junk does no harm.
 */
class InWindowDialogDisplayTest extends DisplayTestBase {

    private final List<AutoCloseable> registrations = new ArrayList<AutoCloseable>();
    private TestLocator locator;

    @BeforeEach
    void registerLocator() {
        locator = new TestLocator();
        registrations.add(ctx.spi.register(DialogLocator.class, locator));
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (AutoCloseable r : registrations) {
            r.close();
        }
        registrations.clear();
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
    void listedAfterTheDialogWindowsAndReadGenerically() throws Exception {
        final Desk desk = Desk.show(locator, 0);
        final JDialog notice = onEdt(() -> {
            JDialog d = new JDialog(ui.frame, "Notice", false);
            d.add(new JLabel("Shelf B is full"));
            d.setBounds(660, 300, 200, 80);
            d.setVisible(true);
            return d;
        });
        await("notice showing", WAIT_MS, notice::isShowing);
        List<Object> dialogs = arr(call("swing.dialogs").get("dialogs"));
        assertEquals(2, dialogs.size(), dialogs.toString());
        Map<String, Object> window = obj(dialogs.get(0));
        assertEquals(ref(notice), window.get("ref"));
        assertFalse(window.containsKey("inWindow"), "a dialog window keeps its shape: " + window);
        Map<String, Object> drawn = obj(dialogs.get(1));
        assertEquals(ref(desk.prompt), drawn.get("ref"));
        assertEquals(Boolean.TRUE, drawn.get("inWindow"));
        assertEquals(ref(desk.frame), drawn.get("window"));
        assertEquals(Boolean.TRUE, drawn.get("modal"));
        assertEquals("Confirm return", drawn.get("title"), "the titled border's title");
        assertEquals("Return the drill to the shelf?", drawn.get("text"));
        assertEquals("generic", drawn.get("extractor"));
        assertEquals(list(map("ref", ref(desk.yes), "text", "Yes"), map("ref", ref(desk.no), "text", "No")),
                drawn.get("buttons"));

        locator.blocks = false;
        assertEquals(Boolean.FALSE, obj(arr(call("swing.dialogs").get("dialogs")).get(1)).get("modal"),
                "modal is what blocksInput says");
        onEdt(() -> {
            desk.glass.setVisible(false);
            return null;
        });
        assertEquals(1, arr(call("swing.dialogs").get("dialogs")).size(), "a hidden root is not listed");
    }

    @Test
    void anExtractorReadsInWindowRootsThroughExtractComponent() throws Exception {
        final Desk desk = Desk.show(locator, 0);
        final AtomicInteger windowCalls = new AtomicInteger();
        registrations.add(ctx.spi.register(DialogTextExtractor.class, new DialogTextExtractor() {
            @Override
            public DialogInfo extract(Window window) {
                windowCalls.incrementAndGet();
                return null;
            }

            @Override
            public DialogInfo extract(Component root) {
                if (root != desk.prompt) {
                    return null;
                }
                return new DialogInfo("desk-prompt", "Return", "Drill 7 back on shelf B?", Arrays.asList("Yes", "No"));
            }
        }));
        Map<String, Object> drawn = obj(arr(call("swing.dialogs").get("dialogs")).get(0));
        assertEquals("desk-prompt", drawn.get("extractor"));
        assertEquals("Return", drawn.get("title"));
        assertEquals("Drill 7 back on shelf B?", drawn.get("text"));
        assertEquals(list(map("ref", ref(desk.yes), "text", "Yes"), map("ref", ref(desk.no), "text", "No")),
                drawn.get("buttons"));
        assertEquals(0, windowCalls.get(), "no dialog window is showing, so extract(Window) was not asked");
        RpcException e = expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.click", "target", ref(desk.behind)));
        assertEquals("Return", e.error().data().get("title"), "MODAL_OPEN names the extractor's title");
    }

    @Test
    void inputOutsideABlockingInWindowDialogIsRefusedUnlessForced() throws Exception {
        final Desk desk = Desk.show(locator, 0);
        final String dialogRef = ref(desk.prompt);
        for (String rung : new String[] {"AUTO", "ACTION", "COMPONENT", "DISPATCH"}) {
            RpcException e = expectError(ErrorCodes.MODAL_OPEN,
                    () -> call("swing.click", "target", ref(desk.behind), "rung", rung));
            assertEquals(map("dialog", dialogRef, "title", "Confirm return", "ref", ref(desk.behind), "inWindow",
                    true), e.error().data(), rung);
        }
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.click", "by", map("text", "Behind")));
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.type", "target", ref(desk.field), "text", "x"));
        expectError(ErrorCodes.MODAL_OPEN,
                () -> call("swing.type", "target", ref(desk.field), "text", "x", "mode", "DISPATCH"));
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.key", "key", "ENTER", "target", ref(desk.field)));
        RpcException key = expectError(ErrorCodes.MODAL_OPEN,
                () -> call("swing.key", "key", "ENTER", "target", ref(desk.frame)));
        assertEquals(Boolean.TRUE, key.error().data().get("inWindow"));
        settle();
        assertEquals(0, desk.behindClicks.get(), "a refused click did nothing");
        assertEquals("", onEdt(() -> desk.field.getText()), "a refused type did nothing");

        // another window is not blocked by it, and neither is the dialog's own content
        final int ok = ui.okClicks.get();
        call("swing.click", "target", ref(ui.ok));
        await("a click into another window", WAIT_MS, () -> ui.okClicks.get() == ok + 1);

        // forced, input goes through
        call("swing.click", "target", ref(desk.behind), "force", true);
        await("forced click delivered", WAIT_MS, () -> desk.behindClicks.get() == 1);
        call("swing.type", "target", ref(desk.field), "text", "forced", "force", true);
        settle();
        assertEquals("forced", onEdt(() -> desk.field.getText()));

        // a dialog that does not block input refuses nothing
        locator.blocks = false;
        call("swing.click", "target", ref(desk.behind));
        await("click while the in-window dialog does not block", WAIT_MS, () -> desk.behindClicks.get() == 2);
        locator.blocks = true;

        call("swing.click", "target", ref(desk.no));
        await("the dialog's own button answers it", WAIT_MS, () -> "No".equals(desk.answer.get()));
        call("swing.click", "target", ref(desk.behind));
        await("once answered, the window takes input again", WAIT_MS, () -> desk.behindClicks.get() == 3);
    }

    @Test
    void dismissAnswersTheInWindowDialogThroughItsButtons() throws Exception {
        final Desk desk = Desk.show(locator, 0);
        final String dialogRef = ref(desk.prompt);
        RpcException missing = expectError(ErrorCodes.NOT_FOUND,
                () -> call("swing.dismiss", "dialog", dialogRef, "button", "Maybe"));
        assertEquals(list("Yes", "No"), missing.error().data().get("buttons"));
        RpcException two = expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.dismiss"));
        assertEquals(list("Yes", "No"), two.error().data().get("buttons"), "the only dialog, but two buttons");
        RpcException close = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.dismiss", "dialog", dialogRef, "close", true));
        assertEquals("an in-window dialog has no window to close; name a button", close.error().message());
        assertEquals(Boolean.TRUE, close.error().data().get("inWindow"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.dismiss", "close", true));
        RpcException notADialog = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.dismiss", "dialog", ref(desk.behind), "button", "Yes"));
        assertEquals("dialog", notADialog.error().data().get("param"));
        assertNull(desk.answer.get(), "nothing was pressed so far");

        assertEquals(map("dismissedBy", "BUTTON_ACTION", "button", "No"),
                call("swing.dismiss", "dialog", dialogRef, "button", "no"));
        await("answered No", WAIT_MS, () -> "No".equals(desk.answer.get()));
        await("the prompt is hidden", WAIT_MS, () -> !desk.prompt.isShowing());

        desk.showPrompt();
        assertEquals(map("dismissedBy", "BUTTON_ACTION", "button", "Yes"), call("swing.dismiss", "button", "Yes"),
                "taken by default as the only dialog showing");
        await("answered Yes", WAIT_MS, () -> "Yes".equals(desk.answer.get()));
        expectError(ErrorCodes.NOT_FOUND, () -> call("swing.dismiss", "button", "Yes"));
    }

    @Test
    void theDefaultOfDismissIsTheOnlyDialogShowing() throws Exception {
        final Desk first = Desk.show(locator, 0);
        final Desk second = Desk.show(locator, 270);
        RpcException e = expectError(ErrorCodes.AMBIGUOUS, () -> call("swing.dismiss", "button", "Yes"));
        assertEquals(list(ref(first.prompt), ref(second.prompt)), e.error().data().get("dialogs"));
        call("swing.dismiss", "dialog", ref(second.prompt), "button", "Yes");
        await("second answered", WAIT_MS, () -> "Yes".equals(second.answer.get()));
        await("second prompt hidden", WAIT_MS, () -> !second.prompt.isShowing());

        // with a modeless dialog window showing as well, the in-window dialog is not the only one
        final JDialog notice = onEdt(() -> {
            JDialog d = new JDialog(ui.frame, "Notice", false);
            d.add(new JButton("Noted"));
            d.setBounds(660, 560, 200, 80);
            d.setVisible(true);
            return d;
        });
        await("notice showing", WAIT_MS, notice::isShowing);
        e = expectError(ErrorCodes.AMBIGUOUS, () -> call("swing.dismiss", "button", "Yes"));
        assertEquals(list(ref(notice), ref(first.prompt)), e.error().data().get("dialogs"));

        // a modal dialog window comes first, as before
        final JDialog modal = onEdt(() -> {
            JDialog d = new JDialog(ui.frame, "Hold", true);
            d.add(new JButton("Release"));
            d.setBounds(660, 640, 200, 80);
            return d;
        });
        SwingUtilities.invokeLater(() -> modal.setVisible(true)); // blocks in the modal loop
        await("modal dialog showing", WAIT_MS, modal::isShowing);
        assertEquals(map("dismissedBy", "BUTTON_ACTION", "button", "Release"), call("swing.dismiss"));
        assertNull(first.answer.get(), "the in-window dialog was left alone");
    }

    @Test
    void aBrokenOrCarelessLocatorDoesNoHarm() throws Exception {
        final Desk desk = Desk.show(locator, 0);
        final JButton hidden = onEdt(() -> {
            JButton b = new JButton("hidden");
            b.setVisible(false);
            desk.frame.getContentPane().add(b);
            return b;
        });
        registrations.add(ctx.spi.register(DialogLocator.class, new DialogLocator() {
            @Override
            public List<Component> dialogRoots(Window window) {
                // null, the window itself, a component of another window, a hidden one, and a root reported twice
                List<Component> junk = new ArrayList<Component>(Arrays.asList(null, window, hidden, desk.prompt));
                if (window != ui.frame) {
                    junk.add(ui.ok);
                }
                return junk;
            }
        }));
        registrations.add(ctx.spi.register(DialogLocator.class, new DialogLocator() {
            @Override
            public List<Component> dialogRoots(Window window) {
                throw new IllegalStateException("locator failed");
            }
        }));
        registrations.add(ctx.spi.register(DialogLocator.class, new DialogLocator() {
            @Override
            public List<Component> dialogRoots(Window window) {
                return null;
            }
        }));
        int warnings = ctx.log.warnings().size();
        List<Object> dialogs = arr(call("swing.dialogs").get("dialogs"));
        assertEquals(1, dialogs.size(), "junk is ignored and the root is listed once: " + dialogs);
        assertEquals(ref(desk.prompt), obj(dialogs.get(0)).get("ref"));
        assertTrue(ctx.log.warnings().size() > warnings, "the failing locator was logged");
        assertTrue(ctx.log.warnings().toString().contains("locator failed"), ctx.log.warnings().toString());
        final int ok = ui.okClicks.get();
        call("swing.click", "target", ref(ui.ok));
        await("a component reported for a window it is not in is no dialog and blocks nothing", WAIT_MS,
                () -> ui.okClicks.get() == ok + 1);

        // a locator whose blocksInput throws is taken to block, as the default says
        locator.blocksFailure = new IllegalStateException("blocksInput failed");
        RpcException e = expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.click", "target", ref(desk.behind)));
        assertEquals(Boolean.TRUE, e.error().data().get("inWindow"));
        assertTrue(ctx.log.warnings().toString().contains("blocksInput failed"), ctx.log.warnings().toString());
    }

    @Test
    void anInternalOptionPaneIsReadAndAnswered() throws Exception {
        final JOptionPane[] pane = new JOptionPane[1];
        final JInternalFrame[] internal = new JInternalFrame[1];
        final JFrame[] frame = new JFrame[1];
        onEdt(() -> {
            frame[0] = new JFrame("Tool library");
            JDesktopPane desktop = new JDesktopPane();
            frame[0].setContentPane(desktop);
            frame[0].setBounds(660, 0, 360, 260);
            frame[0].setVisible(true);
            pane[0] = new JOptionPane("Lend the ladder?", JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
            internal[0] = pane[0].createInternalFrame(desktop, "Lend");
            internal[0].setVisible(true);
            return null;
        });
        await("internal option pane showing", WAIT_MS, () -> internal[0].isShowing());
        locator.roots.add(internal[0]);
        Map<String, Object> d = obj(arr(call("swing.dialogs").get("dialogs")).get(0));
        assertEquals(ref(internal[0]), d.get("ref"));
        assertEquals(ref(frame[0]), d.get("window"));
        assertEquals("Lend", d.get("title"), "an internal frame's title");
        assertEquals("Lend the ladder?", d.get("text"), "the option pane's message");
        assertEquals(2, arr(d.get("buttons")).size());
        assertEquals(map("dismissedBy", "BUTTON_ACTION", "button", "Yes"),
                call("swing.dismiss", "dialog", ref(internal[0]), "button", "Yes"));
        await("the option pane got its answer", WAIT_MS,
                () -> Integer.valueOf(JOptionPane.YES_OPTION).equals(pane[0].getValue()));
    }

    /** Reports the roots it was given that are showing in the window asked about. */
    static final class TestLocator implements DialogLocator {
        final List<Component> roots = new CopyOnWriteArrayList<Component>();
        volatile boolean blocks = true;
        volatile RuntimeException blocksFailure;

        @Override
        public List<Component> dialogRoots(Window window) {
            List<Component> out = new ArrayList<Component>();
            for (Component c : roots) {
                if (c.isShowing() && SwingUtilities.getWindowAncestor(c) == window) {
                    out.add(c);
                }
            }
            return out;
        }

        @Override
        public boolean blocksInput(Component root) {
            RuntimeException failure = blocksFailure;
            if (failure != null) {
                throw failure;
            }
            return blocks;
        }
    }

    /**
     * A frame that draws its prompt on its glass pane, the way some applications show message boxes: a titled panel
     * with a text and two buttons over the window's own controls.
     */
    static final class Desk {
        JFrame frame;
        JPanel glass;
        JPanel prompt;
        JButton behind;
        JTextField field;
        JButton yes;
        JButton no;
        final AtomicInteger behindClicks = new AtomicInteger();
        final AtomicReference<String> answer = new AtomicReference<String>();

        /** Builds and shows the frame with its prompt showing, at {@code y}, and registers the prompt as a root. */
        static Desk show(TestLocator locator, final int y) throws Exception {
            final Desk desk = new Desk();
            onEdt(() -> {
                desk.build(y);
                return null;
            });
            await("desk prompt showing", WAIT_MS, () -> desk.prompt.isShowing());
            locator.roots.add(desk.prompt);
            return desk;
        }

        private void build(int y) {
            frame = new JFrame("Lending desk");
            JPanel content = new JPanel(new FlowLayout());
            behind = new JButton("Behind");
            behind.addActionListener(e -> behindClicks.incrementAndGet());
            field = new JTextField(8);
            content.add(behind);
            content.add(field);
            frame.setContentPane(content);
            prompt = new JPanel(new BorderLayout());
            prompt.setBorder(BorderFactory.createTitledBorder("Confirm return"));
            prompt.add(new JLabel("Return the drill to the shelf?"), BorderLayout.CENTER);
            JPanel buttons = new JPanel(new FlowLayout());
            yes = new JButton("Yes");
            no = new JButton("No");
            yes.addActionListener(e -> choose("Yes"));
            no.addActionListener(e -> choose("No"));
            buttons.add(yes);
            buttons.add(no);
            prompt.add(buttons, BorderLayout.SOUTH);
            glass = new JPanel(new GridBagLayout());
            glass.setOpaque(false);
            glass.add(prompt);
            frame.setGlassPane(glass);
            frame.setBounds(660, y, 360, 260);
            frame.setVisible(true);
            glass.setVisible(true);
        }

        private void choose(String label) {
            answer.set(label);
            glass.setVisible(false);
        }

        void showPrompt() throws Exception {
            onEdt(() -> {
                answer.set(null);
                glass.setVisible(true);
                return null;
            });
            await("desk prompt showing again", WAIT_MS, () -> prompt.isShowing());
        }
    }
}
