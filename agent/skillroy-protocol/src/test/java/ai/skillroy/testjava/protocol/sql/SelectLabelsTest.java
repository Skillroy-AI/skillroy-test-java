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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SelectLabels} (phase8-contracts.md 4.4, gaps 12 and 117, D531, D532): the labels a statement's top-level
 * select list gives, read with the guard's own lexical rules in every dialect reading, null on any doubt, never a
 * value, never a throw.
 */
class SelectLabelsTest {

    private static Arguments labels(String sql, String... expected) {
        return Arguments.of(sql, Arrays.asList(expected));
    }

    static Stream<Arguments> acceptForms() {
        return Stream.of(
            // bare and qualified columns, aliases with and without AS
            labels("SELECT code, name FROM staff", "code", "name"),
            labels("SELECT s.code, s.name AS staff_name FROM staff s", "code", "staff_name"),
            labels("SELECT code AS c, name n FROM staff", "c", "n"),
            labels("select Code as Item_Code, app.item.deposit from item", "Item_Code", "deposit"),
            labels("SELECT date, year, first FROM t", "date", "year", "first"),
            labels("SELECT 1", (String) null),
            labels("SELECT 1 AS one", "one"),
            labels("SELECT 1 one, 'x' two, 2.5 three, -1 four FROM t", "one", "two", "three", "four"),
            // quoted identifiers of every dialect
            labels("SELECT \"Item Code\", \"a\"\"b\", t.\"name\", t.\"name\" AS \"Display Name\" FROM t", "Item Code",
                    "a\"b", "name", "Display Name"),
            labels("SELECT `code`, `t`.`name`, deposit AS `dep``o` FROM t", "code", "name", "dep`o"),
            labels("SELECT [code], [dbo].[item].[name] AS [n m], [a]]b] FROM t", "code", "n m", "a]b"),
            labels("SELECT code x, name \"y\", deposit `z` FROM t", "x", "y", "z"),
            // DISTINCT, ALL, DISTINCT ON, TOP
            labels("SELECT DISTINCT code FROM loan", "code"),
            labels("SELECT ALL code, name FROM loan", "code", "name"),
            labels("SELECT DISTINCT ON (code, member) code, member, due FROM loan", "code", "member", "due"),
            labels("SELECT TOP 5 code, name FROM item", "code", "name"),
            labels("SELECT TOP (5) PERCENT WITH TIES code FROM item ORDER BY code", "code"),
            labels("SELECT top FROM t", "top"),
            // WITH lists
            labels("WITH x AS (SELECT 1 AS a, 2 AS b) SELECT a, b FROM x", "a", "b"),
            labels("with recursive r(n) as (select 1 union all select n + 1 from r where n < 3) select n from r", "n"),
            labels("WITH a AS (SELECT 1 AS v), b AS (SELECT v FROM a) SELECT v AS w FROM b;", "w"),
            // commas inside functions, CASE, brackets; nested parentheses; subqueries with set operations
            labels("SELECT COUNT(*) AS n, MAX(due) m, COALESCE(a, b, c) FROM loan", "n", "m", null),
            labels("SELECT CASE WHEN a = 1 THEN 'x, y' ELSE 'z' END AS kind, CASE WHEN b THEN 1 END status FROM t",
                    "kind", "status"),
            labels("SELECT CASE WHEN a THEN 1 ELSE 2 END FROM t", (String) null),
            labels("SELECT ((a + (b * (c))), d) AS pair, (SELECT MAX(x) FROM y) AS top_x FROM t", "pair", "top_x"),
            labels("SELECT code FROM item WHERE name IN (SELECT name FROM item2 UNION SELECT 'x')", "code"),
            labels("SELECT (SELECT 1 UNION SELECT 2) AS v", "v"),
            labels("SELECT ARRAY[1, 2] AS arr, a[1] AS first, a[2] b FROM t", "arr", "first", "b"),
            labels("SELECT {fn UCASE(name)} AS upper_name, code FROM t", "upper_name", "code"),
            // comments between tokens
            labels("/* lead */ SELECT /* c, d */ code -- e, f\n , name/**/AS/**/n FROM t", "code", "n"),
            labels("-- a comment\nSELECT code FROM t -- , not an item", "code"),
            // expressions without an alias are null; keywords are never labels
            labels("SELECT a + b, a - b AS d, -a, a || b FROM t", null, "d", null, null),
            labels("SELECT x IS NULL, y IS NOT NULL AS has_y, z ISNULL FROM t", null, "has_y", null),
            // ZONE leads in AT TIME ZONE tz, so an identifier after it is not taken as an alias, even after a type
            labels("SELECT x::text, x::text AS t, y::int i, y::timestamp with time zone z FROM t", null, "t", "i",
                    null),
            labels("SELECT d AT TIME ZONE tz, date d2 FROM t", null, "d2"),
            labels("SELECT CAST(x AS INT), CAST(x AS INT) AS n FROM t", null, "n"),
            labels("SELECT INTERVAL '1' DAY, d AT TIME ZONE 'UTC' FROM t", null, null),
            labels("SELECT a IS DISTINCT FROM b AS changed, c FROM t", "changed", "c"),
            labels("SELECT a IS NOT DISTINCT FROM b, c FROM t", null, "c"),
            labels("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY deposit) AS median, code FROM item"
                    + " GROUP BY code", "median", "code"),
            labels("SELECT ROW_NUMBER() OVER (PARTITION BY a ORDER BY b) AS rn, a FROM t", "rn", "a"),
            labels("SELECT $1 AS p, 'it''s' AS q, ? AS r FROM t", "p", "q", "r"),
            labels("SELECT 1 FOR XML PATH", (String) null),
            // a string literal is never a label, with or without AS
            labels("SELECT 'secret', 'secret' AS 'x', 'a' 'b' FROM t", null, null, null),
            // the readings disagree on one entry: it is null, the others stand
            labels("SELECT a--1 FROM t", (String) null),
            labels("SELECT arr[1], x [y], code FROM t", null, null, "code"),
            // a reading whose brackets do not balance does not count
            labels("SELECT a # (\n, b FROM t", "a", "b"),
            labels("SELECT [a(], b FROM t", "a(", "b"));
    }

    @ParameterizedTest
    @MethodSource("acceptForms")
    void readsTheLabelsOfTheTopLevelList(String sql, List<String> expected) {
        assertNotNull(StatementGuard.check(sql), "the corpus holds statements the guard accepts: " + sql);
        assertEquals(expected, SelectLabels.of(sql), sql);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        // set operations at the top level
        "SELECT 1 UNION SELECT 2",
        "SELECT a FROM t UNION ALL SELECT b FROM u",
        "SELECT a FROM t INTERSECT SELECT a FROM u",
        "SELECT a FROM t EXCEPT SELECT a FROM u ORDER BY 1",
        "SELECT a FROM t MINUS SELECT a FROM u",
        "WITH x AS (SELECT 1 AS a) SELECT a FROM x UNION SELECT 2",
        // star items
        "SELECT * FROM t",
        "SELECT t.*, a FROM t",
        "SELECT a, * FROM t",
        "SELECT \"t\".* FROM t",
        "SELECT [t].* FROM t",
        // empty items
        "SELECT a, FROM t",
        "SELECT , a FROM t",
        "SELECT FROM t",
        // a reading that splits the list differently
        "SELECT a # , b\n FROM t",
        "SELECT a # b, c FROM t",
        "SELECT 1 /*! , 2 */",
        "SELECT 1 /* /* */ , 2 */",
        // statements the guard rejects
        "",
        "DELETE FROM t",
        "SELECT 1; SELECT 2",
        "(SELECT a FROM t)",
        "SELECT 'unterminated FROM t",
        // no SELECT after a WITH list, unbalanced brackets
        "WITH x AS (SELECT 1)",
        "SELECT (a FROM t",
        "SELECT a) FROM t",
        "SELECT (a] FROM t",
    })
    void givesUpOnWhatItCannotRead(String sql) {
        assertNull(SelectLabels.of(sql), String.valueOf(sql));
    }

    @Test
    void boundsTheDepthAndTheNumberOfItems() {
        assertEquals(Collections.singletonList("deep"), SelectLabels.of(nested(SelectLabels.MAX_DEPTH)));
        assertNull(SelectLabels.of(nested(SelectLabels.MAX_DEPTH + 1)), "parentheses nested beyond 32");
        List<String> thousand = SelectLabels.of(items(SelectLabels.MAX_ITEMS));
        assertNotNull(thousand);
        assertEquals(SelectLabels.MAX_ITEMS, thousand.size());
        assertEquals("c999", thousand.get(999));
        assertNull(SelectLabels.of(items(SelectLabels.MAX_ITEMS + 1)), "1001 items");
        assertNull(SelectLabels.starTable("SELECT * FROM t WHERE " + parentheses(SelectLabels.MAX_DEPTH + 1)));
        assertEquals("t", SelectLabels.starTable("SELECT * FROM t WHERE " + parentheses(SelectLabels.MAX_DEPTH)));
    }

    static Stream<Arguments> starForms() {
        return Stream.of(
            Arguments.of("SELECT * FROM staff", "staff"),
            Arguments.of("select * from app.staff s where s.code = ?", "app.staff"),
            // Security fix S162: a quoted part keeps its quotes, in double quotes, an inner quote doubled.
            Arguments.of("SELECT * FROM \"App\".\"Staff\" AS st ORDER BY code", "\"App\".\"Staff\""),
            Arguments.of("SELECT * FROM `staff` LIMIT 5", "\"staff\""),
            Arguments.of("SELECT * FROM [dbo].[staff]", "\"dbo\".\"staff\""),
            Arguments.of("SELECT * FROM \"staff\"", "\"staff\""),
            Arguments.of("SELECT * FROM PUBLIC.\"staff\" s WHERE s.code = ?", "PUBLIC.\"staff\""),
            Arguments.of("SELECT * FROM \"sta\"\"ff\"", "\"sta\"\"ff\""),
            Arguments.of("SELECT * FROM lending.app.staff FETCH FIRST 5 ROWS ONLY", "lending.app.staff"),
            Arguments.of("SELECT * FROM staff OFFSET 2 ROWS", "staff"),
            Arguments.of("/* c */ SELECT * FROM staff;", "staff"),
            Arguments.of("SELECT * FROM staff WHERE code IN (SELECT code FROM loan UNION SELECT 'x')", "staff"),
            Arguments.of("SELECT * FROM loan WHERE a[1] = 2", "loan"),
            // not the form
            Arguments.of("SELECT * FROM a JOIN b ON a.x = b.x", null),
            Arguments.of("SELECT * FROM a LEFT JOIN b ON a.x = b.x", null),
            Arguments.of("SELECT * FROM a NATURAL JOIN b", null),
            Arguments.of("SELECT * FROM a, b", null),
            Arguments.of("SELECT * FROM a x, b", null),
            Arguments.of("SELECT * FROM a x JOIN b y ON x.k = y.k", null),
            Arguments.of("SELECT * FROM a UNION SELECT * FROM b", null),
            Arguments.of("SELECT * FROM a WHERE x = 1 UNION SELECT * FROM b", null),
            Arguments.of("SELECT * FROM a WHERE x = 1 GROUP BY y", null),
            Arguments.of("SELECT * FROM a FOR XML PATH", null),
            Arguments.of("SELECT code FROM a", null),
            Arguments.of("SELECT a.* FROM a", null),
            Arguments.of("SELECT *, 1 FROM a", null),
            Arguments.of("SELECT DISTINCT * FROM a", null),
            Arguments.of("SELECT TOP 1 * FROM a", null),
            Arguments.of("WITH x AS (SELECT 1) SELECT * FROM x", null),
            Arguments.of("SELECT * FROM (SELECT 1) x", null),
            Arguments.of("SELECT * FROM f(1)", null),
            Arguments.of("SELECT * FROM a WITH (NOLOCK)", null),
            Arguments.of("SELECT * FROM ONLY a", null),
            Arguments.of("SELECT * FROM a.b.c.d", null),
            Arguments.of("SELECT * FROM \"a.b\"", null),
            Arguments.of("SELECT * FROM a # x\n", null),
            Arguments.of("SELECT * FROM a WHERE (x = 1", null),
            Arguments.of("DELETE FROM a", null),
            Arguments.of("SELECT * FROM a; SELECT 1", null),
            Arguments.of(null, null));
    }

    @ParameterizedTest
    @MethodSource("starForms")
    void readsTheTableOfTheStarForm(String sql, String expected) {
        assertEquals(expected, SelectLabels.starTable(sql), String.valueOf(sql));
    }

    static Stream<Arguments> referenceForms() {
        return Stream.of(
            Arguments.of("SELECT code, password FROM staff", Arrays.asList(names("code"), names("password"))),
            Arguments.of("SELECT s.code, (password), password COLLATE SQL_TEXT FROM staff s",
                    Arrays.asList(names("code"), names("password"), names("password", "SQL_TEXT"))),
            Arguments.of("SELECT LOWER(password), COUNT(*), 'x', ?, CURRENT_DATE, 1 FROM staff",
                    Arrays.asList(names("password"), names(), names(), names(), names(), names())),
            Arguments.of("SELECT CAST(password AS TEXT), password::text FROM staff",
                    Arrays.asList(names("password", "TEXT"), names("password", "text"))),
            Arguments.of("SELECT password AS p, password AS 'q' FROM staff",
                    Arrays.asList(names("password", "p"), names("password"))),
            Arguments.of("SELECT (SELECT p.password FROM pw p WHERE p.code = s.code) FROM staff s",
                    Arrays.asList(names("password", "pw", "p", "code"))),
            Arguments.of("SELECT x.y.z, a * b, \"Quoted\" FROM t",
                    Arrays.asList(names("z"), names("a", "b"), names("Quoted"))),
            Arguments.of("SELECT code, (SELECT * FROM pw WHERE pw.code = s.code) FROM staff s",
                    Arrays.asList(names("code"), null)),
            Arguments.of("SELECT (SELECT TOP 1 * FROM pw) FROM staff", Collections.singletonList(null)),
            Arguments.of("SELECT (SELECT pw.* FROM pw) FROM staff", Collections.singletonList(null)),
            Arguments.of("SELECT * FROM staff", null),
            Arguments.of("SELECT s.* FROM staff s", null),
            Arguments.of("SELECT a FROM t UNION SELECT b FROM u", null),
            Arguments.of("DELETE FROM t", null),
            Arguments.of(null, null));
    }

    private static List<String> names(String... names) {
        return Arrays.asList(names);
    }

    /** Security fix S160: the names each select item references, split as {@code of} splits the list. */
    @ParameterizedTest
    @MethodSource("referenceForms")
    void readsTheNamesEachItemReferences(String sql, List<List<String>> expected) {
        assertEquals(expected, SelectLabels.references(sql), String.valueOf(sql));
    }

    @Test
    void neverThrowsOnTheGuardsOwnCorpus() throws Exception {
        List<String> inputs = new ArrayList<String>();
        for (Method method : StatementGuardTest.class.getDeclaredMethods()) {
            ValueSource source = method.getAnnotation(ValueSource.class);
            if (source != null) {
                inputs.addAll(Arrays.asList(source.strings()));
            }
        }
        StatementGuardTest.rejected().forEach(arguments -> inputs.add((String) arguments.get()[0]));
        assertTrue(inputs.size() > 150, "the guard's corpus was found: " + inputs.size());
        inputs.add(null);
        for (String sql : inputs) {
            List<String> labels = SelectLabels.of(sql);
            SelectLabels.starTable(sql);
            List<List<String>> references = SelectLabels.references(sql);
            assertEquals(labels == null, references == null, "references with labels only: " + sql);
            if (labels != null) {
                assertNotNull(StatementGuard.check(sql), "labels only for an accepted statement: " + sql);
                assertEquals(labels.size(), references.size(), sql);
            }
        }
        assertEquals(Arrays.asList("x_kill"), SelectLabels.of("SELECT 1 x_kill"));
    }

    @Test
    void randomTextNeverThrowsAndNeverYieldsALiteral() {
        String alphabet = "'\"`[]$;-/*#\\ \n\rSELECTwithEe!M0aZ_()., é";
        Random random = new Random(20261019L);
        int read = 0;
        for (int i = 0; i < 200000; i++) {
            StringBuilder text = new StringBuilder(random.nextInt(4) == 0 ? "" : "SELECT ");
            for (int k = random.nextInt(24); k > 0; k--) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String sql = text.toString();
            List<String> labels = SelectLabels.of(sql);
            SelectLabels.starTable(sql);
            List<List<String>> references = SelectLabels.references(sql);
            assertEquals(labels == null, references == null, sql);
            if (labels != null) {
                assertEquals(labels.size(), references.size(), sql);
                read++;
                for (String label : labels) {
                    assertTrue(label == null || !label.isEmpty(), sql);
                }
            }
        }
        assertTrue(read > 1000, "the sample must exercise the reading path too: " + read);
    }

    @Test
    void aLiteralsTextIsNeverALabel() {
        for (String sql : new String[] {
            "SELECT 'pw1' FROM staff",
            "SELECT 'pw1' AS 'pw2', N'pw3', E'pw4' AS \"x\" FROM staff",
            "SELECT $$pw1$$, $t$pw2$t$ FROM staff",
            "SELECT code, 'pw1' FROM staff WHERE password = 'pw2'",
        }) {
            List<String> labels = SelectLabels.of(sql);
            assertNotNull(labels, sql);
            for (String label : labels) {
                assertFalse(label != null && label.startsWith("pw"), sql + " -> " + labels);
            }
        }
    }

    @Test
    void theListIsUnmodifiable() {
        List<String> labels = SelectLabels.of("SELECT code FROM t");
        assertThrows(UnsupportedOperationException.class, () -> labels.add("x"));
    }

    @Test
    void readsLongStatementsInLinearTime() {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < 900; i++) {
            sql.append(i == 0 ? "" : ", ").append("COALESCE(a").append(i).append(", 'x;y', \"q\") /* c */ AS c")
                    .append(i);
        }
        sql.append(" FROM t WHERE ");
        for (int i = 0; i < 20000; i++) {
            sql.append("a = 'b;c' AND -- d\n");
        }
        sql.append("1 = 1");
        long start = System.nanoTime();
        List<String> labels = SelectLabels.of(sql.toString());
        long ms = (System.nanoTime() - start) / 1000000L;
        assertNotNull(labels);
        assertEquals(900, labels.size());
        assertEquals("c899", labels.get(899));
        assertTrue(ms < 20000, "took " + ms + " ms");
    }

    private static String nested(int depth) {
        return "SELECT " + parentheses(depth) + " AS deep FROM t";
    }

    private static String parentheses(int depth) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sql.append('(');
        }
        sql.append('1');
        for (int i = 0; i < depth; i++) {
            sql.append(')');
        }
        return sql.toString();
    }

    private static String items(int count) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < count; i++) {
            sql.append(i == 0 ? "" : ", ").append('c').append(i);
        }
        return sql.append(" FROM t").toString();
    }
}
