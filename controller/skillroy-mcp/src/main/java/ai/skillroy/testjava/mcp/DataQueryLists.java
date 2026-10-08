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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedTable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The allow-list {@code data_query} guards a client's statement with (phase5-contracts.md 9.3, decision D99). The
 * tier (b) rule for a personal column ("filter, never select") assumes that nobody sees the rows; {@code data_query}
 * returns them to the client, so a filter on a personal value ({@code LIKE 'A%'}, {@code SUBSTRING}, {@code LENGTH},
 * an order, a comparison) would rebuild it row by row. The server's guard therefore reads a copy of the pack's list in
 * which every personal column, and every column one of the pack's row masks alters, is one of the table's
 * {@code otherColumns}: a statement may not name it anywhere ({@code SqlGuard}'s {@code COLUMN}). A table whose key
 * holds such a column is left out (the guard orders by the key, which would answer by position), and so is a table
 * with no readable column left; a list with no table left is null (the source is not readable here).
 *
 * <p>A row mask is a function of the source, the column and the value, so the probe is the pack's own test's: each
 * readable column, under its declared name and its upper- and lower-case forms, with a text, a whole number, a decimal
 * and a boolean sentinel; a mask that changes any of them, or throws, marks the column.
 *
 * <p>Stateless; safe from any thread if the masks are.
 */
final class DataQueryLists {

    /** The values a probe passes through each mask. */
    private static final List<Object> SENTINELS = List.of("skillroy-probe-value", 7_357L, 7_357.25d, Boolean.TRUE);

    private DataQueryLists() {
    }

    /**
     * The copy of {@code list} for {@code data_query}, or null when no table of it stays readable.
     *
     * @param masks the pack's row masks (none: an empty list)
     */
    static SqlAllowList forDataQuery(SqlAllowList list, List<RowMask> masks) {
        List<AllowedTable> tables = new ArrayList<>();
        for (AllowedTable table : list.tables()) {
            List<AllowedColumn> readable = new ArrayList<>();
            Set<String> hidden = new LinkedHashSet<>(table.otherColumns());
            for (AllowedColumn column : table.columns()) {
                if (column.personal() || masked(list.source(), column.name(), masks)) {
                    hidden.add(column.name());
                } else {
                    readable.add(column);
                }
            }
            boolean keyHidden = false;
            for (String key : table.key()) {
                keyHidden |= hidden.stream().anyMatch(h -> h.equalsIgnoreCase(key));
            }
            if (readable.isEmpty() || keyHidden) {
                continue;
            }
            tables.add(new AllowedTable(table.name(), readable, table.key(), table.description(),
                    new ArrayList<>(hidden)));
        }
        if (tables.isEmpty()) {
            return null;
        }
        return new SqlAllowList(list.source(), list.dialect(), tables, list.procedures(), list.functions());
    }

    /** True when a mask alters (or throws on) a sentinel value of this column under any of its spellings. */
    static boolean masked(String source, String column, List<RowMask> masks) {
        if (masks == null || masks.isEmpty()) {
            return false;
        }
        Set<String> labels = new LinkedHashSet<>(List.of(column, column.toUpperCase(Locale.ROOT),
                column.toLowerCase(Locale.ROOT)));
        for (RowMask mask : masks) {
            if (mask == null) {
                continue;
            }
            for (String label : labels) {
                for (Object sentinel : SENTINELS) {
                    try {
                        if (!Objects.equals(sentinel, mask.mask(source, label, sentinel))) {
                            return true;
                        }
                    } catch (RuntimeException e) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
