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

package ai.skillroy.testjava.inference.prompt;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.data.JdbcDataChannel;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.sql.LendingDatabase;
import ai.skillroy.testjava.inference.sql.LendingFixture;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 5.2: the prompt catalogue built from the allow-list over the lending schema, with the sample
 * columns read through the data channel (one guarded {@code SELECT DISTINCT … LIMIT 21} each, through the mask):
 * tables, keys, types, the personal note, the samples and their cut at 20, the dialect's notes, the rules; no personal
 * value and no value of a column that is not sampled; deterministic; long enough to be cached.
 */
class SchemaCatalogueTest {

    private LendingDatabase db;
    private JdbcDataChannel channel;
    private final List<String> statements = new ArrayList<>();

    @BeforeEach
    void open() throws Exception {
        db = LendingDatabase.create("catalogue");
        channel = db.channel(List.of());
    }

    @AfterEach
    void close() throws Exception {
        channel.close();
        db.close();
    }

    /** The channel, recording every statement it runs. */
    private DataChannel recording(DataChannel inner) {
        return new DataChannel() {
            @Override
            public boolean serves(String source) throws AgentLostException {
                return inner.serves(source);
            }

            @Override
            public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
                    throws DataException, AgentLostException, InterruptedException {
                statements.add(sql + " /" + maxRows);
                return inner.query(source, sql, params, maxRows, timeout);
            }

            @Override
            public String kind(String source) {
                return inner.kind(source);
            }
        };
    }

    private SchemaCatalogue build(SqlAllowList list, List<RowMask> masks) throws Exception {
        PromptMask mask = PromptMask.of(SecretMask.of(List.of(LendingDatabase.READER_PASSWORD)), List.of(list),
                masks);
        return SchemaCatalogue.build(List.of(list), recording(channel), mask, Duration.ofSeconds(10));
    }

    @Test
    void theCatalogueDescribesTheAllowListWithMaskedSamples() throws Exception {
        SchemaCatalogue c = build(LendingFixture.allowList(SqlDialect.HSQLDB), List.of());
        String text = c.text();
        assertTrue(text.contains("## Source \"lending\" (dialect HSQLDB)"), text);
        assertTrue(text.contains("Table loan_line\n  Key (the tie-breaker the guard orders by): loan_id, line_no"));
        assertTrue(text.contains("  - borrower VARCHAR (personal: you may filter, join, group or order on it, never"
                + " select it)"));
        assertTrue(text.contains("  - role VARCHAR (its values: DESK, SUPERVISOR, VOLUNTEER)"), text);
        assertTrue(text.contains("  - status VARCHAR (its values: OPEN, OVERDUE, RETURNED)"));
        assertTrue(text.contains("  - category VARCHAR (its values: ACCESS, GARDEN, POWER)"));
        assertTrue(text.contains("Procedures you may call, with ? parameters only: open_loan_count"));
        assertTrue(text.contains("At most 200 rows"));
        assertTrue(text.contains("## The answer"));
        for (String hidden : List.of("Dana Reyes", "Ada Moss", "ada@example.org", "Cordless drill", "4006381333931",
                "pin_hash", "audit_log", "x1", LendingDatabase.READER_PASSWORD)) {
            assertFalse(text.contains(hidden), hidden);
        }
        assertTrue(c.estimatedTokens() > 512, "clears the provider's minimum cacheable prefix: "
                + c.estimatedTokens());
        assertTrue(c.part().cacheBreakpoint());
        assertTrue(statements.contains("SELECT DISTINCT s0.role FROM staff_role s0 ORDER BY s0.role LIMIT 21 /21"),
                statements.toString());
        assertEquals(5, statements.size(), "one statement per sample column");
        assertEquals(List.of("DESK", "SUPERVISOR", "VOLUNTEER"), c.sample("lending", "staff_role", "role").values());
        assertEquals(text, build(LendingFixture.allowList(SqlDialect.HSQLDB), List.of()).text(), "deterministic");
    }

    @Test
    void aSampleIsCutAtTwentyValuesAndPassesTheRowMasks() throws Exception {
        for (int i = 0; i < 25; i++) {
            db.exec("INSERT INTO item VALUES ('X-" + i + "', 'B" + i + "', 'Extra " + i + "', 'K" + (10 + i)
                    + "', 1.00, 1.00, TRUE)");
        }
        RowMask hide = (source, column, v) -> "SUPERVISOR".equals(v) ? "S*" : v;
        SchemaCatalogue c = build(LendingFixture.allowList(SqlDialect.HSQLDB), List.of(hide));
        SchemaCatalogue.Sample category = c.sample("lending", "item", "category");
        assertEquals(20, category.values().size());
        assertTrue(category.truncated());
        assertTrue(c.text().contains("  - category VARCHAR (some of its values: ACCESS, GARDEN, K10,"), c.text());
        assertTrue(c.text().contains("its values: DESK, S*, VOLUNTEER"), "the pack's row mask applies first");
    }

    @Test
    void eachDialectHasItsNotes() {
        String db2 = SchemaCatalogue.withoutSamples(List.of(LendingFixture.allowList(SqlDialect.DB2)),
                PromptMask.keysOnly()).text();
        assertTrue(db2.contains("ADD_DAYS(d, n)"), db2);
        assertTrue(db2.contains("- 7 DAYS does not parse"));
        assertTrue(db2.contains("FETCH FIRST n ROWS ONLY"));
        String mssql = SchemaCatalogue.withoutSamples(List.of(LendingFixture.allowList(SqlDialect.SQLSERVER)),
                PromptMask.keysOnly()).text();
        assertTrue(mssql.contains("TOP n"));
        String oracle = SchemaCatalogue.withoutSamples(List.of(LendingFixture.allowList(SqlDialect.ORACLE)),
                PromptMask.keysOnly()).text();
        assertTrue(oracle.contains("Oracle 12c or newer"));
        assertTrue(oracle.contains("Functions allowed beyond the built-in set: NVL, TO_CHAR, LISTAGG"));
    }
}
