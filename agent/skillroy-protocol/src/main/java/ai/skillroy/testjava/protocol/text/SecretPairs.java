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

package ai.skillroy.testjava.protocol.text;

import ai.skillroy.testjava.protocol.SecretKeys;

/**
 * The key rule inside a text (amendment A25, phase5-contracts.md 6.2, A34; Phase 8 security fix S175): in every
 * {@code key=value}, {@code key: value}, {@code "key": "value"} and {@code 'key'='value'} pair of a text whose key
 * names a secret, the value is replaced by {@value SecretKeys#REDACTED}. One rule for the agent's console text, the
 * controller's model-facing mask and the controller's view of a fact, so that each hides the same pairs. Java 8, JDK
 * only.
 *
 * <p>A key is the run of letters, digits, {@code _}, {@code .} and {@code -} before the separator ({@code =} or
 * {@code :}, spaces around it allowed), unquoted or closed by a quote. A quote may be escaped: {@code "} preceded by 1,
 * 3 or 7 backslashes is the quote of a JSON text inside a JSON text (one, two or three levels deep), so that
 * {@code {"body":"{\"token\":\"…\"}"}} hides the token. The value is
 * <ul>
 *   <li>a quoted text, in the same quote form as it opens with, its end found by the escape rule of its level (a quote
 *       escaped once more than its level's is part of the text);</li>
 *   <li>an object or a list ({@code {…}}, {@code […]}) up to its matching bracket, brackets inside the key's level of
 *       quoted text not counted, or to the end of the text when it does not close;</li>
 *   <li>else a bare run up to white space, one of {@code , ; & } ] ) " '} or an escaped quote.</li>
 * </ul>
 * The replacement keeps the value's quote form; an object, a list or a bare value of a double-quoted key becomes a
 * quoted {@value SecretKeys#REDACTED} of the key's form, so that a JSON text stays JSON; a value that is the marker
 * already is kept as it is (the rule is idempotent, on what an earlier form of it wrote too). Linear in the text: every
 * separator looks back over its own key only, and a replaced value is skipped whole.
 *
 * <p>Stateless; safe from any thread.
 */
public final class SecretPairs {

    /** The deepest escape level of a quote this rule recognises (a quote after 2^level - 1 backslashes). */
    static final int MAX_LEVEL = 3;

    /** Whether a key names a secret: the caller's key rule. */
    public interface KeyTest {

        /** True when the key (without its quotes) names a secret. */
        boolean secret(String key);
    }

    private SecretPairs() {
    }

    /**
     * The text with the value of every pair whose key {@code secretKey} names replaced (see the class comment); the
     * text itself when nothing was replaced; null for null.
     */
    public static String redact(String text, KeyTest secretKey) {
        if (text == null || secretKey == null) {
            return text;
        }
        int n = text.length();
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c != ':' && c != '=') {
                i++;
                continue;
            }
            int sep = i;
            i++;
            int k = sep - 1;
            while (k >= copied && Character.isWhitespace(text.charAt(k))) {
                k--;
            }
            char keyQuote = 0;
            int keyLevel = 0;
            if (k >= copied && (text.charAt(k) == '"' || text.charAt(k) == '\'')) {
                keyQuote = text.charAt(k);
                k--;
                int run = 0;
                while (k >= copied && text.charAt(k) == '\\') {
                    run++;
                    k--;
                }
                keyLevel = level(run);
                if (keyLevel < 0 || (keyQuote == '\'' && run != 0)) {
                    continue;
                }
            }
            int keyEnd = k + 1;
            while (k >= copied && keyChar(text.charAt(k))) {
                k--;
            }
            int keyStart = k + 1;
            if (keyStart >= keyEnd || !secretKey.secret(text.substring(keyStart, keyEnd))) {
                continue;
            }
            int valueStart = skipSpaces(text, sep + 1);
            if (valueStart >= n) {
                continue;
            }
            String replacement;
            int valueEnd;
            int run = 0;
            while (valueStart + run < n && text.charAt(valueStart + run) == '\\') {
                run++;
            }
            char first = valueStart + run < n ? text.charAt(valueStart + run) : 0;
            int valueLevel = level(run);
            if ((first == '"' && valueLevel >= 0) || (first == '\'' && run == 0)) {
                valueEnd = quotedEnd(text, valueStart + run + 1, first, first == '"' ? valueLevel : 0);
                String quote = text.substring(valueStart, valueStart + run + 1);
                replacement = quote + SecretKeys.REDACTED + quote;
            } else if (run == 0 && (first == '{' || first == '[')) {
                valueEnd = bracketEnd(text, valueStart, keyQuote == '"' ? keyLevel : 0);
                replacement = keyed(keyQuote, keyLevel);
            } else {
                valueEnd = bareEnd(text, valueStart);
                if (valueEnd <= valueStart) {
                    continue;
                }
                replacement = keyed(keyQuote, keyLevel);
            }
            String value = text.substring(valueStart, valueEnd);
            if (value.equals(replacement) || value.equals(SecretKeys.REDACTED)) {
                // Already the marker (a text this rule, or an earlier one, masked): kept as it is, so that the rule
                // is idempotent on its own output and on what an earlier form of it wrote.
                i = valueEnd;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(n);
            }
            out.append(text, copied, valueStart).append(replacement);
            copied = valueEnd;
            i = valueEnd;
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copied, n).toString();
    }

    /** The level whose quote follows {@code run} backslashes (0, 1, 3, 7: levels 0 to 3), or -1. */
    static int level(int run) {
        for (int level = 0; level <= MAX_LEVEL; level++) {
            if (run == (1 << level) - 1) {
                return level;
            }
        }
        return -1;
    }

    /** True when a double quote after {@code run} backslashes delimits a text of {@code level}. */
    static boolean delimits(int run, int level) {
        if (level == 0) {
            return run % 2 == 0;
        }
        return run % (1 << (level + 1)) == (1 << level) - 1;
    }

    /** The replacement of an object, a list or a bare value: quoted in the key's form when the key is double-quoted. */
    private static String keyed(char keyQuote, int keyLevel) {
        if (keyQuote != '"') {
            return keyQuote == '\'' ? "'" + SecretKeys.REDACTED + "'" : SecretKeys.REDACTED;
        }
        StringBuilder quote = new StringBuilder();
        for (int j = 0; j < (1 << keyLevel) - 1; j++) {
            quote.append('\\');
        }
        quote.append('"');
        return quote + SecretKeys.REDACTED + quote;
    }

    /** The index after the quote that closes a text opened before {@code from}; the text's length when none does. */
    private static int quotedEnd(String text, int from, char quote, int level) {
        int run = 0;
        for (int j = from; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') {
                run++;
                continue;
            }
            if (c == quote && (quote == '\'' ? run % 2 == 0 : delimits(run, level))) {
                return j + 1;
            }
            run = 0;
        }
        return text.length();
    }

    /** The index after the bracket that closes the object or list at {@code start}; the text's length when none does. */
    private static int bracketEnd(String text, int start, int level) {
        int depth = 0;
        boolean inText = false;
        int run = 0;
        for (int j = start; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') {
                run++;
                continue;
            }
            if (c == '"' && delimits(run, level)) {
                inText = !inText;
            } else if (!inText) {
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return j + 1;
                    }
                }
            }
            run = 0;
        }
        return text.length();
    }

    /** The end of a bare value: white space, one of {@code , ; & } ] ) " '}, or a backslash before a quote. */
    private static int bareEnd(String text, int start) {
        int j = start;
        while (j < text.length()) {
            char c = text.charAt(j);
            if (Character.isWhitespace(c) || ",;&}])\"'".indexOf(c) >= 0) {
                break;
            }
            if (c == '\\' && j + 1 < text.length() && (text.charAt(j + 1) == '"' || text.charAt(j + 1) == '\'')) {
                break;
            }
            j++;
        }
        return j;
    }

    private static boolean keyChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private static int skipSpaces(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
