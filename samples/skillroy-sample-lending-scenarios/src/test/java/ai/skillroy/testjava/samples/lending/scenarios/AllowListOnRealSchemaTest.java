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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Seed;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedTable;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 acceptance 15.1 item 5, the sample's part (phase5-contracts.md 5.2, 14.1): the lending pack's SQL
 * allow-list against the real schema, read from {@code information_schema.columns} of the sample's own database
 * ({@link LendingDb}, created in this JVM as the desk creates it). Every table the list names exists; every column it
 * names, readable or other, exists in its table, and together they are exactly the table's columns, the readable ones
 * in the schema's order; every readable column has the schema's type; every key column is NOT NULL; no readable column
 * is secret, and the secret one, {@code staff.password} (the column the pack's row mask hides), is among the other
 * columns. The tables a model may not read at all are named here too. The guard's accept corpus over these tables runs
 * on the same database in {@link LendingAcceptCorpusTest}.
 */
class AllowListOnRealSchemaTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    /** One column as {@code information_schema.columns} reports it. */
    private record Column(String name, String type, boolean nullable) {
    }

    /** The columns of every table of schema PUBLIC, in their order, by upper-case table name. */
    private static Map<String, List<Column>> columns(Connection connection) throws SQLException {
        Map<String, List<Column>> tables = new LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name, column_name, data_type, character_maximum_length,"
                     + " numeric_precision, numeric_scale, is_nullable FROM information_schema.columns"
                     + " WHERE table_schema = 'PUBLIC' ORDER BY table_name, ordinal_position")) {
            while (rs.next()) {
                String type = switch (rs.getString(3)) {
                    case "CHARACTER VARYING" -> "VARCHAR(" + rs.getLong(4) + ")";
                    case "DECIMAL", "NUMERIC" -> "DECIMAL(" + rs.getLong(5) + "," + rs.getLong(6) + ")";
                    default -> rs.getString(3);
                };
                tables.computeIfAbsent(rs.getString(1), t -> new ArrayList<>()).add(new Column(rs.getString(2),
                        type, "YES".equals(rs.getString(7))));
            }
        }
        return tables;
    }

    /** The primary-key columns of a table of schema PUBLIC, upper case; empty when it has none. */
    private static Set<String> primaryKey(Connection connection, String table) throws SQLException {
        Set<String> columns = new TreeSet<>();
        try (ResultSet rs = connection.getMetaData().getPrimaryKeys(null, "PUBLIC", table)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private static SqlAllowList allowList() {
        List<SqlAllowList> lists = Runs.PACK.sqlAllowLists();
        assertEquals(1, lists.size(), "one list: the pack reads one source");
        return lists.get(0);
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    @Test
    void theAllowListNamesExactlyTheColumnsOfTheRealSchema() throws Exception {
        SqlAllowList list = allowList();
        assertEquals("lending", list.source());
        try (LendingDb db = LendingDb.create("allowlist" + DATABASES.incrementAndGet(), Seed.A, LocalDate.now())) {
            Map<String, List<Column>> schema = columns(db.connection());
            Set<String> named = new TreeSet<>();
            for (AllowedTable table : list.tables()) {
                String where = table.name();
                List<Column> actual = schema.get(upper(table.name()));
                assertNotNull(actual, "table " + where + " exists");
                named.add(upper(table.name()));
                Map<String, Column> byName = new LinkedHashMap<>();
                actual.forEach(column -> byName.put(column.name(), column));

                Set<String> listed = new LinkedHashSet<>();
                table.columns().forEach(column -> listed.add(upper(column.name())));
                table.otherColumns().forEach(other -> assertTrue(listed.add(upper(other)), where + "." + other
                        + " is named once"));
                assertEquals(new TreeSet<>(byName.keySet()), new TreeSet<>(listed), where + ": the readable and the"
                        + " other columns together are exactly the table's columns");
                List<String> readableInSchemaOrder = actual.stream().map(Column::name).filter(name -> table.column(
                        name) != null).toList();
                assertEquals(readableInSchemaOrder, table.columns().stream().map(c -> upper(c.name())).toList(),
                        where + ": the readable columns in the schema's order");

                for (AllowedColumn column : table.columns()) {
                    Column real = byName.get(upper(column.name()));
                    assertEquals(real.type(), column.type(), where + "." + column.name() + ": the schema's type");
                }
                Set<String> primaryKey = primaryKey(db.connection(), upper(table.name()));
                for (String key : table.key()) {
                    // A primary-key column is NOT NULL by its constraint, though the schema view reports what the
                    // column itself declares (HSQLDB says YES for a primary-key column without NOT NULL).
                    assertTrue(!byName.get(upper(key)).nullable() || primaryKey.contains(upper(key)), where + "."
                            + key + ": a key column is NOT NULL");
                }
            }
            Set<String> unnamed = new TreeSet<>(schema.keySet());
            unnamed.removeAll(named);
            assertEquals(Set.of("LOAN_DEPOSIT", "ROLE"), unnamed, "the tables a model may not read at all: the"
                    + " pack's own SQL and stored-row checks do not read them either");
        }
    }

    /** P2 4's rule (phase5-contracts.md 5.2): a secret column is never allow-listed; the sample's is password. */
    @Test
    void noReadableColumnIsSecretAndThePasswordIsAnOtherColumn() throws Exception {
        SqlAllowList list = allowList();
        RowMask mask = Runs.PACK.rowMasks().get(0);
        List<String> others = new ArrayList<>();
        for (AllowedTable table : list.tables()) {
            for (AllowedColumn column : table.columns()) {
                String where = table.name() + "." + column.name();
                assertFalse(SecretKeys.looksSecret(column.name()), where);
                for (String label : List.of(column.name(), upper(column.name()))) {
                    assertEquals("x", mask.mask("lending", label, "x"), where + ": the row mask leaves it as it is");
                }
            }
            table.otherColumns().forEach(other -> others.add(table.name() + "." + other));
        }
        assertEquals(List.of("staff.password"), others);
        assertEquals(SecretKeys.REDACTED, mask.mask("lending", "PASSWORD", "x"), "the column the row mask hides");
        try (LendingDb db = LendingDb.create("allowlist" + DATABASES.incrementAndGet(), Seed.B, LocalDate.now())) {
            List<Column> staff = columns(db.connection()).get("STAFF");
            assertTrue(staff.stream().anyMatch(c -> c.name().equals("PASSWORD")), "seed B has the same schema");
            assertEquals(List.of("CODE", "NAME", "PASSWORD", "ACTIVE", "AUTO_SIGN_ON"), staff.stream()
                    .map(Column::name).toList());
        }
    }
}
