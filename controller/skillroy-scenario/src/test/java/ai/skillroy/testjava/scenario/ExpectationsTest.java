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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expectation path grammar and every matcher; since Phase 8 (phase8-contracts.md 13.1 row 37) the list filters,
 * dotted filter fields, {@code [json]}, {@code anyOf}, {@code containsWhere} and one regex budget per evaluation.
 */
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

    // ------------------------------------------------------------------------------------ Phase 8: list filters (8.3)

    private static final String EXPONENTIAL = "^(\\w+\\s?){1,1000}$";

    private static String exponentialSubject() {
        return "a".repeat(40) + "!";
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(String path) {
        Object value = value(path);
        assertTrue(value instanceof List, path + " is " + value);
        return (List<Object>) value;
    }

    private static List<Object> codes(List<Object> lines) {
        return lines.stream().map(l -> ((Map<?, ?>) l).get("code")).map(Object.class::cast).toList();
    }

    @Test
    void aListFilterYieldsEveryMatchInListOrder() {
        assertEquals(List.of("YRD-001", "YRD-002"), codes(list("loan.lines[*code~^YRD]")));
        assertEquals(List.of(), list("loan.lines[*code=NONE]"), "no match: an empty list that resolves");
        assertEquals(List.of("YRD-001"), codes(list("loan.lines[*qty=2.0]")), "numbers compare as decimals");
        assertEquals(List.of("YRD-002", "CLN-9"), codes(list("loan.lines[*qty=1]")), "1.0 and 1 both");
        assertEquals(List.of("YRD-002"), codes(list("loan.lines[*code~^YRD][*lineNo=2]")), "chained");
        assertEquals("YRD-002", value("loan.lines[*code~^YRD][last].code"));
        assertEquals("YRD-001", value("loan.lines[*code~^YRD][0].code"));
        assertEquals("YRD-002", value("loan.lines[*code~^YRD].last.code"), ".last indexes the list");
        assertEquals("YRD-002", value("loan.lines[*code~^YRD][code=YRD-002].code"), "a single filter after it");
        assertEquals(List.of("CLN-9"), codes(list("loan.lines[* label~\\[x\\]]")), "spaces after * stripped");
        assertTrue(problem("loan.lines[*code~^YRD].qty").contains("'qty' is not an index of"), "no projection");
        assertTrue(problem("loan.total[*code=x]").contains("is not a list"), "on a scalar");
        assertTrue(problem("loan.nested[*a=1]").contains("is not a list (a mapping)"), "on a map");
        assertTrue(problem("loan.note[*a=1]").contains("is not a list (null)"), "on null: {size: 0} needs a list");
        Matchers.Outcome none = Matchers.evaluate(Map.of("size", 0L), false, null, problem("loan.note[*a=1]"));
        assertFalse(none.holds(), "a missing list never counts as none");
    }

    @Test
    void filterFieldsAreDottedSubPaths() {
        Object events = Json.parse("""
                [{"seq": 1, "topic": "input.acked", "payload": {"source": "Barcode"}},
                 {"seq": 2, "topic": "input.acked", "payload": {"source": "Key.SIGN_OFF"}},
                 {"seq": 3, "topic": "input.acked", "payload": {"source": "Barcode", "lines": [{"code": "X"}]}},
                 {"seq": 4, "topic": "state.changed", "payload": null},
                 {"seq": 5, "topic": "input.acked", "payload.source": "Barcode"},
                 "not a map"]
                """);
        Map<String, Object> root = Map.of("events", events);
        ExpectationPath barcodes = ExpectationPath.parse("app.events[*payload.source=Barcode]");
        assertEquals(List.of(1L, 3L), seqs(barcodes.resolve(root).value()), "the literal key 'payload.source' of"
                + " seq 5 is no longer matched: the field is a sub-path");
        assertEquals(List.of(3L), seqs(ExpectationPath.parse("app.events[*payload.lines.0.code=X]").resolve(root)
                .value()), "an index inside the sub-path");
        assertEquals(List.of(3L), seqs(ExpectationPath.parse("app.events[*payload.lines.last.code~^X$]")
                .resolve(root).value()));
        assertEquals(2L, ExpectationPath.parse("app.events[payload.source=Key.SIGN_OFF].seq").resolve(root).value(),
                "the single form takes a dotted field too");
        assertEquals(List.of(), ExpectationPath.parse("app.events[*payload.source.x=1]").resolve(root).value(),
                "an element on which the sub-path does not resolve does not match");
    }

    private static List<Object> seqs(Object events) {
        return ((List<?>) events).stream().map(e -> ((Map<?, ?>) e).get("seq")).map(Object.class::cast).toList();
    }

    @Test
    void referencesInAListFilterAreBoundWithTheirType() throws Exception {
        Bindings bindings = Bindings.of(Map.of("item", Map.of("code", "YRD-002", "qty", 1L, "re", "^YRD")));
        assertEquals(2, ((List<?>) ExpectationPath.parse("loan.lines[*qty=$item.qty]").bind(bindings::lookup)
                .resolve(LOAN).value()).size(), "1 equals 1.0 and 1");
        assertEquals(2, ((List<?>) ExpectationPath.parse("loan.lines[*code~$item.re]").bind(bindings::lookup)
                .resolve(LOAN).value()).size());
        assertEquals(List.of(new References.Ref("item", List.of("code"), "$item.code")),
                References.collectPath("loan.lines[*code=$item.code]"));
    }

    @Test
    void oneRegexBudgetCoversEveryElementOfAListFilter() {
        List<Object> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add(Map.of("text", exponentialSubject()));
        }
        long t0 = System.nanoTime();
        ExpectationPath.Resolution resolution = ExpectationPath.parse("loan.lines[*text~" + EXPONENTIAL + "]")
                .resolve(Map.of("lines", lines));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(resolution.resolved(), "rejected once, for the whole path");
        assertTrue(resolution.problem().startsWith("regex in [*text~"), resolution.problem());
        assertTrue(ms < 10_000L, ms + " ms (thirty budgets would be thirty seconds)");
    }

    @Test
    void theListFormsGrammarErrors() {
        for (String bad : new String[] {"loan.lines[*]", "loan.lines[* ]", "loan.lines[*=1]", "loan.lines[* =1]",
                "loan.lines[*code]", "loan.lines[*a~(]", "loan.lines[*a..b=1]", "loan.lines[.a=1]",
                "loan.lines[a.=1]"}) {
            assertThrows(IllegalArgumentException.class, () -> ExpectationPath.parse(bad), bad);
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ExpectationPath.parse("loan.lines[*]"))
                .getMessage().contains("empty filter field"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ExpectationPath.parse("loan.lines[*code]"))
                .getMessage().contains("[*field=value]"));
        assertEquals("co*de", ((ExpectationPath.Filter) ExpectationPath.parse("loan.lines[co*de=1]").segments()
                .get(1)).field(), "a * anywhere else is part of the field name");
    }

    @Test
    void theNewFormsNeedAVersion7PlanAndTheOldOnesDoNot() {
        for (String old : List.of("loan", "loan.lines[0].qty", "loan.lines[code=x].qty", "loan.lines[code~^x]",
                "caps[id=swing.tree].state", "loan.lines.last")) {
            assertEquals(1, ExpectationPath.parse(old).minPlanVersion(), old);
        }
        assertEquals("a list filter", ExpectationPath.parse("loan.lines[*code=x]").version7Item());
        assertEquals("a dotted filter field", ExpectationPath.parse("loan.lines[a.b=x]").version7Item());
        assertEquals("[json]", ExpectationPath.parse("loan.note[json]").version7Item());
        assertEquals(7, ExpectationPath.parse("loan.note[json].a").minPlanVersion());
    }

    // -------------------------------------------------------------------------------------------- [json] (8.5)

    @Test
    void theJsonSegmentParsesAStringAndContinues() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("object", "{\"qty\":2,\"code\":\"YRD-001\",\"inner\":\"{\\\"a\\\":[1,2]}\"}");
        root.put("array", "[{\"code\":\"A\"},{\"code\":\"B\"}]");
        root.put("scalar", "42");
        root.put("text", "qty: 2");
        root.put("number", 7L);
        root.put("long", "\"" + "x".repeat(SafeRegex.MAX_SUBJECT) + "\"");
        ExpectationPath.Resolution qty = ExpectationPath.parse("r.object[json].qty").resolve(root);
        assertTrue(qty.resolved(), qty.problem());
        assertEquals(2L, qty.value());
        assertEquals("B", ExpectationPath.parse("r.array[json][*code=B][0].code").resolve(root).value());
        assertEquals(42L, ExpectationPath.parse("r.scalar[json]").resolve(root).value());
        assertEquals(List.of(1L, 2L), ExpectationPath.parse("r.object[json].inner[json].a").resolve(root).value(),
                "two [json] segments in one path");
        String notJson = ExpectationPath.parse("r.text[json]").resolve(root).problem();
        assertTrue(notJson.startsWith("r.text is not JSON: "), notJson);
        assertEquals("r.number is not a string (7)", ExpectationPath.parse("r.number[json]").resolve(root).problem());
        assertEquals("r.long is longer than 1048576 characters", ExpectationPath.parse("r.long[json]").resolve(root)
                .problem());
        assertTrue(ExpectationPath.parse("r.missing[json]").resolve(root).problem().contains("no field 'missing'"));
        assertTrue(ExpectationPath.parse("r[ json ]").segments().get(0) instanceof ExpectationPath.JsonText);
    }

    // ------------------------------------------------------------------------------- size on a filtered list (8.3)

    @Test
    void sizeCountsAFilteredList() throws Exception {
        List<Object> yrd = list("loan.lines[*code~^YRD]");
        holds(Map.of("size", 2L), yrd);
        holds(Map.of("size", Map.of("gte", 1L)), yrd);
        holds(Map.of("size", 0L), list("loan.lines[*code=NONE]"));
        assertEquals("expected size 2 but was 3", failsWith(Map.of("size", 2L), list("loan.lines[*amount~.]")));
        Bindings bindings = Bindings.of(Map.of("before", Map.of("n", 1L)));
        Object delta = bindings.substitutePublic(Matchers.normalise(Map.of("size", Map.of("gt", "$before.n"))));
        assertTrue(Matchers.evaluate(delta, true, yrd, null).holds(), "a delta against a captured count");
        Object same = bindings.substitutePublic(Matchers.normalise(Map.of("size", "$before.n")));
        assertTrue(Matchers.evaluate(same, true, list("loan.lines[*code=CLN-9]"), null).holds());
    }

    // -------------------------------------------------------------------------------------------------- anyOf (8.4)

    @Test
    void anyOfHoldsWhenOneAlternativeDoes() throws Exception {
        String slip = "LOAN 7\nDEPOSIT DUE 7.00";
        holds(Map.of("anyOf", List.of(Map.of("contains", "NOT ON ANY SLIP"), Map.of("contains", "DEPOSIT DUE"))),
                slip);
        holds(Map.of("anyOf", List.of("IDLE", "LOAN")), "LOAN");
        holds(Map.of("anyOf", List.of(Map.of("not", Map.of("contains", "x")))), "abc");
        String both = failsWith(Map.of("anyOf", List.of(Map.of("contains", "x"), Map.of("equals", "y"))), "abc");
        assertTrue(both.startsWith("none of the 2 alternatives holds: expected text containing \"x\""), both);
        assertTrue(both.contains("; expected \"y\" but was \"abc\""), both);
        assertTrue(Matchers.evaluate(Matchers.normalise(Map.of("anyOf", List.of(Map.of("equals", 1L),
                Map.of("exists", false)))), false, null, "gone").holds(), "an unresolved path: exists may hold");
        Matchers.Outcome unresolved = Matchers.evaluate(Matchers.normalise(Map.of("anyOf", List.of(1L, 2L))), false,
                null, "gone");
        assertFalse(unresolved.holds());
        assertTrue(unresolved.reason().contains("did not resolve: gone"), unresolved.reason());
        holds(Map.of("not", Map.of("anyOf", List.of("IDLE", "LOAN"))), "LOCKED");
        failsWith(Map.of("not", Map.of("anyOf", List.of("IDLE", "LOAN"))), "IDLE");
        Bindings bindings = Bindings.of(Map.of("item", Map.of("code", "YRD-001")));
        Object bound = bindings.substitutePublic(Matchers.normalise(Map.of("anyOf", List.of(Map.of("contains",
                "$item.code"), "none"))));
        assertTrue(Matchers.evaluate(bound, true, "line YRD-001", null).holds());
        List<Object> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            sixteen.add(Map.of("equals", "z".repeat(300)));
        }
        String cut = failsWith(Map.of("anyOf", sixteen), "a");
        assertTrue(cut.length() <= Matchers.MAX_ANY_OF_REASON, cut.length() + "");
        assertTrue(cut.endsWith("..."), "each reason and the whole cut");
    }

    @Test
    void anyOfIsNormalisedAndBounded() {
        assertEquals(Map.of("anyOf", List.of(Map.of("equals", "a"), Map.of("equals", List.of(1L)), Map.of("gt",
                2L))), Matchers.normalise(Map.of("anyOf", List.of("a", List.of(1L), Map.of("gt", 2L)))));
        List<Object> seventeen = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            seventeen.add(i);
        }
        for (Object bad : new Object[] {List.of(), seventeen, "x", Map.of("equals", 1L)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Matchers.normalise(Map.of("anyOf", bad)), String.valueOf(bad));
            assertTrue(e.getMessage().startsWith("'anyOf' needs a list of 1 to 16 matchers, found "), e.getMessage());
        }
        IllegalArgumentException capability = assertThrows(IllegalArgumentException.class,
                () -> Matchers.normalise(Map.of("anyOf", List.of(Map.of("capability", "x")))));
        assertTrue(capability.getMessage().contains("'capability' may only guard a top-level matcher"));
        assertThrows(IllegalArgumentException.class, () -> Matchers.normalise(Map.of("anyOf", List.of(Map.of(
                "nope", 1L)))), "an unknown matcher inside");
    }

    // ------------------------------------------------------------------------------------------ containsWhere (8.4)

    private static final Object DEPOSITS = Json.parse("""
            [{"amount": "10.00", "kind": "CASH", "by": {"code": "100"}},
             {"amount": "3.50", "kind": "CARD"},
             {"amount": "3.50", "kind": "CASH", "note": null},
             "not a map"]
            """);

    @Test
    void containsWhereHoldsWhenSomeElementMatchesEveryField() {
        holds(Map.of("containsWhere", Map.of("amount", "3.5", "kind", "CASH")), DEPOSITS);
        holds(Map.of("containsWhere", Map.of("by.code", "100")), DEPOSITS);
        holds(Map.of("containsWhere", Map.of("amount", Map.of("gt", 5L))), DEPOSITS);
        holds(Map.of("containsWhere", Map.of("kind", "CARD", "by", Map.of("exists", false))), DEPOSITS);
        holds(Map.of("containsWhere", Map.of("note", Map.of("exists", false), "kind", "CASH")), DEPOSITS);
        // An ordered mapping, as a scenario's YAML gives: the message lists the fields in the matcher's order, and
        // Map.of's iteration order changes from one JVM to the next.
        String none = failsWith(Map.of("containsWhere", new java.util.TreeMap<>(Map.of("amount", "10.00", "kind",
                "CARD"))), DEPOSITS);
        assertTrue(none.startsWith("no element of the list of 4 matches {\"amount\":{\"equals\":\"10.00\"}"), none);
        failsWith(Map.of("containsWhere", Map.of("by.code", "101")), DEPOSITS);
        assertEquals("no element of the list of 0 matches {\"a\":{\"equals\":1}}",
                failsWith(Map.of("containsWhere", Map.of("a", 1L)), List.of()));
        assertEquals("'containsWhere' needs a list, but the value is a mapping",
                failsWith(Map.of("containsWhere", Map.of("a", 1L)), Map.of("a", 1L)));
        holds(Map.of("not", Map.of("containsWhere", Map.of("kind", "CHEQUE"))), DEPOSITS);
        failsWith(Map.of("not", Map.of("containsWhere", Map.of("kind", "CARD"))), DEPOSITS);
        holds(Map.of("size", 4L), DEPOSITS);
        holds(Map.of("anyOf", List.of(Map.of("containsWhere", Map.of("kind", "X")), Map.of("containsWhere",
                Map.of("kind", "CARD")))), DEPOSITS);
        Matchers.Outcome unresolved = Matchers.evaluate(Matchers.normalise(Map.of("containsWhere", Map.of("a", 1L))),
                false, null, "gone");
        assertTrue(unresolved.reason().contains("did not resolve"), unresolved.reason());
    }

    @Test
    void containsWhereIsNormalisedAndBounded() {
        assertEquals(Map.of("containsWhere", Map.of("code", Map.of("equals", "$item.code"), "qty", Map.of("gte",
                2L))), Matchers.normalise(Map.of("containsWhere", Map.of("code", "$item.code", "qty", Map.of("gte",
                2L)))));
        Map<String, Object> thirtyThree = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) {
            thirtyThree.put("f" + i, 1L);
        }
        for (Object bad : new Object[] {Map.of(), thirtyThree, List.of(Map.of("a", 1L)), "x", Map.of("a b", 1L),
                Map.of("a.", 1L), Map.of(".a", 1L), Map.of("a[0]", 1L)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Matchers.normalise(Map.of("containsWhere", bad)), String.valueOf(bad));
            assertTrue(e.getMessage().startsWith("'containsWhere' needs a mapping of 1 to 32 fields to matchers, found "),
                    e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Matchers.normalise(Map.of("containsWhere", Map.of("a",
                Map.of("capability", "x")))));
    }

    @Test
    void everyMatchesOfOneEvaluationSharesOneBudget() {
        SafeRegex.Budget budget = SafeRegex.Budget.ofMillis(100);
        Matchers.Outcome both = Matchers.evaluate(Matchers.normalise(Map.of("anyOf", List.of(Map.of("matches",
                EXPONENTIAL), Map.of("matches", EXPONENTIAL)))), true, exponentialSubject(), null, budget);
        assertFalse(both.holds());
        assertTrue(both.reason().contains("ran longer than 100 ms"), both.reason());
        assertTrue(both.reason().contains("regex budget spent"), "the second slow regex fails at once: "
                + both.reason());
        List<Object> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add(Map.of("text", exponentialSubject()));
        }
        long t0 = System.nanoTime();
        Matchers.Outcome where = Matchers.evaluate(Matchers.normalise(Map.of("containsWhere", Map.of("text",
                Map.of("matches", EXPONENTIAL)))), true, lines, null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(where.holds());
        assertTrue(ms < 10_000L, ms + " ms: one budget of 1 s for all thirty elements, not one each");
        assertTrue(Matchers.evaluate(Matchers.normalise(Map.of("matches", "^a")), true, "abc", null).holds(),
                "a fresh evaluation has a fresh budget");
    }

    @Test
    void theVersion7MatchersAreFoundAtAnyDepth() {
        assertEquals(7, Matchers.minPlanVersion(Matchers.normalise(Map.of("not", Map.of("size", Map.of("anyOf",
                List.of(1L)))))));
        assertEquals(7, Matchers.minPlanVersion(Matchers.normalise(Map.of("anyOf", List.of(Map.of("containsWhere",
                Map.of("a", 1L)))))));
        assertEquals("containsWhere", Matchers.version7Matcher(Matchers.normalise(Map.of("not", Map.of(
                "containsWhere", Map.of("a", Map.of("not", 1L)))))));
        assertEquals(1, Matchers.minPlanVersion(Matchers.normalise(Map.of("size", Map.of("gt", 1L)))));
        assertEquals(List.of("equals", "contains", "matches", "in", "gt", "gte", "lt", "lte", "size", "exists", "not",
                "anyOf", "containsWhere"), Matchers.NAMES, "thirteen matchers (phase8-contracts.md 8.11)");
    }
}
