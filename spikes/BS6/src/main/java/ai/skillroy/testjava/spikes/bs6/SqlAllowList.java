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

package ai.skillroy.testjava.spikes.bs6;

import java.util.ArrayList;
import java.util.List;

/**
 * What a model may read, as phase5-contracts.md section 5.2 declares it (the shape is kept; the builder is the
 * spike's own). Names are written as the pack declares them; the guard matches unquoted identifiers without regard to
 * case and quoted ones as the dialect folds them.
 */
public record SqlAllowList(String source, SqlDialect dialect, List<AllowedTable> tables, List<String> procedures,
                           List<String> functions) {

    public SqlAllowList {
        tables = List.copyOf(tables);
        procedures = List.copyOf(procedures);
        functions = List.copyOf(functions);
    }

    public record AllowedTable(String name, List<AllowedColumn> columns, List<String> key, String description) {
        public AllowedTable {
            columns = List.copyOf(columns);
            key = List.copyOf(key);
        }
    }

    public record AllowedColumn(String name, String type, boolean personal, boolean sample, String description) { }

    public static Builder builder(String source, SqlDialect dialect) {
        return new Builder(source, dialect);
    }

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
         * Adds a table. Columns are written "name type", "name type personal" or "name type sample"; the key lists
         * the tie-breaker columns.
         */
        public Builder table(String name, List<String> key, String... columns) {
            List<AllowedColumn> cols = new ArrayList<>();
            for (String spec : columns) {
                String[] parts = spec.trim().split("\\s+");
                boolean personal = parts.length > 2 && parts[2].equals("personal");
                boolean sample = parts.length > 2 && parts[2].equals("sample");
                cols.add(new AllowedColumn(parts[0], parts[1], personal, sample, null));
            }
            tables.add(new AllowedTable(name, cols, key, null));
            return this;
        }

        public Builder procedure(String name) {
            procedures.add(name);
            return this;
        }

        public Builder functions(String... names) {
            functions.addAll(List.of(names));
            return this;
        }

        public SqlAllowList build() {
            return new SqlAllowList(source, dialect, tables, procedures, functions);
        }
    }
}
