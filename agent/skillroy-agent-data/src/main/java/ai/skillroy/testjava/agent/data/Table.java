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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The conversion of a provider-executed {@link QueryResult} (an agent-api type) into the shared {@link Rows}
 * (phase2-contracts.md 2.2), so that masking and the result shape are applied in one place whichever form of
 * {@code DataSourceProvider} produced the rows; columns a positional source leaves unnamed take the labels of the
 * statement's select list, or the table's columns for {@code SELECT * FROM <table>} (phase8-contracts.md 4.4), and
 * each column carries the names the masks are asked under, or none when its cells must be withheld (Phase 8 security
 * fix, S160, S163, S164, FB).
 *
 * <p>Stateless; safe from any thread.
 */
final class Table {

    private Table() {
    }

    /**
     * A converted result and, per column, the names {@code DataQuery.mask} asks the masks under: the column's own
     * name first, then the other spellings and the referenced names of a column the statement names only by what it
     * reads; null for a column no name can be given (its cells are withheld while a mask is registered).
     */
    static final class Named {
        final Rows rows;
        final List<List<String>> maskNames;
        /** The columns whose mask names are null. */
        final int withheld;

        Named(Rows rows, List<List<String>> maskNames) {
            this.rows = rows;
            this.maskNames = maskNames;
            int n = 0;
            for (List<String> names : maskNames) {
                n += names == null ? 1 : 0;
            }
            this.withheld = n;
        }

        /** Rows a driver named (the JDBC form): each column is asked under its own name only. */
        static Named byOwnNames(Rows rows) {
            List<List<String>> names = new ArrayList<List<String>>(rows.columns().size());
            for (Rows.Column column : rows.columns()) {
                names.add(Collections.singletonList(column.name()));
            }
            return new Named(rows, names);
        }
    }

    /**
     * Converts a provider-executed result as {@link #fromQueryResult(QueryResult, int, List, List, List, Consumer)}
     * without labels from the statement: columns the provider did not name are {@code c1..cn}.
     */
    static Rows fromQueryResult(QueryResult result, int maxRows) throws SQLException {
        return fromQueryResult(result, maxRows, null, null, null, null).rows;
    }

    /**
     * Converts a provider-executed result (phase8-contracts.md 4.4, the naming and width rule, D531, D532, as the
     * Phase 8 security fixes S160 and S164 amend it): keeps at most {@code maxRows} rows (truncated if the provider
     * returned more or said it had more), pads short rows with null so that every row has one cell per column, and
     * converts every cell with {@link ValueConverter}.
     *
     * <p>The candidate list: the derived list when the statement's select list could be read, else, when the
     * provider named no column, the {@code columns(table)} list. It is used when nothing the provider returned is
     * wider: its names, its types and every row it returned (kept or not) are at most as wide as the list; the list
     * then sets the width, and shorter rows are padded at the end. Otherwise it is not used, {@code mismatch} receives
     * one line with both counts (never the statement or a value), and the width is the widest of the names, the types
     * and the rows.
     *
     * <p>Column <i>i</i>'s name and mask names: the provider's name when non-null and non-empty (asked under it);
     * else the candidate's entry <i>i</i> (a derived label is also asked under its upper- and lower-case forms, since
     * a driver folds an unquoted name where the statement keeps its spelling, S163); else {@code c<i+1>}, which is
     * asked under itself and then under each name its select item references when the list could be read
     * ({@code SelectLabels.references}), and has no mask names (withheld) when no candidate names the column or its
     * item reads columns it does not name.
     *
     * @param derivedLabels {@code SelectLabels.of(statement)}: one label (or null) per select item, or null
     * @param references {@code SelectLabels.references(statement)}: per select item the names it references, or null
     * @param starColumns what {@code columns(table)} answered for the star form, or null
     * @param mismatch receives the line about an unused list; may be null
     */
    static Named fromQueryResult(QueryResult result, int maxRows, List<String> derivedLabels,
                                 List<List<String>> references, List<String> starColumns, Consumer<String> mismatch)
            throws SQLException {
        List<List<Object>> source = result.rows();
        int kept = Math.min(maxRows, source.size());
        List<String> givenNames = result.columnNames();
        List<String> givenTypes = result.columnTypes();
        int widest = Math.max(givenNames == null ? 0 : givenNames.size(), givenTypes == null ? 0 : givenTypes.size());
        for (List<Object> row : source) {
            widest = Math.max(widest, row.size());
        }
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
        if (candidate != null && widest > candidate.size()) {
            note(mismatch, what + " names " + candidate.size() + " column(s), the result has " + widest
                    + "; not used for naming");
            candidate = null;
        }
        int width = candidate != null ? candidate.size() : widest;
        List<Rows.Column> columns = new ArrayList<Rows.Column>(width);
        List<List<String>> maskNames = new ArrayList<List<String>>(width);
        for (int c = 0; c < width; c++) {
            String name = givenNames != null && c < givenNames.size() ? givenNames.get(c) : null;
            String type = givenTypes != null && c < givenTypes.size() ? givenTypes.get(c) : null;
            List<String> ask;
            if (name != null && !name.isEmpty()) {
                ask = Collections.singletonList(name);
            } else if (candidate != null && candidate.get(c) != null && !candidate.get(c).isEmpty()) {
                name = candidate.get(c);
                ask = candidate == derived ? spellings(Collections.singletonList(name), null)
                        : Collections.singletonList(name);
            } else {
                name = syntheticName(c);
                List<String> read = candidate == derived && candidate != null && references != null
                        && c < references.size() ? references.get(c) : null;
                ask = read == null ? null : spellings(read, name);
            }
            columns.add(new Rows.Column(name, type));
            maskNames.add(ask);
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
        return new Named(Rows.of(columns, rows, result.truncated() || source.size() > maxRows), maskNames);
    }

    /** {@code first} (when not null), then each name as written, upper-cased and lower-cased, without repeats. */
    private static List<String> spellings(List<String> names, String first) {
        Set<String> out = new LinkedHashSet<String>();
        if (first != null) {
            out.add(first);
        }
        for (String name : names) {
            out.add(name);
            out.add(name.toUpperCase(Locale.ROOT));
            out.add(name.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableList(new ArrayList<String>(out));
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
