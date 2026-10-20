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

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 acceptance 15.1 items 7 and 11, the schema parts (phase5-contracts.md 10.2, 13.3): the scenario schema of a
 * pack accepts the scenarios the loader and compiler accept and refuses a malformed document with a JSON path; the
 * validator covers the subset the generator writes and refuses anything else.
 */
class ScenarioSchemaTest {

    private final ToyResolution toy = new ToyResolution();
    private final ToyPack pack = toy.pack();

    private static Object yaml(String text) throws Exception {
        return StrictYaml.read(text.getBytes(StandardCharsets.UTF_8)).root();
    }

    private List<ScenarioSchema.Problem> problems(String text) throws Exception {
        return ScenarioSchema.validate(ScenarioSchema.generate(pack, List.of()), yaml(text));
    }

    private ScenarioSchema.Problem only(String text) throws Exception {
        List<ScenarioSchema.Problem> found = problems(text);
        assertEquals(1, found.size(), found.toString());
        return found.get(0);
    }

    @Test
    void theModulesScenariosAreValid() throws Exception {
        String toyLoan = new String(PlanCompilerTest.resource("toy-loan.yaml"), StandardCharsets.UTF_8);
        PlanCompiler.compile(toyLoan.getBytes(StandardCharsets.UTF_8), "toy-loan.yaml", pack);
        assertEquals(List.of(), problems(toyLoan), "the committed toy scenario");
        assertEquals(List.of(), problems(BinderTest.YAML), "slots, oracles, a credential");
        assertEquals(List.of(), problems("""
                scenario: every-base-verb
                pack: toy
                tags: [x]
                requires: ["toy.state | toy.legacy"]
                launchProperties: { toy.seed: A, n: 1 }
                steps:
                  - call: { primitive: toy.poke, args: { a: 1 }, sync: settle }
                  - click: { target: h12, rung: COMPONENT, await: { topic: x, within: 2s } }
                  - type: { by: { name: entry }, text: 42, mode: DISPATCH }
                  - press: { key: F2, modifiers: [SHIFT] }
                  - dismiss:
                  - dismiss: { button: OK }
                  - waitFor: { topic: [a, b], match: { id: x }, capture: { slot: s, fields: { id: id } } }
                  - expect: { "app.toy.name": IDLE, "ui.windows": { size: { gte: 1 } },
                              "loan.total": { equals: "7.00", capability: toy.loan } }
                  - mark: here
                  - mark: { label: here, attrs: { n: 1 } }
                  - webClick: { role: button, name: { regex: Borrow, ignoreCase: true }, browser: { tag: front } }
                  - webFill: { selector: "#a", text: hammer, enter: true }
                  - webDispatch: { name: go, detail: { a: 1 } }
                  - webWaitFlag: { expression: "window.ok", timeoutMs: 5s, pollMs: 50 }
                  - webResize: { clear: true }
                  - webTap: { patterns: [app.*] }
                  - enter: { value: 1 }
                verify:
                  - { "state.name": IDLE }
                  - db.loan: { where: { id: 1 }, rows: 1, total: "7.00" }
                on_prompt: { unexpected: fail }
                artifacts: [capture]
                timeouts: { step: 10s, bind: 2m }
                ui: { clickRung: COMPONENT, strict: false }
                web: { path: agent, browser: { tag: front }, frozenTargets: reload, trace: true }
                """));
    }

    @Test
    void aMalformedDocumentIsRefusedWithAJsonPath() throws Exception {
        assertEquals("$.colour", only("scenario: t\npack: toy\ncolour: red\nsteps: []\n").path());
        assertEquals("$", only("scenario: t\npack: toy\n").path(), "steps are required");
        assertTrue(only("scenario: t\npack: toy\n").message().contains("'steps' is required"));
        assertEquals("$.scenario", only("scenario: Not An Id\npack: toy\nsteps: []\n").path());
        assertEquals("$.pack", only("scenario: t\npack: other\nsteps: []\n").path());
        ScenarioSchema.Problem verb = only("scenario: t\npack: toy\nsteps:\n  - teleport: { to: x }\n");
        assertEquals("$.steps[0]", verb.path());
        assertTrue(verb.message().startsWith("expected one of the keys ["), verb.message());
        assertTrue(verb.message().endsWith("found [teleport]"), verb.message());
        assertEquals("$.steps[1].click.force", only("scenario: t\npack: toy\nsteps:\n  - mark: x\n"
                + "  - click: { target: h1, force: \"yes\" }\n").path());
        ScenarioSchema.Problem primitive = only("scenario: t\npack: toy\nsteps:\n  - call: { args: {} }\n");
        assertEquals("$.steps[0].call", primitive.path(), "the call the primitive is missing from");
        assertTrue(primitive.message().contains("'primitive' is required"), primitive.message());
        assertEquals("$.steps[0].call.sync", only("scenario: t\npack: toy\nsteps:\n  - call: { primitive: x, sync:"
                + " SOON }\n").path());
        assertEquals("$.steps[0].call.timeoutMs", only("scenario: t\npack: toy\nsteps:\n  - call: { primitive: x,"
                + " timeoutMs: 5 }\n").path(), "call has no timeoutMs: it is the verb as built");
        assertEquals("$.verify[0]['loan.total']", only("scenario: t\npack: toy\nsteps: []\nverify:\n"
                + "  - { \"loan.total\": { nearly: 7 } }\n").path());
        assertEquals("$.timeouts.step", only("scenario: t\npack: toy\nsteps: []\ntimeouts: { step: soon }\n")
                .path());
    }

    @Test
    void slotsFollowThePacksKindsStrategiesAndVerifiers() throws Exception {
        String base = "scenario: t\npack: toy\nresolve:\n  item: %s\nsteps: []\n";
        assertEquals(List.of(), problems(base.formatted("{ fixture: { code: A1 }, credential: { realm: s, key: c }"
                + " }")));
        assertEquals(List.of(), problems(base.formatted("{ query: Item, args: { deposit: { lte: $max } }, strategy:"
                + " sql, verify: toy.item.check }")));
        ScenarioSchema.Problem kind = only(base.formatted("{ query: Nope }"));
        assertEquals("$.resolve.item.query", kind.path());
        assertTrue(kind.message().startsWith("must be one of [\"Item\",\"Staff\",\"Limit\"]"), kind.message());
        assertEquals("$.resolve.item.strategy", only(base.formatted("{ query: Item, strategy: [llm-sql] }")).path(),
                "llm-sql only with an allow-list");
        pack.sqlAllowLists = List.of(Phase5CompileTest.TOY_ALLOW_LIST);
        assertEquals(List.of(), problems(base.formatted("{ query: Item, strategy: [sql, llm-sql] }")));
        assertEquals("$.resolve.item.args.colour", only(base.formatted("{ query: Item, args: { colour: red } }"))
                .path());
        assertEquals("$.resolve.item.verify", only(base.formatted("{ query: Item, verify: toy.staff.check }"))
                .path());
        assertEquals("$.resolve.item.fixture", only(base.formatted("{ fixture: 1, query: Item }")).path(),
                "a query slot of kind Item that also has a fixture");
        assertEquals("$.oracle.o.fn", only("scenario: t\npack: toy\noracle:\n  o: { fn: Nope }\nsteps: []\n")
                .path());
    }

    @Test
    void aPackVerbWithASchemaIsCheckedAndOneWithoutIsAFreeFormObject() throws Exception {
        Map<String, Object> schema = ScenarioSchema.generate(pack, List.of());
        Map<?, ?> enter = verbSchema(schema, "enter");
        assertEquals("object", enter.get("type"));
        assertTrue(String.valueOf(enter.get("description")).contains("free-form"));
        ToyPack declaring = toy.pack();
        Map<String, Object> scanArgs = Json.parseObject("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":"
                + "\"string\"}},\"required\":[\"value\"],\"additionalProperties\":false}");
        DelegatingSteps steps = new DelegatingSteps(declaring.steps(), Map.of("enter", scanArgs));
        Map<String, Object> declared = ScenarioSchema.generate(new SchemaPack(declaring, steps), List.of());
        assertEquals(scanArgs, verbSchema(declared, "enter"));
        List<ScenarioSchema.Problem> found = ScenarioSchema.validate(declared, yaml("scenario: t\npack: toy\nsteps:\n"
                + "  - enter: { value: 1 }\n"));
        assertEquals("$.steps[0].enter.value", found.get(0).path());
        PrimitiveDescriptor poke = new PrimitiveDescriptor("toy.poke", null, null, null, null, 1000, "toy.input", true,
                false);
        Map<String, Object> withCatalogue = ScenarioSchema.generate(pack, List.of(poke));
        assertEquals("$.steps[0].call.primitive", ScenarioSchema.validate(withCatalogue, yaml("scenario: t\npack:"
                + " toy\nsteps:\n  - call: { primitive: toy.nope }\n")).get(0).path(), "a primitive the target has");
        String requires = Json.write(((Map<?, ?>) ((Map<?, ?>) withCatalogue.get("properties")).get("requires"))
                .get("items"));
        assertTrue(requires.contains("toy.input"), requires);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> verbSchema(Map<String, Object> schema, String verb) {
        Map<?, ?> defs = (Map<?, ?>) schema.get("$defs");
        for (Object alternative : (List<?>) ((Map<?, ?>) defs.get("step")).get("oneOf")) {
            Map<String, Object> step = (Map<String, Object>) alternative;
            Map<?, ?> properties = (Map<?, ?>) step.get("properties");
            if (properties.containsKey(verb)) {
                return (Map<?, ?>) properties.get(verb);
            }
        }
        throw new AssertionError("no verb " + verb);
    }

    @Test
    void theSchemaStaysWithinTheProvidersLimitsAndLocal() {
        String text = Json.write(ScenarioSchema.generate(pack, List.of()));
        for (String forbidden : List.of("minimum", "maximum", "minLength", "maxLength", "maxItems", "\"format\"",
                "http://", "#/definitions")) {
            assertFalse(text.contains(forbidden), forbidden);
        }
        assertFalse(text.contains("\"minItems\":2"), "minItems 0 or 1 only");
        assertTrue(text.startsWith("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\""), text.substring(0,
                80));
    }

    @Test
    void theValidatorRefusesWhatItDoesNotKnow() {
        assertThrows(IllegalArgumentException.class, () -> ScenarioSchema.validate(Map.of("maxItems", 3L),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> ScenarioSchema.validate(Map.of("$ref",
                "#/$defs/missing"), 1L));
        assertThrows(IllegalArgumentException.class, () -> ScenarioSchema.validate(Map.of("$ref",
                "https://example.org/x"), 1L));
        assertEquals("$[1]", ScenarioSchema.validate(Map.of("type", "array", "items", Map.of("type", "integer"),
                "minItems", 1L), List.of(1L, 2.5, 3L)).get(0).path());
        assertEquals(List.of(), ScenarioSchema.validate(Map.of("type", List.of("integer", "null")), 2.0),
                "a whole double is an integer");
        assertEquals("$", ScenarioSchema.validate(Map.of("oneOf", List.of(Map.of("type", "string"),
                Map.of("pattern", "^a"))), "abc").get(0).path(), "oneOf: exactly one");
        assertTrue(ScenarioSchema.validate(Map.of("type", "array", "minItems", 1L), List.of()).get(0).message()
                .contains("at least 1 item"));
    }

    /** A step library that answers {@link StepLibrary#argsSchema} from a map and compiles through another. */
    private record DelegatingSteps(StepLibrary inner, Map<String, Map<String, Object>> schemas)
            implements StepLibrary {

        @Override
        public Set<String> verbs() {
            return inner.verbs();
        }

        @Override
        public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
            return inner.compile(verb, args, ctx);
        }

        @Override
        public Map<String, Object> argsSchema(String verb) {
            return schemas.get(verb);
        }
    }

    /** The toy pack with another step library. */
    private static final class SchemaPack implements ai.skillroy.testjava.scenario.pack.DomainPack {

        private final ToyPack inner;
        private final StepLibrary steps;

        SchemaPack(ToyPack inner, StepLibrary steps) {
            this.inner = inner;
            this.steps = steps;
        }

        @Override
        public String id() {
            return inner.id();
        }

        @Override
        public String version() {
            return inner.version();
        }

        @Override
        public StepLibrary steps() {
            return steps;
        }

        @Override
        public ai.skillroy.testjava.scenario.pack.SyncPolicy syncPolicy() {
            return inner.syncPolicy();
        }

        @Override
        public List<ai.skillroy.testjava.scenario.pack.FactProvider> facts() {
            return inner.facts();
        }

        @Override
        public ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue resolution() {
            return inner.resolution();
        }
    }

    /** phase7-contracts.md 8.1: the planner's schema lists the four verbs with the arguments BaseSteps accepts. */
    @Test
    void theSchemaListsTheFourVisionVerbs() throws Exception {
        assertEquals(List.of(), problems("""
                scenario: t
                steps:
                  - aiAct: { intent: "lend one item", tier: V1, model: sonnet, maxActions: 5 }
                  - aiExpect: { question: "the table shows a line", expect: NO }
                  - pointer: { by: { id: numberPad }, region: "key:7", at: { fx: 0.5, fy: 0.5 }, action: CLICK }
                  - expectImage: { label: pad, by: { id: numberPad }, mask: [ { by: { id: entry } },
                                   { x: 1, y: 2, w: 3, h: 4 } ], maxDiffPixels: 0 }
                """));
        assertFalse(problems("scenario: t\nsteps:\n  - aiAct: { intent: x, tier: V9 }\n").isEmpty());
        assertFalse(problems("scenario: t\nsteps:\n  - expectImage: { by: { id: x } }\n").isEmpty(),
                "label required");
        assertFalse(problems("scenario: t\nsteps:\n  - pointer: { by: { id: x }, via: MAGIC }\n").isEmpty());
    }

    /** phase8-contracts.md 7.3 (ES): every verb object takes the two step modifiers, and nothing else beside it. */
    @Test
    void everyVerbTakesTheTwoModifiers() throws Exception {
        assertEquals(List.of(), problems("""
                scenario: t
                steps:
                  - enter: { value: a }
                    accept: [VALUE_COERCED]
                  - poke: {}
                    minRung: PHYSICAL
                  - mark: done
                    accept: [VALUE_DROPPED]
                    minRung: SEMANTIC
                """));
        assertFalse(problems("scenario: t\nsteps:\n  - enter: { value: a }\n    accept: []\n").isEmpty(),
                "accept holds at least one code");
        assertFalse(problems("scenario: t\nsteps:\n  - enter: { value: a }\n    minRung: [X]\n").isEmpty());
        assertFalse(problems("scenario: t\nsteps:\n  - enter: { value: a }\n    other: 1\n").isEmpty());
    }

    /**
     * phase8-contracts.md 13.1 row 43 (9.1.4, 9.2): {@code on_prompt.unexpected} is the enum {@code [fail, pack]}, and
     * {@code requires}' description lists the pack's capabilities with their descriptions.
     */
    @Test
    @SuppressWarnings("unchecked")
    void onPromptIsAnEnumAndRequiresListsThePacksCapabilities() throws Exception {
        Map<String, Object> schema = ScenarioSchema.generate(pack, List.of(new PrimitiveDescriptor("toy.poke", null,
                null, null, null, 5000, "toy.poke", true, false)));
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> onPrompt = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) properties
                .get("on_prompt")).get("properties")).get("unexpected");
        assertEquals(List.of("fail", "pack"), onPrompt.get("enum"));
        assertEquals(List.of(), problems("scenario: t\nsteps: []\non_prompt: { unexpected: pack }\n"));
        assertEquals("$.on_prompt.unexpected", only("scenario: t\nsteps: []\non_prompt: { unexpected: lenient }\n")
                .path());
        String requires = (String) ((Map<String, Object>) ((Map<String, Object>) properties.get("requires"))
                .get("items")).get("description");
        assertEquals("a capability id, or alternatives \"a | b\"; the target's primitives name: toy.poke", requires,
                "a pack without capabilities adds nothing");

        pack.capabilities = List.of(capability("toy.env.ready", "the toy environment is ready"),
                capability("toy.a.setting", null));
        schema = ScenarioSchema.generate(pack, List.of());
        properties = (Map<String, Object>) schema.get("properties");
        requires = (String) ((Map<String, Object>) ((Map<String, Object>) properties.get("requires")).get("items"))
                .get("description");
        assertEquals("a capability id, or alternatives \"a | b\"; the pack contributes: toy.a.setting,"
                + " toy.env.ready (the toy environment is ready)", requires);
        pack.capabilitiesFailure = new IllegalStateException("broken");
        properties = (Map<String, Object>) ScenarioSchema.generate(pack, List.of()).get("properties");
        assertEquals("a capability id, or alternatives \"a | b\"", ((Map<String, Object>) ((Map<String, Object>)
                properties.get("requires")).get("items")).get("description"), "a broken pack adds nothing here");
    }

    private static ai.skillroy.testjava.scenario.pack.PackCapability capability(String id, String description) {
        return new ai.skillroy.testjava.scenario.pack.PackCapability() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public ai.skillroy.testjava.protocol.Capability evaluate(
                    ai.skillroy.testjava.scenario.pack.PreflightCheck.PreflightContext ctx) {
                return new ai.skillroy.testjava.protocol.Capability(id, ai.skillroy.testjava.protocol.CapabilityState
                        .OK, null, null);
            }
        };
    }
}
