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

import ai.skillroy.testjava.agent.api.spi.DialogTextExtractor;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Dialog;
import java.awt.Window;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
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
 * Modal dialogs: a stock JOptionPane shown from invokeLater and a custom undecorated modal dialog that paints its
 * text. Proves dialog reading, dismissal, the window events, and that reads and posted actions keep working (and never
 * deadlock) while a modal dialog's secondary loop owns the EDT.
 */
class DialogDisplayTest extends DisplayTestBase {

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
        await("dialogs gone", WAIT_MS, () -> Windows.showingDialogs().isEmpty());
        settle();
    }

    @Test
    void optionPaneIsReadAndAnsweredThroughItsButton() throws Exception {
        final AtomicInteger answer = new AtomicInteger(-99);
        long since = ctx.events.lastSeq();
        SwingUtilities.invokeLater(() -> answer.set(JOptionPane.showConfirmDialog(ui.frame, "Delete the item?",
                "Confirm", JOptionPane.YES_NO_OPTION)));
        Map<String, Object> d = awaitOneDialog();
        assertEquals("Confirm", d.get("title"));
        assertEquals(Boolean.TRUE, d.get("modal"));
        assertEquals("Delete the item?", d.get("text"));
        assertEquals("generic", d.get("extractor"));
        List<Object> buttons = arr(d.get("buttons"));
        assertEquals(2, buttons.size());
        assertEquals("Yes", obj(buttons.get(0)).get("text"));
        assertEquals("No", obj(buttons.get(1)).get("text"));
        assertTrue(obj(buttons.get(0)).get("ref") instanceof String);
        String dialogRef = (String) d.get("ref");

        await("window.opened for the dialog", WAIT_MS, () -> events("window.opened", since).stream()
                .anyMatch(e -> dialogRef.equals(e.payload().get("ref")) && Boolean.TRUE.equals(e.payload()
                        .get("modal"))));
        Map<String, Object> state = call("swing.edtState");
        assertEquals(list(dialogRef), state.get("modalDialogs"));
        assertEquals(Boolean.FALSE, state.get("edtBlocked"));

        // the frame is blocked: a click there is refused unless forced, and a forced one still runs in the modal loop
        int okClicks = ui.okClicks.get();
        RpcException blocked = expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.click", "target", ref(ui.ok)));
        assertEquals(dialogRef, blocked.error().data().get("dialog"));
        assertEquals("Confirm", blocked.error().data().get("title"));
        call("swing.click", "target", ref(ui.ok), "force", true);
        await("frame button clicked while the modal dialog is up", WAIT_MS, () -> ui.okClicks.get() == okClicks + 1);
        assertEquals(2, arr(call("swing.snapshot").get("windows")).size());
        assertEquals(Boolean.TRUE, call("swing.settle").get("settled"));

        RpcException missing = expectError(ErrorCodes.NOT_FOUND, () -> call("swing.dismiss", "button", "Maybe"));
        assertEquals(list("Yes", "No"), missing.error().data().get("buttons"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.dismiss", "button", "No", "close", true));

        assertEquals(map("dismissedBy", "BUTTON_ACTION", "button", "No"), call("swing.dismiss", "button", "no"));
        await("showConfirmDialog returned NO", WAIT_MS, () -> answer.get() == JOptionPane.NO_OPTION);
        await("window.closed for the dialog", WAIT_MS, () -> events("window.closed", since).stream()
                .anyMatch(e -> dialogRef.equals(e.payload().get("ref"))));
        assertEquals(list(), call("swing.dialogs").get("dialogs"));
    }

    @Test
    void closeDeliversWindowClosingSoTheDialogAnswersItself() throws Exception {
        final AtomicInteger answer = new AtomicInteger(-99);
        SwingUtilities.invokeLater(() -> answer.set(JOptionPane.showConfirmDialog(ui.frame, "Discard changes?",
                "Discard", JOptionPane.YES_NO_CANCEL_OPTION)));
        String dialogRef = (String) awaitOneDialog().get("ref");
        assertEquals(map("dismissedBy", "CLOSE"), call("swing.dismiss", "dialog", dialogRef, "close", true));
        await("showConfirmDialog returned CLOSED_OPTION", WAIT_MS, () -> answer.get() == JOptionPane.CLOSED_OPTION);
    }

    @Test
    void dismissWithoutAButtonPressesTheOnlyOne() throws Exception {
        final AtomicBoolean returned = new AtomicBoolean();
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(ui.frame, "<html>Saved <i>all</i> items</html>", "Saved",
                    JOptionPane.INFORMATION_MESSAGE);
            returned.set(true);
        });
        Map<String, Object> d = awaitOneDialog();
        assertEquals("Saved all items", d.get("text"), "HTML message reduced to text");
        assertEquals(map("dismissedBy", "BUTTON_ACTION", "button", "OK"), call("swing.dismiss"));
        await("showMessageDialog returned", WAIT_MS, returned::get);
    }

    @Test
    void dismissWithoutAModalDialogIsNotFound() throws Exception {
        expectError(ErrorCodes.NOT_FOUND, () -> call("swing.dismiss"));
        expectError(ErrorCodes.STALE_HANDLE, () -> call("swing.dismiss", "dialog", "h777777"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.dismiss", "dialog", ref(ui.ok)));
        expectError(ErrorCodes.INVALID_PARAMS, () -> call("swing.dismiss", "dialog", ref(ui.frame), "close", true));
        assertTrue(onEdt(ui.frame::isDisplayable), "a frame is never closed through swing.dismiss");
    }

    @Test
    void genericExtractionReadsLabelsInReadingOrder() throws Exception {
        final JDialog d = onEdt(() -> {
            JDialog dialog = new JDialog(ui.frame, "Notice", false);
            dialog.setLayout(null);
            JLabel second = new JLabel("Second line");
            second.setBounds(10, 50, 200, 20);
            JLabel first = new JLabel("First line");
            first.setBounds(10, 10, 200, 20);
            JButton close = new JButton("Close");
            close.setBounds(10, 80, 100, 25);
            JButton icon = new JButton(new TestWidgets.SquareIcon());
            icon.setBounds(120, 80, 30, 25);
            dialog.add(second);
            dialog.add(first);
            dialog.add(close);
            dialog.add(icon);
            dialog.setBounds(660, 0, 260, 150);
            dialog.setVisible(true);
            return dialog;
        });
        Map<String, Object> info = awaitOneDialog();
        assertEquals(ref(d), info.get("ref"));
        assertEquals("Notice", info.get("title"));
        assertEquals(Boolean.FALSE, info.get("modal"));
        assertEquals("First line\nSecond line", info.get("text"));
        assertEquals(1, arr(info.get("buttons")).size(), "only buttons with text");
        expectError(ErrorCodes.NOT_FOUND, () -> call("swing.dismiss"));
    }

    @Test
    void customPaintedModalNeedsItsExtractorAndStillAcceptsActions() throws Exception {
        final TestWidgets.PaintedPrompt prompt = onEdt(() -> new TestWidgets.PaintedPrompt(ui.frame,
                "Return needs approval", "Yes", "No"));
        long since = ctx.events.lastSeq();
        SwingUtilities.invokeLater(() -> prompt.setVisible(true)); // blocks in the modal loop
        await("prompt showing", WAIT_MS, prompt::isShowing);

        extractorRegistration.close();
        try {
            Map<String, Object> generic = awaitOneDialog();
            assertEquals("generic", generic.get("extractor"));
            assertNull(generic.get("text"), "painted text is invisible to the generic extractor");
            assertEquals(list(), generic.get("buttons"));
        } finally {
            extractorRegistration = ctx.spi.register(DialogTextExtractor.class, new TestWidgets.PromptExtractor());
        }
        Map<String, Object> d = awaitOneDialog();
        assertEquals("test-prompt", d.get("extractor"));
        assertEquals("Return needs approval", d.get("text"));
        assertEquals("", d.get("title"), "no title from the extractor: the dialog's own (empty) title");
        assertEquals(Boolean.TRUE, d.get("modal"));
        List<Object> buttons = arr(d.get("buttons"));
        assertEquals("Yes", obj(buttons.get(0)).get("text"));
        assertTrue(obj(buttons.get(0)).get("ref") instanceof String, "button ref found through the NameEnricher");
        assertTrue(events("window.opened", since).stream().anyMatch(e -> ref(prompt).equals(e.payload()
                .get("ref"))));

        // reads and posted (forced) actions while the modal dialog is up never deadlock
        int okClicks = ui.okClicks.get();
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.click", "by", map("text", "OK", "role", "button")));
        call("swing.click", "by", map("text", "OK", "role", "button"), "force", true);
        await("frame button clicked under the modal prompt", WAIT_MS, () -> ui.okClicks.get() == okClicks + 1);
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.type", "target", ref(ui.entry), "text", "blocked"));
        call("swing.type", "target", ref(ui.entry), "text", "under modal", "force", true);
        assertEquals(Boolean.TRUE, call("swing.settle").get("settled"));
        assertEquals("under modal", onEdt(() -> ui.entry.getText()));
        Map<String, Object> shot = call("swing.capture", "target", ref(prompt));
        assertEquals(320L, shot.get("width"));
        assertEquals(120L, shot.get("height"));
        assertEquals(list(ref(prompt)), call("swing.edtState").get("modalDialogs"));

        assertEquals(map("dismissedBy", "BUTTON_ACTION", "button", "Yes"), call("swing.dismiss", "button", "YES"));
        await("prompt answered", WAIT_MS, () -> "Yes".equals(prompt.answer.get()));
        await("window.closed for the prompt", WAIT_MS, () -> events("window.closed", since).stream()
                .anyMatch(e -> ref(prompt).equals(e.payload().get("ref"))));
        assertFalse(onEdt(prompt::isShowing));
    }

    @Test
    void topmostModalIsTheNestedDialog() throws Exception {
        final TestWidgets.PaintedPrompt prompt = onEdt(() -> new TestWidgets.PaintedPrompt(ui.frame,
                "Outer prompt", "Yes", "No"));
        final AtomicBoolean innerReturned = new AtomicBoolean();
        SwingUtilities.invokeLater(() -> prompt.setVisible(true));
        await("prompt showing", WAIT_MS, prompt::isShowing);
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(prompt, "Inner message");
            innerReturned.set(true);
        });
        await("two dialogs", WAIT_MS, () -> Windows.showingDialogs().size() == 2);
        assertEquals(map("dismissedBy", "BUTTON_ACTION", "button", "OK"), call("swing.dismiss"));
        await("inner dialog answered", WAIT_MS, innerReturned::get);
        assertTrue(onEdt(prompt::isShowing), "the outer prompt is still waiting");
        call("swing.dismiss", "button", "No");
        await("outer prompt answered", WAIT_MS, () -> "No".equals(prompt.answer.get()));
    }

    /** Polls swing.dialogs until exactly one dialog is listed, and returns it. */
    private static Map<String, Object> awaitOneDialog() {
        final Object[] found = new Object[1];
        await("one dialog listed", WAIT_MS, () -> {
            try {
                List<Object> dialogs = arr(call("swing.dialogs").get("dialogs"));
                if (dialogs.size() == 1) {
                    found[0] = dialogs.get(0);
                    return true;
                }
                return false;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        return obj(found[0]);
    }
}
