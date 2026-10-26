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

import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.JdbcDataChannel;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.ContributionException;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedTable;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 5.2 (decision D100): the allow-list checked against its source's schema and the pack's row
 * masks, on spike BS6's lending schema in the embedded database. A complete list passes (the BS6 schema's twin with
 * its allow-list); a column the list names neither as readable nor as another column, a name the table does not have,
 * a table the source cannot read and a readable column a row mask alters are each refused, naming the table and the
 * columns and never a value.
 */
class AllowListCheckTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private LendingDatabase db;
    private JdbcDataChannel channel;

    @BeforeEach
    void open() throws Exception {
        db = LendingDatabase.create("allow-check");
        channel = db.channel(List.of());
    }

    @AfterEach
    void close() throws Exception {
        channel.close();
        db.close();
    }

    @Test
    void theBs6SchemasTwinWithItsCompleteAllowListPasses() {
        SqlAllowList complete = LendingFixture.withOtherColumns(SqlDialect.HSQLDB);
        assertDoesNotThrow(() -> AllowListCheck.check(complete, channel, List.of(), TIMEOUT));
        assertDoesNotThrow(() -> AllowListCheck.rowMasks(complete, List.of((s, c, v) -> v)));
    }

    /** Extra columns on the database table that the allow-list does not enumerate are tolerated. */
    @Test
    void aColumnTheListDoesNotNameIsTolerated() {
        SqlAllowList partial = LendingFixture.allowList(SqlDialect.HSQLDB);
        assertDoesNotThrow(() -> AllowListCheck.check(partial, channel, List.of(), TIMEOUT));
    }

    @Test
    void aNameTheTableDoesNotHaveIsRefused() {
        SqlAllowList extra = replace(LendingFixture.withOtherColumns(SqlDialect.HSQLDB), "staff",
                table -> new AllowedTable("staff", plus(table.columns(), AllowedColumn.of("nickname", "VARCHAR")),
                        table.key(), null, table.otherColumns()));
        ContributionException e = assertThrows(ContributionException.class,
                () -> AllowListCheck.check(extra, channel, List.of(), TIMEOUT));
        assertTrue(e.getMessage().contains("names the table does not have: [nickname]"), e.getMessage());
        SqlAllowList otherExtra = replace(LendingFixture.withOtherColumns(SqlDialect.HSQLDB), "item",
                table -> new AllowedTable("item", table.columns(), table.key(), null, List.of("shelf_note")));
        assertTrue(assertThrows(ContributionException.class, () -> AllowListCheck.check(otherExtra, channel,
                List.of(), TIMEOUT)).getMessage().contains("[shelf_note]"));
    }

    @Test
    void aTableTheSourceCannotReadIsRefused() {
        SqlAllowList.Builder b = SqlAllowList.builder("lending", SqlDialect.HSQLDB);
        for (AllowedTable table : LendingFixture.withOtherColumns(SqlDialect.HSQLDB).tables()) {
            b.table(table);
        }
        b.table("member", List.of("code"), AllowedColumn.of("code", "VARCHAR"));
        ContributionException e = assertThrows(ContributionException.class,
                () -> AllowListCheck.check(b.build(), channel, List.of(), TIMEOUT));
        assertTrue(e.getMessage().contains("names table member, which the source cannot read"), e.getMessage());
    }

    /** P2 4's rule: a column the pack's row mask marks is secret and may not be allow-listed, whatever its name. */
    @Test
    void aReadableColumnARowMaskAltersIsRefused() {
        SqlAllowList complete = LendingFixture.withOtherColumns(SqlDialect.HSQLDB);
        RowMask doorCodes = (source, column, value) -> column.equalsIgnoreCase("email") ? SecretKeys.REDACTED
                : value;
        ContributionException load = assertThrows(ContributionException.class,
                () -> AllowListCheck.rowMasks(complete, List.of((s, c, v) -> v, doorCodes)));
        assertTrue(load.getMessage().contains("column staff.email, which row mask 2 of the pack alters"),
                load.getMessage());
        // A mask that reacts only to the name in the source's case (upper case here) and only to one value type.
        RowMask upperOnly = (source, column, value) -> column.equals("ACTIVE") && value instanceof Boolean ? null
                : value;
        ContributionException full = assertThrows(ContributionException.class,
                () -> AllowListCheck.check(complete, channel, List.of(upperOnly), TIMEOUT));
        assertTrue(full.getMessage().contains("column staff.active"), full.getMessage());
        RowMask failing = (source, column, value) -> {
            if (column.equalsIgnoreCase("barcode")) {
                throw new IllegalStateException("cannot mask");
            }
            return value;
        };
        assertTrue(assertThrows(ContributionException.class, () -> AllowListCheck.rowMasks(complete,
                List.of(failing))).getMessage().contains("item.barcode, which row mask 1 of the pack fails on"));
    }

    @Test
    void aPositionalSourceIsRefusedWithAHintToAnswerColumns() throws Exception {
        // phase8-contracts.md 4.4 (gap 117): a source that reports no column, or only c1..cn, is still refused (never
        // a count-only pass), and the message says what its provider should do.
        SqlAllowList complete = LendingFixture.withOtherColumns(SqlDialect.HSQLDB);
        for (List<String> reported : List.of(List.<String>of(), List.of("c1", "c2", "c3"))) {
            DataChannel positional = new DataChannel() {
                @Override
                public boolean serves(String source) {
                    return true;
                }

                @Override
                public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout) {
                    List<Rows.Column> columns = new ArrayList<>();
                    for (String name : reported) {
                        columns.add(new Rows.Column(name, null));
                    }
                    return Rows.of(columns, List.of(), false);
                }

                @Override
                public String kind(String source) {
                    return "agent";
                }
            };
            ContributionException e = assertThrows(ContributionException.class,
                    () -> AllowListCheck.check(complete, positional, List.of(), TIMEOUT), reported.toString());
            assertTrue(e.getMessage().contains("source lending reports no column names for table "),
                    e.getMessage());
            assertTrue(e.getMessage().endsWith("(a provider that answers by position); its DataSourceProvider should"
                    + " answer columns(table) (gap 117)"), e.getMessage());
        }
        // A named mismatch carries no hint.
        SqlAllowList unknownCol = replace(LendingFixture.withOtherColumns(SqlDialect.HSQLDB), "staff",
                table -> new AllowedTable("staff", plus(table.columns(), AllowedColumn.of("nickname", "VARCHAR")),
                        table.key(), null, table.otherColumns()));
        ContributionException named = assertThrows(ContributionException.class, () -> AllowListCheck.check(
                unknownCol, channel, List.of(), TIMEOUT));
        assertFalse(named.getMessage().contains("gap 117"), named.getMessage());
    }

    @Test
    void aListThatNamesProceduresIsWordedAsRetired() {
        assertEquals(null, AllowListCheck.proceduresRefusal(LendingFixture.allowList(SqlDialect.HSQLDB)));
        SqlAllowList.Builder b = SqlAllowList.builder("lending", SqlDialect.HSQLDB);
        for (AllowedTable table : LendingFixture.allowList(SqlDialect.HSQLDB).tables()) {
            b.table(table);
        }
        @SuppressWarnings("deprecation")
        SqlAllowList.Builder withProcedure = b.procedure("open_loan_count");
        assertEquals("the SQL allow-list of source lending names procedures [open_loan_count]; no data channel runs a"
                + " procedure call (gap 119; the field is reserved for a prepared read, gap 13)",
                AllowListCheck.proceduresRefusal(withProcedure.build()));
    }

    private static List<AllowedColumn> plus(List<AllowedColumn> columns, AllowedColumn more) {
        List<AllowedColumn> out = new ArrayList<>(columns);
        out.add(more);
        return out;
    }

    private static SqlAllowList replace(SqlAllowList list, String name,
                                        java.util.function.UnaryOperator<AllowedTable> change) {
        SqlAllowList.Builder b = SqlAllowList.builder(list.source(), list.dialect());
        for (AllowedTable table : list.tables()) {
            b.table(table.name().equals(name) ? change.apply(table) : table);
        }
        for (String f : list.functions()) {
            b.function(f);
        }
        return b.build();
    }
}
