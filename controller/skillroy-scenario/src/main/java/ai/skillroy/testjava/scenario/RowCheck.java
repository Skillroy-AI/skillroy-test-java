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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.scenario.resolve.ColumnKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stored-row check, {@code db.<table>: {where, rows?, capability?, <column>: matcher…}} (phase2-contracts.md 7.4),
 * compiled to SQL from the pack's {@code StoredTable} at compile time, so that scenario text never becomes SQL text
 * and a reviewer sees the statement in the plan diff (decision D17). At run time the parameters are substituted and
 * converted by kind, the statement runs through the data channel that serves {@code source}, result columns are
 * matched to the table's columns <b>by position</b>, the row count must satisfy {@code rows} and every row every
 * column matcher. Retried like an expectation until it holds or the step timeout expires.
 *
 * <p>Aggregates (phase3-contracts.md 3.2, 7, decision D40): {@code sum(<column>)}, {@code min(<column>)} and
 * {@code max(<column>)} over an {@code INTEGER} or {@code DECIMAL} column, each with a matcher, are computed on the
 * controller over the rows the statement returned, after the row count and the per-row matchers hold; a truncated
 * result fails the check before any aggregate is computed. The statement is the same with or without them.
 *
 * <p>In {@code plan.json}: {@code {"db", "source", "sql", "params": [{"kind", "value"}], "rows", "expect",
 * "aggregates"?, "capability"?}}; {@code aggregates} (version 3) only when the scenario wrote at least one.
 *
 * <p>Immutable (matchers and values are deep unmodifiable copies; {@code expect} and {@code aggregates} sorted by key);
 * safe to share between threads.
 *
 * @param table the pack's table name ({@code db.<table>})
 * @param source the data source id
 * @param sql the generated statement, one {@code ?} per parameter
 * @param params one per {@code ?}: the column kind and the value template (a scalar or a reference)
 * @param rows the row-count matcher in normal form ({@code {"equals": 1}} by default)
 * @param expect column name to matcher in normal form, keys sorted
 * @param aggregates aggregate key ({@code "sum(amount)"}) to matcher in normal form, keys sorted; never null, may be
 *     empty
 * @param capability the guarding capability id, or null
 */
public record RowCheck(String table, String source, String sql, List<Param> params, Object rows,
                       Map<String, Object> expect, Map<String, Object> aggregates, String capability)
        implements Check {

    /**
     * One statement parameter.
     *
     * @param kind the column's kind: how the substituted value is converted
     * @param value a scalar or a reference template
     */
    public record Param(ColumnKind kind, Object value) {

        /** @throws NullPointerException if the kind is null */
        public Param {
            Objects.requireNonNull(kind, "kind");
            value = Plan.freeze(value);
        }

        /** {@code {"kind", "value"}}. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("kind", kind.name());
            json.put("value", value);
            return json;
        }
    }

    /**
     * An aggregate key, parsed (phase3-contracts.md 3.2, 4.1): {@code fn} is {@code sum}, {@code min} or {@code max},
     * {@code column} a column name. Immutable.
     *
     * @param fn {@code sum}, {@code min} or {@code max}
     * @param column the column it aggregates, {@code [A-Za-z_][A-Za-z0-9_]*}
     */
    public record Aggregate(String fn, String column) {

        /** The aggregate functions, in documentation order. */
        public static final List<String> FUNCTIONS = List.of("sum", "min", "max");

        /** The three forms, for messages. */
        public static final String FORMS = "sum(<column>), min(<column>), max(<column>)";

        private static final Pattern FORM = Pattern.compile("(sum|min|max)\\(([A-Za-z_][A-Za-z0-9_]*)\\)");

        /** @throws IllegalArgumentException if the function is not one of the three or the column is not a name */
        public Aggregate {
            if (fn == null || !FUNCTIONS.contains(fn)) {
                throw new IllegalArgumentException("an aggregate is one of " + FORMS + ", found " + fn);
            }
            if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("an aggregate's column must match [A-Za-z_][A-Za-z0-9_]*, found "
                        + column);
            }
        }

        /**
         * The aggregate a key names, or null when the key has not the form {@code fn(column)} with {@code fn} one of
         * {@code sum}, {@code min}, {@code max} (lower case, no spaces): {@code count()}, {@code avg(amount)} and
         * {@code SUM(amount)} are null.
         */
        public static Aggregate parse(String key) {
            if (key == null) {
                return null;
            }
            Matcher m = FORM.matcher(key);
            return m.matches() ? new Aggregate(m.group(1), m.group(2)) : null;
        }

        /**
         * True when the key has the form of a function call ({@code name(…)}, any case, anything inside), whether or
         * not it is an aggregate this version offers: such a key is never a column name.
         */
        public static boolean looksLikeCall(String key) {
            return key != null && (key.indexOf('(') >= 0 || key.indexOf(')') >= 0);
        }

        /** The key as written: {@code "sum(amount)"}. */
        public String key() {
            return fn + "(" + column + ")";
        }
    }

    /** Copies and freezes everything; sorts {@code expect} and {@code aggregates}. */
    public RowCheck {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(rows, "rows");
        params = List.copyOf(params);
        rows = Plan.freeze(rows);
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) Plan.freeze(new TreeMap<>(expect == null ? Map.of()
                : expect));
        expect = frozen;
        @SuppressWarnings("unchecked")
        Map<String, Object> frozenAggregates = (Map<String, Object>) Plan.freeze(new TreeMap<>(aggregates == null
                ? Map.of() : aggregates));
        aggregates = frozenAggregates;
    }

    /** A row check without aggregates (the Phase 2 shape). */
    public RowCheck(String table, String source, String sql, List<Param> params, Object rows,
                    Map<String, Object> expect, String capability) {
        this(table, source, sql, params, rows, expect, Map.of(), capability);
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("db", table);
        json.put("source", source);
        json.put("sql", sql);
        List<Object> list = new ArrayList<>();
        for (Param param : params) {
            list.add(param.toJson());
        }
        json.put("params", list);
        json.put("rows", rows);
        json.put("expect", expect);
        if (!aggregates.isEmpty()) {
            json.put("aggregates", aggregates);
        }
        if (capability != null) {
            json.put("capability", capability);
        }
        return json;
    }

    @Override
    public String describe() {
        List<Object> values = new ArrayList<>();
        for (Param param : params) {
            values.add(param.value());
        }
        return "db." + table + " " + Values.safeWrite(values) + " rows " + Values.safeWrite(rows) + " "
                + Values.safeWrite(expect) + (aggregates.isEmpty() ? "" : " " + Values.safeWrite(aggregates))
                + (capability == null ? "" : " [capability " + capability + "]");
    }

    /** The parameter values in order (templates). */
    List<Object> paramValues() {
        List<Object> out = new ArrayList<>();
        for (Param param : params) {
            out.add(param.value());
        }
        return Collections.unmodifiableList(out);
    }
}
