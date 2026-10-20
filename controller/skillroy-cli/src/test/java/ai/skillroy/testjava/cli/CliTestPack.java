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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CheckScope;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PackCapability;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The domain pack {@code clitest} the CLI tests run scenarios with, registered through
 * {@code META-INF/services} in the test resources: the verb {@code poke {by?}} calls {@code test.poke} on the
 * {@link FakeAgent} and waits for its {@code test.poked} event; the fact {@code counter} reads {@code test.counter};
 * the fact {@code crash} throws an {@link InternalError}. Its one oracle, {@code Echo}, lets a scenario need
 * bindings; its cross-check {@code test.echo.check} (phase3-contracts.md 5.1) asks the fake agent's
 * {@code test.echo} to echo the same input and agrees when the answers match; {@code test.echo.shapes} does the same
 * with the answer in a set, an array and a record. Its one query kind, {@code Staff}, always resolves to the staff
 * member {@code S7} (a {@code java} resolver and a verifier that passes), so that a session can resolve a slot with a
 * credential.
 *
 * <p>Phase 8 (phase8-contracts.md 13.1 row 48): a {@code SUITE} pre-flight check {@code clitest.backend} and the pack
 * capability {@code clitest.env.ready} ({@code OK}, or {@code UNAVAILABLE} with {@link #envUnavailable} as the reason),
 * each counting its evaluations in a static counter, since every command loads its own instance; {@link #reset()}
 * before and after a test that reads them.
 */
public final class CliTestPack implements DomainPack {

    /** The pack capability (phase8-contracts.md 9.1): {@code <packId>.<name>}. */
    static final String ENV_READY = "clitest.env.ready";
    /** The {@code SUITE} pre-flight check (phase8-contracts.md 9.3). */
    static final String BACKEND = "clitest.backend";
    /** How often {@link #BACKEND} ran, over every instance. */
    static final AtomicInteger BACKEND_RUNS = new AtomicInteger();
    /** How often {@link #ENV_READY} was evaluated, over every instance. */
    static final AtomicInteger ENV_EVALUATIONS = new AtomicInteger();
    /** The reason {@link #ENV_READY} is {@code UNAVAILABLE} for; null = {@code OK}. */
    static volatile String envUnavailable;
    /** Declares {@link #ENV_READY} twice: a declaration error every host refuses. */
    static volatile boolean declareTwice;

    /** Clears the counters and the knobs. */
    static void reset() {
        BACKEND_RUNS.set(0);
        ENV_EVALUATIONS.set(0);
        envUnavailable = null;
        declareTwice = false;
    }

    @Override
    public String id() {
        return "clitest";
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
                return Set.of("poke");
            }

            @Override
            public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
                if (!verb.equals("poke")) {
                    throw new ScenarioException("unknown verb " + verb);
                }
                Map<String, Object> params = new LinkedHashMap<>();
                if (args instanceof Map<?, ?> map && map.get("by") != null) {
                    params.put("by", map.get("by"));
                }
                return List.of(PlannedCall.event("test.poke", params, AwaitSpec.of("test.poked")));
            }
        };
    }

    @Override
    public SyncPolicy syncPolicy() {
        return new SyncPolicy(null, List.of(), List.of(), null, null);
    }

    @Override
    public List<FactProvider> facts() {
        return List.of(new FactProvider() {
            @Override
            public String root() {
                return "counter";
            }

            @Override
            public Object fetch(AgentConnection agent) throws Exception {
                return agent.call("test.counter", Map.of()).value();
            }
        }, new FactProvider() {
            @Override
            public String root() {
                return "crash";
            }

            @Override
            public Object fetch(AgentConnection agent) {
                // A pack bug no handler is meant to survive: the run breaks, the JUnit report must still be written.
                throw new InternalError("pack bug");
            }
        });
    }

    @Override
    public List<PreflightCheck> preflight() {
        return List.of(new PreflightCheck() {
            @Override
            public String id() {
                return BACKEND;
            }

            @Override
            public CheckScope scope() {
                return CheckScope.SUITE;
            }

            @Override
            public String check(AgentConnection agent) {
                BACKEND_RUNS.incrementAndGet();
                return null;
            }
        });
    }

    @Override
    public List<PackCapability> capabilities() {
        PackCapability ready = new PackCapability() {
            @Override
            public String id() {
                return ENV_READY;
            }

            @Override
            public String description() {
                return "the test environment is ready";
            }

            @Override
            public Capability evaluate(PreflightCheck.PreflightContext ctx) {
                ENV_EVALUATIONS.incrementAndGet();
                String reason = envUnavailable;
                return reason == null ? new Capability(ENV_READY, CapabilityState.OK, null, null)
                        : new Capability(ENV_READY, CapabilityState.UNAVAILABLE, reason, null);
            }
        };
        return declareTwice ? List.of(ready, ready) : List.of(ready);
    }

    @Override
    public ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue resolution() {
        return ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue.builder().oracle(new Echo())
                .crossCheck(new EchoCheck()).crossCheck(new EchoShapesCheck())
                .query(QueryType.of("Staff", Staff.class)).resolver(new StaffResolver()).verifier(new StaffKnown())
                .build();
    }

    /**
     * As {@link EchoCheck}, but its actual and evidence hold the answer in shapes the SPI asks a pack not to use (a
     * set, an array, a record), so that the tests can show what {@code --json} prints of them: JSON-like and masked
     * (phase3-contracts.md 8.2).
     */
    static final class EchoShapesCheck implements ai.skillroy.testjava.scenario.resolve.CrossCheck<String, String> {

        /** A record the check puts into its values. */
        record Seen(String text) {
        }

        @Override
        public String id() {
            return "test.echo.shapes";
        }

        @Override
        public String oracleFn() {
            return "Echo";
        }

        @Override
        public ai.skillroy.testjava.scenario.resolve.CrossCheckResult check(String input, String output,
                java.math.BigDecimal tolerance, ai.skillroy.testjava.scenario.resolve.VerifyContext ctx)
                throws ai.skillroy.testjava.controller.AgentRpcException,
                ai.skillroy.testjava.controller.AgentLostException {
            String answer = String.valueOf(((Map<?, ?>) ctx.agent().call("test.echo", Map.of("text", input)).value())
                    .get("text"));
            Map<String, Object> shapes = new LinkedHashMap<>();
            shapes.put("set", Set.of(answer));
            shapes.put("array", new String[] {answer});
            shapes.put("record", new Seen(answer));
            String reason = "the fake echoes " + answer;
            return output.equals(answer) ? ai.skillroy.testjava.scenario.resolve.CrossCheckResult.agree(reason, shapes,
                    shapes) : ai.skillroy.testjava.scenario.resolve.CrossCheckResult.disagree(reason, shapes, shapes);
        }
    }

    /** The application's own echo, asked through {@code test.echo}. */
    static final class EchoCheck implements ai.skillroy.testjava.scenario.resolve.CrossCheck<String, String> {
        @Override
        public String id() {
            return "test.echo.check";
        }

        @Override
        public String oracleFn() {
            return "Echo";
        }

        @Override
        public ai.skillroy.testjava.scenario.resolve.CrossCheckResult check(String input, String output,
                java.math.BigDecimal tolerance, ai.skillroy.testjava.scenario.resolve.VerifyContext ctx)
                throws ai.skillroy.testjava.controller.AgentRpcException,
                ai.skillroy.testjava.controller.AgentLostException {
            Object answer = ((Map<?, ?>) ctx.agent().call("test.echo", Map.of("text", input)).value()).get("text");
            String reason = "the fake echoes " + answer;
            Map<String, Object> evidence = Map.of("test.echo", String.valueOf(answer));
            return output.equals(answer) ? ai.skillroy.testjava.scenario.resolve.CrossCheckResult.agree(reason,
                    answer, evidence) : ai.skillroy.testjava.scenario.resolve.CrossCheckResult.disagree(reason,
                    answer, evidence);
        }
    }

    /** A staff member of the fake desk, by role (ignored: there is one). */
    record Staff(String role) implements Query<StaffRef> {
    }

    /** A staff member: the code is the id of its entry in the credentials file's realm {@code staff}. */
    record StaffRef(String code, String name) {
    }

    /** The one staff member, {@code S7}. */
    static final class StaffResolver implements Resolver<Staff, StaffRef> {
        @Override
        public String id() {
            return "clitest.java.Staff";
        }

        @Override
        public Class<Staff> queryClass() {
            return Staff.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_JAVA;
        }

        @Override
        public List<ResolverCandidate<StaffRef>> candidates(Staff query, ResolveContext ctx) {
            return List.of(ResolverCandidate.of(new StaffRef("S7", "Desk Seven"), "staff S7"));
        }
    }

    /** Every staff member the resolver offers is known. */
    static final class StaffKnown implements Verifier<Staff, StaffRef> {
        @Override
        public String id() {
            return "clitest.staff.known";
        }

        @Override
        public Class<Staff> queryClass() {
            return Staff.class;
        }

        @Override
        public VerifierResult verify(Staff query, StaffRef candidate, VerifyContext ctx) {
            return VerifierResult.pass("known", Map.of());
        }
    }

    /** Returns its input. */
    static final class Echo implements ai.skillroy.testjava.scenario.resolve.Oracle<String, String> {
        @Override
        public String fn() {
            return "Echo";
        }

        @Override
        public String compute(String input) {
            return input;
        }
    }
}
