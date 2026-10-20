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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.skillroy.testjava.agent.data.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data.query with providers that execute the statement themselves (DataSourceProvider.query). */
class ProviderQueryFormTest {

    private static TestDb db;

    private FakeAgentContext ctx;

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
    }

    private Object query(String source, String sql, Object... more) throws Exception {
        Map<String, Object> args = args("source", source, "sql", sql);
        args.putAll(args(more));
        return ctx.call("data.query", args);
    }

    @Test
    void queryFormIsTriedFirstWithTheGuardedStatementAndOneExtraRow() throws Exception {
        TestDb.StatementProvider provider = db.new StatementProvider("api", true);
        ctx.spi.register(DataSourceProvider.class, provider);
        ctx.remainingMs = 7300;
        Object value = query("api", "SELECT code, credit FROM person WHERE active = ? ORDER BY code ;",
                "params", Arrays.asList(true), "maxRows", 1L);
        assertEquals("{\"columns\":[{\"name\":\"CODE\",\"type\":null},{\"name\":\"CREDIT\",\"type\":null}],"
                + "\"rows\":[[\"100\",\"250.00\"]],\"rowCount\":1,\"truncated\":true}", Json.write(value));
        assertEquals("SELECT code, credit FROM person WHERE active = ? ORDER BY code ", provider.lastSql);
        assertEquals(Arrays.asList((Object) Boolean.TRUE), provider.lastParams);
        assertEquals(2, provider.lastMaxRows);
        // Phase 8 (phase8-contracts.md 4.2): the read's budget is the call's remaining time less the 250 ms reserve.
        assertEquals(7300L - DataQuery.RESERVE_MS, provider.lastTimeoutMs);
        assertEquals(0, provider.opens.get());
    }

    @Test
    void withoutColumnNamesOrLabelsTheColumnsAreC1ToCn() throws Exception {
        ctx.spi.register(DataSourceProvider.class, db.new StatementProvider("anon", false));
        // Expressions without an alias give the statement no label (phase8-contracts.md 4.4).
        Object value = query("anon", "SELECT LOWER(code), UPPER(name), YEAR(born) FROM person WHERE code = ?",
                "params", Arrays.asList("100"));
        assertEquals("{\"columns\":[{\"name\":\"c1\",\"type\":null},{\"name\":\"c2\",\"type\":null},"
                + "{\"name\":\"c3\",\"type\":null}],\"rows\":[[\"100\",\"ADA\",1990]],\"rowCount\":1,"
                + "\"truncated\":false}", Json.write(value));
    }

    @Test
    void columnsAProviderLeavesUnnamedTakeTheLabelsOfTheSelectList() throws Exception {
        ctx.spi.register(DataSourceProvider.class, db.new StatementProvider("anon", false));
        Object value = query("anon", "SELECT code, name AS who, p.born, credit + 0 FROM person p WHERE code = ?",
                "params", Arrays.asList("100"));
        assertEquals("{\"columns\":[{\"name\":\"code\",\"type\":null},{\"name\":\"who\",\"type\":null},"
                + "{\"name\":\"born\",\"type\":null},{\"name\":\"c4\",\"type\":null}],"
                + "\"rows\":[[\"100\",\"Ada\",\"1990-05-17\",\"250.00\"]],\"rowCount\":1,\"truncated\":false}",
                Json.write(value));
    }

    @Test
    void theProvidersOwnNamesAlwaysWin() throws Exception {
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("half", new QueryResult(
                Arrays.asList("ID", null), null, Collections.singletonList(Arrays.<Object>asList(1, "a")), false)));
        assertEquals("[{\"name\":\"ID\",\"type\":null},{\"name\":\"b\",\"type\":null}]",
                Json.write(((Map<?, ?>) query("half", "SELECT a, b FROM t")).get("columns")));
    }

    @Test
    void aSelectListOfAnotherWidthIsIgnoredWithOneLogLine() throws Exception {
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("wide", new QueryResult(null, null,
                Collections.singletonList(Arrays.<Object>asList(1, 2, 3)), false)));
        assertEquals("[{\"name\":\"c1\",\"type\":null},{\"name\":\"c2\",\"type\":null},"
                + "{\"name\":\"c3\",\"type\":null}]",
                Json.write(((Map<?, ?>) query("wide", "SELECT secret_a, secret_b FROM t")).get("columns")));
        assertTrue(ctx.log.all().contains("data: source 'wide': the statement's select list names 2 column(s), the"
                + " result has 3; not used for naming"), ctx.log.all());
        assertFalse(ctx.log.all().contains("secret_a"), "never the statement");
    }

    @Test
    void zeroRowsTakeTheirWidthFromTheSelectList() throws Exception {
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("empty",
                new QueryResult(null, null, Collections.<List<Object>>emptyList(), false)));
        // phase8-contracts.md 4.8 defect 1: a positional zero-row result had no columns.
        assertEquals("{\"columns\":[{\"name\":\"code\",\"type\":null},{\"name\":\"name\",\"type\":null}],"
                + "\"rows\":[],\"rowCount\":0,\"truncated\":false}",
                Json.write(query("empty", "SELECT code, name FROM person WHERE 1 = 0")));
    }

    @Test
    void zeroRowsWithTypesOnlyTakeTheirWidthFromTheTypes() throws Exception {
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("typedEmpty", new QueryResult(null,
                Arrays.asList("VARCHAR", "DATE"), Collections.<List<Object>>emptyList(), false)));
        // phase8-contracts.md 4.8 defect 2: the types count for the width.
        assertEquals("{\"columns\":[{\"name\":\"c1\",\"type\":\"VARCHAR\"},{\"name\":\"c2\",\"type\":\"DATE\"}],"
                + "\"rows\":[],\"rowCount\":0,\"truncated\":false}",
                Json.write(query("typedEmpty", "SELECT 1 UNION SELECT 2")));
    }

    @Test
    void aStatementTheReaderGivesUpOnGivesNoColumnsWithoutNamesOrRows() throws Exception {
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("fixed",
                new QueryResult(null, null, Collections.<List<Object>>emptyList(), false)));
        assertEquals("{\"columns\":[],\"rows\":[],\"rowCount\":0,\"truncated\":false}",
                Json.write(query("fixed", "SELECT 1 UNION SELECT 2")));
    }

    @Test
    void maxRowsZeroReturnsTheColumnsOfAPositionalSource() throws Exception {
        TestDb.StatementProvider provider = db.new StatementProvider("anon", false);
        ctx.spi.register(DataSourceProvider.class, provider);
        Object value = query("anon", "SELECT code, name FROM person", "maxRows", 0L);
        assertEquals(1, provider.lastMaxRows);
        assertEquals("{\"columns\":[{\"name\":\"code\",\"type\":null},{\"name\":\"name\",\"type\":null}],"
                + "\"rows\":[],\"rowCount\":0,\"truncated\":true}", Json.write(value));
    }

    @Test
    void aNameKeyedMaskSeesTheDerivedName() throws Exception {
        final List<String> columns = Collections.synchronizedList(new ArrayList<String>());
        // A PasswordMask-shaped policy: by column name only.
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> {
            columns.add(sourceId + "/" + column);
            return "password".equalsIgnoreCase(column) ? "<redacted>" : value;
        });
        List<List<Object>> rows = new ArrayList<List<Object>>();
        rows.add(Arrays.<Object>asList("100", "planted-secret-1", "Ada"));
        rows.add(Arrays.<Object>asList("200", "planted-secret-2", "Grace"));
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("staffByPosition",
                new QueryResult(null, null, rows, false)));
        Object value = query("staffByPosition", "SELECT code, password, name FROM staff ORDER BY code");
        String json = Json.write(value);
        assertEquals("[[\"100\",\"<redacted>\",\"Ada\"],[\"200\",\"<redacted>\",\"Grace\"]]",
                Json.write(((Map<?, ?>) value).get("rows")));
        assertFalse(json.contains("planted-secret"), json);
        assertTrue(columns.contains("staffByPosition/password"), columns.toString());
    }

    @Test
    void theStarFormAsksColumnsOnceAndOnlyForTheStarForm() throws Exception {
        CountingProvider provider = new CountingProvider("star", Arrays.asList("code", "password", "name"),
                new QueryResult(null, null, Collections.<List<Object>>emptyList(), false));
        ctx.spi.register(DataSourceProvider.class, provider);
        Object value = query("star", "SELECT * FROM staff WHERE 1 = 0");
        assertEquals("[{\"name\":\"code\",\"type\":null},{\"name\":\"password\",\"type\":null},"
                + "{\"name\":\"name\",\"type\":null}]", Json.write(((Map<?, ?>) value).get("columns")));
        assertEquals(Collections.singletonList("staff"), provider.asked);
        query("star", "SELECT code FROM staff");
        query("star", "SELECT s.* FROM staff s");
        query("star", "SELECT * FROM staff a JOIN loan b ON a.code = b.staff");
        assertEquals(Collections.singletonList("staff"), provider.asked, "only the star form of one table");
        query("star", "SELECT * FROM \"PUBLIC\".\"STAFF\" s ORDER BY code");
        query("star", "SELECT * FROM \"staff\"");
        // Security fix S162: a quoted part keeps its quotes, so the provider can tell "staff" from staff.
        assertEquals(Arrays.asList("staff", "\"PUBLIC\".\"STAFF\"", "\"staff\""), provider.asked,
                "as spelled, a quoted part in double quotes");

        CountingProvider named = new CountingProvider("named", Arrays.asList("x"), new QueryResult(
                Arrays.asList("CODE"), null, Collections.singletonList(Arrays.<Object>asList("100")), false));
        ctx.spi.register(DataSourceProvider.class, named);
        query("named", "SELECT * FROM staff");
        assertTrue(named.asked.isEmpty(), "not asked when the provider named a column");
    }

    @Test
    void anUnusableColumnsAnswerLeavesTheColumnsAsToday() throws Exception {
        List<List<Object>> one = Collections.singletonList(Arrays.<Object>asList("100", "Ada"));
        ctx.spi.register(DataSourceProvider.class, new CountingProvider("none", null,
                new QueryResult(null, null, one, false)));
        ctx.spi.register(DataSourceProvider.class, new CountingProvider("gap", Arrays.asList("code", ""),
                new QueryResult(null, null, one, false)));
        ctx.spi.register(DataSourceProvider.class, new CountingProvider("short", Arrays.asList("code"),
                new QueryResult(null, null, one, false)));
        CountingProvider throwing = new CountingProvider("throws", null, new QueryResult(null, null, one, false));
        throwing.failure = new IllegalStateException("columns of a secret table");
        ctx.spi.register(DataSourceProvider.class, throwing);
        for (String source : Arrays.asList("none", "gap", "short", "throws")) {
            assertEquals("[{\"name\":\"c1\",\"type\":null},{\"name\":\"c2\",\"type\":null}]",
                    Json.write(((Map<?, ?>) query(source, "SELECT * FROM staff")).get("columns")), source);
        }
        assertTrue(ctx.log.all().contains("data: data source 'throws' failed in columns(): "
                + "java.lang.IllegalStateException"), ctx.log.all());
        assertFalse(ctx.log.all().contains("secret table"), "the class only (S22)");
        assertTrue(ctx.log.all().contains("data: source 'short': columns(table) names 1 column(s), the result has 2;"
                + " not used for naming"), ctx.log.all());
    }

    @Test
    void typesAreReportedWhenGivenAndRaggedRowsArePadded() throws Exception {
        List<List<Object>> rows = new ArrayList<List<Object>>();
        rows.add(Arrays.<Object>asList(1, "a"));
        rows.add(Arrays.<Object>asList(2, "b", "extra"));
        rows.add(Arrays.<Object>asList(3));
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("ragged",
                new QueryResult(Arrays.asList("ID", null), Arrays.asList("INTEGER"), rows, false)));
        assertEquals("{\"columns\":[{\"name\":\"ID\",\"type\":\"INTEGER\"},{\"name\":\"c2\",\"type\":null},"
                + "{\"name\":\"c3\",\"type\":null}],\"rows\":[[1,\"a\",null],[2,\"b\",\"extra\"],[3,null,null]],"
                + "\"rowCount\":3,\"truncated\":false}", Json.write(query("ragged", "SELECT 1")));
    }

    @Test
    void typesWithoutNames() throws Exception {
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("typed",
                new QueryResult(null, Arrays.asList("VARCHAR", "DATE"),
                        Collections.singletonList(Arrays.<Object>asList("x", LocalDate.of(2026, 1, 2))), false)));
        assertEquals("{\"columns\":[{\"name\":\"c1\",\"type\":\"VARCHAR\"},{\"name\":\"c2\",\"type\":\"DATE\"}],"
                + "\"rows\":[[\"x\",\"2026-01-02\"]],\"rowCount\":1,\"truncated\":false}",
                Json.write(query("typed", "SELECT 1")));
    }

    @Test
    void theAgentEnforcesMaxRowsAndTruncatedItself() throws Exception {
        List<List<Object>> rows = new ArrayList<List<Object>>();
        for (int i = 1; i <= 500; i++) {
            rows.add(Arrays.<Object>asList(i));
        }
        FixedProvider ignoresTheLimit = new FixedProvider("greedy", new QueryResult(null, null, rows, false));
        ctx.spi.register(DataSourceProvider.class, ignoresTheLimit);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) query("greedy", "SELECT n FROM numbers");
        assertEquals(200L, value.get("rowCount"));
        assertEquals(Boolean.TRUE, value.get("truncated"));
        assertEquals(201, ignoresTheLimit.lastMaxRows);

        value = (Map<String, Object>) query("greedy", "SELECT n FROM numbers", "maxRows", 500L);
        assertEquals(200L, value.get("rowCount"));

        FixedProvider exact = new FixedProvider("exact", new QueryResult(null, null, rows.subList(0, 3), false));
        ctx.spi.register(DataSourceProvider.class, exact);
        value = (Map<String, Object>) query("exact", "SELECT n FROM numbers", "maxRows", 3L);
        assertEquals(3L, value.get("rowCount"));
        assertEquals(Boolean.FALSE, value.get("truncated"));

        FixedProvider says = new FixedProvider("says", new QueryResult(null, null, rows.subList(0, 3), true));
        ctx.spi.register(DataSourceProvider.class, says);
        value = (Map<String, Object>) query("says", "SELECT n FROM numbers", "maxRows", 10L);
        assertEquals(3L, value.get("rowCount"));
        assertEquals(Boolean.TRUE, value.get("truncated"));
    }

    @Test
    void cellValuesAreConvertedLikeJdbcValues() throws Exception {
        List<Object> cells = Arrays.<Object>asList(new BigDecimal("12.50"),
                Timestamp.valueOf("2026-10-01 10:15:30.5"), new java.util.Date(0), new byte[] {1, 2, 3},
                Integer.valueOf(7), Float.valueOf(0.25f), Double.NaN, new SerialClob("text".toCharArray()),
                new SerialBlob(new byte[] {9}), Arrays.asList(1, 2), null, true, 3L, "s");
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("mixed",
                new QueryResult(null, null, Collections.singletonList(cells), false)));
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) query("mixed", "SELECT *");
        assertEquals("[[\"12.50\",\"2026-10-01T10:15:30.5\",\"1970-01-01T00:00:00Z\",\"base64:AQID\",7,0.25,\"NaN\","
                + "\"text\",\"base64:CQ==\",\"[1, 2]\",null,true,3,\"s\"]]", Json.write(value.get("rows")));
    }

    @Test
    void masksApplyToProviderRowsWithSyntheticColumnNames() throws Exception {
        final List<String> columns = Collections.synchronizedList(new ArrayList<String>());
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> {
            columns.add(sourceId + "/" + column);
            return "c2".equals(column) ? "masked" : value;
        });
        ctx.spi.register(DataSourceProvider.class, db.new StatementProvider("anon", false));
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) query("anon",
                "SELECT TRIM(code), TRIM(name) FROM person WHERE code = '200'");
        assertEquals("[[\"200\",\"masked\"]]", Json.write(value.get("rows")));
        // Security fix S160: an unnamed item is asked under its synthetic name first, then, while that leaves the
        // value as it is, under each name it references in each spelling; the first answer that alters it is kept.
        assertEquals(Arrays.asList("anon/c1", "anon/code", "anon/CODE", "anon/c2"), columns);
    }

    /** A PasswordMask-shaped policy: by column name, ignoring case. */
    private void maskPasswords() {
        ctx.spi.register(MaskPolicy.class,
                (sourceId, column, value) -> "password".equalsIgnoreCase(column) ? "<redacted>" : value);
    }

    /**
     * Security fix S160 (code review L1-F3; security L3-F2 and its sceptic): no label-less form of a positional source
     * returns a masked column in clear. Whole-list forms (a {@code t.*} item, a join, a sub-query or WITH in FROM, a
     * set operation) and lists the result is wider than (an alias spelled like a clause word, a short
     * {@code columns(table)} answer) have their cells withheld; an unnamed item is asked under the names it reads.
     */
    @Test
    void noLabelLessFormReturnsAMaskedColumnInClear() throws Exception {
        maskPasswords();
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("staffByPosition", new QueryResult(null, null,
                Collections.singletonList(Arrays.<Object>asList("100", "planted-secret-1", "Ada")), false)));
        for (String sql : Arrays.asList(
                "SELECT s.* FROM staff s",
                "SELECT staff.* FROM staff",
                "SELECT * FROM staff s JOIN loan l ON l.staff_code = s.code",
                "SELECT * FROM (SELECT * FROM staff) x",
                "WITH t AS (SELECT * FROM staff) SELECT * FROM t",
                "SELECT code, password, name FROM staff UNION SELECT code, password, name FROM staff",
                "SELECT * FROM staff, loan",
                "SELECT * FROM staff NATURAL JOIN staff_role",
                "SELECT code, s.* FROM staff s",
                "SELECT code, (password), name FROM staff",
                "SELECT code, password COLLATE SQL_TEXT, name FROM staff",
                "SELECT code, LOWER(password), name FROM staff",
                "SELECT code, CAST(password AS VARCHAR(20)), name FROM staff",
                "SELECT code, password::text, name FROM staff",
                "SELECT code, password AS 'shown', name FROM staff",
                "SELECT code window, password FROM staff",
                "SELECT code limit, password FROM staff",
                "SELECT code, NEXT VALUE FOR s, name FROM staff",
                "SELECT code, (SELECT p.password FROM staff p WHERE p.code = s.code), name FROM staff s",
                "SELECT code, (SELECT * FROM secret p WHERE p.code = s.code), name FROM staff s")) {
            String json = Json.write(query("staffByPosition", sql));
            assertFalse(json.contains("planted-secret"), sql + ": " + json);
        }
        // A short columns(table) answer for the star form (security L3-F2 (c)).
        ctx.spi.register(DataSourceProvider.class, new CountingProvider("shortStar", Arrays.asList("code"),
                new QueryResult(null, null, Collections.singletonList(Arrays.<Object>asList("100", "planted-secret-2")),
                        false)));
        String json = Json.write(query("shortStar", "SELECT * FROM staff"));
        assertFalse(json.contains("planted-secret"), json);
    }

    @Test
    void aColumnNoNameCanBeGivenIsWithheldOnlyWhileAMaskIsRegistered() throws Exception {
        List<List<Object>> rows = new ArrayList<List<Object>>();
        rows.add(Arrays.<Object>asList("100", "pw-1", null));
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("star", new QueryResult(null, null, rows,
                false)));
        assertEquals("[[\"100\",\"pw-1\",null]]",
                Json.write(((Map<?, ?>) query("star", "SELECT s.* FROM staff s")).get("rows")), "no mask: as read");
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> {
            asked.add(column);
            return value;
        });
        Map<?, ?> value = (Map<?, ?>) query("star", "SELECT s.* FROM staff s");
        assertEquals("[{\"name\":\"c1\",\"type\":null},{\"name\":\"c2\",\"type\":null},"
                + "{\"name\":\"c3\",\"type\":null}]", Json.write(value.get("columns")));
        assertEquals("[[\"<redacted>\",\"<redacted>\",null]]", Json.write(value.get("rows")),
                "withheld, a null cell stays null");
        assertTrue(asked.isEmpty(), "a withheld cell is not offered to the policies: " + asked);
        assertTrue(ctx.log.all().contains("data: source 'star': 3 column(s) could not be named from the statement;"
                + " their cells are withheld from the answer"), ctx.log.all());
    }

    @Test
    void anUnnamedItemThatReadsNoColumnKeepsItsValueUnderAMask() throws Exception {
        maskPasswords();
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("counts", new QueryResult(null, null,
                Collections.singletonList(Arrays.<Object>asList(3L, "x", 7L)), false)));
        assertEquals("{\"columns\":[{\"name\":\"c1\",\"type\":null},{\"name\":\"c2\",\"type\":null},"
                + "{\"name\":\"c3\",\"type\":null}],\"rows\":[[3,\"x\",7]],\"rowCount\":1,\"truncated\":false}",
                Json.write(query("counts", "SELECT COUNT(*), 'x', ? + 6 FROM loan", "params", Arrays.asList(1L))));
    }

    /**
     * Security fix S164 (the security L3 sceptic's L3-S2): a row shorter than the select list keeps the labels (it is
     * padded at the end); a row wider than the list withholds every column, whatever the data.
     */
    @Test
    void shortRowsKeepTheLabelsAndARowWiderThanTheListWithholdsTheColumns() throws Exception {
        maskPasswords();
        String sql = "SELECT code, password, note FROM staff WHERE code = ?";
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("short", new QueryResult(null, null,
                Collections.singletonList(Arrays.<Object>asList("S1", "pw-1")), false)));
        Map<?, ?> value = (Map<?, ?>) query("short", sql, "params", Arrays.asList("S1"));
        assertEquals("[{\"name\":\"code\",\"type\":null},{\"name\":\"password\",\"type\":null},"
                + "{\"name\":\"note\",\"type\":null}]", Json.write(value.get("columns")));
        assertEquals("[[\"S1\",\"<redacted>\",null]]", Json.write(value.get("rows")));
        List<List<Object>> ragged = new ArrayList<List<Object>>();
        ragged.add(Arrays.<Object>asList("S1", "pw-1", null));
        ragged.add(Arrays.<Object>asList("S2", "pw-2", "n", "x"));
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("ragged", new QueryResult(null, null, ragged,
                false)));
        String json = Json.write(query("ragged", sql, "params", Arrays.asList("S1")));
        assertFalse(json.contains("pw-"), json);
        assertTrue(json.contains("\"rows\":[[\"<redacted>\",\"<redacted>\",null,null],[\"<redacted>\",\"<redacted>\","
                + "\"<redacted>\",\"<redacted>\"]]"), json);
    }

    /**
     * Security fix S163 (the security L3 sceptic's L3-S1): a policy that compares names exactly, written against a
     * driver that folds unquoted names, still sees a positional source's derived label in its spelling.
     */
    @Test
    void anExactCasePolicySeesTheDerivedLabelInEverySpelling() throws Exception {
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> "PASSWORD".equals(column) ? "<upper>" : value);
        ctx.spi.register(MaskPolicy.class, (sourceId, column, value) -> "name".equals(column) ? "<lower>" : value);
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("case", new QueryResult(null, null,
                Collections.singletonList(Arrays.<Object>asList("100", "planted-secret-3", "Ada")), false)));
        for (String sql : Arrays.asList("SELECT code, password, name FROM staff",
                "SELECT s.code, s.Password, s.NAME FROM staff s", "SELECT CODE, (password), UPPER(Name) FROM staff")) {
            Map<?, ?> value = (Map<?, ?>) query("case", sql);
            assertEquals("[[\"100\",\"<upper>\",\"<lower>\"]]", Json.write(value.get("rows")), sql);
        }
        // The provider's own names are asked as given.
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("named", new QueryResult(
                Arrays.asList("code", "password"), null,
                Collections.singletonList(Arrays.<Object>asList("100", "as-the-provider-spells-it")), false)));
        assertEquals("[[\"100\",\"as-the-provider-spells-it\"]]",
                Json.write(((Map<?, ?>) query("named", "SELECT code, password FROM staff")).get("rows")));
    }

    @Test
    void nullFromQueryFallsBackToOpen() throws Exception {
        final AtomicInteger queries = new AtomicInteger();
        final TestDb.JdbcProvider jdbc = db.new JdbcProvider("both");
        ctx.spi.register(DataSourceProvider.class, new DataSourceProvider() {
            @Override
            public String id() {
                return "both";
            }

            @Override
            public String describe() {
                return "query() declines, open() serves";
            }

            @Override
            public QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs) {
                queries.incrementAndGet();
                return null;
            }

            @Override
            public Connection open() throws SQLException {
                return jdbc.open();
            }
        });
        assertEquals("[[\"Grace\"]]", Json.write(((Map<?, ?>) query("both",
                "SELECT name FROM person WHERE code = ?", "params", Arrays.asList("200"))).get("rows")));
        assertEquals(1, queries.get());
        assertEquals(1, jdbc.opens.get());
        assertEquals(true, jdbc.spy.allClosed());
    }

    @Test
    void queryFailuresAreMapped() throws Exception {
        ctx.spi.register(DataSourceProvider.class, new ThrowingProvider("sql",
                new SQLException("table missing", "42S02", 942)));
        RpcException e = ctx.fails("data.query", args("source", "sql", "sql", "SELECT x FROM y"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertEquals("42S02", e.error().data().get("sqlState"));
        assertEquals(942L, e.error().data().get("errorCode"));
        // S22: the provider did not opt in, so its text is withheld (DriverTextTest covers the opt-in)
        assertEquals(Boolean.TRUE, e.error().data().get("messageWithheld"));
        assertEquals("query on data source 'sql' failed: sqlState=42S02, errorCode=942 (java.sql.SQLException)",
                e.error().message());

        RpcException own = new RpcException(ErrorCodes.NOT_READY, "warming up");
        ctx.spi.register(DataSourceProvider.class, new ThrowingProvider("rpc", own));
        assertSame(own, ctx.fails("data.query", args("source", "rpc", "sql", "SELECT 1")));

        ctx.spi.register(DataSourceProvider.class, new ThrowingProvider("bug", new IllegalStateException("bad")));
        e = ctx.fails("data.query", args("source", "bug", "sql", "SELECT 1"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertEquals(IllegalStateException.class.getName(), e.error().data().get("exception"));
        assertEquals(Boolean.TRUE, e.error().data().get("messageWithheld"));
        assertNull(e.error().data().get("sqlState"), "no SQLException in the cause chain");
        assertNull(e.error().data().get("errorCode"));
        assertEquals("query on data source 'bug' failed in query() (java.lang.IllegalStateException)",
                e.error().message());

        ctx.spi.register(DataSourceProvider.class, new ThrowingProvider("checked", new java.io.IOException("io")));
        e = ctx.fails("data.query", args("source", "checked", "sql", "SELECT 1"));
        assertEquals(java.io.IOException.class.getName(), e.error().data().get("exception"));
    }

    @Test
    void anUnreadableLargeObjectIsAnSqlFailure() throws Exception {
        SerialClob freed = new SerialClob("gone".toCharArray());
        freed.free();
        ctx.spi.register(DataSourceProvider.class, new FixedProvider("stale",
                new QueryResult(null, null, Collections.singletonList(Arrays.<Object>asList(freed)), false)));
        RpcException e = ctx.fails("data.query", args("source", "stale", "sql", "SELECT 1"));
        assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code());
        assertEquals("javax.sql.rowset.serial.SerialException", e.error().data().get("exception"));
    }

    @Test
    void anInterruptedProviderEndsTheCallAsInterrupted() {
        ctx.spi.register(DataSourceProvider.class, new ThrowingProvider("slow", new InterruptedException("stop")));
        InterruptedException e = assertThrows(InterruptedException.class, () -> query("slow", "SELECT 1"));
        assertEquals("stop", e.getMessage());
    }

    @Test
    void maxRowsZeroAsksTheProviderForOneRowAndKeepsNone() throws Exception {
        TestDb.StatementProvider provider = db.new StatementProvider("api", true);
        ctx.spi.register(DataSourceProvider.class, provider);
        Object value = query("api", "SELECT code FROM person", "maxRows", 0L);
        assertEquals(1, provider.lastMaxRows);
        assertEquals("{\"columns\":[{\"name\":\"CODE\",\"type\":null}],\"rows\":[],\"rowCount\":0,\"truncated\":true}",
                Json.write(value));
    }

    /** Returns a fixed result from query(). */
    static final class FixedProvider implements DataSourceProvider {
        private final String id;
        private final QueryResult result;
        volatile int lastMaxRows;

        FixedProvider(String id, QueryResult result) {
            this.id = id;
            this.result = result;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String describe() {
            return "fixed rows";
        }

        @Override
        public QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs) {
            lastMaxRows = maxRows;
            return result;
        }
    }

    /** Returns a fixed result from query() and a fixed answer from columns(table), recording each table asked. */
    static final class CountingProvider implements DataSourceProvider {
        private final String id;
        private final List<String> columns;
        private final QueryResult result;
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
        volatile RuntimeException failure;

        CountingProvider(String id, List<String> columns, QueryResult result) {
            this.id = id;
            this.columns = columns;
            this.result = result;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String describe() {
            return "positional rows";
        }

        @Override
        public QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs) {
            return result;
        }

        @Override
        public List<String> columns(String table) {
            asked.add(table);
            if (failure != null) {
                throw failure;
            }
            return columns;
        }
    }

    /** Throws from query(). */
    static final class ThrowingProvider implements DataSourceProvider {
        private final String id;
        private final Exception failure;

        ThrowingProvider(String id, Exception failure) {
            this.id = id;
            this.failure = failure;
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
            throw failure;
        }
    }
}
