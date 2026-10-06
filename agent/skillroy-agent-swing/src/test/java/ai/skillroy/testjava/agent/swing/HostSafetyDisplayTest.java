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
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the module must never do to its host, on a real display: concurrent callers, a blocked EDT, host code that
 * throws inside a posted action, malformed input, focus that does or does not arrive, and a tap installed late.
 */
class HostSafetyDisplayTest extends DisplayTestBase {

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

    // ---------------------------------------------------------------------------------------------- concurrency

    @Test
    void concurrentCallersGetConsistentAnswers() throws Exception {
        final int threads = 6;
        final int rounds = 10;
        final int okBefore = ui.okClicks.get();
        final int warnings = ctx.log.warnings().size();
        ExecutorService pool = Executors.newFixedThreadPool(threads, FakeContext.daemon("skillroy-test-load"));
        try {
            List<Future<Void>> futures = new ArrayList<Future<Void>>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                futures.add(pool.submit(() -> {
                    for (int r = 0; r < rounds; r++) {
                        Map<String, Object> root = obj(arr(call("swing.snapshot").get("windows")).get(0));
                        assertEquals(ref(ui.frame), root.get("ref"));
                        assertEquals(ref(ui.ok), SwingDisplayTest.node(root, n -> "OK".equals(n.get("text")))
                                .get("ref"));
                        Map<String, Object> found = call("swing.find", "by", map("name", "entry"));
                        assertEquals(ref(ui.entry), obj(arr(found.get("matches")).get(0)).get("ref"));
                        assertEquals(fired("ACTION", "AUTO"), call("swing.click", "target", ref(ui.ok)));
                        assertEquals(1, arr(call("swing.windows").get("windows")).size());
                        assertEquals(list(), call("swing.dialogs").get("dialogs"));
                        if (r % 5 == id % 5) {
                            assertEquals("png", call("swing.capture", "target", ref(ui.ok)).get("format"));
                        }
                    }
                    return null;
                }));
            }
            for (Future<Void> f : futures) {
                f.get(10, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        await("every click delivered exactly once", WAIT_MS * 4,
                () -> ui.okClicks.get() == okBefore + threads * rounds);
        settle();
        assertEquals(okBefore + threads * rounds, ui.okClicks.get());
        assertEquals(warnings, ctx.log.warnings().size(), "no failure was logged: " + ctx.log.warnings());
    }

    // ---------------------------------------------------------------------------------------------- blocked EDT

    @Test
    void aBlockedEdtGivesTimeoutsAndNothingRunsLate() throws Exception {
        final int ok = ui.okClicks.get();
        final int actions = ui.entryActions.get();
        final String text = onEdt(() -> ui.entry.getText());
        final int listeners = onEdt(() -> ui.ok.getFocusListeners().length);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch blocking = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            blocking.countDown();
            try {
                release.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(blocking.await(WAIT_MS, TimeUnit.MILLISECONDS));
            final long shortMs = 1500;
            timesOut("swing.snapshot", map(), shortMs);
            timesOut("swing.windows", map(), shortMs);
            timesOut("swing.find", map("by", map("name", "entry")), shortMs);
            timesOut("swing.dialogs", map(), shortMs);
            timesOut("swing.capture", map(), shortMs);
            timesOut("swing.click", map("target", ref(ui.ok)), shortMs);
            timesOut("swing.click", map("target", ref(ui.ok), "rung", "COMPONENT"), shortMs);
            timesOut("swing.click", map("target", ref(ui.ok), "rung", "DISPATCH"), shortMs);
            timesOut("swing.type", map("target", ref(ui.entry), "text", "late", "enter", true), shortMs);
            timesOut("swing.type", map("target", ref(ui.entry), "text", "late", "mode", "DISPATCH"), shortMs);
            timesOut("swing.key", map("key", "ENTER", "target", ref(ui.entry)), shortMs);
            timesOut("swing.dismiss", map("close", true), shortMs);

            long start = System.nanoTime();
            Map<String, Object> settled = ctx.call("swing.settle", map("timeoutMs", 700L), CALL_MS);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(Boolean.FALSE, settled.get("settled"), "a blocked EDT never settles");
            assertTrue(ms < 10000, "settle kept to its own timeout: " + ms + " ms");
            assertEquals(Boolean.TRUE, call("swing.edtState").get("edtBlocked"));
        } finally {
            release.countDown();
        }
        settle();
        assertEquals(ok, ui.okClicks.get(), "no click was delivered after its call had failed");
        assertEquals(actions, ui.entryActions.get(), "no ENTER was delivered late");
        assertEquals(text, onEdt(() -> ui.entry.getText()), "no text was typed late");
        assertEquals(listeners, (int) onEdt(() -> ui.ok.getFocusListeners().length), "no focus listener was left");
    }

    private static void timesOut(String method, Map<String, Object> params, long timeoutMs) {
        long start = System.nanoTime();
        expectError(ErrorCodes.TIMEOUT, () -> ctx.call(method, params, timeoutMs));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ms < timeoutMs + 10000, method + " was bounded by its timeout: " + ms + " ms");
    }

    // ------------------------------------------------------------------------------------- host code that throws

    @Test
    void hostCodeThatThrowsInAPostedActionIsLoggedAndTheEdtLivesOn() throws Exception {
        final AtomicInteger after = new AtomicInteger();
        final JButton[] buttons = new JButton[3];
        onEdt(() -> {
            JFrame f = new JFrame("Bombs");
            f.setLayout(new FlowLayout());
            buttons[0] = new JButton("Listener bomb");
            buttons[0].addActionListener(e -> {
                throw new IllegalStateException("listener failed");
            });
            buttons[1] = new JButton("Firer bomb");
            buttons[2] = new JButton("After");
            buttons[2].addActionListener(e -> after.incrementAndGet());
            for (JButton b : buttons) {
                f.add(b);
            }
            f.setBounds(660, 0, 300, 120);
            f.setVisible(true);
            return null;
        });
        await("bomb frame showing", WAIT_MS, () -> buttons[2].isShowing());
        final Thread edt = onEdt(Thread::currentThread);
        final List<Throwable> uncaught = new CopyOnWriteArrayList<Throwable>();
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.add(e));
        AutoCloseable throwing = ctx.spi.register(ActionFirer.class, new ActionFirer() {
            @Override
            public boolean canFire(Component c) {
                if (c == buttons[2]) {
                    throw new IllegalStateException("canFire failed");
                }
                return c == buttons[1];
            }

            @Override
            public void fire(Component c) throws Exception {
                throw new Exception("fire failed");
            }
        });
        try {
            int warnings = ctx.log.warnings().size();
            assertEquals(fired("ACTION", "AUTO"), call("swing.click", "target", ref(buttons[0])));
            assertEquals(fired("ACTION", "AUTO"), call("swing.click", "target", ref(buttons[1])));
            assertEquals(fired("DISPATCH", "DISPATCH"),
                    call("swing.click", "target", ref(buttons[0]), "rung", "DISPATCH"));
            // a firer whose canFire throws is skipped: the click falls back to doClick
            call("swing.click", "target", ref(buttons[2]));
            await("the button after the bombs still clicks", WAIT_MS, () -> after.get() == 1);
            settle();
            List<String> logged = ctx.log.warnings().subList(warnings, ctx.log.warnings().size());
            assertEquals(4, logged.size(), "three failed actions and one failed canFire: " + logged);
            assertTrue(logged.toString().contains("listener failed") && logged.toString().contains("fire failed")
                    && logged.toString().contains("canFire failed"), logged.toString());
            assertTrue(uncaught.isEmpty(), "nothing reached the EDT's uncaught-exception handler: " + uncaught);
            assertSame(edt, onEdt(Thread::currentThread), "the same EDT is still dispatching");
        } finally {
            throwing.close();
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }

    // ---------------------------------------------------------------------------------------------------- focus

    @Test
    void componentRungClicksOnlyWithFocusAndAlwaysRemovesItsListener() throws Exception {
        onEdt(() -> {
            ui.frame.toFront();
            ui.frame.requestFocus();
            return null;
        });
        await("the test frame is the focused window", WAIT_MS, () -> ui.frame.isFocused());
        final int listeners = onEdt(() -> ui.ok.getFocusListeners().length);
        final int clicks = ui.okClicks.get();
        assertEquals(fired("COMPONENT", "COMPONENT"),
                call("swing.click", "target", ref(ui.ok), "rung", "COMPONENT", "focusTimeoutMs", 10000L));
        await("clicked once focused", WAIT_MS, () -> ui.okClicks.get() == clicks + 1);
        assertTrue(onEdt(() -> ui.ok.hasFocus()), "the component rung leaves the focus on its target");
        settle();
        assertEquals(listeners, (int) onEdt(() -> ui.ok.getFocusListeners().length), "focus listener removed");

        // a second frame takes the focus: the first frame's button can no longer get it
        final JFrame[] other = new JFrame[1];
        onEdt(() -> {
            other[0] = new JFrame("Focus thief");
            other[0].add(new JButton("Thief"));
            other[0].setBounds(660, 200, 200, 100);
            other[0].setVisible(true);
            return null;
        });
        await("the second frame is the focused window", WAIT_MS, () -> other[0].isFocused());
        Map<String, Object> r = call("swing.click", "target", ref(ui.ok), "rung", "COMPONENT", "focusTimeoutMs", 300L);
        assertEquals("FOCUS_FAILED", r.get("rung"));
        assertEquals(Boolean.FALSE, r.get("fired"));
        assertTrue(String.valueOf(r.get("reason")).contains("requestFocusInWindow() returned false"), r.toString());
        settle();
        assertEquals(clicks + 1, ui.okClicks.get(), "nothing is clicked without focus");
        assertEquals(listeners, (int) onEdt(() -> ui.ok.getFocusListeners().length), "focus listener removed");
    }

    @Test
    void keyWithoutATargetGoesIntoTheModalDialogWhileTheFocusIsStillInItsOwner() throws Exception {
        onEdt(() -> {
            ui.frame.toFront();
            ui.frame.requestFocus();
            return null;
        });
        await("the test frame is the focused window", WAIT_MS, () -> ui.frame.isFocused());
        final AtomicInteger escapes = new AtomicInteger();
        final JDialog[] dialog = new JDialog[1];
        onEdt(() -> {
            JDialog d = new JDialog(ui.frame, "Prompt without focus", true);
            d.add(new JButton("Wait"));
            // shown unfocusable so that the focus stays in the frame, then made focusable: the state every modal
            // dialog is in for a moment after it opens, held still
            d.setFocusableWindowState(false);
            d.setBounds(660, 320, 220, 100);
            dialog[0] = d;
            return null;
        });
        SwingUtilities.invokeLater(() -> dialog[0].setVisible(true)); // blocks in the modal loop
        await("modal dialog showing", WAIT_MS, () -> dialog[0].isShowing());
        settle();
        Component owner = onEdt(() -> {
            dialog[0].setFocusableWindowState(true);
            // registered now, so that Swing files the binding under the dialog (it files the window bindings of an
            // unfocusable window under its owner)
            dialog[0].getRootPane().registerKeyboardAction(e -> escapes.incrementAndGet(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
            return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        });
        assertTrue(owner != null && Windows.windowOf(owner) == ui.frame, "the focus is still in the frame: " + owner);

        // a key aimed at the blocked frame is refused, and when forced it misses the dialog ...
        RpcException blocked = expectError(ErrorCodes.MODAL_OPEN,
                () -> call("swing.key", "key", "ESCAPE", "target", ref(ui.frame)));
        assertEquals(ref(dialog[0]), blocked.error().data().get("dialog"));
        call("swing.key", "key", "ESCAPE", "target", ref(ui.frame), "force", true);
        settle();
        assertEquals(0, escapes.get());
        // ... so a key without a target goes into the dialog, where a real key press would end up
        String to = (String) call("swing.key", "key", "ESCAPE").get("dispatchedTo");
        Component target = (Component) ctx.handles.resolve(to);
        assertTrue(SwingUtilities.isDescendingFrom(target, dialog[0]), "the key went into the modal dialog: " + target);
        await("the dialog's ESCAPE binding ran", WAIT_MS, () -> escapes.get() == 1);
    }

    @Test
    void keyWithoutATargetStaysWithAFocusOwnerTheModalDialogDoesNotBlock() throws Exception {
        final JDialog[] dialog = new JDialog[1];
        final JFrame[] excluded = new JFrame[1];
        final JTextField[] field = new JTextField[1];
        onEdt(() -> {
            dialog[0] = new JDialog(ui.frame, "Modal", true);
            dialog[0].add(new JButton("Wait"));
            dialog[0].setBounds(660, 320, 220, 100);
            return null;
        });
        SwingUtilities.invokeLater(() -> dialog[0].setVisible(true)); // blocks in the modal loop
        await("modal dialog showing", WAIT_MS, () -> dialog[0].isShowing());
        onEdt(() -> {
            JFrame f = new JFrame("Excluded from modality");
            f.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
            field[0] = new JTextField(10);
            f.add(field[0]);
            f.setBounds(660, 450, 220, 80);
            f.setVisible(true);
            excluded[0] = f;
            return null;
        });
        await("the excluded frame has the focus", WAIT_MS, () -> excluded[0].isFocused());
        onEdt(() -> field[0].requestFocusInWindow());
        await("its field owns the focus", WAIT_MS, () -> field[0].hasFocus());
        assertEquals(map("dispatchedTo", ref(field[0])), call("swing.key", "key", "A"));
        settle();
        assertEquals("a", onEdt(() -> field[0].getText()));
    }

    @Test
    void keyWithoutATargetAnswersAnOptionPane() throws Exception {
        final AtomicInteger answer = new AtomicInteger(-99);
        SwingUtilities.invokeLater(() -> answer.set(JOptionPane.showConfirmDialog(ui.frame, "Proceed?", "Key",
                JOptionPane.YES_NO_OPTION)));
        await("option pane showing", WAIT_MS, () -> Windows.showingModalDialogs().size() == 1);
        call("swing.key", "key", "ESCAPE");
        await("ESCAPE closed the option pane", WAIT_MS, () -> answer.get() == JOptionPane.CLOSED_OPTION);
    }

    // -------------------------------------------------------------------------------------------- malformed input

    @Test
    void malformedInputIsRejectedCleanly() throws Exception {
        String notAComponent = ctx.handles.handleFor("just a string", "other");
        Object[][] bad = {
            {"swing.click", map("target", list("h1"))},
            {"swing.click", map("by", "okButton")},
            {"swing.click", map("by", list())},
            {"swing.click", map("target", ref(ui.ok), "rung", 5L)},
            {"swing.click", map("target", ref(ui.ok), "rung", map())},
            {"swing.click", map("target", ref(ui.ok), "focusTimeoutMs", "soon")},
            {"swing.click", map("target", notAComponent)},
            {"swing.type", map("target", ref(ui.entry), "text", list("a"))},
            {"swing.type", map("target", ref(ui.entry), "text", "a", "clear", "maybe")},
            {"swing.type", map("target", ref(ui.entry), "text", "a", "mode", map())},
            {"swing.type", map("text", "a")},
            {"swing.key", map("key", "ENTER", "modifiers", "CTRL")},
            {"swing.key", map("key", map())},
            {"swing.key", map("key", "ENTER", "target", list())},
            {"swing.snapshot", map("maxDepth", "deep")},
            {"swing.snapshot", map("root", notAComponent)},
            {"swing.snapshot", map("includeHidden", "perhaps")},
            {"swing.find", map("by", map("name", "entry"), "all", "maybe")},
            {"swing.find", map("by", list())},
            {"swing.find", map("by", map("name", "entry"), "root", notAComponent)},
            {"swing.capture", map("target", notAComponent)},
            {"swing.capture", map("mode", list())},
            {"swing.settle", map("quietMs", -5L)},
            {"swing.settle", map("timeoutMs", "x")},
            {"swing.dismiss", map("dialog", notAComponent)},
            {"swing.dismiss", map("close", "yes please")},
        };
        for (Object[] c : bad) {
            final String method = (String) c[0];
            @SuppressWarnings("unchecked")
            final Map<String, Object> params = (Map<String, Object>) c[1];
            RpcException e = expectError(ErrorCodes.INVALID_PARAMS, () -> ctx.call(method, params, CALL_MS));
            assertFalse(e.getMessage() == null || e.getMessage().isEmpty(), method + " " + params);
        }
        final char[] many = new char[Typing.MAX_DISPATCH_CHARS + 1];
        java.util.Arrays.fill(many, 'x');
        RpcException tooLong = expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.type", "target",
                ref(ui.entry), "text", new String(many), "mode", "DISPATCH"));
        assertEquals("too long", tooLong.error().data().get("reason"));
        // extreme but valid numbers are clamped, not rejected
        assertEquals(Boolean.FALSE, call("swing.snapshot", "maxNodes", Long.MAX_VALUE, "maxDepth", Long.MAX_VALUE)
                .get("truncated"));
        assertEquals(Boolean.TRUE, call("swing.settle", "timeoutMs", 60000L, "quietMs", 0L).get("settled"));
        assertEquals(fired("ACTION", "AUTO"),
                call("swing.click", "target", ref(ui.ok), "focusTimeoutMs", Long.MAX_VALUE));
        settle();
    }

    @Test
    void dismissChoosesButtonsCarefully() throws Exception {
        final AtomicInteger exact = new AtomicInteger();
        final AtomicInteger lower = new AtomicInteger();
        final JDialog[] dialogs = new JDialog[3];
        onEdt(() -> {
            JDialog twins = new JDialog(ui.frame, "Twins", false);
            twins.setLayout(new FlowLayout());
            twins.add(new JButton("Apply"));
            twins.add(new JButton("Apply"));
            JButton off = new JButton("Off");
            off.setEnabled(false);
            twins.add(off);
            twins.setBounds(660, 0, 300, 90);
            twins.setVisible(true);
            dialogs[0] = twins;

            JDialog cases = new JDialog(ui.frame, "Cases", false);
            cases.setLayout(new FlowLayout());
            JButton upper = new JButton("OK");
            upper.addActionListener(e -> exact.incrementAndGet());
            JButton small = new JButton("ok");
            small.addActionListener(e -> lower.incrementAndGet());
            cases.add(small);
            cases.add(upper);
            cases.setBounds(660, 100, 300, 90);
            cases.setVisible(true);
            dialogs[1] = cases;

            JDialog bare = new JDialog(ui.frame, "Bare", false);
            bare.setBounds(660, 200, 300, 90);
            bare.setVisible(true);
            dialogs[2] = bare;
            return null;
        });
        await("dialogs showing", WAIT_MS, () -> dialogs[0].isShowing() && dialogs[1].isShowing()
                && dialogs[2].isShowing());

        RpcException twins = expectError(ErrorCodes.AMBIGUOUS,
                () -> call("swing.dismiss", "dialog", ref(dialogs[0]), "button", "Apply"));
        assertEquals(2, arr(twins.error().data().get("candidates")).size());
        RpcException off = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.dismiss", "dialog", ref(dialogs[0]), "button", "Off"));
        assertEquals("disabled", off.error().data().get("reason"));
        RpcException several = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.dismiss", "dialog", ref(dialogs[0])));
        assertEquals(list("Apply", "Apply", "Off"), several.error().data().get("buttons"));

        // exact match first: "OK" is not ambiguous with "ok"; a different case of both is
        assertEquals(map("dismissedBy", "BUTTON_ACTION", "button", "OK"),
                call("swing.dismiss", "dialog", ref(dialogs[1]), "button", "OK"));
        await("the exact match was pressed", WAIT_MS, () -> exact.get() == 1);
        assertEquals(0, lower.get());
        expectError(ErrorCodes.AMBIGUOUS, () -> call("swing.dismiss", "dialog", ref(dialogs[1]), "button", "Ok"));

        RpcException none = expectError(ErrorCodes.INVALID_PARAMS,
                () -> call("swing.dismiss", "dialog", ref(dialogs[2])));
        assertEquals(list(), none.error().data().get("buttons"));
        assertEquals(map("dismissedBy", "CLOSE"), call("swing.dismiss", "dialog", ref(dialogs[2]), "close", true));
        settle();
        assertTrue(onEdt(() -> dialogs[2].isDisplayable()), "CLOSE only delivers WINDOW_CLOSING; a dialog that "
                + "ignores it stays (JDialog's default close operation hides it, it is never disposed for it)");
        assertFalse(onEdt(() -> dialogs[2].isVisible()), "JDialog's default HIDE_ON_CLOSE ran");
    }

    // ------------------------------------------------------------------------------------------------ window tap

    @Test
    void aTapInstalledLateReportsTheWindowsAlreadyShowingOnce() throws Exception {
        final JDialog early = onEdt(() -> {
            JDialog dialog = new JDialog(ui.frame, "Early", false);
            dialog.setBounds(660, 0, 200, 90);
            dialog.setVisible(true);
            return dialog;
        });
        await("early dialog showing", WAIT_MS, early::isShowing);
        settle();
        final FakeContext other = new FakeContext();
        final AtomicBoolean awtRunning = new AtomicBoolean();
        SwingModule late = new SwingModule(awtRunning::get);
        try {
            late.install(other);
            late.onTargetReady(other);
            assertFalse(late.tapInstalled());
            awtRunning.set(true);
            late.onTargetReady(other);
            assertTrue(late.tapInstalled());
            onEdt(() -> null); // the catch-up was posted before this
            List<Event> opened = other.events.since(0, TopicFilter.parse("window.opened"), 100);
            assertEquals(2, opened.size(), "the frame and the dialog, once each: " + opened);
            assertEquals(other.handles.handleFor(ui.frame, ComponentView.KIND), opened.get(0).payload().get("ref"));
            assertEquals(map("ref", other.handles.handleFor(early, ComponentView.KIND), "type", "dialog",
                    "title", "Early", "modal", false), opened.get(1).payload());

            final JDialog next = onEdt(() -> {
                JDialog dialog = new JDialog(ui.frame, "Next", false);
                dialog.setBounds(660, 100, 200, 90);
                dialog.setVisible(true);
                return dialog;
            });
            await("the next window is reported by the tap itself", WAIT_MS,
                    () -> other.events.since(0, TopicFilter.parse("window.opened"), 100).size() == 3);
            settle();
            opened = other.events.since(0, TopicFilter.parse("window.opened"), 100);
            assertEquals(3, opened.size(), "no duplicates: " + opened);
            assertEquals(other.handles.handleFor(next, ComponentView.KIND), opened.get(2).payload().get("ref"));
        } finally {
            late.uninstall();
            other.shutdown();
        }
    }
}
