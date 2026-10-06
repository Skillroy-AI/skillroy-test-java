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
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The glue's Phase 2 behaviour (phase2-contracts.md 12.2, 14.1 item 10). */
class BindingGlueTest {

    private static final List<String> PROPERTIES = List.of(BindOptions.PROP_MODE, BindOptions.PROP_ENV,
            BindOptions.PROP_BINDINGS, BindOptions.PROP_APPROVE, "skillroy.credentials");

    static final String ORACLE = """
            scenario: mini-oracle
            pack: mini
            oracle:
              same: { fn: Echo, args: IDLE, must_equal: IDLE }
            steps:
              - expect: { "app.mini.state": $oracle.same }
            """;

    @TempDir
    Path dir;

    private final MiniAgent agent = new MiniAgent();
    private final Map<String, String> saved = new java.util.HashMap<>();

    @BeforeEach
    void saveProperties() {
        // A developer may run the build with any of them set: each test starts without and ends as it found them.
        for (String name : PROPERTIES) {
            saved.put(name, System.getProperty(name));
            System.clearProperty(name);
        }
    }

    @AfterEach
    void restoreProperties() {
        for (String name : PROPERTIES) {
            String before = saved.get(name);
            if (before == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, before);
            }
        }
    }

    private Path scenarios(String... yaml) throws Exception {
        Path scenarios = Files.createDirectories(dir.resolve("module/scenarios"));
        for (int i = 0; i < yaml.length; i++) {
            Files.writeString(scenarios.resolve("s" + i + ".yaml"), yaml[i]);
        }
        return scenarios;
    }

    private static List<String> names(List<DynamicTest> tests) {
        return tests.stream().map(DynamicTest::getDisplayName).toList();
    }

    @Test
    void theSystemPropertiesChooseModeEnvironmentRootAndApprover() throws Throwable {
        Path scenarios = scenarios(ORACLE);
        System.setProperty(BindOptions.PROP_MODE, "approve");
        System.setProperty(BindOptions.PROP_ENV, "ci-env");
        System.setProperty(BindOptions.PROP_APPROVE, "all");
        List<DynamicTest> tests = ScenarioTests.dynamicTests(ScenarioSource.directory(scenarios), () -> agent)
                .toList();
        assertEquals(List.of("mini-oracle"), names(tests), "approve: no trailing ledger test");
        tests.get(0).getExecutable().execute();
        Path written = dir.resolve("module/bindings/ci-env/mini-oracle.bindings.json");
        assertTrue(Files.isRegularFile(written), "default root: bindings in the parent of the scenario directory");

        System.setProperty(BindOptions.PROP_MODE, "replay");
        List<DynamicTest> replay = ScenarioTests.dynamicTests(ScenarioSource.directory(scenarios), () -> agent)
                .toList();
        assertEquals(List.of("mini-oracle", ScenarioTests.ZERO_MODEL_CALLS_TEST), names(replay));
        for (DynamicTest test : replay) {
            test.getExecutable().execute();
        }

        System.setProperty(BindOptions.PROP_BINDINGS, dir.resolve("elsewhere").toString());
        ScenarioRunException noBindings = assertThrows(ScenarioRunException.class, () -> ScenarioTests
                .dynamicTests(ScenarioSource.directory(scenarios), () -> agent).toList().get(0).getExecutable()
                .execute());
        assertEquals(FailureKind.REBIND_NEEDED, noBindings.result().failureKind());

        System.setProperty(BindOptions.PROP_MODE, "approve");
        System.clearProperty(BindOptions.PROP_APPROVE);
        ScenarioRunException refused = assertThrows(ScenarioRunException.class, () -> ScenarioTests
                .dynamicTests(ScenarioSource.directory(scenarios), () -> agent).toList().get(0).getExecutable()
                .execute());
        assertEquals("NOT_APPROVED", refused.result().detail().get("reason"), "the glue never prompts");
    }

    @Test
    void aCredentialsFileThatCannotBeLoadedIsTheSingleTestEnvironment() throws Exception {
        Path scenarios = scenarios(ORACLE, "scenario: plain\npack: mini\nsteps: []\n");
        System.setProperty("skillroy.credentials", dir.resolve("missing.yaml").toString());
        List<DynamicTest> tests = ScenarioTests.dynamicTests(ScenarioSource.directory(scenarios), () -> agent)
                .toList();
        assertEquals(List.of(ScenarioTests.ENVIRONMENT_TEST), names(tests));
        AssertionFailedError failed = assertThrows(AssertionFailedError.class, () -> tests.get(0).getExecutable()
                .execute());
        assertTrue(failed.getMessage().contains("does not exist"), failed.getMessage());
        assertEquals(0, agent.calls.get(), "no scenario contacted the agent");
        System.setProperty(BindOptions.PROP_MODE, "re-resolve");
        System.clearProperty("skillroy.credentials");
        assertEquals(List.of(ScenarioTests.ENVIRONMENT_TEST), names(ScenarioTests.dynamicTests(
                ScenarioSource.directory(scenarios), () -> agent).toList()));
    }

    @Test
    void anApproveStreamWithoutABindingsRootIsTheSingleTestEnvironment() throws Exception {
        System.setProperty(BindOptions.PROP_MODE, "approve");
        System.setProperty(BindOptions.PROP_APPROVE, "all");
        List<DynamicTest> classpath = ScenarioTests.dynamicTests(ScenarioSource.classpath("junit-bindings"),
                () -> agent).toList();
        assertEquals(List.of(ScenarioTests.ENVIRONMENT_TEST), names(classpath), "a class path source has no"
                + " default root");
        AssertionFailedError failed = assertThrows(AssertionFailedError.class, () -> classpath.get(0)
                .getExecutable().execute());
        assertTrue(failed.getMessage().contains("approve needs a bindings root"), failed.getMessage());

        Path scenarios = scenarios(ORACLE, "scenario: plain\npack: mini\nsteps: []\n");
        RunOptions noRoot = RunOptions.defaults().withBind(BindOptions.defaults().withMode(BindMode.APPROVE)
                .withApprover(ai.skillroy.testjava.scenario.Approver.APPROVE_ALL));
        assertEquals(List.of(ScenarioTests.ENVIRONMENT_TEST), names(ScenarioTests.dynamicTests(
                ScenarioSource.directory(scenarios), () -> agent, noRoot).toList()), "options given in code alike");
        assertEquals(0, agent.calls.get(), "no scenario contacted the agent");

        Path plain = Files.createDirectories(dir.resolve("plain"));
        Files.writeString(plain.resolve("a.yaml"), "scenario: a\npack: mini\nsteps: []\n");
        assertEquals(List.of("a"), names(ScenarioTests.dynamicTests(ScenarioSource.directory(plain), () -> agent,
                noRoot).toList()), "a stream without bindings needs no root");
    }

    @Test
    void theZeroModelCallsTestFailsOnANonEmptyLedgerAndIsAbsentForPhaseOneStreams() throws Throwable {
        Path scenarios = scenarios(ORACLE);
        CostLedger ledger = new CostLedger();
        RunOptions options = RunOptions.defaults().withBind(BindOptions.defaults().withLedger(ledger));
        List<DynamicTest> tests = ScenarioTests.dynamicTests(ScenarioSource.directory(scenarios), () -> agent,
                options).toList();
        assertEquals(ScenarioTests.ZERO_MODEL_CALLS_TEST, tests.get(1).getDisplayName());
        assertDoesNotThrow(() -> tests.get(1).getExecutable().execute());
        ledger.record(new CostLedger.Entry("mini-oracle", null, Strategy.LLM_REASON, "p", "m", "r", 1, 1, "0"));
        AssertionFailedError failed = assertThrows(AssertionFailedError.class, () -> tests.get(1).getExecutable()
                .execute());
        assertTrue(failed.getMessage().contains("replay made 1 model call"), failed.getMessage());

        Path phase1 = Files.createDirectories(dir.resolve("p1"));
        Files.writeString(phase1.resolve("a.yaml"), "scenario: a\npack: mini\nsteps: []\n");
        assertEquals(List.of("a"), names(ScenarioTests.dynamicTests(ScenarioSource.directory(phase1), () -> agent,
                options).toList()), "a Phase 1 stream keeps its test count");
        assertEquals(List.of("mini-oracle"), names(ScenarioTests.dynamicTests(ScenarioSource.directory(scenarios),
                () -> agent, options.withBind(options.bind().withMode(BindMode.LIVE))).toList()),
                "only in replay");
    }

    @Test
    void theDefaultBindingsRoot() throws Exception {
        Path scenarios = scenarios(ORACLE);
        assertEquals(dir.resolve("module/bindings").toAbsolutePath().normalize(),
                ScenarioTests.defaultBindingsRoot(ScenarioSource.directory(scenarios)));
        assertNull(ScenarioTests.defaultBindingsRoot(ScenarioSource.classpath("junit-scenarios")),
                "a class path source has no default");
        BindOptions fromProperties = BindOptions.fromSystemProperties(dir);
        assertEquals(BindMode.REPLAY, fromProperties.mode());
        assertEquals("default", fromProperties.envId());
        assertEquals(dir, fromProperties.bindingsRoot());
    }
}
