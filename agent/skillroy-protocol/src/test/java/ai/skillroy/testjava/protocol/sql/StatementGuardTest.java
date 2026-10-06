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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementGuardTest {

    /** Why the guard rejects {@code sql}, or null if it accepts it. */
    static String rejectionReason(String sql) {
        try {
            StatementGuard.check(sql);
            return null;
        } catch (RpcException rejected) {
            assertEquals(ErrorCodes.INVALID_PARAMS, rejected.error().code());
            assertEquals("sql", rejected.error().data().get("param"));
            return String.valueOf(rejected.error().data().get("reason"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // plain queries, any case, whitespace before and after the keyword
        "SELECT 1",
        "select * from person",
        "SeLeCt code FROM person",
        "  \n\t SELECT 1",
        "\r\nSELECT\n1",
        "SELECT(1)",
        "SELECT*FROM person",
        "SELECT code FROM person WHERE code = ? AND active = ?",
        // one trailing semicolon, with or without whitespace around it
        "SELECT 1;",
        "SELECT 1 ;  \n\t",
        // leading comments
        "-- leading comment\nSELECT 1",
        "--\nSELECT 1",
        "/* block */ SELECT 1",
        "/* multi\nline */ -- and a line comment\n  SELECT 1",
        "/**/SELECT 1",
        // WITH queries
        "WITH x AS (SELECT 1 AS a) SELECT a FROM x",
        "with recursive r(n) as (select 1 union all select n + 1 from r where n < 3) select n from r",
        "WITH a AS (SELECT 1 AS v), b AS (SELECT v FROM a) SELECT * FROM b;",
        // semicolons and keywords inside literals, quoted identifiers and comments
        "SELECT 'a;b' FROM person",
        "SELECT 'it''s; DROP TABLE person; --' FROM person",
        "SELECT 'DELETE FROM person' AS sql_text FROM person",
        "SELECT \"weird;name\" FROM person",
        "SELECT \"UPDATE\", \"Insert\", \"say \"\"hi\"\"; now\" FROM person",
        "SELECT 1 -- ; DROP TABLE person",
        "SELECT 1 -- DELETE FROM person;",
        "SELECT 1 /* ; DELETE FROM person */",
        "SELECT 1 /* ; */;",
        "SELECT 1 -- note\n;",
        "SELECT ''';' FROM person",
        "SELECT '' FROM person",
        // words that merely contain a forbidden word
        "SELECT updated_at, last_update, deleted, created_by, inserts, callback, executed, into_x FROM person",
        "SELECT x.dropped, intolerance FROM t x",
        // standard SQL that only looks odd to some dialect readings
        "SELECT * FROM person WHERE name LIKE 'a\\_%' ESCAPE '\\'",
        "SELECT 'C:\\temp\\' FROM person",
        "SELECT 1 FROM person /* /* */",
        "SELECT a[1], ARRAY[1, 2] FROM t",
        "SELECT $1, deposit$ FROM t",
        "SELECT 1 # 2 FROM t",
        "SELECT 1 /*! , 2 */",
        "SELECT N'text', X'0102', E'plain', U&'d\\0061t' FROM t",
        "SELECT 1e5, 0x1F, 2.5, 1.e3 FROM t",
        // scalar functions that share their name with a forbidden word
        "SELECT TRUNCATE(deposit, 1) FROM item",
        "select truncate (deposit, 1), Insert\n\t('abc', 1, 1, 'x') from item",
        "WITH x AS (SELECT INSERT(code, 1, 3, 'X') AS s FROM item) SELECT TRUNCATE(1.55, 1), s FROM x;",
    })
    void accepts(String sql) {
        assertNull(rejectionReason(sql), sql);
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
            // empty
            Arguments.of("", "empty statement"),
            Arguments.of("   \n\t ", "empty statement"),
            Arguments.of("-- only a comment", "empty statement"),
            Arguments.of("/* only a comment */", "empty statement"),
            Arguments.of(";", "empty statement"),
            Arguments.of("  ;  ", "empty statement"),
            Arguments.of("-- c\n;", "empty statement"),
            // first keyword
            Arguments.of("DELETE FROM person", "must start with SELECT or WITH, not 'DELETE'"),
            Arguments.of("delete from person where 1 = 1", "must start with SELECT or WITH, not 'delete'"),
            Arguments.of("UPDATE person SET name = 'x'", "not 'UPDATE'"),
            Arguments.of("INSERT INTO person (code) VALUES ('1')", "not 'INSERT'"),
            Arguments.of("MERGE INTO person USING (VALUES 1) v(x) ON 1 = 1", "not 'MERGE'"),
            Arguments.of("DROP TABLE person", "not 'DROP'"),
            Arguments.of("CREATE TABLE x (a INT)", "not 'CREATE'"),
            Arguments.of("ALTER TABLE person ADD COLUMN x INT", "not 'ALTER'"),
            Arguments.of("TRUNCATE TABLE person", "not 'TRUNCATE'"),
            Arguments.of("GRANT SELECT ON person TO PUBLIC", "not 'GRANT'"),
            Arguments.of("CALL some_procedure()", "not 'CALL'"),
            Arguments.of("{call some_procedure()}", "must start with SELECT or WITH"),
            Arguments.of("EXEC sp_who", "not 'EXEC'"),
            Arguments.of("EXECUTE some_procedure", "not 'EXECUTE'"),
            Arguments.of("SHOW TABLES", "not 'SHOW'"),
            Arguments.of("EXPLAIN SELECT 1", "not 'EXPLAIN'"),
            Arguments.of("VALUES (1)", "not 'VALUES'"),
            Arguments.of("TABLE person", "not 'TABLE'"),
            Arguments.of("SET SCHEMA public", "not 'SET'"),
            Arguments.of("COMMIT", "not 'COMMIT'"),
            Arguments.of("SELECTX 1", "not 'SELECTX'"),
            Arguments.of("SELECT_1", "not 'SELECT_1'"),
            Arguments.of("WITHDRAW", "not 'WITHDRAW'"),
            Arguments.of("SELECT$x FROM t", "not 'SELECT$x'"),
            Arguments.of("(SELECT 1)", "must start with SELECT or WITH, not '(SELECT 1)'"),
            Arguments.of("'SELECT' 1", "must start with SELECT or WITH"),
            Arguments.of("\"SELECT\" 1", "must start with SELECT or WITH"),
            Arguments.of("\u017fELECT 1", "must start with SELECT or WITH"),
            Arguments.of("-- SELECT 1\nDELETE FROM person", "not 'DELETE'"),
            Arguments.of("/* SELECT */ DROP TABLE person", "not 'DROP'"),
            // stacked statements
            Arguments.of("SELECT 1; DELETE FROM person", "only one statement is allowed"),
            Arguments.of("SELECT 1;SELECT 2", "only one statement is allowed"),
            Arguments.of("SELECT 1;;", "only one statement is allowed"),
            Arguments.of("SELECT 1; -- trailing comment", "only one statement is allowed"),
            Arguments.of("SELECT 1; /* c */", "only one statement is allowed"),
            Arguments.of("SELECT 'a'; DROP TABLE person; --'", "only one statement is allowed"),
            Arguments.of("SELECT \"a\"; DROP TABLE person", "only one statement is allowed"),
            Arguments.of("WITH x AS (SELECT 1) SELECT * FROM x; CALL p()", "only one statement is allowed"),
            Arguments.of("SELECT 1 /* /* */ ; DELETE FROM person */", "only one statement is allowed"),
            // unterminated constructs
            Arguments.of("SELECT 'unterminated FROM person", "unterminated string literal starting at offset 7"),
            Arguments.of("SELECT 'it''s FROM person", "unterminated string literal"),
            Arguments.of("SELECT \"unterminated FROM person", "unterminated quoted identifier"),
            Arguments.of("SELECT 1 /* unterminated", "unterminated comment starting at offset 9"),
            Arguments.of("/* unterminated SELECT 1", "unterminated comment"),
            // data-changing words after the first keyword
            Arguments.of("WITH d AS (DELETE FROM person RETURNING *) SELECT * FROM d", "'DELETE' is not allowed"),
            Arguments.of("WITH x AS (SELECT 1) DELETE FROM person", "'DELETE' is not allowed"),
            Arguments.of("WITH x AS (SELECT 1 AS a) INSERT INTO person (code) SELECT a FROM x",
                    "'INSERT' is not allowed"),
            Arguments.of("WITH x AS (SELECT 1) UPDATE person SET name = 'x'", "'UPDATE' is not allowed"),
            Arguments.of("WITH x AS (SELECT 1) MERGE INTO person USING x ON 1 = 1", "'MERGE' is not allowed"),
            Arguments.of("SELECT * FROM person FOR UPDATE", "'UPDATE' is not allowed"),
            Arguments.of("SELECT * INTO person_copy FROM person", "'INTO' is not allowed"),
            Arguments.of("SELECT 1 DELETE FROM person", "'DELETE' is not allowed"),
            Arguments.of("SELECT 1 EXEC sp_configure", "'EXEC' is not allowed"),
            Arguments.of("select 1 execute p", "'execute' is not allowed"),
            Arguments.of("SELECT 1 DROP TABLE person", "'DROP' is not allowed"),
            Arguments.of("SELECT 1 TRUNCATE TABLE person", "'TRUNCATE' is not allowed"),
            Arguments.of("SELECT 1 SHUTDOWN", "'SHUTDOWN' is not allowed"),
            Arguments.of("SELECT 1 KILL 52", "'KILL' is not allowed"),
            Arguments.of("SELECT 1 GRANT ALL TO x", "'GRANT' is not allowed"),
            Arguments.of("SELECT 1 REVOKE ALL FROM x", "'REVOKE' is not allowed"),
            Arguments.of("SELECT 1 DENY SELECT TO x", "'DENY' is not allowed"),
            Arguments.of("SELECT 1 CREATE TABLE x (a INT)", "'CREATE' is not allowed"),
            Arguments.of("SELECT 1 ALTER TABLE person DROP COLUMN name", "'ALTER' is not allowed"),
            Arguments.of("SELECT 1 BACKUP DATABASE d TO DISK = 'x'", "'BACKUP' is not allowed"),
            Arguments.of("SELECT 1 RESTORE DATABASE d", "'RESTORE' is not allowed"),
            Arguments.of("SELECT 1 DBCC CHECKDB", "'DBCC' is not allowed"),
            Arguments.of("SELECT 1 CALL p()", "'CALL' is not allowed"),
            Arguments.of("SELECT 1 UPDATETEXT t.c @p 0 NULL 'x'", "'UPDATETEXT' is not allowed"),
            Arguments.of("SELECT 1 WRITETEXT t.c @p 'x'", "'WRITETEXT' is not allowed"),
            Arguments.of("SELECT 1 RECONFIGURE", "'RECONFIGURE' is not allowed"),
            // only TRUNCATE( and INSERT( are function calls; anything else between the word and '(' is not
            Arguments.of("SELECT 1 INSERT person (code) VALUES ('1')", "'INSERT' is not allowed"),
            Arguments.of("SELECT 1 INSERT /* c */ (code) VALUES ('1')", "'INSERT' is not allowed"),
            Arguments.of("SELECT 1 INSERT -- c\n (code) VALUES ('1')", "'INSERT' is not allowed"),
            Arguments.of("SELECT 1 TRUNCATE", "'TRUNCATE' is not allowed"),
            Arguments.of("SELECT 1 TRUNCATE person", "'TRUNCATE' is not allowed"),
            Arguments.of("SELECT 1INSERT(1)", "'1INSERT' is not allowed"),
            Arguments.of("SELECT 1 FROM person WHERE DELETE(1) = 1", "'DELETE' is not allowed"),
            Arguments.of("SELECT 1 EXEC('DROP TABLE person')", "'EXEC' is not allowed"),
            Arguments.of("SELECT 1 CALL(1)", "'CALL' is not allowed"),
            Arguments.of("SELECT INTO(1)", "'INTO' is not allowed"),
            Arguments.of("SELECT INSERT(code, 1, 1, 'x') FROM item; DELETE FROM item", "only one statement is allowed"),
            // T-SQL reads a number glued to a keyword as two tokens
            Arguments.of("SELECT 1DELETE FROM person", "'1DELETE' is not allowed"),
            Arguments.of("SELECT 1 FROM person WHERE 1=1.5UPDATE person SET name = 'x'", "'5UPDATE' is not allowed"),
            // MySQL readings: backslash escapes, backticks, '--' needs a space, executable comments
            Arguments.of("SELECT 'a\\'' ; DELETE FROM person --'", "MySQL"),
            Arguments.of("SELECT \"a\\\"\" ; DELETE FROM person --\"", "MySQL"),
            Arguments.of("SELECT `a'` FROM person; DELETE FROM person -- '", "MySQL"),
            Arguments.of("SELECT 1 --x; DELETE FROM person", "MySQL"),
            Arguments.of("SELECT 1 --\u00a0; DELETE FROM person", "MySQL"),
            Arguments.of("SELECT 1 /*! ; DELETE FROM person */", "MySQL"),
            Arguments.of("SELECT 1 /*!50000 INTO OUTFILE '/tmp/x' */", "'INTO' is not allowed"),
            Arguments.of("SELECT 1 /*M! ; DROP TABLE person */", "MySQL"),
            Arguments.of("SELECT 1 # ; DELETE FROM person", "only one statement is allowed"),
            // PostgreSQL readings: dollar quotes, E'' strings
            Arguments.of("SELECT $$ ' $$; DELETE FROM person; SELECT $$ ' $$", "PostgreSQL"),
            Arguments.of("SELECT $tag$ ' $tag$ FROM person; DELETE FROM person -- '", "PostgreSQL"),
            Arguments.of("SELECT E'\\'' ; DELETE FROM person --'", "only one statement is allowed"),
            // SQL Server readings: bracket identifiers
            Arguments.of("SELECT [a'] FROM person; DELETE FROM person --']", "SQL Server"),
            Arguments.of("SELECT [x'] FROM person DELETE FROM person --']", "SQL Server"));
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void rejects(String sql, String expectedReason) {
        String reason = rejectionReason(sql);
        assertNotNull(reason, "accepted: " + sql);
        assertTrue(reason.contains(expectedReason), "reason for [" + sql + "] was: " + reason);
    }

    @Test
    void rejectsNull() {
        assertEquals("empty statement", rejectionReason(null));
    }

    @Test
    void checkReturnsTheStatementWithoutTheTrailingSemicolon() {
        assertEquals("SELECT 1", StatementGuard.check("SELECT 1;"));
        assertEquals("SELECT 1 ", StatementGuard.check("SELECT 1 ;\n  "));
        assertEquals("  SELECT 'a;'", StatementGuard.check("  SELECT 'a;';"));
        assertEquals("SELECT 1 -- note\n", StatementGuard.check("SELECT 1 -- note\n;"));
        String unchanged = "SELECT 1 -- note;";
        assertSame(unchanged, StatementGuard.check(unchanged));
        String plain = "SELECT code FROM person";
        assertSame(plain, StatementGuard.check(plain));
    }

    @Test
    void checkFailsWithInvalidParamsNamingTheSqlParameter() {
        RpcException e = assertThrows(RpcException.class, () -> StatementGuard.check("SELECT 1; DELETE FROM t"));
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertEquals("sql", e.error().data().get("param"));
        String reason = (String) e.error().data().get("reason");
        assertTrue(reason.startsWith("only one statement is allowed"), reason);
        assertEquals("sql rejected: " + reason, e.error().message());
    }

    @Test
    void forbiddenWordsAreMatchedCaseInsensitivelyAsWholeWords() {
        for (String word : StatementGuard.FORBIDDEN_WORDS) {
            assertNotNull(rejectionReason("SELECT 1 " + word), word);
            assertNotNull(rejectionReason("SELECT 1 " + word.toLowerCase(java.util.Locale.ROOT)),
                    word);
            assertNull(rejectionReason("SELECT 1 x_" + word), word);
            assertNull(rejectionReason("SELECT '" + word + "'"), word);
            assertNull(rejectionReason("SELECT \"" + word + "\""), word);
            assertNull(rejectionReason("SELECT 1 -- " + word), word);
        }
    }

    @Test
    void randomTextIsEitherAcceptedAsIsOrRejectedAsInvalidParams() {
        // Every quote, comment and escape character the dialect readings know, glued together at random: the guard
        // must never fail in any other way, and what it accepts is the input minus at most a trailing ';'.
        String alphabet = "'\"`[]$;-/*#\\ \n\rSELECTwithEe!M0aZ_().\u00a0\u00e9";
        Random random = new Random(20261001L);
        int accepted = 0;
        for (int i = 0; i < 300000; i++) {
            StringBuilder text = new StringBuilder(random.nextInt(3) == 0 ? "" : "SELECT ");
            for (int k = random.nextInt(24); k > 0; k--) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String sql = text.toString();
            String statement;
            try {
                statement = StatementGuard.check(sql);
            } catch (RpcException rejected) {
                assertEquals(ErrorCodes.INVALID_PARAMS, rejected.error().code(), sql);
                continue;
            }
            accepted++;
            assertTrue(sql.startsWith(statement), sql);
            String rest = sql.substring(statement.length());
            if (!rest.isEmpty()) {
                assertEquals(';', rest.charAt(0), sql);
                for (int k = 1; k < rest.length(); k++) {
                    char c = rest.charAt(k);
                    assertTrue(Character.isWhitespace(c) || Character.isSpaceChar(c), sql);
                }
            }
            assertFalse(statement.trim().isEmpty(), sql);
        }
        assertTrue(accepted > 1000, "the sample must exercise the accepting path too: " + accepted);
    }

    @Test
    void longStatementsAreScannedInLinearTime() {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < 20000; i++) {
            sql.append("'a;''b' || /* c; */ \"d;\" || -- e;\n");
        }
        sql.append("1;");
        long start = System.nanoTime();
        assertEquals(sql.substring(0, sql.length() - 1), StatementGuard.check(sql.toString()));
        long ms = (System.nanoTime() - start) / 1000000L;
        assertTrue(ms < 20000, "took " + ms + " ms");
    }
}
