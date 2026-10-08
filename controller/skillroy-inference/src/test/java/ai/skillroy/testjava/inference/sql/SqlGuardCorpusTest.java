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

import ai.skillroy.testjava.inference.sql.Corpus.Entry;
import ai.skillroy.testjava.inference.sql.GuardViolation.Reason;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * phase5-contracts.md 15.1 item 5, the gate of spike BS6 as this module's tests: every accept-corpus statement of the
 * eight dialects is accepted and rewritten byte-identically by two independent guards into the text the corpus
 * expects; every reject-corpus statement is refused with the reason the corpus names, the same way twice; each dialect
 * has at least 25 of each and violates every rule of 5.3 that a statement can violate at least twice; the parser
 * limits documented in {@code parser-limits.sql} still hold.
 */
class SqlGuardCorpusTest {

    private static final Map<SqlDialect, SqlGuard> FIRST = new EnumMap<>(SqlDialect.class);
    private static final Map<SqlDialect, SqlGuard> SECOND = new EnumMap<>(SqlDialect.class);

    static synchronized SqlGuard guard(Map<SqlDialect, SqlGuard> guards, SqlDialect d) {
        return guards.computeIfAbsent(d, k -> new SqlGuard(LendingFixture.allowList(k)));
    }

    @AfterAll
    static void close() {
        FIRST.values().forEach(SqlGuard::close);
        SECOND.values().forEach(SqlGuard::close);
    }

    static Stream<Entry> acceptEntries() {
        return Stream.of(SqlDialect.values()).flatMap(d -> Corpus.accept(d).entries().stream());
    }

    static Stream<Entry> rejectEntries() {
        return Stream.of(SqlDialect.values()).flatMap(d -> Corpus.reject(d).entries().stream());
    }

    static Stream<Entry> limitEntries() {
        return Corpus.limits().entries().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptEntries")
    void accepts(Entry e) {
        GuardedStatement first;
        GuardedStatement second;
        try {
            first = guard(FIRST, e.dialect()).guard(e.sql());
            second = guard(SECOND, e.dialect()).guard(e.sql());
        } catch (GuardViolation v) {
            fail("refused " + v.reason() + ": " + v.fragment());
            return;
        }
        assertEquals(first, second, "two runs differ");
        assertTrue(e.expected() != null, "every accept entry states its rewrite");
        assertEquals(e.expected(), first.sql(), "rewrite");
        assertEquals(count(e.sql(), '?'), first.parameterCount(), "parameters");
        assertEquals(count(e.sql(), '?'), count(first.sql(), '?'), "parameters in the rewritten text");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectEntries")
    void rejects(Entry e) {
        GuardViolation first = assertThrows(GuardViolation.class, () -> guard(FIRST, e.dialect()).guard(e.sql()));
        GuardViolation second = assertThrows(GuardViolation.class, () -> guard(SECOND, e.dialect()).guard(e.sql()));
        assertEquals(e.reason(), first.reason(), () -> "reason (" + first.fragment() + ")");
        assertEquals(first.fragment(), second.fragment(), "two runs differ");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limitEntries")
    void parserLimitsAreAsDocumented(Entry e) {
        try {
            GuardedStatement g = guard(FIRST, e.dialect()).guard(e.sql());
            assertTrue(e.limitAccepted(), () -> "accepted, but documented as " + e.reason() + ": " + g.sql());
            if (e.expected() != null) {
                assertEquals(e.expected(), g.sql(), "rewrite");
            }
        } catch (GuardViolation v) {
            assertEquals(e.limitAccepted() ? null : e.reason(), v.reason(), () -> v.fragment());
        }
    }

    @ParameterizedTest
    @EnumSource(SqlDialect.class)
    void corporaAreLargeEnoughAndCoverEveryRuleTwice(SqlDialect d) {
        List<Entry> accept = Corpus.accept(d).entries();
        List<Entry> reject = Corpus.reject(d).entries();
        assertTrue(accept.size() >= 25, accept.size() + " accept statements");
        assertTrue(reject.size() >= 25, reject.size() + " reject statements");
        int[] perRule = new int[11];
        for (Entry e : reject) {
            perRule[rule(e.reason())]++;
        }
        List<String> missing = new ArrayList<>();
        // Rule 10, the output check, fires only on a guard defect: SqlGuardRulesTest forces it.
        for (int r = 1; r <= 9; r++) {
            if (perRule[r] < 2) {
                missing.add("rule " + r + " (" + perRule[r] + ")");
            }
        }
        assertTrue(missing.isEmpty(), "rules violated fewer than twice: " + missing);
    }

    /** The rule of phase5-contracts.md 5.3 a reason code belongs to (UNSUPPORTED is rule 2's catch-all). */
    static int rule(Reason r) {
        return switch (r) {
            case PARSE, COMMENT -> 1;
            case NOT_SELECT, DML, DDL, PROCEDURE, SET_OPERATION, LOCKING, UNSUPPORTED -> 2;
            case TABLE -> 3;
            case COLUMN, STAR, PERSONAL -> 4;
            case FUNCTION -> 5;
            case LITERAL -> 6;
            case ORDER -> 7;
            case LIMIT -> 8;
            case SIZE -> 9;
            case REWRITE -> 10;
        };
    }

    static int count(String s, char c) {
        int n = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') {
                inString = !inString;
            } else if (ch == c && !inString) {
                n++;
            }
        }
        return n;
    }
}
