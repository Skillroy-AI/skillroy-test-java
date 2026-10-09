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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fixes S57 and S59 (decision D158; phase5-contracts.md 12.3): an MCP client's step is journaled with
 * the origin {@code CLIENT} and its label, and the export's head attributes it to {@code client <label>}, never to a
 * person; a client's {@code confirm} is stripped as a model's is.
 */
class JournalExportTest {

    @TempDir
    Path dir;

    @Test
    void aClientsStepIsExportedAsTheClients() throws Exception {
        SessionFixture fx = new SessionFixture(dir.resolve("session"));
        fx.agent.describe("toy.input", true, "toy.input");
        try (ExecutorSession session = fx.open()) {
            assertEquals(Status.PASS, session.execute("enter", Map.of("value", "B2"), Origin.client("mcp")).status());
            ExportResult result = session.journal().export(ExportOptions.of("client-step"));
            List<String> acted = result.yaml().lines().filter(l -> l.startsWith("# Acted:")).toList();
            assertEquals(List.of("# Acted: client mcp: 1 step."), acted, result.yaml());
            String journal = Files.readString(dir.resolve("session").resolve(StepJournal.FILE),
                    StandardCharsets.UTF_8);
            assertTrue(journal.contains("\"kind\":\"CLIENT\""), journal);
        }
    }

    @Test
    void aClientsConfirmIsStrippedAsAModelsIs() throws Exception {
        SessionFixture fx = new SessionFixture(dir.resolve("session"));
        try (ExecutorSession session = fx.open()) {
            StepOutcome raw = session.execute(ai.skillroy.testjava.scenario.pack.PlannedCall.of("toy.wipe",
                    Map.of("confirm", true), ai.skillroy.testjava.scenario.pack.Sync.NONE), Origin.client("planner"));
            assertEquals(Status.ERROR, raw.status(), raw.message());
            assertTrue(raw.message().contains("CONFIRM_REQUIRED"), raw.message());
            assertFalse(fx.agent.callsTo("toy.wipe").get(0).options().confirm());
            StepOutcome verb = session.execute("call", Map.of("primitive", "toy.wipe", "args", Map.of("confirm",
                    true)), Origin.client("planner"));
            assertTrue(verb.message().contains("CONFIRM_REQUIRED"), "through a verb as well: " + verb.message());
            assertFalse(fx.agent.callsTo("toy.wipe").get(1).options().confirm());
        }
    }
}
