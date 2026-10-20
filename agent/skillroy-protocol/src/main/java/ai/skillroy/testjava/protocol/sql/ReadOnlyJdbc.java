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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The JDBC read path both data channels share (phase2-contracts.md 2.2): the agent's {@code data.query} on a connection
 * from {@code DataSourceProvider.open()}, and the controller's JDBC channel on the connection it keeps per source. One
 * implementation, so that a statement is bound with the same Java types and its values come back in the same forms
 * whichever side ran it.
 *
 * <p>The connection is set read-only before anything else ({@code SQLFeatureNotSupportedException} is ignored, any
 * other failure aborts the call), so a statement that slips past {@link StatementGuard} still cannot write where the
 * driver enforces read-only transactions. The statement is a forward-only, read-only {@code PreparedStatement} (never
 * a plain {@code Statement}, which some drivers let run several statements) with a query timeout and a row limit of
 * {@code maxRows + 1}, so that truncation is detected without reading further. Cells pass through
 * {@link ValueConverter}.
 *
 * <p>Every statement runs in a transaction this class ends itself (Phase 6 security fix, pass 2, P2-L3-F1 and
 * P2-L3-S1): after {@code setReadOnly(true)}, a connection in auto-commit mode is switched to manual commit
 * ({@code SQLFeatureNotSupportedException} leaves it as it was, any other failure aborts the call). A T-SQL batch can
 * open a transaction the guard does not see ({@code SET IMPLICIT_TRANSACTIONS ON} leaves the JDBC flag on), which would
 * otherwise stay open, with its locks, on the controller's kept connection or on a pooled connection the agent hands
 * back.
 *
 * <p>Afterwards, whatever happened: result set and statement are closed; the transaction (which holds only this query)
 * is rolled back, then auto-commit is switched back on where it was on (in that order: the JDBC rule commits an open
 * transaction on a mode change); a connection that was writable before is set writable again. The connection
 * itself is <em>not</em> closed: it belongs to the caller (the agent closes it after every call, the controller keeps
 * it). Failures while closing or restoring go to {@code warn}, never thrown, so they cannot hide the query's own
 * outcome; the text names the exception class, SQL state and error code but never the driver's message (finding
 * S22).
 *
 * <p>Stateless; runs on the calling thread, which it never blocks beyond the driver's own call. Java 8.
 */
public final class ReadOnlyJdbc {

    private ReadOnlyJdbc() {
    }

    /**
     * Checks statement parameters against the forms both channels bind (phase2-contracts.md 7.1, decision D23): each a
     * {@code String}, a {@code Long}, a {@code Boolean} or null. A decimal travels as its plain string and a date as
     * ISO-8601 text; the committed SQL casts.
     *
     * @throws IllegalArgumentException naming the first parameter of another type (never its value)
     */
    public static void checkParams(List<?> params) {
        if (params == null) {
            throw new IllegalArgumentException("params must be a list (empty for none)");
        }
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p != null && !(p instanceof String) && !(p instanceof Long) && !(p instanceof Boolean)) {
                throw new IllegalArgumentException("params[" + i + "] is a " + p.getClass().getName()
                        + "; a statement parameter must be a String, Long, Boolean or null (a decimal as its plain"
                        + " string, a date or time as ISO-8601 text)");
            }
        }
    }

    /**
     * Runs {@code sql} with parameters of the forms {@link #checkParams} allows and converts at most {@code maxRows}
     * rows.
     *
     * @param c an open connection; left open
     * @param sql a statement already accepted by {@link StatementGuard}; not checked again here
     * @param params positional parameters, bound with {@code setObject}
     * @param maxRows rows to keep, at least 0; one more is fetched to detect truncation
     * @param timeoutSeconds query timeout, raised to 1 if lower
     * @param warn receives the clean-up failures
     * @throws IllegalArgumentException if a parameter has another type (nothing has touched the connection then)
     * @throws SQLException from the driver
     * @throws InterruptedException if the calling thread is interrupted while rows are read
     */
    public static Rows execute(Connection c, String sql, List<Object> params, int maxRows, int timeoutSeconds,
                               Consumer<String> warn) throws SQLException, InterruptedException {
        checkParams(params);
        return executeLenient(c, sql, params, maxRows, timeoutSeconds, warn);
    }

    /**
     * {@link #execute} without the parameter check: binds whatever {@code params} holds. Only for the agent's
     * {@code data.query}, whose wire contract (phase1-contracts.md 6) accepts any JSON scalar, a {@code Double}
     * included, and which phase2-contracts.md 2.2 keeps unchanged. Every caller on the controller uses
     * {@link #execute}.
     *
     * @throws IllegalArgumentException if {@code maxRows} is negative or {@code params} is null
     */
    public static Rows executeLenient(Connection c, String sql, List<Object> params, int maxRows,
                                      int timeoutSeconds, Consumer<String> warn)
            throws SQLException, InterruptedException {
        return executeLenient(c, sql, params, maxRows, timeoutSeconds, warn, null);
    }

    /**
     * {@link #executeLenient(Connection, String, List, int, int, Consumer)} with a statement sink (phase8-contracts.md
     * 4.3, D530): {@code statementSink} receives the prepared statement right after {@code prepareStatement}, before
     * anything is bound or run, and {@code null} once the statement is closed, so that a caller on another thread can
     * {@code cancel()} the statement only while it is open (never a later statement of a pooled connection). A
     * statement cancelled while it runs ends {@code executeQuery} with the driver's {@code SQLException}, and the
     * connection is restored as on every other path. The sink runs on the calling thread; what it throws is reported to
     * {@code warn} by class name and does not change the outcome.
     *
     * @param statementSink receives the open statement, then null; may be null (no sink)
     * @throws IllegalArgumentException if {@code maxRows} is negative or {@code params} is null
     */
    public static Rows executeLenient(Connection c, String sql, List<Object> params, int maxRows,
                                      int timeoutSeconds, Consumer<String> warn, Consumer<Statement> statementSink)
            throws SQLException, InterruptedException {
        if (maxRows < 0) {
            throw new IllegalArgumentException("maxRows must not be negative");
        }
        if (params == null) {
            throw new IllegalArgumentException("params must be a list (empty for none)");
        }
        Consumer<String> out = warn == null ? new Consumer<String>() {
            @Override
            public void accept(String ignored) {
                // No sink: clean-up failures are dropped.
            }
        } : warn;
        boolean restoreWritable = false;
        boolean restoreAutoCommit = false;
        try {
            restoreWritable = makeReadOnly(c);
            restoreAutoCommit = beginTransaction(c);
            PreparedStatement statement = null;
            ResultSet resultSet = null;
            try {
                statement = c.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                hand(statementSink, statement, out);
                try {
                    statement.setQueryTimeout(Math.max(1, timeoutSeconds));
                } catch (SQLFeatureNotSupportedException unsupported) {
                    // The caller's own deadline still bounds the call.
                }
                try {
                    statement.setMaxRows(maxRows == Integer.MAX_VALUE ? maxRows : maxRows + 1);
                } catch (SQLFeatureNotSupportedException unsupported) {
                    // The row loop below enforces the limit anyway.
                }
                for (int i = 0; i < params.size(); i++) {
                    statement.setObject(i + 1, params.get(i));
                }
                resultSet = statement.executeQuery();
                return read(resultSet, maxRows);
            } finally {
                closeQuietly(resultSet, "result set", out);
                closeQuietly(statement, "statement", out);
                if (statement != null) {
                    hand(statementSink, null, out);
                }
            }
        } finally {
            restore(c, restoreAutoCommit, restoreWritable, out);
        }
    }

    /**
     * Switches an auto-commit connection to manual commit, so that the statement runs in a transaction
     * {@link #restore} ends (P2-L3-F1, P2-L3-S1); true when it did, and auto-commit is to be switched back on. A
     * connection already in manual commit is left so (and rolled back afterwards, as before); a driver that does not
     * support the switch leaves the connection as it was.
     */
    private static boolean beginTransaction(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            return false;
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLFeatureNotSupportedException unsupported) {
            return false;
        }
        return true;
    }

    /** Sets the connection read-only; true if it was writable before and should be set writable again. */
    private static boolean makeReadOnly(Connection connection) throws SQLException {
        boolean wasWritable;
        try {
            wasWritable = !connection.isReadOnly();
        } catch (SQLException unknown) {
            wasWritable = false;
        }
        try {
            connection.setReadOnly(true);
        } catch (SQLFeatureNotSupportedException unsupported) {
            return false;
        }
        return wasWritable;
    }

    private static Rows read(ResultSet resultSet, int maxRows) throws SQLException, InterruptedException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int width = meta.getColumnCount();
        List<Rows.Column> columns = new ArrayList<Rows.Column>(width);
        for (int c = 1; c <= width; c++) {
            String label = meta.getColumnLabel(c);
            String name = label == null || label.isEmpty() ? "c" + c : label;
            columns.add(new Rows.Column(name, meta.getColumnTypeName(c)));
        }
        List<List<Object>> rows = new ArrayList<List<Object>>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted while reading rows");
            }
            List<Object> row = new ArrayList<Object>(width);
            for (int c = 1; c <= width; c++) {
                row.add(ValueConverter.convert(resultSet.getObject(c)));
            }
            rows.add(row);
        }
        return Rows.of(columns, rows, truncated);
    }

    /**
     * Ends the read transaction (rolled back), then switches auto-commit back on where {@link #beginTransaction}
     * switched it off, then restores the read-only flag; never throws.
     */
    private static void restore(Connection connection, boolean restoreAutoCommit, boolean restoreWritable,
                                Consumer<String> warn) {
        try {
            if (restoreAutoCommit || !connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException | RuntimeException e) {
            warn(warn, "could not end the read transaction: " + describe(e));
        }
        if (restoreAutoCommit) {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException | RuntimeException e) {
                warn(warn, "could not switch auto-commit back on: " + describe(e));
            }
        }
        if (restoreWritable) {
            try {
                connection.setReadOnly(false);
            } catch (SQLException | RuntimeException e) {
                warn(warn, "could not restore the connection's read-only flag: " + describe(e));
            }
        }
    }

    /** Hands the statement (or null) to the sink, if any; a failing sink is reported, never thrown. */
    private static void hand(Consumer<Statement> sink, Statement statement, Consumer<String> warn) {
        if (sink == null) {
            return;
        }
        try {
            sink.accept(statement);
        } catch (RuntimeException e) {
            warn(warn, "the statement sink failed: " + e.getClass().getName());
        }
    }

    private static void closeQuietly(AutoCloseable resource, String what, Consumer<String> warn) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            warn(warn, "could not close the " + what + ": " + describe(e));
        }
    }

    private static void warn(Consumer<String> warn, String text) {
        try {
            warn.accept(text);
        } catch (RuntimeException ignored) {
            // A failing log sink must not hide the query's outcome.
        }
    }

    /**
     * {@code java.sql.SQLException (sqlState=42501, errorCode=-5501)} for a failure with an {@code SQLException} in
     * its cause chain (at most 16 deep), else the class name alone; never the message (finding S22).
     */
    public static String describe(Throwable t) {
        SQLException sql = sqlCause(t);
        String name = t.getClass().getName();
        return sql == null ? name : name + " (sqlState=" + sql.getSQLState() + ", errorCode=" + sql.getErrorCode()
                + ")";
    }

    /** The first {@code SQLException} in the cause chain of {@code t} ({@code t} included), at most 16 deep. */
    public static SQLException sqlCause(Throwable t) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable c = t;
        for (int depth = 0; c != null && depth < 16 && seen.add(c); depth++) {
            if (c instanceof SQLException) {
                return (SQLException) c;
            }
            c = c.getCause();
        }
        return null;
    }
}
