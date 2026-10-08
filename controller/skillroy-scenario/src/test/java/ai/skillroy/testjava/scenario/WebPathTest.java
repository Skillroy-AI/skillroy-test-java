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
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.web.WebLib;
import ai.skillroy.testjava.scenario.web.AgentWebPath;
import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;
import ai.skillroy.testjava.scenario.web.WebPathException;
import ai.skillroy.testjava.scenario.web.WebPathOpenException;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.web.WebSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase4-contracts.md 6.1, 6.3, 8.2 (12.1 item 4, the run half): web path selection for every {@code web.path}
 * against the fake agent and the fake {@code cdp} path that {@code ServiceLoader} finds ({@link FakeCdpPath}), the
 * {@code web.path} record, the per-call {@code UNSUPPORTED} fall-back and its record, {@code path} on every web call
 * record, waits served from the path's events and from the bus, the path closed however the run ends, the trace on
 * failure (6.4: withheld before the path opens when a call may carry a secret, the failure-time guard behind it, and
 * recorded as saved only when the path wrote it), and the run's web summary.
 */
class WebPathTest {

    /** Two web steps that the fake path and the agent both serve. */
    static final String STEPS = """
              - webFill: { selector: "#item", text: hammer }
              - webClick: { role: button, name: Borrow }
            """;

    @TempDir
    Path dir;

    private final ToyPack pack = new ToyPack();
    private FakeAgentConnection agent = freshAgent();
    private final TestClock clock = new TestClock();
    private RunOptions options;
    private final FakeCdpPath.Script cdp = FakeCdpPath.SCRIPT;

    @BeforeEach
    void setUp() {
        cdp.reset();
        cdp.answers.put("web.dom.fill", args -> Map.of("matched", 1L, "filled", Map.of("tag", "INPUT", "type",
                "text"), "typed", 6L));
        cdp.answers.put("web.dom.click", args -> Map.of("matched", 1L, "clicked", Map.of("role", "button", "name",
                "Borrow", "tag", "BUTTON"), "fidelity", "DOM"));
        cdp.answers.put("web.dom.text", args -> Map.of("text", "cdp text of " + args.get("browser"), "truncated",
                false));
        cdp.answers.put("web.tap", args -> Map.of("installed", (long) ((List<?>) args.get("patterns")).size(),
                "timing", "DOCUMENT_START"));
        cdp.answers.put("web.dispatchEvent", args -> Map.of("dispatched", true));
        options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withOutputDir(dir.resolve("out")).withStepTimeout(Duration.ofSeconds(2));
    }

    /** The fake agent with the web module and an IDLE toy application. */
    private static FakeAgentConnection freshAgent() {
        FakeAgentConnection fresh = new FakeAgentConnection().withWeb();
        fresh.value("toy.state", Map.of("name", "IDLE"));
        fresh.value("swing.edtState", Map.of("dispatchThreadAlive", true));
        return fresh;
    }

    private Plan compile(String web, String steps) throws ScenarioException {
        String yaml = "scenario: t\npack: toy\n" + (web == null ? "" : "web: " + web + "\n") + "steps:\n" + steps;
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
    }

    private RunResult run(String web, String steps) throws ScenarioException {
        return new PlanExecutor(pack, agent, options).run(compile(web, steps));
    }

    private RunResult run(String web, String steps, ClassLoader loader) throws ScenarioException {
        return new PlanExecutor(pack, agent, options, loader).run(compile(web, steps));
    }

    /** The {@code run.jsonl} records of this type, without {@code type} and {@code t}. */
    private List<Map<String, Object>> records(String type) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("out").resolve("run.jsonl"))) {
            Map<String, Object> record = new LinkedHashMap<>(Json.parseObject(line));
            if (type.equals(record.remove("type"))) {
                record.remove("t");
                out.add(record);
            }
        }
        return out;
    }

    private List<String> types() throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("out").resolve("run.jsonl"))) {
            out.add(String.valueOf(Json.parseObject(line).get("type")));
        }
        return out;
    }

    /** The {@code path} of each {@code call} record of a {@code web.*} primitive, in order. */
    private List<String> webCallPaths() throws IOException {
        return records("call").stream().filter(r -> String.valueOf(r.get("primitive")).startsWith("web."))
                .map(r -> String.valueOf(r.get("path"))).toList();
    }

    private static Map<String, Object> pathRecord(String path, String reason, Map<String, Object> cdpInfo,
                                                  String policy) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("path", path);
        if (reason != null) {
            record.put("reason", reason);
        }
        if (cdpInfo != null) {
            record.put("cdp", cdpInfo);
        }
        record.put("libHash", WebLib.hash());
        record.put("policy", policy);
        return record;
    }

    private void webCdp(CapabilityState state, String reason) {
        agent.capability("web.cdp", state, reason);
    }

    // ------------------------------------------------------------------------------------------------ selection

    @Test
    void autoUsesTheCdpPathWhenItIsThereWebCdpIsOkAndItOpens() throws Exception {
        webCdp(CapabilityState.OK, null);
        RunResult result = run(null, STEPS);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of(pathRecord("cdp", null, cdp.openInfo, "auto")), records("web.path"));
        assertEquals(List.of("cdp", "cdp"), webCallPaths(), "path on every web call record");
        assertEquals(List.of("web.dom.fill", "web.dom.click"), cdp.calls.stream().map(FakeCdpPath.Call::primitive)
                .toList());
        assertEquals(List.of(), agent.callsTo("web.dom.fill"));
        assertEquals(List.of(), agent.callsTo("web.dom.click"));
        assertEquals(2, agent.callsTo("swing.settle").size(), "SETTLE still settles the agent's EDT");
        FakeCdpPath opened = cdp.opened();
        assertNotNull(opened);
        assertEquals(WebSettings.DEFAULT, opened.openedWith);
        assertEquals(dir.resolve("out"), opened.context.artifactsDir());
        assertEquals(1, opened.closes, "closed when the run ends");
        assertEquals("controller", records("call").get(1).get("thread"));
        List<String> types = types();
        assertTrue(types.indexOf("requires") < types.indexOf("web.path")
                && types.indexOf("web.path") < types.indexOf("bind"), "step 1a: after the capability check and"
                + " before pre-flight and bind: " + types);
        assertEquals(1, types.stream().filter("web.path"::equals).count(), "recorded once");
        assertEquals("cdp", records("call").get(1).get("path"), "path follows the primitive");
        assertEquals(List.of("step", "index", "primitive", "path", "params", "ok", "value", "events", "seq",
                "durationMs", "thread"), new ArrayList<>(records("call").get(1).keySet()));
    }

    /**
     * A36 (phase4-contracts.md 6.3, 16.2): before a call the cdp path serves, the executor reads the agent's sequence
     * once ({@code evt.query {since, limit: 1}}), and that number, not the path's own, is both {@code seq.before} and
     * {@code seq.after} of the call record; a read the executor makes on its own through the path (the fact root)
     * reads none.
     */
    @Test
    void aCallTheCdpPathServesReadsTheAgentsSequenceOnceAndRecordsIt() throws Exception {
        webCdp(CapabilityState.OK, null);
        FakeAgentConnection.Handler query = agent.handler("evt.query");
        List<Long> read = new CopyOnWriteArrayList<>();
        agent.on("evt.query", params -> {
            Object value = query.handle(params);
            if (!params.containsKey("topics")) {
                read.add(((Number) ((Map<?, ?>) value).get("lastSeq")).longValue());
            }
            return value;
        });
        RunResult result = run(null, STEPS + """
                  - expect: { "web.browsers[tag=front].text": { contains: "cdp text of h7" } }
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        List<String> primitives = cdp.calls.stream().map(FakeCdpPath.Call::primitive).toList();
        assertEquals(List.of("web.dom.fill", "web.dom.click"), primitives.subList(0, 2));
        assertTrue(primitives.size() > 2 && primitives.subList(2, primitives.size()).stream()
                .allMatch("web.dom.text"::equals), "the fact root's text reads went through the path: " + primitives);
        List<Map<String, Object>> served = records("call").stream()
                .filter(r -> "cdp".equals(r.get("path"))).toList();
        assertEquals(2, served.size());
        assertEquals(2, read.size(), "one sequence read per served step call, none for the fact read: " + read);
        for (int i = 0; i < served.size(); i++) {
            assertEquals(Map.of("before", read.get(i), "after", read.get(i)), served.get(i).get("seq"),
                    "the executor's read, not the path's numbers (10000 and up): " + served.get(i));
        }
    }

    @Test
    void autoTakesTheAgentPathAndRecordsWhyForEveryReasonTheCdpPathCannotBeUsed() throws Exception {
        String broken = "the web path cdp cannot be loaded: ";
        record Case(String name, Runnable setUp, ClassLoader loader, String reason, boolean opened) {
        }
        List<Case> cases = List.of(
                new Case("absent", () -> webCdp(CapabilityState.OK, null), FakeCdpPath.withoutWebPaths(),
                        "no web path with id cdp is on the class path", false),
                new Case("missing capability", () -> { }, null, "capability web.cdp is missing", false),
                new Case("unavailable", () -> webCdp(CapabilityState.UNAVAILABLE, "no remote-debugging endpoint"
                        + " configured"), null, "capability web.cdp is UNAVAILABLE (no remote-debugging endpoint"
                        + " configured)", false),
                new Case("degraded", () -> webCdp(CapabilityState.DEGRADED, "port unknown"), null,
                        "capability web.cdp is DEGRADED (port unknown)", false),
                new Case("open reason", () -> {
                    webCdp(CapabilityState.OK, null);
                    cdp.openReason = "no remote-debugging endpoint configured";
                }, null, "the cdp path is not usable: no remote-debugging endpoint configured", true),
                new Case("attach failed", () -> {
                    webCdp(CapabilityState.OK, null);
                    cdp.openFailure = new WebPathOpenException(WebPathOpenException.Kind.TIMEOUT,
                            "connectOverCDP did not finish within 30 s", null);
                }, null, "the cdp path could not attach (TIMEOUT): connectOverCDP did not finish within 30 s", true),
                new Case("open threw", () -> {
                    webCdp(CapabilityState.OK, null);
                    cdp.openFailure = new IllegalStateException("driver missing");
                }, null, "the cdp path failed to open: java.lang.IllegalStateException: driver missing", true));
        for (Case c : cases) {
            cdp.reset();
            agent = freshAgent();
            c.setUp().run();
            RunResult result = run(null, STEPS, c.loader());
            assertEquals(Status.PASS, result.status(), c.name() + ": " + result.message());
            assertEquals(List.of(pathRecord("agent", c.reason(), null, "auto")), records("web.path"), c.name());
            assertEquals(List.of("agent", "agent"), webCallPaths(), c.name());
            assertEquals(1, agent.callsTo("web.dom.fill").size(), c.name());
            assertEquals(List.of(), cdp.calls, c.name());
            for (FakeCdpPath path : cdp.instances) {
                assertEquals(c.opened() ? 1 : 0, path.closes, c.name() + ": an opened path is closed again");
            }
        }
        cdp.reset();
        agent = freshAgent();
        webCdp(CapabilityState.OK, null);
        assertEquals(Status.PASS, run(null, STEPS, FakeCdpPath.withABrokenWebPath(dir.resolve("broken"))).status());
        String reason = String.valueOf(records("web.path").get(0).get("reason"));
        assertTrue(reason.startsWith(broken) && reason.contains("com.example.NoSuchWebPath"), reason);
        assertEquals("agent", records("web.path").get(0).get("path"));
    }

    @Test
    void aPinnedCdpPathThatCannotBeUsedEndsTheRunBeforeAnyStep() throws Exception {
        record Case(String name, Runnable setUp, ClassLoader loader, FailureKind kind, String message) {
        }
        List<Case> cases = List.of(
                new Case("absent", () -> webCdp(CapabilityState.OK, null), FakeCdpPath.withoutWebPaths(),
                        FailureKind.PRECONDITION_FAILED, "web.path is cdp, but no web path with id cdp is on the"
                        + " class path"),
                new Case("degraded", () -> webCdp(CapabilityState.DEGRADED, "port unknown"), null,
                        FailureKind.PRECONDITION_FAILED, "web.path is cdp, but capability web.cdp is DEGRADED (port"
                        + " unknown)"),
                new Case("open reason", () -> {
                    webCdp(CapabilityState.OK, null);
                    cdp.openReason = "no page target";
                }, null, FailureKind.PRECONDITION_FAILED, "web.path is cdp, but the cdp path is not usable: no page"
                        + " target"),
                new Case("open threw", () -> {
                    webCdp(CapabilityState.OK, null);
                    cdp.openFailure = new IllegalStateException("driver missing");
                }, null, FailureKind.ENVIRONMENT, "web.path is cdp, but the cdp path failed to open:"
                        + " java.lang.IllegalStateException: driver missing"));
        for (Case c : cases) {
            cdp.reset();
            agent = freshAgent();
            c.setUp().run();
            RunResult result = run("{ path: cdp }", STEPS, c.loader());
            assertEquals(Status.ERROR, result.status(), c.name());
            assertEquals(c.kind(), result.failureKind(), c.name());
            assertEquals(c.message(), result.message(), c.name());
            assertEquals("cdp", result.detail().get("webPath"), c.name());
            assertEquals(List.of(), result.steps(), c.name() + ": no step runs");
            assertEquals(List.of(), agent.callsTo("trace.mark"), c.name() + ": not even the start mark");
            assertEquals(List.of(), agent.calls.stream().filter(call -> call.method().startsWith("web."))
                    .toList(), c.name());
            Map<String, Object> record = records("web.path").get(0);
            assertNull(record.get("path"), c.name());
            assertEquals("cdp", record.get("policy"), c.name());
            assertEquals(List.of(), records("cleanup"), c.name() + ": nothing more is sent (D30)");
        }

        cdp.reset();
        agent = freshAgent();
        agent.capability("web.cdp", CapabilityState.UNAVAILABLE, "no remote-debugging endpoint configured");
        RunResult noPort = run("{ path: cdp }", STEPS, null);
        assertEquals(FailureKind.PRECONDITION_FAILED, noPort.failureKind());
        assertEquals("unmet requirements: web.cdp: UNAVAILABLE (no remote-debugging endpoint configured)",
                noPort.message(), "the compiler put web.cdp into requires: the capability check names it first");
    }

    @Test
    void aPinnedCdpPathWithFrozenTargetsIsAnEnvironmentErrorWithTheTargets() throws Exception {
        webCdp(CapabilityState.OK, null);
        List<Map<String, Object>> frozen = List.of(Map.of("id", "T-back", "url", "data:text/html,back#freeze",
                "title", "back page"));
        cdp.openFailure = new WebPathOpenException(WebPathOpenException.Kind.FROZEN_TARGETS,
                "1 page target did not answer the liveness probe", Map.of("frozenTargets", frozen));
        RunResult result = run("{ path: cdp, frozenTargets: reload }", STEPS);
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertEquals("web.path is cdp, but the cdp path could not attach (FROZEN_TARGETS): 1 page target did not"
                + " answer the liveness probe", result.message());
        assertEquals(frozen, result.detail().get("frozenTargets"));
        assertEquals("FROZEN_TARGETS", result.detail().get("attach"));
        assertEquals(List.of(), result.steps());
        assertEquals(1, cdp.instances.get(0).closes, "the half-opened path is closed");
    }

    @Test
    void theOpenedPathGetsTheScenariosSettingsAndCanRecordThroughItsRunContext() throws Exception {
        webCdp(CapabilityState.OK, null);
        Map<String, Object> recover = new LinkedHashMap<>();
        recover.put("type", "web.recover");
        recover.put("browser", "h9");
        recover.put("action", "reload");
        recover.put("ok", true);
        cdp.recordsOnOpen.add(recover);
        assertEquals(Status.PASS, run("{ frozenTargets: reload, browser: { tag: front } }", STEPS).status());
        assertEquals(new WebSettings(WebSettings.Path.AUTO, Map.of("tag", "front"), FrozenTargetPolicy.RELOAD,
                Duration.ofSeconds(30), false), cdp.opened().openedWith);
        assertEquals(List.of(Map.of("browser", "h9", "action", "reload", "ok", true)), records("web.recover"));
        assertEquals(Map.of("tag", "front"), cdp.calls.get(0).args().get("by"), "a scenario never knows refs");
    }

    @Test
    void agentPinnedNeverLooksForAnotherPath() throws Exception {
        webCdp(CapabilityState.OK, null);
        assertEquals(Status.PASS, run("{ path: agent }", STEPS).status());
        assertEquals(List.of(pathRecord("agent", "the scenario pins web.path: agent", null, "agent")),
                records("web.path"));
        assertEquals(List.of(), cdp.instances, "ServiceLoader was not even asked");
        assertEquals(List.of("agent", "agent"), webCallPaths());
    }

    @Test
    void aPlanWithoutWebCallsChoosesNoPath() throws Exception {
        webCdp(CapabilityState.OK, null);
        assertEquals(Status.PASS, run("{ path: cdp }", "  - mark: x\n").status());
        assertEquals(List.of(), records("web.path"));
        assertEquals(List.of(), cdp.instances);
        assertNull(new PlanExecutor(pack, agent, options).runWithOutcome(compile(null, "  - mark: x\n")).web());
    }

    @Test
    void theAgentPathServesEveryWebPrimitiveThroughTheAgent() throws Exception {
        AgentWebPath path = new AgentWebPath();
        assertEquals("agent", path.id());
        assertNull(path.open(agent, WebSettings.DEFAULT, null), "always usable");
        assertTrue(path.serves("web.dom.click") && path.serves("web.cdp.status") && path.serves("web.anything"));
        assertFalse(path.serves("swing.click"));
        assertEquals(Map.of("dispatched", true), path.call("web.dispatchEvent", Map.of("name", "x"),
                CallOptions.defaults()).value());
        assertEquals(1, agent.callsTo("web.dispatchEvent").size());
        assertEquals(List.of(), path.eventsSince(0, "*"), "its events are the agent's bus");
        path.close();
        assertTrue(new AgentWebPath(agent).serves("web.browsers"));
    }

    // ------------------------------------------------------------------------------------------------ calls

    @Test
    void aCallThePathCannotServeGoesToTheAgentAndIsRecorded() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.answers.put("web.dom.click", args -> {
            throw WebPathException.unsupported("browser h7 has no CDP target id");
        });
        RunResult result = run(null, STEPS);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of(Map.of("step", "s2", "primitive", "web.dom.click", "reason",
                "browser h7 has no CDP target id", "from", "cdp")), records("web.fallback"));
        assertEquals(List.of("cdp", "agent"), webCallPaths());
        assertEquals(1, agent.callsTo("web.dom.click").size());
        assertEquals(List.of("web.dom.fill", "web.dom.click"), cdp.calls.stream().map(FakeCdpPath.Call::primitive)
                .toList(), "asked first");
    }

    @Test
    void aPrimitiveThePathDoesNotServeGoesToTheAgentWithoutAFallBack() throws Exception {
        webCdp(CapabilityState.OK, null);
        agent.on("web.cdp.status", params -> Map.of("endpoints", List.of()));
        agent.on("web.reload", params -> Map.of("reloaded", true));
        RunResult again = run(null, "  - call: { primitive: web.cdp.status }\n"
                + "  - call: { primitive: web.reload, args: { browser: h9 } }\n");
        assertEquals(Status.PASS, again.status(), again.message());
        assertEquals(List.of("agent", "agent"), webCallPaths());
        assertEquals(List.of(), records("web.fallback"));
        assertEquals(List.of(), cdp.calls);
    }

    @Test
    void aPathFailureOutsideTheAgentsCodesIsAnEnvironmentError() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.answers.put("web.dom.fill", args -> {
            throw new IllegalStateException("target closed");
        });
        RunResult result = run(null, STEPS);
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertEquals("s1", result.failedStepId());
        assertEquals("web.dom.fill failed on the cdp path: java.lang.IllegalStateException: target closed",
                result.message());
        Map<String, Object> call = records("call").stream().filter(r -> "web.dom.fill".equals(r.get("primitive")))
                .findFirst().orElseThrow();
        assertEquals(false, call.get("ok"));
        assertEquals("cdp", call.get("path"));
    }

    @Test
    void theAgentsErrorCodesFromThePathAreMappedAsTheAgentsOwn() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.answers.put("web.dom.click", args -> {
            throw new AgentRpcException("web.dom.click", new RpcError(ErrorCodes.NOT_FOUND,
                    "no element with role button and name Borrow", Map.of("role", "button", "name", "Borrow")));
        });
        RunResult result = run(null, STEPS);
        assertEquals(Status.FAIL, result.status());
        assertEquals(FailureKind.ASSERTION, result.failureKind());
        assertEquals("web.dom.click failed: NOT_FOUND: no element with role button and name Borrow",
                result.message());
        assertEquals(Map.of("role", "button", "name", "Borrow"), result.detail().get("data"));
    }

    @Test
    void cleanupTakesTheRunsPathTooAndRecordsIt() throws Exception {
        webCdp(CapabilityState.OK, null);
        RunResult result = run(null, "  - webTap: { patterns: [webhost.*] }\n  - expect: { \"state.name\": BUSY }\n");
        assertEquals(Status.FAIL, result.status(), "the toy state is not BUSY");
        assertEquals(List.of(List.of("webhost.*"), List.of()), cdp.callsTo("web.tap").stream()
                .map(c -> c.args().get("patterns")).toList(), "the tap, then its removal");
        Map<String, Object> cleanup = records("cleanup").get(0);
        assertEquals("web.tap", cleanup.get("primitive"));
        assertEquals("cdp", cleanup.get("path"));
        assertEquals(true, cleanup.get("ok"));
        assertEquals(List.of(), agent.callsTo("web.tap"));
    }

    @Test
    void runWithOutcomeCarriesTheWebSummary() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.answers.put("web.dom.click", args -> {
            throw WebPathException.unsupported("no target");
        });
        PlanExecutor.Resolved ran = new PlanExecutor(pack, agent, options).runWithOutcome(compile(null, STEPS));
        assertEquals(Status.PASS, ran.result().status());
        Map<String, Object> web = ran.web();
        assertEquals("cdp", web.get("path"));
        assertEquals(cdp.openInfo, web.get("cdp"));
        assertEquals(WebLib.hash(), web.get("libHash"));
        assertEquals("auto", web.get("policy"));
        assertEquals(List.of(Map.of("step", "s1", "index", 1L, "primitive", "web.dom.fill", "path", "cdp", "ok", true),
                Map.of("step", "s2", "index", 1L, "primitive", "web.dom.click", "path", "agent", "ok", true)),
                web.get("calls"));
        assertEquals(List.of(Map.of("step", "s2", "primitive", "web.dom.click", "reason", "no target", "from",
                "cdp")), web.get("fallbacks"));
    }

    // ------------------------------------------------------------------------------------------------ waits

    @Test
    void anEventOfThePathEndsTheWaitAndIsCaptured() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.publish("web.tap", Map.of("browser", "h7", "name", "webhost.borrowed", "detail", Map.of("qty", 1L)));
        cdp.answers.put("web.dom.click", args -> {
            cdp.publish("web.tap", Map.of("browser", "h7", "name", "webhost.ready"));
            cdp.publish("web.tap", Map.of("browser", "h7", "name", "webhost.borrowed", "detail", Map.of("item",
                    "hammer", "qty", 2L)));
            return Map.of("matched", 1L, "fidelity", "DOM");
        });
        RunResult result = run(null, """
                  - webClick:
                      role: button
                      name: Borrow
                      await:
                        topic: web.tap
                        match: { name: webhost.borrowed }
                        capture: { slot: borrowed, fields: { qty: detail.qty } }
                  - webFill: { selector: "#echo", text: "qty $borrowed.qty" }
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        Map<String, Object> sync = records("sync").stream().filter(r -> "web.dom.click".equals(r.get("primitive")))
                .findFirst().orElseThrow();
        assertEquals("cdp", sync.get("source"), "the path's event ended the wait");
        assertEquals(10_003L, sync.get("eventSeq"), "in the path's own sequence: not the old event 10001");
        assertEquals(10_001L, sync.get("pathSince"));
        assertEquals("web.tap", sync.get("topic"));
        assertEquals("qty 2", cdp.callsTo("web.dom.fill").get(0).args().get("text"), "captured from the path's event");
        assertTrue(agent.awaits.stream().allMatch(a -> a.since() < 10_000L), "the agent's bus is waited on from"
                + " the agent's own numbers, never the path's: " + agent.awaits);
        assertTrue(records("call").stream().filter(r -> "web.dom.click".equals(r.get("primitive")))
                .allMatch(r -> "cdp".equals(r.get("path"))));
    }

    @Test
    void anEventOnTheAgentsBusEndsTheWaitOfACallThePathServed() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.answers.put("web.dom.click", args -> {
            agent.publish("web.ready", Map.of("browser", "h7", "signal", "LOAD", "url", "data:x"));
            return Map.of("matched", 1L, "fidelity", "DOM");
        });
        RunResult result = run(null, "  - webClick: { role: button, name: Borrow, await: { topic: web.ready, match:"
                + " { signal: LOAD } } }\n");
        assertEquals(Status.PASS, result.status(), result.message());
        Map<String, Object> sync = records("sync").stream().filter(r -> "web.dom.click".equals(r.get("primitive")))
                .findFirst().orElseThrow();
        assertEquals("agent", sync.get("source"));
        assertEquals(agent.bus.stream().filter(e -> e.topic().equals("web.ready")).findFirst().orElseThrow().seq(),
                sync.get("eventSeq"));
    }

    @Test
    void anOldEventOfThePathDoesNotEndAWait() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.publish("web.tap", Map.of("browser", "h7", "name", "webhost.borrowed"));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true));
        RunResult result = run(null, "  - webClick: { role: button, name: Borrow, await: { topic: web.tap, match:"
                + " { name: webhost.borrowed }, within: 1s } }\n");
        assertEquals(Status.FAIL, result.status());
        assertEquals(FailureKind.SYNC_TIMEOUT, result.failureKind());
        assertEquals("no event (web.tap) matching {\"name\":\"webhost.borrowed\"} within 1000 ms after web.dom.click",
                result.message());
    }

    @Test
    void aWaitForOnAWebTopicReadsThePathsEventsToo() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.answers.put("web.dom.click", args -> {
            cdp.publish("web.ready", Map.of("browser", "h7", "signal", "TAP", "name", "webhost.borrowed"));
            return Map.of("matched", 1L, "fidelity", "DOM");
        });
        RunResult result = run(null, """
                  - webClick: { role: button, name: Borrow }
                  - waitFor:
                      topic: web.ready
                      match: { signal: TAP }
                      capture: { slot: ready, fields: { name: name } }
                  - webFill: { selector: "#echo", text: $ready.name }
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        Map<String, Object> wait = records("call").stream().filter(r -> "sync.await".equals(r.get("primitive")))
                .findFirst().orElseThrow();
        assertEquals("cdp", wait.get("eventSource"));
        assertEquals("controller", wait.get("thread"));
        assertEquals(List.of(), agent.callsTo("sync.await"), "the executor waited itself");
        assertEquals("webhost.borrowed", cdp.callsTo("web.dom.fill").get(0).args().get("text"));
    }

    @Test
    void aWaitForOnAWebTopicThatNothingEndsIsTheAgentsTimeout() throws Exception {
        webCdp(CapabilityState.OK, null);
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true));
        RunResult result = run(null, "  - webClick: { role: button, name: Borrow }\n"
                + "  - waitFor: { topic: web.ready, within: 500ms }\n");
        assertEquals(Status.FAIL, result.status());
        assertEquals(FailureKind.SYNC_TIMEOUT, result.failureKind());
        assertEquals("s2", result.failedStepId());
        assertEquals("sync.await failed: TIMEOUT: no matching event within 500 ms", result.message());
    }

    @Test
    void onTheAgentPathAWebWaitIsTheAgentsOneWait() throws Exception {
        agent.on("web.dom.click", params -> {
            agent.publish("web.tap", Map.of("browser", "h7", "name", "webhost.borrowed"));
            return Map.of("matched", 1L, "fidelity", "DOM");
        });
        RunResult result = run("{ path: agent }", "  - webClick: { role: button, name: Borrow, await: { topic:"
                + " web.tap } }\n");
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(1, agent.awaits.size(), "no slices");
        assertEquals(Duration.ofSeconds(10), agent.awaits.get(0).timeout(), "the await's own timeout, as before");
        assertFalse(records("sync").get(0).containsKey("source"));
    }

    // ------------------------------------------------------------------------------------------------ end of run

    @Test
    void thePathIsClosedHoweverTheRunEnds() throws Exception {
        webCdp(CapabilityState.OK, null);
        assertEquals(Status.PASS, run(null, STEPS).status());
        assertEquals(1, cdp.opened().closes);

        cdp.reset();
        cdp.answers.put("web.dom.fill", args -> Map.of("matched", 1L));
        assertEquals(Status.FAIL, run(null, "  - webFill: { selector: \"#a\", text: x }\n"
                + "  - expect: { \"state.name\": BUSY }\n").status());
        assertEquals(1, cdp.opened().closes, "after a failure");

        cdp.reset();
        cdp.answers.put("web.dom.fill", args -> Map.of("matched", 1L));
        agent.loseOn("swing.settle");
        assertEquals(FailureKind.AGENT_LOST, run(null, "  - webFill: { selector: \"#a\", text: x }\n")
                .failureKind());
        assertEquals(1, cdp.opened().closes, "after the agent was lost");
    }

    @Test
    void aFailingCloseIsRecordedNotThrown() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.closeFailure = new IllegalStateException("driver gone");
        assertEquals(Status.PASS, run(null, STEPS).status());
        assertEquals(List.of(Map.of("path", "cdp", "ok", false, "error", "java.lang.IllegalStateException: driver"
                + " gone")), records("web.close"));
    }

    @Test
    void theTraceIsKeptOnFailureWhenTheScenarioAsksForIt() throws Exception {
        webCdp(CapabilityState.OK, null);
        String failing = "  - webFill: { selector: \"#a\", text: x }\n  - expect: { \"state.name\": BUSY }\n";
        Path trace = dir.resolve("out/failure/web-trace.zip");
        assertEquals(Status.FAIL, run("{ trace: true }", failing).status());
        assertTrue(cdp.opened().openedWith.trace(), "no call of the plan carries a secret: the path traces");
        assertTrue(Files.isRegularFile(trace));
        assertEquals(trace, cdp.opened().traceSavedTo);
        assertEquals(List.of(Map.of("ok", true, "file", "failure/web-trace.zip")), records("web.trace"));

        cdp.reset();
        cdp.answers.put("web.dom.fill", args -> Map.of("matched", 1L));
        assertEquals(Status.PASS, run("{ trace: true }", "  - webFill: { selector: \"#a\", text: x }\n").status());
        assertFalse(Files.exists(trace), "none on success, and the old one is gone");
        assertNull(cdp.opened().traceSavedTo);

        cdp.reset();
        cdp.answers.put("web.dom.fill", args -> Map.of("matched", 1L));
        assertEquals(Status.FAIL, run(null, failing).status());
        assertNull(cdp.opened().traceSavedTo, "web.trace is off by default");
        assertEquals(List.of(), records("web.trace"));
    }

    /** A scenario with the toy credential {@code staff} (code 100), {@code web} as given, then the steps. */
    private Plan withStaff(String web, String steps) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\nweb: " + web + "\nresolve:\n"
                + "  staff: { fixture: { code: \"100\" }, credential: { realm: staff, key: code } }\nsteps:\n"
                + steps).getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
    }

    /** The settings of {@code web: { trace: true }} with {@code trace} as given: what the path may be opened with. */
    private static WebSettings openedWith(boolean trace) {
        return new WebSettings(WebSettings.Path.AUTO, null, FrozenTargetPolicy.FAIL, Duration.ofSeconds(30), trace);
    }

    @Test
    void noTraceIsKeptWhenACallCarriedASecret() throws Exception {
        webCdp(CapabilityState.OK, null);
        options = options.withBind(TestCredentials.bind());
        Plan plan = withStaff("{ trace: true }", "  - webFill: { selector: \"#password\", text: $staff.password }\n"
                + "  - expect: { \"state.name\": BUSY }\n");
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.FAIL, result.status());
        assertEquals(openedWith(false), cdp.opened().openedWith, "P4 6.4: decided from the plan before the path opened,"
                + " so that the path never records the secret; the rest of the settings as the scenario says");
        assertNull(cdp.opened().traceSavedTo);
        assertEquals(List.of(Map.of("skipped", true, "reason", "secrets")), records("web.trace"), "once, at open");
        List<String> types = types();
        assertEquals(types.indexOf("web.path") + 1, types.indexOf("web.trace"), "right after web.path: " + types);
        FakeCdpPath.Call fill = cdp.callsTo("web.dom.fill").get(0);
        assertEquals(List.of("text"), fill.options().secretParams(), "A34 on the cdp path too");
        assertEquals(TestCredentials.PW_100, fill.args().get("text"), "the primitive receives the value");
        Map<String, Object> call = records("call").stream().filter(r -> "web.dom.fill".equals(r.get("primitive")))
                .findFirst().orElseThrow();
        assertEquals(Map.of("selector", "#password", "text", "<redacted>"), call.get("params"));
        assertEquals(List.of("text"), call.get("secretParams"));
        assertFalse(Files.readString(dir.resolve("out/run.jsonl")).contains(TestCredentials.PW_100));
    }

    @Test
    void aPassingRunThatMayCarryASecretOpensThePathWithoutTracingAndSaysSo() throws Exception {
        webCdp(CapabilityState.OK, null);
        options = options.withBind(TestCredentials.bind());
        RunResult result = new PlanExecutor(pack, agent, options).run(withStaff("{ trace: true }",
                "  - webFill: { selector: \"#password\", text: \"pw: $staff.password\" }\n"));
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(openedWith(false), cdp.opened().openedWith, "a secret embedded in a longer text counts too");
        assertNull(cdp.opened().traceSavedTo);
        assertEquals(List.of(Map.of("skipped", true, "reason", "secrets")), records("web.trace"));
    }

    @Test
    void aSecretInACleanupCallAloneWithholdsTheTrace() throws Exception {
        webCdp(CapabilityState.OK, null);
        options = options.withBind(TestCredentials.bind());
        agent.value("toy.unlock", Map.of());
        pack.onCustom = ctx -> ctx.addCleanup(new PlannedCall("toy.unlock", Map.of("which", "$staff.password"),
                Sync.NONE, 0));
        RunResult result = new PlanExecutor(pack, agent, options).run(withStaff("{ trace: true }",
                "  - custom: {}\n  - webClick: { role: button, name: Borrow }\n"));
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of("which"), agent.callsTo("toy.unlock").get(0).options().secretParams(),
                "the cleanup call does carry the secret");
        assertFalse(cdp.opened().openedWith.trace(), "so the path was opened without tracing");
        assertEquals(List.of(Map.of("skipped", true, "reason", "secrets")), records("web.trace"));
    }

    @Test
    void aFieldOfTheCredentialsFixtureIsNoSecretAndThePathTraces() throws Exception {
        webCdp(CapabilityState.OK, null);
        options = options.withBind(TestCredentials.bind());
        cdp.answers.put("web.dom.fill", args -> Map.of("matched", 1L));
        RunResult result = new PlanExecutor(pack, agent, options).run(withStaff("{ trace: true }",
                "  - webFill: { selector: \"#user\", text: $staff.code }\n  - expect: { \"state.name\": BUSY }\n"));
        assertEquals(Status.FAIL, result.status());
        assertEquals(openedWith(true), cdp.opened().openedWith, "the key field of the credential slot is not secret");
        assertEquals(List.of(Map.of("ok", true, "file", "failure/web-trace.zip")), records("web.trace"));
    }

    @Test
    void theCallersBindingsDecideTheForecast() throws Exception {
        webCdp(CapabilityState.OK, null);
        Bindings staff = Bindings.of(Map.of("staff", Map.of("code", "100"))).withCredential("staff",
                Map.of("password", TestCredentials.PW_100));
        RunResult secret = new PlanExecutor(pack, agent, options).run(withStaff("{ trace: true }",
                "  - webFill: { selector: \"#password\", text: $staff.password }\n"), staff);
        assertEquals(Status.PASS, secret.status(), secret.message());
        assertFalse(cdp.opened().openedWith.trace(), "the caller joined the password");
        assertEquals(List.of(Map.of("skipped", true, "reason", "secrets")), records("web.trace"));

        cdp.reset();
        cdp.answers.put("web.dom.fill", args -> Map.of("matched", 1L));
        RunResult plain = new PlanExecutor(pack, agent, options).run(withStaff("{ trace: true }",
                "  - webFill: { selector: \"#user\", text: $staff.code }\n"), staff);
        assertEquals(Status.PASS, plain.status(), plain.message());
        assertTrue(cdp.opened().openedWith.trace(), "no joined field is referenced");
        assertEquals(List.of(), records("web.trace"), "a passing run keeps no trace and says nothing of one");
    }

    @Test
    void theFailureTimeGuardStillKeepsNoTraceWhenTheForecastMissedASecret() throws Exception {
        webCdp(CapabilityState.OK, null);
        options = options.withBind(TestCredentials.bind());
        Plan plan = withStaff("{ trace: true }", "  - webFill: { selector: \"#password\", text: $staff.password }\n"
                + "  - expect: { \"state.name\": BUSY }\n");
        AtomicInteger asked = new AtomicInteger();
        RunResult result = new PlanExecutor(pack, agent, options, null, (p, provided) -> {
            asked.incrementAndGet();
            return false;
        }).run(plan);
        assertEquals(Status.FAIL, result.status());
        assertEquals(1, asked.get());
        assertTrue(cdp.opened().openedWith.trace(), "a forecast that misses the secret: the path traces");
        assertEquals(List.of("text"), cdp.callsTo("web.dom.fill").get(0).options().secretParams());
        assertNull(cdp.opened().traceSavedTo, "the call announced a secret: the trace is not kept (A34)");
        assertEquals(List.of(Map.of("skipped", true, "reason", "secrets")), records("web.trace"));
        List<String> types = types();
        assertTrue(types.indexOf("web.trace") > types.indexOf("evidence"), "written at failure time: " + types);
    }

    @Test
    void aForecastThatFailsWithholdsTheTraceAndTheRecordSaysWhy() throws Exception {
        webCdp(CapabilityState.OK, null);
        RunResult result = new PlanExecutor(pack, agent, options, null, (p, provided) -> {
            throw new IllegalStateException("forecast defect");
        }).run(compile("{ trace: true }", STEPS));
        assertEquals(Status.PASS, result.status(), result.message());
        assertFalse(cdp.opened().openedWith.trace());
        assertEquals(List.of(Map.of("skipped", true, "reason", "secrets", "error",
                "java.lang.IllegalStateException: forecast defect")), records("web.trace"));
    }

    @Test
    void theForecastIsAskedOnlyWhenTheScenarioAsksForATraceAndThePlanHasWebCalls() throws Exception {
        webCdp(CapabilityState.OK, null);
        AtomicInteger asked = new AtomicInteger();
        PlanExecutor executor = new PlanExecutor(pack, agent, options, null, (p, provided) -> {
            asked.incrementAndGet();
            return true;
        });
        assertEquals(Status.PASS, executor.run(compile(null, STEPS)).status());
        assertEquals(0, asked.get(), "web.trace is off by default");
        assertFalse(cdp.opened().openedWith.trace());
        assertEquals(List.of(), records("web.trace"));

        assertEquals(Status.PASS, executor.run(compile("{ trace: true }", "  - expect: { \"state.name\": IDLE }\n"))
                .status());
        assertEquals(0, asked.get(), "no web call, no path, no trace");
        assertEquals(List.of(), records("web.trace"));
    }

    @Test
    void onTheAgentPathThereIsNoTraceToWithholdAndNoRecord() throws Exception {
        webCdp(CapabilityState.OK, null);
        options = options.withBind(TestCredentials.bind());
        RunResult pinned = new PlanExecutor(pack, agent, options).run(withStaff("{ path: agent, trace: true }",
                "  - webFill: { selector: \"#password\", text: $staff.password }\n"
                        + "  - expect: { \"state.name\": BUSY }\n"));
        assertEquals(Status.FAIL, pinned.status());
        assertEquals("agent", records("web.path").get(0).get("path"));
        assertNull(cdp.opened(), "no cdp path was opened");
        assertEquals(List.of(), records("web.trace"));

        webCdp(CapabilityState.UNAVAILABLE, "no remote-debugging endpoint configured");
        RunResult auto = new PlanExecutor(pack, agent, options).run(withStaff("{ trace: true }",
                "  - webFill: { selector: \"#password\", text: $staff.password }\n"));
        assertEquals(Status.PASS, auto.status(), auto.message());
        assertEquals("agent", records("web.path").get(0).get("path"));
        assertEquals(List.of(), records("web.trace"), "auto took the agent path: nothing was withheld");
    }

    @Test
    void aTraceIsRecordedAsSavedOnlyWhenThePathWroteOne() throws Exception {
        webCdp(CapabilityState.OK, null);
        String failing = "  - webFill: { selector: \"#a\", text: x }\n  - expect: { \"state.name\": BUSY }\n";
        Path trace = dir.resolve("out/failure/web-trace.zip");
        Map<String, Object> none = Map.of("ok", false, "reason", "the cdp path wrote no trace");

        cdp.trace = null;
        assertEquals(Status.FAIL, run("{ trace: true }", failing).status());
        assertEquals(trace, cdp.opened().traceSavedTo, "asked");
        assertFalse(Files.exists(trace));
        assertEquals(List.of(none), records("web.trace"), "N2: tracing never started, say: no file, no ok");

        cdp.reset();
        cdp.answers.put("web.dom.fill", args -> Map.of("matched", 1L));
        cdp.trace = new byte[0];
        assertEquals(Status.FAIL, run("{ trace: true }", failing).status());
        assertFalse(Files.exists(trace), "an empty file is no trace and is removed");
        assertEquals(List.of(none), records("web.trace"));

        cdp.reset();
        cdp.trace = null;
        cdp.answers.put("web.dom.fill", args -> {
            Files.createDirectories(trace.getParent());
            Files.writeString(trace, "a file the path did not write");
            return Map.of("matched", 1L);
        });
        assertEquals(Status.FAIL, run("{ trace: true }", failing).status());
        assertFalse(Files.exists(trace), "a file already there is removed before the path is asked");
        assertEquals(List.of(none), records("web.trace"));
    }

    @Test
    void aTraceThatCannotBeSavedIsRecordedWithItsError() throws Exception {
        webCdp(CapabilityState.OK, null);
        cdp.traceFailure = new IOException("disk full");
        assertEquals(Status.FAIL, run("{ trace: true }", "  - webFill: { selector: \"#a\", text: x }\n"
                + "  - expect: { \"state.name\": BUSY }\n").status());
        assertEquals(List.of(Map.of("ok", false, "error", "java.io.IOException: disk full")), records("web.trace"));
    }
}
