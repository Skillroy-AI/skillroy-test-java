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

package ai.skillroy.testjava.vision;

import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.ExportOptions;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.vision.VisionHarness.done;
import static ai.skillroy.testjava.vision.VisionHarness.tools;
import static ai.skillroy.testjava.vision.VisionHarness.use;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 security fix, S113 (L6-S2; phase7-contracts.md 6.8, 9.2): the scenario's slots are the person's for the
 * whole play. The model of an {@code aiAct} that resolves a slot the player bound is refused before anything is
 * resolved or journaled, so the person's later step acts on the scenario's value and the {@code verify} judges it: the
 * play that would fail fails, and the export carries the scenario's own resolve entry.
 */
class ScenarioPlayerRebindTest {

    private static final String REBIND = String.join("\n",
            "scenario: rebind",
            "pack: toy",
            "resolve:",
            "  item: { query: Item, args: { deposit: { between: [\"6.00\", \"50.00\"] } } }",
            "  assistant: { fixture: { code: \"100\" }, credential: { realm: staff, key: code } }",
            "steps:",
            "  - signOn: { staff: $assistant }",
            "  - aiAct: { intent: \"lend the item\" }",
            "  - enter: { value: $item.code }",
            "verify:",
            "  - { \"loan.lines[0].code\": $item.code }",
            "");

    @TempDir
    Path dir;

    @Test
    void aModelNeverRebindsASlotTheScenarioBound() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, Map.of(), List.of(tools("r1", use("x1", "skillroy_resolve",
                Map.of("slot", "item", "kind", "Item", "args", Map.of("deposit", Map.of("between",
                        List.of("2.00", "6.00")))))), done("r2")))) {
            ScenarioPlayer player = new ScenarioPlayer(h.session, h.service, h.tools, VisionOptions.from(Map.of()),
                    false, PromptMask.keysOnly().withImages(true), Origin.person("tester"), null, null);
            ScenarioPlayer.Result result = player.play(REBIND.getBytes(StandardCharsets.UTF_8), "rebind.yaml");

            assertEquals(Status.FAIL, result.status(), result.message());
            assertTrue(result.message().startsWith("verify:") && result.message().contains("expected \"C3\" but was"
                    + " \"B2\""), result.message());
            assertEquals(List.of("signOn:PASS", "aiAct:PASS", "enter:PASS", "verify:FAIL"), result.steps().stream()
                    .map(s -> s.verb() + ":" + s.status()).toList());
            List<Map<String, Object>> inputs = h.agent.bridge.callsTo("toy.input");
            assertEquals("C3", inputs.get(inputs.size() - 1).get("value"), "the person's step typed the scenario's"
                    + " item");
            List<StepJournal.ResolveEntry> resolves = h.session.journal().entries().stream()
                    .filter(e -> e instanceof StepJournal.ResolveEntry).map(e -> (StepJournal.ResolveEntry) e)
                    .toList();
            assertEquals(1, resolves.size(), "the model's resolve was journaled nowhere");
            assertEquals(Origin.Kind.PERSON, resolves.get(0).origin().kind());
            String afterResolve = String.valueOf(h.gateway.requests().get(1).messages());
            assertTrue(afterResolve.contains(ExecutorSession.personSlotRefusal("item")), "the model reads the"
                    + " refusal: " + afterResolve);
            String yaml = result.annotate(h.session.journal().export(ExportOptions.of("rebind-export"))).yaml();
            assertTrue(yaml.contains("between: [\"6.00\", \"50.00\"]"), yaml);
            assertFalse(yaml.contains("\"2.00\""), "the model's arguments are not the scenario's: " + yaml);
        }
    }
}
