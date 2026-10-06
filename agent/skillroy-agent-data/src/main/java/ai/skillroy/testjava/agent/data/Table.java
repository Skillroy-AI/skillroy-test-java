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

/**
 * The conversion of a provider-executed {@link QueryResult} (an agent-api type) into the shared {@link Rows}
 * (phase2-contracts.md 2.2), so that masking and the result shape are applied in one place whichever form of
 * {@code DataSourceProvider} produced the rows.
 *
 * <p>Stateless; safe from any thread.
 */
final class Table {

    private Table() {
    }

    /**
     * Converts a provider-executed result: keeps at most {@code maxRows} rows (truncated if the provider returned
     * more or said it had more), names columns {@code c1..cn} where the provider gave no name, pads short rows with
     * null so that every row has one cell per column, and converts every cell with {@link ValueConverter}.
     */
    static Rows fromQueryResult(QueryResult result, int maxRows) throws SQLException {
        List<List<Object>> source = result.rows();
        int kept = Math.min(maxRows, source.size());
        List<String> givenNames = result.columnNames();
        List<String> givenTypes = result.columnTypes();
        int width = givenNames == null ? 0 : givenNames.size();
        for (int r = 0; r < kept; r++) {
            width = Math.max(width, source.get(r).size());
        }
        List<Rows.Column> columns = new ArrayList<Rows.Column>(width);
        for (int c = 0; c < width; c++) {
            String name = givenNames != null && c < givenNames.size() ? givenNames.get(c) : null;
            String type = givenTypes != null && c < givenTypes.size() ? givenTypes.get(c) : null;
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

    /** "c1" for the column at index 0. */
    static String syntheticName(int index) {
        return "c" + (index + 1);
    }
}
