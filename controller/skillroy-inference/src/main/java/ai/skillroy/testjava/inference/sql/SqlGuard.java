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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

import ai.skillroy.testjava.inference.sql.Analyzer.Scope;
import ai.skillroy.testjava.inference.sql.Analyzer.Source;
import ai.skillroy.testjava.inference.sql.Analyzer.Use;
import ai.skillroy.testjava.inference.sql.GuardViolation.Reason;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.select.Fetch;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Top;
import net.sf.jsqlparser.util.deparser.StatementDeParser;

/**
 * The guard of a model-written statement (phase5-contracts.md 5.3, decision D73) on JSqlParser 5.4, lifted from spike
 * BS6: it parses the statement in the allow-list's dialect, applies the ten rules, rewrites the ORDER BY (the derived
 * tie-breaker) and the row limit (the dialect's one form, at most {@value #ROW_CAP} rows), and returns the deparsed
 * text that runs. Pure and deterministic: the same text and allow-list give the same {@link GuardedStatement} or the
 * same {@link GuardViolation}.
 *
 * <p>Order of the checks: the text-size half of rule 9 and the guard's own lexical pass (rule 1) run first, because
 * they bound and clean what the parser sees; then the parse (rule 1), rules 2 to 8 and the rest of rule 9 in the walk;
 * then the rewrite; then the output check of rule 10 (no comment marker, the output parses and deparses to itself, the
 * output passes the walk again with the same parameters, tables and columns), whose failure is {@code REWRITE}.
 *
 * <p>Served dialects ({@link #served}): the eight of {@code SqlDialect}, each with a passing BS6 corpus; a dialect
 * without one is refused with "no BS6 corpus" rather than served loosely. DB2: plural labeled durations
 * ({@code - 7 DAYS}) do not parse ({@code PARSE}); Oracle: 12c or newer ({@code FETCH FIRST}); SQL Server: {@code CALL}
 * through the JDBC escape; GENERIC: only a database that accepts {@code FETCH FIRST n ROWS ONLY}.
 *
 * <p>Thread-safe: {@link #guard} may be called from several threads. One parser thread per guard (a daemon, the
 * parser's time-out needs it), stopped by {@link #close()}.
 */
public final class SqlGuard implements AutoCloseable {

    /** The most rows a guarded statement returns (rule 8). */
    public static final int ROW_CAP = 200;
    /** The longest statement text the guard reads (rule 9). */
    public static final int MAX_TEXT = 4000;

    /** The dialects with a passing BS6 corpus: every dialect of this version. */
    private static final Set<SqlDialect> SERVED = Set.of(SqlDialect.values());

    private final SqlAllowList allow;
    private final DialectProfile profile;
    private final ExecutorService parserThread;
    private final UnaryOperator<String> outputFilter;

    /**
     * A guard for statements on the allow-list's source.
     *
     * @throws IllegalArgumentException for a dialect without a passing BS6 corpus ("no BS6 corpus")
     */
    public SqlGuard(SqlAllowList allow) {
        this(allow, UnaryOperator.identity());
    }

    /** A guard whose deparsed output passes through {@code outputFilter} before the output check (rule 10's tests). */
    SqlGuard(SqlAllowList allow, UnaryOperator<String> outputFilter) {
        this.allow = java.util.Objects.requireNonNull(allow, "allow");
        if (!served(allow.dialect())) {
            throw new IllegalArgumentException("SQL dialect " + allow.dialect() + " of source " + allow.source()
                    + " is not served: no BS6 corpus");
        }
        this.profile = DialectProfile.of(allow.dialect());
        this.outputFilter = outputFilter;
        this.parserThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sql-guard-parser");
            t.setDaemon(true);
            return t;
        });
    }

    /** True when the dialect has a passing BS6 corpus and is served (phase5-contracts.md 5.3). */
    public static boolean served(SqlDialect dialect) {
        return dialect != null && SERVED.contains(dialect);
    }

    /** The dialect this guard parses. */
    public SqlDialect dialect() {
        return profile.dialect();
    }

    /** The allow-list this guard checks against. */
    public SqlAllowList allowList() {
        return allow;
    }

    DialectProfile profile() {
        return profile;
    }

    /**
     * The guarded statement that reads up to {@code rows} distinct values of one allow-listed column, in the dialect's
     * own row-limit form and ordered by the value (the prompt catalogue's sample, phase5-contracts.md 5.2).
     *
     * @param table the allow-listed table (a declared {@code schema.table} is named by its table part)
     * @param column one of its columns
     * @param rows 1 to {@value #ROW_CAP}
     * @throws GuardViolation when the guard refuses it (a personal column, a name the allow-list does not hold)
     */
    public GuardedStatement guardDistinct(String table, String column, int rows) throws GuardViolation {
        if (rows < 1 || rows > ROW_CAP) {
            throw new IllegalArgumentException("rows is 1 to " + ROW_CAP);
        }
        String name = table.substring(table.lastIndexOf('.') + 1);
        String text = switch (profile.limitStyle()) {
            case LIMIT -> "SELECT DISTINCT s0." + column + " FROM " + name + " s0 LIMIT " + rows;
            case FETCH_FIRST -> "SELECT DISTINCT s0." + column + " FROM " + name + " s0 FETCH FIRST " + rows
                    + " ROWS ONLY";
            case TOP -> "SELECT DISTINCT TOP " + rows + " s0." + column + " FROM " + name + " s0";
        };
        return guard(text);
    }

    /**
     * Guards one statement a model wrote.
     *
     * @param text the model's statement
     * @return the rewritten statement that runs, with its parameters, tables, columns and injected clauses
     * @throws GuardViolation with the reason code and the violating fragment; a guard defect is refused too
     *     ({@code UNSUPPORTED} or {@code REWRITE}), never passed through
     */
    public GuardedStatement guard(String text) throws GuardViolation {
        if (text == null || text.isBlank()) {
            throw new GuardViolation(Reason.PARSE, "empty statement");
        }
        if (text.length() > MAX_TEXT) {
            throw new GuardViolation(Reason.SIZE, text.length() + " characters (at most " + MAX_TEXT + ")");
        }
        String stripped = SqlText.strip(text, profile);
        Statement statement = parseOne(stripped);
        try {
            return switch (statement) {
                case Select select -> select(select);
                case Execute execute -> call(execute);
                default -> throw classify(statement);
            };
        } catch (RuntimeException | StackOverflowError e) {
            // Fail closed: a guard defect refuses the statement (the corpus tests flag the unexpected reason).
            throw new GuardViolation(Reason.UNSUPPORTED, "guard error " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------- rule 1

    /** Parses the text in the dialect; exactly one statement. Package-private so that the report can time it. */
    Statement parseOne(String text) throws GuardViolation {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(text, parserThread, profile::configure);
        } catch (JSQLParserException e) {
            throw new GuardViolation(Reason.PARSE, firstLine(e));
        } catch (RuntimeException | Error e) {
            throw new GuardViolation(Reason.PARSE, "parser failure: " + e.getClass().getSimpleName());
        }
        if (statements.size() != 1) {
            throw new GuardViolation(Reason.PARSE, statements.size() + " statements (exactly one is allowed)");
        }
        return statements.get(0);
    }

    private static String firstLine(JSQLParserException e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = String.valueOf(t.getMessage()).replaceAll("\\s+", " ").trim();
        int cut = m.indexOf(" Was expecting");
        m = cut > 0 ? m.substring(0, cut) : m;
        m = m.replace(", in lexical state DEFAULT.", "");
        return m.length() > 160 ? m.substring(0, 157) + "..." : m;
    }

    // ---------------------------------------------------------------------------------------------- rule 2

    private static GuardViolation classify(Statement st) {
        String pkg = st.getClass().getPackageName();
        String name = st.getClass().getSimpleName();
        if (st instanceof UnsupportedStatement) {
            // JSqlParser captures an unknown SHOW, CREATE, ALTER, REPLACE or REFRESH as an UnsupportedStatement even with
            // allowUnsupportedStatements off: it was not parsed, so rule 1 applies.
            String text = st.toString().trim();
            return new GuardViolation(Reason.PARSE, "not parsed (captured as an unsupported statement: "
                    + (text.length() > 40 ? text.substring(0, 37) + "..." : text) + ")");
        }
        String word = name.replace("Statement", "").replaceAll("([a-z])([A-Z])", "$1 $2").toUpperCase(Locale.ROOT);
        if (pkg.endsWith(".insert") || pkg.endsWith(".update") || pkg.endsWith(".delete") || pkg.endsWith(".merge")
                || pkg.endsWith(".upsert") || pkg.endsWith(".imprt") || pkg.endsWith(".export")) {
            return new GuardViolation(Reason.DML, word + " writes");
        }
        if (pkg.contains(".create") || pkg.contains(".alter") || pkg.endsWith(".drop") || pkg.endsWith(".truncate")
                || pkg.endsWith(".grant") || pkg.endsWith(".comment") || pkg.endsWith(".analyze")
                || pkg.endsWith(".refresh") || pkg.endsWith(".role") || name.equals("PurgeStatement")
                || name.equals("CreateFunctionalStatement")) {
            return new GuardViolation(Reason.DDL, word + " changes the schema");
        }
        if (pkg.endsWith(".lock")) {
            return new GuardViolation(Reason.LOCKING, "LOCK");
        }
        if (pkg.endsWith(".oracle") || name.equals("Block") || name.equals("DoStatement")
                || name.equals("DeclareStatement")) {
            return new GuardViolation(Reason.PROCEDURE, "a procedural block");
        }
        return new GuardViolation(Reason.NOT_SELECT, word + " is not a SELECT");
    }

    private GuardedStatement call(Execute ex) throws GuardViolation {
        String name = ex.getName();
        if (ex.getExecType() != Execute.ExecType.CALL) {
            throw new GuardViolation(Reason.PROCEDURE, ex.getExecType() + " " + name
                    + " (only CALL of an allow-listed procedure)");
        }
        boolean listed = false;
        for (String p : allow.procedures()) {
            listed |= p.equalsIgnoreCase(unquoteParts(name));
        }
        if (!listed) {
            throw new GuardViolation(Reason.PROCEDURE, name + " is not an allow-listed procedure");
        }
        int parameters = 0;
        ExpressionList<?> args = ex.getExprList();
        if (args != null) {
            for (Expression a : args) {
                if (!(a instanceof JdbcParameter jp) || jp.isUseFixedIndex()) {
                    throw new GuardViolation(Reason.PROCEDURE, "argument " + a + " of " + name + " is not ?");
                }
                parameters++;
            }
        }
        String sql = outputFilter.apply(deparse(ex));
        try {
            if (!SqlText.strip(sql, profile).equals(sql) || !deparse(parseOne(sql)).equals(sql)) {
                throw new GuardViolation(Reason.REWRITE, "the deparsed CALL does not read back as itself");
            }
        } catch (GuardViolation v) {
            throw v.reason() == Reason.REWRITE ? v : new GuardViolation(Reason.REWRITE, "the deparsed CALL fails "
                    + v.reason() + ": " + v.fragment());
        }
        return new GuardedStatement(sql, parameters, List.of(), List.of(), List.of());
    }

    private static String unquoteParts(String dotted) {
        List<String> parts = new ArrayList<>();
        for (String part : dotted.split("\\.")) {
            parts.add(DialectProfile.unquote(part.trim()));
        }
        return String.join(".", parts);
    }

    // ---------------------------------------------------------------------------------------------- the select

    private GuardedStatement select(Select select) throws GuardViolation {
        if (select instanceof SetOperationList) {
            throw new GuardViolation(Reason.SET_OPERATION, "UNION, INTERSECT, EXCEPT and MINUS are refused");
        }
        if (!(select instanceof PlainSelect ps)) {
            throw new GuardViolation(Reason.NOT_SELECT, select.getClass().getSimpleName() + " is not a plain SELECT");
        }
        Analyzer analyzer = new Analyzer(profile, allow);
        analyzer.select(ps, null, 0, Use.OUTPUT, true);
        List<String> injected = new ArrayList<>();
        completeOrderBy(ps, analyzer, injected);
        limit(ps, injected);
        String sql = outputFilter.apply(deparse(ps));
        return outputCheck(sql, analyzer, injected);
    }

    /**
     * Rule 10: the output (what runs) carries no comment marker, reads back as itself and passes the walk again with
     * the same parameters and tables, reading at least the same columns (the tie-breaker may add key columns). Any
     * failure is {@code REWRITE}: a guard defect, failing closed.
     */
    private GuardedStatement outputCheck(String sql, Analyzer analyzer, List<String> injected) throws GuardViolation {
        try {
            if (!SqlText.strip(sql, profile).equals(sql)) {
                throw new GuardViolation(Reason.REWRITE, "a comment marker appeared in the rewritten text");
            }
            Statement again = parseOne(sql);
            if (!deparse(again).equals(sql)) {
                throw new GuardViolation(Reason.REWRITE, "the rewritten text does not deparse to itself");
            }
            if (!(again instanceof Select select)) {
                throw new GuardViolation(Reason.REWRITE, "the rewritten text is not a SELECT");
            }
            Analyzer check = new Analyzer(profile, allow);
            check.select(select, null, 0, Use.OUTPUT, true);
            if (check.parameters != analyzer.parameters || !check.tablesRead.equals(analyzer.tablesRead)
                    || !check.columnsRead.containsAll(analyzer.columnsRead)) {
                throw new GuardViolation(Reason.REWRITE, "the rewrite changed the parameters, tables or columns");
            }
            return new GuardedStatement(sql, check.parameters, List.copyOf(check.tablesRead),
                    List.copyOf(check.columnsRead), injected);
        } catch (GuardViolation v) {
            throw v.reason() == Reason.REWRITE ? v : new GuardViolation(Reason.REWRITE, "the rewritten text fails "
                    + v.reason() + ": " + v.fragment());
        }
    }

    // ---------------------------------------------------------------------------------------------- rule 7

    /**
     * Completes the ORDER BY with a tie-breaker that makes the order total, in the form the query shape allows: the
     * GROUP BY expressions of a grouped query, the select list of a DISTINCT query, nothing for a single-row aggregate,
     * else the key columns of every table in the FROM clause (each joined table can multiply rows). Section 5.3 says
     * "the key of the driving table"; RESULT.md explains why that is not enough and is invalid SQL in two shapes.
     */
    private void completeOrderBy(PlainSelect ps, Analyzer a, List<String> injected) throws GuardViolation {
        Scope scope = a.outerScope;
        List<SelectItem<?>> items = ps.getSelectItems();
        List<Expression> ties = new ArrayList<>();
        String why;
        if (ps.getGroupBy() != null) {
            for (Expression g : ps.getGroupBy().getGroupByExpressionList()) {
                ties.add(g);
            }
            why = "the GROUP BY columns";
        } else if (Analyzer.singleRowAggregate(ps, a.outerItemInfo)) {
            why = "one row";
        } else if (ps.getDistinct() != null) {
            for (SelectItem<?> item : items) {
                ties.add(item.getAlias() != null ? new Column(item.getAlias().getName()) : item.getExpression());
            }
            why = "the DISTINCT select list";
        } else {
            for (Source s : scope.sources) {
                if (s.key == null) {
                    throw new GuardViolation(Reason.ORDER, "no key can be shown for " + (s.qualifier == null
                            ? "a derived table" : s.qualifier) + "; select its key columns");
                }
                if (s.qualifier == null && !s.key.isEmpty()) {
                    throw new GuardViolation(Reason.ORDER, "a derived table without an alias cannot be ordered by "
                            + "its key");
                }
                for (String k : s.key) {
                    // A column merged by JOIN … USING is written unqualified (Oracle refuses a qualifier on it).
                    ties.add(scope.merged.contains(profile.key(k)) ? new Column(k)
                            : new Column(new Table(s.qualifier), k));
                }
            }
            why = "the key of each table";
        }
        List<OrderByElement> order = ps.getOrderByElements() == null ? new ArrayList<>()
                : new ArrayList<>(ps.getOrderByElements());
        Set<String> present = new HashSet<>();
        for (OrderByElement o : order) {
            present.add(a.canonical(o.getExpression(), scope, items));
        }
        List<String> appended = new ArrayList<>();
        for (Expression t : ties) {
            if (present.add(a.canonical(t, scope, items))) {
                order.add(new OrderByElement().withExpression(t));
                appended.add(t.toString());
            }
        }
        if (!appended.isEmpty()) {
            ps.setOrderByElements(order);
            injected.add("ORDER BY + " + String.join(", ", appended) + " (" + why + ")");
        }
    }

    // ---------------------------------------------------------------------------------------------- rule 8

    /** Reads the model's row limit (one of LIMIT, FETCH FIRST, TOP), caps it and writes it in the dialect's form. */
    private void limit(PlainSelect ps, List<String> injected) throws GuardViolation {
        Long n = null;
        String form = null;
        Limit limit = ps.getLimit();
        if (limit != null) {
            if (limit.isLimitAll() || limit.isLimitNull() || limit.getByExpressions() != null) {
                throw new GuardViolation(Reason.LIMIT, limit.toString().trim());
            }
            n = count(limit.getRowCount(), "LIMIT");
            form = "LIMIT " + n;
        }
        Fetch fetch = ps.getFetch();
        if (fetch != null) {
            if (form != null) {
                throw new GuardViolation(Reason.LIMIT, "two row limits");
            }
            List<String> words = new ArrayList<>();
            for (String w : fetch.getFetchParameters()) {
                words.add(w.toUpperCase(Locale.ROOT));
            }
            if (!words.equals(List.of("ROWS", "ONLY")) && !words.equals(List.of("ROW", "ONLY"))) {
                throw new GuardViolation(Reason.LIMIT, fetch.toString().trim() + " (only … ROWS ONLY)");
            }
            n = fetch.getExpression() == null ? 1L : count(fetch.getExpression(), "FETCH");
            form = "FETCH FIRST " + n + " ROWS ONLY";
        }
        Top top = ps.getTop();
        if (top != null) {
            if (form != null) {
                throw new GuardViolation(Reason.LIMIT, "two row limits");
            }
            if (top.isPercentage() || top.isWithTies()) {
                throw new GuardViolation(Reason.LIMIT, top.toString());
            }
            n = count(top.getExpression(), "TOP");
            form = "TOP " + n;
        }
        long rows = n == null ? ROW_CAP : Math.min(n, ROW_CAP);
        ps.setLimit(null);
        ps.setFetch(null);
        ps.setTop(null);
        String written;
        switch (profile.limitStyle()) {
            case LIMIT -> {
                ps.setLimit(new Limit().withRowCount(new LongValue(rows)));
                written = "LIMIT " + rows;
            }
            case FETCH_FIRST -> {
                Fetch f = new Fetch();
                f.setFetchParamFirst(true);
                f.setExpression(new LongValue(rows));
                f.addFetchParameter("ROWS");
                f.addFetchParameter("ONLY");
                ps.setFetch(f);
                written = "FETCH FIRST " + rows + " ROWS ONLY";
            }
            case TOP -> {
                Top t = new Top();
                t.setExpression(new LongValue(rows));
                ps.setTop(t);
                written = "TOP " + rows;
            }
            default -> throw new IllegalStateException();
        }
        if (n == null) {
            injected.add("row limit injected: " + written);
        } else if (n > ROW_CAP) {
            injected.add("row limit capped: " + form + " -> " + written);
        } else if (!form.equals(written)) {
            injected.add("row limit rewritten: " + form + " -> " + written);
        }
    }

    private static long count(Expression e, String what) throws GuardViolation {
        Expression inner = e;
        if (inner instanceof ExpressionList<?> list && list.size() == 1) {
            inner = list.get(0);
        }
        if (inner instanceof JdbcParameter) {
            throw new GuardViolation(Reason.LIMIT, what + " ?: the row limit must be a number");
        }
        if (!(inner instanceof LongValue v)) {
            throw new GuardViolation(Reason.LIMIT, what + " " + e + ": the row limit must be a number");
        }
        if (v.getValue() < 1) {
            throw new GuardViolation(Reason.LIMIT, what + " " + v.getValue() + ": at least one row");
        }
        return v.getValue();
    }

    // ---------------------------------------------------------------------------------------------- output

    /** The text through JSqlParser's deparser (StatementDeParser), which is what the database receives. */
    static String deparse(Statement statement) {
        StringBuilder out = new StringBuilder();
        statement.accept(new StatementDeParser(out));
        return out.toString();
    }

    /** Stops the parser thread. Idempotent. */
    @Override
    public void close() {
        parserThread.shutdownNow();
    }
}
