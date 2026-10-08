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

import ai.skillroy.testjava.spikes.bs6.GuardViolation.Reason;

/**
 * The guard's own lexical pass, before JSqlParser sees the text (rule 10, and the lexical half of rule 6).
 *
 * <p>JSqlParser drops every comment silently, including MySQL's executable comments ({@code /*! … *}{@code /}), which
 * MySQL runs; it keeps Oracle hints in the tree. So comments are stripped here, where the guard decides: a plain
 * {@code --}, {@code /* … *}{@code /} or (MySQL, MariaDB) {@code #} comment becomes one space; a hint
 * ({@code /*+}, {@code --+}), an executable comment ({@code /*!}), a nested or an unterminated comment is refused.
 *
 * <p>The same pass refuses the lexical forms whose meaning depends on server settings the guard cannot see, so that
 * JSqlParser and the database always agree on where a literal ends: a backslash inside a string literal (MySQL's
 * {@code NO_BACKSLASH_ESCAPES}, PostgreSQL's {@code standard_conforming_strings}), double-quoted text in MySQL and
 * MariaDB ({@code ANSI_QUOTES} makes it an identifier, the parser's MySQL preset makes it a string), PostgreSQL dollar
 * quoting and Oracle's {@code q'[…]'} quoting, and identifier quotes of another dialect.
 */
final class SqlText {

    /**
     * Parentheses may nest this deep (outside literals). JSqlParser's parse time grows faster than linearly with
     * nesting (about 1 ms at 80 levels, 15 ms at 160, 160 ms at 640) and its recursion overflows a 1 MB thread stack
     * near 640 levels, all well inside the 4000-character bound; the guard's own walk and the deparser recurse too.
     */
    static final int MAX_NESTING = 32;

    private SqlText() {
    }

    /** Returns the text with every plain comment replaced by one space, or throws. */
    static String strip(String text, DialectProfile p) throws GuardViolation {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        int depth = 0;
        while (i < n) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            if (c == '(' && ++depth > MAX_NESTING) {
                throw new GuardViolation(Reason.SIZE, "parentheses nested more than " + MAX_NESTING + " deep");
            } else if (c == ')') {
                depth--;
            }
            if (c == '\'') {
                if (p.dialect() == SqlDialect.ORACLE && i > 0 && (text.charAt(i - 1) == 'q' || text.charAt(i - 1) == 'Q')
                        && (i < 2 || !isIdentifierPart(text.charAt(i - 2)))) {
                    throw new GuardViolation(Reason.LITERAL, "Oracle alternative quoting q'…' is refused");
                }
                int end = endOfString(text, i);
                out.append(text, i, end);
                i = end;
            } else if (c == '"') {
                if (p.dialect() == SqlDialect.MYSQL || p.dialect() == SqlDialect.MARIADB) {
                    throw new GuardViolation(Reason.LITERAL, "double-quoted text in " + p.dialect()
                            + " (a string or an identifier depending on ANSI_QUOTES); use '…' or `…`");
                }
                int end = endOfQuoted(text, i, '"', '"');
                out.append(text, i, end);
                i = end;
            } else if (c == '`') {
                if (!p.quotesIdentifiersWith('`')) {
                    throw new GuardViolation(Reason.PARSE, "backtick quoting is not " + p.dialect() + " syntax");
                }
                int end = endOfQuoted(text, i, '`', '`');
                out.append(text, i, end);
                i = end;
            } else if (c == '[' && p.bracketQuotes()) {
                int end = endOfQuoted(text, i, '[', ']');
                out.append(text, i, end);
                i = end;
            } else if (c == '$' && p.dollarQuotes() && isDollarQuote(text, i)) {
                throw new GuardViolation(Reason.LITERAL, "dollar-quoted string literal is refused");
            } else if (c == '-' && next == '-') {
                if (i + 2 < n && text.charAt(i + 2) == '+') {
                    throw new GuardViolation(Reason.COMMENT, "optimizer hint in a line comment (--+)");
                }
                int end = text.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.append(' ');
                i = end;
            } else if (c == '#' && p.hashComments()) {
                int end = text.indexOf('\n', i);
                end = end < 0 ? n : end;
                out.append(' ');
                i = end;
            } else if (c == '/' && next == '*') {
                char third = i + 2 < n ? text.charAt(i + 2) : '\0';
                if (third == '+') {
                    throw new GuardViolation(Reason.COMMENT, "optimizer hint comment /*+ … */");
                }
                if (third == '!') {
                    throw new GuardViolation(Reason.COMMENT, "executable comment /*! … */");
                }
                int end = text.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new GuardViolation(Reason.COMMENT, "unterminated block comment");
                }
                if (text.indexOf("/*", i + 2) >= 0 && text.indexOf("/*", i + 2) < end) {
                    throw new GuardViolation(Reason.COMMENT, "nested block comment");
                }
                out.append(' ');
                i = end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The index after the closing quote of the string literal that starts at {@code start}; {@code ''} escapes. */
    private static int endOfString(String text, int start) throws GuardViolation {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                throw new GuardViolation(Reason.LITERAL, "backslash in a string literal (its meaning depends on "
                        + "server settings)");
            }
            if (c == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new GuardViolation(Reason.PARSE, "unterminated string literal");
    }

    private static int endOfQuoted(String text, int start, char open, char close) throws GuardViolation {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == close) {
                if (i + 1 < text.length() && text.charAt(i + 1) == close && close == open) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new GuardViolation(Reason.PARSE, "unterminated quoted identifier");
    }

    /** {@code $$} or {@code $tag$} at a token start ({@code $1} is a numbered parameter, left to the parser). */
    private static boolean isDollarQuote(String text, int i) {
        if (i > 0 && isIdentifierPart(text.charAt(i - 1))) {
            return false;
        }
        int j = i + 1;
        while (j < text.length() && (Character.isLetter(text.charAt(j)) || text.charAt(j) == '_')) {
            j++;
        }
        return j < text.length() && text.charAt(j) == '$';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
