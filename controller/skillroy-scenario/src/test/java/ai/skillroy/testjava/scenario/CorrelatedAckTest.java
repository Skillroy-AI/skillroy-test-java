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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Acknowledgements correlated by input (phase8-contracts.md 7.4, gap 107; 13.1 row 32). */
class CorrelatedAckTest {

    @TempDir
    Path dir;

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private final AtomicInteger minted = new AtomicInteger();
    /** When true, the fake publishes an earlier input's late acknowledgement before this input's own. */
    private boolean lateAckOfAnother;
    /** When true, the fake returns no inputId. */
    private boolean noId;

    @BeforeEach
    void toyApplication() {
        pack.syncPolicy = new SyncPolicy("input.acked", List.of("input.requested"), List.of("prompt.waiting"),
                Duration.ofSeconds(2), Duration.ofSeconds(3), true);
        agent.capabilities("toy.input", "toy.state", "toy.loan");
        agent.on("toy.input", params -> {
            String id = "toy-" + minted.incrementAndGet();
            if (lateAckOfAnother) {
                agent.publish("input.acked", Map.of("source", "Entry", "inputId", "toy-earlier"));
            }
            agent.publish("input.acked", Map.of("source", params.get("source"), "inputId", id));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return noId ? Map.of("accepted", true) : Map.of("accepted", true, "inputId", id, "consumed", "CONSUMED");
        });
        agent.value("toy.state", Map.of("name", "LOAN"));
        agent.value("toy.loan", Map.of("lines", List.of()));
    }

    private RunResult run(String body) throws ScenarioException {
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withBind(TestCredentials.bind()).withOutputDir(dir);
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    private List<Map<String, Object>> records(String type) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if (type.equals(record.get("type"))) {
                out.add(record);
            }
        }
        return out;
    }

    @Test
    void ackAndReadyAwaitTheAckOfTheirOwnInput() throws Exception {
        RunResult result = run("steps:\n  - ackOnly: { value: 1 }\n  - enter: { value: 2 }\n");
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of(
                new FakeAgentConnection.Await("input.acked", 1, Map.of("inputId", "toy-1"), Duration.ofSeconds(2)),
                new FakeAgentConnection.Await("input.acked", 3, Map.of("inputId", "toy-2"), Duration.ofSeconds(2)),
                new FakeAgentConnection.Await("input.requested", 3, null, Duration.ofSeconds(3))), agent.awaits,
                "the ready wait stays 'since seq.before', uncorrelated");
        List<Map<String, Object>> syncs = records("sync");
        assertEquals(Map.of("inputId", "toy-1"), syncs.get(0).get("match"), "the sync record shows the match");
    }

    @Test
    void anotherInputsAckBetweenTheCallAndTheWaitIsIgnored() throws Exception {
        lateAckOfAnother = true;
        RunResult result = run("steps:\n  - ackOnly: { value: 1 }\n");
        assertEquals(Status.PASS, result.status(), result.message());
        long ownSeq = ((Number) records("sync").get(0).get("eventSeq")).longValue();
        assertEquals("toy-1", agent.bus.stream().filter(e -> e.seq() == ownSeq).findFirst().orElseThrow()
                .payload().get("inputId"), "the wait ended on this input's own acknowledgement");

        // The uncorrelated policy would have taken the other input's acknowledgement.
        pack.syncPolicy = pack.syncPolicy.withAckByInputId(false);
        assertEquals(Status.PASS, run("steps:\n  - ackOnly: { value: 1 }\n").status());
        long anySeq = ((Number) records("sync").get(0).get("eventSeq")).longValue();
        assertEquals("toy-earlier", agent.bus.stream().filter(e -> e.seq() == anySeq).findFirst().orElseThrow()
                .payload().get("inputId"));
    }

    @Test
    void anAckThatNeverComesNamesTheInputId() throws Exception {
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source"), "inputId", "toy-other"));
            return Map.of("accepted", true, "inputId", "toy-9", "consumed", "QUEUED");
        });
        RunResult result = run("steps:\n  - ackOnly: { value: 1 }\n");
        assertEquals(Status.FAIL, result.status());
        assertEquals(FailureKind.SYNC_TIMEOUT, result.failureKind());
        assertTrue(result.message().contains("no ack event (input.acked) matching {\"inputId\":\"toy-9\"} within"),
                result.message());
    }

    @Test
    void aResultWithoutAnInputIdIsAnEnvironmentErrorAndNothingIsAwaited() throws Exception {
        noId = true;
        RunResult result = run("steps:\n  - ackOnly: { value: 1 }\n");
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertEquals("the pack's acknowledgements are correlated by inputId; toy.input returned none",
                result.message());
        assertTrue(agent.awaits.isEmpty(), "fail closed: nothing awaited");
    }

    @Test
    void cleanupCallsCorrelateToo() throws Exception {
        pack.custom = List.of(new PlannedCall("toy.input", Map.of("source", "Entry", "value", "x"), Sync.NONE, 0));
        pack.onCustom = ctx -> ctx.addCleanup(new PlannedCall("toy.input", Map.of("source", "Key.SIGN_OFF"),
                Sync.ACK, 2000));
        RunResult result = run("steps:\n  - custom: {}\n");
        assertEquals(Status.PASS, result.status(), result.message());
        FakeAgentConnection.Await cleanup = agent.awaits.get(agent.awaits.size() - 1);
        assertEquals(Map.of("inputId", "toy-2"), cleanup.match());
    }

    @Test
    void withoutTheFlagTheWaitIsTodays() throws Exception {
        pack.syncPolicy = new SyncPolicy("input.acked", List.of("input.requested"), List.of("prompt.waiting"),
                null, null);
        assertEquals(Status.PASS, run("steps:\n  - ackOnly: { value: 1 }\n").status());
        assertEquals(List.of(new FakeAgentConnection.Await("input.acked", 1, null, Duration.ofSeconds(10))),
                agent.awaits, "PlanExecutorTest.syncAckAwaitsTheAckTopicSinceSeqBefore's assertion");
    }
}
