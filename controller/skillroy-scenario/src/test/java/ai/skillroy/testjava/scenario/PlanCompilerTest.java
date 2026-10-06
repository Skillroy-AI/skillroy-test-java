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
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.Sync;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCompilerTest {

    private final ToyPack pack = new ToyPack();

    static byte[] resource(String name) throws Exception {
        try (InputStream in = PlanCompilerTest.class.getResourceAsStream("/scenarios/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return in.readAllBytes();
        }
    }

    private Plan compile(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "test.yaml", pack);
    }

    private ScenarioException fails(String yaml) {
        return assertThrows(ScenarioException.class, () -> compile(yaml));
    }

    private static String scenario(String steps) {
        return "scenario: t\npack: toy\nsteps:\n" + steps;
    }

    @Test
    void compilesTheCommittedPlanByteForByte() throws Exception {
        Plan plan = PlanCompiler.compile(resource("toy-loan.yaml"), "toy-loan.yaml", pack);
        String expected = new String(resource("toy-loan.plan.json"), StandardCharsets.UTF_8);
        assertEquals(expected, plan.toJsonText());
        assertEquals(ScenarioLoader.sourceHash(resource("toy-loan.yaml")), plan.sourceHash());
    }

    @Test
    void committedPlanRoundTrips(@TempDir Path dir) throws Exception {
        String text = new String(resource("toy-loan.plan.json"), StandardCharsets.UTF_8);
        Plan parsed = Plan.parse(text);
        assertEquals(text, parsed.toJsonText());
        Path file = dir.resolve("nested/toy-loan.plan.json");
        parsed.write(file);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
        Plan read = Plan.read(file);
        assertEquals(parsed, read);
        assertEquals(parsed.hash(), read.hash());
        assertTrue(read.hash().startsWith("sha256:"));
        assertEquals(Sync.READY, read.steps().get(0).calls().get(0).sync());
        assertEquals("toy.loan.authorisations", read.steps().get(7).expect().get(0).capability());
        assertEquals(new AwaitSpec("toy.done,toy.failed", Map.of("id", "$item.code", "zone", "A"), 2000),
                read.steps().get(9).calls().get(0).await());
        assertEquals(new AwaitSpec("toy.poked", null, 10_000), read.steps().get(10).calls().get(0).await());
        assertNull(read.steps().get(0).calls().get(0).await());
    }

    @Test
    void compilationIsDeterministic() throws Exception {
        byte[] yaml = resource("toy-loan.yaml");
        String first = PlanCompiler.compile(yaml, "toy-loan.yaml", pack).toJsonText();
        for (int i = 0; i < 5; i++) {
            assertEquals(first, PlanCompiler.compile(yaml.clone(), "elsewhere.yaml", new ToyPack()).toJsonText());
        }
        assertFalse(first.contains("elsewhere"), "no source path in the plan");
    }

    @Test
    void packArgsAreWrittenWithSortedKeys() throws Exception {
        Plan plan = compile(scenario("  - mapOf: {}\n"));
        String text = plan.toJsonText();
        assertTrue(text.indexOf("\"alpha\"") < text.indexOf("\"mid\"") && text.indexOf("\"mid\"")
                < text.indexOf("\"omega\"") && text.indexOf("\"omega\"") < text.indexOf("\"zeta\""), text);
        assertTrue(text.indexOf("\"b\"") < text.indexOf("\"y\""), text);
        assertTrue(text.indexOf("\"k1\"") < text.indexOf("\"k2\""), text);
    }

    @Test
    void isUpToDate(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("toy-loan.yaml");
        Files.write(yaml, resource("toy-loan.yaml"));
        Path planFile = dir.resolve("plan/toy-loan.plan.json");
        assertFalse(PlanCompiler.isUpToDate(yaml, planFile));
        PlanCompiler.compileTo(yaml, pack, planFile);
        assertTrue(PlanCompiler.isUpToDate(yaml, planFile));
        assertTrue(PlanCompiler.isUpToDate(yaml, planFile, pack));
        ToyPack newer = new ToyPack();
        newer.version = "1.0.1";
        assertTrue(PlanCompiler.isUpToDate(yaml, planFile), "the two-argument form checks the source hash only");
        assertFalse(PlanCompiler.isUpToDate(yaml, planFile, newer));
        Files.writeString(yaml, Files.readString(yaml) + "# edited\n");
        assertFalse(PlanCompiler.isUpToDate(yaml, planFile));
        Files.writeString(planFile, "{ not json");
        assertFalse(PlanCompiler.isUpToDate(yaml, planFile));
    }

    @Test
    void unknownVerbListsAvailableVerbs() {
        ScenarioException e = fails(scenario("  - mark: a\n  - dance: {}\n"));
        assertEquals("t", e.scenarioId());
        assertEquals(5, e.line());
        assertTrue(e.getMessage().contains("unknown verb 'dance'"), e.getMessage());
        assertTrue(e.getMessage().contains("signOn") && e.getMessage().contains("waitFor"), e.getMessage());
    }

    @Test
    void packErrorsGetScenarioAndLine() {
        ScenarioException e = fails(scenario("  - mark: a\n  - invalid: {}\n"));
        assertEquals("t line 5: invalid: always rejected", e.getMessage());
        ScenarioException bug = fails(scenario("  - boom: {}\n"));
        assertEquals(4, bug.line());
        assertTrue(bug.getMessage().contains("pack bug"), bug.getMessage());
    }

    @Test
    void scenarioMustNameThisPack() {
        ScenarioException e = assertThrows(ScenarioException.class, () -> PlanCompiler.compile(
                "scenario: t\npack: other\nsteps: []\n".getBytes(StandardCharsets.UTF_8), "t.yaml", pack));
        assertTrue(e.getMessage().contains("names pack 'other'"), e.getMessage());
    }

    @Test
    void verbLookupPrefersThePack() throws Exception {
        Plan plan = compile(scenario("  - key: { action: DUE }\n  - press: { key: ENTER, by: { name: x } }\n"));
        assertEquals("toy.input", plan.steps().get(0).calls().get(0).primitive());
        Plan.Call press = plan.steps().get(1).calls().get(0);
        assertEquals("swing.key", press.primitive());
        assertEquals(Map.of("key", "ENTER", "target", Map.of("name", "x")), press.args());
    }

    @Test
    void everyBaseVerb() throws Exception {
        Plan plan = compile(scenario("""
                  - call: { primitive: toy.poke, args: { a: 1 }, sync: settle }
                  - call: { primitive: toy.read }
                  - click: { target: h12, rung: COMPONENT }
                  - type: { target: h13, text: 42, mode: DISPATCH, clear: false }
                  - press: { key: F2, modifiers: [SHIFT] }
                  - dismiss: { button: OK }
                  - dismiss:
                  - waitFor: { topic: [prompt.waiting, other], match: { id: x } }
                  - expect: { "app.toy.name": IDLE, "ui.windows": { size: { gte: 1 } } }
                  - mark: { label: here, attrs: { n: 1 } }
                  - mark: 7
                """));
        List<Plan.Call> calls = new ArrayList<>();
        plan.steps().forEach(s -> calls.addAll(s.calls()));
        assertEquals(List.of("toy.poke", "toy.read", "swing.click", "swing.type", "swing.key", "swing.dismiss",
                "swing.dismiss", "sync.await", "trace.mark", "trace.mark"),
                calls.stream().map(Plan.Call::primitive).toList());
        assertEquals(List.of(Sync.SETTLE, Sync.NONE, Sync.SETTLE, Sync.SETTLE, Sync.SETTLE, Sync.SETTLE, Sync.SETTLE,
                Sync.NONE, Sync.NONE, Sync.NONE), calls.stream().map(Plan.Call::sync).toList());
        assertEquals(Map.of("a", 1L), calls.get(0).args());
        assertEquals(Map.of("rung", "COMPONENT", "target", "h12"), calls.get(2).args());
        assertEquals("42", calls.get(3).args().get("text"));
        assertEquals(Map.of("key", "F2", "modifiers", List.of("SHIFT")), calls.get(4).args());
        assertEquals(Map.of("button", "OK"), calls.get(5).args());
        assertEquals(Map.of(), calls.get(6).args());
        assertTrue(calls.get(5).handlesPrompt() && calls.get(6).handlesPrompt());
        assertEquals(Map.of("match", Map.of("id", "x"), "timeoutMs", 10_000L, "topics",
                List.of("prompt.waiting", "other")), calls.get(7).args());
        assertEquals(11_000, calls.get(7).timeoutMs());
        assertTrue(calls.get(7).handlesPrompt(), "a wait on a prompt topic handles prompts");
        assertFalse(calls.get(0).handlesPrompt());
        assertEquals(Map.of("attrs", Map.of("n", 1L), "label", "here"), calls.get(8).args());
        assertEquals(Map.of("label", "7"), calls.get(9).args());
        Plan.Step expect = plan.steps().get(8);
        assertEquals(List.of(), expect.calls());
        assertEquals(List.of(new Expectation("app.toy.name", Map.of("equals", "IDLE"), null),
                new Expectation("ui.windows", Map.of("size", Map.of("gte", 1L)), null)), expect.expect());
    }

    @Test
    void baseInputVerbsPassTheModalAndWindowActivationFlagsToTheSwingModule() throws Exception {
        // Amendments A8 and A10: without force a click, type or key into a window a modal dialog blocks fails with
        // MODAL_OPEN, and activateWindow lets the COMPONENT rung reach an unfocused window. The base verbs must not
        // reject those arguments.
        Plan plan = compile(scenario("""
                  - click: { by: { action: DUE }, rung: COMPONENT, activateWindow: true, force: true }
                  - type: { by: { name: entry }, text: "7.00", force: true }
                  - press: { key: ESCAPE, by: { name: entry }, force: true }
                """));
        List<Plan.Call> calls = new ArrayList<>();
        plan.steps().forEach(s -> calls.addAll(s.calls()));
        assertEquals(Map.of("by", Map.of("action", "DUE"), "rung", "COMPONENT", "activateWindow", true, "force",
                true), calls.get(0).args());
        assertEquals(Map.of("by", Map.of("name", "entry"), "text", "7.00", "force", true), calls.get(1).args());
        assertEquals(Map.of("key", "ESCAPE", "target", Map.of("name", "entry"), "force", true), calls.get(2).args());
        assertTrue(fails(scenario("  - type: { by: {name: a}, text: x, activateWindow: true }\n")).getMessage()
                .contains("unknown argument 'activateWindow'"), "activateWindow belongs to click only");
    }

    @Test
    void baseVerbArgumentsAreChecked() {
        assertTrue(fails(scenario("  - click: { by: {name: a}, target: h1 }\n")).getMessage().contains("exactly one"));
        assertTrue(fails(scenario("  - click: { rung: ACTION }\n")).getMessage().contains("exactly one"));
        assertTrue(fails(scenario("  - click: { by: {name: a}, speed: 3 }\n")).getMessage()
                .contains("unknown argument 'speed'"));
        assertTrue(fails(scenario("  - type: { target: h1 }\n")).getMessage().contains("'text'"));
        assertTrue(fails(scenario("  - press: { target: h1 }\n")).getMessage().contains("'key'"));
        assertTrue(fails(scenario("  - call: { args: {} }\n")).getMessage().contains("'primitive'"));
        assertTrue(fails(scenario("  - call: { primitive: x, sync: SOON }\n")).getMessage().contains("'sync'"));
        assertTrue(fails(scenario("  - call: { primitive: x, args: [1] }\n")).getMessage().contains("'args'"));
        assertTrue(fails(scenario("  - waitFor: { within: 1s }\n")).getMessage().contains("'topic'"));
        assertTrue(fails(scenario("  - waitFor: { topic: a, within: soon }\n")).getMessage()
                .contains("waitFor.within"));
        assertTrue(fails(scenario("  - mark: [a]\n")).getMessage().contains("label"));
        assertTrue(fails(scenario("  - expect: {}\n")).getMessage().contains("path: matcher"));
        assertTrue(fails(scenario("  - dismiss: { now: true }\n")).getMessage().contains("unknown argument"));
    }

    @Test
    void expectationsAreCompiledToNormalForm() throws Exception {
        Plan plan = compile(scenario("""
                  - expect:
                      "loan.total": "2.00"
                      "loan.lines": { size: 2 }
                      "loan.lines[0].code": { not: YRD-001 }
                      "dialogs": { exists: false }
                      "caps[id=swing.tree].state": { in: [OK, DEGRADED] }
                      "loan.authorisations": { size: 0, capability: toy.loan.authorisations }
                """));
        List<Expectation> e = plan.steps().get(0).expect().stream().map(Expectation.class::cast).toList();
        assertEquals(Map.of("equals", "2.00"), e.get(0).matcher());
        assertEquals(Map.of("size", 2L), e.get(1).matcher());
        assertEquals(Map.of("not", Map.of("equals", "YRD-001")), e.get(2).matcher());
        assertEquals(Map.of("exists", false), e.get(3).matcher());
        assertEquals(Map.of("in", List.of("OK", "DEGRADED")), e.get(4).matcher());
        assertEquals(Map.of("size", 0L), e.get(5).matcher());
        assertEquals("toy.loan.authorisations", e.get(5).capability());
        assertNull(e.get(0).capability());
    }

    @Test
    void badExpectationsAreCompileErrors() {
        assertTrue(fails(scenario("  - expect: { \"nope.x\": 1 }\n")).getMessage()
                .contains("unknown fact root 'nope'"));
        assertTrue(fails(scenario("  - expect: { \"loan..x\": 1 }\n")).getMessage().contains("empty field"));
        assertTrue(fails(scenario("  - expect: { \"loan[x\": 1 }\n")).getMessage().contains("unclosed"));
        assertTrue(fails(scenario("  - expect: { \"loan[abc]\": 1 }\n")).getMessage().contains("neither an index"));
        assertTrue(fails(scenario("  - expect: { \"loan[code~(]\": 1 }\n")).getMessage().contains("bad regex"));
        assertTrue(fails(scenario("  - expect: { \"loan.total\": { bigger: 1 } }\n")).getMessage()
                .contains("unknown matcher 'bigger'"));
        assertTrue(fails(scenario("  - expect: { \"loan.total\": { gt: 1, lt: 3 } }\n")).getMessage()
                .contains("exactly one"));
        assertTrue(fails(scenario("  - expect: { \"loan.total\": { gt: abc } }\n")).getMessage()
                .contains("needs a number"));
        assertTrue(fails(scenario("  - expect: { \"loan.total\": { matches: \"[\" } }\n")).getMessage()
                .contains("bad regex"));
        assertTrue(fails(scenario("  - expect: { \"loan.total\": { exists: maybe } }\n")).getMessage()
                .contains("true or false"));
        assertTrue(fails(scenario("  - expect: { \"loan.total\": { size: -1 } }\n")).getMessage()
                .contains("non-negative"));
        assertTrue(fails(scenario("  - expect: { \"loan.total\": { capability: x } }\n")).getMessage()
                .contains("needs a matcher next to 'capability'"));
        assertTrue(fails(scenario("  - expect: { \"loan.total\": { not: { equals: 1, capability: x } } }\n"))
                .getMessage().contains("exactly one"));
        assertTrue(fails(scenario("  - expect: { \"loan.total\": { in: 3 } }\n")).getMessage().contains("list"));
        ScenarioException verify = fails("scenario: t\npack: toy\nsteps: []\nverify:\n  - { \"x.y\": 1 }\n");
        assertEquals(5, verify.line());
    }

    @Test
    void syncLevelsMustFitThePack() {
        pack.syncPolicy = new ai.skillroy.testjava.scenario.pack.SyncPolicy(null, List.of(), null, null, null);
        assertTrue(fails(scenario("  - ackOnly: { value: 1 }\n")).getMessage().contains("no ack topic"));
        assertTrue(fails(scenario("  - enter: { value: 1 }\n")).getMessage().contains("no ready topics"));
    }

    @Test
    void cleanupIsCollectedInRegistrationOrderAndTimeoutsDefault() throws Exception {
        Plan plan = compile("scenario: t\npack: toy\nresolve:\n"
                + "  c: {fixture: {code: 1}, credential: {realm: staff, key: code}}\n"
                + "steps:\n"
                + "  - lock: { name: a }\n  - signOn: { staff: $c }\n  - lock: { name: b }\ntimeouts: { step: 3s }\n");
        assertEquals(List.of("toy.unlock", "toy.input", "toy.unlock"),
                plan.cleanup().stream().map(Plan.Call::primitive).toList());
        assertEquals(Map.of("which", "a"), plan.cleanup().get(0).args());
        assertEquals(3000, plan.cleanup().get(0).timeoutMs(), "non-positive timeout -> step timeout");
        assertEquals(5000, plan.cleanup().get(1).timeoutMs());
        assertEquals(3000, plan.stepTimeoutMs());
        assertEquals(Map.of(), plan.onPrompt());
    }

    @Test
    void markedCallsHandlePrompts() throws Exception {
        Plan plan = compile(scenario("  - answer: { choice: OK }\n  - expectPrompt: { within: 2s }\n"
                + "  - enter: { value: 1 }\n  - nothing: {}\n"));
        assertTrue(plan.steps().get(0).calls().get(0).handlesPrompt());
        assertTrue(plan.steps().get(1).calls().get(0).handlesPrompt());
        assertFalse(plan.steps().get(2).calls().get(0).handlesPrompt());
        assertEquals(List.of(), plan.steps().get(3).calls());
        assertTrue(plan.toJsonText().contains("\"handlesPrompt\": true"));
    }

    @Test
    void invalidPlansAreRejected() {
        assertThrows(ScenarioException.class, () -> Plan.parse("[]"));
        assertThrows(ScenarioException.class, () -> Plan.parse("{\"planVersion\": 2}"));
        ScenarioException unknown = assertThrows(ScenarioException.class, () -> Plan.parse(
                "{\"planVersion\":1,\"scenarioId\":\"x\",\"resolutionGraph\":[]}"));
        assertTrue(unknown.getMessage().contains("unknown key 'resolutionGraph'"), unknown.getMessage());
        Map<String, Object> json = Json.parseObject(new String(assertDoesNotThrowResource(), StandardCharsets.UTF_8));
        json.remove("steps");
        ScenarioException missing = assertThrows(ScenarioException.class, () -> Plan.fromJson(json));
        assertTrue(missing.getMessage().contains("'steps'"), missing.getMessage());
    }

    private static byte[] assertDoesNotThrowResource() {
        try {
            return resource("toy-loan.plan.json");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void discoversPacksWithServiceLoader() throws Exception {
        assertEquals(List.of("toy"), DomainPacks.discover(getClass().getClassLoader()).stream()
                .map(p -> p.id()).toList());
        assertEquals("toy", DomainPacks.select(null, List.of(pack)).id());
        assertTrue(assertThrows(ScenarioException.class, () -> DomainPacks.select("lending", List.of(pack)))
                .getMessage().contains("packs found: [toy]"));
        assertTrue(assertThrows(ScenarioException.class, () -> DomainPacks.select(null, List.of()))
                .getMessage().contains("no domain pack"));
    }
}
