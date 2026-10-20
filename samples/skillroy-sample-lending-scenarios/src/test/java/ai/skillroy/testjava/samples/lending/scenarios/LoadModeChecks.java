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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.controller.Discovery;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.junit.ScenarioSource;
import ai.skillroy.testjava.junit.ScenarioTests;
import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.protocol.SecretKeys;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.JunitXmlReporter;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Every numbered item of contract section 10, the checks of the Phase 1.x amendments and the hardening wave, and the
 * integration owner's extra checks, against one sample lending desk process whose agent was loaded in
 * {@link #mode()}. The process is shared by the checks of that mode and they run in order; each check leaves the desk
 * signed off, as the scenarios themselves do, except the last, which ends the process.
 *
 * <p>The {@code @Order} values (phase6-contracts.md 8.1): check 1 is 10, the Phase 6 checks 1b and 1c are 20 and 30,
 * checks 2 to 19 are 40 to 210 (check n at (n + 2) * 10), so that the hooks leg's {@link HookChecks} take values
 * between them (65: after 1c and before 5; 165: after 14).
 *
 * <ol>
 *   <li>{@link #handshakeLoadModeAndInstrumentation()}: section 10 item 1;</li>
 *   <li>(1b) {@link #noJep451WarningButOnADynamicLoad()}: phase6-contracts.md 8.1, D135;</li>
 *   <li>(1c) {@link #theCapabilityMatrixIsTheGoldenOfTheMode()}: phase6-contracts.md 8.2, D136;</li>
 *   <li>{@link #theHandshakeIsHmacAndTheOldBearerHeaderIsRefused()}: hardening H2 against the real agent;</li>
 *   <li>{@link #theLendingModuleRedactsTheDesksPin()}: amendment A21;</li>
 *   <li>{@link #keypadKeysAreNamedByTheirActionId()}: item 2;</li>
 *   <li>{@link #committedScenariosPassWithoutSleeping()}: item 3, plus run.jsonl and JUnit XML, with the UI scenario
 *       clicking on the COMPONENT rung at full fidelity (A29);</li>
 *   <li>{@link #aWrongExpectationFailsAsAssertionWithSnapshotAndCapture()}: item 4;</li>
 *   <li>{@link #recoverSignsOffAfterAFailureAndTheNextScenarioPasses()}: item 5 (recover);</li>
 *   <li>{@link #aMissingCapabilityIsAPreconditionFailureAndAnUnavailableGuardDegrades()}: item 5 (precondition,
 *       guarded expectation);</li>
 *   <li>{@link #componentRungOnAWindowThatCannotTakeTheFocusReportsFocusFailed()}: item 6;</li>
 *   <li>{@link #aClickThatFellBackToTheActionRungDegradesTheRunAndIsListed()}: amendments A28/A29, fall-back;</li>
 *   <li>{@link #withStrictTheClickThatCannotTakeTheFocusFailsAsAssertion()}: amendment A29, strict;</li>
 *   <li>{@link #aModalDialogInTheWayOfAStepFailsItAsAssertion()}: amendment A30;</li>
 *   <li>{@link #theUiScenarioActivatesTheWindowAndClicksAtFullFidelityAfterAPrompt()}: A29 with window
 *       activation;</li>
 *   <li>{@link #thePhase7SeedAScenariosRunAsDesigned()}: phase7-contracts.md 13.2 item 7 (seed A's part: the number
 *       pad's sign-on, the visual check DEGRADED, the live-only plan SKIPPED without an agent call);</li>
 *   <li>{@link #theCapabilitiesRecordCarriesTheAgentsHashAndTheContributedIds()}: phase7-contracts.md 13.2 item 9
 *       (the run's side; the goldens stay item 1c's);</li>
 *   <li>{@link #junitGlueRunsTheCommittedScenarios()}: the JUnit dynamic-test glue (one dynamic test per committed
 *       scenario, the ledger check and a check of what the glue wrote);</li>
 *   <li>{@link #aSecondAttachReusesTheRunningAgent()}: re-attach, one agent;</li>
 *   <li>{@link #disableLeavesTheApplicationRunningAndRemovesTheEngineListener()}: item 7, plus the client latch
 *       after {@code agent.disable} and a captured request replayed against the next agent (H2);</li>
 *   <li>{@link #anAgentAttachedWhileAPromptIsOpenIsReadyAndThePackRecovers()}: a prompt left open does not block
 *       the next agent or the next scenario;</li>
 *   <li>{@link #thePasswordAppearsInNoRunOutput()}: amendment A25;</li>
 *   <li>{@link #theDiscoveryFilesOfAKilledTargetAreRemovedByDiscovery()}: amendment A23 (ends the target).</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class LoadModeChecks {

    static final Duration WAIT = Duration.ofSeconds(60);
    static final CallOptions CALL = CallOptions.defaults().withTimeoutMs(60_000L);
    static final String READY_TOPICS = "input.requested,prompt.waiting";
    static final Set<String> KEYPAD = Set.of("QTY", "CANCEL", "RETURN", "DUE", "DEPOSIT", "SIGN_OFF");
    /** The clicks of {@code basic-loan-ui}: DUE, DEPOSIT, SIGN_OFF. */
    static final int UI_CLICKS = 3;
    /** The password of the scenarios' assistant (seed A, staff 100): it must appear in no run output (A25). */
    static final String PASSWORD = "1111";

    LendingTarget target;
    String java;

    /** The load mode this class checks. */
    abstract LendingTarget.Mode mode();

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        target = LendingTarget.launch(mode());
        Map<String, Object> jvm = value(client().call("jvm.info", Map.of(), CALL));
        java = String.valueOf(jvm.get("javaVersion"));
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " agentAnsweredMs=" + target.agentMs
                + " targetReadyMs=" + target.readyMs);
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " " + perCall());
    }

    /**
     * For the record (not asserted): the round trip of one authenticated call on the kept-alive connection, after a
     * warm-up, as the median and mean of 40 {@code agent.info} calls and of 20 {@code /ping}s (hardening H1 removed
     * the delayed-ACK stall of the JDK server).
     */
    private String perCall() throws Exception {
        for (int i = 0; i < 10; i++) {
            client().call("agent.info", Map.of(), CALL);
        }
        long[] calls = new long[40];
        for (int i = 0; i < calls.length; i++) {
            long t0 = System.nanoTime();
            client().call("agent.info", Map.of(), CALL);
            calls[i] = System.nanoTime() - t0;
        }
        long[] pings = new long[20];
        for (int i = 0; i < pings.length; i++) {
            long t0 = System.nanoTime();
            client().ping();
            pings[i] = System.nanoTime() - t0;
        }
        return "perCallMs(median/mean of " + calls.length + " agent.info)=" + stats(calls)
                + " perPingMs(median/mean of " + pings.length + ")=" + stats(pings);
    }

    private static String stats(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (long n : sorted) {
            sum += n;
        }
        return String.format(Locale.ROOT, "%.2f/%.2f", sorted[sorted.length / 2] / 1e6,
                sum / sorted.length / 1e6);
    }

    @AfterAll
    void close() {
        if (target != null) {
            target.close();
        }
    }

    AgentClient client() {
        return target.client;
    }

    // ------------------------------------------------------------------ section 10, item 1

    @Test
    @Order(10)
    void handshakeLoadModeAndInstrumentation() throws Exception {
        Map<String, Object> ping = client().ping();
        assertEquals(Boolean.TRUE, ping.get("ready"));
        assertEquals(target.endpoint.nonce(), ping.get("agentNonce"));
        Map<String, Object> info = value(client().call("agent.info", Map.of(), CALL));
        assertEquals(mode().loadMode.name(), info.get("loadMode"));
        assertEquals(target.pid(), ((Number) info.get("pid")).longValue());
        assertEquals(mode().loadMode != LoadMode.EMBEDDED, info.get("instrumentation"));
        Map<String, Object> modules = new LinkedHashMap<>();
        for (Object m : (List<?>) info.get("modules")) {
            Map<?, ?> module = (Map<?, ?>) m;
            modules.put(String.valueOf(module.get("id")), module.get("state"));
        }
        assertEquals(expectedModules(), modules);

        CapabilityMatrix caps = client().capabilities();
        if (mode().loadMode == LoadMode.EMBEDDED) {
            assertEquals(CapabilityState.UNAVAILABLE, caps.get("jvm.instrumentation").state());
        } else {
            assertTrue(caps.isOk("jvm.instrumentation"), String.valueOf(caps.get("jvm.instrumentation")));
        }
        for (String id : List.of("target.ready", "lending.input", "lending.state", "lending.loan", "lending.prompt",
                "lending.prompt.answer", "lending.slip", "lending.access.check", "lending.sql",
                "lending.loan.authorisations", "swing.tree", "swing.click.action", "swing.click.component",
                "swing.capture.paint", "data.query")) {
            assertTrue(caps.isOk(id), id + ": " + caps.get(id));
        }
        assertEquals(CapabilityState.UNAVAILABLE, caps.get("swing.click.robot").state());
        // phase6-contracts.md 2.5: the optional hooks module loads only in the hooks leg; elsewhere nothing of it.
        if (hooksLeg()) {
            assertTrue(caps.isOk("hook.engine"), String.valueOf(caps.get("hook.engine")));
        } else {
            assertNull(caps.get("hook.engine"), "no hooks module without optionalModules");
        }

        if (mode() == LendingTarget.Mode.JAVA_TOOL_OPTIONS) {
            String toolOptions = target.launched.environment().get("JAVA_TOOL_OPTIONS");
            assertNotNull(toolOptions);
            assertTrue(toolOptions.contains("-javaagent:") && toolOptions.contains("mainClassGuard="), toolOptions);
            assertTrue(target.launched.command().stream().noneMatch(a -> a.startsWith("-javaagent:")),
                    "the agent came through JAVA_TOOL_OPTIONS only: " + target.launched.command());
        }
        // Asked of the target itself: it runs on the Java this run was told to use, and in the modes that load the
        // agent after start-up (ATTACH, EMBEDDED) its JVM was given no -javaagent by any route.
        Map<String, Object> jvm = value(client().call("jvm.info", Map.of(), CALL));
        assertEquals(target.pid(), ((Number) jvm.get("pid")).longValue());
        assertEquals(java, jvm.get("javaVersion"));
        assertEquals(LendingTarget.MAIN_CLASS, jvm.get("mainCommand"));
        Map<String, Object> properties = value(client().call("jvm.properties", Map.of("names",
                List.of("java.home", "lending.seed")), CALL));
        Map<?, ?> values = (Map<?, ?>) properties.get("properties");
        Path targetHome = Path.of(String.valueOf(values.get("java.home"))).toRealPath();
        Path expectedHome = ItEnv.targetJava().toRealPath().getParent().getParent();
        assertTrue(targetHome.startsWith(expectedHome), "the target runs on " + targetHome + ", expected the Java of "
                + ItEnv.targetJava() + " (" + expectedHome + ")");
        assertEquals("A", values.get("lending.seed"));
        List<String> jvmArguments = ((List<?>) jvm.get("inputArguments")).stream().map(String::valueOf).toList();
        if (mode() == LendingTarget.Mode.ATTACH || mode() == LendingTarget.Mode.EMBEDDED) {
            assertTrue(target.launched.command().stream().noneMatch(a -> a.startsWith("-javaagent:")));
            assertNull(target.launched.environment().get("JAVA_TOOL_OPTIONS"));
            assertTrue(jvmArguments.stream().noneMatch(a -> a.startsWith("-javaagent")),
                    "no agent at launch: " + jvmArguments);
        } else {
            assertTrue(jvmArguments.stream().anyMatch(a -> a.startsWith("-javaagent:")
                    && a.contains("skillroy-agent-bootstrap.jar")), "the agent was loaded at launch: " + jvmArguments);
        }
        if (mode() == LendingTarget.Mode.ATTACH) {
            // The agent got in through the attach API, and the session read the reply the way the target's Java
            // needs: a Java 8 target makes an attach client of Java 21 or later report its successful load as the
            // error "0"; any newer target answers with a plain success.
            ItEnv.timing("java=" + java + " mode=" + mode().slug + " attachReply=" + target.attachReply);
            assertEquals(LoadMode.ATTACH.name(), info.get("loadMode"));
            if (!java.startsWith("1.")) {
                assertEquals("none", target.attachReply, "a Java " + java + " target reports a plain success");
            } else if (Runtime.version().feature() >= 21) {
                assertTrue(target.attachReply.matches(".*\\(Java 1\\.8\\) reported the load as \"(.*: )?0\".*"),
                        "the Java 8 success reply was seen and accepted: " + target.attachReply);
            }
        }
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " loadMode=" + info.get("loadMode")
                + " agentVersion=" + info.get("agentVersion"));
        // For the record (not asserted): what the agent itself warned about while starting in this mode.
        Map<String, Object> agentLog = value(client().call("agent.log", Map.of("n", 2000L), CALL));
        List<String> warnings = ((List<?>) agentLog.get("lines")).stream().map(String::valueOf)
                .filter(l -> l.contains("WARN") || l.contains("ERROR")).toList();
        Files.createDirectories(ItEnv.log("x").getParent());
        Files.write(ItEnv.log(mode().slug + "-agent-warnings.txt"), warnings, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ Phase 6: JEP 451 and the golden matrix

    /**
     * Check 1b (phase6-contracts.md 8.1, decision D135): the target's own merged log ({@code it-logs/<mode>.log}, a
     * redirect, so the lines are in the file once the agent answered) holds no JEP 451 warning in the modes that load
     * the agent at launch or embed it, on Java 8 and Java 25 alike; in {@code ATTACH} on a target of Java 21 or newer
     * the warning <b>must</b> be there (the desk is launched without {@code -XX:+EnableDynamicAgentLoading}): the
     * positive control that the detector works. It matches the texts, never "the log is empty" (the
     * {@code JAVA_TOOL_OPTIONS} leg's log legitimately says {@code Picked up JAVA_TOOL_OPTIONS}).
     */
    @Test
    @Order(20)
    void noJep451WarningButOnADynamicLoad() throws Exception {
        List<String> warnings = jep451Lines(Files.readAllLines(target.launched.logFile(), StandardCharsets.UTF_8));
        int feature = javaFeature(java);
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " jep451Lines=" + warnings.size());
        if (mode() == LendingTarget.Mode.ATTACH && feature >= 21) {
            assertFalse(warnings.isEmpty(), "the positive control: a Java " + feature + " target warns about the"
                    + " dynamic load (" + target.launched.logFile() + ")");
            assertTrue(warnings.stream().anyMatch(l -> l.contains("A Java agent has been loaded dynamically")),
                    String.valueOf(warnings));
        } else {
            assertEquals(List.of(), warnings, "no JEP 451 warning in " + mode().slug + " on Java " + feature);
        }
    }

    /** The lines of the two JEP 451 texts (attach F12, BS3 RESULT 8). */
    static List<String> jep451Lines(List<String> log) {
        return log.stream().filter(l -> l.contains("A Java agent has been loaded dynamically")
                || l.contains("EnableDynamicAgentLoading")).toList();
    }

    /** {@code 8} for {@code 1.8.0_x}, else the number before the first dot or dash ({@code 25}). */
    static int javaFeature(String version) {
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2, version.indexOf('.', 2) < 0 ? version.length()
                    : version.indexOf('.', 2)));
        }
        String digits = version.split("[.\\-+]")[0];
        return Integer.parseInt(digits);
    }

    /**
     * Check 1c (phase6-contracts.md 8.2, decision D136): after {@code target.ready} (pending probes read "not probed
     * yet" before it), the target's matrix equals the committed golden of its mode, normalised (ids, states and
     * strategy strict, reason by presence, the allow-list); the hooks leg's is read before any catalogue is installed,
     * so it holds {@code hook.engine} and no {@code hook.<id>}.
     */
    @Test
    @Order(30)
    void theCapabilityMatrixIsTheGoldenOfTheMode() throws Exception {
        CapabilityGolden.check(mode().slug, client().capabilities());
    }

    /**
     * The modules {@code agent.info} lists: the four of every mode, and {@code hooks} and (BW, phase6-contracts.md 13.2
     * item 1) {@code byteman} in the hooks leg.
     */
    Map<String, Object> expectedModules() {
        Map<String, Object> modules = new java.util.TreeMap<>(Map.of("swing", "INSTALLED", "data", "INSTALLED",
                "lending", "INSTALLED", "web", "INSTALLED"));
        if (hooksLeg()) {
            modules.put("hooks", "INSTALLED");
            modules.put("byteman", "INSTALLED");
        }
        return modules;
    }

    /** True in the hooks leg ({@link LendingTarget.Mode#JAVAAGENT_HOOKS}). */
    boolean hooksLeg() {
        return mode() == LendingTarget.Mode.JAVAAGENT_HOOKS;
    }

    /**
     * Called in checks 16 and 17 right after the fresh agent is ready (phase6-contracts.md 13.2 item 1: the hooks
     * leg's re-attach carries the same options); nothing outside the hooks leg.
     */
    void afterReattach() throws Exception {
    }

    // ------------------------------------------------------------------ hardening H2: HMAC, no bearer token

    /**
     * Hardening H2 against the real agent, on raw sockets so that nothing depends on the client under test: the old
     * {@code Bearer} header is refused with the constant 401 on {@code /ping} and on {@code /rpc}; a hello signed
     * with the shared token is answered, and the reply's MAC, session and echoed nonce verify with the token; a
     * JSON-RPC request signed for the session is executed and its reply verifies; a hello or a request signed with
     * another key is 401. No reply carries the token.
     */
    @Test
    @Order(40)
    void theHandshakeIsHmacAndTheOldBearerHeaderIsRefused() throws Exception {
        int port = target.endpoint.port();
        String token = target.endpoint.token();
        assertNotNull(token, "the endpoint knows the shared token");
        String session = String.valueOf(client().ping().get("sessionId"));
        assertTrue(Auth.isValidSessionId(session), "the client's verified handshake learned the session: " + session);

        byte[] info = RawHttp.rpcBody("raw-1", "agent.info");
        for (RawHttp.Reply bearer : List.of(RawHttp.get(port, "/ping", "Bearer " + token),
                RawHttp.get(port, "/ping?nonce=" + Auth.newNonce(), "Bearer " + token),
                RawHttp.post(port, "/rpc", "Bearer " + token, info))) {
            assertUnauthorized(bearer, "the old Bearer header");
        }
        assertUnauthorized(RawHttp.get(port, "/ping?nonce=" + Auth.newNonce(), null), "no Authorization");

        String clientNonce = Auth.newNonce();
        RawHttp.Reply hello = RawHttp.get(port, "/ping?nonce=" + clientNonce,
                Auth.helloAuthorization(token, clientNonce));
        assertEquals(200, hello.status(), String.valueOf(hello));
        assertEquals(session, hello.header(Auth.HEADER_SESSION));
        assertTrue(hello.verifies(token, session, clientNonce), "the reply MAC verifies with the token: " + hello);
        assertFalse(hello.verifies("x" + token, session, clientNonce), "and with nothing else");
        Map<String, Object> body = hello.json();
        assertEquals(clientNonce, body.get("clientNonce"));
        assertEquals(session, body.get("sessionId"));
        assertEquals(target.endpoint.nonce(), body.get("agentNonce"));
        String forgedNonce = Auth.newNonce();
        assertUnauthorized(RawHttp.get(port, "/ping?nonce=" + forgedNonce, Auth.helloAuthorization("x" + token,
                forgedNonce)), "a hello signed with another key");

        String requestNonce = Auth.newNonce();
        RawHttp.Reply rpc = RawHttp.post(port, "/rpc", Auth.requestAuthorization(token, session, requestNonce, "POST",
                "/rpc", Auth.sha256hex(info)), info);
        assertEquals(200, rpc.status(), String.valueOf(rpc));
        assertTrue(rpc.verifies(token, session, requestNonce), "the reply MAC verifies: " + rpc);
        Map<?, ?> result = (Map<?, ?>) rpc.json().get("result");
        assertNotNull(result, String.valueOf(rpc));
        assertEquals(mode().loadMode.name(), ((Map<?, ?>) result.get("value")).get("loadMode"), String.valueOf(rpc));
        String otherNonce = Auth.newNonce();
        assertUnauthorized(RawHttp.post(port, "/rpc", Auth.requestAuthorization("x" + token, session, otherNonce,
                "POST", "/rpc", Auth.sha256hex(info)), info), "a request signed with another key");
        for (RawHttp.Reply reply : List.of(hello, rpc)) {
            assertFalse(reply.text().contains(token) || reply.headers().toString().contains(token),
                    "the token never travels");
        }
        assertFalse(client().isLost(), "none of this touched the client");
        client().call("agent.info", Map.of(), CALL);
    }

    /** The constant 401 of hardening H2: {@code WWW-Authenticate: Skillroy-HMAC}, no MAC, connection closed. */
    static void assertUnauthorized(RawHttp.Reply reply, String what) {
        assertEquals(401, reply.status(), what + ": " + reply);
        assertEquals(Auth.SCHEME, reply.header(Auth.HEADER_WWW_AUTHENTICATE), what + ": " + reply);
        assertNull(reply.header(Auth.HEADER_MAC), what + " gets no reply MAC: " + reply);
        assertNull(reply.header(Auth.HEADER_SESSION), what + " learns no session: " + reply);
        assertEquals("close", String.valueOf(reply.header("Connection")).toLowerCase(Locale.ROOT), what + ": "
                + reply);
    }

    // ------------------------------------------------------------------ amendment A21: module redaction keys

    /**
     * Amendment A21: the target was launched with {@code -Dlending.pin}, a key the shared rule does not consider
     * secret; the lending module's {@code RedactionKeys} names it, so {@code jvm.properties} and {@code jvm.info}
     * show {@code <redacted>} and nowhere the value, while the desk's other properties are shown as they are.
     */
    @Test
    @Order(50)
    void theLendingModuleRedactsTheDesksPin() throws Exception {
        assertFalse(SecretKeys.looksSecret("lending.pin"), "only the module's rule makes it secret");
        Map<String, Object> properties = value(client().call("jvm.properties", Map.of("names",
                List.of("lending.pin", "lending.seed")), CALL));
        Map<?, ?> values = (Map<?, ?>) properties.get("properties");
        assertEquals(SecretKeys.REDACTED, values.get("lending.pin"), String.valueOf(properties));
        assertEquals("A", values.get("lending.seed"));
        Map<String, Object> jvm = value(client().call("jvm.info", Map.of(), CALL));
        List<String> arguments = ((List<?>) jvm.get("inputArguments")).stream().map(String::valueOf).toList();
        assertTrue(arguments.contains("-Dlending.pin=" + SecretKeys.REDACTED), String.valueOf(arguments));
        assertTrue(arguments.contains("-Dlending.seed=A"), String.valueOf(arguments));
        for (RpcResult reply : List.of(client().call("jvm.info", Map.of(), CALL), client().call("jvm.properties",
                Map.of("names", List.of("lending.pin")), CALL), client().call("agent.info", Map.of(), CALL))) {
            assertFalse(String.valueOf(reply.value()).contains(LendingTarget.PIN), "the PIN never leaves the JVM");
        }
    }

    // ------------------------------------------------------------------ item 2

    @Test
    @Order(60)
    void keypadKeysAreNamedByTheirActionId() throws Exception {
        Map<String, Object> snapshot = value(client().call("swing.snapshot", Map.of(), CALL));
        Set<String> keyNames = new HashSet<>();
        collect(snapshot.get("windows"), keyNames);
        assertEquals(KEYPAD, keyNames, "the icon-only keys carry their action ids as names");

        Map<String, Object> found = value(client().call("swing.find", Map.of("by", Map.of("action", "DUE")),
                CALL));
        List<?> matches = (List<?>) found.get("matches");
        assertEquals(1, matches.size(), String.valueOf(found));
        Map<?, ?> due = (Map<?, ?>) matches.get(0);
        assertEquals("DUE", due.get("name"));
        assertEquals("IconKey", due.get("class"));

        Map<String, Object> windows = value(client().call("swing.windows", Map.of(), CALL));
        assertTrue(((List<?>) windows.get("windows")).stream().anyMatch(w -> "Tool Library Lending Desk".equals(
                ((Map<?, ?>) w).get("title"))), String.valueOf(windows));
    }

    private static void collect(Object nodes, Set<String> keyNames) {
        if (!(nodes instanceof List<?> list)) {
            return;
        }
        for (Object n : list) {
            Map<?, ?> node = (Map<?, ?>) n;
            if ("IconKey".equals(node.get("class"))) {
                keyNames.add(String.valueOf(node.get("name")));
            }
            collect(node.get("children"), keyNames);
        }
    }

    // ------------------------------------------------------------------ item 3, run.jsonl, JUnit XML

    @Test
    @Order(70)
    void committedScenariosPassWithoutSleeping() throws Exception {
        List<RunResult> results = new ArrayList<>();
        for (String id : List.of("basic-loan", "basic-loan-ui")) {
            Runs.Outcome outcome = Runs.run(client(), Runs.committed(id), ItEnv.runDir(mode().slug, id));
            Runs.assertPassedWithoutSleeping(outcome);
            if (id.equals("basic-loan-ui")) {
                Runs.assertComponentClicksAtFullFidelity(outcome.dir(), UI_CLICKS);
            }
            results.add(outcome.result());
            ItEnv.timing("java=" + java + " mode=" + mode().slug + " scenario=" + id + " status="
                    + outcome.result().status() + " ms=" + outcome.result().duration().toMillis());
        }
        assertSignedOff();
        Path capture = ItEnv.runDir(mode().slug, "basic-loan").resolve("artifacts/capture.png");
        assertPng(capture);

        Path xml = ItEnv.runDir(mode().slug, "junit-report.xml");
        new JunitXmlReporter().write(xml, "lending-" + mode().slug, results, null);
        String text = Files.readString(xml, StandardCharsets.UTF_8);
        assertTrue(text.contains("<testsuite name=\"lending-" + mode().slug + "\" tests=\"3\" failures=\"0\" "
                + "errors=\"0\" skipped=\"0\""), text);
        assertTrue(text.contains("<testcase name=\"basic-loan\"") && text.contains("<testcase name=\"basic-loan-ui\""),
                text);
    }

    // ------------------------------------------------------------------ item 4

    @Test
    @Order(80)
    void aWrongExpectationFailsAsAssertionWithSnapshotAndCapture() throws Exception {
        Runs.Outcome outcome = Runs.run(client(), Runs.negative("wrong-expectation.yaml"),
                ItEnv.runDir(mode().slug, "wrong-expectation"));
        RunResult result = outcome.result();
        assertEquals(Status.FAIL, result.status(), outcome.describe());
        assertEquals(FailureKind.ASSERTION, result.failureKind(), outcome.describe());
        assertEquals("s3", result.failedStepId());
        assertEquals("loan.lines[code=$item.code].qty", result.detail().get("path"), "the path as written");
        assertEquals("2", result.detail().get("actual"));
        assertTrue(result.detail().get("snapshot") instanceof String ui && ui.contains("DUE"),
                "the failure carries the UI as text: " + result.detail().get("snapshot"));
        Path failure = outcome.dir().resolve("failure");
        assertTrue(Files.size(failure.resolve("snapshot.json")) > 100);
        assertTrue(Files.readString(failure.resolve("app-state.json")).contains("\"LOAN\""));
        assertTrue(Files.exists(failure.resolve("events.json")));
        BufferedImage capture = assertPng(failure.resolve("capture.png"));
        assertTrue(capture.getWidth() >= 800 && capture.getHeight() >= 600, "the capture shows the desk window: "
                + capture.getWidth() + " x " + capture.getHeight());
        assertSignedOff();
    }

    // ------------------------------------------------------------------ item 5

    @Test
    @Order(90)
    void recoverSignsOffAfterAFailureAndTheNextScenarioPasses() throws Exception {
        Runs.Outcome failed = Runs.run(client(), Runs.negative("prompt-left-open.yaml"),
                ItEnv.runDir(mode().slug, "prompt-left-open"));
        assertEquals(Status.FAIL, failed.result().status(), failed.describe());
        assertEquals(FailureKind.ASSERTION, failed.result().failureKind(), failed.describe());
        assertEquals("Unknown item", failed.result().detail().get("actual"));
        assertTrue(Files.readString(failed.dir().resolve("failure/dialogs.json")).contains("Unknown item"),
                "the open prompt is part of the evidence");
        List<Map<String, Object>> log = Runs.runLog(failed.dir());
        assertTrue(log.stream().anyMatch(r -> "recover".equals(r.get("type")) && Boolean.TRUE.equals(r.get("ok"))),
                "recover ran and succeeded: " + log);
        assertSignedOff();
        assertNull(client().call("lending.prompt", Map.of(), CALL).value(), "the prompt was answered");

        Runs.Outcome next = Runs.run(client(), Runs.committed("basic-loan"),
                ItEnv.runDir(mode().slug, "basic-loan-after-recover"));
        Runs.assertPassedWithoutSleeping(next);
    }

    @Test
    @Order(100)
    void aMissingCapabilityIsAPreconditionFailureAndAnUnavailableGuardDegrades() throws Exception {
        Runs.Outcome missing = Runs.run(client(), Runs.negative("missing-capability.yaml"),
                ItEnv.runDir(mode().slug, "missing-capability"));
        assertEquals(Status.ERROR, missing.result().status(), missing.describe());
        assertEquals(FailureKind.PRECONDITION_FAILED, missing.result().failureKind(), missing.describe());
        assertTrue(missing.result().message().contains("lending.teleport"), missing.result().message());
        assertTrue(missing.result().steps().isEmpty(), "no step ran");

        Runs.Outcome guarded = Runs.run(client(), Runs.negative("guarded-unavailable.yaml"),
                ItEnv.runDir(mode().slug, "guarded-unavailable"));
        assertEquals(Status.DEGRADED, guarded.result().status(), guarded.describe());
        assertEquals(1, guarded.result().skippedExpectations().size());
        assertTrue(guarded.result().skippedExpectations().get(0).contains("swing.click.robot"),
                guarded.result().skippedExpectations().get(0));
        assertEquals(0, guarded.sleeps());
        assertSignedOff();
    }

    // ------------------------------------------------------------------ item 6

    @Test
    @Order(110)
    void componentRungOnAWindowThatCannotTakeTheFocusReportsFocusFailed() throws Exception {
        send("Entry", "100");
        assertEquals("IDLE", send("Entry", "1111").payload().get("state"));
        assertEquals("LOAN", send("Barcode", "5000000000017").payload().get("state"));
        RpcResult scan = client().call("lending.input", Map.of("source", "Barcode", "value", "0000000000000"), CALL);
        Event prompt = client().await("prompt.waiting", scan.seqBefore(), null, WAIT);
        assertNotNull(prompt);
        // The engine reports the prompt before its dialog is on screen; the dialog's own window event says it is.
        Event shown = client().await("window.opened", scan.seqBefore(), Map.of("modal", true), WAIT);
        assertNotNull(shown, "the prompt dialog opened");
        Map<String, Object> dialogs = value(client().call("swing.dialogs", Map.of(), CALL));
        Map<?, ?> dialog = (Map<?, ?>) ((List<?>) dialogs.get("dialogs")).get(0);
        assertEquals("Unknown item", dialog.get("text"));
        assertEquals("lending", dialog.get("extractor"));

        Map<String, Object> click = new LinkedHashMap<>();
        click.put("by", Map.of("action", "DUE"));
        click.put("rung", "COMPONENT");
        click.put("focusTimeoutMs", 1500L);
        AgentRpcException blocked = assertThrows(AgentRpcException.class,
                () -> client().call("swing.click", click, CALL));
        assertEquals(ErrorCodes.MODAL_OPEN, blocked.error().code(), "the modal prompt blocks the main window");

        click.put("force", true);
        Map<String, Object> forced = value(client().call("swing.click", click, CALL));
        assertEquals("FOCUS_FAILED", forced.get("rung"), String.valueOf(forced));
        assertEquals(Boolean.FALSE, forced.get("fired"));

        click.put("activateWindow", true);
        Map<String, Object> activated = value(client().call("swing.click", click, CALL));
        assertEquals("FOCUS_FAILED", activated.get("rung"), "a blocked window cannot be activated: " + activated);
        assertEquals(Boolean.FALSE, activated.get("fired"));

        Map<String, Object> painted = value(client().call("swing.click", Map.of("by", Map.of("name", "OK"),
                "rung", "COMPONENT", "focusTimeoutMs", 1000L), CALL));
        assertEquals("FOCUS_FAILED", painted.get("rung"), "the painted prompt button is not focusable: " + painted);
        assertNotNull(client().call("lending.prompt", Map.of(), CALL).value(), "nothing answered the prompt");

        RpcResult answer = client().call("lending.prompt.answer", Map.of("choice", "OK"), CALL);
        Event back = client().await(READY_TOPICS, answer.seqBefore(), null, WAIT);
        assertEquals("LOAN", back.payload().get("state"));
        // A DUE delivered behind the prompt would be processed now and move the desk to DEPOSIT, where a
        // barcode is not accepted: the next scan proves no click got through.
        assertEquals("LOAN", send("Barcode", "5000000000017").payload().get("state"));
        Map<String, Object> loan = value(client().call("lending.loan", Map.of(), CALL));
        assertEquals("2", ((Map<?, ?>) ((List<?>) loan.get("lines")).get(0)).get("qty"));

        // The control: the same rung on the same key does click once the window can take the focus, so the
        // FOCUS_FAILED above says something about the blocked window and not about the rung. With the prompt gone
        // the window is no longer blocked; on a display without a window manager nothing hands it the focus back,
        // so the click asks for it (activateWindow). Recorded for the focus notes: what the rung does unaided.
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("by", Map.of("action", "DUE"));
        component.put("rung", "COMPONENT");
        component.put("focusTimeoutMs", 1500L);
        RpcResult unaided = client().call("swing.click", component, CALL);
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " componentRungAfterPromptWithoutActivation="
                + value(unaided).get("rung"));
        long clickedAt = unaided.seqBefore();
        if (!Boolean.TRUE.equals(value(unaided).get("fired"))) {
            assertEquals("FOCUS_FAILED", value(unaided).get("rung"), String.valueOf(unaided.value()));
            component.put("activateWindow", true);
            component.put("focusTimeoutMs", 20_000L);
            RpcResult focused = client().call("swing.click", component, CALL);
            assertEquals("COMPONENT", value(focused).get("rung"), "the window can take the focus now: "
                    + focused.value());
            assertEquals(Boolean.TRUE, value(focused).get("fired"), String.valueOf(focused.value()));
            clickedAt = focused.seqBefore();
        }
        Event deposit = client().await("input.requested", clickedAt, Map.of("state", "DEPOSIT"), WAIT);
        assertNotNull(deposit, "the focused click on DUE reached the desk");
        Runs.PACK.recover(client());
        assertSignedOff();
    }

    // ------------------------------------------------------------------ amendments A28-A30: click fidelity

    /**
     * Amendments A28/A29: a COMPONENT click (the scenario's {@code clickRung: COMPONENT}, not strict) on the prompt's
     * painted OK button, which can never take the focus, falls back to the ACTION rung, answers the prompt, and the
     * scenario completes its loan; the run is DEGRADED (not PASS) with exactly that fall-back listed in the result,
     * in the step, in {@code run.jsonl} and in the JUnit report.
     */
    @Test
    @Order(120)
    void aClickThatFellBackToTheActionRungDegradesTheRunAndIsListed() throws Exception {
        Runs.Outcome outcome = Runs.run(client(), Runs.negative("click-fallback-degraded.yaml"),
                ItEnv.runDir(mode().slug, "click-fallback-degraded"));
        RunResult result = outcome.result();
        assertEquals(Status.DEGRADED, result.status(), outcome.describe());
        assertNull(result.failureKind(), outcome.describe());
        assertEquals(List.of(), result.skippedExpectations(), "degraded by fidelity alone");
        assertEquals(1, result.degradedFidelity().size(), outcome.describe());
        String entry = result.degradedFidelity().get(0);
        assertTrue(entry.startsWith("s6: click fell back from COMPONENT to ACTION (FOCUS_FAILED: "), entry);
        StepResult click = result.steps().stream().filter(st -> st.id().equals("s6")).findFirst().orElseThrow();
        assertEquals(Status.DEGRADED, click.status());
        assertEquals(List.of(entry), click.degradedFidelity());
        assertTrue(result.steps().stream().filter(st -> !st.id().equals("s6")).allMatch(st -> st.status()
                == Status.PASS), "every other step passed: " + result.steps());
        List<Map<String, Object>> fidelity = Runs.runLog(outcome.dir()).stream().filter(r -> "fidelity".equals(
                r.get("type"))).toList();
        assertEquals(1, fidelity.size(), String.valueOf(fidelity));
        assertEquals("s6", fidelity.get(0).get("step"));
        assertEquals("COMPONENT", fidelity.get(0).get("requestedRung"));
        assertEquals("ACTION", fidelity.get(0).get("rung"));
        Map<?, ?> sent = (Map<?, ?>) Runs.calls(outcome.dir(), "swing.click").get(0).get("params");
        assertEquals("COMPONENT", sent.get("rung"));
        assertEquals("ACTION", sent.get("fallback"), "not strict: the ui setting added the fall-back");
        assertEquals(0, outcome.sleeps());
        assertFalse(Runs.runLog(outcome.dir()).stream().anyMatch(r -> "recover".equals(r.get("type"))),
                "a DEGRADED run is not recovered: it left the desk as a passing run does");
        Path xml = ItEnv.runDir(mode().slug, "click-fallback-degraded.xml");
        new JunitXmlReporter().write(xml, "lending-" + mode().slug + "-fidelity", List.of(result), null);
        String report = Files.readString(xml, StandardCharsets.UTF_8);
        assertTrue(report.contains("<skipped") && report.contains("fell back from COMPONENT to ACTION"), report);
        assertSignedOff();
        assertNull(client().call("lending.prompt", Map.of(), CALL).value(), "the fall-back click answered it");
    }

    /**
     * Amendment A29 with {@code strict: true}: the same click gets no fall-back, so it does not fire; the step fails
     * as FAIL/ASSERTION ("click did not fire"), nothing is listed as degraded, and the pack's recover answers the
     * prompt that is still open.
     */
    @Test
    @Order(130)
    void withStrictTheClickThatCannotTakeTheFocusFailsAsAssertion() throws Exception {
        Runs.Outcome outcome = Runs.run(client(), Runs.negative("click-strict-fails.yaml"),
                ItEnv.runDir(mode().slug, "click-strict-fails"));
        RunResult result = outcome.result();
        assertEquals(Status.FAIL, result.status(), outcome.describe());
        assertEquals(FailureKind.ASSERTION, result.failureKind(), outcome.describe());
        assertEquals("s6", result.failedStepId());
        assertTrue(result.message().startsWith("click did not fire: FOCUS_FAILED: "), result.message());
        Map<?, ?> fired = (Map<?, ?>) result.detail().get("value");
        assertEquals(Boolean.FALSE, fired.get("fired"), String.valueOf(result.detail()));
        assertEquals(List.of(), result.degradedFidelity());
        Map<?, ?> sent = (Map<?, ?>) Runs.runLog(outcome.dir()).stream().filter(r -> "call".equals(r.get("type"))
                && "swing.click".equals(r.get("primitive"))).findFirst().orElseThrow().get("params");
        assertEquals("COMPONENT", sent.get("rung"));
        assertFalse(sent.containsKey("fallback"), "strict: no fall-back was asked for: " + sent);
        assertTrue(Runs.runLog(outcome.dir()).stream().anyMatch(r -> "recover".equals(r.get("type"))
                && Boolean.TRUE.equals(r.get("ok"))), "recover ran and succeeded");
        assertSignedOff();
        assertNull(client().call("lending.prompt", Map.of(), CALL).value(), "recover answered the prompt");
    }

    /**
     * Amendment A30: a step whose input a modal dialog blocks ({@code MODAL_OPEN}) fails as FAIL/ASSERTION, not as an
     * environment error, and the failure names the dialog's text.
     */
    @Test
    @Order(140)
    void aModalDialogInTheWayOfAStepFailsItAsAssertion() throws Exception {
        Runs.Outcome outcome = Runs.run(client(), Runs.negative("modal-open-step.yaml"),
                ItEnv.runDir(mode().slug, "modal-open-step"));
        RunResult result = outcome.result();
        assertEquals(Status.FAIL, result.status(), outcome.describe());
        assertEquals(FailureKind.ASSERTION, result.failureKind(), outcome.describe());
        assertEquals("s6", result.failedStepId());
        assertTrue(result.message().contains("MODAL_OPEN") && result.message().contains("Unknown item"),
                result.message());
        assertEquals("Unknown item", result.detail().get("dialogText"), String.valueOf(result.detail()));
        assertTrue(result.detail().containsKey("dialogTitle"), String.valueOf(result.detail()));
        assertTrue(Files.readString(outcome.dir().resolve("failure/dialogs.json")).contains("Unknown item"),
                "the dialog in the way is part of the evidence");
        assertTrue(Files.readString(outcome.dir().resolve("failure/app-state.json")).contains("unknown-item"),
                "when the step failed, the desk was still asking its question");
        List<Map<String, Object>> log = Runs.runLog(outcome.dir());
        assertTrue(log.stream().anyMatch(r -> "recover".equals(r.get("type")) && Boolean.TRUE.equals(r.get("ok"))),
                "recover ran and succeeded");
        assertSignedOff();
    }

    /**
     * The committed UI scenario again, now that a prompt has come and gone: on a display without a window manager
     * nothing hands the focus back to the desk window when a dialog closes, so its COMPONENT clicks must activate the
     * window ({@code activateWindow: true} from {@code clickRung: COMPONENT}); they all fire on the COMPONENT rung, the
     * fall-back is never used and the run passes with no degraded-fidelity entry.
     */
    @Test
    @Order(150)
    void theUiScenarioActivatesTheWindowAndClicksAtFullFidelityAfterAPrompt() throws Exception {
        Map<String, Object> windows = value(client().call("swing.windows", Map.of(), CALL));
        Object focused = ((List<?>) windows.get("windows")).stream().map(w -> (Map<?, ?>) w).filter(w ->
                "Tool Library Lending Desk".equals(w.get("title"))).map(w -> w.get("focused")).findFirst()
                .orElse(null);
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " deskWindowFocusedBeforeUiRun=" + focused);
        Runs.Outcome outcome = Runs.run(client(), Runs.committed("basic-loan-ui"),
                ItEnv.runDir(mode().slug, "basic-loan-ui-after-prompt"));
        Runs.assertPassedWithoutSleeping(outcome);
        Runs.assertComponentClicksAtFullFidelity(outcome.dir(), UI_CLICKS);
        assertSignedOff();
        // phase6-contracts.md 9.5: the DISPATCH rung (a mouse press, release and click through dispatchEvent) fires on
        // the keypad too, so that every rung but ROBOT is proven in one place.
        send("Entry", "100");
        assertEquals("IDLE", send("Entry", "1111").payload().get("state"));
        Map<String, Object> click = new LinkedHashMap<>();
        click.put("by", Map.of("action", "SIGN_OFF"));
        click.put("rung", "DISPATCH");
        RpcResult dispatched = client().call("swing.click", click, CALL);
        assertEquals("DISPATCH", value(dispatched).get("rung"), String.valueOf(dispatched.value()));
        assertEquals(Boolean.TRUE, value(dispatched).get("fired"), String.valueOf(dispatched.value()));
        assertNotNull(client().await("input.requested", dispatched.seqBefore(), Map.of("state", "SIGNED_OFF"), WAIT),
                "the dispatched click on SIGN_OFF reached the desk");
        assertSignedOff();
    }

    // ------------------------------------------------------------------ phase7-contracts.md 13.2 items 7 and 9

    /**
     * Item 7, the seed-A scenarios (phase7-contracts.md 12.4, 13.2 item 7): {@code sign-on-by-number-pad} passes (its
     * region steps through the number pad);
     * {@code loan-table-visual-check} is DEGRADED, its visual check skipped with the reason naming
     * {@code inference.vision} {@code UNAVAILABLE (replay)} (the vision module, a test dependency of this module, is on
     * the class path; the fix wave of the Phase 7 review, FB, L3-F6, asserts it unconditionally, so that a lost
     * dependency or a renamed contributor fails here instead of weakening exit criterion 3); {@code vision-live-only}
     * is SKIPPED: its {@code run.jsonl} holds
     * {@code run-start}, {@code skipped}, {@code ledger {count: 0}} and {@code run-end} and nothing was sent to the
     * agent. None of the three has a bindings file.
     */
    @Test
    @Order(155)
    void thePhase7SeedAScenariosRunAsDesigned() throws Exception {
        Runs.Outcome pad = Runs.run(client(), Runs.committed("sign-on-by-number-pad"),
                ItEnv.runDir(mode().slug, "sign-on-by-number-pad"));
        Runs.assertPassedWithoutSleeping(pad);
        assertEquals(3, Runs.calls(pad.dir(), "swing.pointer").size(), "three region clicks on the pad");
        assertSignedOff();

        Runs.Outcome visual = Runs.run(client(), Runs.committed("loan-table-visual-check"),
                ItEnv.runDir(mode().slug, "loan-table-visual-check"));
        assertEquals(Status.DEGRADED, visual.result().status(), visual.describe());
        assertEquals(1, visual.result().skippedExpectations().size(), visual.describe());
        String line = visual.result().skippedExpectations().get(0);
        assertTrue(line.contains("aiExpect") && line.contains("no visual evaluator in this host")
                && line.endsWith("capability inference.vision is UNAVAILABLE (replay)"), line);
        assertEquals(List.of(), visual.result().modelCalls(), "no model call");
        assertSignedOff();

        Runs.Outcome live = Runs.run(client(), Runs.committed("vision-live-only"),
                ItEnv.runDir(mode().slug, "vision-live-only"));
        assertEquals(Status.SKIPPED, live.result().status(), live.describe());
        assertNull(live.result().failureKind());
        assertEquals("not executed: the plan needs a model while steps run (inference: run)", live.result().message());
        assertEquals(List.of(), live.result().steps());
        List<Map<String, Object>> log = Runs.runLog(live.dir());
        assertEquals(List.of("run-start", "skipped", "ledger", "run-end"), log.stream().map(r -> r.get("type"))
                .toList(), "nothing was sent to the agent: no requires, hooks, capabilities or call record");
        assertEquals(0L, log.get(2).get("count"));
        assertEquals("SKIPPED", log.get(3).get("status"));
        for (String id : List.of("sign-on-by-number-pad", "loan-table-visual-check", "vision-live-only")) {
            for (SampleCredentials.Env env : SampleCredentials.Env.values()) {
                assertFalse(Files.exists(BindingsFile.locate(ScenarioA.bindingsRoot(),
                        env.id, id)), id + " has no bindings file");
            }
        }
        assertSignedOff();
    }

    /**
     * Item 9, the run's side (phase7-contracts.md 8.5, 13.2 item 9): the {@code capabilities} record of a run holds
     * {@code agentHash}, the hash of the agent's matrix after step 0b, and {@code contributed}, the ids the controller
     * contributors on the class path add ({@code inference.gateway} and {@code inference.sql} from VC's step 1,
     * {@code inference.vision} from the vision module, asserted unconditionally since the fix wave, FB, L3-F6), each
     * {@code UNAVAILABLE (replay)} in the archived {@code capabilities.json}; {@code hash} is the merged matrix's.
     */
    @Test
    @Order(156)
    void theCapabilitiesRecordCarriesTheAgentsHashAndTheContributedIds() throws Exception {
        Path dir = ItEnv.runDir(mode().slug, "loan-table-visual-check");
        Map<String, Object> record = Runs.runLog(dir).stream().filter(r -> "capabilities".equals(r.get("type")))
                .findFirst().orElseThrow();
        CapabilityMatrix agent = client().capabilities();
        assertEquals(agent.hash(), record.get("agentHash"), "the agent's matrix after step 0b");
        List<String> contributed = List.of("inference.gateway", "inference.sql", "inference.vision");
        assertEquals(contributed, record.get("contributed"));
        CapabilityMatrix archived = CapabilityMatrix.fromJson(Json.parseObject(Files.readString(dir.resolve(
                "capabilities.json"), StandardCharsets.UTF_8)));
        assertEquals(archived.hash(), record.get("hash"), "the merged matrix is archived and hashed");
        for (String id : contributed) {
            assertEquals(CapabilityState.UNAVAILABLE, archived.get(id).state(), id);
            assertEquals("replay", archived.get(id).reason(), id);
        }
        assertEquals(agent.all().keySet(), archived.agentOnly().all().keySet().stream().filter(
                id -> !contributed.contains(id)).collect(Collectors.toSet()),
                "the archive is the agent's matrix plus the contributed ids");
    }

    // ------------------------------------------------------------------ JUnit glue

    /**
     * The JUnit glue over the committed scenario directory: the two Phase 1 scenarios and scenarios A, B, C, D and E
     * (phase3-contracts.md 10.4, phase5-contracts.md 14.2, 14.3, 15.2 items 1 and 3), which run in {@code replay} on
     * this seed A target with the {@code seed-a} bindings (scenario C's authoring record beside them is not a
     * scenario), then the stream's trailing {@code skillroy: zero model calls} test (phase2-contracts.md 12.2), because
     * scenarios A to D need bindings: scenario D's supervisor, found by a model when it was approved, replays like any
     * binding, with no model call.
     */
    @TestFactory
    @Order(160)
    Stream<DynamicTest> junitGlueRunsTheCommittedScenarios() {
        RunOptions options = RunOptions.defaults().withOutputDir(ItEnv.runDir(mode().slug, "junit-glue"))
                .withBind(Runs.bind(SampleCredentials.Env.SEED_A));
        List<DynamicTest> tests = ScenarioTests.dynamicTests(
                ScenarioD.withoutScenarioDUntilItHasBindings(ScenarioSource.directory(ItEnv.moduleDir()
                        .resolve("scenarios"))), this::client, options).toList();
        List<String> names = tests.stream().map(DynamicTest::getDisplayName).toList();
        List<String> all = new ArrayList<>(List.of("basic-loan", "basic-loan-ui", ScenarioB.ID, ScenarioA.ID,
                ScenarioC.ID, ScenarioE.ID, ScenarioD.ID, "loan-table-visual-check", "vision-live-only",
                "sign-on-by-number-pad", "keypad-image",
                // phase8-contracts.md 12.4, 13.2 item 32 (ES; this branch's truth, D641)
                "semantic-input-warnings", "sign-on-any", "scan-checked", "device-scan"));
        List<String> ids = all.stream().filter(id -> !id.equals("loan-table-visual-check")
                && !id.equals("vision-live-only") && !id.equals("device-scan")).toList();
        assertEquals(all.stream().sorted().toList(), names.subList(0, all.size()).stream().sorted().toList(),
                String.valueOf(names));
        assertEquals("skillroy: zero model calls", names.get(all.size()), "the stream ends with the ledger check");
        assertEquals(all.size() + 1, names.size());
        List<DynamicTest> checked = new ArrayList<>(tests);
        checked.add(DynamicTest.dynamicTest("run.jsonl written by the glue", () -> {
            for (String id : ids) {
                List<Map<String, Object>> log = Runs.runLog(ItEnv.runDir(mode().slug, "junit-glue").resolve(id));
                // phase6-contracts.md 6.3: scenario E is PASS with the pack's hooks, DEGRADED (reported as skipped)
                // without; every other committed scenario is PASS either way.
                String expected = id.equals(ScenarioE.ID) && !hooksLeg() ? "DEGRADED" : "PASS";
                assertEquals(expected, log.get(log.size() - 1).get("status"), id);
                assertFalse(log.stream().anyMatch(r -> "fidelity".equals(r.get("type"))), id + ": full fidelity");
                Runs.assertHooksRecord(ItEnv.runDir(mode().slug, "junit-glue").resolve(id), hooksLeg());
            }
            Runs.assertComponentClicksAtFullFidelity(ItEnv.runDir(mode().slug, "junit-glue").resolve("basic-loan-ui"),
                    UI_CLICKS);
            // phase7-contracts.md 8.6, 13.2 item 7: the visual check DEGRADED (reported as skipped), the live-only plan
            // SKIPPED (aborted), neither a failure.
            List<Map<String, Object>> visual = Runs.runLog(ItEnv.runDir(mode().slug, "junit-glue")
                    .resolve("loan-table-visual-check"));
            assertEquals("DEGRADED", visual.get(visual.size() - 1).get("status"));
            List<Map<String, Object>> live = Runs.runLog(ItEnv.runDir(mode().slug, "junit-glue")
                    .resolve("vision-live-only"));
            assertEquals("SKIPPED", live.get(live.size() - 1).get("status"));
            // phase8-contracts.md 13.2 item 32 (ES): device-scan falls back to SEMANTIC without the scanner driver.
            List<Map<String, Object>> device = Runs.runLog(ItEnv.runDir(mode().slug, "junit-glue")
                    .resolve("device-scan"));
            assertEquals("DEGRADED", device.get(device.size() - 1).get("status"));
            assertEquals(1, device.stream().filter(r -> "fidelity".equals(r.get("type"))).count());
            assertSignedOff();
        }));
        return checked.stream();
    }

    // ------------------------------------------------------------------ re-attach

    @Test
    @Order(170)
    void aSecondAttachReusesTheRunningAgent() throws Exception {
        Endpoint again = target.attach();
        assertEquals(target.endpoint.port(), again.port(), "the same agent answers");
        assertEquals(target.endpoint.nonce(), again.nonce());
        assertEquals(target.endpoint.startTimeMs(), again.startTimeMs());
        try (AgentClient second = AgentClient.connect(again)) {
            Map<String, Object> info = value(second.call("agent.info", Map.of(), CALL));
            assertEquals(mode().loadMode.name(), info.get("loadMode"), "still the original agent");
            List<String> ids = ((List<?>) info.get("modules")).stream().map(m -> String.valueOf(((Map<?, ?>) m)
                    .get("id"))).sorted().toList();
            assertEquals(List.copyOf(expectedModules().keySet()), ids, "one of each module");
            assertEquals(2L, lendingState(second).get("engineListeners"), "the window's listener and one agent's");
        }
        assertEquals(2L, lendingState(client()).get("engineListeners"));
    }

    // ------------------------------------------------------------------ phase8-contracts.md 13.2 items 26-29 (ES)

    /** The records of one type in a run's {@code run.jsonl}. */
    private static List<Map<String, Object>> records(Path dir, String type) throws IOException {
        return Runs.runLog(dir).stream().filter(r -> type.equals(r.get("type"))).toList();
    }

    /**
     * Items 26 and 27 (phase8-contracts.md 7.1, 7.2, 7.4): {@code semantic-input-warnings} passes with two
     * {@code warning} records the steps accepted ({@code VALUE_COERCED} with the barcode the desk took,
     * {@code VALUE_DROPPED}), two lines in {@code RunResult.warnings} and the JUnit XML; the negative
     * {@code warning-not-accepted} fails naming {@code VALUE_COERCED} with the hint; the two compile-error negatives are
     * refused with their messages; every {@code ack} wait of {@code basic-loan}'s run carries {@code match: {inputId}},
     * whose {@code input.acked} carries the id and never the value; and a queued input's later acknowledgement never
     * ends another input's correlated wait (observed when the desk's engine loses the race, see the method below).
     */
    @Test
    @Order(173)
    void semanticInputWarningsAndCorrelatedAcknowledgements() throws Exception {
        Runs.Outcome warned = Runs.run(client(), Runs.committed("semantic-input-warnings"),
                ItEnv.runDir(mode().slug, "semantic-input-warnings"));
        assertEquals(Status.PASS, warned.result().status(), warned.describe());
        List<Map<String, Object>> warnings = records(warned.dir(), "warning");
        assertEquals(List.of("VALUE_COERCED", "VALUE_DROPPED"), warnings.stream().map(w -> w.get("code")).toList());
        assertTrue(warnings.stream().allMatch(w -> Boolean.TRUE.equals(w.get("accepted"))), String.valueOf(warnings));
        assertEquals(Map.of("taken", "5000000000017"), warnings.get(0).get("data"), "the barcode the desk took");
        assertEquals(2, warned.result().warnings().size(), String.valueOf(warned.result().warnings()));
        String xml = new JunitXmlReporter().render("semantic", List.of(warned.result()), null);
        assertTrue(xml.contains("  warning s2: scan: lending.input VALUE_COERCED"), xml);
        assertSignedOff();

        Runs.Outcome refused = Runs.run(client(), Runs.negative("warning-not-accepted.yaml"),
                ItEnv.runDir(mode().slug, "warning-not-accepted"));
        assertEquals(Status.FAIL, refused.result().status(), refused.describe());
        assertEquals(FailureKind.ASSERTION, refused.result().failureKind());
        assertTrue(refused.result().message().contains("reported VALUE_COERCED") && refused.result().message()
                .contains("accept: [VALUE_COERCED]"), refused.result().message());
        assertSignedOff();
        for (String file : List.of("accept-unknown-code.yaml", "min-rung-without-ladder.yaml")) {
            ai.skillroy.testjava.scenario.ScenarioException e = assertThrows(
                    ai.skillroy.testjava.scenario.ScenarioException.class, () -> Runs.negative(file), file);
            assertTrue(e.getMessage().contains(PlansUpToDateTest.COMPILE_ERRORS.get(file)), e.getMessage());
        }

        // Item 27: the pack's acknowledgements are correlated by inputId in every committed scenario.
        Runs.Outcome loan = Runs.run(client(), Runs.committed("basic-loan"), ItEnv.runDir(mode().slug,
                "basic-loan-correlated"));
        Runs.assertPassedWithoutSleeping(loan);
        List<Map<String, Object>> acks = records(loan.dir(), "sync").stream().filter(r -> "ack".equals(r.get("wait")))
                .toList();
        assertFalse(acks.isEmpty());
        for (Map<String, Object> ack : acks) {
            assertTrue(ack.get("match") instanceof Map<?, ?> m && m.keySet().equals(Set.of("inputId"))
                    && String.valueOf(m.get("inputId")).startsWith("lending-"), String.valueOf(ack));
        }
        List<Map<String, Object>> inputs = Runs.calls(loan.dir(), "lending.input");
        for (Map<String, Object> call : inputs) {
            Map<?, ?> value = (Map<?, ?>) call.get("value");
            assertTrue(value.get("inputId") instanceof String && "CONSUMED".equals(value.get("consumed")),
                    String.valueOf(value));
        }
        long since = ((Number) ((Map<?, ?>) inputs.get(0).get("seq")).get("before")).longValue();
        Event acked = client().await("input.acked", since, null, Duration.ofSeconds(5));
        assertNotNull(acked);
        assertEquals(Set.of("source", "inputId"), acked.payload().keySet(), "never the value: " + acked);
        assertEquals(((Map<?, ?>) inputs.get(0).get("value")).get("inputId"), acked.payload().get("inputId"));
        assertSignedOff();
        aQueuedInputsLateAcknowledgementNeverEndsAnotherInputsWait();
    }

    /**
     * Item 27's race: a {@code lending.input} with {@code ackTimeoutMs: 0} returns {@code QUEUED}; a second input sent
     * at once, before the engine took the first, is pre-checked against the state the first will leave and dropped
     * when the engine takes it after the first; its correlated ACK wait then ends SYNC_TIMEOUT naming its own inputId,
     * although the first input's acknowledgement came after it started. Whether the second input passes its pre-check
     * depends on the engine thread taking the first input later than the call that posts the second checks the desk's
     * state, which a remote caller cannot arrange: the attempts are bounded, and a leg on which the engine always wins
     * the race says so in the timing log (the deterministic proofs are {@code LendingPrimitivesTest} and
     * {@code CorrelatedAckTest}).
     */
    private void aQueuedInputsLateAcknowledgementNeverEndsAnotherInputsWait() throws Exception {
        Path dir = ItEnv.runDir(mode().slug, "correlated-ack-session");
        boolean observed = false;
        int attempts = 0;
        try (ai.skillroy.testjava.scenario.ExecutorSession session = ai.skillroy.testjava.scenario.ExecutorSession
                .open(Runs.PACK, client(), ai.skillroy.testjava.scenario.SessionOptions.defaults(dir)
                        .withBind(Runs.bind(SampleCredentials.Env.SEED_A)))) {
            ai.skillroy.testjava.scenario.Origin person = ai.skillroy.testjava.scenario.Origin.person("load-mode-it");
            while (!observed && attempts < 10) {
                attempts++;
                send("Entry", "100");
                send("Entry", PASSWORD);
                RpcResult first = client().call("lending.input", Map.of("source", "Key.SIGN_OFF", "ackTimeoutMs", 0L),
                        CALL);
                ai.skillroy.testjava.scenario.StepOutcome second = session.execute(
                        new ai.skillroy.testjava.scenario.pack.PlannedCall("lending.input", Map.of("source", "Key.QTY",
                                "value", "2", "ackTimeoutMs", 0L), ai.skillroy.testjava.scenario.pack.Sync.ACK, 3000),
                        person);
                if (second.failureKind() == FailureKind.SYNC_TIMEOUT) {
                    observed = true;
                    String id = String.valueOf(((Map<?, ?>) second.lastResult().value()).get("inputId"));
                    assertEquals("QUEUED", ((Map<?, ?>) second.lastResult().value()).get("consumed"));
                    assertTrue(second.message().contains("matching {\"inputId\":\"" + id + "\"}"), second.message());
                    assertNotEquals(id, value(first).get("inputId"));
                }
                Map<String, Object> state = value(client().call("lending.state", Map.of(), CALL));
                if ("IDLE".equals(state.get("name"))) {
                    send("Key.SIGN_OFF", null);
                }
                assertSignedOff();
            }
        }
        ItEnv.timing("java=" + java + " mode=" + mode().slug + " correlatedAckRaceObserved=" + observed
                + " attempts=" + attempts);
    }

    /**
     * Item 28 (phase8-contracts.md 7.5): {@code sign-on-any} passes through the branches {@code auto} and
     * {@code password}; {@code scan-checked} passes with the induction check's {@code prompt} record expected and
     * answered by {@code lending.scanChecked}; {@code sign-on-any-password-required} fails naming the pack's
     * {@code lending.PASSWORD_REQUIRED}, recovery signs off, and the next scenario runs.
     */
    @Test
    @Order(174)
    void compositesOwnTheirNestedQuestions() throws Exception {
        Runs.Outcome any = Runs.run(client(), Runs.committed("sign-on-any"), ItEnv.runDir(mode().slug,
                "sign-on-any"));
        Runs.assertPassedWithoutSleeping(any);
        List<String> branches = Runs.calls(any.dir(), "lending.signOn").stream().map(c -> String.valueOf(((Map<?, ?>)
                ((Map<?, ?>) c.get("value")).get("composite")).get("branch"))).toList();
        assertEquals(List.of("auto", "password"), branches);
        assertSignedOff();

        Runs.Outcome checked = Runs.run(client(), Runs.committed("scan-checked"), ItEnv.runDir(mode().slug,
                "scan-checked"));
        Runs.assertPassedWithoutSleeping(checked);
        List<Map<String, Object>> prompts = records(checked.dir(), "prompt").stream().filter(r -> r.get("event")
                != null).toList();
        assertEquals(1, prompts.size(), String.valueOf(prompts));
        assertEquals(Boolean.TRUE, prompts.get(0).get("expected"));
        assertEquals("lending.scanChecked", prompts.get(0).get("answeredBy"));
        assertEquals("induction-check", ((Map<?, ?>) ((Map<?, ?>) prompts.get(0).get("event")).get("payload"))
                .get("id"));
        assertSignedOff();

        Runs.Outcome required = Runs.run(client(), Runs.negative("sign-on-any-password-required.yaml"),
                ItEnv.runDir(mode().slug, "sign-on-any-password-required"));
        assertEquals(Status.FAIL, required.result().status(), required.describe());
        assertEquals(FailureKind.ASSERTION, required.result().failureKind());
        assertTrue(required.result().message().contains("lending.PASSWORD_REQUIRED"), required.result().message());
        assertSignedOff();
        Runs.assertPassedWithoutSleeping(Runs.run(client(), Runs.committed("basic-loan"), ItEnv.runDir(mode().slug,
                "basic-loan-after-password-required")));
        assertSignedOff();
    }

    /**
     * Item 29 (phase8-contracts.md 7.6), the desk without its scanner driver: {@code device-scan} is DEGRADED with a
     * {@code rung} record on SEMANTIC, one {@code fidelity} record and one degraded line; {@code scanner/
     * device-scan-physical} ends ERROR / PRECONDITION_FAILED naming {@code lending.scanner.physical} with its
     * {@code rungs} record and no step call sent; {@code device.list} shows the scanner's ladder.
     */
    @Test
    @Order(175)
    void aLadderFallsBackAndARequiredRungIsRefusedBeforeAnythingRuns() throws Exception {
        Runs.Outcome scan = Runs.run(client(), Runs.committed("device-scan"), ItEnv.runDir(mode().slug,
                "device-scan"));
        assertEquals(Status.DEGRADED, scan.result().status(), scan.describe());
        List<Map<String, Object>> rungs = records(scan.dir(), "rung");
        assertEquals(1, rungs.size());
        assertEquals("SEMANTIC", rungs.get(0).get("rung"));
        assertEquals(1, records(scan.dir(), "fidelity").size());
        assertEquals(List.of("s2: deviceScan fell back from PHYSICAL to SEMANTIC (scanner driver not enabled)"),
                scan.result().degradedFidelity());
        assertSignedOff();

        Runs.Outcome physical = Runs.run(client(), Runs.committed("device-scan-physical"), ItEnv.runDir(mode().slug,
                "device-scan-physical"));
        assertEquals(Status.ERROR, physical.result().status(), physical.describe());
        assertEquals(FailureKind.PRECONDITION_FAILED, physical.result().failureKind());
        assertTrue(physical.result().message().contains("lending.scanner.physical is UNAVAILABLE"),
                physical.result().message());
        assertEquals(1, records(physical.dir(), "rungs").size());
        assertTrue(records(physical.dir(), "call").stream().allMatch(c -> "trace.mark".equals(c.get("primitive"))),
                "no step call sent");
        assertSignedOff();

        Map<String, Object> devices = value(client().call("device.list", Map.of(), CALL));
        Map<?, ?> scanner = ((List<?>) devices.get("devices")).stream().map(d -> (Map<?, ?>) d)
                .filter(d -> "scanner".equals(d.get("id"))).findFirst().orElseThrow();
        assertEquals(Map.of("scan", List.of("PHYSICAL", "SEMANTIC")), scanner.get("rungs"));
        Map<?, ?> printer = ((List<?>) devices.get("devices")).stream().map(d -> (Map<?, ?>) d)
                .filter(d -> "printer".equals(d.get("id"))).findFirst().orElseThrow();
        assertFalse(printer.containsKey("rungs"), "the printer's entry is unchanged");
    }

    // ------------------------------------------------------------------ item 7

    @Test
    @Order(180)
    void disableLeavesTheApplicationRunningAndRemovesTheEngineListener() throws Exception {
        assertEquals(2L, lendingState(client()).get("engineListeners"));
        // What the attached, idle agent runs in the application: daemon threads named skillroy-* only.
        List<Map<?, ?>> running = agentThreads();
        assertTrue(running.stream().anyMatch(t -> "skillroy-http-accept".equals(t.get("name"))),
                String.valueOf(running));
        for (Map<?, ?> thread : running) {
            assertEquals(Boolean.TRUE, thread.get("daemon"), "an agent thread that is not a daemon: " + thread);
        }
        Set<Object> oldThreadIds = new HashSet<>();
        running.forEach(t -> oldThreadIds.add(t.get("id")));
        String oldNonce = target.endpoint.nonce();
        // A request signed for this agent start, as anyone watching the loopback could capture it: accepted now.
        String token = target.endpoint.token();
        String oldSession = String.valueOf(client().ping().get("sessionId"));
        byte[] captured = RawHttp.rpcBody("captured-1", "agent.info");
        String capturedNonce = Auth.newNonce();
        String capturedAuthorization = Auth.requestAuthorization(token, oldSession, capturedNonce, "POST", "/rpc",
                Auth.sha256hex(captured));
        RawHttp.Reply original = RawHttp.post(target.endpoint.port(), "/rpc", capturedAuthorization, captured);
        assertEquals(200, original.status(), String.valueOf(original));
        assertTrue(original.verifies(token, oldSession, capturedNonce), String.valueOf(original));
        disableAndAwaitStopped();
        assertTrue(target.launched.process().isAlive(), "the application keeps running");

        Endpoint fresh = attachFresh();
        assertNotEquals(oldNonce, fresh.nonce(), "a new agent");
        assertEquals(LoadMode.ATTACH, fresh.loadMode());
        target.endpoint = fresh;
        target.client = AgentClient.connect(fresh);
        LendingTarget.awaitReady(client());
        afterReattach();
        // Hardening H2: the new agent start has a new session, so the captured request is worthless against it
        // whatever the token is; it is refused before anything in it is used.
        String newSession = String.valueOf(client().ping().get("sessionId"));
        assertNotEquals(oldSession, newSession, "every agent start has its own session");
        assertUnauthorized(RawHttp.post(fresh.port(), "/rpc", capturedAuthorization, captured),
                "a request captured from the disabled agent, replayed against the new one");
        assertFalse(client().isLost(), "the replay did not disturb the new client");
        // The new agent's own listener is attached (that is what makes lending.input OK), so a count of two is the
        // window's listener plus the new agent's: the disabled agent removed its own.
        assertTrue(client().capabilities().isOk("lending.input"), String.valueOf(client().capabilities()
                .get("lending.input")));
        assertEquals(2L, lendingState(client()).get("engineListeners"),
                "the window's listener and the new agent's: the disabled agent removed its own");
        // Nothing of the disabled agent is left running in the application: not one of its threads is alive
        // (thread ids are never reused within a JVM), and there is one control server, daemons only.
        awaitCondition("the disabled agent's threads to end", Duration.ofSeconds(60),
                () -> threads(null).stream().noneMatch(t -> oldThreadIds.contains(t.get("id")))
                        && agentThreads().stream().filter(t -> "skillroy-http-accept".equals(t.get("name")))
                                .count() == 1
                        && agentThreads().stream().noneMatch(t -> "skillroy-lending-detach".equals(t.get("name"))));
        for (Map<?, ?> thread : agentThreads()) {
            assertEquals(Boolean.TRUE, thread.get("daemon"), "an agent thread that is not a daemon: " + thread);
        }
        // The application still works: a loan through its inputs, and one through its window (swing.type into the
        // entry field, swing.click on the keypad keys).
        for (String id : List.of("basic-loan", "basic-loan-ui")) {
            Runs.Outcome after = Runs.run(client(), Runs.committed(id),
                    ItEnv.runDir(mode().slug, id + "-after-disable"));
            Runs.assertPassedWithoutSleeping(after);
            List<Map<String, Object>> log = Runs.runLog(after.dir());
            if (id.equals("basic-loan-ui")) {
                Runs.assertComponentClicksAtFullFidelity(after.dir(), UI_CLICKS);
                assertTrue(log.stream().anyMatch(r -> "swing.click".equals(r.get("primitive"))
                        && Boolean.TRUE.equals(r.get("ok"))) && log.stream().anyMatch(r -> "swing.type".equals(
                        r.get("primitive")) && Boolean.TRUE.equals(r.get("ok"))), "driven through the window");
            }
        }
        assertSignedOff();
    }

    // ------------------------------------------------------------------ a prompt left open

    /**
     * A desk left with a question open is not waiting for input, which is the only moment it lets a listener be
     * added. An agent that arrives then must still become ready (or nothing could ever answer the question), say
     * that {@code lending.input} is not available yet, and let the pack's recovery answer the prompt; the listener is
     * attached as soon as the desk waits for input again and the next scenario passes.
     */
    @Test
    @Order(190)
    void anAgentAttachedWhileAPromptIsOpenIsReadyAndThePackRecovers() throws Exception {
        send("Entry", "100");
        assertEquals("IDLE", send("Entry", "1111").payload().get("state"));
        RpcResult scan = client().call("lending.input", Map.of("source", "Barcode", "value", "0000000000000"), CALL);
        assertNotNull(client().await("prompt.waiting", scan.seqBefore(), null, WAIT), "the prompt is open");
        disableAndAwaitStopped();
        assertTrue(target.launched.process().isAlive(), "the application keeps running");

        Endpoint fresh = attachFresh();
        target.endpoint = fresh;
        target.client = AgentClient.connect(fresh);
        LendingTarget.awaitReady(client());
        afterReattach();
        CapabilityMatrix caps = client().capabilities();
        assertEquals(CapabilityState.UNAVAILABLE, caps.get("lending.input").state(), String.valueOf(caps.get(
                "lending.input")));
        assertTrue(caps.get("lending.input").reason().contains("prompt"), caps.get("lending.input").reason());
        assertTrue(caps.isOk("lending.prompt") && caps.isOk("lending.prompt.answer") && caps.isOk("lending.state"),
                String.valueOf(caps.all()));
        Map<?, ?> prompt = (Map<?, ?>) client().call("lending.prompt", Map.of(), CALL).value();
        assertNotNull(prompt, "the prompt is still open");
        assertEquals("unknown-item", prompt.get("id"));

        Runs.Outcome blocked = Runs.run(client(), Runs.committed("basic-loan"),
                ItEnv.runDir(mode().slug, "basic-loan-prompt-open"));
        assertEquals(Status.ERROR, blocked.result().status(), blocked.describe());
        assertEquals(FailureKind.PRECONDITION_FAILED, blocked.result().failureKind(), blocked.describe());
        assertTrue(blocked.result().message().contains("lending.input") && blocked.result().message().contains(
                "prompt"), blocked.result().message());
        // Phase 2 (D30, the project lead's answer Q11): a failure before the start mark sends nothing more to the
        // target, so the run itself neither cleans up nor recovers; the prompt is still open afterwards.
        List<Map<String, Object>> log = Runs.runLog(blocked.dir());
        assertFalse(log.stream().anyMatch(r -> "recover".equals(r.get("type")) || "cleanup".equals(r.get("type"))),
                "no cleanup and no recover before the start mark: " + log);
        assertNotNull(client().call("lending.prompt", Map.of(), CALL).value(), "nothing answered the prompt");
        // The pack's recovery, called as an operator (or the next suite's set-up) would, answers it.
        Runs.PACK.recover(client());
        assertNull(client().call("lending.prompt", Map.of(), CALL).value(), "recover answered the prompt");
        assertSignedOff();
        assertTrue(client().capabilities().isOk("lending.input"), "the listener was attached once the desk waited "
                + "for input: " + client().capabilities().get("lending.input"));
        // The disabled agent could not remove its (inert) listener while the prompt was open; it does so, on its
        // own daemon thread, at the desk's next wait for input. No event reports that, hence the bounded wait.
        awaitCondition("the disabled agent's listener to be removed", Duration.ofSeconds(60),
                () -> Long.valueOf(2L).equals(lendingStateOrNull().get("engineListeners"))
                        && agentThreads().stream().noneMatch(t -> "skillroy-lending-detach".equals(t.get("name"))));

        Runs.Outcome after = Runs.run(client(), Runs.committed("basic-loan"),
                ItEnv.runDir(mode().slug, "basic-loan-after-prompt-open"));
        Runs.assertPassedWithoutSleeping(after);
    }

    // ------------------------------------------------------------------ amendment A25: fixture secrets

    /**
     * Amendment A25: the assistant's password was typed by every scenario of this mode (through {@code lending.input}
     * and through the entry field), yet it appears in no file any run wrote: {@code run.jsonl}, {@code bindings.json},
     * the failure evidence and the JUnit reports, of passing, degraded, failed and recovered runs alike. Every text
     * file below {@code target/it-runs/<mode>/} is read; the only binary files are PNG captures (pixels, no text).
     * JSON files are parsed and every string in them (keys and values) is searched, while their numbers are not: a
     * number there is a time, a count or a sequence number (the run log's {@code "t":1111} is one), and the
     * password, a string in the fixture, is never written as a number. Other files (the JUnit XML) are searched as
     * text. A match is the password as a value of its own, not a part of a longer number such as a time stamp.
     */
    @Test
    @Order(200)
    void thePasswordAppearsInNoRunOutput() throws Exception {
        // The detector itself: it finds the password as a value, in any quoting, and nothing else.
        assertEquals(4, passwordOccurrences("{\"password\":\"1111\"} text=1111; typed 1111. <v>1111</v>").size());
        assertEquals(List.of(), passwordOccurrences("\"t\":1759441111234, 0.11115, 21111, 1111.5, v1.1111"));
        List<String> sample = new ArrayList<>();
        collectStrings(Json.parse("{\"t\":1111,\"params\":{\"text\":\"1111\"},\"m\":[\"x 1111\"]}"), sample);
        assertEquals(2, sample.stream().mapToInt(t -> passwordOccurrences(t).size()).sum(), String.valueOf(sample));
        Path root = ItEnv.runDir(mode().slug, "x").getParent();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        Set<String> kinds = new HashSet<>();
        int text = 0;
        int images = 0;
        List<String> leaks = new ArrayList<>();
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            String name = file.getFileName().toString();
            if (name.endsWith(".png")) {
                assertPng(file);
                images++;
                continue;
            }
            text++;
            kinds.add(name.endsWith(".xml") ? "junit.xml" : name);
            String content = new String(bytes, StandardCharsets.UTF_8);
            List<String> strings = new ArrayList<>();
            if (name.endsWith(".jsonl")) {
                for (String line : content.split("\n")) {
                    if (!line.isBlank()) {
                        collectStrings(Json.parse(line), strings);
                    }
                }
            } else if (name.endsWith(".json")) {
                collectStrings(Json.parse(content), strings);
            } else {
                strings.add(content);
            }
            for (String string : strings) {
                for (String hit : passwordOccurrences(string)) {
                    leaks.add(root.relativize(file) + ": ..." + hit + "...");
                }
            }
        }
        assertEquals(List.of(), leaks, "the password leaked into run outputs");
        assertTrue(kinds.containsAll(List.of("run.jsonl", "bindings.json", "snapshot.json", "app-state.json",
                "dialogs.json", "events.json", "junit.xml")), "every kind of output was looked at: " + kinds);
        // Fix wave (Phase 6 review, FC; finding L3-S3): the files Phase 6 writes were looked at too (phase6-contracts.md
        // 13.2 item 10): every run archives its matrix, and the hooks leg's order 67 writes the CAPTURE events of
        // sign.on.checked below this root. No load mode's root holds spans.jsonl (the telemetry IT writes and scans
        // its own under it-runs/telemetry): a file of that name here would be read like any other.
        assertTrue(kinds.contains("capabilities.json"), "the archived matrices were looked at: " + kinds);
        if (mode() == LendingTarget.Mode.JAVAAGENT_HOOKS) {
            assertTrue(kinds.contains("sign-on-events.json"), "the hooks evidence was looked at: " + kinds);
        }
        assertTrue(text >= 40 && images >= 5, text + " text files, " + images + " images");
        // The password was there to be leaked: it was sent (and recorded masked, with the parameter announced to the
        // agent as secret, A34). Since Phase 2 it comes from credentials.yaml: bindings.json names the credential and
        // holds no password field at all (phase2-contracts.md 8.4).
        Path ui = ItEnv.runDir(mode().slug, "basic-loan-ui");
        List<Map<String, Object>> typedPassword = Runs.calls(ui, "swing.type").stream().filter(c -> SecretKeys.REDACTED
                .equals(((Map<?, ?>) c.get("params")).get("text"))).toList();
        assertEquals(1, typedPassword.size(), "the typed password is recorded masked");
        assertEquals(List.of("text"), typedPassword.get(0).get("secretParams"), "and announced as secret (A34)");
        Map<String, Object> bindings = Json.parseObject(Files.readString(ui.resolve("bindings.json")));
        Map<?, ?> assistant = (Map<?, ?>) ((Map<?, ?>) bindings.get("resolutions")).get("assistant");
        assertEquals(Map.of("realm", "staff", "id", "100"), assistant.get("credential"));
        assertEquals(Map.of("code", "100"), assistant.get("value"), "the joined field is not written");
        // A34: the agent's own log of this target holds the password nowhere either.
        Map<String, Object> agentLog = value(client().call("agent.log", Map.of("n", 2000L), CALL));
        List<String> logLeaks = new ArrayList<>();
        for (Object line : (List<?>) agentLog.get("lines")) {
            logLeaks.addAll(passwordOccurrences(String.valueOf(line)));
        }
        assertEquals(List.of(), logLeaks, "the password leaked into the agent log");
    }

    /** Every string of a parsed JSON value, map keys included, depth first. */
    static void collectStrings(Object value, List<String> out) {
        if (value instanceof String string) {
            out.add(string);
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.add(String.valueOf(e.getKey()));
                collectStrings(e.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectStrings(item, out);
            }
        }
    }

    /**
     * The places in {@code content} where the password stands as a value of its own: the maximal run of digits and
     * dots around an occurrence (a trailing dot ends a sentence) is exactly the password, so a longer number that
     * happens to contain it (a time stamp, a sequence number, a decimal) does not count.
     */
    static List<String> passwordOccurrences(String content) {
        List<String> hits = new ArrayList<>();
        Matcher m = Pattern.compile(Pattern.quote(PASSWORD)).matcher(content);
        while (m.find()) {
            int from = m.start();
            int to = m.end();
            while (from > 0 && isNumberChar(content.charAt(from - 1))) {
                from--;
            }
            while (to < content.length() && isNumberChar(content.charAt(to))) {
                to++;
            }
            String token = content.substring(from, to);
            while (token.endsWith(".")) {
                token = token.substring(0, token.length() - 1);
            }
            while (token.startsWith(".")) {
                token = token.substring(1);
            }
            if (token.equals(PASSWORD)) {
                hits.add(content.substring(Math.max(0, m.start() - 40), Math.min(content.length(), m.end() + 40)));
            }
        }
        return hits;
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.';
    }

    // ------------------------------------------------------------------ amendment A23: stale discovery files

    /**
     * Amendment A23, controller part: the target is killed (no shutdown hook runs, so its agent cannot delete its
     * discovery file). The client notices the process is gone without a request reaching anything; discovery with
     * clean-up switched off lists nothing and deletes nothing; discovery as the CLI runs it deletes the dead
     * process's discovery and token files. Last check of the mode: it ends the target.
     */
    @Test
    @Order(210)
    void theDiscoveryFilesOfAKilledTargetAreRemovedByDiscovery() throws Exception {
        long pid = target.pid();
        Path dir = target.discoveryDir;
        List<Path> files = agentFiles(dir, pid);
        assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().endsWith(".json")),
                "the running agent has its discovery file: " + files);
        assertTrue(Discovery.list(dir, false).stream().anyMatch(e -> e.pid() == pid), "and it is listed");

        Process process = target.launched.process();
        process.destroyForcibly();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the target ended");
        assertThrows(AgentLostException.class, () -> client().call("agent.info", Map.of(), CALL));
        assertTrue(client().isLost(), "the client saw its process gone and is latched");
        assertEquals(files, agentFiles(dir, pid), "a killed JVM leaves its files behind");

        assertTrue(Discovery.list(dir, false).stream().noneMatch(e -> e.pid() == pid), "a dead process is not live");
        assertEquals(files, agentFiles(dir, pid), "with clean-up off, nothing is deleted");
        assertTrue(Discovery.list(dir).stream().noneMatch(e -> e.pid() == pid));
        assertEquals(List.of(), agentFiles(dir, pid), "discovery deleted the dead process's files");
    }

    /** {@code skillroy-<pid>-<startMs>.json} and {@code .token} in {@code dir}, sorted. */
    static List<Path> agentFiles(Path dir, long pid) throws Exception {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(f -> f.getFileName().toString().matches("skillroy-" + pid + "-\\d+\\.(json|token)"))
                    .sorted().toList();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** {@code lending.input}, then the desk's next "waiting" or "asking" event since the call began. */
    Event send(String source, String value) throws Exception {
        RpcResult result = client().call("lending.input", Map.of("source", source, "value", value), CALL);
        assertEquals(Boolean.TRUE, value(result).get("acked"), source + " " + value);
        Event done = client().await(READY_TOPICS, result.seqBefore(), null, WAIT);
        assertNotNull(done, "no input.requested or prompt.waiting after " + source);
        return done;
    }

    /**
     * {@code agent.disable}, then waits until the agent is gone. After its reply the agent uninstalls its modules,
     * stops its server and deletes its discovery file; there is no event left to wait for, so this waits (bounded)
     * for the file to disappear and the port to close. The client that disabled the agent is latched lost at once
     * (hardening H2): it makes no further request, so nothing can reach whatever takes the port next.
     */
    void disableAndAwaitStopped() throws Exception {
        RpcResult disabled = client().call("agent.disable", Map.of(), CALL.withConfirm(true));
        assertEquals(Boolean.TRUE, value(disabled).get("stopping"));
        assertTrue(client().isLost(), "a successful agent.disable latches the client that sent it");
        AgentLostException lost = assertThrows(AgentLostException.class, () -> client().call("agent.info",
                Map.of(), CALL));
        assertNotNull(lost.getMessage());
        assertThrows(AgentLostException.class, () -> client().ping());
        client().close();
        awaitCondition("the agent to stop", Duration.ofSeconds(60), () -> Discovery.forPid(target.pid(),
                target.discoveryDir).isEmpty() && !answers(target.endpoint));
    }

    /** The target's threads whose name contains {@code skillroy-}: every thread an agent in it owns. */
    List<Map<?, ?>> agentThreads() {
        return threads("skillroy-");
    }

    /** The target's live threads whose name contains {@code nameContains} (null: all of them). */
    List<Map<?, ?>> threads(String nameContains) {
        try {
            Map<String, Object> threads = value(client().call("jvm.threads", nameContains == null ? Map.of()
                    : Map.of("nameContains", nameContains), CALL));
            List<Map<?, ?>> list = new ArrayList<>();
            for (Object t : (List<?>) threads.get("threads")) {
                list.add((Map<?, ?>) t);
            }
            return list;
        } catch (AgentRpcException | AgentLostException e) {
            throw new AssertionError("jvm.threads failed: " + e, e);
        }
    }

    /** {@link #lendingState} for use in a condition. */
    Map<String, Object> lendingStateOrNull() {
        try {
            return lendingState(client());
        } catch (Exception e) {
            throw new AssertionError("app.state failed: " + e, e);
        }
    }

    void assertSignedOff() throws Exception {
        Map<String, Object> state = value(client().call("lending.state", Map.of(), CALL));
        assertEquals("SIGNED_OFF", state.get("name"), String.valueOf(state));
        assertEquals(Boolean.TRUE, state.get("awaitingInput"), String.valueOf(state));
    }

    static Map<String, Object> lendingState(AgentClient agent) throws Exception {
        Map<String, Object> appState = value(agent.call("app.state", Map.of(), CALL));
        @SuppressWarnings("unchecked")
        Map<String, Object> lending = (Map<String, Object>) appState.get("lending");
        assertNotNull(lending, String.valueOf(appState));
        return lending;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> value(RpcResult result) {
        return (Map<String, Object>) result.value();
    }

    /**
     * The file is a PNG that decodes and is a painted window, not a blank rectangle: a blank image compresses to a
     * few kilobytes too, so the size alone proves nothing.
     */
    static BufferedImage assertPng(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file), file + " exists");
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(Arrays.equals(Arrays.copyOf(bytes, 8), new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a,
                '\n'}), file + " is a PNG");
        BufferedImage image = ImageIO.read(file.toFile());
        assertNotNull(image, file + " decodes");
        assertTrue(image.getWidth() >= 100 && image.getHeight() >= 50, file + " is " + image.getWidth() + " x "
                + image.getHeight());
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                colours.add(image.getRGB(x, y));
            }
        }
        assertTrue(colours.size() >= 8, file + " shows painted content, not a blank window: " + colours.size()
                + " colours");
        return image;
    }

    static boolean answers(Endpoint endpoint) {
        try (AgentClient c = AgentClient.connect(endpoint)) {
            return true;
        } catch (AgentLostException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Attaches a fresh agent after {@code agent.disable}, once: the bootstrap lets a stop that is still finishing
     * complete before it starts the next agent, so no retry is needed (and none may hide a failure).
     */
    Endpoint attachFresh() throws Exception {
        return target.attach();
    }

    /**
     * Waits for a condition that has no event: at most {@code timeout}, re-checking every 50 ms or as soon as the
     * target exits.
     */
    void awaitCondition(String what, Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        CountDownLatch exited = new CountDownLatch(1);
        target.launched.process().onExit().thenRun(exited::countDown);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                throw new AssertionError("timed out after " + timeout.toMillis() + " ms waiting for " + what);
            }
            if (exited.await(50, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("the target exited while waiting for " + what);
            }
        }
    }
}
