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

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module against awkward hosts and awkward input, without a display: the deepest snapshot still fits the wire, a
 * runaway regex cannot hold the EDT, a widget whose own code throws does not fail a snapshot, and the tap thread
 * waits for AWT and always ends.
 */
class RobustnessTest {

    private static final long WAIT_MS = 30000;

    private final FakeContext ctx = new FakeContext();

    @AfterEach
    void tearDown() {
        ctx.shutdown();
    }

    // ------------------------------------------------------------------------------------------- snapshot depth

    @Test
    void deepestSnapshotWithATableAtTheBottomFitsTheWire() throws Exception {
        final JPanel top = onEdt(() -> {
            JPanel root = new JPanel();
            JPanel p = root;
            for (int i = 0; i < 120; i++) {
                JPanel child = new JPanel();
                p.add(new JTable(new Object[][] {{"a", "b"}}, new Object[] {"A", "B"}));
                p.add(child);
                p = child;
            }
            return root;
        });
        Map<String, Object> value = onEdt(() -> {
            SnapshotWalker walker = new SnapshotWalker(new ComponentView(ctx), SwingPrimitives.MAX_DEPTH, 100000, true);
            Map<String, Object> v = new LinkedHashMap<String, Object>();
            v.put("seq", 0L);
            Map<String, Object> node = walker.walk(top);
            v.put("truncated", walker.truncated());
            v.put("windows", list(node));
            return v;
        });
        assertEquals(Boolean.TRUE, value.get("truncated"));
        Map<String, Object> node = obj(arr(value.get("windows")).get(0));
        Map<String, Object> deepestTable = null;
        for (int level = 1; level <= SwingPrimitives.MAX_DEPTH; level++) {
            List<Object> children = arr(node.get("children"));
            assertEquals(2, children.size(), "level " + level);
            deepestTable = obj(children.get(0));
            node = obj(children.get(1));
        }
        assertFalse(node.containsKey("children"), "nothing below the cap");
        assertEquals(list(list("a", "b")), obj(deepestTable.get("value")).get("cells"), "the deepest table has cells");
        // the wire types take it, and so does the writer once it is wrapped in a JSON-RPC response
        RpcResult result = new RpcResult(value, Collections.<Event>emptyList(), 0, 0, 0, "t");
        String json = Json.write(map("jsonrpc", "2.0", "id", 1L, "result", result.toJson()));
        assertTrue(json.length() > 1000);
    }

    // ---------------------------------------------------------------------------------------------------- regex

    @Test
    void runawayRegexIsStoppedInsteadOfHoldingTheEdt() throws Exception {
        final JPanel root = onEdt(() -> {
            JPanel p = new JPanel();
            p.add(new JLabel("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!"));
            p.add(new JLabel("plain"));
            return p;
        });
        // the back reference keeps newer JDKs from memoising their way out of the nested quantifier
        final Locator runaway = Locator.parse(map("text", map("regex", "(a+)+\\1?x")), "by");
        long start = System.nanoTime();
        RpcException e = expectError(ErrorCodes.INVALID_PARAMS,
                () -> onEdt(() -> new LocatorEngine(new ComponentView(ctx)).find(runaway, root, true)));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals("regex too slow", e.error().data().get("reason"));
        assertEquals("by.text.regex", e.error().data().get("param"));
        assertTrue(ms >= 900, "it had its whole budget: " + ms + " ms");
        assertTrue(ms < 20000, "stopped near its " + Locator.RegexBudget.TOTAL_MS + " ms budget: " + ms + " ms");
        // an ordinary regex over the same tree is unaffected
        final Locator fine = Locator.parse(map("text", map("regex", "^pl.*n$")), "by");
        assertEquals(1, onEdt(() -> new LocatorEngine(new ComponentView(ctx)).find(fine, root, true)).size());
    }

    @Test
    void regexMatchesLongTextsAndSubSequences() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("line ").append(i).append('\n');
        }
        String text = sb.toString();
        assertTrue(Locator.parse(map("text", map("regex", "line 19999$")), "by").match().test(text));
        assertTrue(Locator.parse(map("text", map("regex", "(?m)^line (\\d+)$")), "by").match().test(text));
        assertTrue(Locator.parse(map("text", map("regex", "\\bline 7\\b")), "by").match().test(text));
        assertFalse(Locator.parse(map("text", map("regex", "^line 7$")), "by").match().test(text));
    }

    // -------------------------------------------------------------------------------------------- broken widgets

    @Test
    void aWidgetWhoseOwnCodeThrowsDoesNotFailTheSnapshot() throws Exception {
        final Component[] parts = new Component[3];
        final JPanel root = onEdt(() -> {
            JPanel p = new JPanel();
            JTable brokenTable = new JTable(new AbstractTableModel() {
                private static final long serialVersionUID = 1L;

                @Override
                public int getRowCount() {
                    return 2;
                }

                @Override
                public int getColumnCount() {
                    return 2;
                }

                @Override
                public Object getValueAt(int row, int column) {
                    throw new IllegalStateException("model is broken");
                }
            });
            JTextField brokenField = new JTextField() {
                private static final long serialVersionUID = 1L;

                @Override
                public String getText() {
                    throw new IllegalStateException("document is broken");
                }
            };
            brokenField.setName("brokenField");
            JButton fine = new JButton("Fine");
            p.add(brokenTable);
            p.add(brokenField);
            p.add(fine);
            parts[0] = brokenTable;
            parts[1] = brokenField;
            parts[2] = fine;
            return p;
        });
        Map<String, Object> node = onEdt(() -> new SnapshotWalker(new ComponentView(ctx), 40, 100, true).walk(root));
        List<Object> children = arr(node.get("children"));
        assertEquals(3, children.size());
        assertEquals("table", obj(children.get(0)).get("role"));
        assertFalse(obj(children.get(0)).containsKey("value"), "no value from a broken model");
        assertEquals("brokenField", obj(children.get(1)).get("name"));
        assertNull(obj(children.get(1)).get("text"));
        assertEquals("Fine", obj(children.get(2)).get("text"));
        assertEquals(1, ctx.log.warnings().size(), "logged once per call: " + ctx.log.warnings());

        // a text search walks past the broken field
        final Locator loc = Locator.parse(map("text", "Fine"), "by");
        assertEquals(Collections.singletonList(parts[2]),
                onEdt(() -> new LocatorEngine(new ComponentView(ctx)).find(loc, root, true)));
    }

    @Test
    void anHtmlNameWithNoTextFallsBackToTheComponentName() throws Exception {
        final JButton button = new JButton("<html><img src='x.png'></html>");
        button.setName("imageButton");
        assertEquals("imageButton", onEdt(() -> new ComponentView(ctx).name(button)));
    }

    // ------------------------------------------------------------------------------------------------ tap thread

    @Test
    void tapThreadWaitsForAwtThenEnds() throws Exception {
        final AtomicBoolean awtRunning = new AtomicBoolean();
        SwingModule module = new SwingModule(awtRunning::get);
        try {
            awaitNoTapThread();
            module.install(ctx);
            Thread tapThread = thread(SwingModule.TAP_THREAD);
            assertNotNull(tapThread, "install starts the tap thread while AWT is not running");
            assertTrue(tapThread.isDaemon());
            module.onTargetReady(ctx);
            assertFalse(module.tapInstalled(), "AWT is never started on the application's behalf");
            assertTrue(tapThread.isAlive());
            final int looks = module.awtChecks();
            await("the watcher keeps looking", WAIT_MS, () -> module.awtChecks() >= looks + 3);
            assertTrue(tapThread.isAlive());

            awtRunning.set(true);
            await("tap thread ended once AWT runs", WAIT_MS, () -> !tapThread.isAlive());
            boolean headless = GraphicsEnvironment.isHeadless();
            assertEquals(!headless, module.tapInstalled());
            assertEquals(headless ? CapabilityState.UNAVAILABLE : CapabilityState.OK,
                    ctx.capabilities.get(SwingModule.CAP_TREE).state(), "confirmed with AWT");
        } finally {
            module.uninstall();
        }
        assertFalse(module.tapInstalled());
    }

    @Test
    void aModuleCanBeInstalledAgainAfterUninstall() throws Exception {
        final AtomicBoolean awtRunning = new AtomicBoolean();
        SwingModule module = new SwingModule(awtRunning::get);
        awaitNoTapThread();
        module.install(ctx);
        module.uninstall();
        awaitNoTapThread();
        assertTrue(ctx.primitives.list().isEmpty());
        try {
            module.install(ctx);
            assertEquals(11, ctx.primitives.list().size());
            awtRunning.set(true);
            module.onTargetReady(ctx);
            assertEquals(!GraphicsEnvironment.isHeadless(), module.tapInstalled(), "the second install is a full one");
        } finally {
            module.uninstall();
        }
    }

    @Test
    void uninstallEndsAWaitingTapThread() throws Exception {
        SwingModule module = new SwingModule(() -> false);
        awaitNoTapThread();
        module.install(ctx);
        Thread tapThread = thread(SwingModule.TAP_THREAD);
        assertNotNull(tapThread);
        module.uninstall();
        await("tap thread ended by uninstall", WAIT_MS, () -> !tapThread.isAlive());
        assertFalse(module.tapInstalled());
        assertTrue(ctx.primitives.list().isEmpty());
    }

    private static void awaitNoTapThread() {
        await("no tap thread left over from an earlier test", WAIT_MS, () -> thread(SwingModule.TAP_THREAD) == null);
    }

    private static Thread thread(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && name.equals(t.getName())) {
                return t;
            }
        }
        return null;
    }
}
