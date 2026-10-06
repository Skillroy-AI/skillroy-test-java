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

package ai.skillroy.testjava.scenario;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security finding S24: scenario regexes on target-supplied text are bounded in time, length and stack depth, through
 * both {@code matches} and {@code [field~regex]}.
 */
class SafeRegexTest {

    /** Generous: the budget is one second; the bound only has to show that the search was stopped. */
    private static final long BOUND_MS = 6_000L;

    private static final String RECURSIVE = "(.|\\n)*";
    private static final String EXPONENTIAL = "^(\\w+\\s?){1,1000}$";
    private static final String QUADRATIC = "\\s+$";

    private static String recursiveSubject() {
        return "ab\n".repeat(70_000);
    }

    private static String exponentialSubject() {
        return "a".repeat(40) + "!";
    }

    private static String quadraticSubject() {
        return " ".repeat(300_000) + "x";
    }

    private static Matchers.Outcome matches(String regex, String actual) {
        return Matchers.evaluate(Map.of("matches", regex), true, actual, null);
    }

    @Test
    void deepRecursionIsAFailedOutcomeNotAnError() {
        Matchers.Outcome outcome = matches(RECURSIVE, recursiveSubject());
        assertFalse(outcome.holds());
        assertTrue(outcome.reason().contains("recurses too deeply"), outcome.reason());
        assertTrue(outcome.reason().contains("(?s).*"), outcome.reason());
    }

    @Test
    void catastrophicBacktrackingIsStoppedByTheDeadline() {
        long t0 = System.nanoTime();
        Matchers.Outcome outcome = matches(EXPONENTIAL, exponentialSubject());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(outcome.holds());
        assertTrue(outcome.reason().contains("ran longer than 1000 ms"), outcome.reason());
        assertTrue(ms < BOUND_MS, ms + " ms");
    }

    @Test
    void aPolynomialSearchOnAHugeValueIsStopped() {
        long t0 = System.nanoTime();
        Matchers.Outcome outcome = matches(QUADRATIC, quadraticSubject());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(outcome.holds());
        assertTrue(outcome.reason().contains("was stopped"), outcome.reason());
        assertTrue(ms < BOUND_MS, ms + " ms");
    }

    @Test
    void ordinaryRegexesStillWork() {
        assertTrue(matches("^LO[A-Z]+$", "LOAN").holds());
        assertFalse(matches("^x", "LOAN").holds());
        assertTrue(matches("(?s)a.*b", "a\n".repeat(50_000) + "b").holds());
    }

    @Test
    void aSubjectAboveTheCapIsRejectedNotTruncated() {
        String huge = "a".repeat(SafeRegex.MAX_SUBJECT + 1);
        SafeRegex.Rejected rejected = assertThrows(SafeRegex.Rejected.class,
                () -> SafeRegex.find(Pattern.compile("a"), huge, SafeRegex.Budget.start()));
        assertTrue(rejected.getMessage().contains("at most " + SafeRegex.MAX_SUBJECT), rejected.getMessage());
        assertEquals(0, rejected.getStackTrace().length, "no stack trace is captured");
    }

    @Test
    void anExpiredBudgetStopsTheNextSearchAtOnce() throws Exception {
        SafeRegex.Budget budget = SafeRegex.Budget.ofMillis(1);
        Thread.sleep(5);
        assertThrows(SafeRegex.Rejected.class, () -> SafeRegex.find(Pattern.compile("a"), "a", budget));
    }

    @Test
    void filtersAreBoundedToo() {
        assertFilterStopped(RECURSIVE, recursiveSubject(), "recurses too deeply");
        assertFilterStopped(EXPONENTIAL, exponentialSubject(), "ran longer than");
        assertFilterStopped(QUADRATIC, quadraticSubject(), "was stopped");
    }

    @Test
    void oneBudgetCoversAllElementsOfAFilter() {
        // Twenty elements that each take about the whole budget: without a shared budget this would take ~20 s.
        List<Object> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add(Map.of("text", exponentialSubject()));
        }
        long t0 = System.nanoTime();
        ExpectationPath.Resolution resolution = ExpectationPath.parse("loan.lines[text~" + EXPONENTIAL + "]")
                .resolve(Map.of("lines", lines));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(resolution.resolved());
        assertTrue(resolution.problem().startsWith("regex in [text~"), resolution.problem());
        assertTrue(ms < BOUND_MS, ms + " ms");
    }

    private static void assertFilterStopped(String regex, String subject, String reason) {
        long t0 = System.nanoTime();
        ExpectationPath.Resolution resolution = ExpectationPath.parse("loan.lines[text~" + regex + "]")
                .resolve(Map.of("lines", List.of(Map.of("text", "short"), Map.of("text", subject))));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(resolution.resolved());
        assertTrue(resolution.problem().contains(reason), resolution.problem());
        assertTrue(ms < BOUND_MS, ms + " ms");
    }
}
