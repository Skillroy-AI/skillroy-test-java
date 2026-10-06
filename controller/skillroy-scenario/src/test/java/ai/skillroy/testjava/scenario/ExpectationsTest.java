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

import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The expectation path grammar and every matcher. */
class ExpectationsTest {

    private static final Object LOAN = Json.parse("""
            {"id": 7, "total": "7.00", "returning": false, "note": null,
             "lines": [
               {"lineNo": 1, "code": "YRD-001", "qty": 2, "amount": "7.00", "tags": ["garden", "spade"]},
               {"lineNo": 2, "code": "YRD-002", "qty": 1.0, "amount": "3.5", "tags": []},
               {"lineNo": 3, "code": "CLN-9", "qty": 1, "amount": "2.25", "tags": [], "label": "[x]"}
             ],
             "nested": {"a": {"b": [10, 20, 30]}}}
            """);

    private static ExpectationPath.Resolution resolve(String path) {
        ExpectationPath parsed = ExpectationPath.parse(path);
        assertEquals("loan", parsed.root());
        return parsed.resolve(LOAN);
    }

    private static Object value(String path) {
        ExpectationPath.Resolution r = resolve(path);
        assertTrue(r.resolved(), path + ": " + r.problem());
        return r.value();
    }

    private static String problem(String path) {
        ExpectationPath.Resolution r = resolve(path);
        assertFalse(r.resolved(), path + " resolved to " + r.value());
        return r.problem();
    }

    @Test
    void fieldsAndIndexes() {
        assertEquals(LOAN, value("loan"));
        assertEquals("7.00", value("loan.total"));
        assertEquals(2L, value("loan.lines[0].qty"));
        assertEquals("CLN-9", value("loan.lines[last].code"));
        assertEquals("YRD-001", value("loan.lines[first].code"));
        assertEquals("CLN-9", value("loan.lines.last.code"));
        assertEquals("YRD-002", value("loan.lines.1.code"));
        assertEquals(30L, value("loan.nested.a.b[2]"));
        assertEquals(null, value("loan.note"));
    }

    @Test
    void filters() {
        assertEquals(2L, value("loan.lines[code=YRD-001].qty"));
        assertEquals("YRD-002", value("loan.lines[lineNo=2].code"), "numbers compare numerically");
        assertEquals("YRD-002", value("loan.lines[amount=3.50].code"), "3.5 == 3.50");
        assertEquals("CLN-9", value("loan.lines[code~^CL].code"));
        assertEquals("YRD-002", value("loan.lines[code~^YRD-[0-9]+2$].code"), "brackets inside a regex balance");
        assertEquals(3L, value("loan.lines[label~\\[x\\]].lineNo"), "escaped brackets");
        assertTrue(problem("loan.lines[code~^YRD].qty").contains("2 elements"));
        assertTrue(problem("loan.lines[code=NONE].qty").contains("no element"));
        assertTrue(problem("loan.total[code=x]").contains("not a list"));
    }

    @Test
    void unresolvedPaths() {
        assertTrue(problem("loan.missing").contains("no field 'missing'"));
        assertTrue(problem("loan.lines[7]").contains("out of range"));
        assertTrue(problem("loan.lines.middle").contains("not an index"));
        assertTrue(problem("loan.total.x").contains("cannot take field 'x'"));
        assertTrue(problem("loan.total[0]").contains("not a list"));
        assertTrue(problem("loan.note.x").contains("cannot take field"));
    }

    @Test
    void grammarErrors() {
        for (String bad : new String[] {"", ".x", "loan.", "loan..a", "loan[", "loan[x", "loan[]", "loan[-1]",
                "loan[=1]", "loan]", "loan.a]b", "loan[a~(]", "1loan"}) {
            assertThrows(IllegalArgumentException.class, () -> ExpectationPath.parse(bad), bad);
        }
    }

    @Test
    void referencesInFiltersAreBoundWithTheirType() throws Exception {
        Bindings bindings = Bindings.of(Map.of("item", Map.of("code", "YRD-002", "line", 3L, "re", "^CL")));
        ExpectationPath path = ExpectationPath.parse("loan.lines[code=$item.code].qty").bind(bindings::lookup);
        assertEquals(1.0, path.resolve(LOAN).value());
        assertEquals("CLN-9", ExpectationPath.parse("loan.lines[lineNo=$item.line].code").bind(bindings::lookup)
                .resolve(LOAN).value());
        assertEquals("CLN-9", ExpectationPath.parse("loan.lines[code~$item.re].code").bind(bindings::lookup)
                .resolve(LOAN).value());
        assertThrows(ResolutionException.class,
                () -> ExpectationPath.parse("loan.lines[code=$item.nope].qty").bind(bindings::lookup));
    }

    private static Matchers.Outcome eval(Object matcher, Object actual) {
        return Matchers.evaluate(Matchers.normalise(matcher), true, actual, null);
    }

    private static void holds(Object matcher, Object actual) {
        Matchers.Outcome outcome = eval(matcher, actual);
        assertTrue(outcome.holds(), Json.write(matcher) + " vs " + Json.write(actual) + ": " + outcome.reason());
    }

    private static String failsWith(Object matcher, Object actual) {
        Matchers.Outcome outcome = eval(matcher, actual);
        assertFalse(outcome.holds(), Json.write(matcher) + " vs " + Json.write(actual));
        return outcome.reason();
    }

    @Test
    void scalarEqualityIsNumericAware() {
        holds(2L, "2.00");
        holds("2.00", 2L);
        holds(Map.of("equals", "3.50"), 3.5);
        holds(-1L, "-1.0");
        holds(true, "true");
        holds("TRUE", true);
        holds("IDLE", "IDLE");
        holds(Map.of("equals", Map.of("a", 1L, "b", List.of("2.0"))), Map.of("b", List.of(2L), "a", "1"));
        assertEquals("expected \"IDLE\" but was \"LOAN\"", failsWith("IDLE", "LOAN"));
        failsWith(2L, "two");
        failsWith("7", "07x");
        failsWith(1L, true);
        failsWith(List.of(1L), List.of(1L, 2L));
        java.util.Map<String, Object> nullMatcher = new java.util.LinkedHashMap<>();
        nullMatcher.put("equals", null);
        holds(nullMatcher, null);
        failsWith(nullMatcher, "x");
    }

    @Test
    void contains() {
        holds(Map.of("contains", "DUE"), "WAIVED 3.50\nDEPOSIT DUE 7.00");
        holds(Map.of("contains", "spade"), List.of("garden", "spade"));
        holds(Map.of("contains", 2L), List.of("1.0", "2.00"));
        holds(Map.of("contains", ".5"), 3.50);
        assertTrue(failsWith(Map.of("contains", "x"), Map.of("x", 1L)).contains("string or a list"));
        failsWith(Map.of("contains", "fork"), List.of("garden"));
        failsWith(Map.of("contains", "Z"), "abc");
    }

    @Test
    void matchesSearchesTheText() {
        holds(Map.of("matches", "DUE\\s+7\\.00"), "WAIVED\nDEPOSIT DUE   7.00\n");
        holds(Map.of("matches", "^h"), "hello");
        holds(Map.of("matches", "^4\\d$"), 42L);
        failsWith(Map.of("matches", "^ello"), "hello");
        failsWith(Map.of("matches", "x"), List.of("x"));
    }

    @Test
    void inAndComparisons() {
        holds(Map.of("in", List.of("IDLE", "LOAN")), "LOAN");
        holds(Map.of("in", List.of(1L, 2L)), "2.0");
        failsWith(Map.of("in", List.of("IDLE")), "LOCKED");
        holds(Map.of("gt", 3L), "3.01");
        holds(Map.of("gte", "3.50"), 3.5);
        holds(Map.of("lt", 0L), -0.5);
        holds(Map.of("lte", "10"), 10L);
        assertTrue(failsWith(Map.of("gt", 3L), 3L).contains("> 3"));
        failsWith(Map.of("lte", 1L), "1.01");
        assertTrue(failsWith(Map.of("gt", 1L), "abc").contains("needs numbers"));
    }

    @Test
    void sizeAndExists() {
        holds(Map.of("size", 3L), List.of(1L, 2L, 3L));
        holds(Map.of("size", 0L), List.of());
        holds(Map.of("size", 2L), Map.of("a", 1L, "b", 2L));
        holds(Map.of("size", 5L), "hello");
        holds(Map.of("size", Map.of("gte", 2L)), List.of(1L, 2L, 3L));
        assertTrue(failsWith(Map.of("size", Map.of("lt", 2L)), List.of(1L, 2L)).startsWith("size 2:"));
        assertEquals("expected size 1 but was 0", failsWith(Map.of("size", 1L), List.of()));
        failsWith(Map.of("size", 1L), 5L);
        holds(Map.of("exists", true), "x");
        failsWith(Map.of("exists", true), null);
        holds(Map.of("exists", false), null);
        Matchers.Outcome missing = Matchers.evaluate(Map.of("exists", false), false, null, "no field 'x'");
        assertTrue(missing.holds());
        Matchers.Outcome present = Matchers.evaluate(Map.of("exists", true), false, null, "no field 'x'");
        assertTrue(present.reason().contains("no field 'x'"), present.reason());
    }

    @Test
    void notNegatesAndUnresolvedPathsFail() {
        holds(Map.of("not", "IDLE"), "LOAN");
        holds(Map.of("not", Map.of("contains", "x")), "abc");
        holds(Map.of("not", Map.of("not", 2L)), "2.0");
        assertTrue(failsWith(Map.of("not", 2L), "2.00").startsWith("expected not"));
        Matchers.Outcome unresolved = Matchers.evaluate(Map.of("not", Map.of("equals", 1L)), false, null, "gone");
        assertFalse(unresolved.holds());
        assertTrue(unresolved.reason().contains("did not resolve: gone"));
        assertTrue(Matchers.evaluate(Map.of("not", Map.of("exists", true)), false, null, "gone").holds());
        assertFalse(Matchers.evaluate(Map.of("equals", 1L), false, null, "gone").holds());
    }

    @Test
    void valuesRules() {
        assertEquals(0, Values.decimal("2.00").compareTo(Values.decimal(2L)));
        assertEquals(null, Values.decimal("2,00"));
        assertEquals(null, Values.decimal(" 2"));
        assertEquals(null, Values.decimal(Double.NaN));
        assertEquals("3.5", Values.stringOf(3.5));
        assertEquals("{\"a\":1}", Values.stringOf(Map.of("a", 1L)));
    }
}
