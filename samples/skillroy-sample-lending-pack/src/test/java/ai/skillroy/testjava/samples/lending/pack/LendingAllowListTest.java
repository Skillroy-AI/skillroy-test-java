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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedTable;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack's SQL allow-list as phase5-contracts.md 14.1 declares it, without a database (the scenarios module proves
 * it against the embedded one): one list for source {@code lending}, dialect HSQLDB, over the tables the pack's own
 * SQL reads; {@code staff.password} never readable and named among the other columns; {@code staff.name} personal;
 * four sampled columns; the stored tables with their keys; a description for every table and column; and a scenario
 * of the pack may now name {@code llm-sql}.
 */
class LendingAllowListTest {

    private final LendingPack pack = new LendingPack();

    private SqlAllowList list() {
        assertEquals(1, pack.sqlAllowLists().size());
        return pack.sqlAllowLists().get(0);
    }

    @Test
    void oneListForSourceLendingOverTheTablesThePacksSqlReads() {
        SqlAllowList list = list();
        assertSame(list, pack.sqlAllowLists().get(0), "the same list on every call");
        assertEquals("lending", list.source());
        assertEquals(SqlDialect.HSQLDB, list.dialect());
        assertEquals(List.of(), list.procedures(), "no procedure");
        assertEquals(List.of(), list.functions(), "no function beyond the guard's built-in set");
        assertEquals(LendingFingerprinter.TABLES.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList(),
                list.tables().stream().map(AllowedTable::name).toList(),
                "the tables the fingerprint covers: the resolvers' SQL and the stored-row checks");
    }

    @Test
    void theKeysAreThePrimaryKeysAndTheStoredTablesKeys() {
        Map<String, List<String>> keys = new TreeMap<>();
        for (AllowedTable table : list().tables()) {
            keys.put(table.name(), table.key());
        }
        assertEquals(new TreeMap<>(Map.ofEntries(
                Map.entry("staff", List.of("code")),
                Map.entry("staff_role", List.of("staff_code", "role_id")),
                Map.entry("role_permission", List.of("role_id", "permission")),
                Map.entry("role_cap", List.of("role_id", "kind")),
                Map.entry("desk_setting", List.of("name")),
                Map.entry("item", List.of("code")),
                Map.entry("waiver_rule", List.of("id")),
                Map.entry("waiver_rule_item", List.of("rule_id", "item_code")),
                Map.entry("loan", List.of("id")),
                Map.entry("loan_line", List.of("loan_id", "line_no")),
                Map.entry("loan_waiver", List.of("loan_id", "rule_code")),
                Map.entry("loan_authorisation", List.of("loan_id", "kind", "staff_code")))), keys);
        for (StoredTable stored : pack.storedTables()) {
            AllowedTable table = list().table(stored.tableSql());
            assertNotNull(table, stored.name());
            assertEquals(stored.orderBy(), table.key(), stored.name() + ": the stored table's key");
            for (StoredColumn column : stored.columns()) {
                assertNotNull(table.column(column.name()), stored.name() + "." + column.name() + " is readable");
            }
        }
    }

    /** P2 4's rule: a column the pack's row mask or a secret-key pattern marks is never allow-listed. */
    @Test
    void theSecretColumnIsNeverReadableAndIsNamedAmongTheOtherColumns() {
        RowMask mask = pack.rowMasks().get(0);
        List<String> others = new ArrayList<>();
        for (AllowedTable table : list().tables()) {
            for (AllowedColumn column : table.columns()) {
                String where = table.name() + "." + column.name();
                assertFalse(SecretKeys.looksSecret(column.name()), where);
                assertEquals("value", mask.mask("lending", column.name(), "value"), where + " is not masked");
                assertEquals("value", mask.mask("lending", column.name().toUpperCase(Locale.ROOT), "value"), where);
            }
            table.otherColumns().forEach(other -> others.add(table.name() + "." + other));
        }
        assertEquals(List.of("staff.password"), others, "the one column a model may never read");
        assertEquals(SecretKeys.REDACTED, mask.mask("lending", "password", "1111"), "the column the mask hides");
    }

    @Test
    void nameIsPersonalAndFourColumnsAreSampled() {
        List<String> personal = new ArrayList<>();
        List<String> sampled = new ArrayList<>();
        for (AllowedTable table : list().tables()) {
            for (AllowedColumn column : table.columns()) {
                if (column.personal()) {
                    personal.add(table.name() + "." + column.name());
                }
                if (column.sample()) {
                    sampled.add(table.name() + "." + column.name());
                }
            }
        }
        assertEquals(List.of("staff.name"), personal);
        assertEquals(List.of("role_permission.permission", "desk_setting.name", "item.per_day", "waiver_rule.kind"),
                sampled);
    }

    /** What a model reads about the desk: one line per table and per column, and money said to be decimal text. */
    @Test
    void everyTableAndColumnIsDescribedOnOneLine() {
        for (AllowedTable table : list().tables()) {
            assertDescribed(table.name(), table.description());
            for (AllowedColumn column : table.columns()) {
                String where = table.name() + "." + column.name();
                assertDescribed(where, column.description());
                assertFalse(column.type().isBlank(), where);
                if (column.type().equals("DECIMAL(10,2)")) {
                    assertTrue(column.description().contains("money as a decimal string"), where + ": "
                            + column.description());
                }
            }
        }
    }

    private static void assertDescribed(String where, String description) {
        assertNotNull(description, where);
        assertFalse(description.isBlank(), where);
        assertFalse(description.contains("\n"), where + ": one line");
        assertTrue(description.endsWith("."), where + ": a sentence");
    }

    /** phase5-contracts.md 13.1: with an allow-list, the pack's scenarios may name llm-sql; their plans then bind. */
    @Test
    void aScenarioOfThePackMayNowNameLlmSql() throws Exception {
        String yaml = """
                scenario: llm-sql-check
                pack: lending
                resolve:
                  supervisor:
                    query: StaffWithPermission
                    args: { perform: [RETURN_OVERRIDE] }
                    strategy: [llm-sql]
                    credential: { realm: staff, key: code }
                steps:
                  - authorise: { staff: $supervisor }
                """;
        Plan plan = PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "llm-sql-check.yaml", pack);
        assertEquals(Plan.INFERENCE_BIND, plan.inference());
        assertEquals(List.of("llm-sql"), plan.slot("supervisor").query().strategy());
        assertEquals("0.5.0", plan.packVersion());
    }
}
