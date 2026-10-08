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

package ai.skillroy.testjava.scenario.resolve;

import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 5 (phase5-contracts.md 5.2): the allow-list a pack declares per source, and its builder's validation. */
class SqlAllowListTest {

    private static SqlAllowList.Builder lending() {
        return SqlAllowList.builder("lending", SqlDialect.HSQLDB)
                .table("staff", List.of("code"), "desk staff", List.of(AllowedColumn.of("code", "VARCHAR(10)"),
                        AllowedColumn.personal("name", "VARCHAR(60)"), AllowedColumn.sampled("active", "BOOLEAN")
                                .withDescription("whether the member may sign on")))
                .table("item", List.of("code"), AllowedColumn.of("code", "VARCHAR(20)"),
                        AllowedColumn.sampled("per_day", "BOOLEAN"), AllowedColumn.of("deposit", "DECIMAL(10,2)"));
    }

    private static String refused(Runnable build) {
        return assertThrows(IllegalArgumentException.class, build::run).getMessage();
    }

    @Test
    void theBuilderMakesTheListOfTheContract() {
        SqlAllowList list = lending().procedure("refresh_totals").function("coalesce2").build();
        assertEquals("lending", list.source());
        assertEquals(SqlDialect.HSQLDB, list.dialect());
        assertEquals(List.of("staff", "item"), list.tables().stream().map(AllowedTable::name).toList());
        AllowedTable staff = list.table("STAFF");
        assertSame(list.tables().get(0), staff, "tables are found regardless of case, as SQL compares names");
        assertEquals(List.of("code"), staff.key());
        assertEquals("desk staff", staff.description());
        AllowedColumn name = staff.column("Name");
        assertTrue(name.personal());
        assertFalse(name.sample());
        assertTrue(staff.column("active").sample());
        assertEquals("whether the member may sign on", staff.column("active").description());
        assertNull(list.table("loan"));
        assertNull(staff.column("password"));
        assertEquals(List.of("refresh_totals"), list.procedures());
        assertEquals(List.of("coalesce2"), list.functions());
        assertEquals(List.of(), lending().build().procedures(), "no procedure: no CALL");
        assertEquals(8, SqlDialect.values().length);
    }

    @Test
    void theListsAreCopies() {
        List<AllowedColumn> columns = new ArrayList<>(List.of(AllowedColumn.of("code", "VARCHAR")));
        AllowedTable table = new AllowedTable("item", columns, List.of("code"), null);
        columns.add(AllowedColumn.of("title", "VARCHAR"));
        assertEquals(1, table.columns().size());
        assertThrows(UnsupportedOperationException.class, () -> table.columns().add(columns.get(1)));
        SqlAllowList list = new SqlAllowList("lending", SqlDialect.GENERIC, List.of(table), null, null);
        assertEquals(List.of(), list.procedures());
        assertEquals(List.of(), list.functions());
        assertThrows(UnsupportedOperationException.class, () -> list.tables().clear());
    }

    @Test
    void namesAreCheckedAndDuplicatesRefused() {
        assertTrue(refused(() -> SqlAllowList.builder("", SqlDialect.HSQLDB).build()).contains("data source id"));
        assertTrue(refused(() -> SqlAllowList.builder("-lending", SqlDialect.HSQLDB).build())
                .contains("data source id"));
        assertTrue(refused(() -> SqlAllowList.builder("lending", null).table("item", List.of("code"),
                AllowedColumn.of("code", "VARCHAR")).build()).contains("needs a dialect"));
        assertTrue(refused(() -> SqlAllowList.builder("lending", SqlDialect.HSQLDB).build()).contains("no table"));
        assertTrue(refused(() -> lending().table("ITEM", List.of("code"), AllowedColumn.of("code", "VARCHAR")))
                .contains("names table ITEM twice"), "a duplicate table, in another case");
        assertTrue(refused(() -> new SqlAllowList("lending", SqlDialect.HSQLDB, List.of(
                new AllowedTable("item", List.of(AllowedColumn.of("code", "V")), List.of("code"), null),
                new AllowedTable("Item", List.of(AllowedColumn.of("code", "V")), List.of("code"), null)), null, null))
                .contains("twice"), "the record checks what the builder checks");
        assertTrue(refused(() -> AllowedColumn.of("1code", "VARCHAR")).contains("SQL identifier"));
        assertTrue(refused(() -> AllowedColumn.of("code;drop", "VARCHAR")).contains("SQL identifier"));
        assertTrue(refused(() -> new AllowedTable("item x", List.of(AllowedColumn.of("code", "V")), List.of("code"),
                null)).contains("SQL identifier"));
        assertEquals("app.item", new AllowedTable("app.item", List.of(AllowedColumn.of("code", "V")),
                List.of("code"), null).name(), "a qualified table name is a name");
        assertTrue(refused(() -> new AllowedTable("item", List.of(AllowedColumn.of("code", "V"),
                AllowedColumn.of("CODE", "V")), List.of("code"), null)).contains("names column CODE twice"));
        assertTrue(refused(() -> new AllowedTable("item", List.of(), List.of("code"), null)).contains("no column"));
        assertTrue(refused(() -> AllowedColumn.of("code", " ")).contains("needs its type"));
        assertTrue(refused(() -> lending().procedure("refresh").procedure("REFRESH")).contains("twice"));
        assertTrue(refused(() -> lending().function("f(x)")).contains("SQL identifier"));
        SqlAllowList.Builder builder = lending();
        assertThrows(IllegalArgumentException.class, () -> builder.function("bad name"));
        assertEquals(List.of(), builder.build().functions(), "a refused name is not kept");
    }

    @Test
    void theKeyIsColumnsOfTheTable() {
        assertTrue(refused(() -> new AllowedTable("item", List.of(AllowedColumn.of("code", "V")), List.of(), null))
                .contains("needs a key"));
        assertTrue(refused(() -> new AllowedTable("item", List.of(AllowedColumn.of("code", "V")), List.of("id"),
                null)).contains("\"id\", which is not one of its columns"));
        assertTrue(refused(() -> new AllowedTable("item", List.of(AllowedColumn.of("code", "V")),
                List.of("code", "Code"), null)).contains("names column Code twice"));
        assertEquals(List.of("CODE"), new AllowedTable("item", List.of(AllowedColumn.of("code", "V")),
                List.of("CODE"), null).key(), "a key column in another case is that column");
    }

    /** Spike BS6: a table names its other columns, never readable, so that the guard resolves every reference. */
    @Test
    void aTableNamesItsOtherColumnsOnceAndApartFromTheReadableOnes() {
        SqlAllowList list = SqlAllowList.builder("lending", SqlDialect.HSQLDB)
                .table("staff", List.of("code"), "desk staff", List.of(AllowedColumn.of("code", "VARCHAR(10)")),
                        List.of("password", "name"))
                .build();
        assertEquals(List.of("password", "name"), list.table("staff").otherColumns(),
                "a secret column may be named as one that is never readable");
        assertEquals(List.of(), new AllowedTable("item", List.of(AllowedColumn.of("code", "V")), List.of("code"),
                null).otherColumns(), "the four-argument form names none");
        assertEquals(List.of(), new AllowedTable("item", List.of(AllowedColumn.of("code", "V")), List.of("code"),
                null, null).otherColumns(), "null is none");
        assertTrue(refused(() -> new AllowedTable("item", List.of(AllowedColumn.of("code", "V")), List.of("code"),
                null, List.of("CODE"))).contains("both as readable and as another column"));
        assertTrue(refused(() -> new AllowedTable("item", List.of(AllowedColumn.of("code", "V")), List.of("code"),
                null, List.of("title", "Title"))).contains("names the other column Title twice"));
        assertTrue(refused(() -> new AllowedTable("item", List.of(AllowedColumn.of("code", "V")), List.of("code"),
                null, List.of("a b"))).contains("not an SQL identifier"));
        List<String> others = new ArrayList<>(List.of("title"));
        AllowedTable table = new AllowedTable("item", List.of(AllowedColumn.of("code", "V")), List.of("code"), null,
                others);
        others.add("price");
        assertEquals(List.of("title"), table.otherColumns(), "a copy");
    }

    @Test
    void aSecretColumnIsNeverAllowListedAndAPersonalOneNeverSampled() {
        for (String secret : List.of("password", "staff_pwd", "api_key", "Token", "licence_no")) {
            assertTrue(refused(() -> AllowedColumn.of(secret, "VARCHAR")).contains("looks secret"), secret);
        }
        assertEquals("key", AllowedColumn.sampled("key", "VARCHAR").name(), "a settings key is not a secret");
        assertTrue(refused(() -> new AllowedColumn("name", "VARCHAR", true, true, null))
                .contains("personal and may not be sampled"));
    }
}
