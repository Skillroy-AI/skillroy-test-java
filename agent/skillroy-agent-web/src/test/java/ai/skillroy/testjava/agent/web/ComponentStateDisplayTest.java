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

package ai.skillroy.testjava.agent.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The web module's own modal check and component read against real windows (phase7-contracts.md section 4.3, W5;
 * section 13.1 item 3's display half): a showing application-modal dialog owned by the browser's frame blocks it; a
 * modeless one does not; a component inside the modal dialog is not blocked by it (the Swing module's heuristic); the
 * read reports showing and the size. Needs a
 * display: run with {@code ci/run-in-linux.sh ... -Dskillroy.headless=false}.
 */
class ComponentStateDisplayTest {

    private FakeAgentContext ctx;
    private SwingSeeds walk;
    private JFrame frame;
    private JPanel browserView;
    private JDialog dialog;

    @BeforeEach
    void show() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        ctx = new FakeAgentContext();
        walk = new SwingSeeds(ctx);
        onEdt(new Callable<Void>() {
            @Override
            public Void call() {
                frame = new JFrame("Loan desk");
                browserView = new JPanel();
                browserView.setPreferredSize(new java.awt.Dimension(320, 200));
                frame.getContentPane().add(browserView);
                frame.pack();
                frame.setVisible(true);
                return null;
            }
        });
    }

    @AfterEach
    void hide() throws Exception {
        if (ctx == null) {
            return;
        }
        onEdt(new Callable<Void>() {
            @Override
            public Void call() {
                if (dialog != null) {
                    dialog.dispose();
                }
                frame.dispose();
                return null;
            }
        });
        ctx.shutdown();
    }

    @Test
    void anUnblockedComponentReportsItsSizeAndShowing() throws Exception {
        ComponentState s = walk.state(browserView, 5000);
        assertTrue(s.showing);
        assertEquals(320, s.width);
        assertEquals(200, s.height);
        assertTrue(s.scale > 0);
        assertNull(s.blocker);
        assertSame(ComponentState.UNKNOWN, walk.state("not a component", 5000));
    }

    @Test
    void aShowingModalDialogBlocksTheFrameAndAModelessOneDoesNot() throws Exception {
        openDialog(Dialog.ModalityType.MODELESS);
        assertNull(walk.state(browserView, 5000).blocker, "a modeless dialog blocks nothing");
        closeDialog();
        openDialog(Dialog.ModalityType.APPLICATION_MODAL);
        ComponentState blocked = walk.state(browserView, 5000);
        assertSame(dialog, blocked.blocker);
        assertEquals("Confirm loan", blocked.blockerTitle);
        final JPanel inside = new JPanel();
        onEdt(new Callable<Void>() {
            @Override
            public Void call() {
                dialog.getContentPane().add(inside);
                dialog.validate();
                return null;
            }
        });
        assertNull(walk.state(inside, 5000).blocker, "a component inside the modal dialog is not blocked by it");
    }

    private void openDialog(final Dialog.ModalityType type) throws Exception {
        onEdt(new Callable<Void>() {
            @Override
            public Void call() {
                dialog = new JDialog(frame, "Confirm loan", type);
                dialog.setSize(200, 100);
                return null;
            }
        });
        // A modal dialog's setVisible(true) runs a secondary loop until it closes: shown from a posted task.
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                dialog.setVisible(true);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!onEdt(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(dialog.isShowing());
            }
        }).booleanValue()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the dialog did not show");
            }
        }
    }

    private void closeDialog() throws Exception {
        onEdt(new Callable<Void>() {
            @Override
            public Void call() {
                dialog.dispose();
                dialog = null;
                return null;
            }
        });
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        FutureTask<T> f = new FutureTask<T>(task);
        EventQueue.invokeLater(f);
        return f.get(10, TimeUnit.SECONDS);
    }
}
