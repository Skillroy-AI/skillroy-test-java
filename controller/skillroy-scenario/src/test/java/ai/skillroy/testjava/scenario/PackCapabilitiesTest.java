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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.pack.CheckScope;
import ai.skillroy.testjava.scenario.pack.PackCapability;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase8-contracts.md 13.1 row 42 (9.1, gap 106): the pack's capabilities evaluated by the executor after the
 * contributors' merge and before {@code requires}, honoured in {@code replay}, guarding and required like any id; the
 * declaration rules refusing before anything is evaluated; the failure path (an exception's text in the record's
 * {@code error} only, null, another id, an interruption); the source {@code pack:toy}; the {@code capabilities}
 * record's {@code pack} list and its absence; a {@code SUITE} capability over the suite cache; the session's open.
 */
class PackCapabilitiesTest {

    static final String CEILING = "toy.returns.ceiling";
    static final String CEILING_SETTING = "max_return";

    @TempDir
    Path tmp;

    private final ToyResolution toy = new ToyResolution();
    private final ToyPack pack = toy.pack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();

    @BeforeEach
    void toyApplication() {
        toy.install(agent);
        agent.capabilities("toy.input", "toy.state");
        agent.value("toy.poke", Map.of());
        agent.value("toy.state", Map.of("name", "IDLE"));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Reads the {@code setting} row named {@link #CEILING_SETTING} through the run's data channel, as the sample does. */
    static final class Ceiling implements PackCapability {
        final AtomicInteger evaluations = new AtomicInteger();
        final CheckScope scope;

        Ceiling(CheckScope scope) {
            this.scope = scope;
        }

        @Override
        public String id() {
            return CEILING;
        }

        @Override
        public String description() {
            return "the toy desk has a maximum return";
        }

        @Override
        public CheckScope scope() {
            return scope;
        }

        @Override
        public Capability evaluate(PreflightCheck.PreflightContext ctx) throws Exception {
            evaluations.incrementAndGet();
            Rows rows;
            try {
                rows = ctx.data().query(ToyResolution.SOURCE, ToyResolution.LIMIT_SQL, List.of(CEILING_SETTING), 1,
                        Duration.ofSeconds(10));
            } catch (DataException e) {
                return new Capability(CEILING, CapabilityState.UNAVAILABLE, "the toy database could not be read",
                        null);
            }
            return rows.rows().size() == 1 ? new Capability(CEILING, CapabilityState.OK, null, null)
                    : new Capability(CEILING, CapabilityState.UNAVAILABLE, "no maximum return configured (setting "
                    + CEILING_SETTING + ")", null);
        }
    }

    /** A capability whose evaluation the test scripts. */
    interface Body {
        Capability evaluate(PreflightCheck.PreflightContext ctx) throws Exception;
    }

    static PackCapability capability(String id, CheckScope scope, AtomicInteger count, Body body) {
        return new PackCapability() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public CheckScope scope() {
                return scope;
            }

            @Override
            public Capability evaluate(PreflightCheck.PreflightContext ctx) throws Exception {
                count.incrementAndGet();
                return body.evaluate(ctx);
            }
        };
    }

    static PackCapability ok(String id, AtomicInteger count) {
        return capability(id, CheckScope.SCENARIO, count, ctx -> new Capability(id, CapabilityState.OK, null, null));
    }

    private RunOptions options(BindMode mode, Path out) {
        return RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withOutputDir(out)
                .withBind(TestCredentials.bind().withMode(mode).withEnvId("seed-a")
                        .withBindingsRoot(tmp.resolve("bindings")));
    }

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private RunResult run(Plan plan, RunOptions options) {
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    private RunResult run(Plan plan, RunOptions options, ClassLoader contributors) {
        return new PlanExecutor(pack, agent, options, contributors).run(plan);
    }

    static List<Map<String, Object>> log(Path dir) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            records.add(Json.parseObject(line));
        }
        return records;
    }

    static Map<String, Object> record(Path dir, String type) throws Exception {
        return log(dir).stream().filter(r -> type.equals(r.get("type"))).findFirst().orElseThrow();
    }

    static List<String> types(Path dir) throws Exception {
        return log(dir).stream().map(r -> String.valueOf(r.get("type"))).toList();
    }

    private List<String> methods() {
        return agent.calls.stream().map(FakeAgentConnection.Call::method).toList();
    }

    private ClassLoader contributors(Class<?>... classes) throws Exception {
        Path dir = tmp.resolve("services-" + System.nanoTime());
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        StringBuilder text = new StringBuilder();
        for (Class<?> c : classes) {
            text.append(c.getName()).append('\n');
        }
        Files.writeString(services.resolve(CapabilityContributor.class.getName()), text.toString());
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, getClass().getClassLoader());
    }

    /** Answers inference.vision OK: a contributor whose entry is merged before the pack's. */
    public static final class VisionOk implements CapabilityContributor {
        @Override
        public String id() {
            return "vision";
        }

        @Override
        public Map<String, Capability> capabilities(CapabilityContext ctx) {
            return Map.of("inference.vision", new Capability("inference.vision", CapabilityState.OK, null, "v1"));
        }
    }

    // ------------------------------------------------------------------------------------------------ the run

    @Test
    void anIdIsEvaluatedAfterTheContributorsAndBeforeRequiresWithReadsOnly() throws Exception {
        Ceiling ceiling = new Ceiling(CheckScope.SCENARIO);
        pack.capabilities = List.of(ceiling);
        Path out = tmp.resolve("live");
        RunResult live = run(plan("requires: [" + CEILING + ", inference.vision]\nsteps:\n  - poke: {}\n"),
                options(BindMode.LIVE, out), contributors(VisionOk.class));
        assertEquals(Status.PASS, live.status(), live.message());
        assertEquals(1, ceiling.evaluations.get());
        List<String> types = types(out);
        assertEquals(List.of("run-start", "hooks", "capabilities", "requires"), types.subList(0, 4),
                "the records' order is unchanged");
        Map<String, Object> record = record(out, "capabilities");
        assertEquals(List.of("inference.vision"), record.get("contributed"));
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("id", CEILING);
        entry.put("state", "OK");
        entry.put("reason", null);
        entry.put("strategy", null);
        entry.put("scope", "scenario");
        entry.put("cached", false);
        assertEquals(List.of(entry), record.get("pack"), "{id, state, reason, strategy, scope, cached}");
        CapabilityMatrix archived = CapabilityMatrix.fromJson(Json.parseObject(Files.readString(out.resolve(
                PlanExecutor.CAPABILITIES_FILE))));
        assertEquals(new Capability(CEILING, CapabilityState.OK, null, null), archived.get(CEILING));
        assertEquals(archived.hash(), record.get("hash"), "the archived matrix is the merged one");
        assertEquals(agent.capabilities().hash(), record.get("agentHash"), "the agent's matrix, without the pack's");

        agent.calls.clear();
        RunResult unmet = run(plan("requires: [missing.cap]\nsteps:\n  - poke: {}\n"), options(BindMode.LIVE, null));
        assertEquals(FailureKind.PRECONDITION_FAILED, unmet.failureKind(), unmet.message());
        assertEquals(List.of("data.sources", "data.query"), methods(),
                "the pack's reads are the only calls before requires, and nothing is sent after it");
    }

    @Test
    void aPackCapabilityIsHonouredInReplay() throws Exception {
        pack.capabilities = List.of(new Ceiling(CheckScope.SCENARIO));
        Path out = tmp.resolve("replay");
        Plan plan = plan("requires: [" + CEILING + "]\nsteps:\n  - poke: {}\n  - expect: { \"caps[id=" + CEILING
                + "].source\": \"pack:toy\", \"caps[id=" + CEILING + "].state\": OK, \"caps[id=inference.vision]"
                + ".state\": UNAVAILABLE }\n");
        RunResult replay = run(plan, options(BindMode.REPLAY, out), contributors(VisionOk.class));
        assertEquals(Status.PASS, replay.status(), replay.message() + " " + replay.skippedExpectations());
        CapabilityMatrix archived = CapabilityMatrix.fromJson(Json.parseObject(Files.readString(out.resolve(
                PlanExecutor.CAPABILITIES_FILE))));
        assertEquals(CapabilityState.OK, archived.get(CEILING).state(), "OK kept in replay (D610)");
        assertEquals(new Capability("inference.vision", CapabilityState.UNAVAILABLE, "replay", null),
                archived.get("inference.vision"), "a contributed id is forced UNAVAILABLE as before (D326)");
        assertFalse(Files.readString(out.resolve(PlanExecutor.CAPABILITIES_FILE)).contains("pack:"),
                "the wire shape carries no source");
    }

    @Test
    void aGuardOnAnUnavailablePackIdSkipsItsCheck() throws Exception {
        toy.settings.remove(CEILING_SETTING);
        pack.capabilities = List.of(new Ceiling(CheckScope.SCENARIO));
        RunResult result = run(plan("steps:\n  - poke: {}\n  - expect: { \"state.name\": { equals: IDLE, capability: "
                + CEILING + " } }\n"), options(BindMode.LIVE, null));
        assertEquals(Status.DEGRADED, result.status(), result.message());
        assertEquals(List.of("s2: state.name {\"equals\":\"IDLE\"} skipped: capability " + CEILING + " is UNAVAILABLE (no maximum"
                + " return configured (setting " + CEILING_SETTING + "))"), result.skippedExpectations());
    }

    @Test
    void requiringAnUnavailablePackIdIsAPreconditionFailure() throws Exception {
        toy.settings.remove(CEILING_SETTING);
        pack.capabilities = List.of(new Ceiling(CheckScope.SCENARIO));
        Path out = tmp.resolve("unmet");
        RunResult result = run(plan("requires: [" + CEILING + "]\nsteps:\n  - poke: {}\n"), options(BindMode.REPLAY,
                out));
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.PRECONDITION_FAILED, result.failureKind());
        assertEquals("unmet requirements: " + CEILING + ": UNAVAILABLE (no maximum return configured (setting "
                + CEILING_SETTING + "))", result.message());
        assertEquals(List.of("data.sources", "data.query"), methods(), "nothing is sent after the reads");
        assertEquals(List.of("run-start", "hooks", "capabilities", "requires", "ledger", "run-end"), types(out));
    }

    @Test
    void theDeclarationRulesRefuseBeforeAnythingIsEvaluated() throws Exception {
        AtomicInteger count = new AtomicInteger();
        Plan plan = plan("steps:\n  - poke: {}\n");
        pack.capabilitiesFailure = new IllegalStateException("no list");
        assertRefused(plan, "pack toy: its capabilities cannot be read: java.lang.IllegalStateException: no list");
        pack.capabilitiesFailure = null;

        List<PackCapability> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            many.add(ok("toy.c" + i, count));
        }
        pack.capabilities = many;
        assertRefused(plan, "pack toy declares 65 capabilities; at most 64");
        pack.capabilities = many.subList(0, 64);
        assertEquals(Status.PASS, run(plan, options(BindMode.LIVE, null)).status(), "64 is the bound");
        assertEquals(64, count.getAndSet(0));

        for (String id : java.util.Arrays.asList(null, "", " ", "toy", "toy.", "other.ready", "toy.Ready", "toy.a..b",
                "toy.a.", "toy.-a", "toy.a_b", "inference.vision", "toy." + "a".repeat(125))) {
            pack.capabilities = List.of(ok(id, count));
            assertRefused(plan, "pack toy declares the capability '" + id + "': a pack capability id is"
                    + " <packId>.<name>, name [a-z0-9]+([.-][a-z0-9]+)*");
        }
        pack.capabilities = List.of(ok("toy." + "a".repeat(124), count));
        assertEquals(Status.PASS, run(plan, options(BindMode.LIVE, null)).status(), "128 characters is the bound");
        assertEquals(1, count.getAndSet(0));
        agent.calls.clear();

        pack.capabilities = java.util.Arrays.asList(ok("toy.ready", count), null);
        assertRefused(plan, "pack toy declares the capability 'null': a pack capability id is <packId>.<name>, name"
                + " [a-z0-9]+([.-][a-z0-9]+)*");
        pack.capabilities = List.of(ok("toy.ready", count), ok("toy.ready-2.x", count), ok("toy.ready", count));
        assertRefused(plan, "pack toy declares the capability 'toy.ready' twice");
        pack.capabilities = List.of(ok("toy.ready", count), ok("toy.state", count));
        assertRefused(plan, "capability 'toy.state' is declared by the agent and by pack toy");
        assertEquals(0, count.get(), "nothing was evaluated");
        assertTrue(agent.calls.isEmpty(), "nothing was sent: " + methods());
    }

    private void assertRefused(Plan plan, String message) throws Exception {
        Path out = tmp.resolve("refused-" + System.nanoTime());
        RunResult result = run(plan, options(BindMode.LIVE, out));
        assertEquals(Status.ERROR, result.status(), message);
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind(), result.message());
        assertEquals(message, result.message());
        Map<String, Object> record = record(out, "capabilities");
        assertEquals(false, record.get("ok"));
        assertEquals(message, record.get("error"));
        assertEquals(List.of("run-start", "hooks", "capabilities", "ledger", "run-end"), types(out));
    }

    /** The contributor conflict cannot reach a run (a contributed id starts with inference.); the rule itself. */
    @Test
    void anIdAContributorAnsweredIsAConflictNamingIt() throws Exception {
        AtomicInteger count = new AtomicInteger();
        pack.capabilities = List.of(ok("toy.ready", count));
        CapabilityMatrix merged = new CapabilityMatrix(Map.of()).with(Map.of("toy.ready", new Capability("toy.ready",
                CapabilityState.OK, null, null)), CapabilityMatrix.SOURCE_CONTROLLER_PREFIX + "rogue");
        CapabilityMerge.Refused refused = assertThrows(CapabilityMerge.Refused.class, () -> PackCapabilities.evaluate(
                merged, pack, context(), null, null));
        assertEquals("capability 'toy.ready' is contributed by rogue and declared by pack toy", refused.getMessage());
        CapabilityMatrix agentOnly = new CapabilityMatrix(Map.of("toy.ready", new Capability("toy.ready",
                CapabilityState.OK, null, null)));
        assertEquals("capability 'toy.ready' is declared by the agent and by pack toy", assertThrows(
                CapabilityMerge.Refused.class, () -> PackCapabilities.evaluate(agentOnly, pack, context(), null, null))
                .getMessage());
        assertEquals(0, count.get());
        PackCapabilities.Result none = PackCapabilities.evaluate(agentOnly, new ToyPack(), context(), null, null);
        assertTrue(none.merged() == agentOnly && none.entries().isEmpty(), "a pack without capabilities adds nothing");
    }

    private PreflightCheck.PreflightContext context() {
        return new PreflightCheck.PreflightContext() {
            @Override
            public ai.skillroy.testjava.controller.AgentConnection agent() {
                return agent;
            }

            @Override
            public ai.skillroy.testjava.data.DataChannel data() {
                return ai.skillroy.testjava.data.DataChannels.of(agent, null, List.of());
            }

            @Override
            public String envId() {
                return "seed-a";
            }
        };
    }

    @Test
    void aThrowingEvaluationIsUnavailableAndItsTextReachesTheRecordOnly() throws Exception {
        AtomicInteger count = new AtomicInteger();
        String quoted = "the row value 'Alice Archer' and the password " + TestCredentials.PW_100;
        pack.capabilities = List.of(capability("toy.ready", CheckScope.SCENARIO, count, ctx -> {
            throw new IllegalStateException(quoted + " " + "x".repeat(600));
        }), ok("toy.other", count));
        Path out = tmp.resolve("throws");
        RunResult result = run(plan("steps:\n  - poke: {}\n  - expect: { \"caps[id=toy.ready].reason\": \"failed:"
                + " IllegalStateException\", \"caps[id=toy.ready].state\": UNAVAILABLE }\n"),
                options(BindMode.LIVE, out));
        assertEquals(Status.PASS, result.status(), "the run goes on: " + result.message());
        assertEquals(2, count.get());
        String archived = Files.readString(out.resolve(PlanExecutor.CAPABILITIES_FILE));
        assertTrue(archived.contains("failed: IllegalStateException"), archived);
        assertFalse(archived.contains("Alice"), "the exception's text is in no matrix");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) record(out, "capabilities").get("pack");
        assertEquals(List.of("toy.other", "toy.ready"), entries.stream().map(e -> e.get("id")).toList(), "sorted");
        assertFalse(entries.get(0).containsKey("error"), "error only on a failed evaluation");
        String error = (String) entries.get(1).get("error");
        assertTrue(error.startsWith("java.lang.IllegalStateException: the row value 'Alice Archer' and the password "
                + "<redacted>"), error);
        assertEquals(PackCapabilities.MAX_ERROR_LENGTH, error.length(), "cut at 500");
        assertEquals("failed: IllegalStateException", entries.get(1).get("reason"));
        String log = Files.readString(out.resolve("run.jsonl"));
        assertFalse(log.contains(TestCredentials.PW_100), "masked");

        pack.capabilities = List.of(capability("toy.ready", CheckScope.SCENARIO, count, ctx -> {
            throw new StackOverflowError();
        }));
        assertEquals(Status.PASS, run(plan("steps:\n  - expect: { \"caps[id=toy.ready].reason\": \"failed:"
                + " StackOverflowError\" }\n"), options(BindMode.LIVE, null)).status());
        pack.capabilities = List.of(capability("toy.ready", CheckScope.SCENARIO, count, ctx -> {
            throw new NoClassDefFoundError("x");
        }));
        assertEquals(Status.PASS, run(plan("steps:\n  - expect: { \"caps[id=toy.ready].reason\": \"failed:"
                + " NoClassDefFoundError\" }\n"), options(BindMode.LIVE, null)).status());
    }

    @Test
    void nullIsNoAnswerAnotherIdIsRefusedAndAnInterruptionEndsTheRun() throws Exception {
        AtomicInteger count = new AtomicInteger();
        pack.capabilities = List.of(capability("toy.ready", CheckScope.SCENARIO, count, ctx -> null));
        assertEquals(Status.PASS, run(plan("steps:\n  - expect: { \"caps[id=toy.ready].reason\": \"no answer\","
                + " \"caps[id=toy.ready].state\": UNAVAILABLE }\n"), options(BindMode.LIVE, null)).status());

        pack.capabilities = List.of(capability("toy.ready", CheckScope.SCENARIO, count, ctx -> new Capability(
                "toy.other", CapabilityState.OK, null, null)));
        RunResult other = run(plan("steps:\n  - poke: {}\n"), options(BindMode.LIVE, null));
        assertEquals(FailureKind.ENVIRONMENT, other.failureKind());
        assertEquals("pack toy answered 'toy.other' for its capability 'toy.ready'", other.message());

        pack.capabilities = List.of(capability("toy.ready", CheckScope.SUITE, count, ctx -> {
            throw new InterruptedException("stop");
        }));
        SuiteCache cache = SuiteCache.create();
        agent.session("s-1");
        RunResult interrupted = run(plan("steps:\n  - poke: {}\n"), options(BindMode.LIVE, null).withSuite(cache));
        assertTrue(Thread.interrupted(), "the thread is interrupted again");
        assertEquals(Status.ERROR, interrupted.status());
        assertEquals(FailureKind.ENVIRONMENT, interrupted.failureKind());
        assertEquals("interrupted", interrupted.message());
        assertEquals(0, cache.size(), "an interruption is never kept");
        assertTrue(agent.callsTo("toy.poke").isEmpty());

        pack.capabilities = List.of(capability("toy.ready", CheckScope.SCENARIO, count, ctx -> {
            throw new AgentLostException("gone");
        }));
        assertEquals(FailureKind.AGENT_LOST, run(plan("steps:\n  - poke: {}\n"), options(BindMode.LIVE, null))
                .failureKind());
    }

    @Test
    void aPackWithoutCapabilitiesWritesTheRecordAsBefore() throws Exception {
        Path out = tmp.resolve("none");
        assertEquals(Status.PASS, run(plan("steps:\n  - poke: {}\n"), options(BindMode.LIVE, out)).status());
        Map<String, Object> record = record(out, "capabilities");
        assertEquals(new LinkedHashSet<>(List.of("type", "t", "hash", "ids", "file", "agentHash", "contributed")),
                new LinkedHashSet<>(record.keySet()), "no pack key");
        pack.capabilities = null;
        Path nul = tmp.resolve("null");
        assertEquals(Status.PASS, run(plan("steps:\n  - poke: {}\n"), options(BindMode.LIVE, nul)).status(),
                "null is none");
        assertFalse(record(nul, "capabilities").containsKey("pack"));
    }

    // ------------------------------------------------------------------------------------------------ SUITE

    @Test
    void aSuiteCapabilityIsEvaluatedOncePerSuiteEnvironmentAndAgentStart() throws Exception {
        Ceiling ceiling = new Ceiling(CheckScope.SUITE);
        pack.capabilities = List.of(ceiling);
        Plan plan = plan("requires: [" + CEILING + "]\nsteps:\n  - poke: {}\n");
        SuiteCache cache = SuiteCache.create();
        agent.session("s-1");
        Path first = tmp.resolve("first");
        Path second = tmp.resolve("second");
        assertEquals(Status.PASS, run(plan, options(BindMode.LIVE, first).withSuite(cache)).status());
        assertEquals(Status.PASS, run(plan, options(BindMode.LIVE, second).withSuite(cache)).status());
        assertEquals(1, ceiling.evaluations.get(), "one evaluation for one cache and one session");
        assertEquals(false, packEntry(first).get("cached"));
        assertEquals("suite", packEntry(first).get("scope"));
        assertEquals(true, packEntry(second).get("cached"));
        assertEquals("OK", packEntry(second).get("state"));

        agent.session("s-2");
        run(plan, options(BindMode.LIVE, null).withSuite(cache));
        assertEquals(2, ceiling.evaluations.get(), "another agent start is evaluated again");
        run(plan, options(BindMode.LIVE, null).withSuite(cache).withBind(TestCredentials.bind().withEnvId("seed-b")
                .withBindingsRoot(tmp.resolve("bindings"))));
        assertEquals(3, ceiling.evaluations.get(), "another environment is evaluated again");

        run(plan, options(BindMode.LIVE, null));
        run(plan, options(BindMode.LIVE, null));
        assertEquals(5, ceiling.evaluations.get(), "without a cache every run evaluates");

        agent.session(null);
        SuiteCache none = SuiteCache.create();
        Path noSession = tmp.resolve("no-session");
        run(plan, options(BindMode.LIVE, noSession).withSuite(none));
        run(plan, options(BindMode.LIVE, null).withSuite(none));
        assertEquals(7, ceiling.evaluations.get(), "a connection without a session is never cached for");
        assertEquals(0, none.size());
        assertEquals(false, packEntry(noSession).get("cached"));

        toy.settings.remove(CEILING_SETTING);
        agent.session("s-3");
        SuiteCache failing = SuiteCache.create();
        assertEquals(FailureKind.PRECONDITION_FAILED, run(plan, options(BindMode.LIVE, null).withSuite(failing))
                .failureKind());
        toy.settings.put(CEILING_SETTING, "100.00");
        Path stale = tmp.resolve("stale");
        RunResult kept = run(plan, options(BindMode.LIVE, stale).withSuite(failing));
        assertEquals(FailureKind.PRECONDITION_FAILED, kept.failureKind(), "an UNAVAILABLE answer is kept for the suite");
        assertEquals(true, packEntry(stale).get("cached"));
        assertEquals(8, ceiling.evaluations.get());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> packEntry(Path dir) throws Exception {
        return ((List<Map<String, Object>>) record(dir, "capabilities").get("pack")).get(0);
    }

    // ------------------------------------------------------------------------------------------------ the session

    @Test
    void aSessionsOpenMergesThePacksCapabilities() throws Exception {
        SessionFixture fx = new SessionFixture(tmp.resolve("session"));
        Ceiling ceiling = new Ceiling(CheckScope.SUITE);
        fx.pack.capabilities = List.of(ceiling);
        SuiteCache cache = SuiteCache.create();
        fx.agent.session("s-1");
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options().withSuite(cache))) {
            assertEquals(CapabilityState.OK, session.capabilities().get(CEILING).state());
            assertEquals("pack:toy", session.capabilities().source(CEILING));
        }
        String first = Files.readAllLines(tmp.resolve("session").resolve(StepJournal.FILE)).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) Json.parseObject(first).get("options");
        assertEquals(List.of(CEILING), options.get("pack"), "the journal's session-start names the pack's ids");
        CapabilityMatrix archived = CapabilityMatrix.fromJson(Json.parseObject(Files.readString(tmp.resolve("session")
                .resolve(PlanExecutor.CAPABILITIES_FILE))));
        assertNotNull(archived.get(CEILING), "capabilities.json holds it");

        new PlanExecutor(fx.pack, fx.agent, RunOptions.defaults().withClock(fx.clock).withSleeper(fx.clock.sleeper())
                .withBind(TestCredentials.bind().withEnvId("seed-a")).withSuite(cache)).run(PlanCompiler.compile(
                "scenario: t\npack: toy\nsteps: []\n".getBytes(StandardCharsets.UTF_8), "t.yaml", fx.pack));
        assertEquals(1, ceiling.evaluations.get(), "an open and a run sharing the cache evaluate once");

        fx.pack.capabilities = List.of(ok("toy.ready", new AtomicInteger()), ok("toy.ready", new AtomicInteger()));
        SessionOpenException refused = assertThrows(SessionOpenException.class, () -> ExecutorSession.open(fx.pack,
                fx.agent, SessionOptions.defaults(null).withClock(fx.clock).withBind(TestCredentials.bind()
                        .withEnvId("seed-a")).withId("sess-2")));
        assertEquals(FailureKind.ENVIRONMENT, refused.kind());
        assertEquals("pack toy declares the capability 'toy.ready' twice", refused.getMessage());
    }
}
