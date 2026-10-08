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

package ai.skillroy.testjava.samples.webhost.scenarios;

import ai.skillroy.testjava.cdp.CdpAttach;
import ai.skillroy.testjava.cdp.CdpEndpoint;
import ai.skillroy.testjava.cdp.CdpException;
import ai.skillroy.testjava.cdp.CdpOptions;
import ai.skillroy.testjava.cdp.CdpWebPath;
import ai.skillroy.testjava.cdp.TargetProbe;
import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.samples.webhost.WebHostOptions;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.web.FrozenTargetPolicy;
import ai.skillroy.testjava.scenario.web.WebPathOpenException;
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Item 6 of phase4-contracts.md 12.2, the BS5 criterion (D53), once per target Java against the web host started off
 * screen with a remote-debugging port and the agent on the command line. The {@code back} page is frozen by an
 * endless loop, or stalled by busy chunks with a yield between them, which the test evaluates in the page itself
 * (posted with a zero-delay timer, so that the evaluation answers first): the page's own {@code #freeze} and
 * {@code #stall} fragments cannot be reached, because Chromium leaves the fragment of a {@code data:} URL document as
 * it is ({@code location.hash = '#freeze'} has no effect on the host's pages; seen against JCEF 152 on 2026-10-07).
 *
 * <ol>
 *   <li>Stalled: the {@code reload} policy recovers the page ({@code web.reload} through the agent, a clean second
 *       probe, the attach succeeds, a {@code web.recover} record with {@code ok: true}); and W1 with
 *       {@code web: {frozenTargets: reload}} passes over CDP against a host whose back page is stalled.</li>
 *   <li>Frozen: 50 times {@code CdpAttach.connect} with the default policy ends within {@code connectDeadline} with
 *       {@code FROZEN_TARGETS} naming the back target (each attempt timed, the slowest recorded).</li>
 *   <li>Frozen: the {@code reload} policy cannot recover an endless loop: Chromium does not commit a reload into a
 *       renderer whose main thread never yields, so no load follows the agent's {@code web.reload} and the second
 *       probe still finds the target frozen; {@code CdpWebPath.open} ends with {@code FROZEN_TARGETS} after one
 *       {@code web.recover} record (the reload itself was sent: {@code ok: true}).</li>
 *   <li>Frozen: with the probe left out, Playwright's connect against the frozen target is ended by the external
 *       deadline: {@code TIMEOUT} within the deadline plus 5 s, and the driver ({@code node}) is gone.</li>
 * </ol>
 * Nothing thaws an endless loop short of ending the host: a reload is not committed, and a new DevTools session (a
 * probe, Playwright, a {@code Runtime.terminateExecution} sent over the port) is never served by the busy renderer
 * (seen against JCEF 152 on 2026-10-07); the frozen parts therefore come last and the host is ended afterwards. Needs
 * a display (skipped when headless). Every wait is bounded: the probes run on their own deadlines, a reload is
 * awaited as the agent's {@code web.ready}, the driver's end as its process' exit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CdpAttachSoakIT {

    static final int ATTEMPTS = 50;
    static final CallOptions CALL = CallOptions.defaults().withTimeoutMs(30_000L);
    static final Duration FREEZE_WAIT = Duration.ofSeconds(60);
    /** What the page's {@code #freeze} does (flag, log, a busy loop for ever), after a zero-delay timer. */
    static final String FREEZE = "(setTimeout(function () { window.webhostFrozen = true;"
            + " console.log('webhost back frozen'); for (;;) { } }, 0), 'freezing')";
    /**
     * What the page's {@code #stall} does, with chunks of {@value #STALL_CHUNK_MS} ms (the page's own are 30 s, longer
     * than the reload policy waits): unresponsive to a 2 s probe, yet a navigation is taken at the next yield.
     */
    static final String STALL = "(setTimeout(function stall() { var end = Date.now() + 5000;"
            + " while (Date.now() < end) { } setTimeout(stall, 0); }, 0), 'stalling')";
    static final long STALL_CHUNK_MS = 5000;

    WebHostTarget target;
    CdpEndpoint endpoint;
    String java;

    @BeforeAll
    void launch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display: run under ci/run-in-linux.sh with "
                + "-Dskillroy.headless=false");
        ItEnv.warmUpPlaywright();
        target = WebHostTarget.launch(WebHostTarget.Mode.JAVAAGENT, WebHostOptions.Rendering.OSR, true);
        endpoint = CdpEndpoint.local(target.port);
        java = String.valueOf(WebHostChecks.value(client().call("jvm.info", Map.of(), CALL)).get("javaVersion"));
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

    /** Item 6, first part: every attempt against the frozen target ends in time with FROZEN_TARGETS. */
    @Test
    @Order(2)
    void fiftyAttachAttemptsAgainstAFrozenTargetEndWithinTheDeadline() throws Exception {
        String backTarget = blockBackAndAwaitUnresponsive(FREEZE);
        CdpOptions options = CdpOptions.defaults();
        long slowest = 0;
        long total = 0;
        for (int i = 0; i < ATTEMPTS; i++) {
            long t0 = System.nanoTime();
            CdpException e = assertThrows(CdpException.class, () -> CdpAttach.connect(endpoint, options,
                    TargetProbe.run(endpoint, options)).close(), "attempt " + (i + 1));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals(CdpException.Kind.FROZEN_TARGETS, e.kind(), "attempt " + (i + 1) + ": " + e);
            assertTrue(e.targets().stream().anyMatch(t -> t.id().equals(backTarget)), "names the back target: "
                    + e.targets());
            assertTrue(ms <= options.connectDeadline().toMillis(), "attempt " + (i + 1) + " took " + ms + " ms");
            slowest = Math.max(slowest, ms);
            total += ms;
        }
        ItEnv.timing("java=" + java + " frozen-target attach attempts=" + ATTEMPTS + " slowestMs=" + slowest
                + " meanMs=" + total / ATTEMPTS + " connectDeadlineMs=" + options.connectDeadline().toMillis());
    }

    /**
     * Item 6, the reload policy against an endless loop: the agent's {@code web.reload} is sent and recorded, but no
     * load follows and the second probe still finds the target frozen, so the open ends with {@code FROZEN_TARGETS}.
     */
    @Test
    @Order(3)
    void theReloadPolicyCannotRecoverAnEndlessLoop() throws Exception {
        String backRef = ref("back");
        String backTarget = blockBackAndAwaitUnresponsive(FREEZE);
        List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
        long t0 = System.nanoTime();
        WebPathOpenException e = assertThrows(WebPathOpenException.class, () -> openWithReload(records,
                "reload-frozen"));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        ItEnv.timing("java=" + java + " reload policy against an endless loop: " + e.kind() + " after " + ms
                + " ms; records " + records);
        assertEquals(WebPathOpenException.Kind.FROZEN_TARGETS, e.kind(), String.valueOf(e));
        List<Map<String, Object>> recover = records.stream().filter(r -> "web.recover".equals(r.get("type")))
                .toList();
        assertEquals(1, recover.size(), String.valueOf(records));
        assertEquals(backRef, recover.get(0).get("browser"), String.valueOf(recover));
        assertEquals("reload", recover.get(0).get("action"));
        assertTrue(frozen(TargetProbe.run(endpoint, CdpOptions.defaults()), backTarget), "still frozen");
    }

    /**
     * Item 6, the reload policy against a stalled page: {@code CdpWebPath.open} reloads it, the second probe is clean
     * and the attach succeeds; then W1 with {@code web: {frozenTargets: reload}} against a host whose back page is
     * stalled passes over CDP, the recovery in its {@code run.jsonl}.
     */
    @Test
    @Order(1)
    void theReloadPolicyRecoversAStalledPageAndW1PassesOverCdp() throws Exception {
        String backRef = ref("back");
        blockBackAndAwaitUnresponsive(STALL);
        List<Map<String, Object>> records = new CopyOnWriteArrayList<>();
        long t0 = System.nanoTime();
        String reason = openWithReload(records, "reload-stalled");
        ItEnv.timing("java=" + java + " reload policy against a stalled page: opened after " + (System.nanoTime()
                - t0) / 1_000_000L + " ms; records " + records);
        assertNull(reason, "the path opened after the recovery: " + records);
        List<Map<String, Object>> recover = records.stream().filter(r -> "web.recover".equals(r.get("type")))
                .toList();
        assertEquals(1, recover.size(), String.valueOf(records));
        assertEquals(backRef, recover.get(0).get("browser"), String.valueOf(recover));
        assertEquals("reload", recover.get(0).get("action"));
        assertEquals(Boolean.TRUE, recover.get(0).get("ok"), String.valueOf(recover));

        blockBackAndAwaitUnresponsive(STALL);
        Path yaml = ItEnv.runDir("soak", "scenarios").resolve("borrow-in-page-reload.yaml");
        Files.createDirectories(yaml.getParent());
        String text = Files.readString(ItEnv.scenario("borrow-in-page.yaml"), StandardCharsets.UTF_8);
        String pinned = text.replace("web: { browser: { tag: front } }",
                "web: { browser: { tag: front }, frozenTargets: reload }");
        assertTrue(!pinned.equals(text), "the scenario's web key was found");
        Files.writeString(yaml, pinned, StandardCharsets.UTF_8);
        Plan plan = PlanCompiler.compile(yaml, Runs.PACK);
        assertEquals(FrozenTargetPolicy.RELOAD, plan.effectiveWeb().frozenTargets());
        Path run = ItEnv.runDir("soak", "borrow-in-page-reload");
        Runs.Outcome o = Runs.run(client(), plan, run);
        Runs.assertPassedWithoutSleeping(o);
        assertEquals("cdp", Runs.records(run, "web.path").get(0).get("path"), o.describe());
        List<Map<String, Object>> recovered = Runs.records(run, "web.recover");
        assertEquals(1, recovered.size(), String.valueOf(recovered));
        assertEquals(Boolean.TRUE, recovered.get(0).get("ok"), String.valueOf(recovered));
    }

    /**
     * Item 6, third part: without the probe, Playwright's connect against the frozen target hangs until the external
     * deadline ends it ({@code TIMEOUT}), and the driver it started is gone afterwards.
     */
    @Test
    @Order(4)
    void withoutTheProbeTheExternalDeadlineEndsTheConnectAndTheDriver() throws Exception {
        String backTarget = blockBackAndAwaitUnresponsive(FREEZE);
        CdpOptions options = CdpOptions.defaults().withConnectDeadline(Duration.ofSeconds(10));
        long t0 = System.nanoTime();
        CdpException e = assertThrows(CdpException.class, () -> CdpAttach.connect(endpoint, options, null)
                .close());
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        ItEnv.timing("java=" + java + " probe-less connect ended ms=" + ms + " kind=" + e.kind());
        assertEquals(CdpException.Kind.TIMEOUT, e.kind(), String.valueOf(e));
        assertTrue(ms <= 15_000, "ended within the deadline and 5 s: " + ms + " ms");
        // The driver's end is a process event: wait for each (bounded) instead of polling.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (ProcessHandle p : ProcessHandle.current().descendants().filter(CdpAttachSoakIT::isDriver).toList()) {
            try {
                p.onExit().get(Math.max(1, (deadline - System.nanoTime()) / 1_000_000L), TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillThere) {
                // reported below
            }
        }
        assertEquals(List.of(), drivers(), "the driver is gone");
        assertTrue(frozen(TargetProbe.run(endpoint, CdpOptions.defaults()), backTarget), "the page stays frozen");
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** {@code CdpWebPath.open} with {@code path: cdp} and {@code frozenTargets: reload}; the path is closed again. */
    String openWithReload(List<Map<String, Object>> records, String what) throws Exception {
        Path dir = ItEnv.runDir("soak", what);
        Files.createDirectories(dir);
        WebRunContext ctx = new WebRunContext() {
            @Override
            public Path artifactsDir() {
                return dir;
            }

            @Override
            public void record(Map<String, Object> record) {
                records.add(record);
            }
        };
        CdpWebPath path = new CdpWebPath();
        try {
            return path.open(client(), new WebSettings(WebSettings.Path.CDP, null, FrozenTargetPolicy.RELOAD,
                    Duration.ofSeconds(30), false), ctx);
        } finally {
            path.close();
        }
    }

    String ref(String tag) throws Exception {
        for (Map<String, Object> b : WebHostChecks.browsers(client())) {
            if (tag.equals(b.get("tag"))) {
                return String.valueOf(b.get("ref"));
            }
        }
        throw new AssertionError("no browser tagged " + tag);
    }

    String targetId(String tag) throws Exception {
        for (Map<String, Object> b : WebHostChecks.browsers(client())) {
            if (tag.equals(b.get("tag"))) {
                Object id = b.get("cdpTargetId");
                assertNotNull(id, "the " + tag + " browser's target id: " + b.get("ref"));
                return String.valueOf(id);
            }
        }
        throw new AssertionError("no browser tagged " + tag);
    }

    /**
     * Evaluates {@code block} ({@link #FREEZE} or {@link #STALL}) in the back page unless a probe already finds it
     * unresponsive, then repeats the probe (each run bounded by its per-target deadline) until it reports the back
     * target unresponsive, at most {@link #FREEZE_WAIT}.
     *
     * @return the back page's target id
     */
    String blockBackAndAwaitUnresponsive(String block) throws Exception {
        String backTarget = targetId("back");
        CdpOptions options = CdpOptions.defaults();
        if (frozen(TargetProbe.run(endpoint, options), backTarget)) {
            return backTarget;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("by", Map.of("tag", "back"));
        params.put("expression", block);
        client().call("web.evaluate", params, CALL);
        long deadline = System.nanoTime() + FREEZE_WAIT.toNanos();
        TargetProbe probe;
        do {
            probe = TargetProbe.run(endpoint, options);
            if (frozen(probe, backTarget)) {
                return backTarget;
            }
        } while (System.nanoTime() - deadline < 0);
        throw new AssertionError("the back page did not stop answering within " + FREEZE_WAIT + ": " + probe);
    }

    static boolean frozen(TargetProbe probe, String targetId) {
        return probe.unresponsive().stream().anyMatch(t -> t.id().equals(targetId));
    }

    /** Playwright's driver processes among this JVM's descendants (a {@code node} executable). */
    static List<String> drivers() {
        List<String> out = new ArrayList<>();
        for (ProcessHandle p : ProcessHandle.current().descendants().toList()) {
            if (isDriver(p)) {
                out.add(p.pid() + " " + p.info().command().orElse("?"));
            }
        }
        return out;
    }

    static boolean isDriver(ProcessHandle p) {
        String command = p.info().command().orElse("");
        return p.isAlive() && (command.endsWith("/node") || command.endsWith("\\node.exe"));
    }
}
