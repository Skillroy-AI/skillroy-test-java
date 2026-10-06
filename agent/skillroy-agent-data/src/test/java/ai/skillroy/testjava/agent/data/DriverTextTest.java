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
import ai.skillroy.testjava.agent.api.spi.QueryResult;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.data.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding S22 (it replaces amendment A26): the text of a driver's or provider's exception never reaches the agent log
 * or an error message, and reaches {@code data.message} only when the provider opted in and no {@code MaskPolicy} is
 * registered. Every failure site is run with every combination of opt-in and mask; the text carries a sentinel that
 * stands for a masked cell value a driver echoes (as conversion errors of several databases do).
 */
class DriverTextTest {

    private static final String SENTINEL = "masked-cell-7f3a9c";
    private static final String TEXT = "conversion failed for the value '" + SENTINEL + "'";

    private static TestDb db;

    @BeforeAll
    static void createDatabase() throws SQLException {
        db = TestDb.create();
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        db.close();
    }

    /** A failure site: registers a provider with id {@code src} that fails in its own way. */
    private interface Site {
        void register(FakeAgentContext ctx);
    }

    /** What a site's error must carry besides the rule on the text. */
    private static final class Expected {
        final String name;
        final Site site;
        final String sqlState;
        final Long errorCode;
        final String exception;
        final String message;

        Expected(String name, Site site, String sqlState, Long errorCode, String exception, String message) {
            this.name = name;
            this.site = site;
            this.sqlState = sqlState;
            this.errorCode = errorCode;
            this.exception = exception;
            this.message = message;
        }
    }

    private static List<Expected> sites() {
        List<Expected> sites = new ArrayList<Expected>();
        String sqlMessage = "query on data source 'src' failed: sqlState=22018, errorCode=4242 (java.sql.SQLException)";
        sites.add(new Expected("executeQuery", ctx -> {
            TestDb.JdbcProvider p = db.new JdbcProvider("src");
            p.spy.fail("PreparedStatement.executeQuery", JdbcSpy.sqlException(TEXT, "22018"));
            ctx.spi.register(DataSourceProvider.class, p);
        }, "22018", 4242L, SQLException.class.getName(), sqlMessage));
        sites.add(new Expected("ResultSet.getObject", ctx -> {
            TestDb.JdbcProvider p = db.new JdbcProvider("src");
            p.spy.fail("ResultSet.getObject", JdbcSpy.sqlException(TEXT, "22018"));
            ctx.spi.register(DataSourceProvider.class, p);
        }, "22018", 4242L, SQLException.class.getName(), sqlMessage));
        sites.add(new Expected("open() throws SQLException", ctx -> ctx.spi.register(DataSourceProvider.class,
                new Failing("src", null, JdbcSpy.sqlException(TEXT, "22018"))),
                "22018", 4242L, SQLException.class.getName(), sqlMessage));
        sites.add(new Expected("query() throws SQLException", ctx -> ctx.spi.register(DataSourceProvider.class,
                new Failing("src", JdbcSpy.sqlException(TEXT, "22018"), null)),
                "22018", 4242L, SQLException.class.getName(), sqlMessage));
        // a provider that wraps the driver's exception: its own message usually repeats the cause's text
        sites.add(new Expected("query() throws a wrapping RuntimeException", ctx -> ctx.spi.register(
                DataSourceProvider.class, new Failing("src", new IllegalStateException("statement API failed: "
                        + TEXT, JdbcSpy.sqlException(TEXT, "22018")), null)),
                "22018", 4242L, IllegalStateException.class.getName(), "query on data source 'src' failed:"
                        + " sqlState=22018, errorCode=4242 (java.lang.IllegalStateException)"));
        sites.add(new Expected("query() throws a RuntimeException without a cause", ctx -> ctx.spi.register(
                DataSourceProvider.class, new Failing("src", new IllegalArgumentException(TEXT), null)),
                null, null, IllegalArgumentException.class.getName(), "query on data source 'src' failed in"
                        + " query() (java.lang.IllegalArgumentException)"));
        sites.add(new Expected("open() throws a RuntimeException", ctx -> ctx.spi.register(DataSourceProvider.class,
                new Failing("src", null, new IllegalStateException(TEXT))),
                null, null, IllegalStateException.class.getName(), "query on data source 'src' failed in open()"
                        + " (java.lang.IllegalStateException)"));
        return sites;
    }

    @Test
    void theTextIsReturnedOnlyWithTheOptInAndWithoutAMaskAndIsNeverLogged() throws Exception {
        for (Expected expected : sites()) {
            for (boolean optIn : new boolean[] {false, true}) {
                for (boolean mask : new boolean[] {false, true}) {
                    String what = expected.name + ", optIn=" + optIn + ", mask=" + mask;
                    FakeAgentContext ctx = context(optIn, mask);
                    expected.site.register(ctx);
                    RpcException e = ctx.fails("data.query", args("source", "src", "sql",
                            "SELECT code FROM person WHERE code = ?", "params", Arrays.asList("100")));
                    assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code(), what);
                    assertEquals(expected.message, e.error().message(), what);
                    assertFalse(String.valueOf(e.getMessage()).contains(SENTINEL), what);
                    assertFalse(ctx.log.all().contains(SENTINEL), what + ": " + ctx.log.all());
                    assertFalse(ctx.log.all().contains("conversion failed"), what + ": " + ctx.log.all());
                    Map<String, Object> data = e.error().data();
                    assertEquals("src", data.get("source"), what);
                    assertEquals(expected.sqlState, data.get("sqlState"), what);
                    assertEquals(expected.errorCode, data.get("errorCode"), what);
                    assertEquals(expected.exception, data.get("exception"), what);
                    if (optIn && !mask) {
                        assertTrue(String.valueOf(data.get("message")).contains(TEXT), what + ": " + data);
                        assertFalse(data.containsKey("messageWithheld"), what);
                    } else {
                        assertEquals(Boolean.TRUE, data.get("messageWithheld"), what);
                        assertFalse(data.containsKey("message"), what);
                        assertFalse(Json.write(e.error().toJson()).contains(SENTINEL), what);
                    }
                }
            }
        }
    }

    @Test
    void aSyntaxErrorOfTheDatabaseIsReturnedToAProviderThatOptedIn() throws Exception {
        FakeAgentContext ctx = context(true, false);
        TestDb.JdbcProvider provider = db.new JdbcProvider("db");
        ctx.spi.register(DataSourceProvider.class, provider);
        RpcException e = ctx.fails("data.query", args("source", "db", "sql", "SELECT nosuch FROM person"));
        Map<String, Object> data = e.error().data();
        assertEquals("42501", data.get("sqlState"));
        assertTrue(String.valueOf(data.get("message")).contains("NOSUCH"), data.toString());
        assertEquals(SQLSyntaxErrorException.class.getName(), data.get("exception"));
        assertFalse(e.error().message().contains("NOSUCH"), "never in the message: " + e.error().message());
        assertFalse(ctx.log.all().contains("NOSUCH"), "never in the log: " + ctx.log.all());
        assertTrue(provider.spy.allClosed(), provider.spy.toString());
    }

    @Test
    void theOptInIsAskedPerCallAndAFailingAnswerWithholds() throws Exception {
        final boolean[] answer = {false};
        FakeAgentContext ctx = new FakeAgentContext();
        new DataModule(p -> {
            if ("broken".equals(p.id())) {
                throw new IllegalStateException("cannot tell: " + SENTINEL);
            }
            return answer[0];
        }).install(ctx);
        ctx.spi.register(DataSourceProvider.class, new Failing("src", JdbcSpy.sqlException(TEXT, "22018"), null));
        ctx.spi.register(DataSourceProvider.class, new Failing("broken", JdbcSpy.sqlException(TEXT, "22018"), null));
        assertEquals(Boolean.TRUE, ctx.fails("data.query", args("source", "src", "sql", "SELECT 1")).error().data()
                .get("messageWithheld"));
        answer[0] = true;
        assertEquals(TEXT, ctx.fails("data.query", args("source", "src", "sql", "SELECT 1")).error().data()
                .get("message"));
        AutoCloseable mask = ctx.spi.register(MaskPolicy.class, (source, column, value) -> value);
        assertEquals(Boolean.TRUE, ctx.fails("data.query", args("source", "src", "sql", "SELECT 1")).error().data()
                .get("messageWithheld"), "a mask registered later withholds again");
        mask.close();
        RpcException e = ctx.fails("data.query", args("source", "broken", "sql", "SELECT 1"));
        assertEquals(Boolean.TRUE, e.error().data().get("messageWithheld"));
        assertEquals("22018", e.error().data().get("sqlState"));
        assertTrue(ctx.log.all().contains("failed in exposesErrorText(): java.lang.IllegalStateException"),
                ctx.log.all());
        assertFalse(ctx.log.all().contains(SENTINEL), ctx.log.all());
    }

    @Test
    void cleanupFailuresAreLoggedWithoutTheDriverText() throws Exception {
        FakeAgentContext ctx = context(true, false);
        TestDb.JdbcProvider provider = db.new JdbcProvider("db");
        provider.spy.fail("ResultSet.close", JdbcSpy.sqlException(TEXT, "HY000"));
        ctx.spi.register(DataSourceProvider.class, provider);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) ctx.call("data.query",
                args("source", "db", "sql", "SELECT code FROM person WHERE code = '100'"));
        assertEquals(1L, value.get("rowCount"));
        assertTrue(ctx.log.all().contains("could not close the result set: java.sql.SQLException (sqlState=HY000,"
                + " errorCode=4242)"), ctx.log.all());
        assertFalse(ctx.log.all().contains(SENTINEL), ctx.log.all());
    }

    @Test
    void failuresDescribeTheirSqlCauseWithoutText() {
        SQLException sql = JdbcSpy.sqlException(TEXT, "22018");
        assertSame(sql, Failures.sqlCause(sql));
        RuntimeException wrapped = new RuntimeException(new IllegalStateException(sql));
        assertSame(sql, Failures.sqlCause(wrapped));
        assertEquals("java.lang.RuntimeException (sqlState=22018, errorCode=4242)", Failures.describe(wrapped));
        assertEquals("java.lang.IllegalStateException", Failures.describe(new IllegalStateException(TEXT)));
        assertNull(Failures.sqlCause(new IllegalStateException(TEXT)));
        // a cyclic chain ends, and so does one deeper than the limit
        CyclicException a = new CyclicException();
        CyclicException b = new CyclicException();
        a.next = b;
        b.next = a;
        assertNull(Failures.sqlCause(a));
        Throwable deep = sql;
        for (int i = 0; i < Failures.MAX_CAUSE_DEPTH; i++) {
            deep = new RuntimeException(deep);
        }
        assertNull(Failures.sqlCause(deep), "deeper than " + Failures.MAX_CAUSE_DEPTH);
    }

    /**
     * The production path asks the provider itself through {@code DataSourceProvider.exposesErrorText()} (contract
     * section 4): a provider that answers true gets its text back, the default answer withholds it.
     */
    @Test
    void theProvidersOwnOptInIsHonoured() throws Exception {
        assertFalse(new DataModuleTest.NamedProvider("plain", "default answer").exposesErrorText());
        DataSourceProvider optedIn = proxyProvider("proxied", true);
        DataSourceProvider silent = proxyProvider("silent", false);
        assertTrue(DataQuery.exposesErrorText(optedIn));
        assertFalse(DataQuery.exposesErrorText(silent));
        assertFalse(DataQuery.exposesErrorText(new DataModuleTest.NamedProvider("plain", "default answer")));

        FakeAgentContext ctx = new FakeAgentContext();
        new DataModule().install(ctx);
        ctx.spi.register(DataSourceProvider.class, optedIn);
        ctx.spi.register(DataSourceProvider.class, silent);
        Map<String, Object> data = ctx.fails("data.query", args("source", "proxied", "sql", "SELECT 1")).error()
                .data();
        assertEquals(TEXT, data.get("message"), data.toString());
        assertNull(data.get("messageWithheld"), data.toString());
        assertEquals(Boolean.TRUE, ctx.fails("data.query", args("source", "silent", "sql", "SELECT 1")).error()
                .data().get("messageWithheld"));
    }

    private static FakeAgentContext context(final boolean optIn, boolean mask) throws Exception {
        FakeAgentContext ctx = new FakeAgentContext();
        new DataModule(p -> optIn).install(ctx);
        if (mask) {
            ctx.spi.register(MaskPolicy.class, (source, column, value) -> value);
        }
        return ctx;
    }

    /** A provider whose query() form answers exposesErrorText() and fails with the sentinel text. */
    private static DataSourceProvider proxyProvider(final String id, final boolean exposes) {
        InvocationHandler handler = (Object proxy, Method method, Object[] a) -> {
            switch (method.getName()) {
                case "id":
                    return id;
                case "describe":
                    return "proxy";
                case "exposesErrorText":
                    return Boolean.valueOf(exposes);
                case "query":
                    throw JdbcSpy.sqlException(TEXT, "22018");
                case "open":
                    return null;
                case "hashCode":
                    return Integer.valueOf(System.identityHashCode(proxy));
                case "equals":
                    return Boolean.valueOf(proxy == a[0]);
                default:
                    return "ProxyProvider(" + id + ")";
            }
        };
        return (DataSourceProvider) Proxy.newProxyInstance(DriverTextTest.class.getClassLoader(),
                new Class<?>[] {DataSourceProvider.class}, handler);
    }

    /** Fails in query() or open() with the given exception. */
    private static final class Failing implements DataSourceProvider {
        private final String id;
        private final Exception inQuery;
        private final RuntimeException inOpenUnchecked;
        private final SQLException inOpen;

        Failing(String id, Exception inQuery, Exception inOpen) {
            this.id = id;
            this.inQuery = inQuery;
            this.inOpen = inOpen instanceof SQLException ? (SQLException) inOpen : null;
            this.inOpenUnchecked = inOpen instanceof RuntimeException ? (RuntimeException) inOpen : null;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String describe() {
            return "fails";
        }

        @Override
        public QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs) throws Exception {
            if (inQuery != null) {
                throw inQuery;
            }
            return null;
        }

        @Override
        public Connection open() throws SQLException {
            if (inOpen != null) {
                throw inOpen;
            }
            if (inOpenUnchecked != null) {
                throw inOpenUnchecked;
            }
            return null;
        }
    }

    /** An exception whose cause chain is a cycle. */
    private static final class CyclicException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Throwable next;

        @Override
        public synchronized Throwable getCause() {
            return next;
        }
    }
}
