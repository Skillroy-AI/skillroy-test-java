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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import ai.skillroy.testjava.spikes.bs6.Corpus.Entry;
import ai.skillroy.testjava.spikes.bs6.GuardViolation.Reason;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * BS6's pass criteria as unit tests: every accept-corpus statement is accepted and rewritten byte-identically by two
 * independent guards (and, where the corpus states it, into the expected text); every reject-corpus statement is
 * refused with the reason the corpus names, the same way twice; each dialect has at least 25 of each and violates
 * every rule of section 5.3 at least twice; the parser limits documented in parser-limits.sql still hold.
 */
class SqlGuardCorpusTest {

    private static final Map<SqlDialect, SqlGuard> FIRST = new EnumMap<>(SqlDialect.class);
    private static final Map<SqlDialect, SqlGuard> SECOND = new EnumMap<>(SqlDialect.class);

    static SqlGuard guard(Map<SqlDialect, SqlGuard> guards, SqlDialect d) {
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
        if (e.expected() != null) {
            assertEquals(e.expected(), first.sql(), "rewrite");
        }
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
        for (int r = 1; r <= 10; r++) {
            if (perRule[r] < 2) {
                missing.add("rule " + r + " (" + perRule[r] + ")");
            }
        }
        assertTrue(missing.isEmpty(), "rules violated fewer than twice: " + missing);
    }

    /** The rule of section 5.3 a reason code belongs to (0: the spike's UNSUPPORTED, which the contract lacks). */
    static int rule(Reason r) {
        return switch (r) {
            case PARSE -> 1;
            case NOT_SELECT, DML, DDL, PROCEDURE, SET_OPERATION, LOCKING -> 2;
            case TABLE -> 3;
            case COLUMN, STAR, PERSONAL -> 4;
            case FUNCTION -> 5;
            case LITERAL -> 6;
            case ORDER -> 7;
            case LIMIT -> 8;
            case SIZE -> 9;
            case COMMENT -> 10;
            case UNSUPPORTED -> 0;
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
