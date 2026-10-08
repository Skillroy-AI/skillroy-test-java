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
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 4.4, 13.5, 15.1 item 16: the inference settings reach a stream that is given no
 * {@code RunOptions}: the system properties ({@code skillroy.inference}, {@code .recordings}, {@code .budget.*},
 * {@code .file}, {@code .logPrompts}) and the {@code inference.yaml} in the environment's directory of the bindings
 * root flow into {@link BindOptions#fromSystemProperties}; {@code replay} forces gateway {@code none}; a setting that
 * cannot be used (gateway {@code claude} without the model key) and {@code skillroy.mode=re-resolve} are the single
 * failing {@code environment} test, and no scenario contacts the agent.
 */
class InferenceGlueTest {

    private static final List<String> PROPERTIES = List.of(BindOptions.PROP_MODE, BindOptions.PROP_ENV,
            BindOptions.PROP_BINDINGS, BindOptions.PROP_APPROVE, "skillroy.credentials",
            InferenceSettings.PROP_GATEWAY, InferenceSettings.PROP_RECORDINGS, InferenceSettings.PROP_BUDGET_SCENARIO,
            InferenceSettings.PROP_BUDGET_SESSION, InferenceSettings.PROP_FILE, InferenceSettings.PROP_LOG_PROMPTS,
            InferenceSettings.PROP_WORKSPACE_ID);

    @TempDir
    Path dir;

    private final MiniAgent agent = new MiniAgent();
    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void saveProperties() {
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

    private Path scenarios() throws Exception {
        Path scenarios = Files.createDirectories(dir.resolve("module/scenarios"));
        Files.writeString(scenarios.resolve("oracle.yaml"), BindingGlueTest.ORACLE);
        return scenarios;
    }

    private List<DynamicTest> stream(Path scenarios) {
        return ScenarioTests.dynamicTests(ScenarioSource.directory(scenarios), () -> agent).toList();
    }

    @Test
    void theInferencePropertiesFlowIntoTheGluesBindOptions() throws Throwable {
        System.setProperty(BindOptions.PROP_MODE, "live");
        System.setProperty(InferenceSettings.PROP_GATEWAY, "recorded");
        System.setProperty(InferenceSettings.PROP_RECORDINGS, dir.resolve("recordings").toString());
        System.setProperty(InferenceSettings.PROP_BUDGET_SCENARIO, "0.20");
        System.setProperty(InferenceSettings.PROP_LOG_PROMPTS, "true");
        System.setProperty(InferenceSettings.PROP_WORKSPACE_ID, "ws-test");
        BindOptions options = BindOptions.fromSystemProperties(dir.resolve("bindings"));
        assertEquals(BindMode.LIVE, options.mode());
        InferenceSettings inference = options.inference();
        assertEquals(InferenceSettings.GATEWAY_RECORDED, inference.gateway());
        assertEquals(dir.resolve("recordings"), inference.recordings());
        assertEquals("0.20", inference.budgets().get(InferenceSettings.BUDGET_SCENARIO));
        assertTrue(inference.logPrompts());
        assertEquals("ws-test", inference.workspaceId());
        List<DynamicTest> tests = stream(scenarios());
        assertEquals(List.of("mini-oracle"), tests.stream().map(DynamicTest::getDisplayName).toList(),
                "a live stream: no trailing ledger test");
        for (DynamicTest test : tests) {
            test.getExecutable().execute();
        }
    }

    @Test
    void inferenceYamlInTheEnvironmentsDirectoryIsReadAndThePropertiesWinOverIt() throws Exception {
        Path env = Files.createDirectories(dir.resolve("module/bindings/ci-env"));
        Files.createDirectories(env.resolve("rec"));
        Files.writeString(env.resolve("inference.yaml"), "version: 1\ngateway: recorded\nrecordings: rec/\n"
                + "budget: {scenarioUsd: \"0.33\", sessionUsd: \"2.00\"}\n");
        System.setProperty(BindOptions.PROP_MODE, "approve");
        System.setProperty(BindOptions.PROP_ENV, "ci-env");
        System.setProperty(InferenceSettings.PROP_BUDGET_SESSION, "3.00");
        BindOptions options = BindOptions.fromSystemProperties(dir.resolve("module/bindings"));
        assertEquals(InferenceSettings.GATEWAY_RECORDED, options.inference().gateway(), "from the file");
        assertEquals(env.resolve("rec"), options.inference().recordings(), "relative to the file");
        assertEquals("0.33", options.inference().budgets().get(InferenceSettings.BUDGET_SCENARIO));
        assertEquals("3.00", options.inference().budgets().get(InferenceSettings.BUDGET_SESSION),
                "a property over the file");
        Path other = Files.writeString(dir.resolve("other.yaml"), "version: 1\ngateway: none\n");
        System.setProperty(InferenceSettings.PROP_FILE, other.toString());
        assertEquals(InferenceSettings.GATEWAY_NONE, BindOptions.fromSystemProperties(dir.resolve("module/bindings"))
                .inference().gateway(), "skillroy.inference.file names another file");
    }

    @Test
    void replayForcesGatewayNoneWhateverTheSettings() throws Exception {
        System.setProperty(InferenceSettings.PROP_GATEWAY, "claude");
        BindOptions options = BindOptions.fromSystemProperties(dir);
        assertEquals(BindMode.REPLAY, options.mode());
        assertEquals(InferenceSettings.GATEWAY_NONE, options.inference().gateway(), "replay never builds a gateway,"
                + " and needs no key");
    }

    @Test
    void aSettingThatCannotBeUsedAndReResolveAreTheSingleEnvironmentTest() throws Exception {
        Path scenarios = scenarios();
        System.setProperty(BindOptions.PROP_MODE, "live");
        System.setProperty(InferenceSettings.PROP_GATEWAY, "claude");
        List<DynamicTest> noKey = stream(scenarios);
        assertEquals(List.of(ScenarioTests.ENVIRONMENT_TEST), noKey.stream().map(DynamicTest::getDisplayName)
                .toList());
        AssertionFailedError failed = assertThrows(AssertionFailedError.class, () -> noKey.get(0).getExecutable()
                .execute());
        assertTrue(failed.getMessage().contains("the model key is not set (ANTHROPIC_API_KEY)"), failed.getMessage());

        System.clearProperty(InferenceSettings.PROP_GATEWAY);
        System.setProperty(BindOptions.PROP_MODE, "re-resolve");
        List<DynamicTest> reResolve = stream(scenarios);
        assertEquals(List.of(ScenarioTests.ENVIRONMENT_TEST), reResolve.stream().map(DynamicTest::getDisplayName)
                .toList());
        AssertionFailedError refused = assertThrows(AssertionFailedError.class, () -> reResolve.get(0)
                .getExecutable().execute());
        assertTrue(refused.getMessage().contains("re-resolve is a command: skillroy re-resolve"),
                refused.getMessage());
        assertEquals(0, agent.calls.get(), "no scenario contacted the agent");
    }
}
