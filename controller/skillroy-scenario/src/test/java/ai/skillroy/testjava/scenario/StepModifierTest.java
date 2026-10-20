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

import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Rung;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The step modifiers {@code accept} and {@code minRung} (phase8-contracts.md 7.3; 13.1 row 32). */
class StepModifierTest {

    private static final List<Rung> LADDER = List.of(new Rung("PHYSICAL", "toy.scanner.physical"),
            Rung.of("SEMANTIC"));

    private final ToyPack pack = new ToyPack();

    private Scenario load(String steps) throws ScenarioException {
        return ScenarioLoader.load(("scenario: t\npack: toy\nsteps:\n" + steps).getBytes(StandardCharsets.UTF_8),
                "t.yaml");
    }

    private Plan compile(String steps) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n" + steps).getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
    }

    private String refusal(String steps) {
        return assertThrows(ScenarioException.class, () -> compile(steps)).getMessage();
    }

    // ---- the loader

    @Test
    void aStepIsOneVerbPlusTheModifiers() throws Exception {
        Scenario scenario = load("""
                  - enter: { value: a }
                    accept: [VALUE_COERCED, lending.PASSWORD_REQUIRED]
                    minRung: PHYSICAL
                  - enter: { value: b }
                """);
        Scenario.Step first = scenario.steps().get(0);
        assertEquals("enter", first.verb());
        assertEquals(Map.of("value", "a"), first.args());
        assertEquals(Map.of("accept", List.of("VALUE_COERCED", "lending.PASSWORD_REQUIRED"), "minRung", "PHYSICAL"),
                first.modifiers());
        assertEquals(Map.of(), scenario.steps().get(1).modifiers());
        assertEquals(Map.of(), new Scenario.Step("enter", null, 3).modifiers(), "the three-argument form");
        Scenario before = load("  - accept: [VALUE_COERCED]\n    enter: { value: a }\n");
        assertEquals("enter", before.steps().get(0).verb(), "the modifier may come first");
    }

    @Test
    void theLoaderRefusesWhatIsNotTheGrammar() {
        assertLoad("  - enter: { value: a }\n    key: { action: X }\n", "must be a single-key mapping verb: args plus"
                + " the modifiers accept, minRung");
        assertLoad("  - accept: [VALUE_COERCED]\n", "'accept' is a reserved modifier, not a verb");
        assertLoad("  - minRung: PHYSICAL\n", "'minRung' is a reserved modifier, not a verb");
        assertLoad("  - enter: { value: a }\n    accept: VALUE_COERCED\n", "'accept' must be a list of 1 to 32 warning"
                + " codes");
        assertLoad("  - enter: { value: a }\n    accept: []\n", "'accept' must be a list of 1 to 32");
        assertLoad("  - enter: { value: a }\n    accept: [value_coerced]\n", "which is not a warning code");
        assertLoad("  - enter: { value: a }\n    accept: [VALUE_COERCED, VALUE_COERCED]\n", "names VALUE_COERCED"
                + " twice");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 33; i++) {
            many.append(i == 0 ? "" : ", ").append("C").append(i);
        }
        assertLoad("  - enter: { value: a }\n    accept: " + many + "]\n", "1 to 32 warning codes");
        assertLoad("  - enter: { value: a }\n    minRung: physical\n", "'minRung' must be a rung name");
        assertLoad("  - enter: { value: a }\n    minRung: [PHYSICAL]\n", "'minRung' must be a rung name");
    }

    private void assertLoad(String steps, String fragment) {
        ScenarioException e = assertThrows(ScenarioException.class, () -> load(steps));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    // ---- the compiler

    @Test
    void acceptIsTheStepsUnionWithTheVerbsPreAcceptedCodes() throws Exception {
        pack.custom = List.of(
                new PlannedCall("toy.input", Map.of("source", "Entry"), Sync.READY, 0).withAccept(Set.of(
                        "VALUE_DROPPED")),
                new PlannedCall("toy.input", Map.of("source", "Key.X"), Sync.READY, 0));
        Plan plan = compile("  - custom: {}\n    accept: [VALUE_COERCED]\n");
        assertEquals(Set.of("VALUE_COERCED", "VALUE_DROPPED"), plan.steps().get(0).calls().get(0).accept());
        assertEquals(Set.of("VALUE_COERCED"), plan.steps().get(0).calls().get(1).accept());
        assertEquals(Set.of("VALUE_DROPPED"), compile("  - custom: {}\n").steps().get(0).calls().get(0).accept(),
                "a verb's pre-acceptance without a step modifier");
        pack.custom = List.of(new PlannedCall("toy.input", Map.of(), Sync.NONE, 0).withAccept(Set.of("toy.NOPE")));
        assertTrue(refusal("  - custom: {}\n").contains("pre-accepts unknown warning code toy.NOPE"));
    }

    @Test
    void minRungMustNameARungOfEveryLadderCall() {
        pack.custom = List.of(new PlannedCall("toy.scan", Map.of(), Sync.NONE, 0).withRungs(LADDER));
        assertTrue(refusal("  - custom: {}\n    minRung: ROBOT\n").contains("minRung ROBOT is not a rung of"
                + " toy.scan's ladder [PHYSICAL, SEMANTIC]"));
        assertTrue(refusal("  - enter: { value: a }\n    minRung: PHYSICAL\n").contains("minRung: no call of 'enter'"
                + " declares a rung ladder"));
    }

    @Test
    void stepsWithoutCallsRefuseBothModifiers() {
        assertTrue(refusal("  - expect: { \"state.name\": IDLE }\n    accept: [VALUE_COERCED]\n")
                .contains("'accept' is not allowed on expect: it has no calls"));
        assertTrue(refusal("  - aiAct: { goal: x }\n    minRung: PHYSICAL\n")
                .contains("'minRung' is not allowed on aiAct: it has no calls"));
        assertTrue(refusal("  - captureFacts: { as: x, fields: { n: { size: loan.lines } } }\n    accept:"
                + " [VALUE_COERCED]\n").contains("'accept' is not allowed on captureFacts: it has no calls"));
        assertTrue(refusal("  - nothing: {}\n    accept: [VALUE_COERCED]\n")
                .contains("'accept' is not allowed on nothing: it has no calls"), "a pack verb with no call too");
    }

    @Test
    void aPackVerbNamedLikeAModifierIsRefusedAtCompilerConstruction() {
        DomainPack named = new DomainPack() {
            @Override
            public String id() {
                return "toy";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public StepLibrary steps() {
                return new StepLibrary() {
                    @Override
                    public Set<String> verbs() {
                        return Set.of("minRung");
                    }

                    @Override
                    public List<PlannedCall> compile(String verb, Object args,
                                                     ai.skillroy.testjava.scenario.pack.CompileContext ctx) {
                        return List.of();
                    }
                };
            }

            @Override
            public ai.skillroy.testjava.scenario.pack.SyncPolicy syncPolicy() {
                return pack.syncPolicy();
            }

            @Override
            public List<ai.skillroy.testjava.scenario.pack.FactProvider> facts() {
                return List.of();
            }
        };
        ScenarioException e = assertThrows(ScenarioException.class, () -> PlanCompiler.compile(
                "scenario: t\npack: toy\nsteps:\n  - poke: {}\n".getBytes(StandardCharsets.UTF_8), "t.yaml", named));
        assertTrue(e.getMessage().contains("declares the verb 'minRung', which is reserved"), e.getMessage());
    }

    @Test
    void theRecordsCheckTheirShapes() {
        PlannedCall call = new PlannedCall("p", Map.of(), Sync.NONE, 0);
        assertEquals(Set.of(), call.accept());
        assertEquals(List.of(), call.rungs());
        assertEquals(null, call.minRung());
        assertThrows(IllegalArgumentException.class, () -> call.withAccept(Set.of("bad code")));
        assertThrows(IllegalArgumentException.class, () -> call.withRungs(List.of(Rung.of("ONLY"))));
        assertThrows(IllegalArgumentException.class, () -> call.withRungs(List.of(Rung.of("A"), Rung.of("A"))));
        assertThrows(IllegalArgumentException.class, () -> call.withMinRung("A"), "no ladder");
        assertThrows(IllegalArgumentException.class, () -> call.withRungs(LADDER).withMinRung("ROBOT"));
        assertEquals("PHYSICAL", call.withRungs(LADDER).withMinRung("PHYSICAL").minRung());
        assertThrows(IllegalArgumentException.class, () -> Rung.of("physical"));
        assertThrows(IllegalArgumentException.class, () -> new Rung("A", " "));
        List<Rung> nine = List.of(Rung.of("A"), Rung.of("B"), Rung.of("C"), Rung.of("D"), Rung.of("E"),
                Rung.of("F"), Rung.of("G"), Rung.of("H"), Rung.of("I"));
        assertThrows(IllegalArgumentException.class, () -> call.withRungs(nine));
        PlannedCall kept = call.withRungs(LADDER).withMinRung("SEMANTIC").withAccept(Set.of("VALUE_DROPPED"))
                .withCapture(null).withLiteralArgs();
        assertEquals(LADDER, kept.rungs(), "every with* keeps the Phase 8 components");
        assertEquals("SEMANTIC", kept.minRung());
        assertEquals(Set.of("VALUE_DROPPED"), kept.accept());
        Plan.Call planCall = new Plan.Call("p", Map.of(), Sync.NONE, 1, null, false, null, null);
        assertEquals(Set.of(), planCall.accept(), "the eight-argument form");
    }
}
