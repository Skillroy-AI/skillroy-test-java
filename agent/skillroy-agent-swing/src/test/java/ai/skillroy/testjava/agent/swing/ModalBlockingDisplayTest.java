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

import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.await;
import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.fired;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Amendment A8: {@code swing.click}, {@code swing.type} and {@code swing.key} into a window a showing modal dialog
 * blocks fail with {@code MODAL_OPEN} (with the dialog's ref and title) unless {@code force: true}; the dialog itself
 * and windows it owns are not blocked; with nested modal dialogs the topmost blocker is named.
 */
class ModalBlockingDisplayTest extends DisplayTestBase {

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
        await("modal dialogs gone", WAIT_MS, () -> Windows.showingModalDialogs().isEmpty());
        settle();
    }

    @Test
    void inputIntoABlockedFrameIsRefusedUnlessForced() throws Exception {
        final AtomicInteger dialogClicks = new AtomicInteger();
        final JDialog[] parts = new JDialog[2];
        final JButton[] buttons = new JButton[2];
        final JTextField[] fields = new JTextField[1];
        onEdt(() -> {
            JDialog d = new JDialog(ui.frame, "Blocking", true);
            d.setLayout(new FlowLayout());
            buttons[0] = new JButton("Inside");
            buttons[0].addActionListener(e -> dialogClicks.incrementAndGet());
            fields[0] = new JTextField(8);
            d.add(buttons[0]);
            d.add(fields[0]);
            d.setBounds(660, 0, 260, 100);
            parts[0] = d;
            return null;
        });
        SwingUtilities.invokeLater(() -> parts[0].setVisible(true)); // blocks in the modal loop
        await("modal dialog showing", WAIT_MS, parts[0]::isShowing);
        // a modeless dialog the modal one owns is not blocked by it
        final AtomicInteger ownedClicks = new AtomicInteger();
        onEdt(() -> {
            JDialog owned = new JDialog(parts[0], "Owned palette", false);
            buttons[1] = new JButton("Palette");
            buttons[1].addActionListener(e -> ownedClicks.incrementAndGet());
            owned.add(buttons[1]);
            owned.setBounds(660, 120, 200, 80);
            owned.setVisible(true);
            parts[1] = owned;
            return null;
        });
        await("owned dialog showing", WAIT_MS, parts[1]::isShowing);
        settle();
        final String dialogRef = ref(parts[0]);
        final int ok = ui.okClicks.get();
        final int actions = ui.entryActions.get();

        for (String rung : new String[] {"AUTO", "ACTION", "COMPONENT", "DISPATCH"}) {
            RpcException e = expectError(ErrorCodes.MODAL_OPEN,
                    () -> call("swing.click", "target", ref(ui.ok), "rung", rung));
            assertEquals(map("dialog", dialogRef, "title", "Blocking", "ref", ref(ui.ok)), e.error().data(), rung);
        }
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.click", "by", map("id", "okButton")));
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.type", "target", ref(ui.entry), "text", "x"));
        expectError(ErrorCodes.MODAL_OPEN,
                () -> call("swing.type", "target", ref(ui.entry), "text", "x", "mode", "DISPATCH"));
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.key", "key", "ENTER", "target", ref(ui.entry)));
        expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.key", "key", "ENTER", "target", ref(ui.frame)));
        settle();
        assertEquals(ok, ui.okClicks.get(), "a refused click did nothing");
        assertEquals(actions, ui.entryActions.get(), "a refused key did nothing");

        // forced, the input goes through (and still runs while the modal loop owns the EDT)
        assertEquals(fired("ACTION", "AUTO"),
                call("swing.click", "target", ref(ui.ok), "force", true));
        await("forced click delivered", WAIT_MS, () -> ui.okClicks.get() == ok + 1);
        call("swing.type", "target", ref(ui.entry), "text", "forced", "enter", true, "force", true);
        await("forced type delivered", WAIT_MS, () -> "forced".equals(ui.lastEntry.get()));
        call("swing.key", "key", "ENTER", "target", ref(ui.entry), "force", true);
        await("forced key delivered", WAIT_MS, () -> ui.entryActions.get() == actions + 2);

        // the dialog itself and the window it owns are not blocked
        call("swing.click", "target", ref(buttons[0]));
        await("click inside the modal dialog", WAIT_MS, () -> dialogClicks.get() == 1);
        call("swing.type", "target", ref(fields[0]), "text", "inside");
        call("swing.key", "key", "A", "target", ref(fields[0]));
        call("swing.click", "target", ref(buttons[1]), "rung", "DISPATCH");
        await("click in the owned palette", WAIT_MS, () -> ownedClicks.get() == 1);
        settle();
        assertEquals("insidea", onEdt(() -> fields[0].getText()));

        // once the dialog is gone the frame takes input again
        call("swing.dismiss", "dialog", dialogRef, "close", true);
        await("modal dialog closed", WAIT_MS, () -> !parts[0].isShowing());
        call("swing.click", "target", ref(ui.ok));
        await("unblocked click delivered", WAIT_MS, () -> ui.okClicks.get() == ok + 2);
    }

    @Test
    void anyUnownedWindowIsBlockedAndTheTopmostBlockerIsNamed() throws Exception {
        final JFrame[] other = new JFrame[1];
        final JButton[] otherButton = new JButton[1];
        onEdt(() -> {
            JFrame f = new JFrame("Second frame");
            otherButton[0] = new JButton("Second");
            f.add(otherButton[0]);
            f.setBounds(660, 300, 200, 80);
            f.setVisible(true);
            other[0] = f;
            return null;
        });
        await("second frame showing", WAIT_MS, other[0]::isShowing);
        final JDialog[] modal = new JDialog[2];
        final JButton[] outerButton = new JButton[1];
        onEdt(() -> {
            modal[0] = new JDialog(ui.frame, "Outer", Dialog.ModalityType.APPLICATION_MODAL);
            outerButton[0] = new JButton("Outer button");
            modal[0].add(outerButton[0]);
            modal[0].setBounds(660, 0, 220, 90);
            return null;
        });
        SwingUtilities.invokeLater(() -> modal[0].setVisible(true));
        await("outer modal showing", WAIT_MS, modal[0]::isShowing);
        settle();
        // a frame the dialog does not own is blocked too (application modality)
        RpcException e = expectError(ErrorCodes.MODAL_OPEN, () -> call("swing.click", "target", ref(otherButton[0])));
        assertEquals(ref(modal[0]), e.error().data().get("dialog"));
        assertEquals(fired("ACTION", "AUTO"), call("swing.click", "target", ref(outerButton[0])));

        onEdt(() -> {
            modal[1] = new JDialog(modal[0], "Inner", true);
            modal[1].add(new JButton("Inner button"));
            modal[1].setBounds(660, 120, 220, 90);
            return null;
        });
        SwingUtilities.invokeLater(() -> modal[1].setVisible(true));
        await("inner modal showing", WAIT_MS, modal[1]::isShowing);
        settle();
        // the outer dialog is now blocked by the inner one, which also blocks the frames
        Map<String, Object> data = expectError(ErrorCodes.MODAL_OPEN,
                () -> call("swing.click", "target", ref(outerButton[0]))).error().data();
        assertEquals(ref(modal[1]), data.get("dialog"));
        assertEquals("Inner", data.get("title"));
        assertEquals(ref(modal[1]), expectError(ErrorCodes.MODAL_OPEN,
                () -> call("swing.click", "target", ref(ui.ok))).error().data().get("dialog"));
        assertEquals(fired("ACTION", "AUTO"), call("swing.click", "by", map("text", "Inner button")));
    }
}
