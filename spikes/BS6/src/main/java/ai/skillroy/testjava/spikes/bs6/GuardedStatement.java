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

import java.util.List;

/**
 * What the guard lets through: the statement as JSqlParser's deparser writes it after the rewrite (this is the text
 * that runs), the number of {@code ?} parameters (in order, unchanged by the rewrite), the allow-listed tables and
 * {@code table.column}s it reads (sorted), and one line per clause the guard injected or changed.
 */
public record GuardedStatement(String sql, int parameterCount, List<String> tables, List<String> columns,
                               List<String> injected) {
    public GuardedStatement {
        tables = List.copyOf(tables);
        columns = List.copyOf(columns);
        injected = List.copyOf(injected);
    }
}
