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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.QuiescentPoint;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.agent.data.DataModule;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.samples.lending.EngineListener;
import ai.skillroy.testjava.samples.lending.LendingOptions;
import ai.skillroy.testjava.samples.lending.LendingState;
import ai.skillroy.testjava.samples.lending.Seed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static ai.skillroy.testjava.samples.lending.agent.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase8-contracts.md 13.1 row 13 (ET): {@code lending.engine.view} on the engine thread at its idle point, refused
 * while a prompt is open, admitted by the time left before the idle lock, inert after uninstall; the report reader
 * {@code lending-report} masked by the derived column names and stopped on its lane when its caller's time runs out.
 */
class EngineViewAndReportTest {

    private LendingFixture lending;

    @AfterEach
    void stop() {
        if (lending != null) {
            lending.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private QuiescentPoint point() {
        return lending.ctx.spi.first(ThreadPolicyProvider.class).quiescentPoint();
    }

    private long ran() {
        return ((Number) point().stats().get("ran")).longValue();
    }

    @Test
    void theViewReadsTheEngineConfinedStateOnTheEngineThread() throws Exception {
        lending = LendingFixture.start();
        lending.signOn();
        long before = ran();
        lending.send("Key.QTY", null);
        Map<String, Object> quantity = map(lending.ctx.call("lending.engine.view", args()));
        assertEquals("QUANTITY", quantity.get("step"), "the quantity sub-step, in no published snapshot");
        lending.send("Entry", "3");
        Map<String, Object> view = map(lending.ctx.call("lending.engine.view", args()));
        assertEquals(Arrays.asList("state", "step", "pendingQty", "returnMode", "lockedFrom", "lineCount",
                "idleLockRemainingMs"), new ArrayList<String>(view.keySet()));
        assertEquals("IDLE", view.get("state"));
        assertEquals("NONE", view.get("step"));
        assertEquals("3", view.get("pendingQty"), "only the engine thread at its idle point can read it");
        assertEquals(Boolean.FALSE, view.get("returnMode"));
        assertNull(view.get("lockedFrom"));
        assertEquals(0L, view.get("lineCount"));
        assertNull(view.get("idleLockRemainingMs"), "the fixture's desk has no idle lock");
        assertEquals(before + 2, ran(), "both views ran at the point");
    }

    @Test
    void withAPromptOpenTheViewIsNotReadyAndAnswersAgainOnceItIsAnswered() throws Exception {
        lending = LendingFixture.start();
        lending.signOn();
        assertEquals("prompt.waiting", lending.send("Barcode", "5000000000086").topic(), "the induction check");
        long start = System.nanoTime();
        RpcException e = lending.ctx.fails("lending.engine.view", args());
        long tookMs = (System.nanoTime() - start) / 1000000L;
        assertEquals(ErrorCodes.NOT_READY, e.error().code());
        assertEquals("no quiescent point within 5000 ms", e.error().data().get("reason"));
        assertTrue(tookMs < 7000, "within its time: " + tookMs);
        lending.ctx.call("lending.prompt.answer", args("choice", "Yes"));
        Map<String, Object> view = map(lending.ctx.call("lending.engine.view", args()));
        assertEquals("LOAN", view.get("state"));
        assertEquals(1L, view.get("lineCount"));
        assertEquals(1L, ((Number) point().stats().get("notReady")).longValue());
    }

    /**
     * The module-level home of the admission proof (it needs a desk with a 1 s idle lock, which no shared end-to-end
     * desk can carry): with an idle tick of 90 ms, a call queued at an idle point with less than 105 ms left before the
     * lock (after that point's own run) finds at the next point, 90 ms or more later, less than its 20 ms budget, or no
     * point at all before the lock, and runs only at the first point after the desk locked.
     */
    @Test
    void aCallWithLessThanItsBudgetLeftBeforeTheIdleLockRunsOnlyAfterTheLock() throws Exception {
        lending = LendingFixture.start(LendingOptions.defaults().seed(Seed.A).idleLockMillis(1000)
                .idleTickMillis(90));
        lending.signOn();
        final AtomicBoolean fired = new AtomicBoolean();
        final AtomicReference<Object> outcome = new AtomicReference<Object>();
        final AtomicReference<Long> queuedAt = new AtomicReference<Long>();
        EngineListener trigger = new EngineListener() {
            @Override
            public void idle(LendingState state, long availableMs) {
                if (state == LendingState.IDLE && availableMs > 0 && availableMs < 105
                        && fired.compareAndSet(false, true)) {
                    queuedAt.set(Long.valueOf(availableMs));
                    Thread caller = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                outcome.set(lending.ctx.call("lending.engine.view", args()));
                            } catch (Exception e) {
                                outcome.set(e);
                            }
                        }
                    }, "lending-test-view-caller");
                    caller.setDaemon(true);
                    caller.start();
                }
            }
        };
        assertTrue(lending.app.engine().isAwaitingInput());
        lending.app.engine().addListener(trigger);
        try {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (outcome.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(outcome.get() instanceof Map, String.valueOf(outcome.get()));
            Map<String, Object> view = map(outcome.get());
            assertEquals("LOCKED", view.get("state"), "not run at the point with " + queuedAt.get()
                    + " ms left before the lock, nor at the next with less than its 20 ms budget");
            assertEquals("IDLE", view.get("lockedFrom"));
        } finally {
            if (lending.app.engine().isAwaitingInput()) {
                lending.app.engine().removeListener(trigger);
            }
        }
    }

    @Test
    void afterUninstallThePointIsClosedAndTheListenerRunsNothing() throws Exception {
        lending = LendingFixture.start();
        lending.signOn();
        final QuiescentPoint point = point();
        assertFalse(point.isClosed());
        lending.uninstall();
        assertTrue(point.isClosed(), "the module closed its point before its worker stopped");
        Thread.sleep(250);
        long ranAfter = ((Number) point.stats().get("ran")).longValue();
        Thread.sleep(250);
        assertEquals(ranAfter, ((Number) point.stats().get("ran")).longValue());
        assertFalse(point.runOne(Long.MAX_VALUE), "a closed point runs nothing");
    }

    /** The twin of {@code dataQueryNeverReturnsAStaffPassword} on the positional source (fails without P8 4.4). */
    @Test
    void dataQueryOnTheReportReaderNeverReturnsAStaffPassword() throws Exception {
        lending = LendingFixture.start();
        List<String> passwords = new ArrayList<String>();
        Connection c = lending.app.db().openReadOnlyConnection();
        try {
            Statement st = c.createStatement();
            ResultSet rs = st.executeQuery("SELECT password FROM staff ORDER BY code");
            while (rs.next()) {
                passwords.add(rs.getString(1));
            }
            st.close();
        } finally {
            c.close();
        }
        assertEquals(7, passwords.size());
        DataModule data = new DataModule();
        data.install(lending.ctx);
        try {
            Map<String, Object> all = map(lending.ctx.call("data.query", args("source", "lending-report", "sql",
                    "SELECT code, password, name FROM staff ORDER BY code")));
            assertEquals("[{name=code, type=null}, {name=password, type=null}, {name=name, type=null}]",
                    String.valueOf(all.get("columns")), "the names come from the statement");
            List<Object> rows = list(all.get("rows"));
            assertEquals(7, rows.size());
            for (Object row : rows) {
                assertEquals("<redacted>", list(row).get(1), String.valueOf(list(row).get(0)));
            }
            assertEquals(Arrays.asList("100", "<redacted>", "Alice Archer"), rows.get(0), "other columns as read");
            Map<String, Object> one = map(lending.ctx.call("data.query", args("source", "lending-report", "sql",
                    "SELECT s.password FROM staff s WHERE s.code = ?", "params", Collections.singletonList("100"))));
            assertEquals(Collections.singletonList(Collections.singletonList("<redacted>")), one.get("rows"));
            Map<String, Object> empty = map(lending.ctx.call("data.query", args("source", "lending-report", "sql",
                    "SELECT * FROM staff WHERE 1 = 0")));
            assertEquals(5, list(empty.get("columns")).size(), "the star form: columns(table), no row");
            assertEquals("CODE", map(list(empty.get("columns")).get(0)).get("name"));
            for (Map<String, Object> result : Arrays.asList(all, one, empty)) {
                String text = String.valueOf(result);
                for (String password : passwords) {
                    assertFalse(text.contains(password), "a seeded password left the module: " + text);
                }
            }
        } finally {
            data.uninstall();
        }
    }

    /**
     * Phase 8 security fix S160 (code review L1-F3; security L3-F2 and its sceptic), S162 and S163 on the desk: no
     * label-less form returns a seeded password through the positional source, which answers each statement; the
     * named forms keep their other columns.
     */
    @Test
    void noLabelLessFormOnTheReportReaderReturnsAStaffPassword() throws Exception {
        lending = LendingFixture.start();
        List<String> passwords = seededPasswords();
        DataModule data = new DataModule();
        data.install(lending.ctx);
        try {
            for (String sql : Arrays.asList(
                    "SELECT s.* FROM staff s",
                    "SELECT staff.* FROM staff",
                    "SELECT * FROM staff s JOIN staff_role r ON r.staff_code = s.code",
                    "SELECT * FROM (SELECT * FROM staff) x",
                    "WITH t AS (SELECT * FROM staff) SELECT * FROM t",
                    "SELECT password FROM staff UNION SELECT name FROM staff",
                    "SELECT * FROM staff, role",
                    "SELECT code, s.* FROM staff s",
                    "SELECT (password) FROM staff ORDER BY code",
                    "SELECT code, password COLLATE SQL_TEXT FROM staff ORDER BY code",
                    "SELECT code, LOWER(password) FROM staff",
                    "SELECT code window, password FROM staff",
                    "SELECT code limit, password FROM staff",
                    "SELECT code, (SELECT p.password FROM staff p WHERE p.code = s.code) FROM staff s",
                    "SELECT * FROM \"STAFF\"",
                    "SELECT * FROM PUBLIC.\"STAFF\" WHERE 1 = 1")) {
                Map<String, Object> result = map(lending.ctx.call("data.query", args("source", "lending-report",
                        "sql", sql)));
                assertFalse(list(result.get("rows")).isEmpty(), "the source answers: " + sql);
                String text = String.valueOf(result);
                for (String password : passwords) {
                    assertFalse(text.contains(password), "a seeded password left the module: " + sql + " " + text);
                }
            }
            Map<String, Object> star = map(lending.ctx.call("data.query", args("source", "lending-report", "sql",
                    "SELECT * FROM \"STAFF\" ORDER BY code")));
            assertEquals("CODE", map(list(star.get("columns")).get(0)).get("name"), "the quoted star form is named");
            assertEquals("100", list(list(star.get("rows")).get(0)).get(0), "and its other columns as read");
            Map<String, Object> named = map(lending.ctx.call("data.query", args("source", "lending-report", "sql",
                    "SELECT code, (password), name FROM staff ORDER BY code")));
            assertEquals(Arrays.asList("100", "<redacted>", "Alice Archer"), list(named.get("rows")).get(0),
                    "an unnamed item is masked by the names it reads; the named items as read");
        } finally {
            data.uninstall();
        }
    }

    /**
     * S163 on the desk: a policy that compares names exactly as the JDBC source labels them (upper case) masks the
     * positional source's derived labels too.
     */
    @Test
    void anExactCasePolicyMasksTheReportReaderAsItMasksTheJdbcSource() throws Exception {
        lending = LendingFixture.start();
        lending.ctx.spi.register(ai.skillroy.testjava.agent.api.spi.MaskPolicy.class,
                (sourceId, column, value) -> sourceId.startsWith("lending") && "NAME".equals(column) && value != null
                        ? "<personal>" : value);
        DataModule data = new DataModule();
        data.install(lending.ctx);
        try {
            for (String source : Arrays.asList("lending", "lending-report")) {
                for (String sql : Arrays.asList("SELECT code, name FROM staff ORDER BY code",
                        "SELECT s.code, s.name FROM staff s ORDER BY s.code", "SELECT * FROM staff ORDER BY code")) {
                    Map<String, Object> result = map(lending.ctx.call("data.query", args("source", source, "sql",
                            sql)));
                    assertFalse(String.valueOf(result).contains("Alice Archer"), source + ": " + sql + " " + result);
                }
            }
        } finally {
            data.uninstall();
        }
    }

    private List<String> seededPasswords() throws Exception {
        List<String> passwords = new ArrayList<String>();
        Connection c = lending.app.db().openReadOnlyConnection();
        try {
            Statement st = c.createStatement();
            ResultSet rs = st.executeQuery("SELECT password FROM staff ORDER BY code");
            while (rs.next()) {
                passwords.add(rs.getString(1));
            }
            st.close();
        } finally {
            c.close();
        }
        assertEquals(7, passwords.size());
        return passwords;
    }

    /** A runaway report on the lane: stopped by its ReadHandle when the caller's time runs out. */
    @Test
    void aSlowReportIsCancelledOnItsLane() throws Exception {
        lending = LendingFixture.start();
        DataModule data = new DataModule();
        data.install(lending.ctx);
        try {
            Primitive query = lending.ctx.primitives.find("data.query");
            // A seven-way cross join over the desk's items: it ends only when it is stopped.
            String runaway = "SELECT COUNT(*) FROM item a, item b, item c, item d, item e, item f, item g"
                    + " WHERE a.deposit + b.deposit + c.deposit + d.deposit + e.deposit + f.deposit + g.deposit < 0";
            long start = System.nanoTime();
            RpcException e;
            try {
                query.invoke(lending.ctx.invocation(args("source", "lending-report", "sql", runaway), 1000L));
                throw new AssertionError("expected TIMEOUT");
            } catch (RpcException expected) {
                e = expected;
            }
            long tookMs = (System.nanoTime() - start) / 1000000L;
            assertEquals(ErrorCodes.TIMEOUT, e.error().code(), e.error().toString());
            Map<String, Object> d = e.error().data();
            assertEquals("lending-report", d.get("source"));
            assertEquals("lending.reads", d.get("lane"));
            assertEquals(Boolean.TRUE, d.get("cancelled"), d.toString());
            assertEquals(Boolean.FALSE, d.get("stillRunning"), d.toString());
            assertEquals(Boolean.TRUE, d.get("enforcesTimeout"));
            assertTrue(tookMs < 1000, "inside the call's time: " + tookMs);
            Map<String, Object> next = map(lending.ctx.call("data.query", args("source", "lending-report", "sql",
                    "SELECT code FROM item WHERE code = ?", "params", Collections.singletonList("YRD-001"))));
            assertEquals(1L, next.get("rowCount"), "the lane answers again");
        } finally {
            data.uninstall();
        }
    }
}
