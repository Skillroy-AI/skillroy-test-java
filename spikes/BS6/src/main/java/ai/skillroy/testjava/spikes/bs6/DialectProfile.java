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

import java.util.Locale;
import java.util.Set;

import net.sf.jsqlparser.parser.AbstractJSqlParser.Dialect;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.feature.Feature;

/**
 * How each {@link SqlDialect} is parsed, lexed, quoted and limited.
 *
 * <p>JSqlParser 5.4 has one grammar for every database. A dialect is a preset ({@link Dialect}) that switches lexer
 * features and a few grammar rules on: MYSQL and MARIADB turn on backslash escapes, {@code #} line comments and
 * double-quoted strings; POSTGRESQL turns on tagged dollar quotes and adjacent-literal concatenation; SQLSERVER turns on
 * square-bracket quoting; ORACLE enables PL/SQL blocks; ANSI_SQL turns on adjacent-literal concatenation. There is no
 * preset for HSQLDB or DB2: both parse with the defaults.
 */
public record DialectProfile(SqlDialect dialect, Dialect preset, LimitStyle limitStyle, Fold quotedFold,
                             char identifierQuote, boolean hashComments, boolean bracketQuotes, boolean dollarQuotes,
                             boolean oracleOuterJoin, Set<String> dualTables) {

    /** The row-limit form the guard writes (it replaces whatever form the model wrote). */
    public enum LimitStyle { LIMIT, FETCH_FIRST, TOP }

    /** How the database folds an unquoted identifier, which decides what a quoted one must spell. */
    public enum Fold { UPPER, LOWER, NONE }

    /** The parser's time-out per attempt (JSqlParser tries a simple parse, then a complex one). */
    public static final long PARSE_TIME_OUT_MS = 2_000;

    public static DialectProfile of(SqlDialect dialect) {
        return switch (dialect) {
            case HSQLDB -> new DialectProfile(dialect, null, LimitStyle.LIMIT, Fold.UPPER, '"', false, false, false,
                    false, Set.of());
            case MYSQL -> new DialectProfile(dialect, Dialect.MYSQL, LimitStyle.LIMIT, Fold.NONE, '`', true, false,
                    false, false, Set.of("DUAL"));
            case MARIADB -> new DialectProfile(dialect, Dialect.MARIADB, LimitStyle.LIMIT, Fold.NONE, '`', true, false,
                    false, false, Set.of("DUAL"));
            case POSTGRESQL -> new DialectProfile(dialect, Dialect.POSTGRESQL, LimitStyle.LIMIT, Fold.LOWER, '"',
                    false, false, true, false, Set.of());
            case DB2 -> new DialectProfile(dialect, null, LimitStyle.FETCH_FIRST, Fold.UPPER, '"', false, false, false,
                    false, Set.of());
            case ORACLE -> new DialectProfile(dialect, Dialect.ORACLE, LimitStyle.FETCH_FIRST, Fold.UPPER, '"', false,
                    false, false, true, Set.of("DUAL"));
            case SQLSERVER -> new DialectProfile(dialect, Dialect.SQLSERVER, LimitStyle.TOP, Fold.NONE, '"', false,
                    true, false, false, Set.of());
            case GENERIC -> new DialectProfile(dialect, Dialect.ANSI_SQL, LimitStyle.FETCH_FIRST, Fold.UPPER, '"',
                    false, false, false, false, Set.of());
        };
    }

    /** Configures a fresh parser for this dialect: the preset, then the guard's own settings. */
    public void configure(CCJSqlParser parser) {
        if (preset != null) {
            parser.withDialect(preset);
        }
        parser.withFeature(Feature.timeOut, PARSE_TIME_OUT_MS);
        parser.withFeature(Feature.allowUnsupportedStatements, false);
    }

    /** Whether the dialect's own identifier quote is this character ({@code [x]} counts for SQL Server). */
    public boolean quotesIdentifiersWith(char quote) {
        return quote == identifierQuote || (quote == '"' && dialect == SqlDialect.SQLSERVER);
    }

    /**
     * Whether an identifier as written (possibly quoted) names the declared (unquoted) name. Unquoted: case-insensitive.
     * Quoted: exactly the declared name as the database folds it, so that a quoted spelling cannot reach a second object
     * whose name differs only in case.
     */
    public boolean names(String written, String declared) {
        return written != null && key(written).equals(fold(declared));
    }

    /** The name the database stores for an unquoted identifier (upper-cased where the case does not matter). */
    public String fold(String unquoted) {
        return quotedFold == Fold.LOWER ? unquoted.toLowerCase(Locale.ROOT) : unquoted.toUpperCase(Locale.ROOT);
    }

    /** Strips one level of {@code "x"}, {@code `x`} or {@code [x]} quoting. */
    public static String unquote(String identifier) {
        if (identifier == null || identifier.length() < 2) {
            return identifier;
        }
        char first = identifier.charAt(0);
        char last = identifier.charAt(identifier.length() - 1);
        if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
            return identifier.substring(1, identifier.length() - 1);
        }
        return identifier;
    }

    /**
     * The comparison key of an identifier as the database resolves it: an unquoted one folded, a quoted one as written
     * (in MySQL, MariaDB and SQL Server quoting does not change case sensitivity, so both are upper-cased).
     */
    public String key(String identifier) {
        String bare = unquote(identifier);
        if (bare.equals(identifier) || quotedFold == Fold.NONE) {
            return fold(bare);
        }
        return bare;
    }
}
