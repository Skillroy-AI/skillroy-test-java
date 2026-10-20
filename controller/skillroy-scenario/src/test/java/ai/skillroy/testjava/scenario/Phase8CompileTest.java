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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
