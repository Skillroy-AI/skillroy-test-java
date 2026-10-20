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

import ai.skillroy.testjava.protocol.RpcException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The labels a statement's own top-level select list gives its columns, read from the text (phase8-contracts.md 4.4,
 * gap 12, D531): the agent's {@code data.query} names the columns a statement-API source leaves unnamed with them,
 * before the masks run, so that a positional source behaves as a JDBC one, whose driver takes its labels from the same
 * list. {@link #starTable} recognises the one statement form whose columns a provider names itself (gap 117, D532).
 *
 * <p>The statement is read with {@link StatementGuard}'s lexical rules ({@link SqlTokens}; never a pattern over the
 * raw text), once per dialect reading, and a statement the guard rejects has no labels. Grammar: leading comments; an
 * optional {@code WITH} list skipped by balanced parentheses; {@code SELECT}; optional {@code DISTINCT} (PostgreSQL's
 * {@code DISTINCT ON (...)} included) or {@code ALL}; optional T-SQL {@code TOP n} or {@code TOP (n)}, with
 * {@code PERCENT} and {@code WITH TIES}; the items, split at commas outside parentheses, brackets and braces, up to
 * a {@code FROM} (or another clause word) at that level or the end ({@code SELECT 1} has no {@code FROM}). An item's
 * label is its alias ({@code AS x}, or a trailing identifier that is not a keyword after a token that ends an
 * expression), else the last part of a bare, possibly qualified, column reference; quotes removed. Never a literal's
 * text: a label is a name the statement gives, never a value.
 *
 * <p><b>Conservative.</b> On any doubt the entry, or the whole list, is null and the caller keeps its own name
 * ({@code c<i>}). The whole list is null for a top-level set operation, a {@code *} or {@code t.*} item, an empty item,
 * more than {@value #MAX_ITEMS} items, parentheses nested deeper than {@value #MAX_DEPTH}, or two dialect readings
 * that split the list differently. A reading that meets an unterminated literal or comment, or brackets that do not
 * balance, does not count (the statement is not valid in that dialect), and no reading that counts means no labels; an
 * item with a backtick or a square bracket that a reading does
 * not take as a quote does not count in that reading (the dialect that quotes with it decides). The readings that count
 * must agree on an entry, else it is null; a square-bracket identifier serves as an alias only after {@code AS}, since
 * {@code x [y]} is also PostgreSQL's subscript. Labels keep the case they are written in.
 *
 * <p>A label from the text can be wrong exactly as a JDBC driver's label can be chosen by the statement: a caller who
 * writes {@code password AS p} renames the column for a name-keyed {@code MaskPolicy} over either form (stated in the
 * integration guide, 6.11). Never logs; the statement text never leaves this class.
 *
 * <p>Stateless; safe from any thread; never throws; linear in the text with a bounded depth. Java 8, JDK only.
 */
public final class SelectLabels {

    /** The most select items a list may have; beyond it the list is null. */
    public static final int MAX_ITEMS = 1000;

    /** The deepest nesting of parentheses, brackets and braces a statement may have; beyond it nothing is read. */
    public static final int MAX_DEPTH = 32;

    /** Words that are never a column's name or an alias without quotes (upper-case ASCII). */
    private static final Set<String> RESERVED = words(
            "SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "UNION",
            "INTERSECT", "EXCEPT", "MINUS", "ALL", "DISTINCT", "AS", "ON", "USING", "JOIN", "INNER", "OUTER", "LEFT",
            "RIGHT", "FULL", "CROSS", "NATURAL", "AND", "OR", "NOT", "NULL", "IS", "ISNULL", "NOTNULL", "IN", "EXISTS",
            "BETWEEN", "LIKE", "ILIKE", "SIMILAR", "ESCAPE", "CASE", "WHEN", "THEN", "ELSE", "END", "TRUE", "FALSE",
            "WITH", "OVER", "PARTITION", "WINDOW", "FILTER", "WITHIN", "COLLATE", "INTERVAL", "CAST", "ANY", "SOME",
            "ARRAY", "VALUES", "INTO", "FOR", "LATERAL", "TABLESAMPLE", "QUALIFY", "ONLY", "CURRENT_DATE",
            "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP", "CURRENT_USER", "SESSION_USER", "DIV",
            "MOD", "XOR", "REGEXP", "RLIKE", "BINARY", "LEADING", "TRAILING", "BOTH", "RETURNING");

    /**
     * Words that may name a column but are not taken as an alias without {@code AS}, nor as the end of the expression
     * before one: type names, interval units, frame and ordering words.
     */
    private static final Set<String> SOFT = words(
            "ASC", "DESC", "NULLS", "FIRST", "LAST", "NEXT", "ROWS", "ROW", "PERCENT", "TIES", "ZONE", "AT", "LOCAL",
            "DAY", "HOUR", "MINUTE", "SECOND", "MONTH", "YEAR", "WEEK", "QUARTER", "DATE", "TIME", "TIMESTAMP",
            "UNKNOWN", "RECURSIVE", "OF", "TO", "PRECISION", "VARYING", "CHARACTER", "CHAR", "DOUBLE", "INT", "INTEGER",
            "TEXT", "VARCHAR", "NUMERIC", "DECIMAL", "BOOLEAN", "SMALLINT", "BIGINT", "REAL", "FLOAT", "PRECEDING",
            "FOLLOWING", "UNBOUNDED", "CURRENT", "RANGE", "GROUPS", "TOP");

    /**
     * Soft keywords that lead what follows rather than end an expression ({@code AT TIME ZONE tz}), so that an
     * identifier after one is not an alias.
     */
    private static final Set<String> LEADS = words(
            "AT", "ZONE", "OF", "TO", "LOCAL", "NULLS", "ASC", "DESC", "FIRST", "LAST", "NEXT", "ROWS", "ROW", "RANGE",
            "GROUPS", "PRECEDING", "FOLLOWING", "UNBOUNDED", "CURRENT", "TIES", "PERCENT", "RECURSIVE", "TOP");

    /** Keywords that end an expression, so that an identifier after one is an alias ({@code CASE ... END kind}). */
    private static final Set<String> ENDS_EXPRESSION = words(
            "END", "NULL", "TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME",
            "LOCALTIMESTAMP", "CURRENT_USER", "SESSION_USER");

    /** Words that end the select list at its own level. */
    private static final Set<String> LIST_END = words(
            "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "WINDOW", "INTO", "FOR", "UNION",
            "INTERSECT", "EXCEPT", "MINUS", "QUALIFY");

    /** Top-level set operations: the columns are no longer the first list's alone. */
    private static final Set<String> SET_OPERATIONS = words("UNION", "INTERSECT", "EXCEPT", "MINUS");

    /** The clauses that may follow the table of the star form. */
    private static final Set<String> STAR_REST = words("WHERE", "ORDER", "FETCH", "LIMIT", "OFFSET");

    /** Words that change the star form's columns or rows beyond a filter, an order and a limit. */
    private static final Set<String> STAR_REST_DENIED = words(
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "JOIN", "GROUP", "HAVING", "WINDOW", "INTO", "FOR", "PIVOT",
            "UNPIVOT", "CONNECT", "START", "MODEL", "QUALIFY", "MATCH_RECOGNIZE");

    /** An entry no reading's rule decided: this reading does not count for it. */
    private static final Object UNKNOWN = new Object();
    /** The whole list (or the star form) cannot be read. */
    private static final Object GIVE_UP = new Object();
    /** A reading that met an unterminated construct: it does not count at all. */
    private static final Object[] EXCLUDED = new Object[0];

    private SelectLabels() {
    }

    /**
     * One entry per top-level select item of a statement StatementGuard accepted: the alias (AS x, or a trailing
     * identifier that is not a keyword), else the last part of a bare [qualified] column reference, quotes removed;
     * null for any other item (an expression without alias). Null for the whole list when it cannot be read: a
     * top-level set operation, a * or t.* item, a dialect reading that splits the list differently, more than 1000
     * items, parentheses nested beyond 32.
     *
     * @return an unmodifiable list, or null; null also for a statement the guard rejects and for null
     */
    public static List<String> of(String sql) {
        try {
            return labels(sql);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * For SELECT * FROM &lt;table&gt; [[AS] alias] [WHERE …] [ORDER BY …] [FETCH/LIMIT …] (one table, no join, no set
     * operation): the table as written, quotes removed, a schema part kept; else null.
     *
     * @return the table, its parts joined with {@code .} (at most three), or null; null also for a statement the
     *     guard rejects and for null
     */
    public static String starTable(String sql) {
        try {
            return star(sql);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> labels(String sql) {
        if (!accepted(sql)) {
            return null;
        }
        List<Object[]> counted = new ArrayList<Object[]>();
        for (SqlTokens.Dialect d : SqlTokens.DIALECTS) {
            Object[] entries = listOf(new Reading(sql, d));
            if (entries == null) {
                return null;
            }
            if (entries == EXCLUDED) {
                continue;
            }
            if (!counted.isEmpty() && counted.get(0).length != entries.length) {
                return null;
            }
            counted.add(entries);
        }
        if (counted.isEmpty()) {
            return null;
        }
        int size = counted.get(0).length;
        List<String> labels = new ArrayList<String>(size);
        for (int k = 0; k < size; k++) {
            Object agreed = UNKNOWN;
            boolean conflict = false;
            for (Object[] entries : counted) {
                Object entry = entries[k];
                if (entry == UNKNOWN) {
                    continue;
                }
                if (agreed == UNKNOWN) {
                    agreed = entry;
                } else if (agreed == null ? entry != null : !agreed.equals(entry)) {
                    conflict = true;
                }
            }
            labels.add(conflict || agreed == UNKNOWN ? null : (String) agreed);
        }
        return Collections.unmodifiableList(labels);
    }

    private static String star(String sql) {
        if (!accepted(sql)) {
            return null;
        }
        Object agreed = UNKNOWN;
        for (SqlTokens.Dialect d : SqlTokens.DIALECTS) {
            Object table = starOf(new Reading(sql, d));
            if (table == EXCLUDED || table == UNKNOWN) {
                continue;
            }
            if (table == null) {
                return null;
            }
            if (agreed == UNKNOWN) {
                agreed = table;
            } else if (!agreed.equals(table)) {
                return null;
            }
        }
        return agreed == UNKNOWN ? null : (String) agreed;
    }

    private static boolean accepted(String sql) {
        if (sql == null) {
            return false;
        }
        try {
            StatementGuard.check(sql);
            return true;
        } catch (RpcException rejected) {
            return false;
        }
    }

    /** The entries of one reading (String, null or UNKNOWN), null when the list cannot be read, or EXCLUDED. */
    private static Object[] listOf(Reading r) {
        if (r.broken) {
            return EXCLUDED;
        }
        int n = r.size;
        int i = 0;
        if (r.isWord(0, "WITH")) {
            Nesting nesting = new Nesting();
            i = 1;
            while (i < n && !(nesting.depth == 0 && r.isWord(i, "SELECT"))) {
                if (!nesting.step(r, i)) {
                    return nesting.failure();
                }
                i++;
            }
        }
        if (!r.isWord(i, "SELECT")) {
            return null;
        }
        i++;
        if (r.isWord(i, "DISTINCT")) {
            i++;
            if (r.isWord(i, "ON") && r.isOther(i + 1, '(')) {
                Nesting nesting = new Nesting();
                i = skipGroup(r, i + 1, nesting);
                if (i < 0) {
                    return nesting.failure();
                }
            }
        } else if (r.isWord(i, "ALL")) {
            i++;
        }
        if (r.isWord(i, "TOP") && (r.isNumber(i + 1) || r.isOther(i + 1, '('))) {
            i++;
            if (r.isOther(i, '(')) {
                Nesting nesting = new Nesting();
                i = skipGroup(r, i, nesting);
                if (i < 0) {
                    return nesting.failure();
                }
            } else {
                i++;
            }
            if (r.isWord(i, "PERCENT")) {
                i++;
            }
            if (r.isWord(i, "WITH") && r.isWord(i + 1, "TIES")) {
                i += 2;
            }
        }
        List<int[]> items = new ArrayList<int[]>();
        Nesting nesting = new Nesting();
        int itemStart = i;
        int j = i;
        for (; j < n; j++) {
            if (nesting.depth == 0) {
                if (r.isOther(j, ',')) {
                    items.add(new int[] {itemStart, j});
                    if (items.size() > MAX_ITEMS) {
                        return null;
                    }
                    itemStart = j + 1;
                    continue;
                }
                if (r.word[j] != null && LIST_END.contains(r.word[j]) && !insideExpression(r, j)) {
                    break;
                }
            }
            if (!nesting.step(r, j)) {
                return nesting.failure();
            }
        }
        items.add(new int[] {itemStart, j});
        if (items.size() > MAX_ITEMS) {
            return null;
        }
        for (; j < n; j++) {
            if (nesting.depth == 0 && r.word[j] != null && SET_OPERATIONS.contains(r.word[j])) {
                return null;
            }
            if (!nesting.step(r, j)) {
                return nesting.failure();
            }
        }
        if (nesting.depth != 0) {
            return EXCLUDED;
        }
        Object[] entries = new Object[items.size()];
        for (int k = 0; k < entries.length; k++) {
            Object entry = entry(r, items.get(k)[0], items.get(k)[1]);
            if (entry == GIVE_UP) {
                return null;
            }
            entries[k] = entry;
        }
        return entries;
    }

    /** {@code IS [NOT] DISTINCT FROM} and {@code WITHIN GROUP} are inside an expression, not the list's end. */
    private static boolean insideExpression(Reading r, int j) {
        if ("FROM".equals(r.word[j])) {
            return r.isWord(j - 1, "DISTINCT") && (r.isWord(j - 2, "IS") || r.isWord(j - 2, "NOT"));
        }
        return "GROUP".equals(r.word[j]) && r.isWord(j - 1, "WITHIN");
    }

    /** The label of the item of tokens [from, to): a String, null, UNKNOWN, or GIVE_UP for a star or empty item. */
    private static Object entry(Reading r, int from, int to) {
        int m = to - from;
        if (m <= 0) {
            return GIVE_UP;
        }
        if (r.isOther(to - 1, '*') && (m == 1 || r.isOther(to - 2, '.'))) {
            return GIVE_UP;
        }
        for (int k = from; k < to; k++) {
            if (r.isOther(k, '`') || r.isOther(k, '[') || r.isOther(k, ']')) {
                return UNKNOWN;
            }
        }
        if (m >= 3 && r.isWord(to - 2, "AS")) {
            return r.isName(to - 1, false) ? r.label(to - 1) : null;
        }
        if (m % 2 == 1) {
            boolean bare = true;
            for (int k = 0; k < m && bare; k++) {
                bare = k % 2 == 0 ? r.isName(from + k, false) : r.isOther(from + k, '.');
            }
            if (bare) {
                return r.label(to - 1);
            }
        }
        if (m >= 2 && r.isName(to - 1, true) && !r.isBracketed(to - 1) && endsExpression(r, to - 2)) {
            return r.label(to - 1);
        }
        return null;
    }

    private static boolean endsExpression(Reading r, int i) {
        int kind = r.kind[i];
        if (kind == SqlTokens.STRING || kind == SqlTokens.IDENTIFIER) {
            return true;
        }
        if (kind == SqlTokens.OTHER) {
            return r.isOther(i, ')');
        }
        if (kind != SqlTokens.WORD) {
            return false;
        }
        String word = r.word[i];
        return ENDS_EXPRESSION.contains(word) || Character.isDigit(r.sql.charAt(r.start[i]))
                || !RESERVED.contains(word) && !LEADS.contains(word);
    }

    /** The table of the star form in one reading: a String, null, UNKNOWN or EXCLUDED. */
    private static Object starOf(Reading r) {
        if (r.broken) {
            return EXCLUDED;
        }
        int n = r.size;
        for (int k = 0; k < n; k++) {
            if (r.isOther(k, '`') || r.isOther(k, '[') || r.isOther(k, ']')) {
                return UNKNOWN;
            }
        }
        if (!r.isWord(0, "SELECT") || !r.isOther(1, '*') || !r.isWord(2, "FROM")) {
            return null;
        }
        int i = 3;
        List<String> parts = new ArrayList<String>();
        while (true) {
            if (!r.isName(i, false)) {
                return null;
            }
            String part = r.label(i);
            if (part == null || part.indexOf('.') >= 0 || parts.size() == 3) {
                return null;
            }
            parts.add(part);
            if (r.isOther(i + 1, '.')) {
                i += 2;
            } else {
                i++;
                break;
            }
        }
        if (r.isWord(i, "AS")) {
            if (!r.isName(i + 1, false)) {
                return null;
            }
            i += 2;
        } else if (r.isName(i, true)) {
            i++;
        }
        if (i < n) {
            if (r.word[i] == null || !STAR_REST.contains(r.word[i])) {
                return null;
            }
            Nesting nesting = new Nesting();
            for (; i < n; i++) {
                if (nesting.depth == 0 && r.word[i] != null && STAR_REST_DENIED.contains(r.word[i])) {
                    return null;
                }
                if (!nesting.step(r, i)) {
                    return nesting.tooDeep ? null : EXCLUDED;
                }
            }
            if (nesting.depth != 0) {
                return EXCLUDED;
            }
        }
        StringBuilder table = new StringBuilder();
        for (String part : parts) {
            table.append(table.length() == 0 ? "" : ".").append(part);
        }
        return table.toString();
    }

    /** From the opening parenthesis at {@code i}, the index past its closing one, or -1 ({@code nesting} says why). */
    private static int skipGroup(Reading r, int i, Nesting nesting) {
        int j = i;
        do {
            if (j >= r.size || !nesting.step(r, j)) {
                return -1;
            }
            j++;
        } while (nesting.depth > 0);
        return j;
    }

    private static Set<String> words(String... words) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(words)));
    }

    /**
     * Parentheses, brackets and braces, bounded to MAX_DEPTH, each closed by its own kind. Nesting beyond the bound
     * gives the list up; brackets that do not balance mean the statement is not valid in the reading, which then does
     * not count (as one that meets an unterminated literal).
     */
    private static final class Nesting {
        final char[] open = new char[MAX_DEPTH];
        int depth;
        boolean tooDeep;

        /** Applies token {@code i}; false on a mismatch, a close without an open, or nesting beyond the bound. */
        boolean step(Reading r, int i) {
            if (r.kind[i] != SqlTokens.OTHER) {
                return true;
            }
            char c = r.sql.charAt(r.start[i]);
            if (c == '(' || c == '[' || c == '{') {
                if (depth == MAX_DEPTH) {
                    tooDeep = true;
                    return false;
                }
                open[depth++] = c;
                return true;
            }
            if (c == ')' || c == ']' || c == '}') {
                char expected = c == ')' ? '(' : c == ']' ? '[' : '{';
                if (depth == 0 || open[depth - 1] != expected) {
                    return false;
                }
                depth--;
            }
            return true;
        }

        /** What a failed {@link #step} means for the list: null (give up) when too deep, else EXCLUDED. */
        Object[] failure() {
            return tooDeep ? null : EXCLUDED;
        }
    }

    /** The code tokens of one dialect reading, up to a terminating {@code ;}. */
    private static final class Reading {
        final String sql;
        final SqlTokens.Dialect dialect;
        int size;
        int[] kind = new int[16];
        int[] start = new int[16];
        int[] end = new int[16];
        /** Upper-case ASCII text of WORD tokens, null for the others. */
        String[] word = new String[16];
        /** An unterminated literal, identifier or comment: the statement is not valid in this dialect. */
        boolean broken;

        Reading(String sql, SqlTokens.Dialect dialect) {
            this.sql = sql;
            this.dialect = dialect;
            SqlTokens tokens = new SqlTokens(sql, dialect);
            for (int k = tokens.next(); k != SqlTokens.END; k = tokens.next()) {
                if (k == SqlTokens.UNTERMINATED_COMMENT || tokens.unterminated()) {
                    broken = true;
                    return;
                }
                if (k == SqlTokens.SEMICOLON) {
                    return;
                }
                add(k, tokens.start(), tokens.end());
            }
        }

        private void add(int k, int from, int to) {
            if (size == kind.length) {
                kind = Arrays.copyOf(kind, size * 2);
                start = Arrays.copyOf(start, size * 2);
                end = Arrays.copyOf(end, size * 2);
                word = Arrays.copyOf(word, size * 2);
            }
            kind[size] = k;
            start[size] = from;
            end[size] = to;
            word[size] = k == SqlTokens.WORD ? SqlTokens.asciiUpper(sql.substring(from, to)) : null;
            size++;
        }

        boolean isWord(int i, String upper) {
            return i >= 0 && i < size && upper.equals(word[i]);
        }

        boolean isOther(int i, char c) {
            return i >= 0 && i < size && kind[i] == SqlTokens.OTHER && sql.charAt(start[i]) == c;
        }

        boolean isNumber(int i) {
            return i >= 0 && i < size && kind[i] == SqlTokens.WORD && Character.isDigit(sql.charAt(start[i]));
        }

        boolean isBracketed(int i) {
            return kind[i] == SqlTokens.IDENTIFIER && sql.charAt(start[i]) == '[';
        }

        /**
         * True if token {@code i} can name a column: a quoted identifier, or a word that is not a number and not a
         * reserved word; with {@code strict}, not one of the soft keywords either.
         */
        boolean isName(int i, boolean strict) {
            if (i < 0 || i >= size) {
                return false;
            }
            if (kind[i] == SqlTokens.IDENTIFIER) {
                return true;
            }
            return kind[i] == SqlTokens.WORD && !Character.isDigit(sql.charAt(start[i]))
                    && !RESERVED.contains(word[i]) && !(strict && SOFT.contains(word[i]));
        }

        /** The name token {@code i} gives: a word as written, a quoted identifier without its quotes; or null. */
        String label(int i) {
            if (kind[i] == SqlTokens.WORD) {
                return sql.substring(start[i], end[i]);
            }
            char quote = sql.charAt(start[i]);
            char close = quote == '[' ? ']' : quote;
            String inner = sql.substring(start[i] + 1, end[i] - 1);
            if (dialect.has(SqlTokens.BACKSLASH_ESCAPES) && inner.indexOf('\\') >= 0) {
                return null;
            }
            String doubled = new String(new char[] {close, close});
            String name = inner.replace(doubled, String.valueOf(close));
            return name.isEmpty() ? null : name;
        }
    }
}
