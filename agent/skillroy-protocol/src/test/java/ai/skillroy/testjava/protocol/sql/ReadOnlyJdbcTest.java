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

package ai.skillroy.testjava.protocol.sql;

import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared JDBC read path called directly, bypassing {@link StatementGuard} (the cases of the agent's former
 * {@code JdbcExecutorTest}, which this code came from): the read-only connection, not the guard, is what stops a write;
 * every statement and result set is released on every path; and the caller's connection is left open and restored,
 * so that the controller can keep one connection per source.
 */
class ReadOnlyJdbcTest {

    private static final List<Object> NO_PARAMS = Collections.emptyList();

    private static TestDb db;

    private final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());
    private final Consumer<String> warn = new Consumer<String>() {
        @Override
        public void accept(String line) {
            warnings.add(line);
        }
    };

    @BeforeAll
    static void createDatabase() throws SQLException {
        db = TestDb.create();
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        db.close();
    }

    private Rows run(JdbcSpy spy, String sql) throws Exception {
        return ReadOnlyJdbc.execute(spy.wrap(db.open()), sql, NO_PARAMS, 200, 5, warn);
    }

    private static boolean rejected(String sql) {
        try {
            StatementGuard.check(sql);
            return false;
        } catch (RpcException e) {
            return true;
        }
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
            assertTrue(spy.statementsClosedConnectionKept(), spy.toString());
        }
        assertEquals(3L, db.scalar("SELECT COUNT(*) FROM person"));
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM person WHERE name = 'changed'"));
    }

    @Test
    void withoutTheReadOnlyFlagTheSameWriteIsStoppedOnlyByTheResultSetCheck() throws Exception {
        JdbcSpy spy = new JdbcSpy().fail("Connection.setReadOnly", new SQLFeatureNotSupportedException("no"));
        SQLException e = assertThrows(SQLException.class, () -> run(spy, "UPDATE person SET name = 'changed'"));
        assertEquals("07504", e.getSQLState(), e.toString());   // statement does not generate a result set
        assertEquals(0L, db.scalar("SELECT COUNT(*) FROM person WHERE name = 'changed'"));
        assertTrue(spy.statementsClosedConnectionKept(), spy.toString());
        assertFalse(spy.calls.contains("Connection.setReadOnly[false]"), "nothing to restore");
    }

    @Test
    void readsWorkReleaseTheStatementAndKeepTheConnection() throws Exception {
        JdbcSpy spy = new JdbcSpy();
        Connection real = db.open();
        Connection connection = spy.wrap(real);
        Rows rows = ReadOnlyJdbc.execute(connection, "SELECT code FROM person WHERE code = ?",
                Arrays.<Object>asList("300"), 10, 3, warn);
        assertEquals(Collections.singletonList(Arrays.asList("300")), rows.rows());
        assertEquals("CODE", rows.columns().get(0).name());
        assertEquals(0, rows.index("code"), "names are compared without regard to case");
        assertFalse(rows.truncated());
        assertTrue(spy.statementsClosedConnectionKept(), spy.toString());
        assertTrue(spy.calls.contains("PreparedStatement.setQueryTimeout[3]"), spy.toString());
        assertTrue(spy.calls.contains("PreparedStatement.setMaxRows[11]"), spy.toString());
        assertTrue(spy.calls.contains("PreparedStatement.setObject[1, 300]"), spy.toString());
        assertTrue(spy.indexOf("Connection.setReadOnly[true]") < spy.indexOf("Connection.prepareStatement"));
        assertTrue(spy.calls.contains("Connection.setReadOnly[false]"), "a writable connection is restored");
        assertTrue(spy.calls.contains("Connection.rollback"), "every statement's transaction is ended (P2-L3-F1)");
        assertTrue(real.getAutoCommit(), "and auto-commit is on again");
        assertFalse(real.isClosed());
        assertFalse(real.isReadOnly());
        // The same connection serves the next statement.
        assertEquals(3, ReadOnlyJdbc.execute(connection, "SELECT code FROM person", NO_PARAMS, 10, 3, warn)
                .rowCount());
        real.close();
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void anUnsupportedReadOnlyFlagIsIgnored() throws Exception {
        JdbcSpy spy = new JdbcSpy().fail("Connection.setReadOnly", new SQLFeatureNotSupportedException("no"));
        assertEquals(3, run(spy, "SELECT code FROM person").rows().size());
        assertTrue(spy.statementsClosedConnectionKept(), spy.toString());
    }

    @Test
    void anyOtherReadOnlyFailureAbortsBeforeTheStatement() throws Exception {
        JdbcSpy spy = new JdbcSpy().fail("Connection.setReadOnly", JdbcSpy.sqlException("refused", "25000"));
        SQLException e = assertThrows(SQLException.class, () -> run(spy, "SELECT code FROM person"));
        assertEquals("25000", e.getSQLState());
        assertEquals(-1, spy.indexOf("Connection.prepareStatement"), spy.toString());
        assertTrue(spy.statementsClosedConnectionKept(), spy.toString());
    }

    @Test
    void unsupportedTimeoutAndRowLimitAreIgnored() throws Exception {
        JdbcSpy spy = new JdbcSpy()
                .fail("PreparedStatement.setQueryTimeout", new SQLFeatureNotSupportedException("no"))
                .fail("PreparedStatement.setMaxRows", new SQLFeatureNotSupportedException("no"));
        Rows rows = ReadOnlyJdbc.execute(spy.wrap(db.open()), "SELECT id FROM item", NO_PARAMS, 7, 5, warn);
        assertEquals(7, rows.rows().size());
        assertTrue(rows.truncated());
        assertTrue(spy.statementsClosedConnectionKept(), spy.toString());
    }

    @Test
    void theRowCapAndTruncation() throws Exception {
        Rows capped = run(new JdbcSpy(), "SELECT id FROM item ORDER BY id");
        assertEquals(200, capped.rowCount());
        assertTrue(capped.truncated());
        assertEquals(200L, capped.get(199, 0));
        Rows exact = ReadOnlyJdbc.execute(db.open(), "SELECT id FROM item WHERE id <= 200", NO_PARAMS, 200, 5, warn);
        assertEquals(200, exact.rowCount());
        assertFalse(exact.truncated(), "exactly maxRows rows is not truncated");
        Rows none = ReadOnlyJdbc.execute(db.open(), "SELECT id, code FROM item", NO_PARAMS, 0, 5, warn);
        assertEquals(0, none.rowCount());
        assertEquals(2, none.columns().size(), "maxRows 0 returns the columns only");
    }

    @Test
    void failuresAtEveryStepReleaseEverything() throws Exception {
        for (String step : Arrays.asList("Connection.prepareStatement", "PreparedStatement.setObject",
                "PreparedStatement.executeQuery", "ResultSet.getMetaData", "ResultSet.next", "ResultSet.getObject")) {
            JdbcSpy spy = new JdbcSpy().fail(step, JdbcSpy.sqlException("injected at " + step, "HY000"));
            SQLException e = assertThrows(SQLException.class, () -> ReadOnlyJdbc.execute(spy.wrap(db.open()),
                    "SELECT code FROM person WHERE code <> ?", Arrays.<Object>asList("x"), 200, 5, warn), step);
            assertEquals("injected at " + step, e.getMessage());
            assertTrue(spy.statementsClosedConnectionKept(), step + ": " + spy);
            assertTrue(spy.calls.contains("Connection.setReadOnly[false]"), step + ": " + spy);
        }
    }

    @Test
    void cleanupFailuresAreReportedWithoutDriverTextAndDoNotHideTheResult() throws Exception {
        Connection real = db.open();
        real.setAutoCommit(false);
        JdbcSpy spy = new JdbcSpy()
                .fail("ResultSet.close", JdbcSpy.sqlException("rs close secret-text", "HY000"))
                .fail("PreparedStatement.close", JdbcSpy.sqlException("ps close secret-text", "HY000"))
                .fail("Connection.rollback", JdbcSpy.sqlException("rollback secret-text", "HY000"));
        Rows rows = ReadOnlyJdbc.execute(spy.wrap(real), "SELECT code FROM person", NO_PARAMS, 200, 5, warn);
        assertEquals(3, rows.rows().size());
        String lines = warnings.toString();
        assertTrue(lines.contains("could not close the result set: java.sql.SQLException (sqlState=HY000,"), lines);
        assertTrue(lines.contains("could not close the statement"), lines);
        assertTrue(lines.contains("could not end the read transaction"), lines);
        assertFalse(lines.contains("secret-text"), "never the driver's message: " + lines);
        real.close();
    }

    @Test
    void aFailingWarnSinkDoesNotHideTheResult() throws Exception {
        JdbcSpy spy = new JdbcSpy().fail("ResultSet.close", JdbcSpy.sqlException("rs close", "HY000"));
        Rows rows = ReadOnlyJdbc.execute(spy.wrap(db.open()), "SELECT code FROM person", NO_PARAMS, 200, 5,
                new Consumer<String>() {
                    @Override
                    public void accept(String line) {
                        throw new IllegalStateException("log sink down");
                    }
                });
        assertEquals(3, rows.rowCount());
    }

    @Test
    void manualCommitConnectionsAreRolledBackAndRestored() throws Exception {
        Connection real = db.open();
        real.setAutoCommit(false);
        JdbcSpy spy = new JdbcSpy();
        ReadOnlyJdbc.execute(spy.wrap(real), "SELECT code FROM person", NO_PARAMS, 200, 5, warn);
        int rollback = spy.indexOf("Connection.rollback");
        int restore = spy.indexOf("Connection.setReadOnly[false]");
        assertTrue(rollback >= 0 && rollback < restore, spy.toString());
        assertEquals(0, spy.count("Connection.setAutoCommit"), "a manual-commit connection keeps its mode: " + spy);
        assertFalse(real.getAutoCommit());
        assertFalse(real.isClosed());
        real.close();
    }

    /**
     * Security fix P2-L3-F1 and P2-L3-S1 (pass 2): an auto-commit connection runs the statement in a transaction the
     * read path ends itself, in this order: {@code setReadOnly(true)}, {@code setAutoCommit(false)}, the statement,
     * {@code rollback}, {@code setAutoCommit(true)}, {@code setReadOnly(false)}. A T-SQL batch that opens a transaction
     * the guard does not see ({@code SET IMPLICIT_TRANSACTIONS ON} leaves the JDBC flag on) so leaves none open on the
     * controller's kept connection, nor on a provider's connection that goes back to a pool when the agent closes it.
     */
    @Test
    void anAutoCommitConnectionRunsTheStatementInATransactionTheReadPathEnds() throws Exception {
        Connection real = db.open();
        assertTrue(real.getAutoCommit());
        JdbcSpy spy = new JdbcSpy();
        Rows rows = ReadOnlyJdbc.execute(spy.wrap(real), "SELECT code FROM person", NO_PARAMS, 200, 5, warn);
        assertEquals(3, rows.rowCount());
        int last = -1;
        for (String call : Arrays.asList("Connection.setReadOnly[true]", "Connection.setAutoCommit[false]",
                "PreparedStatement.executeQuery", "Connection.rollback", "Connection.setAutoCommit[true]",
                "Connection.setReadOnly[false]")) {
            int at = spy.indexOf(call);
            assertTrue(at > last, call + " comes after the call before it: " + spy);
            last = at;
        }
        assertTrue(real.getAutoCommit(), "auto-commit is on again");
        assertFalse(real.isReadOnly(), "and the connection writable, as it was");
        assertTrue(spy.statementsClosedConnectionKept(), spy.toString());
        assertTrue(warnings.isEmpty(), warnings.toString());
        real.close();
    }

    @Test
    void anUnsupportedManualCommitLeavesTheConnectionAsItWas() throws Exception {
        JdbcSpy spy = new JdbcSpy().fail("Connection.setAutoCommit", new SQLFeatureNotSupportedException("no"));
        assertEquals(3, run(spy, "SELECT code FROM person").rowCount());
        assertFalse(spy.calls.contains("Connection.rollback"), "nothing was begun, so nothing is ended: " + spy);
        assertTrue(spy.calls.contains("Connection.setReadOnly[false]"), spy.toString());
    }

    @Test
    void anyOtherManualCommitFailureAbortsBeforeTheStatement() throws Exception {
        JdbcSpy spy = new JdbcSpy().fail("Connection.setAutoCommit", JdbcSpy.sqlException("no commit mode", "HY000"));
        SQLException e = assertThrows(SQLException.class, () -> run(spy, "SELECT code FROM person"));
        assertEquals("no commit mode", e.getMessage());
        assertEquals(-1, spy.indexOf("Connection.prepareStatement"), spy.toString());
        assertTrue(spy.calls.contains("Connection.setReadOnly[false]"), "the read-only flag is restored: " + spy);
    }

    @Test
    void aFailedRollbackIsReportedAndAutoCommitStillSwitchedBackOn() throws Exception {
        Connection real = db.open();
        JdbcSpy spy = new JdbcSpy().fail("Connection.rollback", JdbcSpy.sqlException("rollback secret-text", "HY000"));
        assertEquals(3, ReadOnlyJdbc.execute(spy.wrap(real), "SELECT code FROM person", NO_PARAMS, 200, 5, warn)
                .rowCount());
        assertTrue(warnings.toString().contains("could not end the read transaction"), warnings.toString());
        assertFalse(warnings.toString().contains("secret-text"), warnings.toString());
        assertTrue(spy.indexOf("Connection.rollback") < spy.indexOf("Connection.setAutoCommit[true]"), spy.toString());
        assertTrue(real.getAutoCommit());
        real.close();
    }

    @Test
    void anAlreadyReadOnlyConnectionStaysReadOnly() throws Exception {
        Connection real = db.open();
        real.setReadOnly(true);
        JdbcSpy spy = new JdbcSpy();
        ReadOnlyJdbc.execute(spy.wrap(real), "SELECT code FROM person", NO_PARAMS, 200, 5, warn);
        assertFalse(spy.calls.contains("Connection.setReadOnly[false]"), spy.toString());
        assertTrue(spy.statementsClosedConnectionKept(), spy.toString());
        assertTrue(real.isReadOnly());
        real.close();
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
                ReadOnlyJdbc.execute(spy.wrap(db.open()), "SELECT id FROM item", NO_PARAMS, 200, 5, warn);
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
        assertTrue(spy.statementsClosedConnectionKept(), spy.toString());
    }

    @Test
    void parametersOfTheFourAllowedFormsAreBoundAsTheyAre() throws Exception {
        JdbcSpy spy = new JdbcSpy();
        Rows rows = ReadOnlyJdbc.execute(spy.wrap(db.open()), "SELECT code FROM person WHERE code = ? AND active = ?"
                + " AND credit >= CAST(? AS DECIMAL(10,2)) AND ? IS NULL AND ? > 0",
                Arrays.<Object>asList("100", Boolean.TRUE, "250.00", null, Long.valueOf(1)), 10, 5, warn);
        assertEquals(Collections.singletonList(Arrays.asList("100")), rows.rows(),
                "a decimal string compared at its exact bound");
        assertTrue(spy.calls.contains("PreparedStatement.setObject[2, true]"), spy.toString());
        assertTrue(spy.calls.contains("PreparedStatement.setObject[3, 250.00]"), spy.toString());
        assertTrue(spy.calls.contains("PreparedStatement.setObject[5, 1]"), spy.toString());
    }

    @Test
    void aParameterOfAnotherFormIsRefusedBeforeTheConnectionIsUsed() throws Exception {
        for (Object bad : Arrays.<Object>asList(Double.valueOf(34.99), new BigDecimal("34.99"), Integer.valueOf(1),
                new BigInteger("1"), new java.util.Date(0), 'c', new Object[0])) {
            JdbcSpy spy = new JdbcSpy();
            Connection connection = spy.wrap(db.open());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ReadOnlyJdbc.execute(
                    connection, "SELECT code FROM person WHERE code = ? AND code = ?",
                    Arrays.<Object>asList("100", bad), 10, 5, warn), String.valueOf(bad));
            assertTrue(e.getMessage().startsWith("params[1] is a " + bad.getClass().getName()), e.getMessage());
            assertTrue(spy.calls.isEmpty(), "nothing touched the connection: " + spy);
        }
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyJdbc.checkParams(null));
        ReadOnlyJdbc.checkParams(Arrays.<Object>asList("a", 1L, false, null));
    }

    @Test
    void theLenientFormBindsWhatDataQueryAccepts() throws Exception {
        Rows rows = ReadOnlyJdbc.executeLenient(db.open(), "SELECT code FROM person WHERE credit > ?",
                Arrays.<Object>asList(Double.valueOf(100.5)), 10, 5, warn);
        assertEquals(Collections.singletonList(Arrays.asList("100")), rows.rows());
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyJdbc.executeLenient(db.open(), "SELECT 1",
                NO_PARAMS, -1, 5, warn));
    }

    @Test
    void describeNamesClassStateAndCodeButNeverTheMessage() {
        SQLException sql = new SQLException("driver text with value 1111", "42501", -5501);
        assertEquals("java.sql.SQLException (sqlState=42501, errorCode=-5501)", ReadOnlyJdbc.describe(sql));
        RuntimeException wrapped = new RuntimeException("outer text", sql);
        assertEquals("java.lang.RuntimeException (sqlState=42501, errorCode=-5501)", ReadOnlyJdbc.describe(wrapped));
        assertEquals("java.lang.IllegalStateException", ReadOnlyJdbc.describe(new IllegalStateException("x")));
    }
}
