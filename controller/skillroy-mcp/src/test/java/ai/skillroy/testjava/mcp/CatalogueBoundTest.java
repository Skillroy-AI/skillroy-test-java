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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DelegatingDomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PackCapability;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fix wave of the Phase 8 review (L1-F2; phase8-contracts.md 9.1.4, 13.2 item 42): {@code skillroy_catalogue}
 * answers valid JSON within the 25,000-character bound however large the pack's verb schemas are. The capability
 * matrix (a pack capability with its {@code source}) and the data-query sources come first and are kept; the verbs'
 * {@code argsSchema} are withheld from the last verb back until the answer fits, each with a note naming the scenario
 * schema's resource, and {@code withheld} counts them. Before the fix the answer was cut mid-text (no JSON) before
 * its capabilities.
 */
class CatalogueBoundTest {

    @TempDir
    Path dir;

    /** A description long enough that three of them overflow the bound and one does not. */
    private static String longText(String word) {
        StringBuilder out = new StringBuilder();
        while (out.length() < 14_000) {
            out.append(word).append(' ');
        }
        return out.toString();
    }

    /** The desk's pack whose verbs deposit, enter and lend each declare a 14,000-character schema; one capability. */
    private static DomainPack large(DomainPack base) {
        StepLibrary baseSteps = base.steps();
        StepLibrary steps = new StepLibrary() {
            @Override
            public Set<String> verbs() {
                return baseSteps.verbs();
            }

            @Override
            public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
                return baseSteps.compile(verb, args, ctx);
            }

            @Override
            public Map<String, Object> argsSchema(String verb) {
                Map<String, Object> schema = baseSteps.argsSchema(verb);
                if (schema == null) {
                    return null;
                }
                Map<String, Object> out = new java.util.LinkedHashMap<>(schema);
                out.put("description", longText(verb));
                return out;
            }
        };
        return new DelegatingDomainPack(base, "large", "0.1") {
            @Override
            public StepLibrary steps() {
                return steps;
            }

            @Override
            public List<PackCapability> capabilities() {
                return List.of(new PackCapability() {
                    @Override
                    public String id() {
                        return "desk.env.ready";
                    }

                    @Override
                    public Capability evaluate(PreflightCheck.PreflightContext ctx) {
                        return new Capability("desk.env.ready", CapabilityState.OK, null, null);
                    }
                });
            }
        };
    }

    @Test
    void aCatalogueOverTheBoundIsValidJsonWithItsCapabilitiesAndWithholdsSchemasFromTheEnd() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options(large(fx.desk.pack)).mutating(true).build());
            String text = McpFixture.text(fx.call("skillroy_catalogue", Map.of()));
            assertTrue(text.length() <= Masking.MAX_RESULT, "within the bound: " + text.length());
            assertFalse(text.contains("[truncated:"), "never cut");
            Map<String, Object> json = McpFixture.json(fx.call("skillroy_catalogue", Map.of()));
            assertEquals(List.of("pack", "mutating", "withheld", "capabilities", "dataQuerySources", "verbs",
                    "renamedPrimitives", "withheldVerbs", "queries", "oracles"), List.copyOf(json.keySet()));
            Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) json.get("capabilities")).get("desk.env.ready");
            assertNotNull(entry, String.valueOf(json.get("capabilities")));
            assertEquals("OK", entry.get("state"));
            assertEquals("pack:desk", entry.get("source"));
            assertEquals("desk", ((Map<?, ?>) ((List<?>) json.get("dataQuerySources")).get(0)).get("source"));

            List<?> verbs = (List<?>) json.get("verbs");
            assertEquals(List.of("deposit", "enter", "lend", "poke"), verbs.stream()
                    .map(v -> ((Map<?, ?>) v).get("verb")).toList());
            Map<?, ?> deposit = (Map<?, ?>) verbs.get(0);
            assertNotNull(deposit.get("argsSchema"), "the first verb keeps its schema");
            for (int i : new int[] {1, 2}) {
                Map<?, ?> verb = (Map<?, ?>) verbs.get(i);
                assertNull(verb.get("argsSchema"), verb.get("verb") + " withheld from the end");
                assertEquals(ToolCalls.SCHEMA_WITHHELD, verb.get("note"));
            }
            assertEquals("free-form arguments: the pack declares no schema", ((Map<?, ?>) verbs.get(3)).get("note"),
                    "poke has no schema to withhold");
            Map<?, ?> withheld = (Map<?, ?>) json.get("withheld");
            assertEquals(2L, ((Number) withheld.get("schemas")).longValue());
            assertTrue(String.valueOf(withheld.get("reason")).contains("skillroy://schema/scenario"),
                    String.valueOf(withheld));
            assertNotNull(((Map<?, ?>) ((List<?>) json.get("queries")).get(0)).get("args"), "queries kept");
        }
    }

    /** The desk's pack with 400 free-form verbs more (each compiled as {@code poke}) and {@code capabilities}. */
    private static DomainPack manyVerbs(DomainPack base, List<PackCapability> capabilities) {
        StepLibrary baseSteps = base.steps();
        Set<String> verbs = new java.util.TreeSet<>(baseSteps.verbs());
        for (int i = 0; i < 400; i++) {
            verbs.add(String.format("tidy%03d", i));
        }
        StepLibrary steps = new StepLibrary() {
            @Override
            public Set<String> verbs() {
                return verbs;
            }

            @Override
            public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
                return baseSteps.compile(verb.startsWith("tidy") ? "poke" : verb, args, ctx);
            }

            @Override
            public Map<String, Object> argsSchema(String verb) {
                return verb.startsWith("tidy") ? null : baseSteps.argsSchema(verb);
            }
        };
        return new DelegatingDomainPack(base, "many", "0.1") {
            @Override
            public StepLibrary steps() {
                return steps;
            }

            @Override
            public List<PackCapability> capabilities() {
                return capabilities;
            }
        };
    }

    private static PackCapability degraded(String id, String reason) {
        return new PackCapability() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Capability evaluate(PreflightCheck.PreflightContext ctx) {
                return new Capability(id, CapabilityState.DEGRADED, reason, null);
            }
        };
    }

    /**
     * A verb list too long even without schemas: the answer is the pack, the matrix and the sources, the verbs,
     * queries and oracles withheld with the reason; still valid JSON within the bound.
     */
    @Test
    void aVerbListOverTheBoundLeavesTheMatrixAndTheSources() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options(manyVerbs(fx.desk.pack, List.of(degraded("desk.env.ready", "warming up"))))
                    .mutating(true).build());
            String text = McpFixture.text(fx.call("skillroy_catalogue", Map.of()));
            assertTrue(text.length() <= Masking.MAX_RESULT, "within the bound: " + text.length());
            Map<String, Object> json = McpFixture.json(fx.call("skillroy_catalogue", Map.of()));
            assertEquals(List.of("pack", "mutating", "withheld", "capabilities", "dataQuerySources"),
                    List.copyOf(json.keySet()));
            Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) json.get("capabilities")).get("desk.env.ready");
            assertEquals("pack:desk", entry.get("source"));
            assertEquals("DEGRADED", entry.get("state"));
            assertTrue(String.valueOf(((Map<?, ?>) json.get("withheld")).get("reason")).contains(
                    "skillroy://schema/scenario"), String.valueOf(json.get("withheld")));
        }
    }

    /** A matrix over the bound on its own: the pack and the note naming where to read the rest. */
    @Test
    void aMatrixOverTheBoundLeavesThePackAndTheNote() throws Exception {
        List<PackCapability> capabilities = new java.util.ArrayList<>();
        for (int i = 0; i < 64; i++) {
            capabilities.add(degraded(String.format("desk.env.c%02d", i), longText("reason").substring(0, 1_000)));
        }
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options(manyVerbs(fx.desk.pack, capabilities)).mutating(true).build());
            String text = McpFixture.text(fx.call("skillroy_catalogue", Map.of()));
            assertTrue(text.length() <= Masking.MAX_RESULT, "within the bound: " + text.length());
            Map<String, Object> json = McpFixture.json(fx.call("skillroy_catalogue", Map.of()));
            assertEquals(List.of("pack", "mutating", "withheld"), List.copyOf(json.keySet()));
            assertEquals(Map.of("id", "desk", "version", ((Map<?, ?>) json.get("pack")).get("version")),
                    json.get("pack"));
            assertTrue(String.valueOf(((Map<?, ?>) json.get("withheld")).get("reason")).contains(
                    "skillroy_facts {root: caps}"), String.valueOf(json.get("withheld")));
        }
    }

    @Test
    void aCatalogueWithinTheBoundWithholdsNothing() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            Map<String, Object> json = McpFixture.json(fx.call("skillroy_catalogue", Map.of()));
            assertFalse(json.containsKey("withheld"), String.valueOf(json.keySet()));
            assertEquals(List.of("pack", "mutating", "capabilities", "dataQuerySources", "verbs", "renamedPrimitives",
                    "withheldVerbs", "queries", "oracles"), List.copyOf(json.keySet()));
            for (Object verb : (List<?>) json.get("verbs")) {
                assertFalse(ToolCalls.SCHEMA_WITHHELD.equals(((Map<?, ?>) verb).get("note")), String.valueOf(verb));
            }
        }
    }
}
