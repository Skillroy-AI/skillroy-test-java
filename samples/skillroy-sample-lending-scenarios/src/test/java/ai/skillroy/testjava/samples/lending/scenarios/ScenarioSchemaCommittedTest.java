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
import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.scenario.ScenarioSchema;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase 5 acceptance 15.1 item 11 (phase5-contracts.md 10.2, 13.3): the scenario schema the lending pack gives a
 * planner accepts every committed sample scenario and every negative one (all of them compile), and refuses a
 * malformed one with a JSON path; so the generator stays honest against the grammar as built.
 */
class ScenarioSchemaCommittedTest {

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
        Map<String, Object> schema = ScenarioSchema.generate(new LendingPack(), List.of());
        List<Path> files = everyScenario();
        assertEquals(15, files.size(), "the four committed scenarios and the eleven negative ones");
        for (Path yaml : files) {
            assertEquals(List.of(), ScenarioSchema.validate(schema, document(yaml)), yaml.getFileName().toString());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMalformedScenarioIsRefusedWithAJsonPath() throws Exception {
        Map<String, Object> schema = ScenarioSchema.generate(new LendingPack(), List.of());
        Path scenarioA = PlansUpToDateTest.moduleDir().resolve("scenarios/return-over-limit-needs-supervisor.yaml");
        Map<String, Object> broken = new LinkedHashMap<>((Map<String, Object>) document(scenarioA));
        Map<String, Object> resolve = new LinkedHashMap<>((Map<String, Object>) broken.get("resolve"));
        Map<String, Object> supervisor = new LinkedHashMap<>((Map<String, Object>) resolve.get("supervisor"));
        supervisor.put("strategy", List.of("llm-sql"));
        resolve.put("supervisor", supervisor);
        broken.put("resolve", resolve);
        List<Object> steps = new ArrayList<>((List<Object>) broken.get("steps"));
        steps.set(2, Map.of("key", Map.of("action", "DUE"), "scan", Map.of()));
        broken.put("steps", steps);
        List<ScenarioSchema.Problem> problems = ScenarioSchema.validate(schema, broken);
        assertFalse(problems.isEmpty());
        assertEquals(List.of("$.resolve.supervisor.strategy", "$.steps[2].scan"), problems.stream()
                .map(ScenarioSchema.Problem::path).toList(), "llm-sql without the pack's allow-list; a step with a"
                + " second verb: " + problems);
    }
}
