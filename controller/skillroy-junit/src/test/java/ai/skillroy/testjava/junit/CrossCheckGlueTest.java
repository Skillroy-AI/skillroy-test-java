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

package ai.skillroy.testjava.junit;

import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle cross-checks through the glue (phase3-contracts.md 9, 11.1 item 4): a degraded run's message lists a
 * skipped cross-check beside skipped expectations, and ORACLE_SUSPECT is an ERROR ({@link ScenarioRunException}).
 */
class CrossCheckGlueTest {

    static final String GUARDED = """
            scenario: mini-guarded
            pack: mini
            oracle:
              same: { fn: Echo, args: IDLE, crossCheck: { check: mini.echo.check, capability: mini.fancy } }
            steps:
              - expect: { "app.mini.state": $oracle.same }
            """;

    static final String CHECKED = """
            scenario: mini-checked
            pack: mini
            oracle:
              same: { fn: Echo, args: IDLE, crossCheck: mini.echo.check }
            steps:
              - expect: { "app.mini.state": $oracle.same }
            """;

    static final String SKIPPED = "oracle same: cross-check mini.echo.check skipped: capability mini.fancy is"
            + " UNAVAILABLE (the target does not declare it)";

    @TempDir
    Path dir;

    private final MiniAgent agent = new MiniAgent();

    private DynamicTest only(String yaml, RunOptions options) throws Exception {
        Path scenarios = Files.createDirectories(dir.resolve("module/scenarios"));
        Files.writeString(scenarios.resolve("s.yaml"), yaml);
        List<DynamicTest> tests = ScenarioTests.dynamicTests(ScenarioSource.directory(scenarios), () -> agent,
                options).toList();
        assertEquals(1, tests.size(), "live: no trailing ledger test");
        return tests.get(0);
    }

    private static RunOptions live() {
        return RunOptions.defaults().withBind(BindOptions.defaults().withMode(BindMode.LIVE));
    }

    @Test
    void aDegradedRunListsTheSkippedCrossCheck() throws Exception {
        DynamicTest test = only(GUARDED, live());
        TestAbortedException aborted = assertThrows(TestAbortedException.class, () -> test.getExecutable()
                .execute());
        assertEquals("mini-guarded DEGRADED: skipped cross-checks: " + SKIPPED, aborted.getMessage());
        AssertionFailedError failed = assertThrows(AssertionFailedError.class, () -> only(GUARDED,
                live().withDegradedAs(RunOptions.DegradedAs.FAILURE)).getExecutable().execute());
        assertTrue(failed.getMessage().contains("skipped cross-checks: " + SKIPPED), failed.getMessage());
        assertDoesNotThrow(() -> only(GUARDED, live().withDegradedAs(RunOptions.DegradedAs.PASS)).getExecutable()
                .execute());
    }

    @Test
    void theMessageListsSkippedCrossChecksBesideSkippedExpectations() {
        RunResult result = new RunResult("s", Status.DEGRADED, null, null, null, Map.of(), List.of(),
                List.of("s1: app.mini.state {\"equals\":\"IDLE\"} skipped: capability mini.fancy is missing",
                        SKIPPED), Duration.ZERO, List.of());
        TestAbortedException aborted = assertThrows(TestAbortedException.class, () -> ScenarioTests.report(result,
                RunOptions.DegradedAs.SKIPPED));
        assertEquals("s DEGRADED: skipped expectations: s1: app.mini.state {\"equals\":\"IDLE\"} skipped: capability"
                + " mini.fancy is missing; skipped cross-checks: " + SKIPPED, aborted.getMessage());
    }

    @Test
    void anAgreeingCheckPassesAndADisagreeingOneIsAnError() throws Exception {
        assertDoesNotThrow(() -> only(CHECKED, live()).getExecutable().execute());
        agent.echoAnswer = "BUSY";
        ScenarioRunException error = assertThrows(ScenarioRunException.class, () -> only(CHECKED, live())
                .getExecutable().execute());
        assertEquals(FailureKind.ORACLE_SUSPECT, error.result().failureKind());
        assertEquals("DISAGREED", error.result().detail().get("reason"));
        assertTrue(error.getMessage().startsWith("mini-checked ERROR ORACLE_SUSPECT: oracle same (Echo) disagrees"
                + " with mini.echo.check: expected \"IDLE\", the application computes \"BUSY\""), error.getMessage());
    }
}
