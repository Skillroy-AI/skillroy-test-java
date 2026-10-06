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

package ai.skillroy.testjava.data;

import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.sql.ReadOnlyJdbc;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.protocol.sql.StatementGuard;
import ai.skillroy.testjava.protocol.sql.ValueConverter;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The controller-side data channel (phase2-contracts.md 7.2, decision D29): serves the sources {@code credentials.yaml}
 * names under {@code jdbc:}, with the same statement guard, read path and value conversion as the agent
 * ({@link StatementGuard}, {@link ReadOnlyJdbc}), plus the pack's {@link RowMask}s on every value it returns.
 *
 * <p><b>Connections.</b> One per source, opened at first use and kept for the life of the channel ({@link #close()});
 * no pool. Calls for one source are serialised. Before each statement the connection is checked with
 * {@code isValid} (2 s) and reopened once if it is not. The account should be a read-only role (that is the boundary);
 * the connection is additionally set read-only and the statement guarded, as a safety net.
 *
 * <p><b>The driver.</b> {@code DriverManager} is not used: it sees only drivers visible to its caller's class loader
 * (a driver in a {@code --pack} jar sits in a child loader) and its login timeout is JVM-wide. With {@code driver} the
 * class is loaded by name through the thread's context class loader; without it the first
 * {@code ServiceLoader<java.sql.Driver>} provider of the context class loader that accepts the URL is used. Then
 * {@code Driver.connect(url, properties)} is called directly with {@code user}, {@code password} and the source's
 * properties, on a daemon thread, awaited for at most {@code loginTimeout}; a connection that arrives after the wait
 * expired is closed when it arrives.
 *
 * <p><b>Errors</b> ({@link DataException.Kind}): guard rejection {@code GUARD} (before any connection is used); no
 * driver, or {@code connect} returning null, {@code UNAVAILABLE}; {@code SQLTimeoutException} (or an SQL state of
 * class {@code HYT}) and an expired connect {@code TIMEOUT}; any other {@code SQLException} {@code SQL} with state,
 * code and class only; a {@code RowMask} that throws {@code MASK}, and the value appears nowhere. The password, the
 * URL and the driver's message appear in no exception, log line or {@link #toString()}.
 *
 * <p>Thread-safe. The waits are bounded: {@code isValid} by 2 s, the connect by {@code loginTimeout}, the statement by
 * {@code statementTimeout} (or the call's timeout), a caller waiting for another call on the same source by that
 * call. A call that gives a timeout (the binder passes what is left of its budget, phase2-contracts.md 6.4) waits at
 * most that long for each of them: the connect at most {@code min(loginTimeout, timeout)}, {@code isValid} at most
 * the timeout in whole seconds rounded up (2 s at most, never 0), and for another call on the same source at most the
 * timeout ({@code TIMEOUT}). A connect that arrives late is still closed.
 */
public final class JdbcDataChannel implements DataChannel, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(JdbcDataChannel.class.getName());

    /** Seconds {@code isValid} may take before a kept connection counts as gone. */
    static final int VALIDATION_SECONDS = 2;
    /** Most {@code java.sql.Driver} providers looked at when no driver class is named. */
    static final int MAX_DRIVERS = 100;

    private final Credentials credentials;
    private final List<RowMask> masks;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /** The connection of one source; guarded by its lock. */
    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        volatile Connection connection;
    }

    /**
     * @param credentials the environment's credentials; the sources under {@code jdbc:} are served
     * @param masks the pack's row masks, applied in order to every value (none: an empty list)
     */
    public JdbcDataChannel(Credentials credentials, List<RowMask> masks) {
        this.credentials = credentials == null ? Credentials.empty() : credentials;
        this.masks = masks == null ? List.of() : List.copyOf(masks);
    }

    @Override
    public boolean serves(String source) {
        return source != null && credentials.jdbc(source) != null;
    }

    @Override
    public String kind(String source) {
        return "jdbc";
    }

    /** The source's {@code statementTimeout} (10 s unless {@code credentials.yaml} says otherwise); null if unknown. */
    @Override
    public Duration defaultTimeout(String source) {
        Credentials.JdbcSource config = credentials.jdbc(source);
        return config == null ? null : config.statementTimeout();
    }

    @Override
    public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
            throws DataException, InterruptedException {
        Queries.check(source, sql, params, maxRows, timeout);
        Credentials.JdbcSource config = credentials.jdbc(source);
        if (config == null) {
            throw new DataException(DataException.Kind.UNKNOWN_SOURCE, source, "data source '" + source
                    + "' is not a JDBC source of " + credentials.origin() + "; JDBC sources: "
                    + credentials.jdbcSources());
        }
        String statement;
        try {
            statement = StatementGuard.check(sql);
        } catch (RpcException rejected) {
            Object reason = rejected.error().data() == null ? null : rejected.error().data().get("reason");
            throw new DataException(DataException.Kind.GUARD, source, "statement for source '" + source
                    + "' was refused by the statement guard" + (reason == null ? "" : ": " + reason));
        }
        int seconds = Queries.seconds(timeout == null ? config.statementTimeout() : timeout);
        Slot slot = slots.computeIfAbsent(source, id -> new Slot());
        lock(slot, source, timeout);
        try {
            Connection connection = usable(slot, config, timeout);
            Rows rows;
            try {
                rows = ReadOnlyJdbc.execute(connection, statement, params, maxRows, seconds, warn(source));
            } catch (SQLException e) {
                throw failure(source, "query", e);
            }
            return mask(source, rows);
        } finally {
            slot.lock.unlock();
        }
    }

    /** Calls for one source are serialised; with a timeout, a caller waits for the one before it at most that long. */
    private static void lock(Slot slot, String source, Duration timeout) throws DataException, InterruptedException {
        if (timeout == null) {
            slot.lock.lockInterruptibly();
            return;
        }
        long ms = Queries.millis(timeout);
        if (!slot.lock.tryLock(ms, TimeUnit.MILLISECONDS)) {
            throw new DataException(DataException.Kind.TIMEOUT, source, "another statement on JDBC source '" + source
                    + "' kept its connection for longer than " + ms + " ms");
        }
    }

    /** Seconds {@code isValid} may take: 2, or the timeout rounded up to whole seconds when that is shorter. */
    static int validationSeconds(Duration timeout) {
        if (timeout == null) {
            return VALIDATION_SECONDS;
        }
        return (int) Math.max(1, Math.min(VALIDATION_SECONDS, (Queries.millis(timeout) + 999) / 1000));
    }

    /** The kept connection if it is still valid, else a new one (one reconnect). Called under the slot's lock. */
    private Connection usable(Slot slot, Credentials.JdbcSource config, Duration timeout)
            throws DataException, InterruptedException {
        if (closed) {
            throw new DataException(DataException.Kind.UNAVAILABLE, config.id(), "the JDBC channel is closed");
        }
        Connection kept = slot.connection;
        if (kept != null) {
            boolean valid;
            try {
                valid = kept.isValid(validationSeconds(timeout));
            } catch (SQLException | RuntimeException e) {
                valid = false;
            }
            if (valid) {
                return kept;
            }
            slot.connection = null;
            closeQuietly(kept, config.id());
            LOG.log(System.Logger.Level.INFO, "jdbc source '" + config.id() + "': the kept connection is no longer"
                    + " valid; reconnecting once");
        }
        Connection fresh = connect(config, timeout);
        if (closed) {
            closeQuietly(fresh, config.id());
            throw new DataException(DataException.Kind.UNAVAILABLE, config.id(), "the JDBC channel is closed");
        }
        slot.connection = fresh;
        return fresh;
    }

    /** {@code Driver.connect} on a daemon thread, awaited at most {@code loginTimeout} (or the shorter timeout). */
    private Connection connect(Credentials.JdbcSource config, Duration timeout)
            throws DataException, InterruptedException {
        String source = config.id();
        long waitMs = Math.max(1, config.loginTimeout().toMillis());
        if (timeout != null) {
            waitMs = Math.min(waitMs, Queries.millis(timeout));
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = JdbcDataChannel.class.getClassLoader();
        }
        Driver driver = driver(config, loader);
        Properties properties = new Properties();
        config.properties().forEach(properties::setProperty);
        if (config.user() != null) {
            properties.setProperty("user", config.user());
        }
        if (config.password() != null) {
            properties.setProperty("password", config.password());
        }
        CompletableFuture<Connection> future = new CompletableFuture<>();
        ClassLoader contextLoader = loader;
        Thread thread = new Thread(() -> {
            try {
                future.complete(driver.connect(config.url(), properties));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "skillroy-jdbc-connect-" + source);
        thread.setDaemon(true);
        thread.setContextClassLoader(contextLoader);
        thread.start();
        Connection connection;
        try {
            connection = future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.whenComplete((late, error) -> closeQuietly(late, source));
            throw new DataException(DataException.Kind.TIMEOUT, source, "connecting to JDBC source '" + source
                    + "' took longer than " + waitMs + " ms");
        } catch (InterruptedException e) {
            future.whenComplete((late, error) -> closeQuietly(late, source));
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof SQLException sql) {
                throw failure(source, "connect", sql);
            }
            throw new DataException(DataException.Kind.OTHER, source, "connecting to JDBC source '" + source
                    + "' failed (" + cause.getClass().getName() + ")", null, 0, cause.getClass().getName(), null);
        }
        if (connection == null) {
            throw new DataException(DataException.Kind.UNAVAILABLE, source, "the JDBC driver "
                    + driver.getClass().getName() + " did not accept the URL of source '" + source
                    + "' (Driver.connect returned null)");
        }
        try {
            connection.setReadOnly(true);
        } catch (SQLException | RuntimeException e) {
            // The read path sets it again before every statement and fails the statement if that fails.
            LOG.log(System.Logger.Level.DEBUG, () -> "jdbc source '" + source + "': setReadOnly after connect failed: "
                    + ReadOnlyJdbc.describe(e));
        }
        return connection;
    }

    private static Driver driver(Credentials.JdbcSource config, ClassLoader loader) throws DataException {
        String source = config.id();
        if (config.driver() != null) {
            try {
                Class<?> type = Class.forName(config.driver(), true, loader);
                if (!Driver.class.isAssignableFrom(type)) {
                    throw new DataException(DataException.Kind.UNAVAILABLE, source, "the class " + config.driver()
                            + " named as driver of JDBC source '" + source + "' is not a java.sql.Driver");
                }
                return (Driver) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                throw new DataException(DataException.Kind.UNAVAILABLE, source, "the driver " + config.driver()
                        + " of JDBC source '" + source + "' cannot be loaded (" + e.getClass().getName() + ")", null,
                        0, e.getClass().getName(), null);
            }
        }
        Iterator<Driver> drivers = ServiceLoader.load(Driver.class, loader).iterator();
        // Bounded: after a provider that cannot be loaded the iterator goes on with the next one.
        for (int seen = 0; seen < MAX_DRIVERS; seen++) {
            Driver candidate;
            try {
                if (!drivers.hasNext()) {
                    break;
                }
                candidate = drivers.next();
            } catch (ServiceConfigurationError | LinkageError broken) {
                LOG.log(System.Logger.Level.DEBUG, () -> "skipping a JDBC driver that cannot be loaded: "
                        + broken.getClass().getName());
                continue;
            }
            try {
                if (candidate.acceptsURL(config.url())) {
                    return candidate;
                }
            } catch (SQLException | RuntimeException ignored) {
                // A driver that cannot judge the URL does not serve it.
            }
        }
        throw new DataException(DataException.Kind.UNAVAILABLE, source, "no JDBC driver on the class path accepts the"
                + " URL of source '" + source + "'; put the driver jar on the class path (the CLI: --pack <jar>) or"
                + " name its class with 'driver'");
    }

    /** The rows with every row mask applied to every value, in order; a mask that throws is MASK, without the value. */
    private Rows mask(String source, Rows rows) throws DataException {
        if (masks.isEmpty()) {
            return rows;
        }
        List<List<Object>> out = new ArrayList<>(rows.rowCount());
        for (List<Object> cells : rows.rows()) {
            List<Object> row = new ArrayList<>(cells);
            for (int c = 0; c < row.size(); c++) {
                String column = rows.columns().get(c).name();
                Object value = row.get(c);
                for (RowMask mask : masks) {
                    try {
                        value = mask.mask(source, column, value);
                        if (!ValueConverter.isNormal(value)) {
                            value = ValueConverter.convert(value);
                        }
                    } catch (SQLException | RuntimeException e) {
                        LOG.log(System.Logger.Level.WARNING, "jdbc source '" + source + "': RowMask "
                                + mask.getClass().getName() + " failed on column '" + column + "': "
                                + e.getClass().getName());
                        throw new DataException(DataException.Kind.MASK, source, "RowMask " + mask.getClass().getName()
                                + " failed on column '" + column + "' of source '" + source + "'; no rows are returned",
                                null, 0, e.getClass().getName(), null);
                    }
                }
                row.set(c, value);
            }
            out.add(row);
        }
        return Rows.of(rows.columns(), out, rows.truncated());
    }

    private static DataException failure(String source, String what, SQLException e) {
        String state = e.getSQLState();
        boolean timeout = e instanceof SQLTimeoutException || state != null && state.startsWith("HYT");
        String text = (what.equals("connect") ? "connecting to" : "query on") + " JDBC source '" + source
                + "' failed: sqlState=" + state + ", errorCode=" + e.getErrorCode() + " (" + e.getClass().getName()
                + ")";
        return new DataException(timeout ? DataException.Kind.TIMEOUT : DataException.Kind.SQL, source, text, state,
                e.getErrorCode(), e.getClass().getName(), null);
    }

    private static Consumer<String> warn(String source) {
        return line -> LOG.log(System.Logger.Level.WARNING, "jdbc source '" + source + "': " + line);
    }

    private static void closeQuietly(Connection connection, String source) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "jdbc source '" + source + "': closing the connection failed: "
                    + ReadOnlyJdbc.describe(e));
        }
    }

    /** True while a connection of the source is kept (tests). */
    boolean connected(String source) {
        Slot slot = slots.get(source);
        return slot != null && slot.connection != null;
    }

    /** Closes every kept connection; later queries fail as {@code UNAVAILABLE}. Idempotent; never throws. */
    @Override
    public void close() {
        closed = true;
        for (Map.Entry<String, Slot> e : slots.entrySet()) {
            Connection connection = e.getValue().connection;
            e.getValue().connection = null;
            closeQuietly(connection, e.getKey());
        }
    }

    /** The served source ids only. */
    @Override
    public String toString() {
        return "JdbcDataChannel" + credentials.jdbcSources() + (closed ? "[closed]" : "");
    }
}
