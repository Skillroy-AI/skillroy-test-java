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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The statement check both data channels apply before any SQL reaches a data source (the agent's {@code data.query}
 * inside the target JVM and the controller's JDBC channel, phase2-contracts.md 2.2): exactly one statement, and it
 * must be a query. It exists so that a typo or a careless scenario cannot send a data-changing statement to the
 * target application's database, and it lives here so that a statement one channel accepts the other accepts too.
 *
 * <p><b>This is a safety net, not the security boundary.</b> The boundary is the data source itself: the
 * {@code DataSourceProvider} should hand out a connection or statement API that cannot write (a read-only database
 * user, a replica), and {@link ReadOnlyJdbc} additionally sets the connection read-only. A statement can pass
 * this check and still have effects the check cannot see, for example a function that writes or a sequence that
 * advances, and a database dialect this check does not model can read the text differently.
 *
 * <p>Rules, applied by a small tokenizer (never by a pattern over the raw text):
 * <ol>
 *   <li>Leading whitespace and comments ({@code -- line} and {@code /* block *}{@code /}) are skipped; nothing else
 *       may be left out. A statement that is empty after that is rejected.</li>
 *   <li>The first token must be the keyword {@code SELECT} or {@code WITH} (ASCII, case-insensitive), as a whole
 *       word: {@code SELECTX} or a leading parenthesis is rejected.</li>
 *   <li>A {@code ;} is allowed only as the last non-whitespace character. A {@code ;} inside a string literal
 *       ({@code '...'} with {@code ''} escapes), a quoted identifier ({@code "..."} with {@code ""} escapes) or a
 *       comment does not count. Even a comment after the trailing {@code ;} is rejected.</li>
 *   <li>Unterminated literals, quoted identifiers and block comments are rejected.</li>
 *   <li>Stricter than the Phase 1 contract: the words {@link #FORBIDDEN_WORDS} (data-changing, schema-changing,
 *       privilege, procedure-call and server-control statements, plus {@code INTO}) are rejected anywhere outside
 *       literals, quoted identifiers and comments. This catches a data-changing statement inside or after a
 *       {@code WITH} clause, {@code SELECT ... INTO}, locking reads ({@code FOR UPDATE}, which could block the
 *       application) and T-SQL batches, which need no {@code ;} between statements (a word that starts with a
 *       number is also checked without it, since T-SQL reads {@code 1DELETE} as {@code 1 DELETE}). Quote an
 *       identifier that happens to be one of these words. The one exception: {@code TRUNCATE(} and {@code INSERT(}
 *       (the word, optional whitespace, an opening parenthesis) are the scalar functions of that name, which no
 *       database reads as the start of a statement, and are allowed. Security fix S36 (Phase 6, P1 6) adds the T-SQL
 *       transaction, control-of-flow and lock-hint words a batch without {@code ;} would run ({@code BEGIN},
 *       {@code COMMIT}, {@code ROLLBACK}, {@code SAVE}, {@code DECLARE}, {@code WAITFOR}, {@code RAISERROR},
 *       {@code THROW}, {@code PRINT}, {@code GOTO}, {@code USE}, {@code CHECKPOINT}, {@code RECEIVE}, {@code SEND},
 *       {@code OPENROWSET}, {@code OPENQUERY}, {@code OPENDATASOURCE}, {@code HOLDLOCK}, {@code TABLOCK},
 *       {@code TABLOCKX}, {@code XLOCK}, {@code UPDLOCK}, {@code PAGLOCK}); {@code SET} is not one of them, since
 *       MySQL's {@code CAST(x AS CHAR CHARACTER SET utf8mb4)} must stay admitted.</li>
 *   <li>The locking reads {@code FOR SHARE}, {@code FOR KEY SHARE} and {@code LOCK IN SHARE MODE} are rejected as
 *       word sequences ({@link #FORBIDDEN_SEQUENCES}): consecutive words with only whitespace and comments between
 *       them, in every reading (S36).</li>
 * </ol>
 *
 * <p>Because the guard does not know which database a source uses, rules 2, 3 and 5 are checked once with the
 * standard SQL lexical rules above and again with the lexical rules of MySQL (backslash escapes, backtick identifiers,
 * {@code #} comments, {@code --} comments only before whitespace, {@code /*!} comments that are executed),
 * PostgreSQL ({@code $tag$} quoting, {@code E'...'} backslash escapes, nested block comments) and SQL Server
 * ({@code [...]} identifiers, nested block comments). A statement is accepted only if every reading accepts it, so a
 * {@code ;} that one of these databases would see outside a literal is never hidden by the standard reading. In the
 * dialect readings an unterminated construct ends the scan instead of rejecting the statement (that database would
 * reject the statement itself), and a first token that is not a word is left to the standard reading.
 *
 * <p>Stateless; safe from any thread.
 */
public final class StatementGuard {

    /**
     * Words rejected outside literals, quoted identifiers and comments (upper-case ASCII; matched case-insensitively
     * as whole words).
     */
    public static final Set<String> FORBIDDEN_WORDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "DROP", "ALTER", "CREATE", "GRANT", "REVOKE", "DENY",
            "CALL", "EXEC", "EXECUTE", "INTO", "SHUTDOWN", "KILL", "BACKUP", "RESTORE", "DBCC", "UPDATETEXT",
            "WRITETEXT", "RECONFIGURE", "SETUSER",
            // Security fix S36: T-SQL transaction, control-of-flow and lock-hint words (a batch needs no ';').
            "BEGIN", "COMMIT", "ROLLBACK", "SAVE", "DECLARE", "WAITFOR", "RAISERROR", "THROW", "PRINT", "GOTO", "USE",
            "CHECKPOINT", "RECEIVE", "SEND", "OPENROWSET", "OPENQUERY", "OPENDATASOURCE", "HOLDLOCK", "TABLOCK",
            "TABLOCKX", "XLOCK", "UPDLOCK", "PAGLOCK")));

    /**
     * Locking reads rejected as sequences of whole words (security fix S36): consecutive words outside literals,
     * quoted identifiers and comments, with only whitespace and comments between them.
     */
    public static final java.util.List<java.util.List<String>> FORBIDDEN_SEQUENCES =
            Collections.unmodifiableList(Arrays.asList(Arrays.asList("FOR", "SHARE"),
                    Arrays.asList("FOR", "KEY", "SHARE"), Arrays.asList("LOCK", "IN", "SHARE", "MODE")));

    /** Forbidden words that are also scalar functions; allowed when directly followed by a parenthesis. */
    private static final Set<String> FUNCTION_WORDS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("TRUNCATE", "INSERT")));

    // Lexical features of a dialect reading.
    private static final int BACKSLASH_ESCAPES = 1;      // \x escapes inside '...' and "..." (MySQL)
    private static final int E_STRINGS = 1 << 1;         // E'...' takes backslash escapes (PostgreSQL)
    private static final int BACKTICK_IDENTIFIERS = 1 << 2;
    private static final int BRACKET_IDENTIFIERS = 1 << 3;
    private static final int DOLLAR_QUOTES = 1 << 4;     // $$...$$ and $tag$...$tag$
    private static final int NESTED_COMMENTS = 1 << 5;
    private static final int HASH_COMMENTS = 1 << 6;
    private static final int DASH_COMMENT_NEEDS_SPACE = 1 << 7;
    private static final int EXECUTABLE_COMMENTS = 1 << 8; // /*! ... */ and /*M! ... */ are code

    private static final Dialect STANDARD = new Dialect(null, 0);
    private static final Dialect[] DIALECTS = {
        STANDARD,
        new Dialect("MySQL", BACKSLASH_ESCAPES | BACKTICK_IDENTIFIERS | HASH_COMMENTS | DASH_COMMENT_NEEDS_SPACE
                | EXECUTABLE_COMMENTS),
        new Dialect("PostgreSQL", E_STRINGS | DOLLAR_QUOTES | NESTED_COMMENTS),
        new Dialect("SQL Server", BRACKET_IDENTIFIERS | NESTED_COMMENTS),
    };

    private StatementGuard() {
    }

    /**
     * Checks {@code sql} and returns the text to execute: the statement itself, without a trailing {@code ;} and the
     * whitespace after it (some drivers reject a terminator).
     *
     * @throws RpcException {@link ErrorCodes#INVALID_PARAMS} with {@code data.param = "sql"} and
     *     {@code data.reason} when the statement is rejected
     */
    public static String check(String sql) {
        int[] semicolon = {-1};
        String reason = scan(sql, STANDARD, semicolon);
        for (int i = 1; reason == null && i < DIALECTS.length; i++) {
            reason = scan(sql, DIALECTS[i], new int[] {-1});
        }
        if (reason != null) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("param", "sql");
            data.put("reason", reason);
            throw new RpcException(ErrorCodes.INVALID_PARAMS, "sql rejected: " + reason, data);
        }
        // Text before the ';' is kept as is: a newline there may end a line comment.
        return semicolon[0] < 0 ? sql : sql.substring(0, semicolon[0]);
    }

    /**
     * Scans {@code sql} under one dialect reading.
     *
     * @param semicolon receives the offset of the terminating {@code ;}, if there is one
     * @return the rejection reason, or null
     */
    private static String scan(String sql, Dialect d, int[] semicolon) {
        if (sql == null) {
            return "empty statement";
        }
        final boolean standard = d == STANDARD;
        final int n = sql.length();
        // The last words read without another code token between them (S36's sequences).
        final String[] recent = new String[4];
        boolean first = true;
        boolean inExecutableComment = false;
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (isSpace(c)) {
                i++;
                continue;
            }
            // Comments.
            if (c == '-' && next == '-' && (!d.has(DASH_COMMENT_NEEDS_SPACE) || i + 2 >= n
                    || isSpaceOrControl(sql.charAt(i + 2)))) {
                i = lineEnd(sql, i + 2);
                continue;
            }
            if (c == '#' && d.has(HASH_COMMENTS)) {
                i = lineEnd(sql, i + 1);
                continue;
            }
            if (c == '/' && next == '*') {
                int body = executableCommentBody(sql, i, d);
                if (body >= 0) {
                    inExecutableComment = true;
                    i = body;
                    continue;
                }
                int end = blockCommentEnd(sql, i, d.has(NESTED_COMMENTS));
                if (end < 0) {
                    if (standard) {
                        return "unterminated comment starting at offset " + i;
                    }
                    break;
                }
                i = end;
                continue;
            }
            if (inExecutableComment && c == '*' && next == '/') {
                inExecutableComment = false;
                i += 2;
                continue;
            }
            // Code tokens.
            if (c == ';') {
                if (first && standard) {
                    return "empty statement";
                }
                for (int j = i + 1; j < n; j++) {
                    if (!isSpace(sql.charAt(j))) {
                        return reason("only one statement is allowed: text follows the ';' at offset " + i, d);
                    }
                }
                semicolon[0] = i;
                return null;
            }
            if (isWordStart(c)) {
                int end = i + 1;
                while (end < n && isWordPart(sql.charAt(end))) {
                    end++;
                }
                String word = asciiUpper(sql.substring(i, end));
                if (first) {
                    if (!"SELECT".equals(word) && !"WITH".equals(word)) {
                        return reason("the statement must start with SELECT or WITH, not '"
                                + abbreviate(sql.substring(i, end)) + "'", d);
                    }
                } else if ((FORBIDDEN_WORDS.contains(word) || FORBIDDEN_WORDS.contains(afterNumber(word)))
                        && !(FUNCTION_WORDS.contains(word) && opensParenthesis(sql, end))) {
                    return reason("'" + sql.substring(i, end) + "' is not allowed in a read-only query", d);
                }
                System.arraycopy(recent, 1, recent, 0, recent.length - 1);
                recent[recent.length - 1] = word;
                String sequence = forbiddenSequence(recent);
                if (sequence != null) {
                    return reason("'" + sequence + "' (a locking read) is not allowed in a read-only query", d);
                }
                first = false;
                i = end;
                continue;
            }
            if (first && standard) {
                return "the statement must start with SELECT or WITH, not '" + abbreviate(sql.substring(i)) + "'";
            }
            first = false;
            Arrays.fill(recent, null);
            int end;
            String what;
            if (c == '\'') {
                end = quotedEnd(sql, i, '\'', d.has(BACKSLASH_ESCAPES) || isEscapeString(sql, i, d));
                what = "string literal";
            } else if (c == '"') {
                end = quotedEnd(sql, i, '"', d.has(BACKSLASH_ESCAPES));
                what = "quoted identifier";
            } else if (c == '`' && d.has(BACKTICK_IDENTIFIERS)) {
                end = quotedEnd(sql, i, '`', false);
                what = "quoted identifier";
            } else if (c == '[' && d.has(BRACKET_IDENTIFIERS)) {
                end = quotedEnd(sql, i, ']', false);
                what = "quoted identifier";
            } else if (c == '$' && d.has(DOLLAR_QUOTES) && dollarTagEnd(sql, i) > 0) {
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                end = close < 0 ? -1 : close + tag.length();
                what = "dollar-quoted string";
            } else {
                i++;
                continue;
            }
            if (end < 0) {
                if (standard) {
                    return "unterminated " + what + " starting at offset " + i;
                }
                break;
            }
            i = end;
        }
        return first && standard ? "empty statement" : null;
    }

    /** The forbidden sequence the last words read end with, as text, or null (S36). */
    private static String forbiddenSequence(String[] recent) {
        for (java.util.List<String> sequence : FORBIDDEN_SEQUENCES) {
            int offset = recent.length - sequence.size();
            boolean match = true;
            for (int k = 0; k < sequence.size() && match; k++) {
                match = sequence.get(k).equals(recent[offset + k]);
            }
            if (match) {
                StringBuilder text = new StringBuilder();
                for (String w : sequence) {
                    text.append(text.length() == 0 ? "" : " ").append(w);
                }
                return text.toString();
            }
        }
        return null;
    }

    private static String reason(String text, Dialect d) {
        return d.name == null ? text : text + " (reading the statement with " + d.name + " quoting rules)";
    }

    /** Offset just past the closing quote, or -1. {@code close} doubled is an escaped quote. */
    private static int quotedEnd(String sql, int open, char close, boolean backslashEscapes) {
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
    private static boolean isEscapeString(String sql, int quote, Dialect d) {
        if (!d.has(E_STRINGS) || quote < 1) {
            return false;
        }
        char e = sql.charAt(quote - 1);
        return (e == 'E' || e == 'e') && (quote < 2 || !isWordPart(sql.charAt(quote - 2)));
    }

    /** Offset just past an opening {@code $tag$} at {@code i} (the tag may be empty), or -1. */
    private static int dollarTagEnd(String sql, int i) {
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
    private static int executableCommentBody(String sql, int i, Dialect d) {
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
    private static int blockCommentEnd(String sql, int i, boolean nested) {
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

    /** True if the first character at or after {@code from} that is not whitespace is an opening parenthesis. */
    private static boolean opensParenthesis(String sql, int from) {
        int j = from;
        while (j < sql.length() && isSpace(sql.charAt(j))) {
            j++;
        }
        return j < sql.length() && sql.charAt(j) == '(';
    }

    private static int lineEnd(String sql, int from) {
        int n = sql.length();
        int j = from;
        while (j < n && sql.charAt(j) != '\n' && sql.charAt(j) != '\r') {
            j++;
        }
        return j;
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** What MySQL accepts after "--" to start a comment: ASCII whitespace or control characters only. */
    private static boolean isSpaceOrControl(char c) {
        return c <= ' ' || c == 0x7F;
    }

    /**
     * The word without a leading number ("1DELETE" -> "DELETE"): SQL Server reads a number directly followed by a
     * keyword as two tokens.
     */
    private static String afterNumber(String word) {
        int k = 0;
        while (k < word.length() && (word.charAt(k) >= '0' && word.charAt(k) <= '9' || word.charAt(k) == '.')) {
            k++;
        }
        return k == 0 ? word : word.substring(k);
    }

    private static boolean isWordStart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Upper-cases ASCII letters only, so that no locale or Unicode case folding can turn a word into a keyword. */
    private static String asciiUpper(String word) {
        char[] chars = word.toCharArray();
        for (int k = 0; k < chars.length; k++) {
            if (chars[k] >= 'a' && chars[k] <= 'z') {
                chars[k] = (char) (chars[k] - ('a' - 'A'));
            }
        }
        return new String(chars);
    }

    private static String abbreviate(String text) {
        return text.length() <= 30 ? text : text.substring(0, 30) + "...";
    }

    /** One way of reading quotes and comments. */
    private static final class Dialect {
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
