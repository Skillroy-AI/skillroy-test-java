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

import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase 7 security fix, S113 (L6-S2; P7 6.6, 9.2; D97): the export marks a slot a model or a client bound
 * {@code REQUIRED} until a person approves it, whatever its strategy (a tier (a) value included), from the live journal
 * and from the journal read back from its file; a slot the person bound keeps the tier rule; the exporter's own label
 * approves it, as for a tier (b) slot.
 */
class ResolveOriginExportTest {

    private static final Origin PERSON = Origin.person("tester");
    private static final Origin MODEL = Origin.model("a-model", "the item to lend", "req-1");

    @TempDir
    Path dir;

    @Test
    void aSlotAModelBoundIsExportedRequiredWhateverItsStrategy() throws Exception {
        SessionFixture fx = new SessionFixture(dir);
        Path file;
        try (ExecutorSession session = fx.open()) {
            SessionFixture.resolveItemAndAssistant(session, PERSON);
            session.resolve("other", "Item", Map.of("deposit", Map.of("between", List.of("6.00", "50.00"))),
                    List.of(), List.of(), null, MODEL);
            assertEquals(Status.PASS, session.execute("signOn", Map.of("staff", "$assistant"), PERSON).status());
            assertEquals(Status.PASS, session.execute("enter", Map.of("value", "$other.code"), PERSON).status());
            file = session.journal().file();
            check(session.journal());
        }
        check(StepJournal.read(file, fx.pack));
    }

    private static void check(StepJournal journal) throws Exception {
        BindingsFile exported = journal.export(ExportOptions.of("model-slot")).bindings();
        assertEquals(List.of("item", "assistant", "other"), List.copyOf(exported.resolutions().keySet()));
        assertEquals(Approval.State.NOT_REQUIRED, exported.resolutions().get("item").approval().state(),
                "the person's tier (a) slot");
        assertEquals(Approval.State.NOT_REQUIRED, exported.resolutions().get("assistant").approval().state());
        assertEquals(Strategy.CANONICAL_SQL.tier(), exported.resolutions().get("other").strategy().tier(),
                "a tier (a) strategy");
        Approval other = exported.resolutions().get("other").approval();
        assertEquals(Approval.State.REQUIRED, other.state(), "a model's binding waits for a person");
        assertNull(other.by());

        BindingsFile approved = journal.export(ExportOptions.of("model-slot").withApprovedBy("reviewer")).bindings();
        assertEquals(new Approval(Approval.State.APPROVED, "reviewer"), approved.resolutions().get("other")
                .approval(), "the exporter's own label approves it");
        assertEquals(Approval.State.NOT_REQUIRED, approved.resolutions().get("item").approval().state());
    }
}
