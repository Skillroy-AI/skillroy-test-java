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

package ai.skillroy.testjava.agent.data;

import ai.skillroy.testjava.agent.api.spi.QueryResult;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.protocol.sql.ValueConverter;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The conversion of a provider-executed {@link QueryResult} (an agent-api type) into the shared {@link Rows}
 * (phase2-contracts.md 2.2), so that masking and the result shape are applied in one place whichever form of
 * {@code DataSourceProvider} produced the rows; columns a positional source leaves unnamed take the labels of the
 * statement's select list, or the table's columns for {@code SELECT * FROM <table>} (phase8-contracts.md 4.4).
 *
 * <p>Stateless; safe from any thread.
 */
final class Table {

    private Table() {
    }

    /**
     * Converts a provider-executed result as {@link #fromQueryResult(QueryResult, int, List, List, Consumer)} without
     * labels from the statement: columns the provider did not name are {@code c1..cn}.
     */
    static Rows fromQueryResult(QueryResult result, int maxRows) throws SQLException {
        return fromQueryResult(result, maxRows, null, null, null);
    }

    /**
     * Converts a provider-executed result (phase8-contracts.md 4.4, the naming and width rule, D531, D532): keeps at
     * most {@code maxRows} rows (truncated if the provider returned more or said it had more), pads short rows with
     * null so that every row has one cell per column, and converts every cell with {@link ValueConverter}.
     *
     * <p>Width: the largest of the provider's names, its types and every row it returned (kept or not); when it
     * returned no row, also the derived list's size, else the {@code columns(table)} list's size. A list whose size
     * differs from a non-zero width taken from the names or the rows is not used, and {@code mismatch} receives one
     * line with both counts (never the statement or a value).
     *
     * <p>Column <i>i</i>'s name: the provider's when non-null and non-empty; else the derived label <i>i</i> when the
     * derived list has the result's width; else, when the provider named no column, entry <i>i</i> of
     * {@code starColumns} when that list has the result's width; else {@code c<i+1>}.
     *
     * @param derivedLabels {@code SelectLabels.of(statement)}: one label (or null) per select item, or null
     * @param starColumns what {@code columns(table)} answered for the star form, or null
     * @param mismatch receives the line about an unused list; may be null
     */
    static Rows fromQueryResult(QueryResult result, int maxRows, List<String> derivedLabels, List<String> starColumns,
                                Consumer<String> mismatch) throws SQLException {
        List<List<Object>> source = result.rows();
        int kept = Math.min(maxRows, source.size());
        List<String> givenNames = result.columnNames();
        List<String> givenTypes = result.columnTypes();
        int fixed = givenNames == null ? 0 : givenNames.size();
        for (List<Object> row : source) {
            fixed = Math.max(fixed, row.size());
        }
        int width = Math.max(fixed, givenTypes == null ? 0 : givenTypes.size());
        boolean named = false;
        if (givenNames != null) {
            for (String name : givenNames) {
                named |= name != null && !name.isEmpty();
            }
        }
        List<String> derived = derivedLabels;
        List<String> star = derived == null && !named ? starColumns : null;
        List<String> candidate = derived != null ? derived : star;
        String what = derived != null ? "the statement's select list" : "columns(table)";
        if (candidate != null && fixed > 0 && candidate.size() != fixed) {
            note(mismatch, what + " names " + candidate.size() + " column(s), the result has " + fixed
                    + "; not used for naming");
            candidate = null;
        } else if (candidate != null && source.isEmpty()) {
            width = Math.max(width, candidate.size());
        }
        if (candidate != null && candidate.size() != width) {
            note(mismatch, what + " names " + candidate.size() + " column(s), the result has " + width
                    + "; not used for naming");
            candidate = null;
        }
        List<Rows.Column> columns = new ArrayList<Rows.Column>(width);
        for (int c = 0; c < width; c++) {
            String name = givenNames != null && c < givenNames.size() ? givenNames.get(c) : null;
            String type = givenTypes != null && c < givenTypes.size() ? givenTypes.get(c) : null;
            if ((name == null || name.isEmpty()) && candidate != null) {
                name = candidate.get(c);
            }
            columns.add(new Rows.Column(name == null || name.isEmpty() ? syntheticName(c) : name, type));
        }
        List<List<Object>> rows = new ArrayList<List<Object>>(kept);
        for (int r = 0; r < kept; r++) {
            List<Object> cells = source.get(r);
            List<Object> row = new ArrayList<Object>(width);
            for (int c = 0; c < width; c++) {
                row.add(c < cells.size() ? ValueConverter.convert(cells.get(c)) : null);
            }
            rows.add(row);
        }
        return Rows.of(columns, rows, result.truncated() || source.size() > maxRows);
    }

    private static void note(Consumer<String> mismatch, String line) {
        if (mismatch != null) {
            mismatch.accept(line);
        }
    }

    /** "c1" for the column at index 0. */
    static String syntheticName(int index) {
        return "c" + (index + 1);
    }
}
