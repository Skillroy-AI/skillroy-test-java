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

package ai.skillroy.testjava.agent.data;

import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.protocol.sql.StatementGuard;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDBC executor called directly, bypassing {@link StatementGuard}: proves that the read-only connection, not the
 * guard, is what stops a write, and that every resource is released on every path.
 */
class JdbcExecutorTest {

    private static final List<Object> NO_PARAMS = Collections.emptyList();

    private static TestDb db;

    private final FakeAgentContext.Log log = new FakeAgentContext.Log();

    @BeforeAll
    static void createDatabase() throws SQLException {
        db = TestDb.create();
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        db.close();
    }

    /** True when the guard rejects {@code sql}. */
    private static boolean rejected(String sql) {
        try {
            StatementGuard.check(sql);
            return false;
        } catch (RpcException e) {
            return true;
        }
    }

    private Rows run(JdbcSpy spy, String sql) throws Exception {
        return JdbcExecutor.execute(spy.wrap(db.open()), sql, NO_PARAMS, 200, 5, log);
    }

    @Test
    void writesThatSkipTheGuardFailOnTheReadOnlyConnection() throws Exception {
        for (String write : Arrays.asList("UPDATE person SET name = 'changed'", "DELETE FROM person",
                "INSERT INTO person (code, name) VALUES ('999', 'intruder')",
                "UPDATE person SET name = 'changed'; ")) {
            assertTrue(rejected(write), "the guard would have stopped " + write);
            JdbcSpy spy = new JdbcSpy();
            SQLException e = assertThrows(SQLException.class, () -> run(spy, write), write);
            assertEquals("25006", e.getSQLState(), write + ": " + e);   // read-only SQL-transaction
            assertTrue(spy.allClosed(), spy.toString());
        }
        assertEquals(3L, db.scalar("SELECT COUNT(*) FROM person"));
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM person WHERE name = 'changed'"));
    }

    @Test
    void withoutTheReadOnlyFlagTheSameWriteIsStoppedOnlyByTheResultSetCheck() throws Exception {
        // Control for the test above: a driver that cannot set read-only (the executor ignores the
        // SQLFeatureNotSupportedException) gets a different, later failure, so 25006 above is the read-only flag.
        JdbcSpy spy = new JdbcSpy().fail("Connection.setReadOnly", new SQLFeatureNotSupportedException("no"));
        SQLException e = assertThrows(SQLException.class, () -> run(spy, "UPDATE person SET name = 'changed'"));
        assertEquals("07504", e.getSQLState(), e.toString());   // statement does not generate a result set
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM person WHERE name = 'changed'"));
        assertTrue(spy.allClosed(), spy.toString());
        assertFalse(spy.calls.contains("Connection.setReadOnly[false]"), "nothing to restore");
    }

    @Test
    void readsWorkAndReleaseEverything() throws Exception {
        JdbcSpy spy = new JdbcSpy();
        Rows table = JdbcExecutor.execute(spy.wrap(db.open()), "SELECT code FROM person WHERE code = ?",
                Arrays.<Object>asList("300"), 10, 3, log);
        assertEquals(Collections.singletonList(Arrays.asList("300")), table.rows());
        assertEquals("CODE", table.columns().get(0).name());
        assertEquals(1, table.columns().size());
        assertFalse(table.truncated());
        assertTrue(spy.allClosed(), spy.toString());
        assertTrue(spy.calls.contains("PreparedStatement.setQueryTimeout[3]"), spy.toString());
        assertTrue(spy.calls.contains("PreparedStatement.setMaxRows[11]"), spy.toString());
        assertTrue(spy.calls.contains("PreparedStatement.setObject[1, 300]"), spy.toString());
        assertTrue(spy.indexOf("Connection.setReadOnly[true]") < spy.indexOf("Connection.prepareStatement"));
        // Security fix P2-L3-S1 (pass 2): the provider's connection may be pooled, so the statement's transaction is
        // ended and auto-commit switched back on before the connection is closed (handed back).
        int rollback = spy.indexOf("Connection.rollback");
        int autoCommit = spy.indexOf("Connection.setAutoCommit[true]");
        int close = spy.indexOf("Connection.close");
        assertTrue(spy.indexOf("Connection.setAutoCommit[false]") < spy.indexOf("Connection.prepareStatement"),
                spy.toString());
        assertTrue(rollback >= 0 && rollback < autoCommit && autoCommit < close, spy.toString());
    }

    @Test
    void anUnsupportedReadOnlyFlagIsIgnored() throws Exception {
        JdbcSpy spy = new JdbcSpy().fail("Connection.setReadOnly", new SQLFeatureNotSupportedException("no"));
        assertEquals(3, run(spy, "SELECT code FROM person").rows().size());
        assertTrue(spy.allClosed(), spy.toString());
    }

    @Test
    void anyOtherReadOnlyFailureAbortsBeforeTheStatement() throws Exception {
        JdbcSpy spy = new JdbcSpy().fail("Connection.setReadOnly", JdbcSpy.sqlException("refused", "25000"));
        SQLException e = assertThrows(SQLException.class, () -> run(spy, "SELECT code FROM person"));
        assertEquals("25000", e.getSQLState());
        assertEquals(-1, spy.indexOf("Connection.prepareStatement"), spy.toString());
        assertTrue(spy.allClosed(), spy.toString());
    }

    @Test
    void unsupportedTimeoutAndRowLimitAreIgnored() throws Exception {
        JdbcSpy spy = new JdbcSpy()
                .fail("PreparedStatement.setQueryTimeout", new SQLFeatureNotSupportedException("no"))
                .fail("PreparedStatement.setMaxRows", new SQLFeatureNotSupportedException("no"));
        Rows table = JdbcExecutor.execute(spy.wrap(db.open()), "SELECT id FROM item", NO_PARAMS, 7, 5, log);
        assertEquals(7, table.rows().size());
        assertTrue(table.truncated());
        assertTrue(spy.allClosed(), spy.toString());
    }

    @Test
    void failuresAtEveryStepReleaseEverything() throws Exception {
        for (String step : Arrays.asList("Connection.prepareStatement", "PreparedStatement.setObject",
                "PreparedStatement.executeQuery", "ResultSet.getMetaData", "ResultSet.next", "ResultSet.getObject")) {
            JdbcSpy spy = new JdbcSpy().fail(step, JdbcSpy.sqlException("injected at " + step, "HY000"));
            SQLException e = assertThrows(SQLException.class, () -> JdbcExecutor.execute(spy.wrap(db.open()),
                    "SELECT code FROM person WHERE code <> ?", Arrays.<Object>asList("x"), 200, 5, log), step);
            assertEquals("injected at " + step, e.getMessage());
            assertTrue(spy.allClosed(), step + ": " + spy);
            assertTrue(spy.calls.contains("Connection.setReadOnly[false]"), step + ": " + spy);
        }
    }

    @Test
    void cleanupFailuresAreLoggedAndDoNotHideTheResult() throws Exception {
        Connection real = db.open();
        JdbcSpy spy = new JdbcSpy()
                .fail("ResultSet.close", JdbcSpy.sqlException("rs close", "HY000"))
                .fail("PreparedStatement.close", JdbcSpy.sqlException("ps close", "HY000"))
                .fail("Connection.close", JdbcSpy.sqlException("conn close", "HY000"));
        Rows table = JdbcExecutor.execute(spy.wrap(real), "SELECT code FROM person", NO_PARAMS, 200, 5, log);
        assertEquals(3, table.rows().size());
        assertTrue(spy.allClosed(), "each close was attempted once: " + spy);
        String lines = log.all();
        assertTrue(lines.contains("could not close the result set"), lines);
        assertTrue(lines.contains("could not close the statement"), lines);
        assertTrue(lines.contains("could not close the connection"), lines);
        real.close();
    }

    @Test
    void manualCommitConnectionsAreRolledBackAndRestored() throws Exception {
        Connection real = db.open();
        real.setAutoCommit(false);
        JdbcSpy spy = new JdbcSpy();
        JdbcExecutor.execute(spy.wrap(real), "SELECT code FROM person", NO_PARAMS, 200, 5, log);
        int rollback = spy.indexOf("Connection.rollback");
        int restore = spy.indexOf("Connection.setReadOnly[false]");
        int close = spy.indexOf("Connection.close");
        assertTrue(rollback >= 0 && rollback < restore && restore < close, spy.toString());
        assertTrue(real.isClosed());
    }

    @Test
    void anAlreadyReadOnlyConnectionStaysReadOnly() throws Exception {
        Connection real = db.open();
        real.setReadOnly(true);
        JdbcSpy spy = new JdbcSpy();
        JdbcExecutor.execute(spy.wrap(real), "SELECT code FROM person", NO_PARAMS, 200, 5, log);
        assertFalse(spy.calls.contains("Connection.setReadOnly[false]"), spy.toString());
        assertTrue(spy.allClosed(), spy.toString());
    }

    @Test
    void interruptionWhileReadingStopsAndReleasesEverything() throws Exception {
        final AtomicInteger nextCalls = new AtomicInteger();
        final JdbcSpy spy = new JdbcSpy().before("ResultSet.next", () -> {
            if (nextCalls.incrementAndGet() == 3) {
                Thread.currentThread().interrupt();
            }
        });
        final AtomicReference<Throwable> outcome = new AtomicReference<Throwable>();
        final AtomicReference<Boolean> flagAfter = new AtomicReference<Boolean>();
        Thread worker = new Thread(() -> {
            try {
                JdbcExecutor.execute(spy.wrap(db.open()), "SELECT id FROM item", NO_PARAMS, 200, 5, log);
                outcome.set(new AssertionError("not interrupted"));
            } catch (Throwable t) {
                outcome.set(t);
            }
            flagAfter.set(Thread.currentThread().isInterrupted());
        }, "skillroy-test-reader");
        worker.setDaemon(true);
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(60));
        assertFalse(worker.isAlive());
        assertTrue(outcome.get() instanceof InterruptedException, String.valueOf(outcome.get()));
        assertEquals(Boolean.FALSE, flagAfter.get());
        assertEquals(3, nextCalls.get());
        assertTrue(spy.allClosed(), spy.toString());
    }

    /**
     * Security fix S161 (L3-F3): the agent gives up while the driver prepares the statement (a server-side prepare,
     * the sceptic's probe); its batch runs while no statement exists, and the statement prepared afterwards is never
     * run.
     */
    @Test
    void aStatementPreparedAfterTheReadWasCancelledNeverRuns() throws Exception {
        final TestBroker broker = new TestBroker();
        final Control control = new Control(System.nanoTime() + TimeUnit.SECONDS.toNanos(30), log);
        JdbcSpy spy = new JdbcSpy().before("Connection.prepareStatement", () -> {
            control.cancel();
            control.startActions(broker);
            try {
                control.awaitActions(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        SQLException e = assertThrows(SQLException.class, () -> JdbcExecutor.execute(spy.wrap(db.open()),
                "SELECT code FROM person", NO_PARAMS, 10, 5, log, control));
        assertEquals("57014", e.getSQLState(), e.toString());
        assertEquals(1, spy.count("Connection.prepareStatement"), spy.toString());
        assertEquals(0, spy.count("PreparedStatement.executeQuery"), "never run: " + spy);
        assertEquals(0, spy.count("PreparedStatement.setQueryTimeout"), "nothing bound: " + spy);
        assertTrue(spy.allClosed(), spy.toString());
        assertTrue(spy.calls.contains("Connection.rollback"), "the connection is restored: " + spy);
        assertFalse(control.cancelledByAction(), "there was nothing to cancel when the batch ran");
    }

    /** S161, the reviewer's probe: the read was given up on before {@code open()} returned. */
    @Test
    void aReadCancelledBeforeItsStatementWasPreparedPreparesNone() throws Exception {
        Control control = new Control(System.nanoTime() + TimeUnit.SECONDS.toNanos(30), log);
        control.cancel();
        JdbcSpy spy = new JdbcSpy();
        SQLException e = assertThrows(SQLException.class, () -> JdbcExecutor.execute(spy.wrap(db.open()),
                "SELECT code FROM person", NO_PARAMS, 10, 5, log, control));
        assertEquals("57014", e.getSQLState(), e.toString());
        assertEquals(0, spy.count("Connection.prepareStatement"), spy.toString());
        assertEquals(1, spy.count("Connection.close"), "the connection is still closed: " + spy);
    }

    /**
     * S161, the second half: a {@code cancel()} in flight when the read ends is let return before the statement is
     * closed (the sink hands null back first and waits for it), so no cancel lands on a closed statement whose
     * connection went back to its pool.
     */
    @Test
    void theStatementIsClosedOnlyOnceACancelInFlightHasReturned() throws Exception {
        final TestBroker broker = new TestBroker();
        final Control control = new Control(System.nanoTime() + TimeUnit.SECONDS.toNanos(30), log);
        final CountDownLatch cancelling = new CountDownLatch(1);
        final JdbcSpy spy = new JdbcSpy();
        spy.before("PreparedStatement.cancel", () -> {
            cancelling.countDown();
            QueryControlTest.sleepIgnoringInterrupts(300);
            spy.calls.add("cancel returns");
        });
        spy.before("PreparedStatement.executeQuery", () -> {
            control.cancel();
            control.startActions(broker);
            try {
                assertTrue(cancelling.await(5, TimeUnit.SECONDS), "the action reached cancel()");
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Rows rows = JdbcExecutor.execute(spy.wrap(db.open()), "SELECT code FROM person", NO_PARAMS, 10, 5, log,
                control);
        assertEquals(3, rows.rowCount());
        int returned = spy.indexOf("cancel returns");
        assertTrue(returned >= 0, spy.toString());
        assertTrue(spy.indexOf("PreparedStatement.close") > returned, "closed after the cancel returned: " + spy);
        assertTrue(spy.allClosed(), spy.toString());
        assertTrue(control.cancelledByAction() || log.all().contains("Statement.cancel() failed"), log.all());
    }
}
