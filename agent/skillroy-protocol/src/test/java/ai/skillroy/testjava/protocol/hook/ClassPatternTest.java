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

package ai.skillroy.testjava.protocol.hook;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deny-list grammar of phase8-contracts.md section 6.3 (D561; section 13.1 row 22): every form, the non-matches,
 * every violation text, the length bound and {@code firstMatch}'s order.
 */
class ClassPatternTest {

    @Test
    void anExactNameMatchesItselfAndItsNestedClassesOnly() {
        ClassPattern inbox = ClassPattern.compile("ai.skillroy.testjava.samples.lending.Inbox");
        assertTrue(inbox.matches("ai.skillroy.testjava.samples.lending.Inbox"));
        assertTrue(inbox.matches("ai.skillroy.testjava.samples.lending.Inbox$Slot"));
        assertTrue(inbox.matches("ai.skillroy.testjava.samples.lending.Inbox$Slot$1"));
        assertFalse(inbox.matches("ai.skillroy.testjava.samples.lending.InboxSlot"));
        assertFalse(inbox.matches("ai.skillroy.testjava.samples.lending.Inbo"));
        assertFalse(inbox.matches("ai.skillroy.testjava.samples.Inbox"));
        assertFalse(inbox.matches(null));
        assertFalse(ClassPattern.compile("a.b.C").matches("a.b.CD"), "a.b.C against a.b.CD");
        assertEquals("a.b.C", ClassPattern.compile("a.b.C").pattern());
    }

    @Test
    void aStarMatchesThePackageExactlyWithItsNestedClasses() {
        ClassPattern star = ClassPattern.compile("a.b.*");
        assertTrue(star.matches("a.b.C"));
        assertTrue(star.matches("a.b.C$X"));
        assertFalse(star.matches("a.b.c.D"), "a.b.* against a.b.c.D");
        assertFalse(star.matches("a.b"));
        assertFalse(star.matches("a.b."));
        assertFalse(star.matches("a.bc.D"));
    }

    @Test
    void twoStarsMatchEverythingUnderThePackage() {
        ClassPattern tree = ClassPattern.compile("a.b.**");
        assertTrue(tree.matches("a.b.C"));
        assertTrue(tree.matches("a.b.c.D"));
        assertTrue(tree.matches("a.b.c.d.E$F"));
        assertFalse(tree.matches("a.bc.D"));
        assertFalse(tree.matches("a.b"));
        assertTrue(ClassPattern.compile("**").matches("any.Class"), "'**' alone: every class");
    }

    @Test
    void aGlobInTheLastSegmentMatchesRunsWithoutDots() {
        ClassPattern input = ClassPattern.compile("a.b.Input*");
        assertTrue(input.matches("a.b.Input"));
        assertTrue(input.matches("a.b.InputLedger"));
        assertTrue(input.matches("a.b.InputLedger$Node"), "the simple name with its nested suffix");
        assertFalse(input.matches("a.b.c.InputLedger"));
        assertFalse(input.matches("a.b.MyInput"));
        ClassPattern suffix = ClassPattern.compile("a.b.*Rule");
        assertTrue(suffix.matches("a.b.WaiverRule"));
        assertTrue(suffix.matches("a.b.Desk$Rule"));
        assertFalse(suffix.matches("a.b.WaiverRules"));
        assertFalse(suffix.matches("a.b.x.WaiverRule"));
        ClassPattern middle = ClassPattern.compile("a.b.Lo*n*Rule");
        assertTrue(middle.matches("a.b.LoanRule"));
        assertTrue(middle.matches("a.b.LoanLineRule"));
        assertFalse(middle.matches("a.b.LoRule"));
        assertFalse(middle.matches("a.b.LonRul"));
        ClassPattern both = ClassPattern.compile("a.b.*Deposit*");
        assertTrue(both.matches("a.b.Deposit"));
        assertTrue(both.matches("a.b.DepositBox$Lid"));
        assertTrue(both.matches("a.b.TheDeposits"));
        assertFalse(both.matches("a.b.Depot"));
        ClassPattern overlap = ClassPattern.compile("a.b.ab*ab");
        assertTrue(overlap.matches("a.b.abab"));
        assertFalse(overlap.matches("a.b.aba"), "the prefix and the suffix may not overlap");
    }

    @Test
    void aOneSegmentPatternAppliesToTheDefaultPackage() {
        assertTrue(ClassPattern.compile("Main").matches("Main"));
        assertTrue(ClassPattern.compile("Main").matches("Main$1"));
        assertFalse(ClassPattern.compile("Main").matches("a.Main"));
        assertTrue(ClassPattern.compile("*").matches("Main"));
        assertFalse(ClassPattern.compile("*").matches("a.Main"));
        assertTrue(ClassPattern.compile("Ma*").matches("Main"));
        assertFalse(ClassPattern.compile("Ma*").matches("a.Main"));
    }

    @Test
    void everyViolationIsNamed() {
        assertEquals("empty", ClassPattern.violation(""));
        assertEquals("empty", ClassPattern.violation(null));
        assertEquals("longer than 200 characters", ClassPattern.violation(name(201)));
        assertNull(ClassPattern.violation(name(200)));
        assertEquals("character '/' is not allowed", ClassPattern.violation("a/b/C"));
        assertEquals("character '-' is not allowed", ClassPattern.violation("a.b-c.D"));
        assertEquals("character '?' is not allowed", ClassPattern.violation("a.b.C?"));
        assertEquals("an empty segment", ClassPattern.violation("a..b"));
        assertEquals("an empty segment", ClassPattern.violation(".a"));
        assertEquals("an empty segment", ClassPattern.violation("a.b."));
        assertEquals("'**' is allowed only as the whole last segment", ClassPattern.violation("a.**.C"));
        assertEquals("'**' is allowed only as the whole last segment", ClassPattern.violation("a.b.In**"));
        assertEquals("'*' is allowed only in the last segment", ClassPattern.violation("a.*.C"));
        assertEquals("'*' is allowed only in the last segment", ClassPattern.violation("a.b*.C"));
        assertEquals("segment '1b' is not an identifier", ClassPattern.violation("a.1b.C"));
        assertEquals("segment '9*' is not an identifier", ClassPattern.violation("a.b.9*"));
        assertNull(ClassPattern.violation("a.b.*9"));
        assertNull(ClassPattern.violation("a.b.$Proxy*"));
        assertNull(ClassPattern.violation("a.b.C$D"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassPattern.compile("a..b"));
        assertEquals("an empty segment", e.getMessage());
    }

    @Test
    void firstMatchAnswersTheFirstPatternInOrder() {
        assertEquals("a.b.**", ClassPattern.firstMatch(Arrays.asList("x.Y", "a.b.**", "a.b.C"), "a.b.C"));
        assertEquals("a.b.C", ClassPattern.firstMatch(Arrays.asList("a.b.C", "a.b.**"), "a.b.C$1"));
        assertNull(ClassPattern.firstMatch(Arrays.asList("x.Y", "a.c.*"), "a.b.C"));
        assertNull(ClassPattern.firstMatch(Collections.<String>emptyList(), "a.b.C"));
        assertNull(ClassPattern.firstMatch(null, "a.b.C"));
        assertThrows(IllegalArgumentException.class,
                () -> ClassPattern.firstMatch(Arrays.asList("a..b"), "a.b.C"));
        assertEquals(64, ClassPattern.MAX_PER_SOURCE);
        assertEquals(200, ClassPattern.MAX_LENGTH);
    }

    @Test
    void matchingALongNameIsQuick() {
        StringBuilder longName = new StringBuilder("a.b.");
        for (int i = 0; i < 100_000; i++) {
            longName.append('a');
        }
        ClassPattern glob = ClassPattern.compile("a.b.*a*a*a*a*a*a*a*a*a*a*b");
        long start = System.nanoTime();
        assertFalse(glob.matches(longName.toString()));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1000, "no backtracking");
    }

    private static String name(int length) {
        StringBuilder sb = new StringBuilder("a.");
        while (sb.length() < length) {
            sb.append('b');
        }
        return sb.toString();
    }
}
