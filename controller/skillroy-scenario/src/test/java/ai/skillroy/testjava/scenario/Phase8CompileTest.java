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
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DelegatingDomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase8-contracts.md 13.1 row 38, the plan-reader parts of plan version 7 (8.2, 8.7, decisions D595, D596): the
 * version, the strict step and call keys from 7 on with the reserved keys of section 7, the gates that refuse each
 * Phase 8 item below 7, and a step's {@code facts} written and read back. The compiler parts of {@code captureFacts},
 * list filters, {@code [json]}, the two matchers and {@code {has}} follow with their sections; packages EP and ES add
 * their own methods here.
 */
class Phase8CompileTest {

    private final ToyPack pack = new ToyPack();

    private Plan compile(String steps) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n" + steps).getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
    }

    private static String reason(String planText) {
        return assertThrows(ScenarioException.class, () -> Plan.parse(planText)).reason();
    }

    private static String reason(Map<String, Object> json) {
        return assertThrows(ScenarioException.class, () -> Plan.fromJson(json)).reason();
    }

    private static String v6(String planText) {
        assertTrue(planText.startsWith("{\n  \"planVersion\": 7,\n"), planText);
        return planText.replace("\"planVersion\": 7", "\"planVersion\": 6");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> step(Map<String, Object> plan, int index) {
        return (Map<String, Object>) ((List<Object>) plan.get("steps")).get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstCall(Map<String, Object> plan) {
        return (Map<String, Object>) ((List<Object>) step(plan, 0).get("calls")).get(0);
    }

    // ------------------------------------------------------------------------------------------------ the version

    @Test
    void version7IsWrittenVersions1To7AreReadAnd8IsRefused() throws Exception {
        assertEquals(7, Plan.VERSION);
        Plan plan = compile("  - poke: {}\n  - expect: { \"state.name\": IDLE }\n");
        assertEquals(7, plan.planVersion());
        String text = plan.toJsonText();
        assertEquals(text, Plan.parse(text).toJsonText());
        assertEquals(text, Plan.parse(v6(text)).toJsonText(), "a version 6 plan is read as version 7");
        for (String version : List.of("0", "8", "-1")) {
            assertEquals("invalid plan: planVersion " + version + " is not supported (expected 1, 2, 3, 4, 5, 6 or"
                    + " 7)", reason(text.replace("\"planVersion\": 7", "\"planVersion\": " + version)));
        }
        assertFalse(text.contains("\"facts\""), "a step without captured facts is written as before");
    }

    @Test
    void theKeySetsAreTheContractsAndTheSection7KeysAreReserved() {
        assertEquals(java.util.Set.of("id", "verb", "source", "calls", "expect", "facts"), Plan.STEP_KEYS_V7);
        assertEquals(java.util.Set.of("primitive", "args", "sync", "timeoutMs", "await", "handlesPrompt", "capture",
                "guard", "accept", "rungs", "minRung"), Plan.CALL_KEYS_V7);
        assertEquals(java.util.Set.of("captureFacts"), Plan.PHASE8_VERBS);
    }

    // ------------------------------------------------------------------------------------------------ strict keys

    @Test
    void anUnknownStepKeyIsRefusedAt7AndIgnoredBelow() throws Exception {
        String text = compile("  - poke: {}\n").toJsonText();
        String extra = text.replace("\"verb\": \"poke\",", "\"verb\": \"poke\", \"after\": 1,");
        assertEquals("invalid plan: step s1: unknown key 'after'", reason(extra));
        assertEquals(text, Plan.parse(v6(extra)).toJsonText(), "below 7 the key is dropped, as every earlier reader"
                + " did");
    }

    @Test
    void anUnknownCallKeyIsRefusedAt7AndIgnoredBelow() throws Exception {
        String text = compile("  - poke: {}\n").toJsonText();
        String extra = text.replace("\"primitive\": \"toy.poke\",", "\"primitive\": \"toy.poke\", \"retry\": 2,");
        assertEquals("invalid plan: call toy.poke: unknown key 'retry'", reason(extra));
        assertEquals(text, Plan.parse(v6(extra)).toJsonText());
        String cleanup = compile("  - lock: { name: a }\n").toJsonText().replace("\"primitive\": \"toy.unlock\",",
                "\"primitive\": \"toy.unlock\", \"retry\": 2,");
        assertEquals("invalid plan: call toy.unlock: unknown key 'retry'", reason(cleanup), "a cleanup call too");
    }

    @Test
    void theReservedCallKeysPassTheKeyCheckAt7() throws Exception {
        Map<String, Object> json = Json.parseObject(compile("  - poke: {}\n").toJsonText());
        Map<String, Object> call = firstCall(json);
        call.put("accept", new ArrayList<>(List.of("INPUT_QUEUED")));
        call.put("rungs", new ArrayList<>(List.of("ACTION", "COMPONENT")));
        call.put("minRung", "ACTION");
        try {
            Plan.fromJson(json);
        } catch (ScenarioException e) {
            // Section 7's own readers may refuse a value; the key check never refuses these names.
            assertFalse(e.reason().contains("unknown key"), e.reason());
        }
    }

    // ------------------------------------------------------------------------------------------------ the gates

    @Test
    void aListFilterOrADottedFilterFieldNeedsVersion7() throws Exception {
        String list = compile("  - expect: { \"ui[*code=x].text\": a }\n").toJsonText();
        assertEquals("invalid plan: expectation ui[*code=x].text: a list filter needs a version 7 plan",
                reason(v6(list)));
        String dotted = compile("  - expect: { \"ui[payload.source=x]\": { exists: true } }\n").toJsonText();
        assertEquals("invalid plan: expectation ui[payload.source=x]: a dotted filter field needs a version 7 plan",
                reason(v6(dotted)));
        String inVerify = compile("  - poke: {}\n").toJsonText().replace("\"verify\": []",
                "\"verify\": [{\"path\": \"ui[*a~^b]\", \"matcher\": {\"size\": 0}}]");
        assertEquals("invalid plan: expectation ui[*a~^b]: a list filter needs a version 7 plan", reason(v6(inVerify)),
                "verify too");
        assertEquals(list, Plan.parse(list).toJsonText(), "read at 7");
        String single = compile("  - expect: { \"ui[code=x].text\": a }\n").toJsonText();
        assertEquals(single, Plan.parse(v6(single)).toJsonText(), "a single filter on a plain field reads at 6");
        String valueDot = compile("  - expect: { \"caps[id=swing.tree].state\": OK }\n").toJsonText();
        assertEquals(valueDot, Plan.parse(v6(valueDot)).toJsonText(), "a dot in the value is no dotted field");
    }

    @Test
    void anOperatorObjectInAnAwaitMatchNeedsVersion7() throws Exception {
        String text = compile("  - call: { primitive: toy.emit, sync: EVENT, await: { topic: toy.done, match: { id:"
                + " x } } }\n").toJsonText();
        assertTrue(text.contains("\"id\": \"x\""), text);
        assertEquals("invalid plan: call toy.emit await: {has} needs a version 7 plan", reason(v6(text.replace(
                "\"id\": \"x\"", "\"id\": {\"has\": \"x\"}"))));
        assertEquals("invalid plan: call toy.emit await: {has} needs a version 7 plan", reason(v6(text.replace(
                "\"id\": \"x\"", "\"id\": {\"in\": [\"x\"]}"))), "an operator object of any shape below 7");
    }

    @Test
    void theMatchOfASyncAwaitCallIsCheckedAndItsOperatorObjectNeedsVersion7() throws Exception {
        String text = compile("  - call: { primitive: sync.await, args: { topics: toy.done, match: { id: x },"
                + " timeoutMs: 1000 } }\n").toJsonText();
        assertTrue(text.contains("\"id\": \"x\""), text);
        assertEquals(text, Plan.parse(text).toJsonText());
        assertEquals("invalid plan: call sync.await await: {has} needs a version 7 plan", reason(v6(text.replace(
                "\"id\": \"x\"", "\"id\": {\"has\": \"x\"}"))));
        String list = reason(text.replace("\"id\": \"x\"", "\"id\": [\"x\"]"));
        assertTrue(list.startsWith("invalid plan: call sync.await: 'match.id' must be"), list);
        assertTrue(reason(v6(text.replace("\"id\": \"x\"", "\"id\": [\"x\"]"))).startsWith(
                "invalid plan: call sync.await: 'match.id' must be"), "at every version: a list never matched");
        String other = compile("  - call: { primitive: toy.poke, args: { match: { id: [x] } } }\n").toJsonText();
        assertEquals(other, Plan.parse(other).toJsonText(), "only a sync.await call's match is an event match");
    }

    @Test
    void theVerbCaptureFactsAndAStepsFactsNeedVersion7() throws Exception {
        String text = factsPlan().toJsonText();
        assertEquals("invalid plan: the verb 'captureFacts' needs a version 7 plan", reason(v6(text)));
        Map<String, Object> json = Json.parseObject(v6(text));
        Map<String, Object> s2 = step(json, 1);
        s2.put("verb", "poke");
        assertEquals("invalid plan: step s2: 'facts' needs a version 7 plan", reason(json));
    }

    // ------------------------------------------------------------------------------------------------ facts

    /** A compiled plan with a {@code captureFacts} step appended by hand (the compiler's part follows, 8.7). */
    private Plan factsPlan() throws ScenarioException {
        Plan base = compile("  - poke: {}\n");
        Map<String, Plan.FactField> fields = new LinkedHashMap<>();
        fields.put("total", new Plan.FactField("loan.total", false));
        fields.put("scans", new Plan.FactField("loan.lines[code=$item.code]", true));
        List<Plan.Step> steps = new ArrayList<>(base.steps());
        steps.add(new Plan.Step("s2", "captureFacts", 2, List.of(), List.of(), new Plan.FactCapture("before",
                fields)));
        List<Plan.Slot> slots = new ArrayList<>(base.slots());
        slots.add(new Plan.Slot("before", "capture", null, null, null, new Plan.CaptureDecl("s2",
                List.of("total", "scans"))));
        return new Plan(base.planVersion(), base.scenarioId(), base.pack(), base.packVersion(), base.sourceHash(),
                base.requires(), slots, steps, base.verify(), base.cleanup(), base.launchProperties(), base.artifacts(),
                base.onPrompt(), base.stepTimeoutMs(), base.ui(), base.inference(), base.oracles(),
                base.resolutionGraph(), base.bindTimeoutMs(), base.web(), base.images());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStepsFactsAreWrittenAfterExpectSortedAndReadBackByteForByte() throws Exception {
        Plan plan = factsPlan();
        Plan.Step step = plan.steps().get(1);
        assertEquals(List.of("scans", "total"), List.copyOf(step.facts().fields().keySet()), "the fields sorted");
        String text = plan.toJsonText();
        Map<String, Object> json = Json.parseObject(text);
        Map<String, Object> s2 = step(json, 1);
        assertEquals(List.of("id", "verb", "source", "calls", "expect", "facts"), List.copyOf(s2.keySet()));
        assertEquals(Map.of("slot", "before", "fields", Map.of("scans", Map.of("size",
                "loan.lines[code=$item.code]"), "total", Map.of("path", "loan.total"))), s2.get("facts"));
        Map<String, Object> facts = (Map<String, Object>) s2.get("facts");
        assertEquals(List.of("slot", "fields"), List.copyOf(facts.keySet()));
        assertEquals(List.of("scans", "total"), List.copyOf(((Map<String, Object>) facts.get("fields")).keySet()));
        assertEquals(List.of("id", "verb", "source", "calls", "expect"), List.copyOf(step(json, 0).keySet()),
                "no facts key on another step");
        Plan read = Plan.parse(text);
        assertEquals(plan, read);
        assertEquals(text, read.toJsonText(), "byte-identical round trip");
        assertEquals(plan.hash(), read.hash());
        assertNull(read.steps().get(0).facts());
        assertEquals(new Plan.FactField("loan.lines[code=$item.code]", true), read.steps().get(1).facts().fields()
                .get("scans"));
    }

    @Test
    void theReaderChecksAStepsFacts() throws Exception {
        String text = factsPlan().toJsonText();
        assertEquals("invalid plan: step s2: 'facts' is allowed on captureFacts only", reasonWith(text,
                s -> s.put("verb", "poke")));
        assertEquals("invalid plan: step s2: captureFacts needs 'facts'", reasonWith(text, s -> s.remove("facts")));
        assertEquals("invalid plan: step s2: captureFacts has no calls", reasonWith(text, s -> s.put("calls",
                step(parse(text), 0).get("calls"))));
        assertEquals("invalid plan: step s2: captureFacts has no calls", reasonWith(text, s -> s.put("expect",
                List.of(Map.of("path", "state.name", "matcher", Map.of("equals", "IDLE"))))));
        assertEquals("invalid plan: step s2 facts: unknown key 'when'", reasonWith(text, s -> facts(s).put("when",
                "now")));
        assertEquals("invalid plan: 'slot' must be a string", reasonWith(text, s -> facts(s).remove("slot")));
        assertEquals("invalid plan: step s2 facts: the slot name must match [A-Za-z_][A-Za-z0-9_]*, found 1st",
                reasonWith(text, s -> facts(s).put("slot", "1st")));
        assertEquals("invalid plan: step s2 facts: captureFacts needs 1 to 32 fields, found 0", reasonWith(text,
                s -> facts(s).put("fields", new LinkedHashMap<>())));
        assertEquals("invalid plan: step s2 facts: captureFacts needs 1 to 32 fields, found 33", reasonWith(text,
                s -> {
                    Map<String, Object> many = new LinkedHashMap<>();
                    for (int i = 0; i < 33; i++) {
                        many.put("f" + i, Map.of("path", "loan.total"));
                    }
                    facts(s).put("fields", many);
                }));
        assertEquals("invalid plan: step s2 facts: the field name 'a-b' must match [A-Za-z0-9_]+", reasonWith(text,
                s -> fields(s).put("a-b", Map.of("path", "loan.total"))));
        assertEquals("invalid plan: step s2 facts: field 'total' needs exactly one of 'path' and 'size'",
                reasonWith(text, s -> fields(s).put("total", Map.of("path", "loan.total", "size", "loan.lines"))));
        assertEquals("invalid plan: step s2 facts: field 'total' needs exactly one of 'path' and 'size'",
                reasonWith(text, s -> fields(s).put("total", Map.of("value", "loan.total"))));
        assertEquals("invalid plan: step s2 facts: field 'total' needs exactly one of 'path' and 'size'",
                reasonWith(text, s -> fields(s).put("total", "loan.total")));
        assertEquals("invalid plan: step s2 facts: field 'total': 'path' must be a string", reasonWith(text,
                s -> fields(s).put("total", Map.of("path", 3L))));
        String grammar = reasonWith(text, s -> fields(s).put("total", Map.of("path", "loan..total")));
        assertTrue(grammar.startsWith("invalid plan: step s2 facts: field 'total': path 'loan..total': empty field"
                + " name"), grammar);
        assertEquals("invalid plan: step s2: unknown key 'fact'", reasonWith(text, s -> s.put("fact", 1L)),
                "a misspelt member is an unknown step key");
    }

    @Test
    void theRecordsKeepTheirConstructorsAndCheckTheirShapes() {
        Plan.Step plain = new Plan.Step("s1", "poke", 1, List.of(), List.of());
        assertNull(plain.facts(), "the five-argument constructor: no facts");
        Plan.FactCapture one = new Plan.FactCapture("before", Map.of("n", new Plan.FactField("loan.lines", true)));
        assertThrows(IllegalArgumentException.class, () -> new Plan.Step("s1", "poke", 1, List.of(), List.of(), one),
                "facts on another verb");
        assertThrows(IllegalArgumentException.class, () -> new Plan.Step("s1", "captureFacts", 1, List.of(),
                List.of(new Expectation("state.name", Map.of("equals", "IDLE"), null)), one), "facts beside a check");
        assertThrows(IllegalArgumentException.class, () -> new Plan.FactCapture("before", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Plan.FactCapture("$before", Map.of("n",
                new Plan.FactField("loan.lines", true))));
        assertThrows(IllegalArgumentException.class, () -> new Plan.FactCapture("before", Map.of("a.b",
                new Plan.FactField("loan.lines", true))));
        assertThrows(IllegalArgumentException.class, () -> new Plan.FactField(" ", false));
        assertThrows(IllegalArgumentException.class, () -> new Plan.FactField(null, true));
        Map<String, Plan.FactField> fields = new LinkedHashMap<>();
        fields.put("z", new Plan.FactField("loan.total", false));
        fields.put("a", new Plan.FactField("loan.lines", true));
        Plan.FactCapture sorted = new Plan.FactCapture("before", fields);
        assertEquals(List.of("a", "z"), List.copyOf(sorted.fields().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> sorted.fields().put("b", null), "immutable");
        assertEquals(Map.of("size", "loan.lines"), sorted.fields().get("a").toJson());
    }

    // ------------------------------------------------------------------------------------------------ the helpers

    @Test
    void aPathNamesThePhase8ItemItHolds() {
        assertEquals(1, ExpectationPath.parse("loan.lines[code=x].qty").minPlanVersion());
        assertEquals(1, ExpectationPath.parse("caps[id=swing.tree]").minPlanVersion());
        assertEquals(1, ExpectationPath.parse("loan.lines[0][last]").minPlanVersion());
        ExpectationPath list = ExpectationPath.parse("events[*topic=input.acked]");
        assertEquals(7, list.minPlanVersion());
        assertEquals("a list filter", list.version7Item());
        ExpectationPath dotted = ExpectationPath.parse("events[payload.source=Key.SIGN_OFF]");
        assertEquals(7, dotted.minPlanVersion());
        assertEquals("a dotted filter field", dotted.version7Item());
        assertNull(ExpectationPath.parse("ui").version7Item());
    }

    @Test
    void aMatcherNamesThePhase8MatcherItHoldsAnywhere() {
        assertEquals(1, Matchers.minPlanVersion(Map.of("equals", Map.of("anyOf", List.of()))), "an operand is a"
                + " value");
        assertEquals(1, Matchers.minPlanVersion(Matchers.normalise(Map.of("not", Map.of("size", Map.of("gt",
                2L))))));
        assertEquals(1, Matchers.minPlanVersion("text"));
        Map<String, Object> anyOf = Map.of("anyOf", List.of(Map.of("equals", 1L)));
        assertEquals(7, Matchers.minPlanVersion(anyOf));
        assertEquals("anyOf", Matchers.version7Matcher(Map.of("not", anyOf)));
        Map<String, Object> where = Map.of("containsWhere", Map.of("code", Map.of("equals", "x")));
        assertEquals("containsWhere", Matchers.version7Matcher(Map.of("size", where)));
        assertTrue(Matchers.mentions(Map.of("anyOf", List.of(Map.of("equals", 1L), where)), "containsWhere"));
        assertTrue(Matchers.mentions(Map.of("containsWhere", Map.of("a", Map.of("not", anyOf))), "anyOf"));
        assertFalse(Matchers.mentions(Map.of("in", List.of(where)), "containsWhere"), "an operand is a value");
        assertNull(Matchers.version7Matcher(Map.of("equals", 1L)));
    }

    // ------------------------------------------------------------------------------------------------ section 7 (ES)

    @Test
    void aCallsAcceptRungsAndMinRungAreWrittenAtVersion7AndRefusedBelow() throws Exception {
        pack.custom = List.of(new ai.skillroy.testjava.scenario.pack.PlannedCall("toy.scan", Map.of("barcode", "1"),
                ai.skillroy.testjava.scenario.pack.Sync.NONE, 0).withRungs(List.of(
                new ai.skillroy.testjava.scenario.pack.Rung("PHYSICAL", "toy.scanner.physical"),
                ai.skillroy.testjava.scenario.pack.Rung.of("SEMANTIC"))));
        Plan plan = compile("  - custom: {}\n    accept: [VALUE_DROPPED, VALUE_COERCED]\n    minRung: PHYSICAL\n");
        String text = plan.toJsonText();
        Map<String, Object> call = firstCall(parse(text));
        assertEquals(List.of("VALUE_COERCED", "VALUE_DROPPED"), call.get("accept"), "sorted");
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("name", "SEMANTIC");
        semantic.put("capability", null);
        assertEquals(List.of(Map.of("name", "PHYSICAL", "capability", "toy.scanner.physical"), semantic),
                call.get("rungs"));
        assertEquals("PHYSICAL", call.get("minRung"));
        Plan back = Plan.parse(text);
        assertEquals(text, back.toJsonText(), "round trip byte-identical");
        assertEquals(plan.steps().get(0).calls().get(0), back.steps().get(0).calls().get(0));
        assertFalse(compile("  - poke: {}\n").toJsonText().contains("\"accept\""), "absent when empty");

        String old = v6(text);
        assertEquals("invalid plan: call toy.scan: 'accept' needs a version 7 plan", reason(old));
        Map<String, Object> json = parse(old);
        firstCall(json).remove("accept");
        assertEquals("invalid plan: call toy.scan: 'rungs' needs a version 7 plan", reason(json));
        firstCall(json).remove("rungs");
        assertEquals("invalid plan: call toy.scan: 'minRung' needs a version 7 plan", reason(json));

        Map<String, Object> bad = parse(text);
        firstCall(bad).put("minRung", "ROBOT");
        assertTrue(reason(bad).contains("minRung ROBOT is not a rung of its ladder [PHYSICAL, SEMANTIC]"));
        bad = parse(text);
        firstCall(bad).put("accept", new ArrayList<>(List.of("lower")));
        assertTrue(reason(bad).contains("'accept' holds lower, which is not a distinct warning code"));
        bad = parse(text);
        firstCall(bad).put("rungs", new ArrayList<>(List.of(Map.of("name", "ONLY", "capability", "x"))));
        firstCall(bad).remove("minRung");
        assertTrue(reason(bad).contains("a rung ladder has 2 to 8 rungs, found 1"), reason(bad));
    }

    // ------------------------------------------------------------------------- the gates of 8.3-8.5 at the reader

    @Test
    void jsonAndTheTwoMatchersNeedVersion7() throws Exception {
        String json = compile("  - expect: { \"state.note[json].qty\": 2 }\n").toJsonText();
        assertEquals("invalid plan: expectation state.note[json].qty: [json] needs a version 7 plan", reason(v6(json)));
        assertEquals(json, Plan.parse(json).toJsonText(), "read at 7");
        String anyOf = compile("  - expect: { \"state.name\": { anyOf: [IDLE, LOAN] } }\n").toJsonText();
        assertEquals("invalid plan: expectation state.name: matcher 'anyOf' needs a version 7 plan", reason(v6(anyOf)));
        String where = compile("  - expect: { \"loan.lines\": { not: { containsWhere: { code: x } } } }\n")
                .toJsonText();
        assertEquals("invalid plan: expectation loan.lines: matcher 'containsWhere' needs a version 7 plan",
                reason(v6(where)), "nested inside not");
        assertEquals(where, Plan.parse(where).toJsonText());
    }

    @Test
    void inAStoredRowCheckContainsWhereIsRefusedAndAnyOfNeedsVersion7() throws Exception {
        pack.storedTables = List.of(Phase3CompileTest.waiverTable());
        for (String matcher : List.of("rule_code: { containsWhere: { a: 1 } }", "rows: { containsWhere: { a: 1 } }",
                "\"sum(amount)\": { not: { containsWhere: { a: 1 } } }")) {
            ScenarioException e = assertThrows(ScenarioException.class, () -> compile("  - expect: { db.loan_waiver: {"
                    + " where: { loan_id: 1 }, " + matcher + " } }\n"), matcher);
            assertTrue(e.reason().endsWith("db.loan_waiver: 'containsWhere' needs a list, and a stored-row cell is a"
                    + " scalar"), e.reason());
        }
        String anyOf = compile("  - expect: { db.loan_waiver: { where: { loan_id: 1 }, rule_code: { anyOf: [A, B] }"
                + " } }\n").toJsonText();
        assertEquals("invalid plan: db check loan_waiver: matcher 'anyOf' needs a version 7 plan", reason(v6(anyOf)));
        assertEquals(anyOf, Plan.parse(anyOf).toJsonText());
    }

    // ------------------------------------------------------------------------------------- captureFacts (8.7)

    private static final String CAPTURE = """
              - poke: {}
              - captureFacts:
                  slot: before
                  fields:
                    total: loan.total
                    scans: { size: "loan.lines[*code=x]" }
            """;

    @Test
    void captureFactsCompilesToTheFactsMemberAndDeclaresItsSlot() throws Exception {
        Plan plan = compile(CAPTURE + "  - expect: { \"loan.lines[*code=x]\": { size: { gt: $before.scans } } }\n");
        Plan.Step s2 = plan.steps().get(1);
        assertEquals("captureFacts", s2.verb());
        assertEquals(List.of(), s2.calls());
        assertEquals(List.of(), s2.expect());
        assertEquals(List.of("scans", "total"), List.copyOf(s2.facts().fields().keySet()), "sorted");
        assertEquals(new Plan.FactField("loan.lines[*code=x]", true), s2.facts().fields().get("scans"));
        assertEquals(new Plan.FactField("loan.total", false), s2.facts().fields().get("total"));
        Plan.Slot slot = plan.slots().stream().filter(sl -> sl.name().equals("before")).findFirst().orElseThrow();
        assertEquals("capture", slot.kind());
        assertEquals(new Plan.CaptureDecl("s2", List.of("scans", "total")), slot.capture());
        String text = plan.toJsonText();
        assertTrue(text.contains("\"facts\": {\n        \"slot\": \"before\",\n        \"fields\": {\n          \"scans\":"
                + " {\n            \"size\": \"loan.lines[*code=x]\"\n          },\n          \"total\": {\n"
                + "            \"path\": \"loan.total\"\n          }"), text);
        Plan read = Plan.parse(text);
        assertEquals(text, read.toJsonText(), "byte-identical round trip");
        assertEquals(plan, read);
    }

    @Test
    void captureFactsCompileErrors() throws Exception {
        assertCompileError("captureFacts: slot name 'env' is reserved", CAPTURE.replace("slot: before", "slot: env"));
        assertCompileError("captureFacts: slot 'item' is also declared in resolve", "scenario: t\npack: toy\n"
                + "resolve:\n  item: { fixture: { code: x } }\nsteps:\n" + CAPTURE.replace("slot: before",
                "slot: item"), true);
        assertCompileError("captureFacts: slot 'before' is already captured by step s1", "  - waitFor: { topic: x,"
                + " capture: { slot: before, fields: { id: id } } }\n" + CAPTURE);
        assertCompileError("captureFacts: 'fields' needs 1 to 32 fields, found 0", "  - captureFacts: { slot: b,"
                + " fields: {} }\n");
        StringBuilder many = new StringBuilder("  - captureFacts:\n      slot: b\n      fields:\n");
        for (int i = 0; i < 33; i++) {
            many.append("        f").append(i).append(": loan.total\n");
        }
        assertCompileError("captureFacts: 'fields' needs 1 to 32 fields, found 33", many.toString());
        assertCompileError("captureFacts: the field name 'a-b' must match [A-Za-z0-9_]+", "  - captureFacts: { slot: b,"
                + " fields: { a-b: loan.total } }\n");
        assertCompileError("captureFacts b.n: a field is a path or {size: path}, found keys [path, size]",
                "  - captureFacts: { slot: b, fields: { n: { size: loan.lines, path: loan.total } } }\n");
        assertCompileError("captureFacts b.n: a field is a path or {size: path}, found 3", "  - captureFacts: { slot:"
                + " b, fields: { n: 3 } }\n");
        assertCompileError("captureFacts b.n: unknown fact root 'nope' in path 'nope.x'", "  - captureFacts: { slot:"
                + " b, fields: { n: nope.x } }\n");
        assertCompileError("captureFacts b.n: captureFacts reads fact roots; db.* is a stored-row check",
                "  - captureFacts: { slot: b, fields: { n: db.loan_waiver } }\n");
        assertCompileError("captureFacts b.n: path 'loan..x': empty field name", "  - captureFacts: { slot: b, fields:"
                + " { n: loan..x } }\n");
        assertCompileError("captureFacts: needs exactly 'slot' and 'fields', found keys [fields, slot, when]",
                "  - captureFacts: { slot: b, fields: { n: loan.total }, when: now }\n");
        assertCompileError("refers to slot 'before', which step s2 captures", CAPTURE.replace("loan.total",
                "\"loan.lines[code=$before.scans]\""));
        assertCompileError("$before.total refers to slot 'before', which step s3 captures", "  - expect: {"
                + " \"loan.total\": $before.total }\n" + CAPTURE);
        assertCompileError("captured slot 'before' has no field 'nope'", CAPTURE + "  - expect: { \"loan.total\":"
                + " $before.nope }\n");
        Plan later = compile(CAPTURE + "  - captureFacts: { slot: after, fields: { n: \"loan.lines[*code=$before"
                + ".total]\" } }\n");
        assertEquals("after", later.steps().get(2).facts().slot(), "a later capture may filter on an earlier one");
    }

    @Test
    void captureFactsIsReservedForAPack() {
        DomainPack shadowing = new DelegatingDomainPack(pack, "shadow", "1") {
            @Override
            public StepLibrary steps() {
                return new StepLibrary() {
                    @Override
                    public Set<String> verbs() {
                        return Set.of("captureFacts");
                    }

                    @Override
                    public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) {
                        return List.of();
                    }
                };
            }
        };
        ScenarioException e = assertThrows(ScenarioException.class, () -> PlanCompiler.compile(
                "scenario: t\npack: toy\nsteps: []\n".getBytes(StandardCharsets.UTF_8), "t.yaml", shadowing));
        assertTrue(e.reason().contains("declares the verb 'captureFacts', which is reserved"), e.reason());
        assertTrue(BaseSteps.VERBS.contains("captureFacts"));
        assertTrue(PlanCompiler.RESERVED_VERBS.contains("captureFacts"));
    }

    // ------------------------------------------------------------------------------------------ {has} (8.9)

    @Test
    void waitForsMatchIsAScalarOrHas() throws Exception {
        assertCompileError("waitFor: 'match.id' must be a scalar or {has: scalar}, found a list",
                "  - waitFor: { topic: x, match: { id: [a] } }\n");
        assertCompileError("waitFor: 'match.id' must be a scalar or {has: scalar}, found a mapping with keys [a]",
                "  - waitFor: { topic: x, match: { id: { a: 1 } } }\n");
        assertCompileError("waitFor: 'match.id' must be a scalar or {has: scalar}, found {has: a list}",
                "  - waitFor: { topic: x, match: { id: { has: [a] } } }\n");
        Plan plan = compile("  - waitFor: { topic: input.requested, match: { state: DEPOSIT, validInputs: { has:"
                + " Key.DEPOSIT } } }\n");
        assertEquals(Map.of("state", "DEPOSIT", "validInputs", Map.of("has", "Key.DEPOSIT")),
                plan.steps().get(0).calls().get(0).args().get("match"));
        String text = plan.toJsonText();
        assertEquals(text, Plan.parse(text).toJsonText());
        assertEquals("invalid plan: call sync.await await: {has} needs a version 7 plan", reason(v6(text)));
        Plan reference = compile("  - waitFor: { topic: x, match: { items: { has: $env.id } } }\n");
        assertEquals(Map.of("has", "$env.id"), ((Map<?, ?>) reference.steps().get(0).calls().get(0).args()
                .get("match")).get("items"), "a reference in has, resolved at run");
        Plan await = compile("  - call: { primitive: toy.emit, sync: EVENT, await: { topic: toy.done, match: { ids:"
                + " { has: x } } } }\n");
        assertEquals(Map.of("ids", Map.of("has", "x")), await.steps().get(0).calls().get(0).await().match());
        assertCompileError("'match.ids' must be a scalar or {has: scalar}, found {has: null}", "  - call: { primitive:"
                + " toy.emit, sync: EVENT, await: { topic: toy.done, match: { ids: { has: null } } } }\n");
    }

    @Test
    void aPackSyncAwaitCallWithABadMatchIsRefused() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("topics", "toy.done");
        args.put("match", Map.of("id", List.of("a")));
        args.put("timeoutMs", 1000L);
        pack.custom = List.of(new PlannedCall("sync.await", args, Sync.NONE, 2000));
        assertCompileError("verb 'custom': call sync.await: 'match.id' must be a scalar or {has: scalar}, found a list",
                "  - custom: {}\n");
        args.put("match", Map.of("id", Map.of("has", "a")));
        pack.custom = List.of(new PlannedCall("sync.await", args, Sync.NONE, 2000));
        assertEquals(Map.of("id", Map.of("has", "a")), compile("  - custom: {}\n").steps().get(0).calls().get(0)
                .args().get("match"));
    }

    private void assertCompileError(String expected, String steps) {
        assertCompileError(expected, steps, false);
    }

    private void assertCompileError(String expected, String text, boolean whole) {
        ScenarioException e = assertThrows(ScenarioException.class, () -> {
            if (whole) {
                PlanCompiler.compile(text.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
            } else {
                compile(text);
            }
        }, text);
        assertTrue(e.reason().contains(expected), e.reason());
    }

    // ------------------------------------------------------------------------------------- the schema (8.4, 8.7)

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaOffersTheTwoMatchersTheVerbAndTheEightRoots() throws Exception {
        Map<String, Object> schema = ScenarioSchema.generate(pack, List.of());
        assertTrue(String.valueOf(schema.get("description")).endsWith("Fact roots: state, loan, app, ui, dialogs,"
                + " caps, devices, web, hooks, events."), String.valueOf(schema.get("description")));
        Map<String, Object> defs = (Map<String, Object>) schema.get("$defs");
        String check = Json.write(defs.get("check"));
        assertTrue(check.contains("\"properties\":{\"anyOf\":{},\"capability\""), check);
        assertTrue(check.contains("\"properties\":{\"containsWhere\":{},\"capability\""), check);
        String step = Json.write(defs.get("step"));
        assertTrue(step.contains("\"captureFacts\":{\"type\":\"object\""), step);
        Scenario scenario = ScenarioLoader.load(("scenario: t\npack: toy\nsteps:\n" + CAPTURE).getBytes(
                StandardCharsets.UTF_8), "t.yaml");
        assertEquals("captureFacts", scenario.steps().get(1).verb());
        Object document = ai.skillroy.testjava.data.yaml.StrictYaml.read(("scenario: t\npack: toy\nsteps:\n" + CAPTURE
                + "  - expect: { \"loan.lines\": { containsWhere: { code: x } }, \"state.name\": { anyOf: [A] } }\n")
                .getBytes(StandardCharsets.UTF_8)).root();
        assertEquals(List.of(), ScenarioSchema.validate(schema, document));
    }

    // ------------------------------------------------------------------------------------------------ JSON helpers

    private interface Edit {
        void apply(Map<String, Object> step) throws Exception;
    }

    private static Map<String, Object> parse(String text) {
        try {
            return Json.parseObject(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String reasonWith(String text, Edit edit) throws Exception {
        Map<String, Object> json = parse(text);
        edit.apply(step(json, 1));
        return reason(json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> facts(Map<String, Object> step) {
        return (Map<String, Object>) step.get("facts");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Map<String, Object> step) {
        return (Map<String, Object>) facts(step).get("fields");
    }
}
