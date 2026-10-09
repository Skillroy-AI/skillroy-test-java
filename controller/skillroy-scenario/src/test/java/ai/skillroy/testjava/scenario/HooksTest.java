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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookTarget;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.hooks.HookEntry;
import ai.skillroy.testjava.scenario.hooks.HookInstaller;
import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hooks on the controller side (phase6-contracts.md 5.3, 6.1, 6.3, 6.4, 13.1 item 5): {@link HookInstaller} against
 * the fake agent for every branch of 5.3, step 0b inside a run, the run-scoped base fact root {@code hooks}, the
 * archived {@code capabilities.json} and its record, the session's install at open, and {@link RunOptions}' new
 * component.
 */
class HooksTest {

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private final List<Map<String, Object>> installs = new ArrayList<>();
    /** What the fake engine holds: the installed catalogue {id, hash}, or null. */
    private Map<String, Object> installed;
    private List<String> stubs = List.of();

    static HookEntry entry(String id, String className, String fallback) {
        return new HookEntry(id, HookMode.CAPTURE, true, new HookTarget(className, "limit",
                List.of("(Ljava/lang/String;)Ljava/math/BigDecimal;"), null), null, List.of(), false, null,
                List.of(new HookEntry.Fallback.Seam(fallback)), null);
    }

    static HookCatalogue catalogue(String id, int version) {
        return HookCatalogue.builder(id, version).packages(List.of("org.example.toy"))
                .add(entry("toy.applied", "org.example.toy.Desk", "toy.limits"))
                .add(entry("toy.missing", "org.example.toy.Gone", "toy.state"))
                .build();
    }

    @BeforeEach
    void fakeEngine() {
        pack.hooks = catalogue("toy", 1);
        agent.capabilities("toy.input", "toy.state", "toy.loan", "toy.limits");
        agent.capability(HookInstaller.ENGINE, CapabilityState.OK, null);
        agent.on("hook.health", params -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            Map<String, Object> hooks = new LinkedHashMap<>();
            if (installed != null) {
                hooks.put("toy.applied", Map.of("state", "FIRING", "count", 9L, "armed", false));
            }
            answer.put("hooks", hooks);
            answer.put("stubs", stubs);
            answer.put("spy", Map.of("version", 1L));
            if (installed != null) {
                answer.put("catalogue", installed);
            }
            return answer;
        });
        agent.on("hook.install", params -> {
            installs.add(params);
            Map<?, ?> catalogue = (Map<?, ?>) params.get("catalogue");
            installed = Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash"), "installedAt", 1L);
            agent.capability("hook.toy.applied", CapabilityState.OK, null);
            return Map.of("catalogue", Map.of("id", catalogue.get("id"), "hash", catalogue.get("hash"),
                    "alreadyInstalled", false), "hooks", List.of(
                            Map.of("id", "toy.applied", "state", "OK", "strategy", "advice", "capability",
                                    "hook.toy.applied"),
                            Map.of("id", "toy.missing", "state", "UNAVAILABLE", "reason", "class not loaded",
                                    "capability", "hook.toy.missing")),
                    "touched", List.of(Map.of("class", "org.example.toy.Desk")), "otherAgents", List.of("p.jar"));
        });
        agent.on("hook.events", params -> {
            long since = ((Number) params.get("since")).longValue();
            String topic = "hook." + params.get("id");
            List<Object> events = new ArrayList<>();
            for (Event e : List.copyOf(agent.bus)) {
                if (e.seq() > since && e.topic().equals(topic)) {
                    events.add(e.toJson());
                }
            }
            return Map.of("events", events);
        });
        agent.describe("toy.limits", false, "toy.limits");
        agent.describe("toy.state", false, "toy.state");
    }

    private HookInstaller.Result install(HooksMode mode, boolean replay) throws Exception {
        return HookInstaller.install(pack, agent, agent.capabilities(), mode, replay, Clock.systemUTC());
    }

    // ------------------------------------------------------------------------------------------ the installer

    @Test
    void offAndNoCatalogueSkipAndSendNothing() throws Exception {
        assertEquals(Map.of("mode", "off", "skipped", "off"), install(HooksMode.OFF, true).record());
        pack.hooks = HookCatalogue.EMPTY;
        HookInstaller.Result none = install(HooksMode.AUTO, true);
        assertEquals(Map.of("mode", "auto", "skipped", "no catalogue"), none.record());
        assertTrue(none.skipped());
        pack.hooks = HookCatalogue.builder(catalogue("toy", 1)).disable("toy.applied").disable("toy.missing")
                .build();
        assertEquals("no catalogue", install(HooksMode.AUTO, true).record().get("skipped"), "nothing enabled");
        assertTrue(agent.callsTo("hook.health").isEmpty() && agent.callsTo("hook.install").isEmpty());
    }

    @Test
    void onWithoutACatalogueIsAPreconditionFailure() {
        pack.hooks = HookCatalogue.EMPTY;
        HookInstaller.Refused e = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.ON, true));
        assertEquals(FailureKind.PRECONDITION_FAILED, e.kind());
        assertEquals("hooks ON, but pack toy has no hook catalogue", e.getMessage());
        assertEquals(false, e.record().get("ok"));
    }

    @Test
    void autoWithoutAnEngineSkipsAndOnIsRefusedWithTheReason() throws Exception {
        agent.capability(HookInstaller.ENGINE, CapabilityState.UNAVAILABLE, "no Instrumentation in EMBEDDED mode");
        HookInstaller.Result auto = install(HooksMode.AUTO, true);
        assertEquals("hook.engine is UNAVAILABLE: no Instrumentation in EMBEDDED mode", auto.record().get("skipped"));
        HookInstaller.Refused on = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.ON, true));
        assertEquals(FailureKind.PRECONDITION_FAILED, on.kind());
        assertEquals("hooks ON, but hook.engine is UNAVAILABLE: no Instrumentation in EMBEDDED mode", on.getMessage());
        assertEquals("hook.engine is absent: the hooks module is not loaded",
                HookInstaller.engineProblem(new CapabilityMatrix(Map.of())));
        assertTrue(agent.callsTo("hook.install").isEmpty());
    }

    @Test
    void anInstallSendsTheEnabledSpecsAndRecordsTheFallbacks() throws Exception {
        HookInstaller.Result result = install(HooksMode.AUTO, true);
        assertFalse(result.skipped());
        assertEquals(1, installs.size());
        Map<String, Object> params = installs.get(0);
        assertEquals(pack.hooks.descriptor(), params.get("catalogue"));
        assertFalse(params.containsKey("replace"));
        assertEquals(2, ((List<?>) params.get("specs")).size());
        assertEquals(Long.valueOf(HookInstaller.INSTALL_TIMEOUT_MS),
                agent.callsTo("hook.install").get(0).options().timeoutMs());
        Map<String, Object> record = result.record();
        assertEquals(List.of("mode", "catalogue", "installed", "unavailable", "armed", "touched", "ms",
                "otherAgents"), List.copyOf(record.keySet()));
        assertEquals(Map.of("id", "toy", "version", 1L, "hash", pack.hooks.hash()), record.get("catalogue"));
        assertEquals(List.of("toy.applied"), record.get("installed"));
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("id", "toy.missing");
        missing.put("state", "UNAVAILABLE");
        missing.put("reason", "class not loaded");
        missing.put("fallback", "seam:toy.state");
        assertEquals(List.of(missing), record.get("unavailable"));
        assertEquals(1L, record.get("touched"));
        assertEquals(List.of("p.jar"), record.get("otherAgents"));
        // A seam whose capability is not OK is no fallback.
        agent.capability("toy.state", CapabilityState.UNAVAILABLE, "down");
        installed = null;
        Map<?, ?> again = (Map<?, ?>) ((List<?>) install(HooksMode.AUTO, true).record().get("unavailable")).get(0);
        assertNull(again.get("fallback"));
    }

    @Test
    void theSameCatalogueIsAlreadyInstalledAndANewerOneReplacesIt() throws Exception {
        install(HooksMode.AUTO, true);
        HookInstaller.Result again = install(HooksMode.AUTO, true);
        assertTrue(again.alreadyInstalled());
        assertEquals(Map.of("mode", "auto", "alreadyInstalled", true, "catalogue", Map.of("id", "toy", "hash",
                pack.hooks.hash()), "armed", List.of()), again.record());
        assertEquals(1, installs.size(), "nothing sent the second time");
        pack.hooks = catalogue("toy", 2);
        HookInstaller.Result newer = install(HooksMode.AUTO, true);
        assertEquals(2, installs.size());
        assertEquals(true, installs.get(1).get("replace"));
        assertEquals(true, newer.record().get("replaced"));
    }

    @Test
    void anotherCatalogueOnTheTargetIsEnvironment() {
        installed = Map.of("id", "other", "hash", "sha256:00");
        HookInstaller.Refused e = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
        assertEquals(FailureKind.ENVIRONMENT, e.kind());
        assertEquals("catalogue other is installed on this target; `skillroy hooks remove` first", e.getMessage());
        assertTrue(installs.isEmpty());
    }

    @Test
    void anArmedStubIsEnvironmentInReplayAndRecordedInLive() throws Exception {
        stubs = List.of("toy.applied");
        HookInstaller.Refused replay = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
        assertEquals(FailureKind.ENVIRONMENT, replay.kind());
        assertEquals("a stub is armed on the target: toy.applied; `skillroy hooks status`", replay.getMessage());
        assertEquals(List.of("toy.applied"), replay.detail().get("armed"));
        assertEquals(List.of("toy.applied"), install(HooksMode.AUTO, false).record().get("armed"));
    }

    @Test
    void anAgentErrorOfTheInstallIsEnvironmentWithTheError() {
        for (int code : new int[] {ErrorCodes.HOOK_VERIFY_FAILED, ErrorCodes.HOOK_CONFLICT, ErrorCodes.TIMEOUT}) {
            agent.on("hook.install", params -> {
                throw new AgentRpcException("hook.install", new RpcError(code, "refused", Map.of("why", "x")));
            });
            HookInstaller.Refused e = assertThrows(HookInstaller.Refused.class, () -> install(HooksMode.AUTO, true));
            assertEquals(FailureKind.ENVIRONMENT, e.kind());
            assertTrue(e.getMessage().startsWith("hook.install failed: " + ErrorCodes.name(code)), e.getMessage());
            assertEquals(code, ((Number) ((Map<?, ?>) e.detail().get("error")).get("code")).intValue());
        }
    }

    // ------------------------------------------------------------------------------------------ step 0b in a run

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private RunOptions options() {
        return RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withBind(TestCredentials.bind())
                .withPollInterval(Duration.ofMillis(100)).withStepTimeout(Duration.ofSeconds(2));
    }

    private void toyInputFiresTheHook() {
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of());
            agent.publish("hook.toy.applied", Map.of("hook", "toy.applied", "args", Map.of("0", "100"), "returned",
                    "20.00"));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of("accepted", true);
        });
    }

    static final String GUARDED = """
            steps:
              - enter: { value: "100" }
              - expect:
                  "hooks[id=toy.applied].count": { gte: 1, capability: hook.toy.applied }
                  "hooks[id=toy.applied].last.returned": { equals: "20.00", capability: hook.toy.applied }
                  "hooks[id=toy.applied].last.args.0": { equals: "100", capability: hook.toy.applied }
            """;

    @Test
    void stepZeroBInstallsBeforeRequiresAndTheGuardedExpectationsPass(@TempDir Path dir) throws Exception {
        toyInputFiresTheHook();
        // A fire before the run's first step (a verifier's call, an earlier run's) never counts.
        agent.publish("hook.toy.applied", Map.of("returned", "999.00"));
        RunResult result = new PlanExecutor(pack, agent, options().withOutputDir(dir))
                .run(plan("requires: [toy.input, hook.toy.applied]\n" + GUARDED));
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of(), result.skippedExpectations());
        List<Map<String, Object>> log = runLog(dir);
        List<String> types = log.stream().map(r -> (String) r.get("type")).toList();
        assertEquals(List.of("run-start", "hooks", "capabilities", "requires"), types.subList(0, 4));
        assertEquals(List.of("toy.applied"), log.get(1).get("installed"));
        Map<String, Object> capabilities = Json.parseObject(Files.readString(dir.resolve("capabilities.json")));
        CapabilityMatrix archived = CapabilityMatrix.fromJson(capabilities);
        assertTrue(archived.isOk("hook.toy.applied"), "the matrix after the install is archived");
        assertEquals(archived.hash(), log.get(2).get("hash"));
        assertEquals((long) archived.all().size(), log.get(2).get("ids"));
        assertEquals("capabilities.json", log.get(2).get("file"));
    }

    @Test
    void withoutTheEngineTheGuardedExpectationsAreSkippedAndTheRunIsDegraded(@TempDir Path dir) throws Exception {
        toyInputFiresTheHook();
        agent.capability(HookInstaller.ENGINE, CapabilityState.UNAVAILABLE, "no Instrumentation in EMBEDDED mode");
        agent.on("hook.health", params -> {
            throw new AgentRpcException("hook.health", new RpcError(ErrorCodes.CAPABILITY_UNAVAILABLE, "no", null));
        });
        RunResult result = new PlanExecutor(pack, agent, options().withOutputDir(dir)).run(plan(GUARDED));
        assertEquals(Status.DEGRADED, result.status(), result.message());
        assertEquals(3, result.skippedExpectations().size());
        assertTrue(result.skippedExpectations().stream().allMatch(s -> s.contains("hook.toy.applied")),
                String.valueOf(result.skippedExpectations()));
        assertEquals("hook.engine is UNAVAILABLE: no Instrumentation in EMBEDDED mode", runLog(dir).get(1)
                .get("skipped"));
        assertTrue(agent.callsTo("hook.install").isEmpty());
    }

    @Test
    void aRefusedInstallEndsTheRunBeforeTheBind(@TempDir Path dir) throws Exception {
        stubs = List.of("toy.applied");
        RunResult replay = new PlanExecutor(pack, agent, options().withOutputDir(dir)).run(plan(GUARDED));
        assertEquals(Status.ERROR, replay.status());
        assertEquals(FailureKind.ENVIRONMENT, replay.failureKind());
        assertTrue(replay.message().startsWith("a stub is armed on the target"), replay.message());
        assertEquals(false, runLog(dir).get(1).get("ok"));
        assertTrue(agent.callsTo("toy.input").isEmpty(), "no step ran");
        stubs = List.of();
        pack.hooks = HookCatalogue.EMPTY;
        RunResult on = new PlanExecutor(pack, agent, options().withHooks(HooksMode.ON)).run(plan(GUARDED));
        assertEquals(Status.ERROR, on.status());
        assertEquals(FailureKind.PRECONDITION_FAILED, on.failureKind());
    }

    @Test
    void anArmedStubIsRecordedInLive(@TempDir Path dir) throws Exception {
        toyInputFiresTheHook();
        stubs = List.of("toy.applied");
        RunResult live = new PlanExecutor(pack, agent, options().withOutputDir(dir)
                .withBind(TestCredentials.bind().withMode(BindMode.LIVE))).run(plan(GUARDED));
        assertEquals(Status.PASS, live.status(), live.message());
        assertEquals(List.of("toy.applied"), runLog(dir).get(1).get("armed"));
    }

    @Test
    void offInstallsNothingAndTheFactRootIsEmptyWithoutTheModule(@TempDir Path dir) throws Exception {
        toyInputFiresTheHook();
        RunResult result = new PlanExecutor(pack, agent, options().withOutputDir(dir).withHooks(HooksMode.OFF))
                .run(plan(GUARDED));
        assertEquals(Status.DEGRADED, result.status(), result.message());
        assertEquals(3, result.skippedExpectations().size(), "hook.toy.applied is absent: nothing was installed");
        assertEquals(Map.of("mode", "off", "skipped", "off"), PlanExecutorTest.stripTime(runLog(dir).get(1)));
        assertTrue(agent.callsTo("hook.install").isEmpty());
    }

    @Test
    void theFactRootCountsOnlyTheRunsStepsAndIsEmptyWhenNotReady() throws Exception {
        installed = Map.of("id", "toy", "hash", "x");
        agent.publish("hook.toy.applied", Map.of("returned", "1.00"));
        long since = agent.lastSeq();
        agent.publish("hook.toy.applied", Map.of("returned", "2.00"));
        agent.publish("hook.toy.applied", Map.of("returned", "3.00"));
        Object root = BaseFacts.hooks(() -> since).fetch(agent);
        assertTrue(root instanceof List<?> list && list.size() == 1, String.valueOf(root));
        Map<?, ?> fact = (Map<?, ?>) ((List<?>) root).get(0);
        assertEquals("toy.applied", fact.get("id"));
        assertEquals("FIRING", fact.get("state"));
        assertEquals(2L, fact.get("count"), "the cumulative 9 of hook.health is not this run's");
        assertEquals(Map.of("returned", "3.00"), fact.get("last"));
        assertEquals(false, fact.get("armed"));
        assertTrue(fact.get("lastFiredMs") instanceof Long);
        assertEquals(1000L, ((Number) agent.callsTo("hook.events").get(0).params().get("limit")).longValue());
        for (int code : new int[] {ErrorCodes.METHOD_NOT_FOUND, ErrorCodes.CAPABILITY_UNAVAILABLE,
                ErrorCodes.NOT_READY}) {
            agent.error("hook.health", code, "none");
            assertEquals(List.of(), BaseFacts.hooks(() -> 0L).fetch(agent), ErrorCodes.name(code));
        }
        agent.error("hook.health", ErrorCodes.INTERNAL_ERROR, "boom");
        assertThrows(AgentRpcException.class, () -> BaseFacts.hooks(() -> 0L).fetch(agent));
    }

    // ------------------------------------------------------------------------------------------ the session

    @Test
    void aSessionInstallsAtOpenAndArchivesTheMatrix(@TempDir Path dir) throws Exception {
        try (ExecutorSession session = ExecutorSession.open(pack, agent, SessionOptions.defaults(dir)
                .withClock(clock).withBind(TestCredentials.bind()))) {
            assertEquals(List.of("toy.applied"), session.hooks().get("installed"));
            assertTrue(CapabilityMatrix.fromJson(Json.parseObject(Files.readString(dir.resolve("capabilities.json"))))
                    .isOk("hook.toy.applied"));
        }
        pack.hooks = HookCatalogue.EMPTY;
        SessionOpenException e = assertThrows(SessionOpenException.class, () -> ExecutorSession.open(pack, agent,
                SessionOptions.defaults(null).withHooks(HooksMode.ON).withBind(TestCredentials.bind())));
        assertEquals(FailureKind.PRECONDITION_FAILED, e.kind());
    }

    // ------------------------------------------------------------------------------------------ RunOptions

    @Test
    void runOptionsCarryTheSwitchAndTheOldConstructorsFillTheDefault() {
        assertEquals(HooksMode.AUTO, RunOptions.defaults().hooks());
        assertEquals(HooksMode.OFF, RunOptions.defaults().withHooks(HooksMode.OFF).hooks());
        assertEquals(HooksMode.AUTO, RunOptions.defaults().withHooks(null).hooks());
        RunOptions phase5 = new RunOptions(null, null, null, null, null, null, null, true, BindOptions.defaults(),
                true);
        assertEquals(HooksMode.AUTO, phase5.hooks());
        assertTrue(phase5.expectationsOnMaskedValues());
        assertEquals(HooksMode.AUTO, new RunOptions(null, null, null, null, null, null, null, true).hooks());
        assertEquals(HooksMode.ON, RunOptions.defaults().withHooks(HooksMode.ON).withStepTimeout(Duration.ofSeconds(3))
                .withBind(BindOptions.defaults()).withCaptureOnFailure(false).hooks(), "every copy keeps it");
        assertEquals(HooksMode.AUTO, SessionOptions.defaults(null).hooks());
        assertEquals(HooksMode.OFF, SessionOptions.defaults(null).withHooks(HooksMode.OFF).withReadOnly(true)
                .hooks());
    }

    static List<Map<String, Object>> runLog(Path dir) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            records.add(Json.parseObject(line));
        }
        return records;
    }
}
