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

import ai.skillroy.testjava.protocol.SecretKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a model may read from one data source, and how the controller's guard checks a statement a model wrote for it
 * (phase5-contracts.md 5.2). A pack declares one per data source in {@code DomainPack.sqlAllowLists()}; a pack that
 * declares none has no tier (b) resolution, and a scenario of it may not name the strategy {@code llm-sql}.
 *
 * <p>This type only describes and validates names: the guard, the prompt catalogue and the resolver that read it live
 * in the inference module. Validation here: the source is a data source id ({@code [A-Za-z0-9][A-Za-z0-9_.-]*}), the
 * dialect is given, there is at least one table, every table and column name is an SQL identifier (a table may be
 * qualified, {@code schema.table}), no table, column, key column, procedure or function is named twice (compared
 * without regard to case, as SQL compares unquoted identifiers), every key column is a column of its table, every
 * table has a key, a column's type is not blank, a {@code personal} column is not also a {@code sample} column, no
 * column whose name looks secret ({@code SecretKeys.looksSecret}) is allow-listed at all (a secret column is never
 * readable by a model), and a table's {@code otherColumns} (its columns that are never readable, named so that the
 * guard resolves every column reference; spike BS6) are identifiers named once and not among its readable columns.
 * Each violation is an {@link IllegalArgumentException} naming it.
 *
 * <p>Immutable (lists are copied); safe to share between threads.
 *
 * @param source the {@code DataChannel} source id the list is for
 * @param dialect how statements on the source are parsed and written
 * @param tables the tables a statement may read, in declaration order
 * @param procedures procedures a statement may call ({@code CALL p(?, …)}); empty: no {@code CALL}
 * @param functions function names allowed beyond the guard's built-in set; empty: none
 */
public record SqlAllowList(String source, SqlDialect dialect, List<AllowedTable> tables, List<String> procedures,
                           List<String> functions) {

    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final Pattern QUALIFIED = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?");

    /**
     * Copies the lists (null procedures or functions are empty) and validates the whole list.
     *
     * @throws IllegalArgumentException as the class description says
     */
    public SqlAllowList {
        if (source == null || !SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("an SQL allow-list needs a data source id matching"
                    + " [A-Za-z0-9][A-Za-z0-9_.-]*, found " + quoted(source));
        }
        if (dialect == null) {
            throw new IllegalArgumentException("the SQL allow-list of source " + source + " needs a dialect");
        }
        if (tables == null || tables.isEmpty()) {
            throw new IllegalArgumentException("the SQL allow-list of source " + source + " names no table");
        }
        Set<String> seen = new HashSet<>();
        for (AllowedTable table : tables) {
            if (table == null) {
                throw new IllegalArgumentException("the SQL allow-list of source " + source + " holds a null table");
            }
            if (!seen.add(folded(table.name()))) {
                throw new IllegalArgumentException("the SQL allow-list of source " + source + " names table "
                        + table.name() + " twice");
            }
        }
        tables = List.copyOf(tables);
        procedures = names(procedures, "procedure", source, QUALIFIED);
        functions = names(functions, "function", source, IDENTIFIER);
    }

    /** The table of this name (compared without regard to case), or null. */
    public AllowedTable table(String name) {
        if (name == null) {
            return null;
        }
        for (AllowedTable table : tables) {
            if (table.name().equalsIgnoreCase(name)) {
                return table;
            }
        }
        return null;
    }

    /** A builder for the allow-list of {@code source}; see {@link Builder}. */
    public static Builder builder(String source, SqlDialect dialect) {
        return new Builder(source, dialect);
    }

    /**
     * One table a statement may read.
     *
     * @param name the table name, an SQL identifier, optionally qualified ({@code schema.table})
     * @param columns the columns a statement may name, at least one, in declaration order
     * @param key the tie-breaker columns, at least one, each a column of this table: the guard completes a
     *     statement's {@code ORDER BY} with them, so that a model's answer comes in a deterministic order
     * @param description what the table holds, for the prompt catalogue; may be null
     * @param otherColumns every other column of the table, by name: never readable, named so that the guard resolves
     *     every column reference (an unqualified correlated name cannot bind to a hidden column); may be empty (null
     *     is empty); a name that is also in {@code columns}, or one named twice, is refused
     */
    public record AllowedTable(String name, List<AllowedColumn> columns, List<String> key, String description,
                               List<String> otherColumns) {

        /**
         * Copies the lists and validates them.
         *
         * @throws IllegalArgumentException as {@link SqlAllowList} describes
         */
        public AllowedTable {
            if (name == null || !QUALIFIED.matcher(name).matches()) {
                throw new IllegalArgumentException("an allow-listed table needs an SQL identifier as its name, found "
                        + quoted(name));
            }
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("allow-listed table " + name + " names no column");
            }
            Set<String> seen = new HashSet<>();
            for (AllowedColumn column : columns) {
                if (column == null) {
                    throw new IllegalArgumentException("allow-listed table " + name + " holds a null column");
                }
                if (!seen.add(folded(column.name()))) {
                    throw new IllegalArgumentException("allow-listed table " + name + " names column "
                            + column.name() + " twice");
                }
            }
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("allow-listed table " + name + " needs a key (the tie-breaker"
                        + " columns that make a model's answer come in a deterministic order)");
            }
            Set<String> keySeen = new HashSet<>();
            for (String column : key) {
                if (column == null || !seen.contains(folded(column))) {
                    throw new IllegalArgumentException("the key of allow-listed table " + name + " names "
                            + quoted(column) + ", which is not one of its columns");
                }
                if (!keySeen.add(folded(column))) {
                    throw new IllegalArgumentException("the key of allow-listed table " + name + " names column "
                            + column + " twice");
                }
            }
            Set<String> others = new HashSet<>();
            for (String other : otherColumns == null ? List.<String>of() : otherColumns) {
                if (other == null || !IDENTIFIER.matcher(other).matches()) {
                    throw new IllegalArgumentException("allow-listed table " + name + " names another column that is"
                            + " not an SQL identifier: " + quoted(other));
                }
                if (seen.contains(folded(other))) {
                    throw new IllegalArgumentException("allow-listed table " + name + " names column " + other
                            + " both as readable and as another column");
                }
                if (!others.add(folded(other))) {
                    throw new IllegalArgumentException("allow-listed table " + name + " names the other column "
                            + other + " twice");
                }
            }
            columns = List.copyOf(columns);
            key = List.copyOf(key);
            otherColumns = otherColumns == null ? List.of() : List.copyOf(otherColumns);
        }

        /** A table without other columns named (the guard then knows only the readable ones). */
        public AllowedTable(String name, List<AllowedColumn> columns, List<String> key, String description) {
            this(name, columns, key, description, List.of());
        }

        /** The column of this name (compared without regard to case), or null. */
        public AllowedColumn column(String columnName) {
            if (columnName == null) {
                return null;
            }
            for (AllowedColumn column : columns) {
                if (column.name().equalsIgnoreCase(columnName)) {
                    return column;
                }
            }
            return null;
        }
    }

    /**
     * One column a statement may name.
     *
     * @param name the column name, an SQL identifier whose name does not look secret
     * @param type the column's type as the catalogue names it ({@code VARCHAR(20)}, {@code DECIMAL(10,2)}); not blank
     * @param personal true for a column that holds personal data: a statement may filter, join, group or order on it,
     *     but it is never selected into a candidate value and never sampled
     * @param sample true for a small, enum-like column whose distinct values (masked) go into the prompt catalogue
     * @param description what the column holds, for the prompt catalogue; may be null
     */
    public record AllowedColumn(String name, String type, boolean personal, boolean sample, String description) {

        /**
         * @throws IllegalArgumentException if the name is not an SQL identifier or looks secret, the type is blank,
         *     or the column is both personal and sampled
         */
        public AllowedColumn {
            if (name == null || !IDENTIFIER.matcher(name).matches()) {
                throw new IllegalArgumentException("an allow-listed column needs an SQL identifier as its name, found "
                        + quoted(name));
            }
            if (SecretKeys.looksSecret(name)) {
                throw new IllegalArgumentException("column " + name + " looks secret and may not be allow-listed: a"
                        + " model never reads a secret column");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("allow-listed column " + name + " needs its type");
            }
            if (personal && sample) {
                throw new IllegalArgumentException("allow-listed column " + name + " is personal and may not be"
                        + " sampled into the prompt catalogue");
            }
        }

        /** A plain column: selectable, not sampled, no description. */
        public static AllowedColumn of(String name, String type) {
            return new AllowedColumn(name, type, false, false, null);
        }

        /** A personal column: usable in {@code WHERE}, {@code JOIN}, {@code GROUP BY} and {@code ORDER BY} only. */
        public static AllowedColumn personal(String name, String type) {
            return new AllowedColumn(name, type, true, false, null);
        }

        /** A sampled column: its distinct values (masked) go into the prompt catalogue. */
        public static AllowedColumn sampled(String name, String type) {
            return new AllowedColumn(name, type, false, true, null);
        }

        /** This column with a description for the prompt catalogue. */
        public AllowedColumn withDescription(String text) {
            return new AllowedColumn(name, type, personal, sample, text);
        }
    }

    /**
     * Builds an allow-list table by table. Each method validates what it is given at once (a bad name or a duplicate
     * fails at the line that adds it); {@link #build()} validates the whole list.
     *
     * <p>Not thread-safe; use one builder on one thread.
     */
    public static final class Builder {

        private final String source;
        private final SqlDialect dialect;
        private final List<AllowedTable> tables = new ArrayList<>();
        private final List<String> procedures = new ArrayList<>();
        private final List<String> functions = new ArrayList<>();

        private Builder(String source, SqlDialect dialect) {
            this.source = source;
            this.dialect = dialect;
        }

        /**
         * Adds a table without other columns named.
         *
         * @param name the table name
         * @param key the tie-breaker columns
         * @param description what the table holds, or null
         * @param columns the columns
         * @throws IllegalArgumentException if the table is invalid or already added
         */
        public Builder table(String name, List<String> key, String description, List<AllowedColumn> columns) {
            return table(new AllowedTable(name, columns, key, description));
        }

        /**
         * Adds a table and names its other columns, which are never readable.
         *
         * @param otherColumns every other column of the table, by name
         * @throws IllegalArgumentException if the table is invalid or already added
         */
        public Builder table(String name, List<String> key, String description, List<AllowedColumn> columns,
                             List<String> otherColumns) {
            return table(new AllowedTable(name, columns, key, description, otherColumns));
        }

        /**
         * Adds a table without a description.
         *
         * @throws IllegalArgumentException if the table is invalid or already added
         */
        public Builder table(String name, List<String> key, AllowedColumn... columns) {
            return table(name, key, null, columns == null ? null : List.of(columns));
        }

        /**
         * Adds a table built elsewhere.
         *
         * @throws IllegalArgumentException if it is null or a table of its name was already added
         */
        public Builder table(AllowedTable table) {
            Objects.requireNonNull(table, "table");
            for (AllowedTable added : tables) {
                if (added.name().equalsIgnoreCase(table.name())) {
                    throw new IllegalArgumentException("the SQL allow-list of source " + source + " names table "
                            + table.name() + " twice");
                }
            }
            tables.add(table);
            return this;
        }

        /**
         * Allows {@code CALL name(?, …)} with parameters only.
         *
         * @throws IllegalArgumentException if the name is not an SQL identifier or was already added
         */
        public Builder procedure(String name) {
            List<String> next = new ArrayList<>(procedures);
            next.add(name);
            names(next, "procedure", source, QUALIFIED);
            procedures.add(name);
            return this;
        }

        /**
         * Allows a function beyond the guard's built-in set.
         *
         * @throws IllegalArgumentException if the name is not an SQL identifier or was already added
         */
        public Builder function(String name) {
            List<String> next = new ArrayList<>(functions);
            next.add(name);
            names(next, "function", source, IDENTIFIER);
            functions.add(name);
            return this;
        }

        /**
         * The allow-list.
         *
         * @throws IllegalArgumentException as {@link SqlAllowList} describes (no table, a bad source id or dialect)
         */
        public SqlAllowList build() {
            return new SqlAllowList(source, dialect, tables, procedures, functions);
        }
    }

    /** A copy of {@code names} (null is empty), each matching {@code pattern}, none twice regardless of case. */
    private static List<String> names(List<String> names, String what, String source, Pattern pattern) {
        if (names == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (name == null || !pattern.matcher(name).matches()) {
                throw new IllegalArgumentException("the SQL allow-list of source " + source + " names a " + what
                        + " that is not an SQL identifier: " + quoted(name));
            }
            if (!seen.add(folded(name))) {
                throw new IllegalArgumentException("the SQL allow-list of source " + source + " names " + what + " "
                        + name + " twice");
            }
        }
        return List.copyOf(names);
    }

    private static String folded(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String quoted(String text) {
        return text == null ? "null" : "\"" + text + "\"";
    }
}
