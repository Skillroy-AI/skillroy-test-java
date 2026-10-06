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

package ai.skillroy.testjava.scenario.resolve;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.ResolutionEngine;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
 * The interactive hook of phase3-contracts.md 5.3 through public members alone, from outside the engine's package:
 * {@link ResolutionEngine#evaluate(String, String, Object, Plan.CrossCheckSpec)} keeps a named oracle with its
 * cross-check, and {@link ResolutionEngine#crossCheck(String)} then returns what the binder records for the same
 * oracle (11.1 item 2); the two-argument {@code evaluate} keeps nothing by name.
 */
class CrossCheckHookTest {

    static final String YAML = """
            scenario: hook
            pack: hook
            oracle:
              neg: { fn: Negate, args: ["5.00"], crossCheck: { check: hook.negate.check, tolerance: "0.00" } }
            steps: []
            """;

    static final Plan.CrossCheckSpec SPEC = new Plan.CrossCheckSpec("hook.negate.check", "0.00", null);

    /** {@code -amount}, scale kept. */
    static final class Negate implements Oracle<BigDecimal, BigDecimal> {
        @Override
        public String fn() {
            return "Negate";
        }

        @Override
        public BigDecimal compute(BigDecimal input) {
            return input.negate();
        }
    }

    /** The application's own negation, asked through {@code hook.negate}; within the tolerance, or exact. */
    static final class NegateCheck implements CrossCheck<BigDecimal, BigDecimal> {
        @Override
        public String id() {
            return "hook.negate.check";
        }

        @Override
        public String oracleFn() {
            return "Negate";
        }

        @Override
        public List<String> requires() {
            return List.of("hook.negate");
        }

        @Override
        public boolean acceptsTolerance() {
            return true;
        }

        @Override
        public CrossCheckResult check(BigDecimal input, BigDecimal output, BigDecimal tolerance, VerifyContext ctx)
                throws AgentRpcException, ai.skillroy.testjava.controller.AgentLostException {
            Object answer = ctx.agent().call("hook.negate", Map.of("amount", input.toPlainString())).value();
            BigDecimal computed = new BigDecimal(String.valueOf(((Map<?, ?>) answer).get("amount")));
            String reason = "the application negates " + input.toPlainString() + " to " + computed.toPlainString()
                    + ", the oracle to " + output.toPlainString();
            Map<String, Object> actual = Map.of("amount", computed.toPlainString());
            Map<String, Object> evidence = Map.of("hook.negate", answer);
            BigDecimal allowed = tolerance == null ? BigDecimal.ZERO : tolerance;
            return computed.subtract(output).abs().compareTo(allowed) <= 0
                    ? CrossCheckResult.agree(reason, actual, evidence)
                    : CrossCheckResult.disagree(reason, actual, evidence);
        }
    }

    /** A pack with one oracle and its cross-check, and no verbs. */
    static final class Pack implements DomainPack {
        @Override
        public String id() {
            return "hook";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public StepLibrary steps() {
            return new StepLibrary() {
                @Override
                public Set<String> verbs() {
                    return Set.of();
                }

                @Override
                public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) {
                    throw new IllegalStateException("no verbs");
                }
            };
        }

        @Override
        public SyncPolicy syncPolicy() {
            return new SyncPolicy(null, List.of(), List.of(), null, null);
        }

        @Override
        public List<FactProvider> facts() {
            return List.of();
        }

        @Override
        public ResolutionCatalogue resolution() {
            return ResolutionCatalogue.builder().oracle(new Negate()).crossCheck(new NegateCheck()).build();
        }
    }

    /** The target: {@code hook.negate} negates, or answers {@link #answer} when it is set. */
    static final class Target implements AgentConnection {
        String answer;
        final List<String> calls = new ArrayList<>();

        @Override
        public RpcResult call(String method, Map<String, Object> params) throws AgentRpcException {
            return call(method, params, CallOptions.defaults());
        }

        @Override
        public RpcResult call(String method, Map<String, Object> params, CallOptions options)
                throws AgentRpcException {
            calls.add(method);
            if (!"hook.negate".equals(method)) {
                throw new AgentRpcException(method, new RpcError(ErrorCodes.METHOD_NOT_FOUND, "no " + method, null));
            }
            String amount = answer != null ? answer
                    : new BigDecimal(String.valueOf(params.get("amount"))).negate().toPlainString();
            return new RpcResult(Map.of("amount", amount), List.of(), 0, 0, 1, "test");
        }

        @Override
        public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout) {
            throw new UnsupportedOperationException("no events here");
        }

        @Override
        public CapabilityMatrix capabilities() {
            return new CapabilityMatrix(Map.of("hook.negate", new Capability("hook.negate", CapabilityState.OK,
                    null, null)));
        }

        @Override
        public List<PrimitiveDescriptor> schema() {
            return List.of();
        }

        @Override
        public Map<String, Object> ping() {
            return Map.of("protocol", Protocol.VERSION);
        }

        @Override
        public void close() {
            // Nothing to close.
        }
    }

    private final Pack pack = new Pack();
    private final Target target = new Target();
    private final BindOptions live = BindOptions.defaults().withMode(BindMode.LIVE).withEnvId("hook-env");

    private List<Map<String, Object>> bind() throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        Plan plan = PlanCompiler.compile(YAML.getBytes(StandardCharsets.UTF_8), "hook.yaml", pack);
        Binder.bind(plan, pack, target, live, (type, fields) -> {
            if ("crosscheck".equals(type)) {
                records.add(new LinkedHashMap<>(fields));
            }
        });
        return records;
    }

    @Test
    void aNamedOracleCanBeCrossCheckedThroughPublicMembersAndGivesWhatTheBinderRecorded() throws Exception {
        List<Map<String, Object>> recorded = bind();
        assertEquals(1, recorded.size());
        List<Map<String, Object>> seen = new ArrayList<>();
        try (ResolutionEngine engine = new ResolutionEngine(pack, target, live, Duration.ofSeconds(30),
                (type, fields) -> seen.add(new LinkedHashMap<>(fields)))) {
            Object value = engine.evaluate("neg", "Negate", List.of("5.00"), SPEC);
            assertEquals("-5.00", value);
            assertEquals("-5.00", engine.bindings().oracle("neg"), "kept under its name");
            assertEquals("-5.00", engine.bindings().lookup("$oracle.neg"), "for $oracle.<name> as well");
            assertEquals(SPEC, engine.bindings().oracles().get("neg").crossCheck());
            assertTrue(engine.bindings().crossChecks().isEmpty(), "the check runs only when asked for");

            CrossCheckResult result = engine.crossCheck("neg");
            Map<String, Object> binder = recorded.get(0);
            assertEquals(binder.get("agreed"), result.agreed());
            assertEquals(binder.get("reason"), result.reason());
            assertEquals(binder.get("actual"), result.actual());
            assertEquals(binder.get("evidence"), result.evidence());
            assertEquals("the application negates 5.00 to -5.00, the oracle to -5.00", result.reason());
            assertEquals(List.of(binder), seen, "the same crosscheck record as the binder's");
            assertTrue(engine.bindings().crossChecks().get("neg").agreed(), "kept for bindings()");
        }
    }

    @Test
    void theTwoArgumentEvaluateKeepsNothingByNameAndAnOracleWithoutACheckHasNone() throws Exception {
        try (ResolutionEngine engine = new ResolutionEngine(pack, target, live, Duration.ofSeconds(30), null)) {
            assertEquals("-7.00", engine.evaluate("Negate", List.of("7.00")));
            assertFalse(engine.bindings().oracles().containsKey("Negate"));
            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> engine.crossCheck("Negate"));
            assertEquals("no oracle 'Negate' was computed in this engine", unknown.getMessage());

            assertEquals("-5.00", engine.evaluate("plain", "Negate", List.of("5.00"), null));
            assertNull(engine.bindings().oracles().get("plain").crossCheck());
            assertThrows(IllegalArgumentException.class, () -> engine.crossCheck("plain"));
            assertTrue(target.calls.isEmpty(), "nothing was asked of the target");
        }
    }

    @Test
    void aDisagreementIsReturnedAndEvaluatingTheNameAgainReplacesTheOutcome() throws Exception {
        try (ResolutionEngine engine = new ResolutionEngine(pack, target, live, Duration.ofSeconds(30), null)) {
            target.answer = "-4.00";
            engine.evaluate("neg", "Negate", List.of("5.00"), SPEC);
            CrossCheckResult disagreed = engine.crossCheck("neg");
            assertFalse(disagreed.agreed(), "a disagreement is a result, never an exception");
            assertEquals(Map.of("amount", "-4.00"), disagreed.actual());
            assertFalse(engine.bindings().crossChecks().get("neg").agreed());

            engine.evaluate("neg", "Negate", List.of("4.00"), SPEC);
            assertEquals("-4.00", engine.bindings().oracle("neg"));
            assertFalse(engine.bindings().crossChecks().containsKey("neg"), "the old outcome is dropped");
            assertTrue(engine.crossCheck("neg").agreed());
        }
    }

    @Test
    void argumentsThatDoNotFitAreArgsAndACheckThePackLacksIsEnvironment() throws Exception {
        try (ResolutionEngine engine = new ResolutionEngine(pack, target, live, Duration.ofSeconds(30), null)) {
            BindException args = assertThrows(BindException.class, () -> engine.evaluate("neg", "Negate",
                    List.of(Map.of("not", "a number")), SPEC));
            assertEquals(FailureKind.RESOLUTION_FAILED, args.kind());
            assertEquals("ARGS", args.reason());
            assertEquals("oracle.neg", args.slot());
            assertFalse(engine.bindings().oracles().containsKey("neg"), "nothing is kept for a failure");

            engine.evaluate("odd", "Negate", List.of("5.00"), new Plan.CrossCheckSpec("hook.nope", null, null));
            BindException missing = assertThrows(BindException.class, () -> engine.crossCheck("odd"));
            assertEquals(FailureKind.ENVIRONMENT, missing.kind());
            assertTrue(missing.getMessage().startsWith("the plan names cross-check hook.nope for oracle Negate"),
                    missing.getMessage());
        }
    }
}
