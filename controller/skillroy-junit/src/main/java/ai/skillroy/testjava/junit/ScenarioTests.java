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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.DomainPacks;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.PlanExecutor;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Scenario;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.ScenarioLoader;
import ai.skillroy.testjava.scenario.StepResult;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * JUnit 5 glue: one {@link DynamicTest} per scenario file, for use in a {@code @TestFactory}:
 *
 * <pre>
 * &#64;TestFactory
 * Stream&lt;DynamicTest&gt; scenarios() {
 *     return ScenarioTests.dynamicTests(ScenarioSource.classpath("scenarios"), () -&gt; connection);
 * }
 * </pre>
 *
 * <p>Each test loads its YAML, compiles it with the pack it names (found with {@code ServiceLoader} on the thread's
 * context class loader, unless a pack is given), asks the supplier for a connection, runs the plan with
 * {@link PlanExecutor} and maps the result: PASS returns; FAIL fails ({@code AssertionFailedError}); ERROR throws
 * {@link ScenarioRunException} (a JUnit error); DEGRADED (a skipped guarded expectation, a UI call that ran at a
 * lower fidelity than requested, or a guarded oracle cross-check the bind skipped, phase3-contracts.md 6.2) follows
 * {@link RunOptions#degradedAs()}: aborted (shown as skipped) by default, failed for {@code FAILURE}, passed for
 * {@code PASS}; its message ({@code RunResult.degradedSummary()}) lists the skipped expectations, the skipped
 * cross-checks and the degraded-fidelity entries. A file that does not load or compile is a
 * test that throws its {@link ScenarioException}; a source that cannot be listed, or holds no scenario, is one
 * failing test named {@code scenario source}. With an output directory each scenario writes into
 * {@code <outputDir>/<scenarioId>/}.
 *
 * <p>Binding (phase2-contracts.md 12.2): the overloads with {@code RunOptions} use {@code options.bind()} as it is.
 * The overload without them builds {@code RunOptions.defaults().withBind(BindOptions.fromSystemProperties(root))}
 * from {@code skillroy.mode}, {@code skillroy.env}, {@code skillroy.bindings} (default: {@code bindings} in the
 * parent of the source's scenario directory; a class path source has no default), {@code skillroy.credentials} and
 * {@code skillroy.approve} ({@code all} approves everything; there is never an interactive prompt). If the
 * credentials cannot be loaded, or the mode writes bindings files ({@code approve}, or {@code live} with
 * {@code writeBindings}) and no bindings root is known while a scenario needs bindings, the stream is one failing test
 * named {@code environment} and no scenario runs. In
 * {@code replay}, when at least one scenario of the stream needs bindings (a query slot or an oracle), the stream
 * ends with one more test, {@code skillroy: zero model calls}, which fails when the shared cost ledger is not empty
 * after the scenarios ran; a stream of Phase 1 scenarios keeps its test count. REBIND_NEEDED, RESOLUTION_FAILED and
 * ORACLE_SUSPECT (an oracle and its cross-check disagree, phase3-contracts.md 9) are ERROR and throw
 * {@link ScenarioRunException}.
 *
 * <p>Web paths (phase4-contracts.md 6.1, D49): the executor looks for a controller-side web path
 * ({@code ServiceLoader<WebPath>}) through the same class loader that finds the packs, so a suite that puts the CDP
 * path ({@code skillroy-cdp}) on its test class path gets it for {@code web.path: auto} and {@code cdp}; without it
 * the web verbs go through the agent. Nothing about that changes how an outcome is reported.
 *
 * <p>The connection is obtained lazily inside each test and is never closed here: the caller owns it. Tests run on
 * JUnit's thread; scenarios against one target must not run concurrently.
 */
public final class ScenarioTests {

    private ScenarioTests() {
    }

    /** The name of the test that fails when the credentials cannot be loaded. */
    public static final String ENVIRONMENT_TEST = "environment";

    /** The name of the trailing test of a replay stream with scenarios that need bindings. */
    public static final String ZERO_MODEL_CALLS_TEST = "skillroy: zero model calls";

    /**
     * One test per scenario, default {@link RunOptions} with the bind options of the system properties
     * ({@link BindOptions#fromSystemProperties(Path)}), the pack each scenario names.
     */
    public static Stream<DynamicTest> dynamicTests(ScenarioSource source, Supplier<AgentConnection> connection) {
        return dynamicTests(source, connection, null, null);
    }

    /** One test per scenario with the given options; the pack each scenario names. */
    public static Stream<DynamicTest> dynamicTests(ScenarioSource source, Supplier<AgentConnection> connection,
                                                   RunOptions options) {
        return dynamicTests(source, connection, options, null);
    }

    /**
     * One test per scenario.
     *
     * @param options run options, whose {@code bind()} is used as it is; null means defaults with the bind options of
     *     the system properties. {@code outputDir}, when set, is the parent of one directory per scenario
     * @param pack the pack to compile and run every scenario with, or null to select it per scenario with
     *     {@code ServiceLoader}
     */
    public static Stream<DynamicTest> dynamicTests(ScenarioSource source, Supplier<AgentConnection> connection,
                                                   RunOptions options, DomainPack pack) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(connection, "connection");
        RunOptions effective = options;
        if (effective == null) {
            try {
                effective = RunOptions.defaults().withBind(BindOptions.fromSystemProperties(defaultBindingsRoot(
                        source)));
            } catch (CredentialsException | IllegalArgumentException e) {
                // Checked once, before any scenario contacts the agent (12.2): no scenario runs.
                String reason = e.getMessage();
                return Stream.of(DynamicTest.dynamicTest(ENVIRONMENT_TEST, () -> Assertions.fail(
                        "the environment cannot be used: " + reason)));
            }
        }
        List<ScenarioSource.Entry> entries;
        try {
            entries = source.entries();
        } catch (IOException | RuntimeException e) {
            // Reported as one failing test: an exception here would otherwise fail the whole test factory.
            return Stream.of(DynamicTest.dynamicTest("scenario source", () -> {
                throw e;
            }));
        }
        if (entries.isEmpty()) {
            return Stream.of(DynamicTest.dynamicTest("scenario source", () ->
                    Assertions.fail("no scenario files found in " + source)));
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        RunOptions chosen = effective;
        boolean needsBindings = entries.stream().anyMatch(ScenarioTests::needsBindings);
        BindOptions bind = chosen.bind();
        if (needsBindings && bind.bindingsRoot() == null && (bind.mode() == BindMode.APPROVE
                || bind.mode() == BindMode.LIVE && bind.writeBindings())) {
            // 8.3 approve step 8: a usage error before anything runs, reported once like a credentials problem.
            String what = bind.mode() == BindMode.APPROVE ? "approve" : "live with writeBindings";
            return Stream.of(DynamicTest.dynamicTest(ENVIRONMENT_TEST, () -> Assertions.fail("the environment cannot"
                    + " be used: " + what + " needs a bindings root to write bindings files to, and none is known"
                    + " (set skillroy.bindings; a class path source has no default)")));
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (ScenarioSource.Entry entry : entries) {
            tests.add(test(entry, connection, chosen, pack, loader));
        }
        if (needsBindings && chosen.bind().mode() == BindMode.REPLAY) {
            CostLedger ledger = chosen.bind().ledger();
            tests.add(DynamicTest.dynamicTest(ZERO_MODEL_CALLS_TEST, () -> {
                if (!ledger.isEmpty()) {
                    Assertions.fail("replay made " + ledger.size() + " model call" + (ledger.size() == 1 ? "" : "s")
                            + ": " + ledger.entries().stream().map(e -> e.scenarioId() + "/" + e.slot() + " "
                            + e.model()).toList());
                }
            }));
        }
        return tests.stream();
    }

    /** {@code bindings} in the parent of the source's scenario directory, or null when it has none (D31). */
    static Path defaultBindingsRoot(ScenarioSource source) {
        Path dir = source.scenarioDirectory();
        if (dir == null) {
            return null;
        }
        Path parent = dir.toAbsolutePath().normalize().getParent();
        return parent == null ? null : parent.resolve("bindings");
    }

    /** True when the scenario has a query slot or an oracle (8.1); a file that does not load counts as no. */
    private static boolean needsBindings(ScenarioSource.Entry entry) {
        try {
            Scenario scenario = ScenarioLoader.load(entry.content(), entry.name());
            return !scenario.oracles().isEmpty()
                    || scenario.slots().stream().anyMatch(slot -> "query".equals(slot.kind()));
        } catch (ScenarioException e) {
            return false;
        }
    }

    private static DynamicTest test(ScenarioSource.Entry entry, Supplier<AgentConnection> connection,
                                    RunOptions options, DomainPack pack, ClassLoader loader) {
        Scenario scenario;
        try {
            scenario = ScenarioLoader.load(entry.content(), entry.name());
        } catch (ScenarioException e) {
            return DynamicTest.dynamicTest(entry.name(), entry.uri(), () -> {
                throw e;
            });
        }
        return DynamicTest.dynamicTest(scenario.id(), entry.uri(), () -> {
            DomainPack chosen = pack != null ? pack : DomainPacks.forScenario(scenario, loader);
            Plan plan = PlanCompiler.compile(scenario, chosen);
            AgentConnection agent = connection.get();
            if (agent == null) {
                throw new IllegalStateException("the connection supplier returned null");
            }
            RunOptions runOptions = options.outputDir() == null ? options
                    : options.withOutputDir(options.outputDir().resolve(scenario.id()));
            report(new PlanExecutor(chosen, agent, runOptions, loader).run(plan), options.degradedAs());
        });
    }

    /** Maps a result to JUnit's outcome; see the class description. */
    static void report(RunResult result, RunOptions.DegradedAs degradedAs) {
        switch (result.status()) {
            case PASS -> {
            }
            case DEGRADED -> {
                String message = result.scenarioId() + " DEGRADED: " + result.degradedSummary();
                switch (degradedAs) {
                    case SKIPPED -> Assumptions.abort(message);
                    case FAILURE -> Assertions.fail(message);
                    case PASS -> {
                    }
                    default -> throw new IllegalStateException("unknown mapping " + degradedAs);
                }
            }
            case FAIL -> Assertions.fail(describe(result));
            case ERROR -> throw new ScenarioRunException(describe(result), result);
            default -> throw new IllegalStateException("unknown status " + result.status());
        }
    }

    /** The failure message: verdict, step, message, detail (without the snapshot), step log and UI text. */
    static String describe(RunResult result) {
        StringBuilder text = new StringBuilder();
        text.append(result.scenarioId()).append(' ').append(result.status()).append(' ').append(result.failureKind());
        if (result.failedStepId() != null) {
            text.append(" at ").append(result.failedStepId());
        }
        text.append(": ").append(result.message());
        Map<String, Object> detail = new LinkedHashMap<>(result.detail());
        Object snapshot = detail.remove("snapshot");
        if (!detail.isEmpty()) {
            text.append("\ndetail: ").append(Json.writePretty(detail));
        }
        if (!result.steps().isEmpty()) {
            text.append("\nsteps:");
            for (StepResult step : result.steps()) {
                text.append("\n  ").append(step.id()).append(' ').append(step.verb()).append(' ')
                        .append(step.status());
                if (step.line() > 0) {
                    text.append(" (line ").append(step.line()).append(')');
                }
            }
        }
        if (!result.degradedFidelity().isEmpty()) {
            text.append("\ndegraded fidelity: ").append(String.join("; ", result.degradedFidelity()));
        }
        if (snapshot instanceof String ui && !ui.isEmpty()) {
            text.append("\nUI at failure:\n").append(ui);
        }
        return text.toString();
    }
}
