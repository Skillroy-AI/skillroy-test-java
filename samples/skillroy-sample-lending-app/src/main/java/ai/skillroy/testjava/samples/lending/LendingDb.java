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

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.hsqldb.jdbc.JDBCDriver;

/**
 * The desk's embedded database: HSQLDB in memory ({@code jdbc:hsqldb:mem:<name>}, default name {@code lending}),
 * created and seeded at start-up.
 *
 * <p><b>Connections.</b> {@link #connection()} is the engine's own connection (auto-commit off); only the engine
 * thread uses it once the desk runs. Anything else (reporting, diagnostics, other threads) opens its own
 * connection with {@link #openReadOnlyConnection()}. The database uses MVCC, so readers never wait for the engine's
 * transactions and see only committed loans.
 *
 * <p><b>Accounts.</b> The desk connects as the database's administrator. A database made by {@link #create} has the
 * HSQLDB default administrator {@code SA} with an empty password, which is fine while nothing outside the JVM can
 * reach it. A database made by {@link #createServable} instead has one administrator whose name and password are
 * random and known only to this object, and no {@code SA}: that is the form {@link LendingDbServer} requires before it
 * opens a port. The reason is an HSQLDB 2.7 rule: any connected user may change the password of any user whose name
 * it knows, the administrator included, and a connected administrator can be neither dropped nor renamed. A
 * well-known administrator name would therefore let any client of the listener take the database over; a random one
 * that HSQLDB's information schema does not show to other users cannot be targeted.
 * {@link #createReadOnlyUser(String)} adds the account {@value #READER_USER}, with the password its caller chooses
 * (the desk passes {@code lending.dbPassword}), which may read every table and change nothing; it is the account a
 * reporting tool (or a test framework's controller-side channel) uses.
 *
 * <p>The database is found by the driver itself ({@link JDBCDriver}), not through {@code DriverManager}, so it works
 * from any class loader.
 */
public final class LendingDb implements AutoCloseable {

    /** Default database name. */
    public static final String DEFAULT_NAME = "lending";
    /** JDBC URL prefix; the URL is this plus the name. */
    public static final String URL_PREFIX = "jdbc:hsqldb:mem:";
    /** The read-only account created by {@link #createReadOnlyUser(String)} (a case-sensitive user name). */
    public static final String READER_USER = "reader";

    /** The administrator of a database made by {@link #create}. */
    static final String DEFAULT_ADMIN = "SA";

    private static final Logger LOG = Logger.getLogger(LendingDb.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String name;
    private final Seed seed;
    private final Edition edition;
    private final Connection connection;
    private final String adminUser;
    private final String adminPassword;
    private final boolean servable;
    private volatile boolean closed;

    private LendingDb(String name, Seed seed, Edition edition, Connection connection, String adminUser,
                      String adminPassword, boolean servable) {
        this.name = name;
        this.seed = seed;
        this.edition = edition;
        this.connection = connection;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.servable = servable;
    }

    /**
     * Creates (or re-creates: anything in a database of that name is dropped) the database, its schema and the
     * seed data, on the calling thread. Its administrator is {@code SA} with an empty password.
     *
     * @param name in-memory database name; {@link #DEFAULT_NAME} for the application
     * @param seed which seed to load
     * @param today business date that waiver rule windows are relative to
     */
    public static LendingDb create(String name, Seed seed, LocalDate today) throws SQLException {
        return create(name, seed, Edition.STANDARD, today, DEFAULT_ADMIN, "", false);
    }

    /**
     * As {@link #create(String, Seed, LocalDate)}, with the schema and settings of {@code edition}
     * (phase6-contracts.md 11.3).
     */
    public static LendingDb create(String name, Seed seed, Edition edition, LocalDate today) throws SQLException {
        return create(name, seed, edition, today, DEFAULT_ADMIN, "", false);
    }

    /**
     * Creates the database like {@link #create}, but with an administrator of random name and password that only
     * the returned object knows, and without {@code SA}: the form {@link LendingDbServer} may serve on a port (see
     * the class comment for why).
     *
     * @throws SQLException also when an in-memory database of that name is already open in this JVM (close it
     *     first); the message then names no account
     */
    public static LendingDb createServable(String name, Seed seed, LocalDate today) throws SQLException {
        return createServable(name, seed, Edition.STANDARD, today);
    }

    /** As {@link #createServable(String, Seed, LocalDate)}, with the schema and settings of {@code edition}. */
    public static LendingDb createServable(String name, Seed seed, Edition edition, LocalDate today)
            throws SQLException {
        return create(name, seed, edition, today, "ADMIN_" + randomHex(16), randomHex(24), true);
    }

    private static LendingDb create(String name, Seed seed, Edition edition, LocalDate today, String adminUser,
                                    String adminPassword, boolean servable) throws SQLException {
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(edition, "edition");
        Objects.requireNonNull(today, "today");
        if (name == null || !name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("database name must be [A-Za-z0-9_]+: " + name);
        }
        Connection c;
        try {
            c = connect(URL_PREFIX + name, adminUser, adminPassword);
        } catch (SQLException e) {
            if (!servable) {
                throw e;
            }
            // The first connection to a new in-memory database makes its administrator; this one was refused, so
            // a database of that name exists already. The driver's message would name the random account.
            throw new SQLException("an in-memory database named '" + name + "' is already open in this JVM; close it"
                    + " before creating a servable one", e.getSQLState(), e.getErrorCode());
        }
        boolean ok = false;
        try {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA PUBLIC CASCADE");
                st.execute("SET DATABASE TRANSACTION CONTROL MVCC");
            }
            LendingSchema.create(c, SeedData.firstLoanId(seed), edition);
            SeedData.apply(c, seed, today, edition);
            c.setAutoCommit(false);
            ok = true;
            return new LendingDb(name, seed, edition, c, adminUser, adminPassword, servable);
        } finally {
            if (!ok) {
                closeQuietly(c);
            }
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder hex = new StringBuilder(bytes * 2);
        for (byte x : b) {
            hex.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return hex.toString();
    }

    private static Connection connect(String url, String user, String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        Connection c = new JDBCDriver().connect(url, props);
        if (c == null) {
            throw new SQLException("HSQLDB refused the URL " + url);
        }
        return c;
    }

    public String name() {
        return name;
    }

    public Seed seed() {
        return seed;
    }

    /** @return the edition whose schema and settings the database holds */
    public Edition edition() {
        return edition;
    }

    /** @return the JDBC URL of the database */
    public String url() {
        return URL_PREFIX + name;
    }

    /**
     * The engine's connection: auto-commit off, writes allowed. Used by the thread that creates the database and,
     * once the desk runs, by the engine thread only. Never close it.
     */
    public Connection connection() {
        return connection;
    }

    /**
     * Opens a new read-only connection for any thread. The caller closes it. Writes through it fail.
     */
    public Connection openReadOnlyConnection() throws SQLException {
        Connection c = openAdminConnection();
        try {
            c.setReadOnly(true);
        } catch (SQLException e) {
            closeQuietly(c);
            throw e;
        }
        return c;
    }

    /** A new auto-commit administrator connection; the caller closes it. */
    private Connection openAdminConnection() throws SQLException {
        if (closed) {
            throw new SQLException("the lending database is closed");
        }
        return connect(url(), adminUser, adminPassword);
    }

    /**
     * The desk's report reader, which answers by position (phase8-contracts.md 12.1): runs one read-only statement on
     * a connection of its own ({@link #openReadOnlyConnection()}) with a query timeout and returns at most
     * {@code maxRows} rows as arrays of cell values, with no column names. While it runs, {@link ReadHandle#cancel()}
     * from another thread cancels the statement (the call then ends with the driver's {@code SQLException}); the
     * connection is closed on every path. Any thread.
     *
     * @param sql one SELECT or WITH statement (the reader runs what it is given; its callers check statements)
     * @param params positional parameters, bound with {@code setObject}
     * @param maxRows the most rows to return, at least 0
     * @param timeoutSeconds the statement's query timeout, raised to 1 if lower
     * @param handle where the open statement is registered for {@link ReadHandle#cancel()}; may be null
     */
    public List<Object[]> readRows(String sql, List<?> params, int maxRows, int timeoutSeconds, ReadHandle handle)
            throws SQLException {
        if (maxRows < 0) {
            throw new IllegalArgumentException("maxRows must not be negative");
        }
        List<Object[]> rows = new ArrayList<Object[]>();
        try (Connection c = openReadOnlyConnection();
             PreparedStatement ps = c.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            if (handle != null) {
                handle.attach(ps);
            }
            try {
                ps.setQueryTimeout(Math.max(1, timeoutSeconds));
                ps.setMaxRows(maxRows == Integer.MAX_VALUE ? 0 : maxRows + 1);
                for (int i = 0; params != null && i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    int width = rs.getMetaData().getColumnCount();
                    while (rows.size() < maxRows && rs.next()) {
                        Object[] row = new Object[width];
                        for (int i = 0; i < width; i++) {
                            row[i] = rs.getObject(i + 1);
                        }
                        rows.add(row);
                    }
                }
            } finally {
                if (handle != null) {
                    handle.detach(ps);
                }
            }
        }
        return rows;
    }

    /**
     * The column names of {@code table} in their declared order, from {@code INFORMATION_SCHEMA.COLUMNS} by
     * {@code ORDINAL_POSITION} (phase8-contracts.md 12.1), or null for a table the database does not have. The name
     * is compared as HSQLDB folds unquoted names (without regard to case); a {@code schema.table} name names its
     * schema, a bare name the schema {@code PUBLIC}.
     */
    public List<String> columnsOf(String table) throws SQLException {
        if (table == null || table.trim().isEmpty()) {
            return null;
        }
        String schema = "PUBLIC";
        String name = table.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            schema = name.substring(0, dot);
            name = name.substring(dot + 1);
        }
        List<String> columns = new ArrayList<String>();
        try (Connection c = openReadOnlyConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                 + " WHERE UPPER(TABLE_SCHEMA) = UPPER(?) AND UPPER(TABLE_NAME) = UPPER(?)"
                 + " ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns.isEmpty() ? null : columns;
    }

    /**
     * What a caller of {@link #readRows} holds to cancel the read while its statement runs (phase8-contracts.md
     * 12.1): {@link #cancel()} calls {@code Statement.cancel()} while, and only while, the statement is open; once the
     * read has ended it does nothing. Thread-safe.
     */
    public static final class ReadHandle {
        private Statement open;
        private boolean cancelled;

        synchronized void attach(Statement statement) {
            open = statement;
        }

        synchronized void detach(Statement statement) {
            if (open == statement) {
                open = null;
            }
        }

        /**
         * Cancels the statement if it is open now.
         *
         * @return true when an open statement was cancelled, false when none was open
         * @throws SQLException from the driver's {@code cancel()}
         */
        public synchronized boolean cancel() throws SQLException {
            if (open == null) {
                return false;
            }
            open.cancel();
            cancelled = true;
            return true;
        }

        /** @return true once {@link #cancel()} cancelled a statement */
        public synchronized boolean cancelled() {
            return cancelled;
        }
    }

    /** @return true for a database made by {@link #createServable}, which {@link LendingDbServer} may serve */
    public boolean isServable() {
        return servable;
    }

    /** For tests: the administrator's name (random for a servable database). */
    String administrator() {
        return adminUser;
    }

    /**
     * Creates the account {@value #READER_USER} with {@code password} if it does not exist (and sets that password if
     * it does), and grants it {@code SELECT} on every table of the desk's schema and nothing else: it cannot insert,
     * update or delete, create or drop anything, advance the loan id sequence, or run administrative statements
     * ({@code SHUTDOWN}, {@code CHECKPOINT}, {@code SET DATABASE …}). Like every HSQLDB user it can change the
     * password of an account whose name it knows, its own included; that changes no data, and the administrator of a
     * servable database has a name it cannot learn. Idempotent for the same password.
     *
     * <p>The password is taken literally: a {@code '} in it is doubled in the statement, the way SQL quotes it, so
     * every character may be used. It appears in no log line and in no exception this method throws (a failure of
     * the statement is reported with the SQL state and code only).
     *
     * <p>Call it at start-up, before the engine runs: granting is a schema change and waits for open transactions.
     * Any thread.
     *
     * @param password the account's password, not null and not blank
     * @throws IllegalArgumentException for a null or blank password, before anything is changed
     */
    public void createReadOnlyUser(String password) throws SQLException {
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("the read-only account " + READER_USER + " needs a password");
        }
        String literal = "'" + password.replace("'", "''") + "'";
        try (Connection c = openAdminConnection(); Statement st = c.createStatement()) {
            boolean exists;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.system_users WHERE user_name = ?")) {
                ps.setString(1, READER_USER);
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next() && rs.getInt(1) > 0;
                }
            }
            try {
                st.execute(exists ? "ALTER USER \"" + READER_USER + "\" SET PASSWORD " + literal
                        : "CREATE USER \"" + READER_USER + "\" PASSWORD " + literal);
            } catch (SQLException e) {
                // The driver's message may quote the statement, and so the password: state and code only.
                throw new SQLException("the read-only account " + READER_USER + " could not be "
                        + (exists ? "updated" : "created") + " (SQL state " + e.getSQLState() + ", code "
                        + e.getErrorCode() + ")", e.getSQLState(), e.getErrorCode());
            }
            List<String> tables = new ArrayList<String>();
            try (ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'PUBLIC' AND table_type = 'BASE TABLE' ORDER BY table_name")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                st.execute("GRANT SELECT ON TABLE PUBLIC.\"" + table.replace("\"", "\"\"") + "\" TO \""
                        + READER_USER + "\"");
            }
        }
    }

    /**
     * Reads {@code desk_setting} through a read-only connection; any thread.
     *
     * @return name to value, sorted by name
     */
    public Map<String, String> settings() throws SQLException {
        Map<String, String> map = new LinkedHashMap<String, String>();
        try (Connection c = openReadOnlyConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, value FROM desk_setting ORDER BY name")) {
            while (rs.next()) {
                map.put(rs.getString(1), rs.getString(2));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /** @return true after {@link #close()} */
    public boolean isClosed() {
        return closed;
    }

    /** Shuts the database down; every connection to it, read-only ones included, stops working. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try (Statement st = connection.createStatement()) {
            st.execute("SHUTDOWN");
        } catch (SQLException e) {
            LOG.log(Level.FINE, "shutdown of " + name + " failed", e);
        }
        closeQuietly(connection);
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            LOG.log(Level.FINE, "close failed", e);
        }
    }
}
