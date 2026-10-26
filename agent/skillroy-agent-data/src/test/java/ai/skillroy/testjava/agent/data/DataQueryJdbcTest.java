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

import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.RpcResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.skillroy.testjava.agent.data.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data.query in its JDBC form against an in-memory HSQLDB database, through the registered primitive. */
class DataQueryJdbcTest {

    private static TestDb db;

    private FakeAgentContext ctx;
    private TestDb.JdbcProvider provider;

    @BeforeAll
    static void createDatabase() throws SQLException {
        db = TestDb.create();
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        db.close();
    }

    @BeforeEach
    void install() throws Exception {
        ctx = new FakeAgentContext();
        new DataModule().install(ctx);
        provider = db.new JdbcProvider("db");
        ctx.spi.register(DataSourceProvider.class, provider);
    }

    private Map<String, Object> query(String sql, Object... more) throws Exception {
        Map<String, Object> args = args("source", "db", "sql", sql);
        args.putAll(args(more));
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) ctx.call("data.query", args);
        return value;
    }

    private RpcException queryFails(String sql, Object... more) throws Exception {
        Map<String, Object> args = args("source", "db", "sql", sql);
        args.putAll(args(more));
        return ctx.fails("data.query", args);
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> rows(Map<String, Object> result) {
        return (List<List<Object>>) result.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> row0(Map<String, Object> result) {
        return ((List<List<Object>>) result.get("rows")).get(0);
    }

    @Test
    void returnsColumnsRowsCountAndTruncatedFlag() throws Exception {
        Map<String, Object> result = query("SELECT code, name AS \"displayName\", credit FROM person ORDER BY code");
        assertEquals("{\"columns\":[{\"name\":\"CODE\",\"type\":\"VARCHAR\"},"
                + "{\"name\":\"displayName\",\"type\":\"VARCHAR\"},{\"name\":\"CREDIT\",\"type\":\"DECIMAL\"}],"
                + "\"rows\":[[\"100\",\"Ada\",\"250.00\"],[\"200\",\"Grace\",\"12.50\"],[\"300\",\"Linus\",null]],"
                + "\"rowCount\":3,\"truncated\":false}", Json.write(result));
        assertEquals(1, provider.opens.get());
        assertTrue(provider.spy.allClosed(), provider.spy.toString());
    }

    @Test
    void acceptsWithQueriesCommentsAndATrailingSemicolon() throws Exception {
        Map<String, Object> result = query("-- the lowest deposits\n/* on loan */ WITH low AS (SELECT id, deposit"
                + " FROM item WHERE id <= 2) SELECT id FROM low ORDER BY id;  ");
        assertEquals(Arrays.asList(Arrays.asList(1L), Arrays.asList(2L)), rows(result));
        assertEquals(1, provider.spy.count("Connection.prepareStatement[-- the lowest deposits\n/* on loan */ WITH"
                + " low AS (SELECT id, deposit FROM item WHERE id <= 2) SELECT id FROM low ORDER BY id, 1003, 1007]"),
                provider.spy.toString());
    }

    @Test
    void bindsJsonParameterValues() throws Exception {
        // Parsed from JSON, exactly as the dispatcher would hand them over: Long, Double, String, Boolean, null.
        Map<String, Object> args = Json.parseObject("{\"source\":\"db\",\"sql\":\"SELECT code FROM person"
                + " WHERE code = ? AND active = ? AND credit > ? AND credit < ?"
                + " AND (name = ? OR CAST(? AS VARCHAR(10)) IS NULL)\","
                + "\"params\":[\"100\", true, 200, 250.5, \"Ada\", null]}");
        Object value = ctx.call("data.query", args);
        assertEquals("{\"columns\":[{\"name\":\"CODE\",\"type\":\"VARCHAR\"}],\"rows\":[[\"100\"]],\"rowCount\":1,"
                + "\"truncated\":false}", Json.write(value));
        assertTrue(provider.spy.calls.contains("PreparedStatement.setObject[1, 100]"), provider.spy.toString());
        assertTrue(provider.spy.calls.contains("PreparedStatement.setObject[2, true]"));
        assertTrue(provider.spy.calls.contains("PreparedStatement.setObject[3, 200]"));
        assertTrue(provider.spy.calls.contains("PreparedStatement.setObject[4, 250.5]"));
        assertTrue(provider.spy.calls.contains("PreparedStatement.setObject[6, null]"));
    }

    @Test
    void bindsANullParameter() throws Exception {
        Map<String, Object> result = query("SELECT code FROM person WHERE name = ?", "params",
                Collections.singletonList(null));
        assertEquals(0L, result.get("rowCount"));
    }

    @Test
    void rejectsParametersThatAreNotScalars() throws Exception {
        RpcException e = queryFails("SELECT code FROM person WHERE code = ?", "params",
                Arrays.asList("100", Collections.singletonMap("a", 1)));
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertEquals("params[1]", e.error().data().get("param"));
        e = queryFails("SELECT 1 FROM person", "params", Arrays.asList(Arrays.asList(1, 2)));
        assertEquals("params[0]", e.error().data().get("param"));
        e = queryFails("SELECT 1 FROM person", "params", "not-an-array");
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertEquals("params", e.error().data().get("param"));
        assertEquals(0, provider.opens.get());
    }

    @Test
    void defaultMaxRowsIs200AndTheExtraRowMarksTruncation() throws Exception {
        Map<String, Object> result = query("SELECT id FROM item ORDER BY id");
        assertEquals(200L, result.get("rowCount"));
        assertEquals(200, rows(result).size());
        assertEquals(Arrays.asList(200L), rows(result).get(199));
        assertEquals(Boolean.TRUE, result.get("truncated"));
        assertTrue(provider.spy.calls.contains("PreparedStatement.setMaxRows[201]"), provider.spy.toString());
    }

    @Test
    void maxRowsAboveTheLimitIsCappedAt200() throws Exception {
        Map<String, Object> result = query("SELECT id FROM item", "maxRows", 100000L);
        assertEquals(200L, result.get("rowCount"));
        assertEquals(Boolean.TRUE, result.get("truncated"));
    }

    @Test
    void exactlyMaxRowsIsNotTruncated() throws Exception {
        Map<String, Object> result = query("SELECT id FROM item WHERE id <= 5", "maxRows", 5L);
        assertEquals(5L, result.get("rowCount"));
        assertEquals(Boolean.FALSE, result.get("truncated"));
        assertTrue(provider.spy.calls.contains("PreparedStatement.setMaxRows[6]"));
        result = query("SELECT id FROM item WHERE id <= 6", "maxRows", "5");
        assertEquals(5L, result.get("rowCount"));
        assertEquals(Boolean.TRUE, result.get("truncated"));
    }

    @Test
    void maxRowsZeroReturnsColumnsOnly() throws Exception {
        Map<String, Object> result = query("SELECT id, code FROM item", "maxRows", 0L);
        assertEquals(0L, result.get("rowCount"));
        assertEquals(Boolean.TRUE, result.get("truncated"));
        assertEquals("[{\"name\":\"ID\",\"type\":\"INTEGER\"},{\"name\":\"CODE\",\"type\":\"VARCHAR\"}]",
                Json.write(result.get("columns")));
        result = query("SELECT id FROM item WHERE id < 0", "maxRows", 0L);
        assertEquals(Boolean.FALSE, result.get("truncated"));
    }

    @Test
    void invalidMaxRowsIsRejected() throws Exception {
        assertEquals(ErrorCodes.INVALID_PARAMS, queryFails("SELECT 1 FROM item", "maxRows", -1L).error().code());
        assertEquals("maxRows", queryFails("SELECT 1 FROM item", "maxRows", -1L).error().data().get("param"));
        assertEquals(ErrorCodes.INVALID_PARAMS, queryFails("SELECT 1 FROM item", "maxRows", 2.5).error().code());
        assertEquals(ErrorCodes.INVALID_PARAMS, queryFails("SELECT 1 FROM item", "maxRows", true).error().code());
    }

    @Test
    void convertsEveryColumnType() throws Exception {
        Map<String, Object> result = query("SELECT * FROM kinds");
        List<Object> row = row0(result);
        List<Object> expected = new ArrayList<Object>();
        expected.add(1L);                                            // INTEGER
        expected.add(9007199254740993L);                             // BIGINT, exact beyond double precision
        expected.add(3L);                                            // SMALLINT
        expected.add(4L);                                            // TINYINT
        expected.add("12.50");                                       // DECIMAL
        expected.add("12345678901234567890.12300");                  // NUMERIC
        expected.add(1.5);                                           // DOUBLE
        expected.add(2.5);                                           // REAL
        expected.add(Boolean.TRUE);                                  // BOOLEAN
        expected.add(TestDb.repeat('v', 10000) + "...[truncated: 15000 chars]");   // long VARCHAR
        expected.add("ab   ");                                       // CHAR(5), padded
        expected.add("2026-10-01");                                  // DATE
        expected.add("10:15:30");                                    // TIME
        expected.add("2026-10-01T10:15:30.123");                     // TIMESTAMP
        expected.add("2026-10-01T10:15:30+02:00");                   // TIMESTAMP WITH TIME ZONE
        expected.add("10:15:00+02:00");                              // TIME WITH TIME ZONE
        expected.add("base64:AQL/");                                 // VARBINARY
        expected.add(null);                                          // BLOB, checked below
        expected.add(TestDb.repeat('c', 10000) + "...[truncated: 12000 chars]");   // CLOB
        expected.add("short clob");                                  // small CLOB
        expected.add("ARRAY[1,2,3]");                                // ARRAY, toString
        expected.add("6f1e8f7a-0000-4000-8000-000000000001");        // UUID
        expected.add(null);                                          // NULL
        String blob = (String) row.get(17);
        assertTrue(blob.startsWith("base64:AAECAwQFBgcI"), blob);
        assertTrue(blob.endsWith("...[truncated: 2000 bytes]"), blob);
        List<Object> actual = new ArrayList<Object>(row);
        actual.set(17, null);
        assertEquals(expected, actual);
        assertEquals("[\"INTEGER\",\"BIGINT\",\"SMALLINT\",\"TINYINT\",\"DECIMAL\",\"NUMERIC\",\"DOUBLE\",\"DOUBLE\","
                + "\"BOOLEAN\",\"VARCHAR\",\"CHARACTER\",\"DATE\",\"TIME\",\"TIMESTAMP\",\"TIMESTAMP WITH TIME ZONE\","
                + "\"TIME WITH TIME ZONE\",\"VARBINARY\",\"BLOB\",\"CLOB\",\"CLOB\",\"INTEGER ARRAY\",\"UUID\","
                + "\"VARCHAR\"]", Json.write(columnTypes(result)));
        assertTrue(provider.spy.allClosed(), provider.spy.toString());
    }

    @Test
    void resultSurvivesTheWireUnchanged() throws Exception {
        Map<String, Object> result = query("SELECT * FROM kinds");
        RpcResult rpc = new RpcResult(result, Collections.emptyList(), 1, 1, 3, "skillroy-worker-1");
        Map<String, Object> json = rpc.toJson();
        assertEquals(json, Json.parseObject(Json.write(json)));
        assertEquals(Json.write(result), Json.write(json.get("value")));
    }

    @Test
    void appliesEveryMaskPolicyInRegistrationOrderToConvertedValues() throws Exception {
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> {
            seen.add(sourceId + "/" + column + "=" + (value == null ? "null" : value.getClass().getSimpleName()));
            return "NAME".equals(column) && value != null ? "***" : value;
        });
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) ->
                value instanceof String ? value + "#" : value);
        Map<String, Object> result = query("SELECT code, name, credit, born FROM person WHERE code = '100'");
        assertEquals(Arrays.asList("100#", "***#", "250.00#", "1990-05-17#"), row0(result));
        assertEquals(Arrays.asList("db/CODE=String", "db/NAME=String", "db/CREDIT=String", "db/BORN=String"), seen);
    }

    @Test
    void maskOutputIsConvertedToo() throws Exception {
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> {
            if ("CREDIT".equals(column)) {
                return new BigDecimal("1.50");
            }
            if ("BORN".equals(column)) {
                return java.sql.Date.valueOf("2000-01-02");
            }
            if ("ACTIVE".equals(column)) {
                return Double.NaN;
            }
            return value;
        });
        Map<String, Object> result = query("SELECT credit, born, active FROM person WHERE code = '200'");
        assertEquals(Arrays.asList("1.50", "2000-01-02", "NaN"), row0(result));
    }

    @Test
    void aFailingMaskPolicyFailsTheCallWithoutLeakingTheValue() throws Exception {
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> {
            if ("NAME".equals(column)) {
                throw new IllegalStateException("cannot mask " + value);
            }
            return value;
        });
        RpcException e = queryFails("SELECT code, name FROM person WHERE code = '200'");
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertEquals("NAME", e.error().data().get("column"));
        assertEquals(IllegalStateException.class.getName(), e.error().data().get("exception"));
        assertFalse(Json.write(e.error().toJson()).contains("Grace"), Json.write(e.error().toJson()));
        assertFalse(ctx.log.all().contains("Grace"), ctx.log.all());
        assertTrue(provider.spy.allClosed());
    }

    @Test
    void unknownSourceIsNotFoundListingTheKnownIds() throws Exception {
        ctx.spi.register(DataSourceProvider.class, db.new JdbcProvider("other"));
        RpcException e = ctx.fails("data.query", args("source", "nope", "sql", "SELECT 1 FROM person"));
        assertEquals(ErrorCodes.NOT_FOUND, e.error().code());
        assertEquals(Arrays.asList("db", "other"), e.error().data().get("known"));
        assertEquals("nope", e.error().data().get("source"));
        assertEquals("unknown data source 'nope'; known sources: [db, other]", e.error().message());
    }

    @Test
    void firstProviderWithAnIdWins() throws Exception {
        TestDb.JdbcProvider shadowed = db.new JdbcProvider("db");
        ctx.spi.register(DataSourceProvider.class, shadowed);
        query("SELECT 1 FROM person");
        assertEquals(1, provider.opens.get());
        assertEquals(0, shadowed.opens.get());
    }

    @Test
    void missingSourceOrSqlIsInvalidParams() throws Exception {
        RpcException e = ctx.fails("data.query", args("sql", "SELECT 1 FROM person"));
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertEquals("source", e.error().data().get("param"));
        e = ctx.fails("data.query", args("source", "db"));
        assertEquals("sql", e.error().data().get("param"));
    }

    @Test
    void rejectedStatementNeverReachesTheProvider() throws Exception {
        for (String sql : Arrays.asList("DELETE FROM person", "SELECT 1 FROM person; DELETE FROM person",
                "WITH x AS (SELECT 1) UPDATE person SET name = 'x'", "CALL p()", "  ")) {
            RpcException e = queryFails(sql);
            assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code(), sql);
            assertEquals("sql", e.error().data().get("param"), sql);
        }
        assertEquals(0, provider.opens.get());
        assertEquals(3L, db.scalar("SELECT COUNT(*) FROM person"));
    }

    @Test
    void sqlErrorsBecomeInternalErrorsWithSqlStateAndNoParameterValues() throws Exception {
        RpcException e = queryFails("SELECT nosuch FROM person WHERE code = ?", "params",
                Arrays.asList("secret-parameter-1"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        Map<String, Object> data = e.error().data();
        assertEquals("42501", data.get("sqlState"));
        assertEquals(-5501L, data.get("errorCode"));
        assertEquals("db", data.get("source"));
        assertTrue(String.valueOf(data.get("message")).contains("NOSUCH"), data.toString());
        assertFalse(data.containsKey("messageWithheld"), data.toString());
        assertEquals("query on data source 'db' failed: sqlState=42501, errorCode=-5501 (" + data.get("exception")
                + ")", e.error().message());
        assertFalse(e.error().message().contains("NOSUCH"), e.error().message());
        assertFalse(ctx.log.all().contains("NOSUCH"), ctx.log.all());
        assertTrue(provider.spy.allClosed(), provider.spy.toString());

        provider.spy = new JdbcSpy();
        e = queryFails("SELECT code FROM person WHERE credit > ?", "params", Arrays.asList("secret-value-2"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertEquals("22018", e.error().data().get("sqlState"));
        assertFalse(Json.write(e.error().toJson()).contains("secret-value-2"), Json.write(e.error().toJson()));
        assertFalse(ctx.log.all().contains("secret-value-2"), ctx.log.all());
        assertTrue(provider.spy.allClosed(), provider.spy.toString());

        provider.spy = new JdbcSpy();
        e = queryFails("SELECT code FROM person WHERE code = ? AND name = ?", "params",
                Arrays.asList("secret-value-3"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertFalse(Json.write(e.error().toJson()).contains("secret-value-3"));
        assertTrue(provider.spy.allClosed(), provider.spy.toString());
    }

    @Test
    void connectionIsReadOnlyDuringTheQueryAndRestoredBeforeClose() throws Exception {
        query("SELECT code FROM person");
        JdbcSpy spy = provider.spy;
        int readOnly = spy.indexOf("Connection.setReadOnly[true]");
        int prepare = spy.indexOf("Connection.prepareStatement");
        int restore = spy.indexOf("Connection.setReadOnly[false]");
        int close = spy.indexOf("Connection.close");
        assertTrue(readOnly >= 0 && readOnly < prepare && prepare < restore && restore < close, spy.toString());
        assertTrue(spy.indexOf("PreparedStatement.setQueryTimeout") > prepare, spy.toString());
    }

    @Test
    void queryTimeoutComesFromTheRemainingTime() throws Exception {
        ctx.remainingMs = 9500;
        query("SELECT 1 FROM person");
        assertTrue(provider.spy.calls.contains("PreparedStatement.setQueryTimeout[9]"), provider.spy.toString());

        provider.spy = new JdbcSpy();
        ctx.remainingMs = 300;
        query("SELECT 1 FROM person");
        assertTrue(provider.spy.calls.contains("PreparedStatement.setQueryTimeout[1]"), provider.spy.toString());

        provider.spy = new JdbcSpy();
        ctx.remainingMs = 0;
        query("SELECT 1 FROM person");
        assertTrue(provider.spy.calls.contains("PreparedStatement.setQueryTimeout[1]"), provider.spy.toString());
    }

    @Test
    void openFailuresAreMapped() throws Exception {
        ctx.spi.register(DataSourceProvider.class, new DataModuleTest.NamedProvider("down", "unreachable") {
            @Override
            public Connection open() throws SQLException {
                throw new SQLException("connection refused", "08001", 17);
            }
        });
        RpcException e = ctx.fails("data.query", args("source", "down", "sql", "SELECT 1"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertEquals("08001", e.error().data().get("sqlState"));
        assertEquals(17L, e.error().data().get("errorCode"));
        assertEquals(SQLException.class.getName(), e.error().data().get("exception"));
        assertEquals("connection refused", e.error().data().get("message"));
        assertEquals("query on data source 'down' failed: sqlState=08001, errorCode=17 (java.sql.SQLException)",
                e.error().message());

        ctx.spi.register(DataSourceProvider.class, new DataModuleTest.NamedProvider("bug", "throws") {
            @Override
            public Connection open() {
                throw new IllegalStateException("pool closed");
            }
        });
        e = ctx.fails("data.query", args("source", "bug", "sql", "SELECT 1"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertEquals(IllegalStateException.class.getName(), e.error().data().get("exception"));
        assertEquals("pool closed", e.error().data().get("message"));
        assertEquals("query on data source 'bug' failed in open() (java.lang.IllegalStateException)",
                e.error().message());
        assertFalse(ctx.log.all().contains("pool closed"), ctx.log.all());
    }

    @Test
    void aProviderWithNeitherFormIsCapabilityUnavailable() throws Exception {
        ctx.spi.register(DataSourceProvider.class, new DataModuleTest.NamedProvider("empty", "offers nothing"));
        RpcException e = ctx.fails("data.query", args("source", "empty", "sql", "SELECT 1"));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, e.error().code());
        assertEquals("data source 'empty' offers neither query() nor open()", e.error().data().get("reason"));
    }

    @Test
    void manyQueriesLeaveNoConnectionOpen() throws Exception {
        for (int i = 0; i < 50; i++) {
            provider.spy = new JdbcSpy();
            if (i % 2 == 0) {
                query("SELECT id FROM item WHERE id = ?", "params", Arrays.asList((long) i + 1));
            } else {
                queryFails("SELECT broken FROM item");
            }
            assertTrue(provider.spy.allClosed(), provider.spy.toString());
        }
        assertEquals(50, provider.opens.get());
    }

    @Test
    void scalarFunctionsNamedLikeForbiddenWordsRun() throws Exception {
        Map<String, Object> result = query("SELECT TRUNCATE(deposit, 1), INSERT(code, 1, 4, 'X') FROM item"
                + " WHERE id = 7");
        assertEquals(Collections.singletonList(Arrays.asList("7.9", "X-7")), rows(result));
    }

    @Test
    void aMaskThatReturnsACappedValueUnchangedDoesNotCapItAgain() throws Exception {
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> value);
        Map<String, Object> result = query("SELECT v FROM kinds");
        assertEquals(TestDb.repeat('v', 10000) + "...[truncated: 15000 chars]", row0(result).get(0));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theQueryTimeoutStopsARunawayStatement() throws Exception {
        ctx.remainingMs = 1000;
        // A four-way cross join over 250 rows has about four billion combinations: it ends only when stopped. Phase 8
        // (phase8-contracts.md 4.3, D530): the module gives up 250 ms before the call's time, cancels the open
        // statement and answers its own TIMEOUT inside that reserve.
        long start = System.nanoTime();
        RpcException e = queryFails("SELECT COUNT(*) FROM item a, item b, item c, item d"
                + " WHERE a.deposit + b.deposit + c.deposit + d.deposit < 0");
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(ErrorCodes.TIMEOUT, e.error().code(), e.error().toString());
        Map<String, Object> data = e.error().data();
        assertEquals("data.query", data.get("primitive"));
        assertEquals(1000L, data.get("timeoutMs"));
        assertEquals("db", data.get("source"));
        assertEquals(null, data.get("lane"));
        assertEquals(Boolean.TRUE, data.get("cancelled"), data.toString());
        assertEquals(Boolean.FALSE, data.get("stillRunning"), data.toString());
        assertEquals(Boolean.FALSE, data.get("enforcesTimeout"));
        assertFalse(data.containsKey("cancelPending"), data.toString());
        assertTrue(tookMs < 1000, "answered inside the call's time: " + tookMs);
        assertTrue(provider.spy.calls.contains("PreparedStatement.setQueryTimeout[1]"), provider.spy.toString());
        assertEquals(1, provider.spy.count("PreparedStatement.cancel"), provider.spy.toString());
        assertTrue(provider.spy.indexOf("PreparedStatement.cancel") < provider.spy.indexOf("PreparedStatement.close"),
                "cancelled while open: " + provider.spy);
        assertTrue(provider.spy.allClosed(), provider.spy.toString());
        // The database and the source are still usable afterwards: no BUSY.
        assertEquals(1L, query("SELECT id FROM item WHERE id = 1").get("rowCount"));
    }

    @Test
    void aStatementIsCancelledOnlyWhileItIsOpen() throws Exception {
        // phase8-contracts.md 4.3, D530: a cancel that comes after the read never touches a statement.
        Control control = new Control(System.nanoTime() + TimeUnit.SECONDS.toNanos(10), ctx.log);
        JdbcSpy spy = new JdbcSpy();
        JdbcExecutor.execute(spy.wrap(db.open()), "SELECT code FROM person", Collections.<Object>emptyList(), 10, 5,
                ctx.log, control);
        assertTrue(control.cancel());
        control.startActions(ctx.broker);
        control.awaitActions(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        assertEquals(0, spy.count("PreparedStatement.cancel"), spy.toString());
        assertFalse(control.cancelledByAction());
        assertFalse(control.actionsPending());
    }

    @Test
    void concurrentQueriesAreIndependentAndCloseEveryConnection() throws Exception {
        final Queue<JdbcSpy> spies = new ConcurrentLinkedQueue<JdbcSpy>();
        ctx.spi.register(DataSourceProvider.class, new DataModuleTest.NamedProvider("pool", "a spy per connection") {
            @Override
            public Connection open() throws SQLException {
                JdbcSpy spy = new JdbcSpy();
                spies.add(spy);
                return spy.wrap(db.open());
            }
        });
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> "CODE".equals(column) ? "#" + value : value);
        final int threads = 8;
        final int perThread = 25;
        final AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "skillroy-test-query-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            final CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> outcomes = new ArrayList<Future<Void>>();
            for (int t = 0; t < threads; t++) {
                final int first = t * perThread + 1;
                outcomes.add(workers.submit(() -> {
                    start.await();
                    for (long id = first; id < first + perThread; id++) {
                        Object value = ctx.call("data.query", args("source", "pool",
                                "sql", "SELECT id, code FROM item WHERE id = ?", "params", Arrays.asList(id)));
                        assertEquals("[[" + id + ",\"#ITEM-" + id + "\"]]",
                                Json.write(((Map<?, ?>) value).get("rows")));
                        RpcException e = ctx.fails("data.query", args("source", "pool",
                                "sql", "SELECT nosuch FROM item WHERE id = ?", "params", Arrays.asList(id)));
                        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> outcome : outcomes) {
                outcome.get(5, TimeUnit.MINUTES);
            }
        } finally {
            workers.shutdownNow();
        }
        assertEquals(threads * perThread * 2, spies.size());
        for (JdbcSpy spy : spies) {
            assertTrue(spy.allClosed(), spy.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> columnTypes(Map<String, Object> result) {
        List<Object> types = new ArrayList<Object>();
        for (Object column : (List<Object>) result.get("columns")) {
            types.add(((Map<String, Object>) column).get("type"));
        }
        return types;
    }
}
