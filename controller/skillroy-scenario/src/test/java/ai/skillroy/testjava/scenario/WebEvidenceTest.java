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
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.web.WebPathException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase4-contracts.md 6.2, 6.4, 8.1 (12.1 item 4): the base fact root {@code web} (built from {@code web.browsers}
 * and {@code web.dom.text}, bounded, a failing text read omits the field, a pack root {@code web} wins, read through
 * the run's path), the {@code WEB} surface in the failure text, the web evidence files on failure and none on
 * success, and the status mapping of every error code a web verb can meet.
 */
class WebEvidenceTest {

    @TempDir
    Path dir;

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection().withWeb();
    private final TestClock clock = new TestClock();
    private RunOptions options;
    private final FakeCdpPath.Script cdp = FakeCdpPath.SCRIPT;

    @BeforeEach
    void setUp() {
        cdp.reset();
        agent.value("toy.state", Map.of("name", "IDLE"));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true));
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
        agent.value("app.state", Map.of("toy", Map.of("name", "IDLE")));
        agent.value("swing.snapshot", Map.of("seq", 1L, "truncated", false, "windows", List.of(Map.of("ref", "h1",
                "role", "window", "name", "Web host", "children", List.of(Map.of("ref", "h2", "role", "unknown",
                        "name", "browser"))))));
        options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withOutputDir(dir.resolve("out")).withStepTimeout(Duration.ofMillis(500));
    }

    private Plan compile(String steps) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n" + steps).getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
    }

    private RunResult run(String steps) throws ScenarioException {
        return new PlanExecutor(pack, agent, options).run(compile(steps));
    }

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

    private List<String> failureFiles() throws IOException {
        Path failure = dir.resolve("out/failure");
        if (!Files.isDirectory(failure)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(failure)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    private void browsers(Map<?, ?>... entries) {
        agent.value("web.browsers", Map.of("browsers", List.of(entries)));
    }

    // ------------------------------------------------------------------------------------------------ the root web

    @Test
    void theRootWebHoldsTheBrowsersAndTheirText() throws Exception {
        RunResult result = run("""
                  - expect:
                      "web.browsers[tag=front].text": { contains: "page of h7" }
                      "web.browsers[tag=back].visibleInSwing": false
                  - expect: { "web.browsers[url~back$].ref": h9, "web.browsers[tag=front].frames[0].main": true }
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of(Map.of("browser", "h7", "maxChars", 4000L), Map.of("browser", "h9", "maxChars", 4000L)),
                agent.callsTo("web.dom.text").subList(0, 2).stream().map(FakeAgentConnection.Call::params).toList());
        assertEquals(List.of(), records("web.path"), "facts alone choose no path: the agent serves them");
    }

    @Test
    void theRootKeepsTheContractsMembersOnly() throws Exception {
        List<Object> seen = new ArrayList<>();
        FactProvider web = BaseFacts.web(BaseFacts.AGENT_READS, clock, (ref, reason) -> seen.add(ref + ": " + reason));
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) web.fetch(agent);
        @SuppressWarnings("unchecked")
        Map<String, Object> front = ((List<Map<String, Object>>) root.get("browsers")).get(0);
        assertEquals(List.of("ref", "adapter", "url", "title", "visibleInSwing", "component", "rendering", "viewport",
                "cdpTargetId", "tag", "frames", "text"), new ArrayList<>(front.keySet()),
                "nativeId, closed and capabilities are left out");
        assertEquals("page of h7", front.get("text"));
        assertEquals(List.of(), seen);
        assertEquals("web", web.root());
        assertTrue(BaseFacts.ROOTS.equals(List.of("app", "ui", "dialogs", "caps", "devices", "web")));
        assertEquals("web", BaseFacts.all().get(5).root());
    }

    @Test
    void aBrowserWithoutEvaluateHasNoTextAndAFailingTextReadLeavesItOutOnce() throws Exception {
        Map<String, Object> plain = FakeAgentConnection.browser("h3", "plain", true);
        plain.put("capabilities", List.of());
        browsers(FakeAgentConnection.browser("h7", "front", true), FakeAgentConnection.browser("h9", "back", false),
                plain);
        agent.on("web.dom.text", params -> {
            if ("h9".equals(params.get("browser"))) {
                throw new AgentRpcException("web.dom.text", new RpcError(ErrorCodes.SCRIPT_ERROR, "innerText threw",
                        Map.of("message", "innerText threw")));
            }
            return Map.of("text", "page of " + params.get("browser"), "truncated", false);
        });
        RunResult result = run("""
                  - expect:
                      "web.browsers[tag=back].text": { exists: false }
                      "web.browsers[tag=front].text": "page of h7"
                  - expect:
                      "web.browsers[tag=plain].text": { exists: false }
                      "web.browsers[tag=back].title": "back page"
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of(Map.of("root", "web", "browser", "h9", "field", "text", "ok", false, "reason",
                "web.dom.text failed: SCRIPT_ERROR: innerText threw")), records("fact"), "logged once, not per read");
        assertTrue(agent.callsTo("web.dom.text").stream().noneMatch(c -> "h3".equals(c.params().get("browser"))),
                "no EVALUATE, no text read");
    }

    @Test
    void theRootIsBoundedByItsBudget() throws Exception {
        agent.on("web.dom.text", params -> {
            clock.advance(Duration.ofSeconds(6));
            return Map.of("text", "page of " + params.get("browser"), "truncated", false);
        });
        List<String> omitted = new ArrayList<>();
        FactProvider web = BaseFacts.web(BaseFacts.AGENT_READS, clock, (ref, reason) -> omitted.add(ref + ": "
                + reason));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> browsers = (List<Map<String, Object>>) ((Map<?, ?>) web.fetch(agent))
                .get("browsers");
        assertEquals("page of h7", browsers.get(0).get("text"));
        assertTrue(browsers.get(1).containsKey("text"), "6 s spent of 10: the second read still fits");
        assertEquals(10_000L - 6_000L, agent.callsTo("web.dom.text").get(1).options().timeoutMs(),
                "with what is left of the budget");
        agent.value("web.browsers", Map.of("browsers", List.of(FakeAgentConnection.browser("h7", "front", true),
                FakeAgentConnection.browser("h8", "middle", true), FakeAgentConnection.browser("h9", "back",
                        false))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> three = (List<Map<String, Object>>) ((Map<?, ?>) web.fetch(agent)).get("browsers");
        assertEquals(3, three.size(), "the root still resolves");
        assertFalse(three.get(2).containsKey("text"));
        assertEquals(List.of("h9: the root's 10000 ms budget was spent"), omitted);
        assertEquals(10_000L, agent.callsTo("web.browsers").get(0).options().timeoutMs(), "10 s, as the other roots");
    }

    @Test
    void aPackRootWebHidesTheBaseRoot() throws Exception {
        pack.facts = List.of(new FactProvider() {
            @Override
            public String root() {
                return "web";
            }

            @Override
            public Object fetch(AgentConnection connection) {
                return Map.of("mine", 1L);
            }
        });
        assertEquals(Status.PASS, run("  - expect: { \"web.mine\": 1 }\n").status());
        assertEquals(List.of(), agent.callsTo("web.browsers"));
    }

    @Test
    void theRootReadsThroughTheRunsPathWithTheFallBackRecordedOnce() throws Exception {
        agent.capability("web.cdp", CapabilityState.OK, null);
        cdp.answers.put("web.dom.click", args -> Map.of("matched", 1L, "fidelity", "DOM"));
        cdp.answers.put("web.dom.text", args -> {
            if ("h9".equals(args.get("browser"))) {
                throw WebPathException.unsupported("the hidden page has no target");
            }
            return Map.of("text", "cdp text of " + args.get("browser"), "truncated", false);
        });
        RunResult result = run("""
                  - webClick: { selector: "#borrow" }
                  - expect:
                      "web.browsers[tag=front].text": { contains: "cdp text of h7" }
                      "web.browsers[tag=back].text": "page of h9"
                  - expect: { "web.browsers[tag=back].text": "page of h9" }
                """);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of("h9", "h9"), agent.callsTo("web.dom.text").stream().map(c -> String.valueOf(
                c.params().get("browser"))).toList(), "only the refused reads went to the agent");
        assertEquals(List.of(Map.of("step", "s2", "primitive", "web.dom.text", "reason",
                "the hidden page has no target", "from", "cdp")), records("web.fallback"), "once, not per read");
        assertEquals(2, agent.callsTo("web.browsers").size(), "web.browsers is the agent's, once per read");
        assertTrue(cdp.callsTo("web.browsers").isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ evidence

    @Test
    void aFailureKeepsTheBrowsersTheirAriaSnapshotsAndBitmapsAndTheSurface() throws Exception {
        RunResult result = run("  - expect: { \"state.name\": BUSY }\n");
        assertEquals(Status.FAIL, result.status());
        assertEquals(List.of("app-state.json", "dialogs.json", "events.json", "snapshot.json", "web-browsers.json",
                "web-h7-aria.json", "web-h7.png"), failureFiles(), "the hidden browser has no image or snapshot");
        Map<String, Object> listed = Json.parseObject(Files.readString(dir.resolve("out/failure/web-browsers.json")));
        assertEquals(2, ((List<?>) listed.get("browsers")).size());
        Map<String, Object> aria = Json.parseObject(Files.readString(dir.resolve("out/failure/web-h7-aria.json")));
        assertTrue(String.valueOf(aria.get("nodes")).contains("Borrow"), aria.toString());
        assertEquals(List.of(Map.of("browser", "h7", "maxNodes", 200L, "interestingOnly", true),
                Map.of("browser", "h7", "maxNodes", 2000L)), agent.callsTo("web.ariaSnapshot").stream()
                .map(FakeAgentConnection.Call::params).toList(), "the surface's read, then the evidence's");
        assertTrue(java.util.Arrays.equals(Base64.getDecoder().decode(FakeAgentConnection.PNG_1X1),
                Files.readAllBytes(dir.resolve("out/failure/web-h7.png"))));
        String snapshot = String.valueOf(result.detail().get("snapshot"));
        assertTrue(snapshot.contains("""
                - window "Web host" [ref=h1]
                  - unknown "browser" [ref=h2]
                [web h7] front page data:text/html,front (jcef, WINDOWED, agent)
                  heading "Borrow"
                  textbox "Item" [hammer]
                  button "Borrow"
                [web h9 hidden] back page data:text/html,back
                """), snapshot);
    }

    @Test
    void atMostFourVisibleBrowsersInRefOrderAndAnErrorFileForAFailedRead() throws Exception {
        browsers(FakeAgentConnection.browser("h12", "e", true), FakeAgentConnection.browser("h3", "b", true),
                FakeAgentConnection.browser("h10", "d", true), FakeAgentConnection.browser("h2", "a", true),
                FakeAgentConnection.browser("h4", "c", true), FakeAgentConnection.browser("h1", "hidden", false));
        agent.on("web.bitmap", params -> {
            if ("h3".equals(params.get("browser"))) {
                throw new AgentRpcException("web.bitmap", new RpcError(ErrorCodes.CAPABILITY_UNAVAILABLE,
                        "no BITMAP for this browser", null));
            }
            return Map.of("format", "png", "width", 1L, "height", 1L, "base64", FakeAgentConnection.PNG_1X1);
        });
        run("  - expect: { \"state.name\": BUSY }\n");
        assertEquals(List.of("app-state.json", "dialogs.json", "events.json", "snapshot.json", "web-browsers.json",
                "web-h10-aria.json", "web-h10.png", "web-h2-aria.json", "web-h2.png", "web-h3-aria.json",
                "web-h3-error.txt", "web-h4-aria.json", "web-h4.png"), failureFiles(), "h2, h3, h4, h10: not h12");
        assertEquals("web.bitmap: CAPABILITY_UNAVAILABLE (-32011): no BITMAP for this browser\n",
                Files.readString(dir.resolve("out/failure/web-h3-error.txt")));
        assertEquals(List.of("h2", "h3", "h4", "h10"), agent.callsTo("web.bitmap").stream()
                .map(c -> String.valueOf(c.params().get("browser"))).toList());
    }

    @Test
    void noWebEvidenceOnSuccessWithoutBrowsersOrWithoutCapture() throws Exception {
        Files.createDirectories(dir.resolve("out/failure"));
        Files.writeString(dir.resolve("out/failure/web-h5.png"), "old");
        assertEquals(Status.PASS, run("  - expect: { \"state.name\": IDLE }\n").status());
        assertFalse(Files.exists(dir.resolve("out/failure")), "an old run's web files are cleared: " + failureFiles());
        assertEquals(List.of(), agent.callsTo("web.browsers"));

        agent.capability("web.browsers", CapabilityState.UNAVAILABLE, "no embedded browser technology found");
        run("  - expect: { \"state.name\": BUSY }\n");
        assertEquals(List.of("app-state.json", "dialogs.json", "events.json", "snapshot.json"), failureFiles());
        assertEquals(List.of(), agent.callsTo("web.browsers"), "a target without browsers is not asked");

        agent.capability("web.browsers", CapabilityState.OK, null);
        browsers();
        run("  - expect: { \"state.name\": BUSY }\n");
        assertEquals(List.of("app-state.json", "dialogs.json", "events.json", "snapshot.json"), failureFiles(),
                "no indexed browser, no web evidence");

        browsers(FakeAgentConnection.browser("h7", "front", true));
        options = options.withCaptureOnFailure(false);
        run("  - expect: { \"state.name\": BUSY }\n");
        assertEquals(List.of(), failureFiles(), "captureOnFailure governs every piece of evidence");
    }

    @Test
    void theEvidenceAndTheSurfaceReadThroughTheRunsPath() throws Exception {
        agent.capability("web.cdp", CapabilityState.OK, null);
        cdp.answers.put("web.dom.click", args -> Map.of("matched", 1L, "fidelity", "DOM"));
        cdp.answers.put("web.ariaSnapshot", args -> Map.of("nodes", List.of(Map.of("role", "link", "name", "Help",
                "children", List.of(Map.of("role", "text", "name", "via cdp")))), "truncated", false));
        RunResult result = run("  - webClick: { selector: \"#borrow\" }\n  - expect: { \"state.name\": BUSY }\n");
        assertEquals(Status.FAIL, result.status());
        String snapshot = String.valueOf(result.detail().get("snapshot"));
        assertTrue(snapshot.contains("[web h7] front page data:text/html,front (jcef, WINDOWED, cdp)\n  link \"Help\"\n"
                + "    text \"via cdp\"\n"), snapshot);
        assertTrue(Files.readString(dir.resolve("out/failure/web-h7-aria.json")).contains("via cdp"));
        assertEquals(List.of(), agent.callsTo("web.ariaSnapshot"));
        assertEquals(1, agent.callsTo("web.bitmap").size(), "the fake path does not answer web.bitmap: the agent");
        assertTrue(records("evidence").stream().anyMatch(r -> "web.ariaSnapshot".equals(r.get("kind"))
                && "cdp".equals(r.get("path"))));
    }

    // ------------------------------------------------------------------------------------------------ taxonomy

    @Test
    void everyErrorCodeOfAWebVerbMapsAs8_1Says() throws Exception {
        record Case(int code, Status status, FailureKind kind, String message) {
        }
        List<Case> cases = List.of(
                new Case(ErrorCodes.SCRIPT_ERROR, Status.FAIL, FailureKind.ASSERTION,
                        "web.dom.click failed: SCRIPT_ERROR: boom"),
                new Case(ErrorCodes.NOT_FOUND, Status.FAIL, FailureKind.ASSERTION, "web.dom.click failed: NOT_FOUND:"
                        + " boom"),
                new Case(ErrorCodes.AMBIGUOUS, Status.FAIL, FailureKind.ASSERTION, "web.dom.click failed: AMBIGUOUS:"
                        + " boom"),
                new Case(ErrorCodes.STALE_HANDLE, Status.FAIL, FailureKind.ASSERTION, "web.dom.click failed: the"
                        + " browser closed during the step (STALE_HANDLE: boom)"),
                new Case(ErrorCodes.CAPABILITY_UNAVAILABLE, Status.ERROR, FailureKind.ENVIRONMENT,
                        "web.dom.click failed: CAPABILITY_UNAVAILABLE: boom"),
                new Case(ErrorCodes.TIMEOUT, Status.FAIL, FailureKind.SYNC_TIMEOUT, "web.dom.click failed: TIMEOUT:"
                        + " boom"),
                new Case(ErrorCodes.INVALID_PARAMS, Status.ERROR, FailureKind.ENVIRONMENT, "web.dom.click failed:"
                        + " INVALID_PARAMS: boom"));
        for (Case c : cases) {
            agent.error("web.dom.click", c.code(), "boom");
            RunResult result = run("  - webClick: { role: button, name: Borrow }\n");
            assertEquals(c.status(), result.status(), ErrorCodes.name(c.code()));
            assertEquals(c.kind(), result.failureKind(), ErrorCodes.name(c.code()));
            assertEquals(c.message(), result.message(), ErrorCodes.name(c.code()));
            assertEquals("s1", result.failedStepId());
        }
        agent.error("swing.click", ErrorCodes.STALE_HANDLE, "gone");
        RunResult swing = run("  - click: { target: h4 }\n");
        assertEquals(FailureKind.ENVIRONMENT, swing.failureKind(), "a Swing STALE_HANDLE is unchanged");
    }

    @Test
    void theSameCodesFromTheCdpPathMapTheSame() throws Exception {
        agent.capability("web.cdp", CapabilityState.OK, null);
        cdp.answers.put("web.dom.fill", args -> {
            throw new AgentRpcException("web.dom.fill", new RpcError(ErrorCodes.SCRIPT_ERROR, "TypeError: x is null",
                    Map.of("message", "TypeError: x is null", "stack", "at fill")));
        });
        RunResult result = run("  - webFill: { selector: \"#item\", text: hammer }\n");
        assertEquals(Status.FAIL, result.status());
        assertEquals(FailureKind.ASSERTION, result.failureKind());
        assertEquals("web.dom.fill failed: SCRIPT_ERROR: TypeError: x is null", result.message());
        assertEquals(Map.of("message", "TypeError: x is null", "stack", "at fill"), result.detail().get("data"));
    }

    @Test
    void aFlagThatDoesNotTurnTrueAndAnUnsettledUiAreSyncTimeouts() throws Exception {
        agent.on("web.waitFlag", params -> Map.of("ready", false, "ms", 5000L, "polls", 100L));
        RunResult flag = run("  - webWaitFlag: { expression: \"window.ready === true\", timeoutMs: 5s }\n");
        assertEquals(Status.FAIL, flag.status());
        assertEquals(FailureKind.SYNC_TIMEOUT, flag.failureKind());
        assertEquals("webWaitFlag: the flag did not turn true within 5000 ms: window.ready === true", flag.message());
        assertEquals("window.ready === true", flag.detail().get("expression"));

        agent.on("web.waitFlag", params -> Map.of("ready", false, "ms", 5000L, "polls", 100L));
        RunResult raw = run("  - call: { primitive: web.waitFlag, args: { expression: \"x\" } }\n");
        assertEquals(Status.PASS, raw.status(), "a raw call is the scenario's own business");

        agent.on("web.dom.click", params -> {
            agent.publish("web.ready", Map.of("signal", "LOAD"));
            return Map.of("matched", 1L, "fidelity", "DOM");
        });
        agent.on("swing.settle", params -> Map.of("settled", false, "ms", 500L));
        RunResult settle = run("  - webClick: { selector: \"#a\", await: { topic: web.ready } }\n");
        assertEquals(FailureKind.SYNC_TIMEOUT, settle.failureKind());
        assertEquals("the UI did not settle within 10000 ms after webClick", settle.message());
    }

    @Test
    void aWebFillOfACredentialAnnouncesTheTextAsASecret() throws Exception {
        options = options.withBind(TestCredentials.bind());
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\nresolve:\n"
                + "  staff: { fixture: { code: \"100\" }, credential: { realm: staff, key: code } }\nsteps:\n"
                + "  - webFill: { selector: \"#user\", text: $staff.code }\n"
                + "  - webFill: { selector: \"#password\", text: $staff.password, enter: true }\n")
                .getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        assertEquals(Status.PASS, new PlanExecutor(pack, agent, options).run(plan).status());
        List<FakeAgentConnection.Call> fills = agent.callsTo("web.dom.fill");
        assertEquals(List.of(), fills.get(0).options().secretParams(), "the code is no secret");
        assertEquals(List.of("text"), fills.get(1).options().secretParams(), "A34, as for type");
        assertEquals(TestCredentials.PW_100, fills.get(1).params().get("text"));
        String log = Files.readString(dir.resolve("out/run.jsonl"));
        assertFalse(log.contains(TestCredentials.PW_100), "never written");
        assertTrue(log.contains("\"text\":\"<redacted>\""), log);
    }
}
