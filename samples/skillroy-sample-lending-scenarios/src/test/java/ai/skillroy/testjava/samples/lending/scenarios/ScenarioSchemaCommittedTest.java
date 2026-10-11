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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.junit.ScenarioSource;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.ScenarioSchema;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 acceptance 15.1 item 11 (phase5-contracts.md 10.2, 13.3) with the sample's verb schemas (9.5, 14.1): the
 * scenario schema the lending pack gives a planner holds each lending verb's own argument schema, accepts every
 * committed sample scenario and every negative one (all of them compile; none is meant to be malformed, since each
 * fails at bind or run time), and refuses a malformed one with a JSON path; each pack step of those scenarios, taken
 * on its own as a tool's input is, fits its verb's schema. So the generator and the verb schemas stay honest against
 * the grammar and the verbs as built.
 */
class ScenarioSchemaCommittedTest {

    private final LendingPack pack = new LendingPack();

    private static Object document(Path yaml) throws Exception {
        return StrictYaml.read(Files.readAllBytes(yaml)).root();
    }

    private static List<Path> everyScenario() throws Exception {
        List<Path> files = new ArrayList<>(PlansUpToDateTest.committedScenarios());
        Path negative = PlansUpToDateTest.moduleDir().resolve("src/test/resources/negative");
        try (Stream<Path> negatives = Files.list(negative)) {
            negatives.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().forEach(files::add);
        }
        return files;
    }

    @Test
    void everyCommittedScenarioIsValidAgainstThePacksSchema() throws Exception {
        Map<String, Object> schema = ScenarioSchema.generate(pack, List.of());
        List<Path> files = everyScenario();
        assertEquals(22, files.size(), "the eleven committed scenarios (scenario E and the four of phase7-contracts.md"
                + " 12.4 with them) and the eleven negative ones");
        for (Path yaml : files) {
            assertEquals(List.of(), ScenarioSchema.validate(schema, document(yaml)), yaml.getFileName().toString());
        }
    }

    /**
     * Scenario C's authoring record ({@code <id>.authoring.json}, phase5-contracts.md 10.2 item 7, 14.3) is committed
     * beside its YAML and is not a scenario: neither these tests nor the JUnit glue's directory source, which run every
     * committed scenario, take it for one.
     */
    @Test
    void theAuthoringRecordBesideScenarioCIsNotAScenario() throws Exception {
        assertTrue(Files.isRegularFile(ScenarioC.authoring()), "committed beside " + ScenarioC.YAML);
        assertEquals(ScenarioC.yaml().getParent(), ScenarioC.authoring().getParent());
        assertFalse(PlansUpToDateTest.committedScenarios().contains(ScenarioC.authoring()));
        assertTrue(PlansUpToDateTest.committedScenarios().contains(ScenarioC.yaml()));
        List<String> glue = ScenarioSource.directory(ScenarioC.yaml().getParent()).entries().stream()
                .map(ScenarioSource.Entry::name).toList();
        assertEquals(PlansUpToDateTest.committedScenarios().stream().map(p -> p.getFileName().toString()).toList(),
                glue, "the glue runs the committed YAML files, and nothing else of the directory");
    }

    /**
     * The schema holds each lending verb's declared schema, not the free-form object of a verb without one, and stays
     * within a provider's limits.
     */
    @Test
    void theSchemaHoldsEveryLendingVerbsOwnSchema() {
        Map<String, Object> schema = ScenarioSchema.generate(pack, List.of());
        Map<String, Map<?, ?>> steps = stepSchemas(schema);
        StepLibrary library = pack.steps();
        for (String verb : library.verbs()) {
            Map<String, Object> declared = library.argsSchema(verb);
            assertNotNull(declared, verb);
            assertEquals(Json.parse(Json.write(declared)), steps.get(verb), verb);
            assertFalse(String.valueOf(steps.get(verb).get("description")).startsWith("free-form"), verb);
        }
        String text = Json.write(schema);
        for (String forbidden : List.of("minimum", "maximum", "minLength", "maxLength", "maxItems", "\"format\"",
                "http://", "#/definitions")) {
            assertFalse(text.contains(forbidden), forbidden);
        }
        assertFalse(text.contains("\"minItems\":2"), "minItems 0 or 1 only");
    }

    /**
     * Every pack step of every committed and negative scenario, taken on its own as a tool's input is: its arguments
     * fit the schema the verb declares; and every scenario compiles, so the verb accepts them too.
     */
    @Test
    void everyPackStepFitsItsVerbsSchema() throws Exception {
        StepLibrary library = pack.steps();
        Set<String> used = new TreeSet<>();
        for (Path yaml : everyScenario()) {
            PlanCompiler.compile(yaml, pack);
            List<?> steps = (List<?>) ((Map<?, ?>) document(yaml)).get("steps");
            for (int i = 0; i < steps.size(); i++) {
                Map<?, ?> step = (Map<?, ?>) steps.get(i);
                String verb = String.valueOf(step.keySet().iterator().next());
                if (!library.verbs().contains(verb)) {
                    continue;
                }
                assertEquals(List.of(), ScenarioSchema.validate(library.argsSchema(verb), step.get(verb)),
                        yaml.getFileName() + " steps[" + i + "] " + verb);
                used.add(verb);
            }
        }
        assertEquals(Set.of("authorise", "deposit", "expectPrompt", "key", "quantity", "scan", "signOff", "signOn"),
                used, "the lending verbs the sample's scenarios use");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMalformedScenarioIsRefusedWithAJsonPath() throws Exception {
        Map<String, Object> schema = ScenarioSchema.generate(pack, List.of());
        Map<String, Object> broken = new LinkedHashMap<>((Map<String, Object>) document(ScenarioA.yaml()));
        Map<String, Object> resolve = new LinkedHashMap<>((Map<String, Object>) broken.get("resolve"));
        Map<String, Object> supervisor = new LinkedHashMap<>((Map<String, Object>) resolve.get("supervisor"));
        supervisor.put("strategy", List.of("llm-reason"));
        resolve.put("supervisor", supervisor);
        broken.put("resolve", resolve);
        List<Object> steps = new ArrayList<>((List<Object>) broken.get("steps"));
        steps.set(2, Map.of("key", Map.of("action", "DUE"), "scan", Map.of()));
        broken.put("steps", steps);
        List<ScenarioSchema.Problem> problems = ScenarioSchema.validate(schema, broken);
        assertEquals(List.of("$.resolve.supervisor.strategy", "$.steps[2].scan"), problems.stream()
                .map(ScenarioSchema.Problem::path).toList(), "llm-reason, not in this version (llm-sql is, with the"
                + " pack's allow-list); a step with a second verb: " + problems);
    }

    /**
     * A lending verb's arguments are checked against its own schema, with the path of the key at fault: a literal
     * staff code where a slot reference with a credential belongs, a key the keypad does not have, an argument the
     * verb does not take, a wait that is not a duration.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aLendingStepWithWrongArgumentsIsRefusedAtTheKey() throws Exception {
        Map<String, Object> schema = ScenarioSchema.generate(pack, List.of());
        Map<String, Object> broken = new LinkedHashMap<>((Map<String, Object>) document(ScenarioA.yaml()));
        List<Object> steps = new ArrayList<>((List<Object>) broken.get("steps"));
        steps.set(0, Map.of("signOn", Map.of("staff", "100")));
        steps.set(2, Map.of("key", Map.of("action", "LEND")));
        steps.set(4, Map.of("scan", Map.of("item", "$item", "colour", "red")));
        steps.set(5, Map.of("expectPrompt", Map.of("id", "supervisor-required", "within", "soon")));
        broken.put("steps", steps);
        assertEquals(List.of("$.steps[0].signOn.staff", "$.steps[2].key.action", "$.steps[4].scan.colour",
                "$.steps[5].expectPrompt.within"), ScenarioSchema.validate(schema, broken).stream()
                .map(ScenarioSchema.Problem::path).toList());
    }

    /** The step alternatives of a generated schema, by verb. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<?, ?>> stepSchemas(Map<String, Object> schema) {
        Map<String, Map<?, ?>> verbs = new LinkedHashMap<>();
        Map<?, ?> defs = (Map<?, ?>) schema.get("$defs");
        for (Object alternative : (List<?>) ((Map<?, ?>) defs.get("step")).get("oneOf")) {
            Map<String, Object> step = (Map<String, Object>) alternative;
            for (Map.Entry<?, ?> property : ((Map<?, ?>) step.get("properties")).entrySet()) {
                verbs.put(String.valueOf(property.getKey()), (Map<?, ?>) property.getValue());
            }
        }
        return verbs;
    }
}
