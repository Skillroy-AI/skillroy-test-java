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

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One column a scenario may name in a {@code db.<table>} check (phase2-contracts.md 7.4). Scenarios use the logical
 * {@code name}; the generated SQL holds the pack's committed {@code sql} text, so that scenario text never becomes SQL
 * text and a guard-forbidden identifier can be quoted (decision D28).
 *
 * <p>Built through {@link #of}; the canonical constructor is not API. Immutable; safe to share between threads.
 *
 * @param name what scenarios write, {@code [A-Za-z_][A-Za-z0-9_]*}
 * @param sql the pack's committed SQL text for the column (a physical name, quoted if the database needs it)
 * @param kind how a {@code where} value is passed
 * @param paramSql how a {@code where} value is written, with exactly one {@code ?} (for a decimal, typically
 *     {@code "CAST(? AS DECIMAL(10,2))"})
 */
public record StoredColumn(String name, String sql, ColumnKind kind, String paramSql) {

    static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * @throws IllegalArgumentException if the name is not a name, a text is blank or {@code paramSql} does not hold
     *     exactly one {@code ?}
     */
    public StoredColumn {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a stored column name must match [A-Za-z_][A-Za-z0-9_]*, found "
                    + name);
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("column " + name + ": sql must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        if (paramSql == null || paramSql.chars().filter(c -> c == '?').count() != 1) {
            throw new IllegalArgumentException("column " + name + ": paramSql must hold exactly one '?', found "
                    + paramSql);
        }
    }

    /** A column whose SQL text is its name and whose parameter is a plain {@code ?}. */
    public static StoredColumn of(String name, ColumnKind kind) {
        return new StoredColumn(name, name, kind, "?");
    }

    /** This column with another SQL text. */
    public StoredColumn sql(String newSql) {
        return new StoredColumn(name, newSql, kind, paramSql);
    }

    /** This column with another parameter text (exactly one {@code ?}). */
    public StoredColumn param(String newParamSql) {
        return new StoredColumn(name, sql, kind, newParamSql);
    }
}
