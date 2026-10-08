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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import ai.skillroy.testjava.spikes.bs6.GuardViolation.Reason;
import ai.skillroy.testjava.spikes.bs6.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.spikes.bs6.SqlAllowList.AllowedTable;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.AnyComparisonExpression;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DateUnitExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExtractExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.IntervalExpression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.NumericBind;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeKeyExpression;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.TranscodingFunction;
import net.sf.jsqlparser.expression.TrimFunction;
import net.sf.jsqlparser.expression.UserVariable;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Modulo;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression;
import net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.OldOracleJoinBinaryExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ParenthesedStatement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Distinct;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * Walks one parsed SELECT for rules 2 to 6 and 9 of section 5.3 and records what the rewrite (rules 7 and 8) needs.
 * Deny by default: an expression or a clause the walker does not know is refused ({@link Reason#UNSUPPORTED}), and a
 * select, join, table or function whose text differs from the text of a copy that carries only the parts the walker
 * checked is refused too, so that a construct JSqlParser adds in a later release cannot slip through unseen.
 */
final class Analyzer {

    /** Rule 5's built-in set (CASE and CAST are syntax and handled as such). */
    static final Set<String> BASE_FUNCTIONS = Set.of("COUNT", "SUM", "MIN", "MAX", "AVG", "COALESCE", "NULLIF",
            "LOWER", "UPPER", "TRIM", "LENGTH", "SUBSTRING", "ABS", "ROUND", "CURRENT_DATE");
    /** Functions that aggregate rows (the base set's and the common ordered-set ones an allow-list may add). */
    static final Set<String> AGGREGATES = Set.of("COUNT", "SUM", "MIN", "MAX", "AVG", "STRING_AGG", "LISTAGG",
            "GROUP_CONCAT", "ARRAY_AGG", "COUNT_BIG");
    /**
     * Functions whose first argument is a date-part keyword in the dialect, which JSqlParser may parse as a column
     * reference (SQL Server's dd, weekday; HSQLDB's SQL_TSI_DAY). Per dialect: MySQL's DATEDIFF, for one, takes an
     * expression there.
     */
    static Set<String> datePartFunctions(SqlDialect d) {
        return switch (d) {
            case SQLSERVER -> Set.of("DATEADD", "DATEDIFF", "DATEDIFF_BIG", "DATEPART", "DATENAME", "DATETRUNC");
            case HSQLDB -> Set.of("TIMESTAMPADD", "TIMESTAMPDIFF", "DATEADD", "DATEDIFF");
            case MYSQL, MARIADB -> Set.of("TIMESTAMPADD", "TIMESTAMPDIFF");
            default -> Set.of();
        };
    }

    /** Words the dialects read as functions without parentheses; JSqlParser parses some of them as columns. */
    static final Set<String> NILADIC = Set.of("SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
            "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP", "USER", "CURRENT_USER", "SESSION_USER", "SYSTEM_USER",
            "UID", "CURRENT_SCHEMA", "CURRENT_SERVER");

    static final Set<String> DATE_PARTS = Set.of("YEAR", "YY", "YYYY", "QUARTER", "QQ", "Q", "MONTH", "MM", "M",
            "DAYOFYEAR", "DY", "Y", "DAY", "DD", "D", "WEEK", "WK", "WW", "WEEKDAY", "DW", "HOUR", "HH", "MINUTE", "MI",
            "N", "SECOND", "SS", "S", "MILLISECOND", "MS", "SQL_TSI_YEAR", "SQL_TSI_QUARTER", "SQL_TSI_MONTH",
            "SQL_TSI_WEEK", "SQL_TSI_DAY", "SQL_TSI_HOUR", "SQL_TSI_MINUTE", "SQL_TSI_SECOND",
            "SQL_TSI_FRAC_SECOND");
    /** Target types a CAST may name (no catalogue types such as PostgreSQL's regclass, no LOBs, no XML). */
    static final Set<String> CAST_TYPES = Set.of("CHAR", "CHARACTER", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR",
            "NVARCHAR2", "TEXT", "INT", "INTEGER", "SMALLINT", "BIGINT", "TINYINT", "DECIMAL", "DEC", "NUMERIC",
            "NUMBER", "REAL", "FLOAT", "DOUBLE", "DOUBLE PRECISION", "DATE", "TIME", "TIMESTAMP", "DATETIME",
            "DATETIME2", "BOOLEAN", "BIT", "SIGNED", "UNSIGNED", "MONEY");

    static final int MAX_DEPTH = 3;
    static final int MAX_TABLES = 8;
    static final int MAX_SELECT_ITEMS = 20;
    static final int MAX_STRING = 200;

    /** Where the values of a select list go: out of the statement, into a derived relation, or into a filter. */
    enum Use { OUTPUT, DERIVED, FILTER }

    /** What an expression reads: a personal column (directly or through a derived column), an aggregate, a column
     *  outside any aggregate. */
    record Info(boolean personal, boolean aggregate, boolean bare) {
        static final Info NONE = new Info(false, false, false);

        Info or(Info other) {
            return new Info(personal || other.personal, aggregate || other.aggregate, bare || other.bare);
        }
    }

    /** A column a relation offers; {@code origin} is "table.column" for an allow-listed column. */
    record Col(String name, boolean personal, String origin) { }

    /** What a SELECT produces: its columns and the output names that form a unique key (null: unknown). */
    record Relation(List<Col> columns, List<String> key) { }

    /** A relation in a FROM clause, under the name that qualifies it. */
    static final class Source {
        final int id;
        final String qualifier;
        final String table;
        final List<Col> columns;
        final List<String> key;

        Source(int id, String qualifier, String table, List<Col> columns, List<String> key) {
            this.id = id;
            this.qualifier = qualifier;
            this.table = table;
            this.columns = columns;
            this.key = key;
        }
    }

    static final class Scope {
        final Scope parent;
        final Map<String, Relation> ctes;
        final List<Source> sources = new ArrayList<>();
        /** Names merged by JOIN … USING: unqualified, they name one column, not an ambiguous pair. */
        final Set<String> merged = new TreeSet<>();

        Scope(Scope parent, Map<String, Relation> ctes) {
            this.parent = parent;
            this.ctes = ctes;
        }

        Relation cte(String key) {
            for (Scope s = this; s != null; s = s.parent) {
                Relation r = s.ctes.get(key);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }
    }

    /** A column reference resolved to its source. */
    record Ref(Source source, Col col) {
        String canonical() {
            return "#" + source.id + "." + col.name().toUpperCase(Locale.ROOT);
        }
    }

    private final DialectProfile p;
    private final SqlAllowList allow;
    private final Set<String> functions = new TreeSet<>();

    final TreeSet<String> tablesRead = new TreeSet<>();
    final TreeSet<String> columnsRead = new TreeSet<>();
    int parameters;
    private int tableReferences;
    private int sourceIds;

    /** The outermost select's scope and what each of its select items reads, for the rewrite. */
    Scope outerScope;
    List<Info> outerItemInfo;

    Analyzer(DialectProfile p, SqlAllowList allow) {
        this.p = p;
        this.allow = allow;
        functions.addAll(BASE_FUNCTIONS);
        for (String f : allow.functions()) {
            functions.add(f.toUpperCase(Locale.ROOT));
        }
    }

    // ---------------------------------------------------------------------------------------------- selects

    Relation select(Select s, Scope parent, int depth, Use use, boolean outer) throws GuardViolation {
        if (depth > MAX_DEPTH) {
            throw new GuardViolation(Reason.SIZE, "more than " + MAX_DEPTH + " nested subqueries");
        }
        if (s instanceof SetOperationList) {
            throw new GuardViolation(Reason.SET_OPERATION, "UNION, INTERSECT, EXCEPT and MINUS are refused");
        }
        if (s instanceof LateralSubSelect) {
            throw new GuardViolation(Reason.UNSUPPORTED, "LATERAL");
        }
        if (s instanceof ParenthesedSelect ps) {
            if ((ps.getOrderByElements() != null && !ps.getOrderByElements().isEmpty()) || ps.getLimit() != null
                    || ps.getFetch() != null || ps.getOffset() != null
                    || (ps.getWithItemsList() != null && !ps.getWithItemsList().isEmpty())) {
                throw new GuardViolation(Reason.UNSUPPORTED, "clauses outside a parenthesised select");
            }
            return select(ps.getSelect(), parent, depth, use, outer);
        }
        if (!(s instanceof PlainSelect ps)) {
            throw new GuardViolation(Reason.NOT_SELECT, s.getClass().getSimpleName() + " is not a plain SELECT");
        }
        checkClauses(ps, outer);

        // WITH: each CTE sees the ones before it; none may shadow an allow-listed table.
        Map<String, Relation> ctes = new LinkedHashMap<>();
        Scope scope = new Scope(parent, ctes);
        if (ps.getWithItemsList() != null) {
            for (WithItem<?> wi : ps.getWithItemsList()) {
                ctes.put(p.key(wi.getAlias().getName()), cte(wi, scope, depth));
            }
        }

        // FROM and joins.
        if (ps.getFromItem() != null) {
            addSource(scope, ps.getFromItem(), depth);
        }
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                checkJoin(j);
                addSource(scope, j.getRightItem(), depth);
            }
            for (Join j : ps.getJoins()) {
                for (Expression on : j.getOnExpressions()) {
                    expr(on, scope, Use.FILTER, depth);
                }
                if (j.getUsingColumns() != null) {
                    for (Column c : j.getUsingColumns()) {
                        usingColumn(c, scope);
                        scope.merged.add(p.key(c.getColumnName()));
                    }
                }
            }
        }

        // The select list.
        List<SelectItem<?>> items = ps.getSelectItems();
        if (items.size() > MAX_SELECT_ITEMS) {
            throw new GuardViolation(Reason.SIZE, items.size() + " columns in a select list (at most "
                    + MAX_SELECT_ITEMS + ")");
        }
        List<Col> out = new ArrayList<>();
        List<Info> infos = new ArrayList<>();
        for (SelectItem<?> item : items) {
            Expression e = item.getExpression();
            if (e instanceof AllColumns) {
                throw new GuardViolation(Reason.STAR, e + " is refused; name the columns");
            }
            Info info = expr(e, scope, use, depth);
            if (use == Use.OUTPUT && info.personal()) {
                throw new GuardViolation(Reason.PERSONAL, "a personal column in the select list: " + item);
            }
            // Output names are kept as written (quotes included), so that a tie-breaker naming one is valid SQL.
            String name = item.getAlias() != null ? item.getAlias().getName()
                    : e instanceof Column c ? c.getColumnName() : null;
            String origin = e instanceof Column c ? origin(resolve(c, scope)) : null;
            out.add(new Col(name, info.personal(), origin));
            infos.add(info);
        }

        if (ps.getWhere() != null) {
            expr(ps.getWhere(), scope, Use.FILTER, depth);
        }
        GroupByElement groupBy = ps.getGroupBy();
        if (groupBy != null) {
            checkGroupBy(groupBy);
            for (Expression g : groupBy.getGroupByExpressionList()) {
                expr(g, scope, Use.FILTER, depth);
            }
        }
        if (ps.getHaving() != null) {
            expr(ps.getHaving(), scope, Use.FILTER, depth);
        }
        if (outer && ps.getOrderByElements() != null) {
            for (OrderByElement o : ps.getOrderByElements()) {
                orderItem(o, scope, items, depth);
            }
        }
        if (outer) {
            outerScope = scope;
            outerItemInfo = infos;
        }
        return new Relation(out, outer ? null : derivedKey(ps, scope, items, out, infos));
    }

    private Relation cte(WithItem<?> wi, Scope scope, int depth) throws GuardViolation {
        String name = wi.getAlias().getName();
        if (allowedTable(name) != null) {
            throw new GuardViolation(Reason.TABLE, "WITH " + name + " shadows the allow-listed table " + name);
        }
        if (wi.isRecursive()) {
            throw new GuardViolation(Reason.UNSUPPORTED, "WITH RECURSIVE");
        }
        if (wi.getWithFunctionDeclaration() != null || wi.getSearchClause() != null || wi.getCycleClause() != null
                || wi.isMaterialized()) {
            throw new GuardViolation(Reason.UNSUPPORTED, "WITH " + name + " clauses");
        }
        ParenthesedStatement body = wi.getParenthesedStatement();
        if (!(body instanceof ParenthesedSelect select)) {
            throw new GuardViolation(Reason.DML, "WITH " + name + " AS (" + body.getClass().getSimpleName()
                    .replace("Parenthesed", "").toUpperCase(Locale.ROOT) + " …)");
        }
        Relation r = select(select, scope, depth + 1, Use.DERIVED, false);
        if (wi.getWithItemList() == null) {
            return r;
        }
        // WITH x (a, b) AS (…): the column list renames the outputs, key included.
        List<SelectItem<?>> names = wi.getWithItemList();
        if (names.size() != r.columns().size()) {
            throw new GuardViolation(Reason.COLUMN, "WITH " + name + " names " + names.size() + " columns, its "
                    + "select has " + r.columns().size());
        }
        List<Col> renamed = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Col c = r.columns().get(i);
            renamed.add(new Col(names.get(i).toString(), c.personal(), c.origin()));
        }
        List<String> key = null;
        if (r.key() != null) {
            key = new ArrayList<>();
            for (String k : r.key()) {
                for (int i = 0; i < r.columns().size(); i++) {
                    if (k.equals(r.columns().get(i).name())) {
                        key.add(renamed.get(i).name());
                        break;
                    }
                }
            }
        }
        return new Relation(renamed, key);
    }

    private void addSource(Scope scope, FromItem item, int depth) throws GuardViolation {
        switch (item) {
            case Table t -> scope.sources.add(tableSource(t, scope));
            case LateralSubSelect l -> throw new GuardViolation(Reason.UNSUPPORTED, "LATERAL");
            case TableFunction tf -> throw new GuardViolation(Reason.FUNCTION, "table function in FROM: " + tf);
            case ParenthesedSelect sub -> {
                if (sub.getPivot() != null || sub.getUnPivot() != null) {
                    throw new GuardViolation(Reason.UNSUPPORTED, "PIVOT");
                }
                // A derived table sees the CTEs and the enclosing queries, not its sibling FROM items.
                Relation r = select(sub, new Scope(scope.parent, scope.ctes), depth + 1, Use.DERIVED, false);
                String alias = sub.getAlias() == null ? null : sub.getAlias().getName();
                scope.sources.add(new Source(++sourceIds, alias, null, r.columns(), r.key()));
            }
            default -> throw new GuardViolation(Reason.UNSUPPORTED, "FROM item " + item.getClass().getSimpleName()
                    + ": " + item);
        }
    }

    private Source tableSource(Table t, Scope scope) throws GuardViolation {
        String alias = t.getAlias() == null ? null : t.getAlias().getName();
        if (t.getAlias() != null && t.getAlias().getAliasColumns() != null) {
            throw new GuardViolation(Reason.UNSUPPORTED, "column aliases on a table: " + t);
        }
        if (t.getSqlServerHints() != null || t.getIndexHint() != null) {
            throw new GuardViolation(Reason.LOCKING, "table hint: " + t);
        }
        // Deny by default: the table must print as its name and alias, nothing else (sample, time travel, …).
        String plain = t.getFullyQualifiedName() + (t.getAlias() == null ? "" : t.getAlias().toString());
        if (!plain.equals(t.toString())) {
            throw new GuardViolation(Reason.UNSUPPORTED, "table decoration: " + t);
        }
        if (t.getNameParts().size() > 1) {
            throw new GuardViolation(Reason.TABLE, t.getFullyQualifiedName() + ": a qualified name is not on the "
                    + "allow-list");
        }
        // Table.getName() drops an Oracle database link (item@remote is named "item") and getDBLinkName() returns the
        // table's own name when there is no link, so the raw name part is checked.
        if (t.getNameParts().getFirst().contains("@")) {
            throw new GuardViolation(Reason.TABLE, t.getFullyQualifiedName() + ": a table over a database link");
        }
        String name = t.getName();
        Relation cte = scope.cte(p.key(name));
        if (cte != null) {
            return new Source(++sourceIds, alias == null ? name : alias, null, cte.columns(), cte.key());
        }
        if (p.dualTables().contains(DialectProfile.unquote(name).toUpperCase(Locale.ROOT))
                && DialectProfile.unquote(name).equals(name)) {
            return new Source(++sourceIds, alias == null ? name : alias, null, List.of(), List.of());
        }
        AllowedTable at = allowedTable(name);
        if (at == null) {
            throw new GuardViolation(Reason.TABLE, name + " is not on the allow-list");
        }
        if (++tableReferences > MAX_TABLES) {
            throw new GuardViolation(Reason.SIZE, "more than " + MAX_TABLES + " table references");
        }
        tablesRead.add(at.name());
        List<Col> cols = new ArrayList<>();
        for (AllowedColumn c : at.columns()) {
            cols.add(new Col(c.name(), c.personal(), at.name() + "." + c.name()));
        }
        return new Source(++sourceIds, alias == null ? name : alias, at.name(), cols, at.key());
    }

    private AllowedTable allowedTable(String written) {
        for (AllowedTable at : allow.tables()) {
            if (p.names(written, at.name())) {
                return at;
            }
        }
        return null;
    }

    private void checkClauses(PlainSelect ps, boolean outer) throws GuardViolation {
        if (ps.getIntoTables() != null || ps.getIntoTempTable() != null || ps.getMySqlSelectIntoClause() != null) {
            throw new GuardViolation(Reason.DML, "SELECT … INTO writes");
        }
        if (ps.getForMode() != null || ps.getForUpdateTable() != null
                || (ps.getForUpdateTables() != null && !ps.getForUpdateTables().isEmpty()) || ps.getWait() != null
                || ps.isNoWait() || ps.isSkipLocked()) {
            throw new GuardViolation(Reason.LOCKING, "FOR " + (ps.getForMode() == null ? "…"
                    : ps.getForMode().getValue()));
        }
        if (ps.getIsolation() != null) {
            throw new GuardViolation(Reason.LOCKING, "isolation clause" + ps.getIsolation());
        }
        if (ps.getOracleHint() != null) {
            throw new GuardViolation(Reason.COMMENT, "optimizer hint survived: " + ps.getOracleHint());
        }
        if (ps.getOffset() != null || (ps.getLimit() != null && ps.getLimit().getOffset() != null)) {
            throw new GuardViolation(Reason.LIMIT, "OFFSET is refused");
        }
        if (!outer && ps.getOrderByElements() != null && !ps.getOrderByElements().isEmpty()) {
            throw new GuardViolation(Reason.ORDER, "ORDER BY inside a subquery or a CTE");
        }
        if (!outer && (ps.getLimit() != null || ps.getFetch() != null || ps.getTop() != null)) {
            throw new GuardViolation(Reason.LIMIT, "a row limit inside a subquery or a CTE");
        }
        Distinct d = ps.getDistinct();
        if (d != null && (d.getOnSelectItems() != null || d.isUseUnique() || d.isUseDistinctRow())) {
            throw new GuardViolation(Reason.UNSUPPORTED, d.toString());
        }
        // Deny by default: a copy with only the clauses the walker checks must print the same.
        PlainSelect copy = new PlainSelect();
        copy.setWithItemsList(ps.getWithItemsList());
        copy.setDistinct(ps.getDistinct());
        copy.setTop(ps.getTop());
        copy.setSelectItems(ps.getSelectItems());
        copy.setFromItem(ps.getFromItem());
        copy.setJoins(ps.getJoins());
        copy.setWhere(ps.getWhere());
        copy.setGroupByElement(ps.getGroupBy());
        copy.setHaving(ps.getHaving());
        copy.setOrderByElements(ps.getOrderByElements());
        copy.setLimit(ps.getLimit());
        copy.setFetch(ps.getFetch());
        String original = ps.toString();
        String checked = copy.toString();
        if (!original.equals(checked)) {
            throw new GuardViolation(Reason.UNSUPPORTED, "clause outside the guard's grammar: "
                    + difference(original, checked));
        }
    }

    private static void checkJoin(Join j) throws GuardViolation {
        if (j.isNatural() || j.isApply() || j.isStraight() || j.isSemi() || j.isWindowJoin() || j.isGlobal()
                || j.isAsOf() || j.getJoinHint() != null) {
            throw new GuardViolation(Reason.UNSUPPORTED, "join form: " + j);
        }
        Join copy = new Join();
        copy.setSimple(j.isSimple());
        copy.setInner(j.isInner());
        copy.setLeft(j.isLeft());
        copy.setRight(j.isRight());
        copy.setFull(j.isFull());
        copy.setOuter(j.isOuter());
        copy.setCross(j.isCross());
        copy.setRightItem(j.getRightItem());
        copy.setOnExpressions(j.getOnExpressions());
        copy.setUsingColumns(j.getUsingColumns());
        if (!copy.toString().equals(j.toString())) {
            throw new GuardViolation(Reason.UNSUPPORTED, "join form: " + j);
        }
    }

    private static void checkGroupBy(GroupByElement g) throws GuardViolation {
        if ((g.getGroupingSets() != null && !g.getGroupingSets().isEmpty()) || g.isMysqlWithRollup()) {
            throw new GuardViolation(Reason.UNSUPPORTED, "GROUPING SETS, ROLLUP or CUBE");
        }
        GroupByElement copy = new GroupByElement();
        copy.setGroupByExpressions(g.getGroupByExpressionList());
        if (!copy.toString().equals(g.toString())) {
            throw new GuardViolation(Reason.UNSUPPORTED, "GROUP BY form: " + g);
        }
    }

    private void orderItem(OrderByElement o, Scope scope, List<SelectItem<?>> items, int depth)
            throws GuardViolation {
        OrderByElement copy = new OrderByElement();
        copy.setExpression(o.getExpression());
        copy.setAsc(o.isAsc());
        copy.setAscDescPresent(o.isAscDescPresent());
        copy.setNullOrdering(o.getNullOrdering());
        if (!copy.toString().equals(o.toString())) {
            throw new GuardViolation(Reason.ORDER, "ORDER BY form: " + o);
        }
        Expression e = o.getExpression();
        if (e instanceof LongValue ordinal) {
            if (ordinal.getValue() < 1 || ordinal.getValue() > items.size()) {
                throw new GuardViolation(Reason.ORDER, "ORDER BY " + ordinal + " names no select-list column");
            }
            return;
        }
        if (e instanceof Column c && c.getTable() == null && aliasIndex(c.getColumnName(), items) >= 0
                && resolveQuietly(c, scope) == null) {
            return;
        }
        if (e instanceof StringValue || e instanceof JdbcParameter || e instanceof NullValue) {
            throw new GuardViolation(Reason.ORDER, "ORDER BY a constant: " + e);
        }
        expr(e, scope, Use.FILTER, depth);
    }

    static int aliasIndex(String name, List<SelectItem<?>> items) {
        String bare = DialectProfile.unquote(name);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getAlias() != null
                    && DialectProfile.unquote(items.get(i).getAlias().getName()).equalsIgnoreCase(bare)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The output names that identify a row of a derived relation, or null when they cannot be shown: the grouping
     * columns when grouped, every column when DISTINCT, nothing for a single-row aggregate, else the key columns of
     * every source (each must be selected).
     */
    private List<String> derivedKey(PlainSelect ps, Scope scope, List<SelectItem<?>> items, List<Col> out,
                                    List<Info> infos) {
        List<String> key = new ArrayList<>();
        if (ps.getGroupBy() != null) {
            for (Expression g : ps.getGroupBy().getGroupByExpressionList()) {
                int i = itemIndexOf(g, scope, items);
                if (i < 0 || out.get(i).name() == null) {
                    return null;
                }
                key.add(out.get(i).name());
            }
            return key;
        }
        if (singleRowAggregate(ps, infos)) {
            return key;
        }
        if (ps.getDistinct() != null) {
            for (Col c : out) {
                if (c.name() == null) {
                    return null;
                }
                key.add(c.name());
            }
            return key;
        }
        for (Source s : scope.sources) {
            if (s.key == null) {
                return null;
            }
            for (String k : s.key) {
                Column ref = new Column(new Table(s.qualifier), k);
                int i = itemIndexOf(ref, scope, items);
                if (i < 0 || out.get(i).name() == null) {
                    return null;
                }
                key.add(out.get(i).name());
            }
        }
        return key;
    }

    /** No GROUP BY, at least one aggregate in the select list and no column outside an aggregate: one row. */
    static boolean singleRowAggregate(PlainSelect ps, List<Info> infos) {
        if (ps.getGroupBy() != null) {
            return false;
        }
        boolean aggregate = false;
        for (Info i : infos) {
            if (i.bare()) {
                return false;
            }
            aggregate |= i.aggregate();
        }
        return aggregate;
    }

    /** The index of the select item whose expression is the same column or expression, or -1. */
    int itemIndexOf(Expression e, Scope scope, List<SelectItem<?>> items) {
        String key = canonical(e, scope, items);
        for (int i = 0; i < items.size(); i++) {
            if (canonical(items.get(i).getExpression(), scope, items).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** A comparison key: a column by its resolved source, an alias or ordinal by its select item, else the text. */
    String canonical(Expression e, Scope scope, List<SelectItem<?>> items) {
        return canonical(e, scope, items, true);
    }

    private String canonical(Expression e, Scope scope, List<SelectItem<?>> items, boolean followItems) {
        if (followItems && e instanceof LongValue ordinal && ordinal.getValue() >= 1
                && ordinal.getValue() <= items.size()) {
            return canonical(items.get((int) ordinal.getValue() - 1).getExpression(), scope, items, false);
        }
        if (e instanceof Column c) {
            Ref r = resolveQuietly(c, scope);
            if (r != null) {
                return r.canonical();
            }
            int i = followItems && c.getTable() == null ? aliasIndex(c.getColumnName(), items) : -1;
            if (i >= 0) {
                return canonical(items.get(i).getExpression(), scope, items, false);
            }
        }
        return e.toString().toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------------------------------------- columns

    private void usingColumn(Column c, Scope scope) throws GuardViolation {
        boolean found = false;
        for (Source s : scope.sources) {
            Col col = find(s, c.getColumnName());
            if (col != null) {
                found = true;
                if (col.origin() != null) {
                    columnsRead.add(col.origin());
                }
            }
        }
        if (!found) {
            throw new GuardViolation(Reason.COLUMN, "USING (" + c + ") names no allow-listed column");
        }
    }

    private Info column(Column c, Scope scope) throws GuardViolation {
        Ref r = resolve(c, scope);
        if (r == null) {
            String name = DialectProfile.unquote(c.getColumnName()).toUpperCase(Locale.ROOT);
            if (c.getTable() == null && name.equals("ROWNUM")) {
                throw new GuardViolation(Reason.LIMIT, "ROWNUM limits rows before ORDER BY; the guard writes the "
                        + "row limit");
            }
            if (c.getTable() == null && DialectProfile.unquote(c.getColumnName()).equals(c.getColumnName())
                    && NILADIC.contains(name)) {
                // A function written as a bare word (Oracle's SYSDATE): only these known words, so that a column the
                // allow-list hides can never be read under the name of an allowed function.
                requireFunction(name);
                return Info.NONE;
            }
            throw new GuardViolation(Reason.COLUMN, c + " does not resolve to an allow-listed column");
        }
        if (r.col().origin() != null) {
            columnsRead.add(r.col().origin());
        }
        return new Info(r.col().personal(), false, true);
    }

    private Ref resolveQuietly(Column c, Scope scope) {
        try {
            return resolve(c, scope);
        } catch (GuardViolation e) {
            return null;
        }
    }

    /** Resolves a column through aliases, CTEs and enclosing scopes; null when nothing in scope has that name. */
    Ref resolve(Column c, Scope scope) throws GuardViolation {
        Table qualifier = c.getTable();
        String colName = c.getColumnName();
        if (qualifier != null && qualifier.getName() != null && !qualifier.getName().isEmpty()) {
            if (qualifier.getNameParts().size() > 1) {
                throw new GuardViolation(Reason.COLUMN, c + ": qualified beyond the table");
            }
            String q = p.key(qualifier.getName());
            for (Scope s = scope; s != null; s = s.parent) {
                for (Source src : s.sources) {
                    if (src.qualifier != null && p.key(src.qualifier).equals(q)) {
                        Col col = find(src, colName);
                        if (col == null) {
                            throw new GuardViolation(Reason.COLUMN, c + " is not an allow-listed column of "
                                    + (src.table != null ? src.table : src.qualifier));
                        }
                        return new Ref(src, col);
                    }
                }
            }
            throw new GuardViolation(Reason.COLUMN, c + ": no table or alias " + qualifier.getName() + " in scope");
        }
        // An unqualified name resolves in the innermost FROM only. The database looks for it among all the columns of
        // the inner tables before it looks outward, and the guard knows only their allow-listed columns: falling back
        // to an enclosing query could bind, in the guard's view, to an allowed outer column while the database binds
        // to an unlisted inner one. A correlated reference must therefore be qualified.
        Ref found = null;
        for (Source src : scope.sources) {
            Col col = find(src, colName);
            if (col != null) {
                if (found != null) {
                    if (scope.merged.contains(p.key(colName))) {
                        return found; // JOIN … USING merged it into one column
                    }
                    throw new GuardViolation(Reason.COLUMN, colName + " is ambiguous; qualify it");
                }
                found = new Ref(src, col);
            }
        }
        return found;
    }

    /** The column of a source with this name: an allow-listed one by its declared name, a derived one as written. */
    private Col find(Source src, String written) {
        for (Col col : src.columns) {
            if (col.name() == null) {
                continue;
            }
            if (src.table != null ? p.names(written, col.name()) : p.key(written).equals(p.key(col.name()))) {
                return col;
            }
        }
        return null;
    }

    private static String origin(Ref r) {
        return r == null ? null : r.col().origin();
    }

    // ---------------------------------------------------------------------------------------------- expressions

    Info expr(Expression e, Scope scope, Use use, int depth) throws GuardViolation {
        return switch (e) {
            case null -> Info.NONE;
            case Column c -> column(c, scope);
            case LongValue v -> Info.NONE;
            case DoubleValue v -> Info.NONE;
            case NullValue v -> Info.NONE;
            case BooleanValue v -> Info.NONE;
            case StringValue v -> string(v);
            case HexValue v -> throw new GuardViolation(Reason.LITERAL, "hexadecimal literal " + v);
            case DateValue v -> throw new GuardViolation(Reason.LITERAL, "JDBC escape literal " + v);
            case TimeValue v -> throw new GuardViolation(Reason.LITERAL, "JDBC escape literal " + v);
            case TimestampValue v -> throw new GuardViolation(Reason.LITERAL, "JDBC escape literal " + v);
            case JdbcParameter jp -> parameter(jp);
            case JdbcNamedParameter jp -> throw new GuardViolation(Reason.LITERAL, "named parameter " + jp
                    + "; write ?");
            case NumericBind b -> throw new GuardViolation(Reason.LITERAL, "numbered bind " + b + "; write ?");
            case UserVariable u when u.isDoubleAdd() -> throw new GuardViolation(Reason.FUNCTION,
                    "system variable " + u);
            case UserVariable u -> throw new GuardViolation(Reason.LITERAL, "variable " + u + "; write ?");
            case NextValExpression n -> throw new GuardViolation(Reason.FUNCTION, "sequence " + n);
            case DateTimeLiteralExpression d -> typedLiteral(d.getValue());
            case IntervalExpression i -> interval(i, scope, use, depth);
            case TimeKeyExpression t -> timeKey(t);
            case SignedExpression s -> expr(s.getExpression(), scope, use, depth);
            case ExpressionList<?> list -> list(list, scope, use, depth);
            case Addition b -> expr(b.getLeftExpression(), scope, use, depth)
                    .or(expr(b.getRightExpression(), scope, use, depth));
            case Subtraction b -> expr(b.getLeftExpression(), scope, use, depth)
                    .or(expr(b.getRightExpression(), scope, use, depth));
            case Multiplication b -> expr(b.getLeftExpression(), scope, use, depth)
                    .or(expr(b.getRightExpression(), scope, use, depth));
            case Division b -> expr(b.getLeftExpression(), scope, use, depth)
                    .or(expr(b.getRightExpression(), scope, use, depth));
            case Modulo b -> expr(b.getLeftExpression(), scope, use, depth)
                    .or(expr(b.getRightExpression(), scope, use, depth));
            case Concat b -> expr(b.getLeftExpression(), scope, use, depth)
                    .or(expr(b.getRightExpression(), scope, use, depth));
            case AndExpression b -> expr(b.getLeftExpression(), scope, use, depth)
                    .or(expr(b.getRightExpression(), scope, use, depth));
            case OrExpression b -> expr(b.getLeftExpression(), scope, use, depth)
                    .or(expr(b.getRightExpression(), scope, use, depth));
            case EqualsTo b -> comparison(b, scope, use, depth);
            case NotEqualsTo b -> comparison(b, scope, use, depth);
            case GreaterThan b -> comparison(b, scope, use, depth);
            case GreaterThanEquals b -> comparison(b, scope, use, depth);
            case MinorThan b -> comparison(b, scope, use, depth);
            case MinorThanEquals b -> comparison(b, scope, use, depth);
            case LikeExpression l -> like(l, scope, use, depth);
            case Between b -> expr(b.getLeftExpression(), scope, use, depth)
                    .or(expr(b.getBetweenExpressionStart(), scope, use, depth))
                    .or(expr(b.getBetweenExpressionEnd(), scope, use, depth));
            case InExpression in -> in(in, scope, use, depth);
            case IsNullExpression n -> expr(n.getLeftExpression(), scope, use, depth);
            case IsBooleanExpression n -> expr(n.getLeftExpression(), scope, use, depth);
            case IsDistinctExpression d -> expr(d.getLeftExpression(), scope, use, depth)
                    .or(expr(d.getRightExpression(), scope, use, depth));
            case NotExpression n -> expr(n.getExpression(), scope, use, depth);
            case ExistsExpression ex when ex.getRightExpression() instanceof Select sub ->
                    subquery(sub, scope, Use.FILTER, depth);
            case AnyComparisonExpression any when any.getSelect() != null ->
                    subquery(any.getSelect(), scope, Use.FILTER, depth);
            // A scalar subquery's value goes where the enclosing expression's goes (the taint follows it).
            case ParenthesedSelect sub -> subquery(sub, scope, use, depth);
            case CaseExpression ce -> caseExpression(ce, scope, use, depth);
            case CastExpression ce -> cast(ce, scope, use, depth);
            case TranscodingFunction tf -> convert(tf, scope, use, depth);
            case TrimFunction tf -> {
                requireFunction("TRIM");
                yield expr(tf.getExpression(), scope, use, depth).or(expr(tf.getFromExpression(), scope, use, depth));
            }
            case ExtractExpression x -> {
                requireFunction("EXTRACT");
                yield expr(x.getExpression(), scope, use, depth);
            }
            case AnalyticExpression a -> throw new GuardViolation(Reason.FUNCTION, "analytic form of " + a.getName()
                    + " (" + a.getType() + ")");
            case Function f -> function(f, scope, use, depth);
            default -> throw new GuardViolation(Reason.UNSUPPORTED, "expression "
                    + e.getClass().getSimpleName() + ": " + e);
        };
    }

    private Info list(ExpressionList<?> list, Scope scope, Use use, int depth) throws GuardViolation {
        Info acc = Info.NONE;
        for (Expression item : list) {
            acc = acc.or(expr(item, scope, use, depth));
        }
        return acc;
    }

    private Info string(StringValue v) throws GuardViolation {
        String prefix = v.getPrefix();
        if (prefix != null && !prefix.equalsIgnoreCase("N")) {
            throw new GuardViolation(Reason.LITERAL, "string literal with prefix " + prefix + ": " + v);
        }
        if (v.toString().startsWith("\"") || v.toString().startsWith("$")) {
            throw new GuardViolation(Reason.LITERAL, "string literal not in single quotes: " + v);
        }
        if (v.getValue().length() > MAX_STRING) {
            throw new GuardViolation(Reason.LITERAL, "a string literal of " + v.getValue().length()
                    + " characters (at most " + MAX_STRING + ")");
        }
        return Info.NONE;
    }

    /** DATE '…', TIME '…', TIMESTAMP '…': allowed (rule 6 as amended), bounded like a string. */
    private Info typedLiteral(String value) throws GuardViolation {
        if (value != null && value.length() > MAX_STRING + 2) {
            throw new GuardViolation(Reason.LITERAL, "a literal longer than " + MAX_STRING + " characters");
        }
        return Info.NONE;
    }

    private Info parameter(JdbcParameter jp) throws GuardViolation {
        if (jp.isUseFixedIndex()) {
            throw new GuardViolation(Reason.LITERAL, "numbered parameter " + jp + "; write ?");
        }
        parameters++;
        return Info.NONE;
    }

    private Info interval(IntervalExpression i, Scope scope, Use use, int depth) throws GuardViolation {
        if (i.getParameter() != null && i.getParameter().length() > MAX_STRING) {
            throw new GuardViolation(Reason.LITERAL, "an interval literal longer than " + MAX_STRING + " characters");
        }
        return expr(i.getExpression(), scope, use, depth);
    }

    private Info timeKey(TimeKeyExpression t) throws GuardViolation {
        String name = t.getStringValue().trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (name.endsWith("()")) {
            name = name.substring(0, name.length() - 2);
        }
        requireFunction(name);
        return Info.NONE;
    }

    private Info comparison(OldOracleJoinBinaryExpression b, Scope scope, Use use, int depth)
            throws GuardViolation {
        if (b.getOldOracleJoinSyntax() != OldOracleJoinBinaryExpression.NO_ORACLE_JOIN && !p.oracleOuterJoin()) {
            throw new GuardViolation(Reason.UNSUPPORTED, "(+) outer join outside Oracle: " + b);
        }
        if (b.getOraclePriorPosition() != OldOracleJoinBinaryExpression.NO_ORACLE_PRIOR) {
            throw new GuardViolation(Reason.UNSUPPORTED, "PRIOR: " + b);
        }
        return expr(b.getLeftExpression(), scope, use, depth).or(expr(b.getRightExpression(), scope, use, depth));
    }

    private Info like(LikeExpression l, Scope scope, Use use, int depth) throws GuardViolation {
        LikeExpression.KeyWord k = l.getLikeKeyWord();
        if (k != LikeExpression.KeyWord.LIKE && k != LikeExpression.KeyWord.ILIKE) {
            throw new GuardViolation(Reason.FUNCTION, "pattern operator " + k + " (regular expressions are refused)");
        }
        if (k == LikeExpression.KeyWord.ILIKE && p.dialect() != SqlDialect.POSTGRESQL) {
            throw new GuardViolation(Reason.UNSUPPORTED, "ILIKE outside PostgreSQL");
        }
        return expr(l.getLeftExpression(), scope, use, depth).or(expr(l.getRightExpression(), scope, use, depth))
                .or(expr(l.getEscape(), scope, use, depth));
    }

    private Info in(InExpression in, Scope scope, Use use, int depth) throws GuardViolation {
        if (in.getOldOracleJoinSyntax() != OldOracleJoinBinaryExpression.NO_ORACLE_JOIN) {
            throw new GuardViolation(Reason.UNSUPPORTED, "(+) in IN: " + in);
        }
        Info left = expr(in.getLeftExpression(), scope, use, depth);
        Expression right = in.getRightExpression();
        if (right instanceof Select sub) {
            return left.or(subquery(sub, scope, Use.FILTER, depth));
        }
        return left.or(expr(right, scope, use, depth));
    }

    private Info subquery(Select sub, Scope scope, Use use, int depth) throws GuardViolation {
        Relation r = select(sub, scope, depth + 1, use, false);
        boolean personal = false;
        for (Col c : r.columns()) {
            personal |= c.personal();
        }
        return new Info(personal && use != Use.FILTER, false, false);
    }

    private Info caseExpression(CaseExpression ce, Scope scope, Use use, int depth) throws GuardViolation {
        Info acc = expr(ce.getSwitchExpression(), scope, use, depth);
        for (WhenClause w : ce.getWhenClauses()) {
            acc = acc.or(expr(w.getWhenExpression(), scope, use, depth))
                    .or(expr(w.getThenExpression(), scope, use, depth));
        }
        return acc.or(expr(ce.getElseExpression(), scope, use, depth));
    }

    private Info cast(CastExpression ce, Scope scope, Use use, int depth) throws GuardViolation {
        if (ce.isImplicitCast()) {
            throw new GuardViolation(Reason.LITERAL, "typed literal other than DATE, TIME or TIMESTAMP: " + ce);
        }
        if (ce.getColumnDefinitions() != null && ce.getColumnDefinitions().size() > 1) {
            throw new GuardViolation(Reason.UNSUPPORTED, "CAST to a row type");
        }
        String keyword = ce.keyword;
        if (keyword == null) {
            if (p.dialect() != SqlDialect.POSTGRESQL) {
                throw new GuardViolation(Reason.UNSUPPORTED, ":: cast outside PostgreSQL: " + ce);
            }
        } else if (!keyword.equalsIgnoreCase("CAST")) {
            requireFunction(keyword.toUpperCase(Locale.ROOT));
        }
        castType(ce.getColDataType().getDataType());
        return expr(ce.getLeftExpression(), scope, use, depth);
    }

    private void castType(String dataType) throws GuardViolation {
        String type = dataType.trim().toUpperCase(Locale.ROOT).replaceAll("\\s*\\(.*$", "").replaceAll("\\s+", " ");
        if (!CAST_TYPES.contains(type)) {
            throw new GuardViolation(Reason.FUNCTION, "CAST to " + dataType + " is refused");
        }
    }

    private Info convert(TranscodingFunction tf, Scope scope, Use use, int depth) throws GuardViolation {
        requireFunction(tf.getKeyword().toUpperCase(Locale.ROOT));
        if (tf.isTranscodeStyle()) {
            throw new GuardViolation(Reason.FUNCTION, "CONVERT … USING");
        }
        castType(tf.getColDataType().getDataType());
        String style = tf.getTranscodingName();
        if (style != null && !style.isEmpty() && !style.trim().matches("\\d{1,3}")) {
            throw new GuardViolation(Reason.FUNCTION, "CONVERT style " + style);
        }
        return expr(tf.getExpression(), scope, use, depth);
    }

    private Info function(Function f, Scope scope, Use use, int depth) throws GuardViolation {
        List<String> parts = new ArrayList<>();
        for (String part : f.getMultipartName()) {
            parts.add(DialectProfile.unquote(part).toUpperCase(Locale.ROOT));
        }
        String name = String.join(".", parts);
        requireFunction(name);
        if (f.isEscaped()) {
            throw new GuardViolation(Reason.FUNCTION, "JDBC escape {fn " + name + "}");
        }
        Function copy = new Function();
        copy.setName(f.getMultipartName());
        copy.setParameters(f.getParameters());
        copy.setNamedParameters(f.getNamedParameters());
        copy.setDistinct(f.isDistinct());
        copy.setAllColumns(f.isAllColumns());
        if (!copy.toString().equals(f.toString())) {
            throw new GuardViolation(Reason.FUNCTION, "unsupported form of " + name + ": " + f);
        }
        boolean aggregate = AGGREGATES.contains(name);
        if (f.isDistinct() && !aggregate) {
            throw new GuardViolation(Reason.FUNCTION, "DISTINCT in " + name);
        }
        Info acc = Info.NONE;
        if (f.getNamedParameters() != null) {
            // SUBSTRING(x FROM 1 FOR 3), POSITION(… IN …): keyword-separated arguments.
            for (Expression arg : f.getNamedParameters()) {
                acc = acc.or(expr(arg, scope, use, depth));
            }
        }
        ExpressionList<?> params = f.getParameters();
        if (params != null) {
            int index = 0;
            for (Expression arg : params) {
                index++;
                if (arg instanceof AllColumns star) {
                    if (name.equals("COUNT") && params.size() == 1 && star.getClass() == AllColumns.class) {
                        continue;
                    }
                    throw new GuardViolation(Reason.STAR, star + " inside " + name);
                }
                if (arg instanceof DateUnitExpression) {
                    continue;
                }
                if (index == 1 && datePartFunctions(p.dialect()).contains(name) && arg instanceof Column c
                        && c.getTable() == null
                        && DATE_PARTS.contains(DialectProfile.unquote(c.getColumnName()).toUpperCase(Locale.ROOT))) {
                    continue; // a date part keyword that JSqlParser read as a column (SQL_TSI_DAY, dd, weekday)
                }
                acc = acc.or(expr(arg, scope, use, depth));
            }
        }
        return aggregate ? new Info(acc.personal(), true, false) : acc;
    }

    private void requireFunction(String name) throws GuardViolation {
        if (!functions.contains(name)) {
            throw new GuardViolation(Reason.FUNCTION, name + " is not on the function allow-list");
        }
    }

    /** The first stretch of the original text that the checked copy lacks, for a violation message. */
    static String difference(String original, String checked) {
        int i = 0;
        while (i < original.length() && i < checked.length() && original.charAt(i) == checked.charAt(i)) {
            i++;
        }
        int j = 0;
        while (j < original.length() - i && j < checked.length() - i
                && original.charAt(original.length() - 1 - j) == checked.charAt(checked.length() - 1 - j)) {
            j++;
        }
        String extra = original.substring(i, original.length() - j).trim();
        return extra.isEmpty() ? original : extra.length() > 80 ? extra.substring(0, 77) + "..." : extra;
    }
}
