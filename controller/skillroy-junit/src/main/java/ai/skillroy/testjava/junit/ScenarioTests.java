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
import ai.skillroy.testjava.data.Credentials;
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
import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunObservers;
import ai.skillroy.testjava.scenario.observe.RunStart;
import ai.skillroy.testjava.scenario.observe.TelemetrySettings;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.SettingsException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
 * {@code skillroy.approve} ({@code all} approves everything; there is never an interactive prompt), and its hooks
 * switch from {@code skillroy.hooks} ({@code auto} by default, {@code on} or {@code off}: phase6-contracts.md 5.3,
 * 6.1; a value that is none of them is the {@code environment} failure below). If the
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
 * <p>Telemetry (phase6-contracts.md 10.3, 10.6): the overload without {@code RunOptions} also reads
 * {@code skillroy.telemetry}, {@code skillroy.telemetry.endpoint}, {@code skillroy.telemetry.attributes},
 * {@code skillroy.telemetry.out}, {@code skillroy.telemetry.serviceName}, {@code skillroy.telemetry.timeoutMs} and
 * {@code skillroy.telemetry.file} ({@code telemetry.yaml} beside the credentials file otherwise;
 * {@link TelemetrySettings#fromSystemProperties}); off by default, when nothing names an exporter no provider is looked
 * for. With an exporter, the stream's scenarios report to one observer found through {@code ServiceLoader} on the
 * thread's context class loader ({@code skillroy-telemetry} on the test class path): one trace per stream, the root
 * span {@code skillroy junit}, its version {@code skillroy-junit}'s {@code Implementation-Version}; the observer is
 * flushed and closed at JVM exit by a shutdown hook (bounded by its timeout), since a stream of dynamic tests has no
 * end of its own. A setting that cannot be used (an {@code http://} endpoint that is not loopback, a missing header
 * variable, no provider on the class path) is the failing {@code environment} test and no scenario runs. The
 * overloads with {@code RunOptions} use {@code options.observer()} as given.
 *
 * <p>The connection is obtained lazily inside each test and is never closed here: the caller owns it. Tests run on
 * JUnit's thread; scenarios against one target must not run concurrently.
 */
public final class ScenarioTests {

    private ScenarioTests() {
    }

    /** The system property of the hooks switch (phase6-contracts.md 6.1): {@code auto}, {@code on} or {@code off}. */
    public static final String HOOKS_PROPERTY = "skillroy.hooks";

    /**
     * The hooks switch {@value #HOOKS_PROPERTY} names; {@link HooksMode#AUTO} when it is not set.
     *
     * @throws IllegalArgumentException for a value other than {@code auto}, {@code on} or {@code off}
     */
    static HooksMode hooksFromSystemProperties() {
        try {
            return HooksMode.parse(System.getProperty(HOOKS_PROPERTY));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(HOOKS_PROPERTY + ": " + e.getMessage(), e);
        }
    }

    /** The name of the test that fails when the credentials cannot be loaded. */
    public static final String ENVIRONMENT_TEST = "environment";

    /** The name of the trailing test of a replay stream with scenarios that need bindings. */
    public static final String ZERO_MODEL_CALLS_TEST = "skillroy: zero model calls";

    /** The streams' observers, closed at JVM exit (phase6-contracts.md 10.6). */
    private static final List<RunObserver> OBSERVERS = Collections.synchronizedList(new ArrayList<>());
    private static final Object HOOK_LOCK = new Object();
    private static boolean hookAdded;

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
                        source))).withHooks(hooksFromSystemProperties());
                effective = effective.withObserver(streamObserver(effective.bind(), source));
            } catch (CredentialsException | IllegalArgumentException | SettingsException e) {
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

    /**
     * The stream's observer (phase6-contracts.md 10.3): {@link RunObserver#NONE} unless the system properties or
     * {@code telemetry.yaml} name an exporter; else the provider's, started as the root span {@code skillroy junit} and
     * closed by the shutdown hook.
     *
     * @throws SettingsException when the settings cannot be used or no provider serves the exporter
     */
    static RunObserver streamObserver(BindOptions bind, ScenarioSource source) throws SettingsException {
        Path credentials = Credentials.locate(null, bind.bindingsRoot(), bind.envId());
        Path credentialsDir = credentials != null ? credentials.toAbsolutePath().getParent()
                : bind.bindingsRoot() != null ? bind.bindingsRoot().resolve(bind.envId()) : null;
        TelemetrySettings settings = TelemetrySettings.fromSystemProperties(credentialsDir, version());
        if (!settings.enabled()) {
            return RunObserver.NONE;
        }
        RunObserver observer = RunObservers.open(settings, Thread.currentThread().getContextClassLoader());
        closeAtExit(observer);
        RunObservers.safe(observer).runStarted(new RunStart("junit", null, bind.mode().label(), bind.envId(),
                version(), null, null));
        return observer;
    }

    /** {@code skillroy-junit}'s version (its jar's Implementation-Version), {@code (development build)} when absent. */
    static String version() {
        Package p = ScenarioTests.class.getPackage();
        String version = p == null ? null : p.getImplementationVersion();
        return version == null ? TelemetrySettings.DEVELOPMENT_BUILD : version;
    }

    /** Registers the observer with the JVM-exit hook, which closes each (each bounded by its own timeout). */
    private static void closeAtExit(RunObserver observer) {
        OBSERVERS.add(observer);
        synchronized (HOOK_LOCK) {
            if (hookAdded) {
                return;
            }
            hookAdded = true;
            Thread hook = new Thread(() -> {
                List<RunObserver> open;
                synchronized (OBSERVERS) {
                    open = new ArrayList<>(OBSERVERS);
                    OBSERVERS.clear();
                }
                for (RunObserver o : open) {
                    try {
                        o.close();
                    } catch (RuntimeException | LinkageError e) {
                        // Telemetry never fails a test run, not even at its end.
                    }
                }
            }, "skillroy-telemetry-exit");
            Runtime.getRuntime().addShutdownHook(hook);
        }
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
