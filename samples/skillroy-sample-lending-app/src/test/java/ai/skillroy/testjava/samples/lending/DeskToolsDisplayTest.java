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

import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Panel;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.WindowConstants;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The "Desk tools" window (phase8-contracts.md 12.1): opened with {@code lending.tools=true} after the desk frame, not
 * owned by it, at (624, 400) 400 x 360, never taking the focus; its controls are named as the contract's table says
 * and each counts what it receives; the close box counts a close request and closes nothing; without the option no
 * second window opens. Needs a display (the AWT controls cannot be built headless); run under
 * {@code ci/run-in-linux.sh ... -Dskillroy.headless=false}.
 */
class DeskToolsDisplayTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private LendingApp app;

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

    private LendingApp launch(boolean tools) throws Exception {
        app = LendingApp.launch(LendingOptions.defaults().dbName("tools_" + COUNTER.incrementAndGet()).ui(true)
                .tools(tools));
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

    private static Component named(Container root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName())) {
                return c;
            }
            if (c instanceof Container) {
                Component found = named((Container) c, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void withoutTheOptionNoSecondWindowOpens() throws Exception {
        launch(false);
        assertNull(app.tools());
        assertFalse(onEdt(() -> {
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && DeskTools.TITLE.equals(w instanceof JFrame ? ((JFrame) w).getTitle() : null)) {
                    return true;
                }
            }
            return false;
        }));
    }

    @Test
    void theWindowOpensBesideTheDeskWithTheContractsControls() throws Exception {
        launch(true);
        final DeskTools tools = app.tools();
        assertNotNull(tools);
        onEdt(() -> {
            assertTrue(tools.isShowing());
            assertEquals(DeskTools.TITLE, tools.getTitle());
            assertEquals(DeskTools.NAME, tools.getName());
            assertNull(tools.getOwner(), "not owned by the desk frame");
            assertFalse(tools.isAutoRequestFocus());
            assertEquals(WindowConstants.DO_NOTHING_ON_CLOSE, tools.getDefaultCloseOperation());
            assertEquals(new java.awt.Rectangle(624, 400, 400, 360), tools.getBounds());
            for (String name : Arrays.asList("closeMenu", "closeDesk", "printTestSlip", "toolsNote",
                    "printSlipVisible", "printSlipHidden", "visiblePane", "collapsedPane", "toolsScroll",
                    "reprintSlip", "scanner", "scanButton", "lidOpen", "rereadCard", "openDepositBox")) {
                assertNotNull(named(tools.getContentPane(), name), name);
            }
            assertSame(tools.closeDesk(), tools.getRootPane().getDefaultButton(), "the default button");
            assertTrue(named(tools.getContentPane(), "scanner") instanceof Panel, "a heavyweight panel");
            assertTrue(named(tools.getContentPane(), "scanButton") instanceof Button);
            assertTrue(named(tools.getContentPane(), "lidOpen") instanceof Checkbox);
            assertEquals(0, named(tools.getContentPane(), "collapsedPane").getWidth(), "a zero-width pane");
            Component reprint = named(tools.getContentPane(), "reprintSlip");
            assertTrue(((JButton) reprint).getVisibleRect().isEmpty(), "scrolled out of the viewport");
            JScrollPane scroll = (JScrollPane) named(tools.getContentPane(), "toolsScroll");
            assertTrue(scroll.getViewport().getHeight() <= 124, "a short viewport");
            IconKey key = null;
            for (Component c : ((Container) named(tools.getContentPane(), "closeMenu")).getComponents()) {
                if (c instanceof IconKey) {
                    key = (IconKey) c;
                }
            }
            assertNotNull(key, "the CLOSE key");
            assertEquals("CLOSE", key.actionId());
            return null;
        });
    }

    @Test
    void everyControlCountsWhatItReceivesAndTheCloseBoxClosesNothing() throws Exception {
        launch(true);
        final DeskTools tools = app.tools();
        final DeskTools.Counters n = tools.counters();
        onEdt(() -> {
            Container c = tools.getContentPane();
            ((JButton) named(c, "closeDesk")).doClick();
            ((JButton) named(c, "printTestSlip")).doClick();
            ((JButton) named(c, "printSlipVisible")).doClick();
            ((JButton) named(c, "printSlipHidden")).doClick();
            ((JButton) named(c, "reprintSlip")).doClick();
            ((JButton) named(c, "rereadCard")).doClick();
            ((JButton) named(c, "openDepositBox")).doClick();
            Button scan = (Button) named(c, "scanButton");
            scan.dispatchEvent(new ActionEvent(scan, ActionEvent.ACTION_PERFORMED, scan.getActionCommand()));
            Checkbox lid = (Checkbox) named(c, "lidOpen");
            lid.setState(true);
            lid.dispatchEvent(new ItemEvent(lid, ItemEvent.ITEM_STATE_CHANGED, lid.getLabel(), ItemEvent.SELECTED));
            for (Component k : ((Container) named(c, "closeMenu")).getComponents()) {
                if (k instanceof IconKey) {
                    ((IconKey) k).perform();
                }
            }
            tools.dispatchEvent(new WindowEvent(tools, WindowEvent.WINDOW_CLOSING));
            return null;
        });
        Map<String, Object> m = n.toMap();
        assertEquals(Arrays.asList("closeRequests", "testSlips", "slipsVisible", "slipsHidden", "reprints", "scans",
                "lidOpen", "lidChanges", "rereads", "depositBoxOpens"), new java.util.ArrayList<String>(m.keySet()));
        assertEquals(3L, m.get("closeRequests"), "the button, the key and the close box");
        for (String k : Arrays.asList("testSlips", "slipsVisible", "slipsHidden", "reprints", "scans", "lidChanges",
                "rereads", "depositBoxOpens")) {
            assertEquals(1L, m.get(k), k);
        }
        assertEquals(Boolean.TRUE, m.get("lidOpen"));
        assertTrue(onEdt(() -> tools.isShowing()), "the close box closed nothing");
        assertTrue(app.engine().isRunning(), "the desk keeps running");
    }
}
