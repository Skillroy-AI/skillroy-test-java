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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hardening H2's client latch on the executor side (no call after a loss, cleanup included) and the error backstop of
 * security finding S24 (an Error from pack or matcher code ends the run as ENVIRONMENT, with cleanup and recover).
 */
class LostAgentAndErrorBackstopTest {

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private final RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
            .withBind(TestCredentials.bind());

    @BeforeEach
    void toyApplication() {
        agent.capabilities("toy.input", "toy.state", "toy.loan");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of("accepted", true);
        });
        agent.value("toy.lock", Map.of());
        agent.value("toy.unlock", Map.of());
        agent.value("swing.snapshot", Map.of("windows", List.of()));
        agent.value("app.state", Map.of());
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
    }

    private RunResult run(String steps) throws ScenarioException {
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\n" + steps).getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    @Test
    void afterALossNoCleanupCallGoesOut(@TempDir Path dir) throws Exception {
        agent.loseOn("toy.lock");
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\nresolve:\n"
                + "  c: { fixture: { code: \"1\" }, credential: { realm: staff, key: code } }\n"
                + "steps:\n  - signOn: { staff: $c }\n"
                + "  - lock: { name: a }\n").getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        RunResult result = new PlanExecutor(pack, agent, options.withOutputDir(dir)).run(plan);
        assertEquals(FailureKind.AGENT_LOST, result.failureKind(), result.message());
        assertEquals("toy.lock", agent.methods().get(agent.methods().size() - 1),
                "the call that found the agent lost is the last call of the run");
        assertTrue(agent.callsTo("toy.unlock").isEmpty());
        assertEquals(2, agent.callsTo("toy.input").size(), "sign-on only; its SIGN_OFF cleanup is not sent");
        assertEquals(0, pack.recovered.get());
        String log = Files.readString(dir.resolve("run.jsonl"));
        assertTrue(log.contains("\"type\":\"cleanup\",") && log.contains("skipped: the agent was lost"), log);
    }

    @Test
    void aLossDuringDiagnosticsStopsEvidenceAndCleanup() throws Exception {
        // A wait times out, and the agent goes away while the executor reads the diagnostics.
        agent.loseOn("swing.edtState");
        RunResult result = run("steps:\n  - lock: { name: a }\n  - waitFor: { topic: never, within: 1s }\n");
        assertEquals(FailureKind.SYNC_TIMEOUT, result.failureKind(), result.message());
        List<String> methods = agent.methods();
        assertEquals("swing.edtState", methods.get(methods.size() - 1), "nothing after the call that was lost: "
                + methods);
        assertTrue(agent.callsTo("toy.unlock").isEmpty());
        assertEquals(0, pack.recovered.get());
    }

    @Test
    void aStackOverflowFromAFactIsAnEnvironmentErrorWithCleanupAndRecover() throws Exception {
        pack.facts = List.of(new FactProvider() {
            @Override
            public String root() {
                return "state";
            }

            @Override
            public Object fetch(AgentConnection connection) {
                throw new StackOverflowError("pack recursion");
            }
        });
        RunResult result = run("steps:\n  - lock: { name: a }\n  - expect: { \"state.name\": IDLE }\n");
        assertEquals(Status.ERROR, result.status(), result.message());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertEquals("s2", result.failedStepId());
        assertTrue(result.message().startsWith("internal error: java.lang.StackOverflowError"), result.message());
        assertEquals(Status.ERROR, result.steps().get(1).status());
        assertEquals(List.of("a"), agent.callsTo("toy.unlock").stream().map(c -> c.params().get("which")).toList(),
                "cleanup ran");
        assertEquals(1, pack.recovered.get(), "recover ran");
    }

    @Test
    void aLinkageErrorFromAPackIsAnEnvironmentError() throws Exception {
        agent.on("toy.lock", params -> {
            throw new NoClassDefFoundError("com/example/Missing");
        });
        RunResult result = run("steps:\n  - lock: { name: a }\n");
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind(), result.message());
        assertTrue(result.message().contains("NoClassDefFoundError"), result.message());
        assertEquals(1, agent.callsTo("toy.unlock").size());
    }

    @Test
    void aLossBeforeTheFirstStepSendsNoCleanup() throws Exception {
        // The plan's cleanup list is fixed at compile time, so before the latch a loss at the start mark still sent
        // toy.unlock to whatever answered on the port.
        FakeAgentConnection gone = new FakeAgentConnection().loseOn("trace.mark");
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n  - lock: { name: a }\n")
                .getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        RunResult result = new PlanExecutor(pack, gone, options).run(plan);
        assertEquals(FailureKind.AGENT_LOST, result.failureKind(), result.message());
        assertEquals(List.of("trace.mark"), gone.methods());
    }
}
