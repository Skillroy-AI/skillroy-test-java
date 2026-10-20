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

/**
 * The lexical rules of the statement guard, as a token stream: whitespace and comments are skipped, and what is left
 * comes as words, {@code ;}, string literals, quoted identifiers and single other characters. One reading per
 * {@link Dialect}: the standard SQL rules, and those of MySQL, PostgreSQL and SQL Server (see {@link StatementGuard}
 * for what each reading adds). Moved out of {@link StatementGuard} unchanged (phase8-contracts.md 4.4, D537), so that
 * {@link SelectLabels} reads a statement with exactly the rules the guard checks it with; never a pattern over the raw
 * text.
 *
 * <p>An unterminated block comment ends the stream with {@link #UNTERMINATED_COMMENT}; an unterminated literal or
 * quoted identifier is returned as its kind with {@link #unterminated()} true and ends the stream after it. Linear in
 * the text. Not thread-safe (one instance per scan); the static members are stateless.
 */
final class SqlTokens {

    /** No token is left. */
    static final int END = 0;
    /** A word: a letter, digit or {@code _} followed by letters, digits, {@code _} and {@code $}. */
    static final int WORD = 1;
    /** A {@code ;} outside literals, quoted identifiers and comments. */
    static final int SEMICOLON = 2;
    /** A string literal: {@code '...'} (also {@code E'...'} under PostgreSQL) or a dollar-quoted string. */
    static final int STRING = 3;
    /** A quoted identifier: {@code "..."}, and {@code `...`} under MySQL, {@code [...]} under SQL Server. */
    static final int IDENTIFIER = 4;
    /** Any other single character outside literals, quoted identifiers and comments. */
    static final int OTHER = 5;
    /** A block comment that is not closed; the stream ends here. */
    static final int UNTERMINATED_COMMENT = 6;

    // Lexical features of a dialect reading.
    static final int BACKSLASH_ESCAPES = 1;      // \x escapes inside '...' and "..." (MySQL)
    static final int E_STRINGS = 1 << 1;         // E'...' takes backslash escapes (PostgreSQL)
    static final int BACKTICK_IDENTIFIERS = 1 << 2;
    static final int BRACKET_IDENTIFIERS = 1 << 3;
    static final int DOLLAR_QUOTES = 1 << 4;     // $$...$$ and $tag$...$tag$
    static final int NESTED_COMMENTS = 1 << 5;
    static final int HASH_COMMENTS = 1 << 6;
    static final int DASH_COMMENT_NEEDS_SPACE = 1 << 7;
    static final int EXECUTABLE_COMMENTS = 1 << 8; // /*! ... */ and /*M! ... */ are code

    /** The standard SQL reading. */
    static final Dialect STANDARD = new Dialect(null, 0);
    /** Every reading, the standard one first. */
    static final Dialect[] DIALECTS = {
        STANDARD,
        new Dialect("MySQL", BACKSLASH_ESCAPES | BACKTICK_IDENTIFIERS | HASH_COMMENTS | DASH_COMMENT_NEEDS_SPACE
                | EXECUTABLE_COMMENTS),
        new Dialect("PostgreSQL", E_STRINGS | DOLLAR_QUOTES | NESTED_COMMENTS),
        new Dialect("SQL Server", BRACKET_IDENTIFIERS | NESTED_COMMENTS),
    };

    private final String sql;
    private final Dialect d;
    private final int n;
    private int pos;
    private boolean inExecutableComment;
    private int kind = END;
    private int start = -1;
    private int end = -1;
    private String what;

    /** @param sql not null */
    SqlTokens(String sql, Dialect dialect) {
        this.sql = sql;
        this.d = dialect;
        this.n = sql.length();
    }

    /** Advances to the next token and returns its kind ({@link #END} when none is left). */
    int next() {
        while (pos < n) {
            char c = sql.charAt(pos);
            char next = pos + 1 < n ? sql.charAt(pos + 1) : '\0';
            if (isSpace(c)) {
                pos++;
                continue;
            }
            // Comments.
            if (c == '-' && next == '-' && (!d.has(DASH_COMMENT_NEEDS_SPACE) || pos + 2 >= n
                    || isSpaceOrControl(sql.charAt(pos + 2)))) {
                pos = lineEnd(sql, pos + 2);
                continue;
            }
            if (c == '#' && d.has(HASH_COMMENTS)) {
                pos = lineEnd(sql, pos + 1);
                continue;
            }
            if (c == '/' && next == '*') {
                int body = executableCommentBody(sql, pos, d);
                if (body >= 0) {
                    inExecutableComment = true;
                    pos = body;
                    continue;
                }
                int close = blockCommentEnd(sql, pos, d.has(NESTED_COMMENTS));
                if (close < 0) {
                    return last(UNTERMINATED_COMMENT, pos, "comment");
                }
                pos = close;
                continue;
            }
            if (inExecutableComment && c == '*' && next == '/') {
                inExecutableComment = false;
                pos += 2;
                continue;
            }
            // Code tokens.
            if (c == ';') {
                return token(SEMICOLON, pos, pos + 1, null);
            }
            if (isWordStart(c)) {
                int e = pos + 1;
                while (e < n && isWordPart(sql.charAt(e))) {
                    e++;
                }
                return token(WORD, pos, e, null);
            }
            if (c == '\'') {
                boolean escapes = d.has(BACKSLASH_ESCAPES) || isEscapeString(sql, pos, d);
                return quoted(STRING, quotedEnd(sql, pos, '\'', escapes), "string literal");
            }
            if (c == '"') {
                return quoted(IDENTIFIER, quotedEnd(sql, pos, '"', d.has(BACKSLASH_ESCAPES)), "quoted identifier");
            }
            if (c == '`' && d.has(BACKTICK_IDENTIFIERS)) {
                return quoted(IDENTIFIER, quotedEnd(sql, pos, '`', false), "quoted identifier");
            }
            if (c == '[' && d.has(BRACKET_IDENTIFIERS)) {
                return quoted(IDENTIFIER, quotedEnd(sql, pos, ']', false), "quoted identifier");
            }
            if (c == '$' && d.has(DOLLAR_QUOTES) && dollarTagEnd(sql, pos) > 0) {
                int tagEnd = dollarTagEnd(sql, pos);
                String tag = sql.substring(pos, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                return quoted(STRING, close < 0 ? -1 : close + tag.length(), "dollar-quoted string");
            }
            return token(OTHER, pos, pos + 1, null);
        }
        kind = END;
        start = n;
        end = n;
        what = null;
        return END;
    }

    /** The kind of the current token. */
    int kind() {
        return kind;
    }

    /** Offset of the current token's first character. */
    int start() {
        return start;
    }

    /** Offset just past the current token, or -1 when it is not terminated. */
    int end() {
        return end;
    }

    /** True when the current literal, quoted identifier or comment is not terminated (the stream ends with it). */
    boolean unterminated() {
        return end < 0;
    }

    /** What the current quoted token or unterminated comment is, in words ("string literal"); else null. */
    String what() {
        return what;
    }

    /** The reading this stream follows. */
    Dialect dialect() {
        return d;
    }

    private int token(int k, int from, int to, String description) {
        kind = k;
        start = from;
        end = to;
        what = description;
        pos = to;
        return k;
    }

    private int quoted(int k, int to, String description) {
        if (to < 0) {
            return last(k, pos, description);
        }
        return token(k, pos, to, description);
    }

    /** A token after which the stream ends: an unterminated construct. */
    private int last(int k, int from, String description) {
        kind = k;
        start = from;
        end = -1;
        what = description;
        pos = n;
        return k;
    }

    /** Offset just past the closing quote, or -1. {@code close} doubled is an escaped quote. */
    static int quotedEnd(String sql, int open, char close, boolean backslashEscapes) {
        int n = sql.length();
        int j = open + 1;
        while (j < n) {
            char c = sql.charAt(j);
            if (backslashEscapes && c == '\\') {
                j += 2;
                continue;
            }
            if (c == close) {
                if (j + 1 < n && sql.charAt(j + 1) == close) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return -1;
    }

    /** PostgreSQL E'...': the quote directly follows a lone E. */
    static boolean isEscapeString(String sql, int quote, Dialect d) {
        if (!d.has(E_STRINGS) || quote < 1) {
            return false;
        }
        char e = sql.charAt(quote - 1);
        return (e == 'E' || e == 'e') && (quote < 2 || !isWordPart(sql.charAt(quote - 2)));
    }

    /** Offset just past an opening {@code $tag$} at {@code i} (the tag may be empty), or -1. */
    static int dollarTagEnd(String sql, int i) {
        if (i > 0 && isWordPart(sql.charAt(i - 1))) {
            return -1;
        }
        int n = sql.length();
        int j = i + 1;
        if (j < n && sql.charAt(j) == '$') {
            return j + 1;
        }
        if (j >= n || !(Character.isLetter(sql.charAt(j)) || sql.charAt(j) == '_')) {
            return -1;
        }
        while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        return j < n && sql.charAt(j) == '$' ? j + 1 : -1;
    }

    /** For a MySQL executable comment at {@code i}, the offset of its body; otherwise -1. */
    static int executableCommentBody(String sql, int i, Dialect d) {
        if (!d.has(EXECUTABLE_COMMENTS)) {
            return -1;
        }
        int j = i + 2;
        if (j < sql.length() && sql.charAt(j) == 'M') {
            j++;
        }
        if (j >= sql.length() || sql.charAt(j) != '!') {
            return -1;
        }
        j++;
        while (j < sql.length() && sql.charAt(j) >= '0' && sql.charAt(j) <= '9') {
            j++;
        }
        return j;
    }

    /** Offset just past the comment that opens at {@code i}, or -1 if it is not closed. */
    static int blockCommentEnd(String sql, int i, boolean nested) {
        int n = sql.length();
        int depth = 1;
        int j = i + 2;
        while (j + 1 < n) {
            char c = sql.charAt(j);
            char next = sql.charAt(j + 1);
            if (nested && c == '/' && next == '*') {
                depth++;
                j += 2;
            } else if (c == '*' && next == '/') {
                if (--depth == 0) {
                    return j + 2;
                }
                j += 2;
            } else {
                j++;
            }
        }
        return -1;
    }

    static int lineEnd(String sql, int from) {
        int n = sql.length();
        int j = from;
        while (j < n && sql.charAt(j) != '\n' && sql.charAt(j) != '\r') {
            j++;
        }
        return j;
    }

    static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** What MySQL accepts after "--" to start a comment: ASCII whitespace or control characters only. */
    static boolean isSpaceOrControl(char c) {
        return c <= ' ' || c == 0x7F;
    }

    static boolean isWordStart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Upper-cases ASCII letters only, so that no locale or Unicode case folding can turn a word into a keyword. */
    static String asciiUpper(String word) {
        char[] chars = word.toCharArray();
        for (int k = 0; k < chars.length; k++) {
            if (chars[k] >= 'a' && chars[k] <= 'z') {
                chars[k] = (char) (chars[k] - ('a' - 'A'));
            }
        }
        return new String(chars);
    }

    /** One way of reading quotes and comments. */
    static final class Dialect {
        final String name;
        final int features;

        Dialect(String name, int features) {
            this.name = name;
            this.features = features;
        }

        boolean has(int feature) {
            return (features & feature) != 0;
        }
    }
}
