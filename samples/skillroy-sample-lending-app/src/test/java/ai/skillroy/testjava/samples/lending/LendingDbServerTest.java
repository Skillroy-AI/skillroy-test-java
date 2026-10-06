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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.hsqldb.jdbc.JDBCDriver;
import org.junit.jupiter.api.Test;

/**
 * The database listener of the sample ({@code lending.dbPort}, {@link LendingDbServer}): off by default, never without
 * the read-only account's password ({@code lending.dbPassword}), on loopback only, read-only for its one account,
 * closed for the administrator, stopped with the desk.
 */
class LendingDbServerTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    /** The read-only account's password in these tests ({@code lending.dbPassword}); test-local, any value works. */
    private static final String READER_PASSWORD = "Reader-Test-4826";

    /** Every statement the read-only account must be refused, each valid for the administrator. */
    private static final List<String> WRITES = Collections.unmodifiableList(Arrays.asList(
        "INSERT INTO desk_setting (name, value) VALUES ('x', 'y')",
        "UPDATE item SET deposit = 0 WHERE code = 'YRD-001'",
        "DELETE FROM staff_role",
        "TRUNCATE TABLE loan_line",
        "MERGE INTO desk_setting d USING (VALUES ('max_return', '1.00')) AS v (n, x) ON d.name = v.n"
            + " WHEN MATCHED THEN UPDATE SET d.value = v.x",
        "CREATE TABLE extra (id INTEGER)",
        "DROP TABLE loan_deposit",
        "ALTER TABLE item ADD COLUMN extra INTEGER",
        "CALL NEXT VALUE FOR loan_id_seq",
        "SELECT NEXT VALUE FOR loan_id_seq FROM desk_setting",
        "CREATE SCHEMA other",
        "CREATE USER other PASSWORD 'other-password'",
        "SET DATABASE TRANSACTION CONTROL LOCKS",
        "SET DATABASE AUTHENTICATION FUNCTION NONE",
        "SET FILES LOG FALSE",
        "CHECKPOINT",
        "SHUTDOWN"));
    /**
     * HSQLDB's states for "user lacks privilege or object not found", "admin rights required" and "invalid grantor"
     * (creating a schema without the right to).
     */
    private static final List<String> RIGHTS_MISSING = Arrays.asList("42501", "42507", "0L501");

    private static String name(String what) {
        return "dbserver_" + what + "_" + COUNTER.incrementAndGet();
    }

    private static LendingApp launch(String what, int port) throws Exception {
        return LendingApp.launch(LendingOptions.defaults().seed(Seed.A).ui(false).dbName(name(what)).dbPort(port)
            .dbPassword(READER_PASSWORD));
    }

    private static Connection connect(String url, String user, String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        Connection c = new JDBCDriver().connect(url, props);
        assertNotNull(c, "the driver accepts " + url);
        return c;
    }

    private static Connection reader(LendingDbServer server) throws SQLException {
        return connect(server.url(), LendingDb.READER_USER, READER_PASSWORD);
    }

    private static List<List<String>> rows(Connection c, String sql, String... params) throws SQLException {
        List<List<String>> rows = new ArrayList<List<String>>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> row = new ArrayList<String>();
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        row.add(v instanceof BigDecimal ? ((BigDecimal) v).toPlainString() : String.valueOf(v));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static List<String> tables(LendingDb db) throws SQLException {
        List<String> tables = new ArrayList<String>();
        try (Connection c = db.openReadOnlyConnection()) {
            for (List<String> row : rows(c, "SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = 'PUBLIC' AND table_type = 'BASE TABLE' ORDER BY table_name")) {
                tables.add(row.get(0));
            }
        }
        return tables;
    }

    @Test
    void theDeskOpensNoPortUnlessAskedTo() throws Exception {
        assertEquals(LendingOptions.NO_DB_PORT, LendingOptions.defaults().dbPort());
        assertEquals(LendingOptions.NO_DB_PORT, LendingOptions.fromProperties(new Properties()).dbPort());
        LendingApp app = LendingApp.launch(LendingOptions.defaults().ui(false).dbName(name("off")));
        try {
            assertNull(app.dbServer(), "no listener without lending.dbPort");
        } finally {
            app.shutdown();
        }
    }

    @Test
    void thePortIsReadFromTheSystemPropertyAndChecked() {
        Properties p = new Properties();
        p.setProperty(LendingOptions.PROP_DB_PORT, " 9101 ");
        assertEquals(9101, LendingOptions.fromProperties(p).dbPort());
        p.setProperty(LendingOptions.PROP_DB_PORT, "0");
        assertEquals(0, LendingOptions.fromProperties(p).dbPort(), "0 = any free port");
        p.setProperty(LendingOptions.PROP_DB_PORT, " ");
        assertEquals(LendingOptions.NO_DB_PORT, LendingOptions.fromProperties(p).dbPort(), "blank = none");
        for (String bad : Arrays.asList("-1", "65536", "port", "9101.5")) {
            p.setProperty(LendingOptions.PROP_DB_PORT, bad);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LendingOptions.fromProperties(p), bad);
            assertTrue(e.getMessage().startsWith("lending.dbPort must be"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> LendingOptions.defaults().dbPort(-2));
        assertThrows(IllegalArgumentException.class, () -> LendingOptions.defaults().dbPort(70000));
        assertEquals(LendingOptions.NO_DB_PORT, LendingOptions.defaults().dbPort(5).dbPort(-1).dbPort());
    }

    @Test
    void thePasswordIsReadFromTheSystemPropertyAsGiven() {
        Properties p = new Properties();
        assertNull(LendingOptions.fromProperties(p).dbPassword(), "none by default");
        assertNull(LendingOptions.defaults().dbPassword());
        p.setProperty(LendingOptions.PROP_DB_PASSWORD, " Pass word 'q' ");
        assertEquals(" Pass word 'q' ", LendingOptions.fromProperties(p).dbPassword(), "taken as given, not trimmed");
        for (String none : Arrays.asList("", " ", "\t")) {
            p.setProperty(LendingOptions.PROP_DB_PASSWORD, none);
            assertNull(LendingOptions.fromProperties(p).dbPassword(), "empty or blank = none: '" + none + "'");
            assertNull(LendingOptions.defaults().dbPassword(none).dbPassword());
        }
        assertNull(LendingOptions.defaults().dbPassword("x").dbPassword(null).dbPassword());
        assertEquals("lending.dbPassword", LendingOptions.PROP_DB_PASSWORD);
    }

    /**
     * Contract 13.1: a listener needs the read-only account's password. A start with {@code lending.dbPort} but
     * without {@code lending.dbPassword} (or with an empty or blank one), from system properties or from options set
     * in code, fails with a message that names the property, before anything is created: no desk is published, and
     * no database of that name exists afterwards (a servable one can still be made, which is refused while one is
     * open).
     */
    @Test
    void aListenerWithoutThePasswordIsRefusedBeforeAnythingIsCreated() throws Exception {
        LendingApp before = LendingApp.instance();
        String name = name("nopassword");
        List<LendingOptions> refused = new ArrayList<LendingOptions>();
        refused.add(LendingOptions.defaults().ui(false).dbName(name).dbPort(0));
        refused.add(LendingOptions.defaults().ui(false).dbName(name).dbPort(9101).dbPassword(""));
        refused.add(LendingOptions.defaults().ui(false).dbName(name).dbPort(0).dbPassword(" "));
        Properties p = new Properties();
        p.setProperty(LendingOptions.PROP_DB_PORT, "0");
        refused.add(LendingOptions.fromProperties(p).ui(false).dbName(name));
        p.setProperty(LendingOptions.PROP_DB_PASSWORD, "");
        refused.add(LendingOptions.fromProperties(p).ui(false).dbName(name));
        for (final LendingOptions options : refused) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LendingApp.launch(options));
            assertEquals("lending.dbPassword is required when lending.dbPort is set: it is the password of the"
                + " read-only account reader", e.getMessage());
            assertTrue(LendingApp.instance() == before, "no desk was published");
        }
        LendingDb.createServable(name, Seed.A, LocalDate.of(2026, 10, 1)).close();
        LendingApp app = LendingApp.launch(LendingOptions.defaults().ui(false).dbName(name).dbPort(0)
            .dbPassword(READER_PASSWORD));
        try {
            assertTrue(app.dbServer().isRunning(), "with the password the same options start the listener");
        } finally {
            app.shutdown();
        }
        LendingApp none = LendingApp.launch(LendingOptions.defaults().ui(false).dbName(name("nolistener"))
            .dbPassword(READER_PASSWORD));
        try {
            assertNull(none.dbServer(), "a password alone opens no listener");
        } finally {
            none.shutdown();
        }
    }

    /**
     * The password given is the account's, character for character: a {@code '} is doubled in the statement, as SQL
     * quotes it, so the password works as given and its SQL-quoted form does not. Null and blank passwords are
     * refused before anything is changed.
     */
    @Test
    void theReaderHasExactlyThePasswordGiven() throws Exception {
        String quoted = "it's a 'quoted' pass";
        try (LendingDb db = LendingDb.createServable(name("quote"), Seed.A, LocalDate.of(2026, 10, 1))) {
            for (final String bad : Arrays.asList(null, "", "  ")) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LendingDbServer.start(db, 0, bad));
                assertEquals("the read-only account reader needs a password", e.getMessage());
                assertThrows(IllegalArgumentException.class, () -> db.createReadOnlyUser(bad));
            }
            try (Connection c = db.openReadOnlyConnection()) {
                assertEquals(Collections.singletonList(Collections.singletonList("0")), rows(c,
                    "SELECT COUNT(*) FROM information_schema.system_users WHERE user_name = 'reader'"),
                    "a refused password created no account");
            }
            LendingDbServer server = LendingDbServer.start(db, 0, quoted);
            try {
                try (Connection reader = connect(server.url(), LendingDb.READER_USER, quoted)) {
                    assertEquals(Collections.singletonList(Collections.singletonList("7")),
                        rows(reader, "SELECT COUNT(*) FROM staff"));
                }
                for (final String wrong : Arrays.asList("it''s a ''quoted'' pass", "its a quoted pass", "")) {
                    SQLException e = assertThrows(SQLException.class,
                        () -> connect(server.url(), LendingDb.READER_USER, wrong).close(), wrong);
                    assertTrue(e.getSQLState() != null && e.getSQLState().startsWith("28"),
                        e.getSQLState() + " " + e.getMessage());
                }
            } finally {
                server.close();
            }
        }
    }

    @Test
    void theReaderSeesEveryTableAsTheDeskDoes() throws Exception {
        LendingApp app = launch("tables", 0);
        try {
            LendingDbServer server = app.dbServer();
            assertNotNull(server);
            assertTrue(server.isRunning());
            assertTrue(server.port() > 0, "the free port chosen is reported: " + server.port());
            assertEquals("127.0.0.1", server.address());
            assertEquals("jdbc:hsqldb:hsql://127.0.0.1:" + server.port() + "/" + app.db().name(), server.url());

            List<String> tables = tables(app.db());
            assertEquals(14, tables.size(), String.valueOf(tables));
            try (Connection inProcess = app.db().openReadOnlyConnection(); Connection reader = reader(server)) {
                for (String table : tables) {
                    assertEquals(rows(inProcess, "SELECT * FROM " + table), rows(reader, "SELECT * FROM " + table),
                        table + ": the same rows through the listener");
                    String columns = "SELECT column_name, data_type, is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = ? ORDER BY ordinal_position";
                    List<List<String>> described = rows(reader, columns, table);
                    assertFalse(described.isEmpty(), table);
                    assertEquals(rows(inProcess, columns, table), described,
                        table + ": the catalogue describes it alike for both accounts (a schema checksum agrees)");
                }
                assertEquals(Collections.singletonList(Arrays.asList("100", "Alice Archer")),
                    rows(reader, "SELECT code, name FROM staff WHERE code = ?", "100"));
                List<String> listenerThreads = new ArrayList<String>();
                for (Thread t : Thread.getAllStackTraces().keySet()) {
                    if (t.isAlive() && t.getName().startsWith("HSQLDB")) {
                        listenerThreads.add(t.getName());
                        assertTrue(t.isDaemon(), t.getName() + " must not keep the desk's JVM alive");
                    }
                }
                assertTrue(listenerThreads.size() >= 2, "the listener and the client's connection: "
                    + listenerThreads);
            }
            assertEquals("500.00", app.db().settings().get("max_return"), "the desk's own connections still work");
        } finally {
            app.shutdown();
        }
    }

    @Test
    void theReaderCanChangeNothing() throws Exception {
        LendingApp app = launch("writes", 0);
        try {
            List<String> refused = new ArrayList<String>();
            try (Connection reader = reader(app.dbServer())) {
                reader.setReadOnly(false);
                reader.setAutoCommit(true);
                assertFalse(reader.isReadOnly(), "the account is the boundary, not the connection's flag");
                for (String sql : WRITES) {
                    try (Statement st = reader.createStatement()) {
                        st.execute(sql);
                        refused.add("ACCEPTED " + sql);
                    } catch (SQLException e) {
                        // HSQLDB 2.7 answers a MERGE without the rights with a "General error" (S1000) instead of
                        // 42501; the statement is refused all the same, and the checks below show nothing changed.
                        boolean merge = sql.startsWith("MERGE") && "S1000".equals(e.getSQLState());
                        assertTrue(merge || RIGHTS_MISSING.contains(e.getSQLState()),
                            "a missing right, not a syntax error: " + e.getSQLState() + " " + e.getMessage() + ": "
                                + sql);
                    }
                }
            }
            assertEquals(Collections.emptyList(), refused, "every write is refused");
            try (Connection c = app.db().openReadOnlyConnection()) {
                assertEquals(Collections.singletonList(Collections.singletonList("1001")), rows(c,
                    "SELECT next_value FROM information_schema.sequences WHERE sequence_name = 'LOAN_ID_SEQ'"),
                    "the loan id sequence did not move");
                assertEquals(Collections.singletonList(Collections.singletonList("3.50")),
                    rows(c, "SELECT deposit FROM item WHERE code = 'YRD-001'"));
                assertEquals(Collections.singletonList(Collections.singletonList("8")),
                    rows(c, "SELECT COUNT(*) FROM staff_role"));
                assertEquals(14, tables(app.db()).size());
            }
            assertEquals("500.00", app.db().settings().get("max_return"));
            assertEquals(2, app.db().settings().size());
            assertTrue(app.dbServer().isRunning(), "SHUTDOWN was refused");
        } finally {
            app.shutdown();
        }
    }

    /**
     * HSQLDB accepts a {@code GRANT} from a user who holds no grantable right and grants nothing (as the SQL standard
     * has it): the account still cannot write, and no right was added.
     */
    @Test
    void aGrantByTheReaderGrantsNothing() throws Exception {
        LendingApp app = launch("grant", 0);
        try {
            String privileges = "SELECT grantee, privilege_type FROM information_schema.table_privileges"
                + " WHERE table_schema = 'PUBLIC' AND table_name = 'STAFF' ORDER BY grantee, privilege_type";
            List<List<String>> before;
            try (Connection c = app.db().openReadOnlyConnection()) {
                before = rows(c, privileges);
            }
            try (Connection reader = reader(app.dbServer()); Statement st = reader.createStatement()) {
                for (String grant : Arrays.asList("GRANT INSERT ON TABLE staff TO PUBLIC",
                    "GRANT ALL PRIVILEGES ON TABLE staff TO \"" + LendingDb.READER_USER + "\"")) {
                    try {
                        st.execute(grant);
                    } catch (SQLException refused) {
                        assertTrue(RIGHTS_MISSING.contains(refused.getSQLState()), refused.getMessage());
                    }
                }
                SQLException e = assertThrows(SQLException.class,
                    () -> st.executeUpdate("INSERT INTO staff (code, name) VALUES ('999', 'Nobody')"));
                assertEquals("42501", e.getSQLState());
            }
            try (Connection c = app.db().openReadOnlyConnection()) {
                assertEquals(before, rows(c, privileges), "no right was added");
            }
        } finally {
            app.shutdown();
        }
    }

    /**
     * HSQLDB lets any user change the password of any user whose name it knows, so the administrator of a served
     * database has a random name: the read-only account must not be able to find it anywhere, and the well-known
     * names lead nowhere.
     */
    @Test
    void theAdministratorCanBeNeitherSeenNorTakenOver() throws Exception {
        LendingApp app = launch("admin_hidden", 0);
        try {
            String admin = app.db().administrator();
            assertTrue(admin.matches("ADMIN_[0-9a-f]{32}"), admin);
            try (Connection reader = reader(app.dbServer())) {
                List<String> views = new ArrayList<String>();
                for (List<String> row : rows(reader, "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'INFORMATION_SCHEMA' ORDER BY table_name")) {
                    views.add(row.get(0));
                }
                assertTrue(views.size() > 50, "the reader sees the information schema: " + views.size());
                int read = 0;
                for (String view : views) {
                    List<List<String>> content;
                    try {
                        content = rows(reader, "SELECT * FROM information_schema." + view);
                    } catch (SQLException unreadable) {
                        continue;
                    }
                    read++;
                    for (List<String> row : content) {
                        for (String cell : row) {
                            assertFalse(cell.contains(admin),
                                "information_schema." + view + " shows the administrator: " + row);
                        }
                    }
                }
                assertTrue(read > 50, "views read: " + read);
                for (String sql : Arrays.asList("ALTER USER SA SET PASSWORD 'taken-over'",
                    "SET SESSION AUTHORIZATION 'SA'", "SET SESSION AUTHORIZATION '" + admin + "'", "SCRIPT",
                    "DROP USER \"" + LendingDb.READER_USER + "\"")) {
                    try (Statement st = reader.createStatement()) {
                        SQLException e = assertThrows(SQLException.class, () -> st.execute(sql), sql);
                        assertTrue(e.getSQLState().startsWith("28") || RIGHTS_MISSING.contains(e.getSQLState()),
                            sql + ": " + e.getSQLState() + " " + e.getMessage());
                    }
                }
            }
            for (String password : Arrays.asList("", "taken-over")) {
                assertThrows(SQLException.class,
                    () -> connect(app.dbServer().url(), "SA", password).close(), "there is no SA");
            }
            try (Connection c = app.db().openReadOnlyConnection()) {
                assertTrue(c.isValid(2), "the desk's own administrator is unchanged");
            }
        } finally {
            app.shutdown();
        }
    }

    @Test
    void onlyAServableDatabaseIsServed() throws Exception {
        try (LendingDb plain = LendingDb.create(name("plain"), Seed.A, LocalDate.of(2026, 10, 1))) {
            assertFalse(plain.isServable());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LendingDbServer.start(plain, 0, READER_PASSWORD));
            assertTrue(e.getMessage().contains("SA without a password"), e.getMessage());
        }
        String name = name("twice");
        try (LendingDb first = LendingDb.createServable(name, Seed.A, LocalDate.of(2026, 10, 1))) {
            assertTrue(first.isServable());
            SQLException e = assertThrows(SQLException.class,
                () -> LendingDb.createServable(name, Seed.B, LocalDate.of(2026, 10, 1)));
            assertTrue(e.getMessage().contains("is already open in this JVM"), e.getMessage());
            assertFalse(e.getMessage().contains("ADMIN_"), "names no account: " + e.getMessage());
            assertNull(e.getCause(), "and carries no driver message that would");
            assertEquals("500.00", first.settings().get("max_return"), "the open database is untouched");
            assertThrows(IllegalArgumentException.class,
                () -> LendingDbServer.start(first, 65536, READER_PASSWORD));
        }
    }

    /** The refusals above are about rights: the same statements, run by the administrator, are all accepted. */
    @Test
    void theRefusedStatementsAreValidForTheAdministrator() throws Exception {
        LendingDb db = LendingDb.create(name("admin"), Seed.A, LocalDate.of(2026, 10, 1));
        try (Connection admin = connect(db.url(), "SA", "")) {
            admin.setAutoCommit(true);
            for (String sql : WRITES) {
                try (Statement st = admin.createStatement()) {
                    st.execute(sql);
                }
            }
        } finally {
            db.close();
        }
    }

    @Test
    void nobodyElseCanConnectThroughTheListener() throws Exception {
        LendingApp app = launch("accounts", 0);
        try {
            String url = app.dbServer().url();
            String admin = app.db().administrator();
            for (String[] account : new String[][] {
                {"SA", ""}, {"sa", ""}, {"SA", READER_PASSWORD}, {admin, ""}, {admin, "wrong"},
                {admin, READER_PASSWORD}, {LendingDb.READER_USER, ""}, {LendingDb.READER_USER, "wrong"},
                {"READER", READER_PASSWORD}, {"other", "other"}}) {
                SQLException e = assertThrows(SQLException.class, () -> connect(url, account[0], account[1]).close(),
                    account[0] + "/" + account[1]);
                assertTrue(e.getSQLState() != null && e.getSQLState().startsWith("28"),
                    account[0] + ": an invalid authorization, not another failure: " + e.getSQLState() + " "
                        + e.getMessage());
            }
            try (Connection reader = reader(app.dbServer())) {
                assertTrue(reader.isValid(2));
            }
            try (Connection inProcess = app.db().openReadOnlyConnection()) {
                assertTrue(inProcess.isValid(2), "the desk itself still connects as the administrator");
            }
        } finally {
            app.shutdown();
        }
    }

    @Test
    void committedLoansAreSeenThroughTheListenerAndClosingItLeavesTheDatabaseOpen() throws Exception {
        try (EngineHarness desk = EngineHarness.startServable(Seed.A)) {
            LendingDbServer server = LendingDbServer.start(desk.db, 0, READER_PASSWORD);
            try (Connection reader = reader(server)) {
                assertEquals(Collections.singletonList(Collections.singletonList("0")),
                    rows(reader, "SELECT COUNT(*) FROM loan"));
                desk.signOn("100", "1111");
                desk.scan("YRD-001");
                desk.key(KeyAction.DUE);
                EngineHarness.expectRequested(desk.key(KeyAction.DEPOSIT, "10.00"), LendingState.IDLE);
                assertEquals(Collections.singletonList(Arrays.asList("1001", "100", "3.50")),
                    rows(reader, "SELECT id, staff_code, total FROM loan"));
                assertEquals(Collections.singletonList(Arrays.asList("1", "YRD-001", "3.50", "false")),
                    rows(reader, "SELECT line_no, item_code, amount, returned FROM loan_line WHERE loan_id = 1001"));
            } finally {
                server.close();
            }
            assertFalse(server.isRunning());
            server.close();
            assertEquals(Collections.singletonList(Arrays.<Object>asList(1L)),
                desk.query("SELECT COUNT(*) FROM loan"), "the database stays open for the desk");
            desk.scan("YRD-002");
            EngineHarness.expectRequested(desk.key(KeyAction.CANCEL), LendingState.IDLE);
        }
    }

    @Test
    void theListenerStopsWithTheDeskAndItsPortCanBeUsedAgain() throws Exception {
        LendingApp first = launch("stop", 0);
        LendingDbServer server = first.dbServer();
        int port = server.port();
        Connection open = reader(server);
        try {
            first.shutdown();
            assertFalse(server.isRunning());
            assertRefused(InetAddress.getByName("127.0.0.1"), port);
            assertThrows(SQLException.class, () -> rows(open, "SELECT COUNT(*) FROM staff"),
                "an open client connection ends with the listener");
        } finally {
            try {
                open.close();
            } catch (SQLException ignored) {
                // already closed by the listener
            }
        }
        LendingApp second = launch("again", port);
        try {
            assertEquals(port, second.dbServer().port(), "the same fixed port, released by the first desk");
            try (Connection reader = reader(second.dbServer())) {
                assertEquals(Collections.singletonList(Collections.singletonList("7")),
                    rows(reader, "SELECT COUNT(*) FROM staff"));
            }
        } finally {
            second.shutdown();
        }
    }

    @Test
    void aPortInUseFailsTheLaunch() throws Exception {
        LendingApp before = LendingApp.instance();
        String name = name("busy");
        try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = busy.getLocalPort();
            SQLException e = assertThrows(SQLException.class, () -> LendingApp.launch(LendingOptions.defaults()
                .ui(false).dbName(name).dbPort(port).dbPassword(READER_PASSWORD)));
            assertTrue(e.getMessage().startsWith("could not serve the lending database on 127.0.0.1:" + port + ": "),
                e.getMessage());
            assertTrue(LendingApp.instance() == before, "no desk was published");
        }
        LendingApp app = LendingApp.launch(LendingOptions.defaults().ui(false).dbName(name));
        try {
            assertNotSame(before, app);
            assertEquals(7, app.access().staff().size(), "the failed launch closed its database");
        } finally {
            app.shutdown();
        }
    }

    @Test
    void theListenerIsBoundToLoopbackOnly() throws Exception {
        List<InetAddress> others = new ArrayList<InetAddress>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }
            for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements();) {
                InetAddress address = a.nextElement();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                    others.add(address);
                }
            }
        }
        LendingApp app = launch("loopback", 0);
        try {
            int port = app.dbServer().port();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000);
            }
            assumeFalse(others.isEmpty(), "this host has no non-loopback IPv4 address to try");
            for (InetAddress address : others) {
                assertRefused(address, port);
            }
        } finally {
            app.shutdown();
        }
    }

    /**
     * In process too; creating the account again with the same password changes nothing, and with another password
     * the account takes the new one (the caller's password is always the account's).
     */
    @Test
    void theReadOnlyAccountCanBeCreatedInProcessToo() throws Exception {
        try (LendingDb db = LendingDb.create(name("inprocess"), Seed.B, LocalDate.of(2026, 10, 1))) {
            db.createReadOnlyUser(READER_PASSWORD);
            db.createReadOnlyUser(READER_PASSWORD);
            try (Connection reader = connect(db.url(), LendingDb.READER_USER, READER_PASSWORD);
                 Statement st = reader.createStatement()) {
                assertEquals(Collections.singletonList(Collections.singletonList("350.00")),
                    rows(reader, "SELECT value FROM desk_setting WHERE name = 'max_return'"));
                SQLException e = assertThrows(SQLException.class,
                    () -> st.executeUpdate("UPDATE desk_setting SET value = '1.00'"));
                assertEquals("42501", e.getSQLState());
            }
            db.createReadOnlyUser("Another-Reader-Pass");
            SQLException old = assertThrows(SQLException.class,
                () -> connect(db.url(), LendingDb.READER_USER, READER_PASSWORD).close(), "the old password");
            assertTrue(old.getSQLState().startsWith("28"), old.getSQLState());
            try (Connection reader = connect(db.url(), LendingDb.READER_USER, "Another-Reader-Pass")) {
                assertEquals(Collections.singletonList(Collections.singletonList("350.00")),
                    rows(reader, "SELECT value FROM desk_setting WHERE name = 'max_return'"));
            }
            try (Connection admin = connect(db.url(), "SA", "")) {
                assertTrue(admin.isValid(2), "in process, without a listener, the administrator is unchanged");
            }
        }
    }

    private static void assertRefused(InetAddress address, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(address, port), 2000);
            throw new AssertionError("connected to " + address + ":" + port);
        } catch (IOException expected) {
            // refused (or, on a filtering host, timed out): nothing listens there
        }
    }
}
