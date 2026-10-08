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

package ai.skillroy.testjava.samples.webhost.scenarios;

import ai.skillroy.testjava.data.yaml.StrictYaml;
import ai.skillroy.testjava.samples.webhost.pack.WebHostPack;
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

/**
 * Phase 5 acceptance (phase5-contracts.md 10.2, 13.3), the web host's part: the scenario schema the webhost pack gives
 * a planner accepts the committed scenarios W1 and W2 and every negative one (all of them compile), with the base web
 * verbs as built, and refuses a malformed one with a JSON path.
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
        Map<String, Object> schema = ScenarioSchema.generate(new WebHostPack(), List.of());
        List<Path> files = everyScenario();
        assertEquals(9, files.size(), "the two committed scenarios and the seven negative ones");
        for (Path yaml : files) {
            assertEquals(List.of(), ScenarioSchema.validate(schema, document(yaml)), yaml.getFileName().toString());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMalformedScenarioIsRefusedWithAJsonPath() throws Exception {
        Map<String, Object> schema = ScenarioSchema.generate(new WebHostPack(), List.of());
        Path w1 = PlansUpToDateTest.moduleDir().resolve("scenarios/borrow-in-page.yaml");
        Map<String, Object> broken = new LinkedHashMap<>((Map<String, Object>) document(w1));
        List<Object> steps = new ArrayList<>((List<Object>) broken.get("steps"));
        steps.set(1, Map.of("webFill", Map.of("selector", "#item")));
        Map<String, Object> click = new LinkedHashMap<>((Map<String, Object>) ((Map<String, Object>) steps.get(3))
                .get("webClick"));
        Map<String, Object> await = new LinkedHashMap<>((Map<String, Object>) click.get("await"));
        await.put("within", "soon");
        click.put("await", await);
        steps.set(3, Map.of("webClick", click));
        broken.put("steps", steps);
        List<ScenarioSchema.Problem> problems = ScenarioSchema.validate(schema, broken);
        assertEquals(List.of("$.steps[1].webFill", "$.steps[3].webClick.await.within"), problems.stream()
                .map(ScenarioSchema.Problem::path).toList(), "a fill without its text; an await within no duration: "
                + problems);
    }
}
