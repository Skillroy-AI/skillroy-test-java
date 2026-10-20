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

package ai.skillroy.testjava.inference.sql;

import ai.skillroy.testjava.inference.sql.GuardViolation.Reason;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 5 beyond the corpora: every reason code of 5.3 with a minimal statement of its own, the
 * injected {@code ORDER BY} and limit, alias and CTE resolution, the personal rule, the allow-list's other columns
 * (rule 4), a table declared with a schema, the output check (rule 10, forced), and the served dialects.
 */
class SqlGuardRulesTest {

    private static SqlGuard hsqldb;
    private static SqlGuard withOthers;

    @BeforeAll
    static void open() {
        hsqldb = new SqlGuard(LendingFixture.allowList(SqlDialect.HSQLDB));
        withOthers = new SqlGuard(LendingFixture.withOtherColumns(SqlDialect.HSQLDB));
    }

    @AfterAll
    static void close() {
        hsqldb.close();
        withOthers.close();
    }

    private static Reason refused(SqlGuard guard, String sql) {
        return assertThrows(GuardViolation.class, () -> guard.guard(sql), sql).reason();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', textBlock = """
            PARSE         | SELECT FROM WHERE
            PARSE         | SELECT i.code FROM item i; SELECT i.code FROM item i
            COMMENT       | SELECT /*+ FULL(i) */ i.code FROM item i
            COMMENT       | SELECT i.code FROM item i /* unterminated
            NOT_SELECT    | VALUES (1)
            DML           | DELETE FROM item
            DDL           | DROP TABLE item
            PROCEDURE     | CALL purge_everything(?)
            SET_OPERATION | SELECT i.code FROM item i UNION SELECT x.item_code FROM loan_line x
            LOCKING       | SELECT i.code FROM item i FOR UPDATE
            UNSUPPORTED   | SELECT i.code FROM item i, LATERAL (SELECT x.qty FROM loan_line x WHERE x.item_code = i.code) q
            TABLE         | SELECT a.id FROM audit_log a
            COLUMN        | SELECT s.pin_hash FROM staff s
            STAR          | SELECT * FROM item
            PERSONAL      | SELECT l.borrower FROM loan l
            FUNCTION      | SELECT SLEEP(5) FROM item i
            LITERAL       | SELECT i.code FROM item i WHERE i.barcode = X'00'
            ORDER         | SELECT i.code FROM item i WHERE i.code IN (SELECT x.item_code FROM loan_line x ORDER BY x.qty)
            LIMIT         | SELECT i.code FROM item i LIMIT ?
            SIZE          | SELECT i.code FROM item i WHERE ((((((((((((((((((((((((((((((((((i.active)))))))))))))))))))))))))))))))))
            """)
    void everyReasonCodeHasAMinimalStatement(Reason reason, String sql) {
        assertEquals(reason, refused(hsqldb, sql));
    }

    @Test
    void everyReasonCodeIsCovered() {
        Set<Reason> covered = EnumSet.of(Reason.PARSE, Reason.COMMENT, Reason.NOT_SELECT, Reason.DML, Reason.DDL,
                Reason.PROCEDURE, Reason.SET_OPERATION, Reason.LOCKING, Reason.UNSUPPORTED, Reason.TABLE,
                Reason.COLUMN, Reason.STAR, Reason.PERSONAL, Reason.FUNCTION, Reason.LITERAL, Reason.ORDER,
                Reason.LIMIT, Reason.SIZE, Reason.REWRITE);
        assertEquals(EnumSet.allOf(Reason.class), covered, "a new reason code needs a minimal statement here");
    }

    @Test
    void theOrderByIsCompletedAndTheLimitInjected() throws Exception {
        GuardedStatement g = hsqldb.guard("SELECT i.code, i.deposit FROM item i ORDER BY i.deposit DESC");
        assertEquals("SELECT i.code, i.deposit FROM item i ORDER BY i.deposit DESC, i.code LIMIT 200", g.sql());
        assertEquals(List.of("ORDER BY + i.code (the key of each table)", "row limit injected: LIMIT 200"),
                g.injected());
        assertEquals(List.of("item"), g.tables());
        assertEquals(List.of("item.code", "item.deposit"), g.columns());
        GuardedStatement capped = hsqldb.guard("SELECT i.code FROM item i ORDER BY i.code LIMIT 5000");
        assertEquals("SELECT i.code FROM item i ORDER BY i.code LIMIT 200", capped.sql());
        assertEquals(List.of("row limit capped: LIMIT 5000 -> LIMIT 200"), capped.injected());
    }

    @Test
    void eachDialectWritesItsOneLimitForm() throws Exception {
        try (SqlGuard db2 = new SqlGuard(LendingFixture.allowList(SqlDialect.DB2));
             SqlGuard mssql = new SqlGuard(LendingFixture.allowList(SqlDialect.SQLSERVER))) {
            assertEquals("SELECT i.code FROM item i ORDER BY i.code FETCH FIRST 7 ROWS ONLY",
                    db2.guard("SELECT i.code FROM item i LIMIT 7").sql());
            assertEquals("SELECT TOP 200 i.code FROM item i ORDER BY i.code",
                    mssql.guard("SELECT i.code FROM item i").sql());
            assertEquals(Reason.PARSE, refused(db2, "SELECT l.id FROM loan l WHERE l.due > CURRENT DATE - 7 DAYS"),
                    "DB2's plural labeled durations do not parse (the catalogue tells the model so)");
            db2.guard("SELECT l.id FROM loan l WHERE l.due > CURRENT DATE - 7 DAY");
        }
    }

    @Test
    void aliasesAndCtesResolve() throws Exception {
        GuardedStatement g = hsqldb.guard("WITH lent (code, n) AS (SELECT x.item_code, COUNT(*) FROM loan_line x"
                + " GROUP BY x.item_code) SELECT it.title, lent.n FROM item it JOIN lent ON lent.code = it.code");
        assertTrue(g.sql().endsWith("ORDER BY it.code, lent.code LIMIT 200"), g.sql());
        assertEquals(List.of("item", "loan_line"), g.tables());
        assertEquals(Reason.TABLE, refused(hsqldb, "WITH item AS (SELECT x.item_code FROM loan_line x)"
                + " SELECT item.item_code FROM item"), "a CTE may not shadow an allow-listed table");
    }

    @Test
    void aPersonalColumnFiltersButIsNeverSelected() throws Exception {
        hsqldb.guard("SELECT l.id FROM loan l WHERE l.borrower = ? ORDER BY l.borrower");
        assertEquals(Reason.PERSONAL, refused(hsqldb, "SELECT UPPER(l.borrower) AS b FROM loan l"));
        assertEquals(Reason.PERSONAL, refused(hsqldb,
                "SELECT d.b FROM (SELECT l.borrower AS b, l.id FROM loan l) d"), "the flag follows a derived column");
        assertEquals(Reason.PERSONAL, refused(hsqldb,
                "SELECT (SELECT MAX(l.borrower) FROM loan l WHERE l.id = x.loan_id) AS who FROM loan_line x"),
                "and a scalar subquery");
    }

    /**
     * The L2 sceptic's S2: a predicate subquery ({@code EXISTS}, {@code IN}, {@code ANY}) inside a select-list
     * expression carries one bit of each personal column it references into the candidate, so it is tainted with
     * them and refused; the same subqueries as filters are accepted (a personal column may filter).
     */
    /** The predicate subqueries over a personal column (the probe's {@code s2.name LIKE 'A%'}). */
    private static final String EXISTS_A = "EXISTS (SELECT 1 FROM staff s2 WHERE s2.code = s.code AND s2.name"
            + " LIKE 'A%')";
    private static final String IN_A = "s.code IN (SELECT s2.code FROM staff s2 WHERE s2.name LIKE 'A%')";
    private static final String ANY_A = "s.code = ANY (SELECT s2.code FROM staff s2 WHERE s2.name LIKE 'A%')";

    private static String flag(String predicate) {
        return "CASE WHEN " + predicate + " THEN 1 ELSE 0 END";
    }

    @Test
    void aPredicateSubqueryOverAPersonalColumnIsNeverSelected() {
        Map<String, String> refusedForms = new LinkedHashMap<>();
        refusedForms.put("EXISTS", "SELECT s.code, " + flag(EXISTS_A) + " AS f FROM staff s");
        refusedForms.put("NOT EXISTS", "SELECT s.code, " + flag("NOT EXISTS (SELECT 1 FROM staff s2 WHERE s2.code ="
                + " s.code AND s2.email LIKE 'b%')") + " AS f FROM staff s");
        refusedForms.put("IN", "SELECT s.code, " + flag(IN_A) + " AS f FROM staff s");
        refusedForms.put("ANY", "SELECT s.code, " + flag(ANY_A) + " AS f FROM staff s");
        refusedForms.put("correlated outward", "SELECT l.id, " + flag("EXISTS (SELECT 1 FROM staff s WHERE s.name ="
                + " l.borrower)") + " AS f FROM loan l");
        refusedForms.put("through a derived column", "SELECT d.code, d.f FROM (SELECT s.code AS code, "
                + flag(EXISTS_A) + " AS f FROM staff s) d");
        refusedForms.put("bare", "SELECT s.code, s.code IN (SELECT s2.code FROM staff s2 WHERE s2.name LIKE 'A%')"
                + " AS f FROM staff s");
        refusedForms.put("a scalar count, the same bit", "SELECT s.code, (SELECT COUNT(*) FROM staff s2 WHERE"
                + " s2.code = s.code AND s2.name LIKE 'A%') AS f FROM staff s");
        refusedForms.forEach((form, sql) -> assertEquals(Reason.PERSONAL, refused(hsqldb, sql), form));
    }

    @Test
    void theSamePredicateSubqueriesFilter() throws Exception {
        List<String> accepted = List.of(
                "SELECT s.code FROM staff s WHERE " + EXISTS_A,
                "SELECT s.code FROM staff s WHERE " + IN_A,
                "SELECT s.code FROM staff s WHERE " + ANY_A,
                "SELECT s.code FROM staff s WHERE " + flag(EXISTS_A) + " = 1",
                "SELECT d.code FROM (SELECT s.code AS code, " + flag(EXISTS_A) + " AS f FROM staff s) d WHERE d.f = 1",
                "SELECT s.code, " + flag("EXISTS (SELECT 1 FROM staff_role r WHERE r.staff_code = s.code AND r.role ="
                        + " 'DESK')") + " AS f FROM staff s",
                "SELECT s.code FROM staff s WHERE (SELECT COUNT(*) FROM staff s2 WHERE s2.code = s.code AND s2.name"
                        + " LIKE 'A%') = 1",
                "SELECT s.code, (SELECT COUNT(*) FROM staff_role r WHERE r.staff_code = s.code) AS roles FROM staff s");
        for (String sql : accepted) {
            hsqldb.guard(sql);
        }
    }

    /** Rule 6: the guarded statement names its string literals (unescaped, in order) for the resolver's check. */
    @Test
    void theGuardedStatementNamesItsStringLiterals() throws Exception {
        GuardedStatement g = hsqldb.guard("SELECT i.code FROM item i WHERE i.category = 'GARDEN' AND i.title <>"
                + " 'O''Neil''s saw' AND i.deposit > 10 AND i.active = TRUE AND i.code = ?");
        assertEquals(List.of("GARDEN", "O'Neil's saw"), g.literals(), "strings only: not 10, not TRUE");
        assertEquals(List.of(), hsqldb.guard("SELECT i.code FROM item i WHERE i.code = ?").literals());
    }

    @Test
    void otherColumnsResolveUnqualifiedNamesAsTheDatabaseDoes() throws Exception {
        // Without the other columns named, a correlated unqualified reference is refused (BS6's rule).
        String correlated = "SELECT s.code FROM staff s WHERE EXISTS (SELECT r.role FROM staff_role r"
                + " WHERE r.staff_code = code)";
        assertEquals(Reason.COLUMN, refused(hsqldb, correlated));
        // staff_role names no other column in either list, so the inner scope is not fully known: still refused.
        assertEquals(Reason.COLUMN, refused(withOthers, correlated));
        // A hidden column named unqualified is refused as not allow-listed, qualified too.
        assertEquals(Reason.COLUMN, refused(withOthers, "SELECT s.code FROM staff s WHERE pin_hash = ?"));
        assertEquals(Reason.COLUMN, refused(withOthers, "SELECT s.code FROM staff s WHERE s.pin_hash = ?"));
        // Fully known inner scope (staff names its other columns): the unqualified name binds outward exactly.
        GuardedStatement g = withOthers.guard("SELECT r.role FROM staff_role r WHERE EXISTS"
                + " (SELECT s.code FROM staff s WHERE s.code = staff_code)");
        assertTrue(g.columns().contains("staff_role.staff_code"), g.columns().toString());
        // A name that is allowed in one source and hidden in another of the same scope could bind to either.
        SqlAllowList clash = SqlAllowList.builder("lending", SqlDialect.HSQLDB)
                .table("item", List.of("code"), null, List.of(AllowedColumn.of("code", "VARCHAR"),
                        AllowedColumn.of("title", "VARCHAR")), List.of("barcode"))
                .table("loan_line", List.of("loan_id"), null, List.of(AllowedColumn.of("loan_id", "INTEGER"),
                        AllowedColumn.of("barcode", "VARCHAR")), List.of("qty"))
                .build();
        try (SqlGuard guard = new SqlGuard(clash)) {
            assertEquals(Reason.COLUMN, refused(guard,
                    "SELECT i.code FROM item i JOIN loan_line x ON x.loan_id = 1 WHERE barcode = ?"));
            guard.guard("SELECT i.code FROM item i JOIN loan_line x ON x.loan_id = 1 WHERE x.barcode = ?");
        }
    }

    @Test
    void aTableDeclaredWithASchemaIsNamedUnqualified() throws Exception {
        SqlAllowList list = SqlAllowList.builder("lending", SqlDialect.HSQLDB)
                .table("desk.item", List.of("code"), AllowedColumn.of("code", "VARCHAR")).build();
        try (SqlGuard guard = new SqlGuard(list)) {
            assertEquals("SELECT i.code FROM item i ORDER BY i.code LIMIT 200",
                    guard.guard("SELECT i.code FROM item i").sql());
            assertEquals(Reason.TABLE, refused(guard, "SELECT i.code FROM desk.item i"));
        }
    }

    @Test
    void theOutputCheckRefusesWhatDoesNotReadBackAsItself() {
        try (SqlGuard comment = new SqlGuard(LendingFixture.allowList(SqlDialect.HSQLDB), s -> s + " -- x");
             SqlGuard changed = new SqlGuard(LendingFixture.allowList(SqlDialect.HSQLDB),
                     s -> s.replace("i.code", "i.barcode"));
             SqlGuard reparsed = new SqlGuard(LendingFixture.allowList(SqlDialect.HSQLDB),
                     s -> s.replace("SELECT", "SELECT  "))) {
            assertEquals(Reason.REWRITE, refused(comment, "SELECT i.code FROM item i"));
            assertEquals(Reason.REWRITE, refused(changed, "SELECT i.code FROM item i"));
            assertEquals(Reason.REWRITE, refused(reparsed, "SELECT i.code FROM item i"));
        }
    }

    @Test
    void everyProcedureCallIsRefusedNamingGap119() throws Exception {
        // phase8-contracts.md 4.5, D533: no data channel runs a procedure call, so the guard refuses every CALL and
        // EXEC, whatever the allow-list says, with the reason where the model hears it.
        for (String call : List.of("CALL open_loan_count(?)", "CALL open_loan_count('S01')", "CALL other(?, ?)",
                "CALL open_loan_count()")) {
            GuardViolation v = assertThrows(GuardViolation.class, () -> hsqldb.guard(call), call);
            assertEquals(Reason.PROCEDURE, v.reason(), call);
            assertEquals(SqlGuard.PROCEDURE_RETIRED, v.fragment(), call);
            assertTrue(v.fragment().contains("gap 119"));
        }
        SqlAllowList.Builder b = SqlAllowList.builder("lending", SqlDialect.HSQLDB);
        for (SqlAllowList.AllowedTable table : LendingFixture.allowList(SqlDialect.HSQLDB).tables()) {
            b.table(table);
        }
        @SuppressWarnings("deprecation")
        SqlAllowList withProcedure = b.procedure("open_loan_count").build();
        try (SqlGuard listed = new SqlGuard(withProcedure)) {
            assertEquals(Reason.PROCEDURE, refused(listed, "CALL open_loan_count(?)"), "even one the list names");
        }
        try (SqlGuard mssql = new SqlGuard(LendingFixture.allowList(SqlDialect.SQLSERVER))) {
            assertEquals(Reason.PROCEDURE, refused(mssql, "EXECUTE open_loan_count 'S01'"));
        }
    }

    @Test
    void everyDialectIsServed() {
        for (SqlDialect d : SqlDialect.values()) {
            assertTrue(SqlGuard.served(d), d + " has a passing BS6 corpus");
        }
        assertFalse(SqlGuard.served(null));
    }

    @Test
    void theRewriteRunsOnTheEmbeddedDatabase() throws Exception {
        GuardedStatement g = hsqldb.guard("SELECT l.id, x.line_no FROM loan l JOIN loan_line x ON x.loan_id = l.id");
        try (Connection db = LendingFixture.hsqldb("rules-run");
             ResultSet rs = db.createStatement().executeQuery(g.sql())) {
            int rows = 0;
            String last = "";
            while (rs.next()) {
                String key = String.format("%05d/%05d", rs.getInt(1), rs.getInt(2));
                assertTrue(key.compareTo(last) > 0, "the tie-breaker orders every row");
                last = key;
                rows++;
            }
            assertEquals(6, rows);
        }
    }
}
