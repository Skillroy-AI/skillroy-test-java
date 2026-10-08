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

import java.util.List;

/**
 * What {@link SqlGuard} lets through (phase5-contracts.md 5.3): the statement as JSqlParser's deparser writes it after
 * the rewrite (this text, never the model's, is what runs), the number of {@code ?} parameters (in order, unchanged by
 * the rewrite), the allow-listed tables and {@code table.column}s it reads (sorted), and one line per clause the guard
 * injected or changed. Lifted from spike BS6.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param sql the rewritten statement
 * @param parameterCount the {@code ?} parameters
 * @param tables the allow-listed tables read, sorted
 * @param columns the allow-listed columns read as {@code table.column}, sorted
 * @param injected what the guard injected or changed ({@code "row limit injected: LIMIT 200"}), in order
 * @param literals the text of every string literal of the statement, in order (rule 6: the tier (b) resolver refuses
 *     one that holds the value of a query argument, which must be bound as {@code ?})
 */
public record GuardedStatement(String sql, int parameterCount, List<String> tables, List<String> columns,
                               List<String> injected, List<String> literals) {

    /** Copies the lists; null literals are none. */
    public GuardedStatement {
        tables = List.copyOf(tables);
        columns = List.copyOf(columns);
        injected = List.copyOf(injected);
        literals = literals == null ? List.of() : List.copyOf(literals);
    }

    /** A statement without string literals (a {@code CALL} with {@code ?} arguments only). */
    public GuardedStatement(String sql, int parameterCount, List<String> tables, List<String> columns,
                            List<String> injected) {
        this(sql, parameterCount, tables, columns, injected, List.of());
    }
}
