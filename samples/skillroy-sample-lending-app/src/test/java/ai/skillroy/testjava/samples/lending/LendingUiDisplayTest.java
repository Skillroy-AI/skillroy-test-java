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

package ai.skillroy.testjava.samples.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Window-level tests. They need a display, so they are skipped headless and run under
 * {@code ci/run-in-linux.sh ... -Dskillroy.headless=false} (a window-manager-less Xvfb).
 */
class LendingUiDisplayTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    /** How often {@link #awaitFocus} re-requests the focus while it waits. */
    private static final long REFOCUS_MS = 1000;

    private LendingApp app;
    private Recorder rec;

    @BeforeEach
    void requireDisplay() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
    }

    @AfterEach
    void shutdown() {
        if (app != null) {
            app.shutdown();
        }
    }

    private LendingApp launch(LendingOptions options) throws Exception {
        app = LendingApp.launch(options.dbName("ui_" + COUNTER.incrementAndGet()).ui(true));
        long deadline = System.currentTimeMillis() + Recorder.TIMEOUT_MS;
        while (!app.engine().isAwaitingInput() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        rec = new Recorder();
        app.engine().addListener(rec);
        return app;
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<T>();
        AtomicReference<Exception> failure = new AtomicReference<Exception>();
        EventQueue.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private static void eventually(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + Recorder.TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (onEdt(condition)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for: " + what);
    }

    private Recorder.Ev send(LendingInput input) {
        int mark = rec.size();
        assertTrue(app.inbox().offer(input));
        Recorder.Ev accepted = rec.await(mark, e -> e.kind == Recorder.Kind.ACCEPTED);
        return rec.await(accepted.index + 1,
            e -> e.kind == Recorder.Kind.REQUESTED || e.kind == Recorder.Kind.PROMPT_SHOWN);
    }

    private void signOn() {
        send(LendingInput.entry("100"));
        assertEquals(LendingState.IDLE, send(LendingInput.entry("1111")).state);
    }

    private static PromptDialog showingDialog() {
        for (Window w : Window.getWindows()) {
            if (w instanceof PromptDialog && w.isShowing()) {
                return (PromptDialog) w;
            }
        }
        return null;
    }

    private static void collect(Component c, List<Component> out) {
        out.add(c);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collect(child, out);
            }
        }
    }

    @Test
    void frameShowsWithTitleSizeAndNamelessKeys() throws Exception {
        launch(LendingOptions.defaults());
        LendingFrame frame = app.frame();
        assertNotNull(frame);
        eventually("frame showing", frame::isShowing);
        assertEquals("Tool Library Lending Desk", onEdt(frame::getTitle));
        assertEquals(new Dimension(1024, 768), onEdt(frame::getSize));
        assertEquals(javax.swing.WindowConstants.EXIT_ON_CLOSE, (int) onEdt(frame::getDefaultCloseOperation));
        assertEquals(6, frame.keys().size());
        for (IconKey key : frame.keys()) {
            assertNull(key.getName(), "no component name");
            assertNull(onEdt(() -> key.getAccessibleContext().getAccessibleName()), "no accessible name");
            assertTrue(onEdt(key::isShowing));
        }
        assertEquals(Arrays.asList("QTY", "CANCEL", "RETURN", "DUE", "DEPOSIT", "SIGN_OFF"),
            Arrays.asList(frame.keys().stream().map(IconKey::actionId).toArray()));
        assertEquals("Enter staff code", onEdt(() -> frame.promptLine().getText()));
    }

    @Test
    void titleComesFromTheOptions() throws Exception {
        launch(LendingOptions.defaults().title("Desk Two").seed(Seed.B));
        assertEquals("Desk Two", onEdt(() -> app.frame().getTitle()));
    }

    @Test
    void iconKeyNeedsFocusButPerformAlwaysWorks() throws Exception {
        launch(LendingOptions.defaults());
        IconKey key = app.frame().key("DUE");
        AtomicInteger delivered = new AtomicInteger();
        onEdt(() -> {
            key.addActionListener(e -> delivered.incrementAndGet());
            return null;
        });
        assertFalse(onEdt(key::isFocusOwner));
        onEdt(() -> {
            key.doClick();
            return null;
        });
        long until = System.currentTimeMillis() + 10L * IconKey.DEBOUNCE_MS;
        while (System.currentTimeMillis() < until) {
            assertEquals(0, delivered.get(), "doClick without focus does nothing");
            Thread.sleep(20);
        }
        onEdt(() -> {
            key.perform();
            return null;
        });
        assertEquals(1, delivered.get(), "perform() works without focus");

        onEdt(() -> {
            key.setFocusGated(false);
            key.doClick();
            return null;
        });
        eventually("ungated press delivered", () -> delivered.get() == 2);
        onEdt(() -> {
            key.setFocusGated(true);
            return null;
        });

        onEdt(() -> {
            app.frame().toFront();
            return key.requestFocusInWindow();
        });
        long deadline = System.currentTimeMillis() + 5000;
        boolean focused = false;
        while (!focused && System.currentTimeMillis() < deadline) {
            focused = onEdt(key::isFocusOwner);
            Thread.sleep(20);
        }
        if (focused) {
            onEdt(() -> {
                key.doClick();
                return null;
            });
            eventually("focused press delivered", () -> delivered.get() == 3);
        } else {
            System.out.println("[lending-app] the window could not take the focus here; focused press not checked");
        }
    }

    @Test
    void promptDialogBlocksTheEngineUntilPressed() throws Exception {
        launch(LendingOptions.defaults());
        signOn();
        Recorder.Ev shown = send(LendingInput.barcode("0000000000000"));
        assertEquals(Recorder.Kind.PROMPT_SHOWN, shown.kind);
        eventually("prompt dialog showing", () -> showingDialog() != null);
        PromptDialog dialog = onEdt(LendingUiDisplayTest::showingDialog);
        assertTrue(onEdt(dialog::isUndecorated));
        assertTrue(onEdt(dialog::isModal));
        assertEquals("unknown-item", dialog.promptId());
        assertEquals("Unknown item", dialog.promptText());
        assertEquals(Arrays.asList("OK"), dialog.buttonLabels());
        List<Component> all = onEdt(() -> {
            List<Component> list = new ArrayList<Component>();
            collect(dialog, list);
            return list;
        });
        for (Component c : all) {
            assertFalse(c instanceof JLabel || c instanceof AbstractButton || c instanceof JOptionPane,
                "no standard text component in the dialog: " + c.getClass().getName());
        }

        int mark = rec.size();
        long until = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < until) {
            assertFalse(app.engine().isAwaitingInput(), "the engine is blocked by the open prompt");
            Thread.sleep(20);
        }
        assertEquals(mark, rec.size(), "nothing happens while the prompt is open");
        assertEquals(0, app.inbox().size(), "nothing queued");

        onEdt(() -> {
            dialog.buttons().get(0).press();
            return null;
        });
        Recorder.Ev next = rec.await(mark, e -> e.kind == Recorder.Kind.REQUESTED);
        assertEquals(LendingState.IDLE, next.state);
        eventually("dialog closed", () -> !dialog.isShowing());

        send(LendingInput.barcode("0000000000000"));
        eventually("second dialog showing", () -> showingDialog() != null);
        PromptDialog second = onEdt(LendingUiDisplayTest::showingDialog);
        mark = rec.size();
        onEdt(() -> {
            second.dispatchEvent(new WindowEvent(second, WindowEvent.WINDOW_CLOSING));
            return null;
        });
        Recorder.Ev answered = rec.await(mark, e -> e.kind == Recorder.Kind.PROMPT_ANSWERED);
        assertEquals("OK", answered.choice, "closing presses the last button");
        eventually("second dialog closed", () -> !second.isShowing());
    }

    @Test
    void loanTableAndSlipPanelFollowTheEngine() throws Exception {
        launch(LendingOptions.defaults());
        LendingFrame frame = app.frame();
        signOn();
        send(LendingInput.barcode("5000000000017"));
        send(LendingInput.barcode("5000000000017"));
        eventually("one line with qty 2", () -> frame.loanModel().getRowCount() == 1
            && "2".equals(frame.loanTable().getValueAt(0, 3)));
        assertEquals("Scan or enter item, or DUE", onEdt(() -> frame.promptLine().getText()));
        assertTrue(onEdt(() -> frame.totalsLine().getText()).contains("Due 7.00"));

        onEdt(() -> {
            frame.key("DUE").perform();
            return null;
        });
        rec.await(0, e -> e.kind == Recorder.Kind.REQUESTED && e.state == LendingState.DEPOSIT);
        int mark = rec.size();
        onEdt(() -> {
            frame.entryField().setText("10.00");
            frame.key("DEPOSIT").perform();
            return null;
        });
        rec.await(mark, e -> e.kind == Recorder.Kind.REQUESTED && e.state == LendingState.IDLE);
        eventually("slip printed", () -> frame.slipArea().getText().contains("CHANGE"));
        String slip = onEdt(() -> frame.slipArea().getText());
        assertTrue(slip.matches("(?s).*DEPOSIT DUE +7\\.00.*"), slip);
        assertTrue(slip.matches("(?s).*RECEIVED +10\\.00.*"), slip);
        assertTrue(slip.matches("(?s).*CHANGE +3\\.00.*"), slip);
        eventually("table cleared", () -> frame.loanModel().getRowCount() == 0);
        eventually("totals show the last loan", () -> frame.totalsLine().getText().contains("Last loan 1001"));
    }

    @Test
    void entryFieldPostsEntriesAndMasksPasswords() throws Exception {
        launch(LendingOptions.defaults());
        LendingFrame frame = app.frame();
        int mark = rec.size();
        onEdt(() -> {
            frame.entryField().setText("100");
            frame.entryField().postActionEvent();
            return null;
        });
        assertEquals(LendingState.PASSWORD, rec.await(mark, e -> e.kind == Recorder.Kind.REQUESTED).state);
        eventually("entry masked", () -> frame.entryField().isMasked());
        mark = rec.size();
        onEdt(() -> {
            frame.entryField().setText("1111");
            assertEquals("****", frame.entryField().getText());
            frame.entryField().postActionEvent();
            return null;
        });
        assertEquals(LendingState.IDLE, rec.await(mark, e -> e.kind == Recorder.Kind.REQUESTED).state);
        eventually("entry unmasked", () -> !frame.entryField().isMasked());
        assertEquals("", onEdt(() -> frame.entryField().getText()));
    }

    /**
     * Delivers key events straight to the component, as a keyboard driver without the window focus would. Typed
     * characters reach a text field whatever has the focus; Enter does not: {@code JTextField} binds it to an action
     * that submits the text field that most recently had the focus, not the event's target (see {@link #awaitFocus}).
     */
    private static void typeInto(Component target, String text, boolean enter) throws Exception {
        onEdt(() -> {
            long when = System.currentTimeMillis();
            for (char c : text.toCharArray()) {
                target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_TYPED, when, 0, KeyEvent.VK_UNDEFINED, c));
            }
            if (enter) {
                target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, when, 0, KeyEvent.VK_ENTER,
                    KeyEvent.CHAR_UNDEFINED));
                target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, when, 0, KeyEvent.VK_ENTER,
                    KeyEvent.CHAR_UNDEFINED));
            }
            return null;
        });
    }

    /**
     * Waits until the component has gained the focus, on its focus-gained event and at most
     * {@link Recorder#TIMEOUT_MS}, re-requesting the focus once every {@link #REFOCUS_MS} while it waits. A freshly
     * shown frame gives its first component the focus by itself, but only after a round trip to the X server, from
     * under a millisecond to a few tens of milliseconds after the frame shows; under the window-manager-less Xvfb a
     * single request is sometimes lost as well (amendment A39), and the re-request is the mitigation A39 gave the
     * agent's display tests.
     */
    private static void awaitFocus(String what, Component c) throws Exception {
        CountDownLatch gained = new CountDownLatch(1);
        FocusListener listener = new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                gained.countDown();
            }
        };
        onEdt(() -> {
            c.addFocusListener(listener);
            if (c.isFocusOwner()) {
                gained.countDown();
            }
            return null;
        });
        try {
            long deadline = System.currentTimeMillis() + Recorder.TIMEOUT_MS;
            int requests = 0;
            while (!gained.await(REFOCUS_MS, TimeUnit.MILLISECONDS)) {
                if (System.currentTimeMillis() >= deadline) {
                    fail("timed out after " + Recorder.TIMEOUT_MS + " ms waiting for " + what + " to gain the focus"
                        + " (the focus re-requested " + requests + " times)");
                }
                requestFocus(c);
                requests++;
            }
        } finally {
            onEdt(() -> {
                c.removeFocusListener(listener);
                return null;
            });
        }
    }

    /**
     * Posts {@code toFront()} + {@code requestFocus()} on the component's window (while that is not the focused
     * window) and {@code requestFocusInWindow()} on the component to the EDT; never waits for the EDT.
     */
    private static void requestFocus(Component c) {
        EventQueue.invokeLater(() -> {
            if (!c.isDisplayable() || c.isFocusOwner()) {
                return;
            }
            Window w = SwingUtilities.getWindowAncestor(c);
            if (w != null && !w.isFocused()) {
                w.toFront();
                w.requestFocus();
            }
            c.requestFocusInWindow();
        });
    }

    @Test
    void typedKeysFillTheEntryFieldAndEnterSubmitsIt() throws Exception {
        launch(LendingOptions.defaults());
        LendingFrame frame = app.frame();
        eventually("frame showing", frame::isShowing);
        int mark = rec.size();
        typeInto(frame.entryField(), "100", false);
        assertEquals("100", onEdt(() -> frame.entryField().getText()));
        // Enter submits the text field that most recently had the focus, so an Enter that comes before the frame has
        // given the entry field the focus is lost and no engine event ever follows: the race this wait closes
        // (enterDoesNotSubmitAnEntryFieldThatNeverHadTheFocus shows the lost Enter)
        awaitFocus("the entry field", frame.entryField());
        typeInto(frame.entryField(), "", true);
        assertEquals(LendingInput.entry("100"), rec.await(mark, e -> e.kind == Recorder.Kind.ACCEPTED).input);
        assertEquals(LendingState.PASSWORD, rec.await(mark, e -> e.kind == Recorder.Kind.REQUESTED).state);
        eventually("entry masked", () -> frame.entryField().isMasked());

        mark = rec.size();
        typeInto(frame.entryField(), "1111", false);
        assertEquals("****", onEdt(() -> frame.entryField().getText()), "typed characters are masked");
        assertEquals("1111", onEdt(() -> frame.entryField().value()));
        typeInto(frame.entryField(), "", true);
        assertEquals(LendingState.IDLE, rec.await(mark, e -> e.kind == Recorder.Kind.REQUESTED).state);
        assertEquals("100", app.engine().currentStaff().code());
    }

    /**
     * The losing side of the race {@link #typedKeysFillTheEntryFieldAndEnterSubmitsIt} waits out, made certain: in a
     * window that cannot take the focus an entry field shows and takes typed characters, and an Enter submits
     * nothing; its own action event still does. Key events reach only a showing component, so this needs a display.
     */
    @Test
    void enterDoesNotSubmitAnEntryFieldThatNeverHadTheFocus() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        EntryField field = onEdt(() -> {
            EntryField f = new EntryField(10);
            f.addActionListener(e -> submitted.incrementAndGet());
            JFrame w = new JFrame("Never focused");
            w.setFocusableWindowState(false);
            w.add(f);
            w.pack();
            w.setVisible(true);
            return f;
        });
        try {
            eventually("the field showing", field::isShowing);
            typeInto(field, "100", false);
            assertEquals("100", onEdt(field::getText), "typed characters arrive without the focus");
            assertFalse(onEdt(field::isFocusOwner));
            typeInto(field, "", true);
            assertEquals(0, submitted.get(), "Enter submits nothing");
            onEdt(() -> {
                field.postActionEvent();
                return null;
            });
            assertEquals(1, submitted.get(), "the field's own action event submits it");
        } finally {
            onEdt(() -> {
                SwingUtilities.getWindowAncestor(field).dispose();
                return null;
            });
        }
    }

    @Test
    void aMouseClickOnAKeyDeliversItsActionAndHandsTheFocusBackToTheEntryField() throws Exception {
        launch(LendingOptions.defaults());
        LendingFrame frame = app.frame();
        signOn();
        send(LendingInput.barcode("5000000000017"));
        onEdt(() -> {
            frame.toFront();
            return frame.entryField().requestFocusInWindow();
        });
        long deadline = System.currentTimeMillis() + 5000;
        boolean focused = false;
        while (!focused && System.currentTimeMillis() < deadline) {
            focused = onEdt(() -> frame.isFocused() && frame.entryField().isFocusOwner());
            Thread.sleep(20);
        }
        if (!focused) {
            System.out.println("[lending-app] the window could not take the focus here; mouse click not checked");
            return;
        }
        IconKey due = frame.key("DUE");
        int mark = rec.size();
        onEdt(() -> {
            long when = System.currentTimeMillis();
            int x = due.getWidth() / 2;
            int y = due.getHeight() / 2;
            due.dispatchEvent(new MouseEvent(due, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON1));
            due.dispatchEvent(new MouseEvent(due, MouseEvent.MOUSE_RELEASED, when, 0, x, y, 1, false,
                MouseEvent.BUTTON1));
            return null;
        });
        Recorder.Ev accepted = rec.await(mark, e -> e.kind == Recorder.Kind.ACCEPTED);
        assertEquals(LendingInput.key(KeyAction.DUE), accepted.input);
        assertEquals(LendingState.DEPOSIT, rec.await(mark, e -> e.kind == Recorder.Kind.REQUESTED).state);
        eventually("focus back on the entry field", () -> frame.entryField().isFocusOwner());
        assertEquals(1, rec.since(mark).stream().filter(e -> e.kind == Recorder.Kind.ACCEPTED).count(),
            "one click, one input");
    }

    /** Phase 7 (phase7-contracts.md section 12.1): a press and release on the number pad's {@code key:7} types 7. */
    @Test
    void aPressOnTheNumberPadsKeySevenAppendsSeven() throws Exception {
        launch(LendingOptions.defaults());
        LendingFrame frame = app.frame();
        eventually("frame showing", frame::isShowing);
        NumberPad pad = frame.numberPad();
        assertEquals(NumberPad.NAME, pad.getName());
        assertTrue(onEdt(pad::isShowing));
        assertEquals(javax.accessibility.AccessibleRole.PANEL,
            onEdt(() -> pad.getAccessibleContext().getAccessibleRole()));
        java.awt.Rectangle seven = NumberPad.keyRect("7");
        onEdt(() -> {
            long when = System.currentTimeMillis();
            int x = seven.x + seven.width / 2;
            int y = seven.y + seven.height / 2;
            pad.dispatchEvent(new MouseEvent(pad, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON1));
            pad.dispatchEvent(new MouseEvent(pad, MouseEvent.MOUSE_RELEASED, when, 0, x, y, 1, false,
                MouseEvent.BUTTON1));
            return null;
        });
        assertEquals("7", onEdt(() -> frame.entryField().value()));
        java.awt.Rectangle clear = NumberPad.keyRect("CLEAR");
        onEdt(() -> {
            pad.press("0");
            assertEquals("70", frame.entryField().value());
            long when = System.currentTimeMillis();
            int x = clear.x + 5;
            int y = clear.y + 5;
            pad.dispatchEvent(new MouseEvent(pad, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON1));
            // released over another key: nothing happens
            pad.dispatchEvent(new MouseEvent(pad, MouseEvent.MOUSE_RELEASED, when, 0, 5, 5, 1, false,
                MouseEvent.BUTTON1));
            return null;
        });
        assertEquals("70", onEdt(() -> frame.entryField().value()), "a release outside the pressed key does nothing");
        assertTrue(onEdt(() -> pad.getHeight()) >= 160, "the bottom panel grew to the pad's height");
        assertEquals(64, (int) onEdt(() -> frame.key("DUE").getHeight()), "the keypad row keeps its height");
    }

    @Test
    void aStaleDialogDoesNotAnswerANewerPromptWithTheSameId() throws Exception {
        PromptService service = new PromptService();
        AtomicReference<String> second = new AtomicReference<String>();
        Thread asker = new Thread(() -> {
            try {
                service.ask("same-id", "First", Collections.singletonList("OK"));
                second.set(service.ask("same-id", "Second", Collections.singletonList("OK")));
            } catch (InterruptedException e) {
                second.set("interrupted");
            }
        }, "asker");
        asker.start();
        try {
            eventually("first prompt open", () -> service.current() != null);
            Prompt first = service.current();
            PromptDialog stale = onEdt(() -> new PromptDialog(null, first, service));
            assertTrue(service.answer("OK"), "answered through the service; its dialog is still around");
            eventually("second prompt open", () -> service.current() != null && service.current() != first);
            Prompt newer = service.current();

            onEdt(() -> {
                stale.buttons().get(0).press();
                return null;
            });
            assertSame(newer, service.current(), "the newer prompt is still open");
            assertNull(second.get());
            assertFalse(onEdt(stale::isDisplayable), "the stale dialog closed itself");

            PromptDialog fresh = onEdt(() -> new PromptDialog(null, newer, service));
            assertEquals("Second", fresh.promptText());
            onEdt(() -> {
                fresh.buttons().get(0).press();
                return null;
            });
            asker.join(Recorder.TIMEOUT_MS);
            assertEquals("OK", second.get());
        } finally {
            asker.interrupt();
        }
    }
}
