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

import ai.skillroy.testjava.protocol.sql.Rows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract every {@link DataChannel} keeps (phase2-contracts.md 7.1, 14.1 item 3): the same statements, with
 * parameters of every allowed form, give the same {@link Rows} on every channel, the same refusals and the same SQL
 * failure. Each concrete channel runs it as a subclass; {@link ChannelParityTest} also compares the channels directly.
 */
abstract class DataChannelContract {

    static final String SOURCE = WireAgent.SOURCE;

    /** A statement with its parameters and row limit. */
    record Statement(String sql, List<Object> params, int maxRows) {
        static Statement of(String sql, Object... params) {
            return new Statement(sql, Arrays.asList(params), DataChannel.MAX_ROWS);
        }

        Statement max(int rows) {
            return new Statement(sql, params, rows);
        }
    }

    /** Statements both channels must answer identically: every column form, every parameter form, the cap. */
    static final List<Statement> STATEMENTS = List.of(
            Statement.of("SELECT code, name, active, credit, born, seen FROM person ORDER BY code"),
            Statement.of("SELECT code FROM person WHERE code = ?", "100"),
            Statement.of("SELECT code FROM person WHERE active = ? ORDER BY code", Boolean.TRUE),
            Statement.of("SELECT code FROM person WHERE credit >= CAST(? AS DECIMAL(10,2)) ORDER BY code", "250.00"),
            Statement.of("SELECT code FROM person WHERE credit > CAST(? AS DECIMAL(10,2)) ORDER BY code", "250.00"),
            Statement.of("SELECT id, code, deposit FROM item WHERE id <= ? ORDER BY id", 3L),
            Statement.of("SELECT code FROM person WHERE CAST(? AS VARCHAR(10)) IS NULL ORDER BY code", (Object) null),
            Statement.of("SELECT born FROM person WHERE born = CAST(? AS DATE)", "1990-05-17"),
            Statement.of("WITH x AS (SELECT code FROM person) SELECT COUNT(*) AS n FROM x"),
            Statement.of("SELECT id FROM item ORDER BY id"),
            Statement.of("SELECT id FROM item WHERE id <= 200 ORDER BY id"),
            Statement.of("SELECT id, code FROM item ORDER BY id").max(0),
            Statement.of("SELECT id FROM item ORDER BY id").max(5),
            Statement.of("SELECT code, deposit FROM item WHERE deposit BETWEEN CAST(? AS DECIMAL(10,2))"
                    + " AND CAST(? AS DECIMAL(10,2)) AND code <> ? ORDER BY deposit, code",
                    "20.00", "34.99", "ITEM-30"));

    static TestDatabase db;

    @BeforeAll
    static void createDatabase() throws Exception {
        db = TestDatabase.create();
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        db.close();
    }

    /** The channel under test, serving {@link #SOURCE} from {@link #db}. */
    abstract DataChannel channel() throws Exception;

    Rows run(Statement s) throws Exception {
        return channel().query(SOURCE, s.sql(), new ArrayList<>(s.params()), s.maxRows(), null);
    }

    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    @Test
    void columnValuesHaveTheFormsOfTheReadPath() throws Exception {
        Rows rows = run(STATEMENTS.get(0));
        assertEquals(List.of("CODE", "NAME", "ACTIVE", "CREDIT", "BORN", "SEEN"),
                rows.columns().stream().map(Rows.Column::name).toList());
        assertEquals(List.of(row("100", "Ada", true, "250.00", "1990-05-17", "2026-10-01T10:15:30.123"),
                row("200", "Grace", true, "12.50", "1985-12-09", null),
                row("300", "Linus", false, null, null, null)), rows.rows());
        assertFalse(rows.truncated());
        assertEquals(3L, run(STATEMENTS.get(8)).get(0, 0), "COUNT(*) is a whole number");
    }

    @Test
    void parametersOfEveryAllowedForm() throws Exception {
        assertEquals(List.of(row("100")), run(STATEMENTS.get(1)).rows(), "String");
        assertEquals(List.of(row("100"), row("200")), run(STATEMENTS.get(2)).rows(), "Boolean");
        assertEquals(List.of(row("100")), run(STATEMENTS.get(3)).rows(), "a decimal string at its exact bound");
        assertEquals(List.of(), run(STATEMENTS.get(4)).rows(), "strictly above the bound");
        assertEquals(List.of(row(1L, "ITEM-1", "1.99"), row(2L, "ITEM-2", "2.99"), row(3L, "ITEM-3", "3.99")),
                run(STATEMENTS.get(5)).rows(), "Long");
        assertEquals(3, run(STATEMENTS.get(6)).rowCount(), "null");
        assertEquals(List.of(row("1990-05-17")), run(STATEMENTS.get(7)).rows(), "a date as ISO text");
    }

    @Test
    void theRowCapAndTruncation() throws Exception {
        Rows capped = run(STATEMENTS.get(9));
        assertEquals(200, capped.rowCount());
        assertTrue(capped.truncated(), "250 rows match");
        Rows exact = run(STATEMENTS.get(10));
        assertEquals(200, exact.rowCount());
        assertFalse(exact.truncated(), "exactly 200 rows is not truncated");
        Rows none = run(STATEMENTS.get(11));
        assertEquals(0, none.rowCount());
        assertEquals(2, none.columns().size(), "maxRows 0 returns the columns only");
        assertTrue(none.truncated());
        assertEquals(5, run(STATEMENTS.get(12)).rowCount());
    }

    @Test
    void theGuardRefusesTheSameStatements() throws Exception {
        for (String sql : List.of("DELETE FROM person", "SELECT 1 FROM person; DROP TABLE person",
                "UPDATE person SET name = 'x'", "SELECT * INTO copy FROM person")) {
            DataException e = assertThrows(DataException.class, () -> channel().query(SOURCE, sql, List.of(),
                    10, null), sql);
            assertEquals(DataException.Kind.GUARD, e.kind(), sql);
            assertEquals(SOURCE, e.source());
        }
        assertEquals(3L, run(STATEMENTS.get(8)).get(0, 0), "nothing was deleted");
    }

    @Test
    void parametersOfAnotherFormAreRefusedOnEveryChannel() throws Exception {
        for (Object bad : List.of(34.99d, new BigDecimal("34.99"), 7, new BigInteger("7"), 'c',
                java.time.LocalDate.of(2026, 10, 2), List.of("x"))) {
            List<Object> params = new ArrayList<>();
            params.add(bad);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> channel().query(SOURCE,
                    "SELECT code FROM person WHERE code = ?", params, 10, null), String.valueOf(bad));
            assertTrue(e.getMessage().startsWith("params[0] is a "), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> channel().query(SOURCE, "SELECT 1", List.of(), 201, null));
        assertThrows(IllegalArgumentException.class, () -> channel().query(SOURCE, "SELECT 1", List.of(), -1, null));
        assertThrows(IllegalArgumentException.class, () -> channel().query(SOURCE, null, List.of(), 1, null));
        assertThrows(IllegalArgumentException.class, () -> channel().query(SOURCE, "SELECT 1", null, 1, null));
    }

    @Test
    void anSqlFailureCarriesStateCodeAndClassButNotTheDriverText() throws Exception {
        DataException e = assertThrows(DataException.class, () -> channel().query(SOURCE,
                "SELECT nosuch_column FROM person WHERE code = ?", List.of("Param-Value-123"), 10, null));
        assertEquals(DataException.Kind.SQL, e.kind());
        assertEquals("42501", e.sqlState());
        assertEquals(-5501, e.errorCode());
        assertEquals("java.sql.SQLSyntaxErrorException", e.exceptionClass());
        assertFalse(e.getMessage().toUpperCase().contains("NOSUCH"), "the driver names the column: " + e.getMessage());
        assertFalse(e.getMessage().contains("Param-Value-123"), e.getMessage());
        assertEquals(null, e.getCause());
    }

    @Test
    void theChannelServesItsSource() throws Exception {
        assertTrue(channel().serves(SOURCE));
        assertFalse(channel().serves("elsewhere"));
    }
}
