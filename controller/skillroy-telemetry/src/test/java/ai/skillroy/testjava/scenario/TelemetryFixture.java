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

import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.Strategy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The executor tests' toy application, pack and fake agent ({@code skillroy-scenario}'s test-jar,
 * phase6-contracts.md 10.6) for {@code skillroy-telemetry}'s tests: runs of the binder's scenario ({@code live}, then
 * {@code replay} with the bindings it wrote), a failing run, a {@code SYNC_TIMEOUT} run, a {@code REBIND_NEEDED} run and
 * a session, each with the observer a test gives. In the scenario module's package so that it reaches the fixtures,
 * which are package-private.
 */
public final class TelemetryFixture {

    /** The binder tests' scenario: three query slots, two oracles, a sign-on with a joined password, an expectation. */
    public static final String BIND_YAML = BinderTest.YAML;

    /** A scenario of two steps whose expectation fails. */
    public static final String FAILING_YAML = """
            scenario: toy-fails
            pack: toy
            steps:
              - enter: { value: B2 }
              - expect: { "state.name": LOAN }
            """;

    /** A scenario whose call's wait never ends. */
    public static final String SYNC_TIMEOUT_YAML = """
            scenario: toy-waits
            pack: toy
            steps:
              - enter: { value: B2 }
              - call: { primitive: toy.state, sync: EVENT, await: { topic: never.published, within: 1s } }
            """;

    private final SessionFixture session;
    private final Path out;

    /** @param dir the test's temporary directory */
    public TelemetryFixture(Path dir) {
        this.session = new SessionFixture(dir.resolve("session"));
        this.out = dir.resolve("runs");
        // The executor's Phase 2 tests' application, with one item, so that the binder's scenario passes.
        FakeAgentConnection agent = session.agent;
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "IDLE"));
            return Map.of("accepted", true);
        });
        agent.value("toy.state", Map.of("name", "IDLE"));
        agent.value("toy.unlock", Map.of());
        agent.value("toy.lock", Map.of());
        agent.value("toy.loan", Map.of("total", "-5.00"));
        session.toy.items.clear();
        session.toy.items.add(List.of("B2", "5.00"));
    }

    /** Records one model call of the bind (a tier (b) slot's, with details) into the run's ledger. */
    public void aModelCallInTheBind() {
        session.toy.beforeStaffCheck = ctx -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("type", "inference");
            details.put("t", "2026-10-09T12:00:01.500Z");
            details.put("purpose", "resolve");
            details.put("requestedModel", "claude-sonnet-5-5");
            details.put("model", "claude-sonnet-5-5");
            details.put("requestId", "req_01");
            details.put("elapsedMs", 1500L);
            details.put("stop", "TOOL_USE");
            ledger.record(new CostLedger.Entry("toy-resolve", "assistant", Strategy.LLM_REASON, "claude",
                    "claude-sonnet-5-5", "req_01", 1200, 80, "0.0048", "resolve", 900, 0, false, null, details));
        };
    }

    private CostLedger ledger = new CostLedger();

    /** Runs {@code yaml} in {@code mode} into {@code <dir>/runs/<scenario>-<mode>/} with the observer. */
    public RunResult run(String yaml, BindMode mode, RunObserver observer) throws Exception {
        Plan plan = PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", session.pack);
        RunOptions options = RunOptions.defaults().withClock(session.clock).withSleeper(session.clock.sleeper())
                .withOutputDir(outputDir(plan.scenarioId(), mode))
                .withBind(TestCredentials.bind().withEnvId("seed-a").withMode(mode)
                        .withApprover(Approver.APPROVE_ALL).withBindingsRoot(out.resolve("bindings"))
                        .withLedger(ledger))
                .withObserver(observer);
        return new PlanExecutor(session.pack, session.agent, options).run(plan);
    }

    /** Where a run of {@link #run} writes. */
    public Path outputDir(String scenarioId, BindMode mode) {
        return out.resolve(scenarioId + "-" + mode.label());
    }

    /** A run in {@code replay} without a bindings root: REBIND_NEEDED. */
    public RunResult rebindNeeded(RunObserver observer) throws Exception {
        Plan plan = PlanCompiler.compile(BIND_YAML.getBytes(StandardCharsets.UTF_8), "t.yaml", session.pack);
        RunOptions options = RunOptions.defaults().withClock(session.clock).withSleeper(session.clock.sleeper())
                .withBind(TestCredentials.bind().withEnvId("seed-a")).withObserver(observer);
        return new PlanExecutor(session.pack, session.agent, options).run(plan);
    }

    /** A session with the observer: two steps of a person and a refusal, then closed. */
    public void session(RunObserver observer) throws Exception {
        try (ExecutorSession s = ExecutorSession.open(session.pack, session.agent, session.options()
                .withObserver(observer))) {
            s.execute("enter", Map.of("value", "B2"), Origin.person("tester"));
            s.execute("enter", Map.of("value", "POST"), Origin.model("claude-sonnet-5-5", "post the loan", "r1"));
            s.note("checked", Origin.person("tester"));
        }
    }

    /** The options of every call the agent received, in order. */
    public List<CallOptions> sentOptions() {
        List<CallOptions> options = new ArrayList<>();
        for (String method : session.agent.methods()) {
            for (FakeAgentConnection.Call call : session.agent.callsTo(method)) {
                if (!options.contains(call.options())) {
                    options.add(call.options());
                }
            }
        }
        return options;
    }

    /** The options the step primitive {@code toy.input} was called with, in order. */
    public List<CallOptions> inputOptions() {
        List<CallOptions> options = new ArrayList<>();
        for (FakeAgentConnection.Call call : session.agent.callsTo("toy.input")) {
            options.add(call.options());
        }
        return options;
    }

    /** The test credentials' passwords: no span may hold one. */
    public static Set<String> secrets() {
        return Set.of(TestCredentials.PW_1, TestCredentials.PW_100, TestCredentials.PW_200, TestCredentials.PW_300);
    }

    /**
     * The public values of a run's {@code bindings.json}: every text and number leaf of its resolutions' and oracles'
     * values and of its captured slots.
     */
    public static List<String> bindingValues(Path runDir) throws Exception {
        Path file = runDir.resolve("bindings.json");
        List<String> out = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            Map<String, Object> json = Json.parseObject(Files.readString(file));
            for (String section : List.of("resolutions", "oracles")) {
                if (json.get(section) instanceof Map<?, ?> entries) {
                    for (Object entry : entries.values()) {
                        if (entry instanceof Map<?, ?> e) {
                            leaves(e.get("value"), out);
                        }
                    }
                }
            }
            leaves(json.get("captured"), out);
        }
        return out;
    }

    private static void leaves(Object value, List<String> out) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> leaves(v, out));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> leaves(v, out));
        } else if (value instanceof String || value instanceof Number) {
            out.add(String.valueOf(value));
        }
    }
}
