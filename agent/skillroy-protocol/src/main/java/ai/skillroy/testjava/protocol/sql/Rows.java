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

package ai.skillroy.testjava.protocol.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows of one guarded read-only statement, whichever channel ran it: the result of the agent's {@code data.query}
 * and of the controller's JDBC channel (phase2-contracts.md 2.2). One type on both sides is what lets a resolver or a
 * stored-row check compare a value read through one channel with the same value read through the other.
 *
 * <p>Cells hold the forms {@link ValueConverter} produces (null, String, Boolean, Long, finite Double, and the rare
 * BigInteger); a row has exactly one cell per column. Column names are what the source reported as labels, which
 * differ in case between databases: read columns by position or through {@link #index(String)}, never by comparing a
 * reported name case-sensitively.
 *
 * <p>Immutable (every list is an unmodifiable copy); safe to share between threads.
 */
public final class Rows {

    /** One result column: the label the source reported (or {@code c1..cn}) and its type name (may be null). */
    public static final class Column {

        private final String name;
        private final String type;

        /**
         * @param name the label; not null
         * @param type the source's type name, or null when the source did not say
         */
        public Column(String name, String type) {
            if (name == null) {
                throw new IllegalArgumentException("a column needs a name");
            }
            this.name = name;
            this.type = type;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Column)) {
                return false;
            }
            Column other = (Column) o;
            return name.equals(other.name) && (type == null ? other.type == null : type.equals(other.type));
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + (type == null ? 0 : type.hashCode());
        }

        @Override
        public String toString() {
            return name + ":" + type;
        }
    }

    private final List<Column> columns;
    private final List<List<Object>> rows;
    private final boolean truncated;

    private Rows(List<Column> columns, List<List<Object>> rows, boolean truncated) {
        this.columns = columns;
        this.rows = rows;
        this.truncated = truncated;
    }

    /**
     * Rows from their parts; the lists are copied.
     *
     * @param truncated true when the source had more rows than were kept
     * @throws IllegalArgumentException if a list or column is null, or a row's width differs from the column count
     */
    public static Rows of(List<Column> columns, List<? extends List<?>> rows, boolean truncated) {
        if (columns == null || rows == null) {
            throw new IllegalArgumentException("columns and rows are required");
        }
        List<Column> cols = new ArrayList<Column>(columns.size());
        for (Column column : columns) {
            if (column == null) {
                throw new IllegalArgumentException("a column is null");
            }
            cols.add(column);
        }
        List<List<Object>> copy = new ArrayList<List<Object>>(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            List<?> row = rows.get(r);
            if (row == null || row.size() != cols.size()) {
                throw new IllegalArgumentException("row " + r + " has " + (row == null ? "no" : row.size())
                        + " cells for " + cols.size() + " columns");
            }
            copy.add(Collections.unmodifiableList(new ArrayList<Object>(row)));
        }
        return new Rows(Collections.unmodifiableList(cols), Collections.unmodifiableList(copy), truncated);
    }

    /** The columns, in result order. */
    public List<Column> columns() {
        return columns;
    }

    /** The rows, each a list with one cell per column. */
    public List<List<Object>> rows() {
        return rows;
    }

    /**
     * The cell at {@code row}, {@code column} (both from 0).
     *
     * @throws IndexOutOfBoundsException if either index is out of range
     */
    public Object get(int row, int column) {
        return rows.get(row).get(column);
    }

    /** Index of the column with this name, compared without regard to case; -1 if absent or ambiguous. */
    public int index(String name) {
        if (name == null) {
            return -1;
        }
        int found = -1;
        for (int c = 0; c < columns.size(); c++) {
            if (columns.get(c).name().equalsIgnoreCase(name)) {
                if (found >= 0) {
                    return -1;
                }
                found = c;
            }
        }
        return found;
    }

    /** How many rows were kept. */
    public int rowCount() {
        return rows.size();
    }

    /** True when the source had more rows than were kept. */
    public boolean truncated() {
        return truncated;
    }

    /** The {@code data.query} result value: {@code {columns:[{name,type}], rows:[[...]], rowCount, truncated}}. */
    public Map<String, Object> toJson() {
        List<Object> cols = new ArrayList<Object>(columns.size());
        for (Column column : columns) {
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("name", column.name());
            json.put("type", column.type());
            cols.add(json);
        }
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("columns", cols);
        json.put("rows", rows);
        json.put("rowCount", Long.valueOf(rows.size()));
        json.put("truncated", Boolean.valueOf(truncated));
        return json;
    }

    /**
     * Reads the {@code data.query} result value back, as the wire codec parsed it.
     *
     * @throws IllegalArgumentException if {@code json} does not have that shape, or {@code rowCount} disagrees with
     *     the rows
     */
    public static Rows fromJson(Map<String, Object> json) {
        if (json == null) {
            throw new IllegalArgumentException("no rows");
        }
        Object rawColumns = json.get("columns");
        Object rawRows = json.get("rows");
        if (!(rawColumns instanceof List) || !(rawRows instanceof List)) {
            throw new IllegalArgumentException("rows need 'columns' and 'rows' lists");
        }
        List<Column> columns = new ArrayList<Column>();
        for (Object raw : (List<?>) rawColumns) {
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("a column must be an object");
            }
            Object name = ((Map<?, ?>) raw).get("name");
            Object type = ((Map<?, ?>) raw).get("type");
            if (!(name instanceof String) || type != null && !(type instanceof String)) {
                throw new IllegalArgumentException("a column needs a string name and a string or null type");
            }
            columns.add(new Column((String) name, (String) type));
        }
        List<List<?>> rows = new ArrayList<List<?>>();
        for (Object raw : (List<?>) rawRows) {
            if (!(raw instanceof List)) {
                throw new IllegalArgumentException("a row must be an array");
            }
            rows.add((List<?>) raw);
        }
        Object truncated = json.get("truncated");
        if (truncated != null && !(truncated instanceof Boolean)) {
            throw new IllegalArgumentException("'truncated' must be a boolean");
        }
        Object rowCount = json.get("rowCount");
        if (rowCount != null && (!(rowCount instanceof Number) || ((Number) rowCount).longValue() != rows.size())) {
            throw new IllegalArgumentException("'rowCount' does not match the rows");
        }
        return of(columns, rows, Boolean.TRUE.equals(truncated));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Rows)) {
            return false;
        }
        Rows other = (Rows) o;
        return truncated == other.truncated && columns.equals(other.columns) && rows.equals(other.rows);
    }

    @Override
    public int hashCode() {
        return (columns.hashCode() * 31 + rows.hashCode()) * 31 + (truncated ? 1 : 0);
    }

    /** Columns and counts only: cell values may be personal data and are never part of the text. */
    @Override
    public String toString() {
        return "Rows" + columns + "[rowCount=" + rows.size() + ", truncated=" + truncated + "]";
    }
}
