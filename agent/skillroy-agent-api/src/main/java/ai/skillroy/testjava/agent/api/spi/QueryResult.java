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

package ai.skillroy.testjava.agent.api.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Rows returned by {@link DataSourceProvider#query}, for data sources that are not reachable through JDBC and
 * therefore cannot hand the agent a {@code ResultSet}.
 *
 * <p>Immutable (the lists are copied and unmodifiable; cell values are kept as given); safe to share between
 * threads as long as the cell values are not mutated.
 */
public final class QueryResult {

    private final List<String> columnNames;
    private final List<String> columnTypes;
    private final List<List<Object>> rows;
    private final boolean truncated;

    /**
     * @param columnNames may be null when the source cannot name its columns: {@code data.query} then reports
     *     c1..cn
     * @param columnTypes type names of the columns, may be null
     * @param rows hold JSON-able values or java.sql/java.math/java.util.Date values; null cells are allowed, null
     *     rows are not; null means no rows
     * @param truncated true if the source had more rows than it returned
     * @throws NullPointerException if a row is null
     */
    public QueryResult(List<String> columnNames, List<String> columnTypes, List<List<Object>> rows,
                       boolean truncated) {
        this.columnNames = copyOrNull(columnNames);
        this.columnTypes = copyOrNull(columnTypes);
        List<List<Object>> copy = new ArrayList<List<Object>>();
        if (rows != null) {
            for (List<Object> row : rows) {
                Objects.requireNonNull(row, "rows must not contain null");
                copy.add(Collections.unmodifiableList(new ArrayList<Object>(row)));
            }
        }
        this.rows = Collections.unmodifiableList(copy);
        this.truncated = truncated;
    }

    private static List<String> copyOrNull(List<String> list) {
        return list == null ? null : Collections.unmodifiableList(new ArrayList<String>(list));
    }

    /** Unmodifiable; null when the source cannot name its columns. */
    public List<String> columnNames() {
        return columnNames;
    }

    /** Unmodifiable; null when the source does not report column types. */
    public List<String> columnTypes() {
        return columnTypes;
    }

    /** Unmodifiable, never null; each row is unmodifiable. */
    public List<List<Object>> rows() {
        return rows;
    }

    public boolean truncated() {
        return truncated;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QueryResult)) {
            return false;
        }
        QueryResult other = (QueryResult) o;
        return Objects.equals(columnNames, other.columnNames) && Objects.equals(columnTypes, other.columnTypes)
                && rows.equals(other.rows) && truncated == other.truncated;
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnNames, columnTypes, rows, Boolean.valueOf(truncated));
    }

    @Override
    public String toString() {
        return "QueryResult{columnNames=" + columnNames + ", columnTypes=" + columnTypes + ", rows=" + rows.size()
                + ", truncated=" + truncated + "}";
    }
}
