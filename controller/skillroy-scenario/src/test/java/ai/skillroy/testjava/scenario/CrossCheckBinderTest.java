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

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CrossCheck;
import ai.skillroy.testjava.scenario.resolve.CrossCheckResult;
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binder's step 8 against the toy pack and a fake connection (phase3-contracts.md 6, 8.3, 11.1 item 2), in each
 * mode: when the check runs and with what, ORACLE_SUSPECT and its detail, ORACLE_CHANGED first in {@code replay},
 * pack defects, guarded checks and the capability states, the budget, the mask, the bindings file's
 * {@code crossCheck} key, {@code BindingsDiff} and {@link ResolutionEngine#crossCheck(String)}.
 */
class CrossCheckBinderTest {

    static final String YAML = """
            scenario: toy-check
            pack: toy
            resolve:
              max: { query: Limit, args: { type: RETURN } }
              item: { query: Item, args: { deposit: { between: ["2.00", "50.00"], lte: $max } } }
            oracle:
              neg: { fn: Negate, args: [$item.deposit], must_equal: "-5.00",
                     crossCheck: { check: toy.negate.check, tolerance: "0.00" } }
            steps:
              - enter: { value: $item.code }
            """;

    static final String GUARDED = YAML.replace("tolerance: \"0.00\" }", "tolerance: \"0.00\","
            + " capability: toy.negate }");

    static final List<String> DETAIL = List.of("reason", "oracle", "fn", "check", "expected", "actual", "tolerance",
            "message", "evidence", "inputsHash", "mode", "envId", "fixedUpstream");

    @TempDir
    Path root;

    private final ToyResolution toy = new ToyResolution();
    private FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private final List<String> events = new ArrayList<>();
    private final List<Map<String, Object>> records = new ArrayList<>();
    private ToyPack pack;

    @BeforeEach
    void setUp() {
        toy.withCrossChecks = true;
        toy.install(agent);
        pack = toy.pack();
    }

    private Plan plan(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "toy-check.yaml", pack);
    }

    private BindOptions options(BindMode mode) {
        return TestCredentials.bind().withMode(mode).withEnvId("seed-a").withBindingsRoot(root).withClock(clock)
                .withApprover(Approver.APPROVE_ALL);
    }

    private BindOutcome bind(Plan plan, BindOptions options) throws Exception {
        events.clear();
        records.clear();
        return Binder.bind(plan, pack, agent, options, (type, fields) -> {
            events.add(type);
            Map<String, Object> copy = new LinkedHashMap<>(fields);
            copy.put("type", type);
            records.add(copy);
        });
    }

    private BindException fails(Plan plan, BindOptions options) {
        return assertThrows(BindException.class, () -> bind(plan, options));
    }

    private Path file(String env) {
        return BindingsFile.locate(root, env, "toy-check");
    }

    private Map<String, Object> record(String type) {
        return records.stream().filter(r -> type.equals(r.get("type"))).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------- the step

    @Test
    void theCheckRunsOnceAfterTheOracleAndBeforeItsGuardInEveryMode() throws Exception {
        for (BindMode mode : List.of(BindMode.LIVE, BindMode.APPROVE, BindMode.REPLAY)) {
            toy.checkCalls.clear();
            BindOutcome outcome = bind(plan(YAML), options(mode));
            int oracle = events.indexOf("oracle");
            assertEquals(List.of("oracle", "crosscheck", "guard"), events.subList(oracle, oracle + 3), mode.label());
            assertEquals(List.of(new ToyResolution.CheckCall("toy.negate.check", new BigDecimal("5.00"),
                    new BigDecimal("-5.00"), new BigDecimal("0.00"))), toy.checkCalls,
                    mode.label() + ": once, with the decoded input and output and the plan's tolerance");
            Map<String, Object> record = record("crosscheck");
            assertEquals(List.of("oracle", "fn", "check", "tolerance", "agreed", "reason", "expected", "actual",
                    "evidence", "type"), new ArrayList<>(record.keySet()), mode.label());
            assertEquals("neg", record.get("oracle"));
            assertEquals("Negate", record.get("fn"));
            assertEquals("toy.negate.check", record.get("check"));
            assertEquals("0.00", record.get("tolerance"));
            assertEquals(true, record.get("agreed"));
            assertEquals("-5.00", record.get("expected"));
            assertEquals(Map.of("amount", "-5.00"), record.get("actual"));
            assertEquals(Map.of("toy.negate", Map.of("amount", "-5.00")), record.get("evidence"));
            assertEquals(List.of(), outcome.skipped());
            CrossCheckOutcome kept = outcome.bindings().crossChecks().get("neg");
            assertTrue(kept.agreed() && !kept.skipped(), mode.label());
            assertEquals("cross-check toy.negate.check: agrees (the application negates 5.00 to -5.00, the oracle to"
                    + " -5.00)", kept.summary());
        }
        assertEquals(3, agent.callsTo("toy.negate").size(), "the application was asked once per bind");
    }

    @Test
    void approveShowsTheOutcomeToTheApproverAndWritesTheCrossCheckKey() throws Exception {
        List<Approver.ApprovalRequest> seen = new ArrayList<>();
        BindOutcome outcome = bind(plan(YAML), options(BindMode.APPROVE).withApprover(request -> {
            seen.add(request);
            return new Approver.Decision(true, null, null);
        }));
        assertEquals(1, seen.size());
        assertEquals(1, seen.get(0).crossChecks().size());
        assertTrue(seen.get(0).crossChecks().get(0).agreed());
        assertEquals("neg", seen.get(0).crossChecks().get(0).oracle());
        String text = Files.readString(file("seed-a"));
        Map<String, Object> json = Json.parseObject(text);
        Map<?, ?> neg = (Map<?, ?>) ((Map<?, ?>) json.get("oracles")).get("neg");
        assertEquals(List.of("fn", "inputsHash", "value", "mustEqual", "crossCheck"), new ArrayList<>(neg.keySet()),
                "crossCheck after mustEqual");
        Map<?, ?> crossCheck = (Map<?, ?>) neg.get("crossCheck");
        assertEquals(List.of("check", "tolerance"), new ArrayList<>(crossCheck.keySet()), "no capability unguarded");
        assertEquals(Map.of("check", "toy.negate.check", "tolerance", "0.00"), crossCheck);
        BindingsFile read = BindingsFile.read(file("seed-a"));
        assertEquals(outcome.file().toJsonText(), text, "what was approved is what was written");
        assertEquals(outcome.file().oracles(), read.oracles(), "the oracles, crossCheck included, read back equal");
        assertEquals(read, BindingsFile.parse(read.toJsonText()), "reads back equal");
        assertEquals(text, read.toJsonText(), "byte-stable");
        assertEquals(new Plan.CrossCheckSpec("toy.negate.check", "0.00", null), read.oracles().get("neg")
                .crossCheck());
        assertNull(bind(plan(YAML), options(BindMode.APPROVE)).written(), "a second approval is identical");

        bind(plan(GUARDED), options(BindMode.APPROVE));
        agent.capability("toy.negate", CapabilityState.OK, null);
        bind(plan(GUARDED), options(BindMode.APPROVE));
        Map<?, ?> guarded = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) Json.parseObject(Files.readString(file("seed-a")))
                .get("oracles")).get("neg")).get("crossCheck");
        assertEquals(List.of("check", "tolerance", "capability"), new ArrayList<>(guarded.keySet()));
        assertEquals("toy.negate", guarded.get("capability"));
    }

    @Test
    void aPhase2FileWithoutTheKeyStillReadsAndReplayDoesNotCompareIt() throws Exception {
        bind(plan(YAML), options(BindMode.APPROVE));
        Map<String, Object> json = Json.parseObject(Files.readString(file("seed-a")));
        @SuppressWarnings("unchecked")
        Map<String, Object> neg = (Map<String, Object>) ((Map<String, Object>) json.get("oracles")).get("neg");
        neg.remove("crossCheck");
        Files.writeString(file("seed-a"), Json.writePretty(json));
        BindingsFile read = BindingsFile.read(file("seed-a"));
        assertNull(read.oracles().get("neg").crossCheck());
        toy.checkCalls.clear();
        BindOutcome replay = bind(plan(YAML), options(BindMode.REPLAY));
        assertEquals(1, toy.checkCalls.size(), "replay recomputes the oracle and re-runs the check (8.3)");
        assertTrue(replay.bindings().crossChecks().get("neg").agreed());

        neg.put("crossCheck", Map.of("check", "toy.negate.check", "colour", "red"));
        Files.writeString(file("seed-a"), Json.writePretty(json));
        assertEquals("UNREADABLE", fails(plan(YAML), options(BindMode.REPLAY)).reason(), "an unknown key inside is"
                + " refused");
    }

    @Test
    void bindingsDiffReportsAChangedCheckUnderTheOracle() throws Exception {
        bind(plan(YAML), options(BindMode.APPROVE));
        BindingsFile before = BindingsFile.read(file("seed-a"));
        OracleValue neg = before.oracles().get("neg");
        OracleValue changed = new OracleValue(neg.name(), neg.fn(), neg.inputsHash(), neg.value(), neg.mustEqual(),
                new Plan.CrossCheckSpec("toy.negate.check", "0.01", null));
        BindingsFile after = new BindingsFile(before.bindingsVersion(), before.scenarioId(), before.envId(),
                before.pack(), before.packVersion(), before.planHash(), before.resolveHash(), before.env(),
                before.resolutions(), Map.of("neg", changed));
        BindingsDiff diff = before.diff(after);
        assertEquals(List.of(new BindingsDiff.Change("oracles.neg.crossCheck", Map.of("check", "toy.negate.check",
                "tolerance", "0.00"), Map.of("check", "toy.negate.check", "tolerance", "0.01"))), diff.changes());
        assertEquals(java.util.Set.of("oracle.neg"), diff.slots());
        OracleValue removed = new OracleValue(neg.name(), neg.fn(), neg.inputsHash(), neg.value(), neg.mustEqual());
        BindingsDiff gone = before.diff(new BindingsFile(1, before.scenarioId(), before.envId(), before.pack(),
                before.packVersion(), before.planHash(), before.resolveHash(), before.env(), before.resolutions(),
                Map.of("neg", removed)));
        assertNull(gone.changes().get(0).after());
    }

    // ------------------------------------------------------------------------------------------- ORACLE_SUSPECT

    @Test
    void aDisagreementIsOracleSuspectInEveryModeWithEveryDetailField() throws Exception {
        bind(plan(YAML), options(BindMode.APPROVE));
        toy.negateAnswer = "-4.00";
        List<BindOptions> runs = List.of(options(BindMode.LIVE), options(BindMode.APPROVE).withEnvId("fresh-a"),
                options(BindMode.LIVE).withWriteBindings(true).withEnvId("fresh-b"), options(BindMode.REPLAY));
        for (BindOptions options : runs) {
            List<Approver.ApprovalRequest> asked = new ArrayList<>();
            BindException e = fails(plan(YAML), options.withApprover(request -> {
                asked.add(request);
                return new Approver.Decision(true, null, null);
            }));
            String mode = options.mode().label() + (options.writeBindings() ? " --write-bindings" : "");
            assertEquals(FailureKind.ORACLE_SUSPECT, e.kind(), mode);
            assertEquals("DISAGREED", e.reason(), mode);
            assertEquals("oracle.neg", e.slot());
            Map<String, Object> d = e.detail();
            assertTrue(d.keySet().containsAll(DETAIL), mode + ": " + d.keySet());
            assertEquals("neg", d.get("oracle"));
            assertEquals("Negate", d.get("fn"));
            assertEquals("toy.negate.check", d.get("check"));
            assertEquals("-5.00", d.get("expected"));
            assertEquals(Map.of("amount", "-4.00"), d.get("actual"));
            assertEquals("0.00", d.get("tolerance"));
            assertEquals("the application negates 5.00 to -4.00, the oracle to -5.00", d.get("message"));
            assertEquals(Map.of("toy.negate", Map.of("amount", "-4.00")), d.get("evidence"));
            assertTrue(String.valueOf(d.get("inputsHash")).startsWith("sha256:"));
            assertEquals(options.mode().label(), d.get("mode"));
            assertEquals(options.envId(), d.get("envId"));
            assertEquals(List.of(Map.of("slot", "item", "label", "{\"code\":\"B2\",\"deposit\":\"5.00\"}")),
                    d.get("fixedUpstream"), "the query slots the oracle's args reference");
            assertEquals("oracle neg (Negate) disagrees with toy.negate.check: expected \"-5.00\", the application"
                    + " computes {\"amount\":\"-4.00\"} (the application negates 5.00 to -4.00, the oracle to"
                    + " -5.00)", e.getMessage());
            assertFalse(events.contains("guard"), mode + ": the guard comes after the check (6.1)");
            assertTrue(events.contains("crosscheck"));
            assertEquals(false, record("crosscheck").get("agreed"));
            assertTrue(asked.isEmpty(), mode + ": the approver is not asked");
        }
        assertFalse(Files.exists(root.resolve("fresh-a")), "approve wrote nothing");
        assertFalse(Files.exists(root.resolve("fresh-b")), "live --write-bindings wrote nothing");
    }

    /** {@code Negate} gone wrong: twice the negation. */
    static final class WrongNegate implements Oracle<BigDecimal, BigDecimal> {
        @Override
        public String fn() {
            return "Negate";
        }

        @Override
        public BigDecimal compute(BigDecimal input) {
            return input.negate().add(input.negate());
        }
    }

    @Test
    void aWrongOracleIsOracleChangedAgainstTheRightFileAndOracleSuspectAgainstAWrongOne() throws Exception {
        bind(plan(YAML), options(BindMode.APPROVE));
        String right = Files.readString(file("seed-a"));
        pack.resolution = ResolutionCatalogue.builder(toy.catalogue()).replace(new WrongNegate()).build();
        toy.checkCalls.clear();
        BindException changed = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals(FailureKind.REBIND_NEEDED, changed.kind());
        assertEquals("ORACLE_CHANGED", changed.reason(), "step 7 comes before step 8");
        assertTrue(toy.checkCalls.isEmpty(), "no check after ORACLE_CHANGED");

        Map<String, Object> json = Json.parseObject(right);
        @SuppressWarnings("unchecked")
        Map<String, Object> neg = (Map<String, Object>) ((Map<String, Object>) json.get("oracles")).get("neg");
        neg.put("value", "-10.00");
        Files.writeString(file("seed-a"), Json.writePretty(json));
        BindException suspect = fails(plan(YAML), options(BindMode.REPLAY));
        assertEquals(FailureKind.ORACLE_SUSPECT, suspect.kind(), "a file approved with the wrong oracle is caught"
                + " by the check");
        assertEquals("-10.00", suspect.detail().get("expected"));
        assertEquals(Map.of("amount", "-5.00"), suspect.detail().get("actual"));
        assertEquals("replay", suspect.detail().get("mode"));
    }

    // ------------------------------------------------------------------------------------------- defects

    @Test
    void whatTheCheckThrowsAndANullResultAreEnvironmentNamingTheCheck() throws Exception {
        toy.beforeNegateCheck = ctx -> {
            throw new IllegalStateException("check bug");
        };
        BindException thrown = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, thrown.kind());
        assertEquals("oracle.neg", thrown.slot());
        assertTrue(thrown.getMessage().startsWith("pack defect: cross-check toy.negate.check failed: "
                + "java.lang.IllegalStateException: check bug"), thrown.getMessage());

        toy.beforeNegateCheck = ctx -> { };
        toy.negateCheckReturnsNull = true;
        BindException none = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, none.kind());
        assertEquals("pack defect: cross-check toy.negate.check failed: it returned null", none.getMessage());
        toy.negateCheckReturnsNull = false;

        agent.error("toy.negate", ErrorCodes.INTERNAL_ERROR, "the dry run broke");
        BindException rpc = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, rpc.kind());
        assertTrue(rpc.getMessage().contains("cross-check toy.negate.check: toy.negate answered INTERNAL_ERROR"),
                rpc.getMessage());
        toy.install(agent);

        toy.beforeNegateCheck = ctx -> ctx.data().query("nowhere", "SELECT 1 FROM x ORDER BY 1", List.of(), 1, null);
        BindException data = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, data.kind());
        assertEquals("UNKNOWN_SOURCE", data.detail().get("dataKind"));
        assertTrue(data.getMessage().startsWith("cross-check toy.negate.check: a statement on source nowhere"),
                data.getMessage());

        agent.describe("toy.mutate", true).value("toy.mutate", Map.of());
        toy.beforeNegateCheck = ctx -> ctx.agent().call("toy.mutate", Map.of());
        BindException mutating = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, mutating.kind());
        assertEquals("pack defect: cross-check toy.negate.check called the mutating primitive toy.mutate",
                mutating.getMessage());
        assertTrue(agent.callsTo("toy.mutate").isEmpty(), "never sent: the view is read-only");

        toy.beforeNegateCheck = ctx -> ctx.agent().await("x", 0, null, Duration.ofSeconds(1));
        assertEquals(FailureKind.ENVIRONMENT, fails(plan(YAML), options(BindMode.LIVE)).kind());
    }

    @Test
    void aPlanNamingACheckTheRunningPackLacksIsEnvironmentBeforeAnyPackCall() throws Exception {
        Plan plan = plan(YAML);
        toy.withCrossChecks = false;
        pack.resolution = toy.catalogue();
        for (BindMode mode : List.of(BindMode.LIVE, BindMode.REPLAY)) {
            BindException e = fails(plan, options(mode));
            assertEquals(FailureKind.ENVIRONMENT, e.kind(), mode.label());
            assertEquals("the plan names cross-check toy.negate.check for oracle Negate, which pack toy 1.0.0 does"
                    + " not have; recompile", e.getMessage());
            assertEquals("neg", e.detail().get("oracle"));
        }
        assertEquals(0, toy.itemResolverCalls.get(), "8.3 step 1 comes before any pack call that reads");

        toy.withCrossChecks = true;
        CrossCheck<BigDecimal, BigDecimal> exact = new CrossCheck<>() {
            @Override
            public String id() {
                return "toy.negate.check";
            }

            @Override
            public String oracleFn() {
                return "Negate";
            }

            @Override
            public CrossCheckResult check(BigDecimal input, BigDecimal output, BigDecimal tolerance,
                                          VerifyContext ctx) {
                return CrossCheckResult.agree("exact", output, Map.of());
            }
        };
        pack.resolution = ResolutionCatalogue.builder(toy.catalogue()).replace(exact).build();
        BindException tolerance = fails(plan, options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, tolerance.kind());
        assertEquals("the plan gives cross-check toy.negate.check a tolerance, which the check of pack toy 1.0.0"
                + " does not take; recompile", tolerance.getMessage());
    }

    // ------------------------------------------------------------------------------------------- guarded

    @Test
    void aGuardedCheckIsSkippedUnlessItsCapabilityIsOk() throws Exception {
        record Case(CapabilityState state, String reason, String expectedState, String expectedReason) {
        }
        List<Case> cases = List.of(new Case(CapabilityState.UNAVAILABLE, "no dry run here", "UNAVAILABLE",
                "no dry run here"), new Case(CapabilityState.DEGRADED, "slow", "DEGRADED", "slow"),
                new Case(null, null, "UNAVAILABLE", "the target does not declare it"));
        for (Case c : cases) {
            agent = new FakeAgentConnection();
            toy.install(agent);
            if (c.state() != null) {
                agent.capability("toy.negate", c.state(), c.reason());
            }
            String line = "oracle neg: cross-check toy.negate.check skipped: capability toy.negate is "
                    + c.expectedState() + " (" + c.expectedReason() + ")";
            Files.deleteIfExists(file("seed-a"));
            for (BindMode mode : List.of(BindMode.LIVE, BindMode.APPROVE, BindMode.REPLAY)) {
                toy.checkCalls.clear();
                BindOutcome outcome = bind(plan(GUARDED), options(mode));
                String what = c.expectedState() + "/" + mode.label();
                assertEquals(List.of(line), outcome.skipped(), what);
                assertTrue(toy.checkCalls.isEmpty(), what + ": not run");
                Map<String, Object> record = record("crosscheck");
                assertEquals(List.of("oracle", "fn", "check", "tolerance", "skipped", "capability", "state", "reason",
                        "type"), new ArrayList<>(record.keySet()), what);
                assertEquals(true, record.get("skipped"));
                assertEquals("toy.negate", record.get("capability"));
                assertEquals(c.expectedState(), record.get("state"));
                assertEquals(c.expectedReason(), record.get("reason"));
                assertTrue(outcome.bindings().crossChecks().get("neg").skipped());
                int oracle = events.indexOf("oracle");
                assertEquals(List.of("oracle", "crosscheck", "guard"), events.subList(oracle, oracle + 3),
                        what + ": the guard still runs");
            }
        }
        agent = new FakeAgentConnection();
        toy.install(agent);
        agent.capability("toy.negate", CapabilityState.OK, null);
        toy.checkCalls.clear();
        BindOutcome ran = bind(plan(GUARDED), options(BindMode.LIVE));
        assertEquals(List.of(), ran.skipped());
        assertEquals(1, toy.checkCalls.size(), "an OK capability runs the check");
    }

    @Test
    void anUnguardedCheckNeverConsultsTheCapabilitiesAndAGuardedBindReadsThemOnce() throws Exception {
        bind(plan(YAML), options(BindMode.LIVE));
        assertEquals(0, agent.capabilityReads, "an unguarded check never consults the capability");
        String twoGuarded = GUARDED.replace("steps:", "  needs: { fn: Needs, args: { limit: \"1\", amount:"
                + " $item.deposit }, crossCheck: { check: toy.needs.check, capability: toy.needs } }\nsteps:");
        BindOutcome outcome = bind(plan(twoGuarded), options(BindMode.LIVE));
        assertEquals(1, agent.capabilityReads, "one read per bind, however many guarded checks");
        assertEquals(2, outcome.skipped().size());
    }

    // ------------------------------------------------------------------------------------------- bounds, mask

    @Test
    void theChecksCallsAreClampedToTheBudgetWhichIsCheckedBeforeTheCall() throws Exception {
        toy.negateCallTimeoutMs = 119_000L;
        bind(plan(YAML), options(BindMode.LIVE));
        assertEquals(60_000L, agent.callsTo("toy.negate").get(0).options().timeoutMs(), "clamped to what is left");
        agent.calls.clear();
        bind(plan(YAML.replace("steps:", "timeouts: { bind: 3s }\nsteps:")), options(BindMode.LIVE));
        assertEquals(3_000L, agent.callsTo("toy.negate").get(0).options().timeoutMs());

        FakeAgentConnection.Handler item = agent.handler("toy.item");
        agent.on("toy.item", params -> {
            clock.advance(Duration.ofSeconds(61));
            return item.handle(params);
        });
        toy.checkCalls.clear();
        BindException spent = fails(plan(YAML), options(BindMode.LIVE));
        assertEquals(FailureKind.ENVIRONMENT, spent.kind());
        assertEquals("binding timed out in slot oracle.neg (budget 60000 ms)", spent.getMessage());
        assertTrue(toy.checkCalls.isEmpty(), "the budget is checked before the call");
    }

    @Test
    void theRecordsEvidencePassesTheMask() throws Exception {
        toy.negateEvidenceNote = "seen token " + TestCredentials.PW_100 + " in the dry run";
        BindOutcome outcome = bind(plan(YAML), options(BindMode.LIVE));
        Map<?, ?> evidence = (Map<?, ?>) record("crosscheck").get("evidence");
        assertEquals("seen token <redacted> in the dry run", evidence.get("note"));
        assertFalse(String.valueOf(records).contains(TestCredentials.PW_100));
        assertFalse(String.valueOf(outcome.bindings().crossChecks()).contains(TestCredentials.PW_100));
    }

    /** A credential of 17 characters with a quote and a backslash: a mask applied to written JSON text misses it. */
    static final String QUOTED = "pw\"in\\side-secret";

    static final String SHAPES = """
            scenario: toy-check
            pack: toy
            oracle:
              same:
                fn: Echo
                args: 'pw"in\\side-secret'
                crossCheck: %s
            steps: []
            """;

    /** Returns its input. */
    static final class Echo implements Oracle<String, String> {
        @Override
        public String fn() {
            return "Echo";
        }

        @Override
        public String compute(String input) {
            return input;
        }
    }

    /** A value of a shape the SPI asks a pack not to put into a result. */
    record Seen(String value) {
    }

    /** Puts what it was given into a set, an array and a record; or, with {@link #nan}, a NaN into a set. */
    static final class ShapesCheck implements CrossCheck<String, String> {
        boolean nan;

        @Override
        public String id() {
            return "toy.shapes.check";
        }

        @Override
        public String oracleFn() {
            return "Echo";
        }

        @Override
        public CrossCheckResult check(String input, String output, BigDecimal tolerance, VerifyContext ctx) {
            if (nan) {
                return CrossCheckResult.agree("not a number", Map.of("set", Set.of(Double.NaN)), Map.of());
            }
            Map<String, Object> shapes = new LinkedHashMap<>();
            shapes.put("plain", input);
            shapes.put("set", Set.of(input));
            shapes.put("array", new String[] {input});
            shapes.put("record", new Seen(input));
            shapes.put("nested", List.of(Map.of("deeper", Set.of(input))));
            return CrossCheckResult.agree("saw " + input, shapes, shapes);
        }
    }

    /** True when {@code secret} occurs in any string, key or other object's text inside {@code value}. */
    private static boolean holds(Object value, String secret) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().anyMatch(e -> holds(e.getKey(), secret) || holds(e.getValue(), secret));
        }
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (holds(item, secret)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Object[] array) {
            return holds(Arrays.asList(array), secret);
        }
        return value != null && String.valueOf(value).contains(secret);
    }

    /**
     * phase3-contracts.md 8.2, P2 11.4: what the engine records, keeps ({@code Bindings.crossChecks()},
     * {@code BindOutcome.skipped()}) and returns from its hook is in JSON form and masked, whatever shape the pack
     * gave it (a set, an array, a record, which the mask cannot look into), for the check's values, its reason, the
     * oracle's value and a skipped check's capability reason; a value the codec cannot write is a pack defect.
     */
    @Test
    void whatTheEngineRecordsKeepsAndReturnsIsJsonLikeAndMaskedWhateverItsShape() throws Exception {
        ShapesCheck check = new ShapesCheck();
        pack.resolution = ResolutionCatalogue.builder(toy.catalogue()).oracle(new Echo()).crossCheck(check).build();
        BindOptions options = options(BindMode.LIVE).withCredentials(TestCredentials.of(Map.of("version", 1L,
                "credentials", Map.of("staff", Map.of("1", Map.of("password", QUOTED))))));
        BindOutcome outcome = bind(plan(SHAPES.formatted("toy.shapes.check")), options);
        Map<String, Object> shapes = new LinkedHashMap<>();
        shapes.put("array", List.of("<redacted>"));
        shapes.put("nested", List.of(Map.of("deeper", List.of("<redacted>"))));
        shapes.put("plain", "<redacted>");
        shapes.put("record", "Seen[value=<redacted>]");
        shapes.put("set", List.of("<redacted>"));
        Map<String, Object> recorded = record("crosscheck");
        Map<String, Object> kept = outcome.bindings().crossChecks().get("same").toJson();
        for (Map<String, Object> json : List.of(recorded, kept)) {
            assertEquals(shapes, json.get("actual"));
            assertEquals(shapes, json.get("evidence"));
            assertEquals("<redacted>", json.get("expected"), "the oracle's value");
            assertEquals("saw <redacted>", json.get("reason"));
            assertFalse(holds(json, QUOTED), String.valueOf(json));
        }
        assertFalse(holds(outcome.bindings().crossChecks(), QUOTED));
        try (ResolutionEngine engine = new ResolutionEngine(pack, agent, options, Duration.ofSeconds(30), null)) {
            engine.evaluate("same", "Echo", QUOTED, new Plan.CrossCheckSpec("toy.shapes.check", null, null));
            CrossCheckResult result = engine.crossCheck("same");
            assertEquals(shapes, result.actual(), "the hook returns what was recorded");
            assertEquals(shapes, result.evidence());
        }

        agent.capability("toy.shapes", CapabilityState.UNAVAILABLE, "offline: " + QUOTED);
        BindOutcome skipped = bind(plan(SHAPES.formatted("{ check: toy.shapes.check, capability: toy.shapes }")),
                options);
        assertEquals(List.of("oracle same: cross-check toy.shapes.check skipped: capability toy.shapes is"
                + " UNAVAILABLE (offline: <redacted>)"), skipped.skipped());
        assertEquals("offline: <redacted>", record("crosscheck").get("reason"));
        assertEquals("<redacted>", skipped.bindings().crossChecks().get("same").expected());
        assertFalse(holds(skipped.bindings().crossChecks(), QUOTED));
        assertFalse(holds(record("crosscheck"), QUOTED));

        check.nan = true;
        BindException nan = fails(plan(SHAPES.formatted("toy.shapes.check")), options);
        assertEquals(FailureKind.ENVIRONMENT, nan.kind());
        assertTrue(nan.getMessage().startsWith("pack defect: cross-check toy.shapes.check failed: its actual or"
                + " evidence cannot be written as JSON: "), nan.getMessage());
    }

    // ------------------------------------------------------------------------------------------- the engine

    @Test
    void theEnginesCrossCheckReturnsWhatTheBinderRecorded() throws Exception {
        bind(plan(YAML), options(BindMode.LIVE));
        Map<String, Object> recorded = record("crosscheck");
        try (ResolutionEngine engine = new ResolutionEngine(pack, agent, options(BindMode.LIVE),
                Duration.ofSeconds(30), null)) {
            Object args = List.of("5.00");
            Object value = engine.evaluate("Negate", args);
            Plan.CrossCheckSpec spec = new Plan.CrossCheckSpec("toy.negate.check", "0.00", null);
            engine.bindOracle(new OracleValue("neg", "Negate", ResolutionEngine.hash(args), value, null, spec), args);
            CrossCheckResult result = engine.crossCheck("neg");
            assertEquals(recorded.get("agreed"), result.agreed());
            assertEquals(recorded.get("reason"), result.reason());
            assertEquals(recorded.get("actual"), result.actual());
            assertEquals(recorded.get("evidence"), result.evidence());
            assertTrue(engine.bindings().crossChecks().get("neg").agreed());
            assertThrows(IllegalArgumentException.class, () -> engine.crossCheck("nope"));
            engine.bindOracle(new OracleValue("plain", "Negate", ResolutionEngine.hash(args), value, null), args);
            assertThrows(IllegalArgumentException.class, () -> engine.crossCheck("plain"));
        }
    }
}
