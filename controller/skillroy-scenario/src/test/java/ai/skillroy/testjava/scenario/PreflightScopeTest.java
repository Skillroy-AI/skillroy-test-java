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
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.hooks.HooksMode;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.pack.CheckScope;
import ai.skillroy.testjava.scenario.pack.PackCapability;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pre-flight scope and the suite cache (phase8-contracts.md 9.3, gap 112; 13.1 row 46): the scope defaults,
 * {@link SuiteCache}, the options' {@code suite} component, and {@link Preflight} in the run and the session's open: a
 * {@code SUITE} check run once per cache and agent session, its records ({@code scope}, {@code cached}), a
 * {@code SCENARIO} check's record as before, a cached failure failing later scenarios without running, an exception
 * kept, an interruption not kept, a full cache.
 */
class PreflightScopeTest {

    private static SuiteCache.Key key(String id, String session) {
        return new SuiteCache.Key(SuiteCache.KIND_PREFLIGHT, "toy", "1.0.0", id, "default", session);
    }

    @Test
    void theScopesDefaultToScenarioAndAPackDeclaresNothing() {
        PreflightCheck check = new PreflightCheck() {
            @Override
            public String id() {
                return "desk";
            }

            @Override
            public String check(AgentConnection agent) {
                return null;
            }
        };
        assertEquals(CheckScope.SCENARIO, check.scope());
        PackCapability capability = new PackCapability() {
            @Override
            public String id() {
                return "toy.env.ready";
            }

            @Override
            public Capability evaluate(PreflightCheck.PreflightContext ctx) {
                return new Capability(id(), CapabilityState.OK, null, null);
            }
        };
        assertEquals(CheckScope.SCENARIO, capability.scope());
        assertNull(capability.description());
        ToyPack pack = new ToyPack();
        assertEquals(List.of(), pack.capabilities());
        assertEquals(Set.of(), pack.warningCodes());
        assertEquals(List.of("SCENARIO", "SUITE"), List.of(CheckScope.values()).stream().map(Enum::name).toList());
    }

    @Test
    void theSuiteCacheIsBoundedAndKeyedBySession() {
        assertEquals(SuiteCache.DEFAULT_MAX_ENTRIES, SuiteCache.create().maxEntries());
        assertEquals(1000, SuiteCache.DEFAULT_MAX_ENTRIES);
        assertThrows(IllegalArgumentException.class, () -> SuiteCache.create(0));
        assertThrows(IllegalArgumentException.class, () -> SuiteCache.create(100_001));
        assertEquals(100_000, SuiteCache.create(100_000).maxEntries());

        SuiteCache cache = SuiteCache.create(1);
        assertEquals(0, cache.size());
        cache.put(key("desk", "s-1"), "ok");
        assertEquals("ok", cache.get(key("desk", "s-1")));
        assertNull(cache.get(key("desk", "s-2")), "another agent start is another key");
        assertNull(cache.get(new SuiteCache.Key(SuiteCache.KIND_CAPABILITY, "toy", "1.0.0", "desk", "default",
                "s-1")), "a capability is another kind");
        cache.put(key("printer", "s-1"), "ok");
        assertEquals(1, cache.size(), "full: nothing more is kept");
        assertNull(cache.get(key("printer", "s-1")));
        cache.put(key("desk", "s-1"), "desk closed");
        assertEquals("desk closed", cache.get(key("desk", "s-1")), "the last answer wins");
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(key("desk", "s-1")));

        assertThrows(NullPointerException.class, () -> key("desk", null), "no session is never cached for");
        assertThrows(IllegalArgumentException.class, () -> new SuiteCache.Key("other", "toy", "1", "desk", "default",
                "s-1"));
        assertThrows(NullPointerException.class, () -> cache.put(key("desk", "s-1"), null));
    }

    @Test
    void theRunAndSessionOptionsCarryTheSuiteCache() {
        SuiteCache cache = SuiteCache.create();
        assertNull(RunOptions.defaults().suite());
        RunOptions run = RunOptions.defaults().withSuite(cache);
        assertSame(cache, run.suite());
        assertSame(cache, run.withRobot(true).withHooks(HooksMode.OFF).withOutputDir(null).withStepTimeout(
                Duration.ofSeconds(3)).withBind(BindOptions.defaults()).withObserver(RunObserver.NONE).suite(),
                "every copy keeps it");
        assertNull(run.withSuite(null).suite());
        RunOptions thirteen = new RunOptions(null, null, null, null, null, null, null, true, BindOptions.defaults(),
                false, HooksMode.AUTO, RunObserver.NONE, true);
        assertNull(thirteen.suite(), "the Phase 7 constructor keeps none");
        assertEquals(true, thirteen.robot());

        assertNull(SessionOptions.defaults(null).suite());
        SessionOptions session = SessionOptions.defaults(null).withSuite(cache);
        assertSame(cache, session.suite());
        assertSame(cache, session.withRobot(true).withHost(Set.of("x")).withId("s-1").withReadOnly(true).suite(),
                "every copy keeps it");
        SessionOptions fourteen = new SessionOptions(null, null, null, null, false, false, null, null, null, null,
                HooksMode.AUTO, RunObserver.NONE, true, Set.of("x"));
        assertNull(fourteen.suite(), "the Phase 7 constructor keeps none");
        assertEquals(Set.of("x"), fourteen.host());
    }
    // ------------------------------------------------------------------------------------------------ the body

    @TempDir
    Path tmp;

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();

    /** A check that counts its runs and answers {@link #reason}. */
    static final class Counting implements PreflightCheck {
        final String id;
        final CheckScope scope;
        final AtomicInteger runs = new AtomicInteger();
        volatile String reason;
        volatile Exception failure;

        Counting(String id, CheckScope scope) {
            this.id = id;
            this.scope = scope;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public CheckScope scope() {
            return scope;
        }

        @Override
        public String check(AgentConnection agent) throws Exception {
            runs.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return reason;
        }
    }

    private RunOptions options(SuiteCache cache, Path out) {
        return RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withOutputDir(out)
                .withBind(TestCredentials.bind().withEnvId("seed-a")).withSuite(cache);
    }

    private RunResult run(SuiteCache cache, Path out) throws Exception {
        agent.value("toy.poke", Map.of());
        Plan plan = PlanCompiler.compile("scenario: t\npack: toy\nsteps:\n  - poke: {}\n".getBytes(
                StandardCharsets.UTF_8), "t.yaml", pack);
        return new PlanExecutor(pack, agent, options(cache, out)).run(plan);
    }

    private List<Map<String, Object>> preflightRecords(Path dir) throws Exception {
        return PackCapabilitiesTest.log(dir).stream().filter(r -> "preflight".equals(r.get("type"))).map(r -> {
            Map<String, Object> fields = new LinkedHashMap<>(r);
            fields.remove("type");
            fields.remove("t");
            return fields;
        }).toList();
    }

    private static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void aSuiteCheckRunsOncePerCacheAndSession() throws Exception {
        Counting desk = new Counting("desk", CheckScope.SUITE);
        pack.preflight = List.of(desk);
        SuiteCache cache = SuiteCache.create();
        agent.session("s-1");
        Path first = tmp.resolve("first");
        Path second = tmp.resolve("second");
        assertEquals(Status.PASS, run(cache, first).status());
        assertEquals(Status.PASS, run(cache, second).status());
        assertEquals(1, desk.runs.get(), "one call for one cache and one session");
        assertEquals(List.of(fields("id", "desk", "ok", true, "reason", null, "scope", "suite", "cached", false)),
                preflightRecords(first));
        assertEquals(List.of(fields("id", "desk", "ok", true, "reason", null, "scope", "suite", "cached", true)),
                preflightRecords(second));
        assertEquals(1, cache.size());

        agent.session("s-2");
        run(cache, null);
        assertEquals(2, desk.runs.get(), "another session: the check runs again");

        run(null, null);
        run(null, null);
        assertEquals(4, desk.runs.get(), "no cache: every run");

        agent.session(null);
        SuiteCache unused = SuiteCache.create();
        Path noSession = tmp.resolve("no-session");
        run(unused, noSession);
        run(unused, null);
        assertEquals(6, desk.runs.get(), "no session: every run");
        assertEquals(0, unused.size(), "nothing is cached without a session");
        assertEquals(List.of(fields("id", "desk", "ok", true, "reason", null, "scope", "suite", "cached", false)),
                preflightRecords(noSession));
    }

    @Test
    void aScenarioCheckRunsEveryTimeAndItsRecordIsAsBefore() throws Exception {
        Counting desk = new Counting("desk", CheckScope.SCENARIO);
        pack.preflight = List.of(desk);
        SuiteCache cache = SuiteCache.create();
        agent.session("s-1");
        Path out = tmp.resolve("scenario");
        run(cache, out);
        run(cache, null);
        assertEquals(2, desk.runs.get());
        assertEquals(0, cache.size());
        assertEquals(List.of(fields("id", "desk", "ok", true, "reason", null)), preflightRecords(out),
                "no scope, no cached: the record of 0.1.0");
    }

    @Test
    void aCachedFailureFailsEveryLaterScenarioWithoutRunning() throws Exception {
        Counting desk = new Counting("desk", CheckScope.SUITE);
        desk.reason = "the desk is closed";
        Counting printer = new Counting("printer", CheckScope.SCENARIO);
        pack.preflight = List.of(desk, printer);
        SuiteCache cache = SuiteCache.create();
        agent.session("s-1");
        RunResult first = run(cache, null);
        assertEquals(FailureKind.ENVIRONMENT, first.failureKind());
        assertEquals("pre-flight check desk: the desk is closed", first.message());
        desk.reason = null;
        Path out = tmp.resolve("cached-failure");
        RunResult second = run(cache, out);
        assertEquals(Status.ERROR, second.status());
        assertEquals(FailureKind.ENVIRONMENT, second.failureKind());
        assertEquals("pre-flight check desk: the desk is closed (checked once for this suite)", second.message());
        assertEquals(Map.of("preflight", "desk", "reason", "the desk is closed", "cached", true),
                second.detail());
        assertEquals(1, desk.runs.get(), "not run again");
        assertEquals(0, printer.runs.get(), "the checks stop at the first failure");
        assertEquals(List.of(fields("id", "desk", "ok", false, "reason", "the desk is closed", "scope", "suite",
                "cached", true)), preflightRecords(out));
        assertTrue(agent.callsTo("toy.poke").isEmpty());

        Counting thrown = new Counting("thrown", CheckScope.SUITE);
        thrown.failure = new IllegalStateException("device gone");
        pack.preflight = List.of(thrown);
        SuiteCache other = SuiteCache.create();
        run(other, null);
        RunResult kept = run(other, null);
        assertEquals("pre-flight check thrown: failed: java.lang.IllegalStateException: device gone (checked once"
                + " for this suite)", kept.message(), "an exception is kept like a failure");
        assertEquals(1, thrown.runs.get());

        Counting interrupted = new Counting("interrupted", CheckScope.SUITE);
        interrupted.failure = new InterruptedException("stop");
        pack.preflight = List.of(interrupted);
        SuiteCache third = SuiteCache.create();
        RunResult once = run(third, null);
        assertTrue(Thread.interrupted(), "re-interrupted");
        assertEquals("pre-flight check interrupted: interrupted", once.message());
        assertEquals(0, third.size(), "an interruption is never kept");
        run(third, null);
        assertTrue(Thread.interrupted());
        assertEquals(2, interrupted.runs.get());
    }

    @Test
    void aFullCacheRunsTheNextItemPerScenario() throws Exception {
        Counting desk = new Counting("desk", CheckScope.SUITE);
        Counting printer = new Counting("printer", CheckScope.SUITE);
        pack.preflight = List.of(desk, printer);
        SuiteCache cache = SuiteCache.create(1);
        agent.session("s-1");
        Path out = tmp.resolve("full");
        run(cache, null);
        run(cache, out);
        assertEquals(1, desk.runs.get());
        assertEquals(2, printer.runs.get(), "beyond the bound nothing more is kept");
        assertEquals(List.of(fields("id", "desk", "ok", true, "reason", null, "scope", "suite", "cached", true),
                fields("id", "printer", "ok", true, "reason", null, "scope", "suite", "cached", false)),
                preflightRecords(out));
    }

    @Test
    void theHelperReportsAPackThatCannotListItsChecks() throws Exception {
        ai.skillroy.testjava.scenario.pack.DomainPack broken = new ai.skillroy.testjava.scenario.pack
                .DelegatingDomainPack(pack, "broken", "1") {
            @Override
            public List<PreflightCheck> preflight() {
                throw new IllegalStateException("no list");
            }
        };
        Preflight.Outcome outcome = Preflight.run(broken, null, SuiteCache.create(), "s-1");
        assertEquals("pack " + broken.id() + ": its pre-flight checks cannot be read:"
                + " java.lang.IllegalStateException: no list", outcome.failure());
        assertEquals(List.of(), outcome.records());
        assertNull(outcome.failed());
        pack.preflight = null;
        assertNull(Preflight.run(pack, null, null, null).failure(), "null is none");
        assertEquals(Map.of("id", "x", "ok", true, "scope", "suite", "cached", true).keySet(),
                new Preflight.Record("x", true, null, CheckScope.SUITE, true).fields().keySet().stream().filter(
                        k -> !"reason".equals(k)).collect(Collectors.toSet()));
    }

    /** phase8-contracts.md 13.1 row 46: {@code SessionOptions.suite} is honoured by the open. */
    @Test
    void aSessionsOpenAndARunShareTheCache() throws Exception {
        SessionFixture fx = new SessionFixture(tmp.resolve("session"));
        Counting desk = new Counting("desk", CheckScope.SUITE);
        fx.pack.preflight = List.of(desk);
        SuiteCache cache = SuiteCache.create();
        fx.agent.session("s-1");
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options().withSuite(cache))) {
            assertNotNull(session.capabilities());
        }
        new PlanExecutor(fx.pack, fx.agent, RunOptions.defaults().withClock(fx.clock).withSleeper(fx.clock.sleeper())
                .withBind(TestCredentials.bind().withEnvId("seed-a")).withSuite(cache)).run(PlanCompiler.compile(
                "scenario: t\npack: toy\nsteps: []\n".getBytes(StandardCharsets.UTF_8), "t.yaml",
                fx.pack));
        assertEquals(1, desk.runs.get(), "one evaluation over an open and a run sharing the cache");

        desk.reason = "closed";
        SuiteCache failing = SuiteCache.create();
        SessionOpenException refused = assertThrows(SessionOpenException.class, () -> ExecutorSession.open(fx.pack,
                fx.agent, SessionOptions.defaults(null).withClock(fx.clock).withBind(TestCredentials.bind()
                        .withEnvId("seed-a")).withSuite(failing)));
        assertEquals("pre-flight check desk: closed", refused.getMessage());
        SessionOpenException again = assertThrows(SessionOpenException.class, () -> ExecutorSession.open(fx.pack,
                fx.agent, SessionOptions.defaults(null).withClock(fx.clock).withBind(TestCredentials.bind()
                        .withEnvId("seed-a")).withSuite(failing)));
        assertEquals("pre-flight check desk: closed (checked once for this suite)", again.getMessage());
        assertEquals(2, desk.runs.get());
    }
}
