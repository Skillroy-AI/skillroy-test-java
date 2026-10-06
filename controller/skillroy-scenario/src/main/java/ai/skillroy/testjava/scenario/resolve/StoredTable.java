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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A table a scenario may check with {@code db.<name>} (phase2-contracts.md 7.4). The compiler generates the check's
 * SQL from it at compile time and writes it into the plan (decision D17): {@code SELECT <every column's sql> FROM
 * <tableSql> WHERE <c.sql> = <c.paramSql> AND … ORDER BY <orderBy columns' sql>}. A variant pack with other
 * physical names keeps the scenarios and compiles its own plans.
 *
 * <p>Built through {@link #of}; the canonical constructor is not API. Immutable; safe to share between threads.
 *
 * @param name what scenarios write after {@code db.}, {@code [A-Za-z_][A-Za-z0-9_]*}
 * @param source the data source id the table is read from
 * @param tableSql the pack's committed SQL text for the table
 * @param columns the columns, in the order the statement selects them (results are matched by position)
 * @param orderBy column names that determine the row order completely
 */
public record StoredTable(String name, String source, String tableSql, List<StoredColumn> columns,
                          List<String> orderBy) {

    /**
     * @throws IllegalArgumentException if a name is invalid, a column name repeats, there is no column, or
     *     {@code orderBy} is empty or names an undeclared column
     */
    public StoredTable {
        if (name == null || !StoredColumn.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a stored table name must match [A-Za-z_][A-Za-z0-9_]*, found "
                    + name);
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("table " + name + ": source must not be blank");
        }
        if (tableSql == null || tableSql.isBlank()) {
            throw new IllegalArgumentException("table " + name + ": tableSql must not be blank");
        }
        columns = List.copyOf(columns);
        orderBy = List.copyOf(orderBy);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("table " + name + " needs at least one column");
        }
        Set<String> names = new HashSet<>();
        for (StoredColumn column : columns) {
            if (!names.add(column.name())) {
                throw new IllegalArgumentException("table " + name + ": column " + column.name() + " repeats");
            }
        }
        if (orderBy.isEmpty()) {
            throw new IllegalArgumentException("table " + name + ": orderBy must name the columns that determine"
                    + " the row order");
        }
        for (String column : orderBy) {
            if (!names.contains(column)) {
                throw new IllegalArgumentException("table " + name + ": orderBy names " + column
                        + ", which is not a declared column");
            }
        }
    }

    /** A stored table. */
    public static StoredTable of(String name, String source, String tableSql, List<StoredColumn> columns,
                                 List<String> orderBy) {
        return new StoredTable(name, source, tableSql, columns, orderBy);
    }

    /** The column with this name, or null. */
    public StoredColumn column(String columnName) {
        for (StoredColumn column : columns) {
            if (column.name().equals(columnName)) {
                return column;
            }
        }
        return null;
    }
}
