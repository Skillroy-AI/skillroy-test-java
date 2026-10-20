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

package ai.skillroy.testjava.inference.prompt;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.sql.GuardViolation;
import ai.skillroy.testjava.inference.sql.GuardedStatement;
import ai.skillroy.testjava.inference.sql.SqlGuard;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedTable;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The prompt catalogue (phase5-contracts.md 5.2): the frozen text a model sees about the schema, built once per bind
 * from the pack's allow-lists. Per table: its name, description, columns with types and descriptions (a personal
 * column marked as filterable, never selectable), the key, and for each {@code sample} column up to {@value #SAMPLES}
 * distinct values read with one guarded {@code SELECT DISTINCT … LIMIT 21} ({@code truncated} when 21 came back), every
 * value through the {@link PromptMask}; plus each source's dialect and its notes (DB2: singular durations or
 * {@code ADD_DAYS}), the row cap, the guard's rules in words, and the shape the answer takes. Rendered as the first
 * system part with a cache breakpoint, long enough on its own instructions to clear the provider's minimum cacheable
 * prefix; it holds no credential, no personal value, no row of a non-sample column and nothing read from the target's
 * UI. Deterministic: the same allow-lists and sample values give the same text.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class SchemaCatalogue {

    /** The most distinct values a sample shows. */
    public static final int SAMPLES = 20;

    /**
     * One sampled column.
     *
     * @param source the data source
     * @param table the table
     * @param column the column
     * @param values the distinct values, masked, in the guard's order
     * @param truncated true when more than {@value #SAMPLES} exist
     */
    public record Sample(String source, String table, String column, List<String> values, boolean truncated) {

        /** Copies the values. */
        public Sample {
            values = List.copyOf(values);
        }
    }

    private final List<SqlAllowList> lists;
    private final Map<String, Sample> samples;
    private final String text;

    private SchemaCatalogue(List<SqlAllowList> lists, Map<String, Sample> samples, PromptMask mask) {
        this.lists = List.copyOf(lists);
        this.samples = Map.copyOf(samples);
        this.text = render(this.lists, samples, mask);
    }

    /**
     * The catalogue of these allow-lists with the sample columns read through the data channel (each statement the
     * guard's own rewrite of {@code SELECT DISTINCT t.c FROM t} limited to {@value #SAMPLES}+1 rows).
     *
     * @param timeout each statement's timeout; null is the source's default
     * @throws DataException when a sample statement fails
     * @throws AgentLostException when the agent serving a source is gone
     * @throws InterruptedException when the thread is interrupted
     * @throws IllegalArgumentException for a dialect without a passing BS6 corpus
     */
    public static SchemaCatalogue build(List<SqlAllowList> lists, DataChannel data, PromptMask mask, Duration timeout)
            throws DataException, AgentLostException, InterruptedException {
        Objects.requireNonNull(data, "data");
        Map<String, Sample> samples = new LinkedHashMap<>();
        for (SqlAllowList list : lists) {
            try (SqlGuard guard = new SqlGuard(list)) {
                for (AllowedTable table : list.tables()) {
                    for (AllowedColumn column : table.columns()) {
                        if (!column.sample()) {
                            continue;
                        }
                        GuardedStatement sql;
                        try {
                            sql = guard.guardDistinct(table.name(), column.name(), SAMPLES + 1);
                        } catch (GuardViolation v) {
                            throw new IllegalArgumentException("the sample of " + table.name() + "." + column.name()
                                    + " is refused by the guard: " + v.getMessage());
                        }
                        Rows rows = mask.rows(list.source(), data.query(list.source(), sql.sql(), List.of(),
                                SAMPLES + 1, timeout));
                        List<String> values = new ArrayList<>();
                        for (List<Object> row : rows.rows()) {
                            if (!row.isEmpty() && row.get(0) != null && values.size() < SAMPLES) {
                                values.add(value(row.get(0)));
                            }
                        }
                        boolean truncated = rows.truncated() || values.size() >= SAMPLES && rows.rowCount() > SAMPLES;
                        samples.put(key(list.source(), table.name(), column.name()),
                                new Sample(list.source(), table.name(), column.name(), values, truncated));
                    }
                }
            }
        }
        return new SchemaCatalogue(lists, samples, mask);
    }

    /** A catalogue whose sample columns show no values (nothing read): for a pack's own checks and tests. */
    public static SchemaCatalogue withoutSamples(List<SqlAllowList> lists, PromptMask mask) {
        return new SchemaCatalogue(lists, Map.of(), mask);
    }

    /** The allow-lists, in declaration order. */
    public List<SqlAllowList> allowLists() {
        return lists;
    }

    /** The sample of a column, or null. */
    public Sample sample(String source, String table, String column) {
        return samples.get(key(source, table, column));
    }

    /** The rendered catalogue. */
    public String text() {
        return text;
    }

    /** The catalogue as the first system part, with the cache breakpoint. */
    public Content.Text part() {
        return Content.Text.cached(text);
    }

    /** A rough token count: characters / 4. */
    public long estimatedTokens() {
        return Math.max(1, text.length() / 4);
    }

    private static String key(String source, String table, String column) {
        return source + "\u0000" + table + "\u0000" + column;
    }

    private static String value(Object cell) {
        if (cell instanceof BigDecimal d) {
            return d.toPlainString();
        }
        return String.valueOf(cell);
    }

    // ------------------------------------------------------------------------------------------------ rendering

    private static String render(List<SqlAllowList> lists, Map<String, Sample> samples, PromptMask mask) {
        StringBuilder out = new StringBuilder();
        out.append("# SQL catalogue\n\n");
        out.append("This is everything you may read, per data source. A statement reads the tables of exactly one")
                .append(" source. A guard checks every statement against this catalogue and the rules below before")
                .append(" it runs, rewrites its ORDER BY and row limit, and refuses anything else; a refused")
                .append(" statement gives no answer.\n");
        for (SqlAllowList list : lists) {
            out.append("\n## Source \"").append(list.source()).append("\" (dialect ").append(list.dialect())
                    .append(")\n");
            for (AllowedTable table : list.tables()) {
                out.append("\nTable ").append(table.name().substring(table.name().lastIndexOf('.') + 1));
                if (table.description() != null && !table.description().isBlank()) {
                    out.append(": ").append(mask.text(oneLine(table.description())));
                }
                out.append("\n  Key (the tie-breaker the guard orders by): ").append(String.join(", ", table.key()))
                        .append("\n  Columns:\n");
                for (AllowedColumn column : table.columns()) {
                    out.append("  - ").append(column.name()).append(' ').append(column.type());
                    List<String> notes = new ArrayList<>();
                    if (column.description() != null && !column.description().isBlank()) {
                        notes.add(mask.text(oneLine(column.description())));
                    }
                    if (column.personal()) {
                        notes.add("personal: you may filter, join, group or order on it, never select it");
                    }
                    Sample sample = samples.get(key(list.source(), table.name(), column.name()));
                    if (column.sample() && sample != null) {
                        notes.add((sample.truncated() ? "some of its values" : "its values") + ": "
                                + (sample.values().isEmpty() ? "(none)" : String.join(", ", sample.values())));
                    }
                    if (!notes.isEmpty()) {
                        out.append(" (").append(String.join("; ", notes)).append(')');
                    }
                    out.append('\n');
                }
            }
            // phase8-contracts.md 4.5 (gap 119): no procedures line; no data channel runs a procedure call.
            if (!list.functions().isEmpty()) {
                out.append("\nFunctions allowed beyond the built-in set: ").append(String.join(", ", list.functions()))
                        .append('\n');
            }
            out.append("\nDialect notes: ").append(dialectNotes(list.dialect())).append('\n');
        }
        out.append(RULES);
        return out.toString();
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /** What the model must know of each dialect (phase5-contracts.md 5.3, spike BS6 section 8). */
    static String dialectNotes(SqlDialect dialect) {
        return switch (dialect) {
            case HSQLDB -> "HyperSQL; the row limit is written LIMIT n; date functions DATEADD, DATEDIFF,"
                    + " TIMESTAMPADD, TIMESTAMPDIFF, DATE_ADD, DATE_SUB, YEAR, MONTH, DAY.";
            case MYSQL, MARIADB -> "the row limit is written LIMIT n; quote an identifier, if at all, with backticks,"
                    + " never double quotes; date functions NOW, CURDATE, DATE_ADD, DATE_SUB, DATEDIFF,"
                    + " TIMESTAMPADD, TIMESTAMPDIFF, YEAR, MONTH, DAY.";
            case POSTGRESQL -> "the row limit is written LIMIT n; :: casts to scalar types are allowed; date"
                    + " functions NOW, DATE_TRUNC, DATE_PART; no dollar-quoted, E'' or U&'' strings.";
            case DB2 -> "the row limit is written FETCH FIRST n ROWS ONLY; write date arithmetic with ADD_DAYS(d, n),"
                    + " DAYS(d) or a singular labeled duration (CURRENT DATE - 7 DAY): a plural duration such as"
                    + " - 7 DAYS does not parse and the statement is refused.";
            case ORACLE -> "Oracle 12c or newer; the row limit is written FETCH FIRST n ROWS ONLY and ROWNUM is"
                    + " refused; DUAL is a one-row table; date functions SYSDATE, SYSTIMESTAMP, ADD_MONTHS,"
                    + " MONTHS_BETWEEN, TRUNC, TO_DATE.";
            case SQLSERVER -> "the row limit is written TOP n (the guard places it); no table hints such as NOLOCK;"
                    + " date functions GETDATE, DATEADD, DATEDIFF, YEAR, MONTH, DAY.";
            case GENERIC -> "standard SQL; the row limit is written FETCH FIRST n ROWS ONLY.";
        };
    }

    private static final String RULES = """

            ## The rules the guard enforces

            1. Exactly one statement: a SELECT, optionally with WITH. Never INSERT, UPDATE, DELETE, MERGE, DDL, \
            SELECT ... INTO, a locking clause or table hint, UNION, INTERSECT, EXCEPT or MINUS, CONNECT BY, PIVOT, \
            LATERAL, APPLY, DISTINCT ON, a recursive WITH or GROUPING SETS. No comments of any kind.
            2. Only the tables above, named by their unqualified names (no schema, no database link); a WITH name \
            may not reuse a table's name.
            3. Only the columns above. Qualify every column with its table's alias whenever more than one table is \
            in scope. Never SELECT * or t.*. A personal column never reaches the select list, not even inside an \
            expression, a derived table or a scalar subquery whose value is selected.
            4. Functions: COUNT, SUM, MIN, MAX, AVG, COALESCE, NULLIF, CASE, CAST to a scalar type, LOWER, UPPER, \
            TRIM, LENGTH, SUBSTRING, ABS, ROUND, CURRENT_DATE, CURRENT_TIMESTAMP, EXTRACT, the dialect's date \
            functions named above and the functions the source allows. No window functions (OVER), WITHIN GROUP, \
            FILTER or KEEP.
            5. Literals: numbers, single-quoted strings of at most 200 characters without backslashes, DATE, \
            TIMESTAMP and INTERVAL literals, TRUE, FALSE and NULL. No double-quoted strings, no hexadecimal or \
            binary literals, no named or numbered parameters. Every value that comes from the query's arguments is \
            a ? parameter, in order.
            6. Order: the guard completes the ORDER BY with a tie-breaker (the GROUP BY columns of a grouped query, \
            the select list of a DISTINCT query, otherwise the key columns of every table in FROM), so order by \
            what the question needs and let the guard make it total. No ORDER BY inside a subquery or WITH.
            7. At most 200 rows, in the dialect's one form, which the guard injects when absent and caps when \
            larger. No OFFSET, no parameter or 0 as the limit, no PERCENT or WITH TIES, no limit inside a subquery.
            8. At most 3 nested subqueries, 8 table references, 20 columns in the select list, 32 nested \
            parentheses and 4000 characters.

            ## The answer

            One JSON object: sql (the statement), params (one entry per ? in order: name, the query argument it \
            binds as a dotted path such as deposit.between.0; value, that argument's value as text exactly as the \
            query holds it; type, one of STRING, NUMBER, DATE, TIMESTAMP, BOOLEAN), rationale (why this statement \
            answers the question, in a few sentences, naming no person), tablesUsed, orderBy (the ORDER BY you \
            meant), confidence (from 0 to 1), expectedRows (min and max rows you expect, between 1 and 200), \
            cannotAnswer (true when the catalogue cannot answer the question) and cannotAnswerReason. When \
            cannotAnswer is true, sql is empty and params is empty.
            """;

    /** Sources and sample counts only. */
    @Override
    public String toString() {
        return "SchemaCatalogue[" + lists.size() + " sources, " + samples.size() + " samples]";
    }
}
