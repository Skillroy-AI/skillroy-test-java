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

import ai.skillroy.testjava.junit.OutputScan;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.RowCheck;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase8-contracts.md 13.1 row 14 (ET): the test-only {@link PositionalOverlayPack} and its scenario
 * {@code positional/positional-loan.yaml} compile; the stored-row checks read the positional source; no new file holds
 * a staff password. The run itself is §13.2 item 13 (the image, {@code LoadModeChecks} slot 171).
 */
class PositionalScenarioTest {

    @Test
    void theOverlayReSourcesThreeStoredTablesAndForwardsTheRest() {
        PositionalOverlayPack overlay = PositionalOverlayPack.lending();
        assertEquals(Runs.PACK.id(), overlay.id());
        assertTrue(overlay.version().startsWith(Runs.PACK.version() + "+positional@"), overlay.version());
        for (StoredTable table : overlay.storedTables()) {
            String expected = PositionalOverlayPack.TABLES.contains(table.name()) ? "lending-report" : "lending";
            assertEquals(expected, table.source(), table.name());
        }
        assertEquals(Runs.PACK.storedTables().size(), overlay.storedTables().size());
        assertEquals(Runs.PACK.rowMasks(), overlay.rowMasks(), "the pack's masks, lending-report included");
        assertEquals(Runs.PACK.sqlAllowLists(), overlay.sqlAllowLists());
    }

    @Test
    void thePositionalScenarioCompilesUnderTheOverlayWithItsChecksOnTheReportReader() throws Exception {
        Plan plan = PlanCompiler.compile(PositionalOverlayPack.scenarioBytes(), "positional-loan.yaml",
                PositionalOverlayPack.lending());
        assertEquals("positional-loan", plan.scenarioId());
        List<RowCheck> checks = plan.rowChecks();
        assertEquals(3, checks.size());
        for (RowCheck check : checks) {
            assertEquals("lending-report", check.source(), check.table());
        }
        assertEquals("loan_authorisation", checks.get(2).table());
        assertTrue(String.valueOf(checks.get(2).rows()).contains("0"), "the absence check: " + checks.get(2).rows());
        // The same scenario under the pack itself reads the JDBC-form source.
        Plan plain = PlanCompiler.compile(PositionalOverlayPack.scenarioBytes(), "positional-loan.yaml", Runs.PACK);
        assertTrue(plain.rowChecks().stream().allMatch(c -> "lending".equals(c.source())));
    }

    @Test
    void theNewFilesHoldNoStaffPassword() throws Exception {
        Set<String> passwords = new HashSet<>(SampleCredentials.SEED_A.values());
        passwords.addAll(SampleCredentials.SEED_B.values());
        Path scenario = PositionalOverlayPack.scenario();
        assertEquals(List.of(), OutputScan.leaks(scenario, passwords), scenario.toString());
        assertFalse(new String(PositionalOverlayPack.scenarioBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("password:"), "a credential slot, never a password");
    }
}
