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
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The domain pack {@code clitest} the CLI tests run scenarios with, registered through
 * {@code META-INF/services} in the test resources: the verb {@code poke {by?}} calls {@code test.poke} on the
 * {@link FakeAgent} and waits for its {@code test.poked} event; the fact {@code counter} reads {@code test.counter};
 * the fact {@code crash} throws an {@link InternalError}. Its one oracle, {@code Echo}, lets a scenario need
 * bindings; its cross-check {@code test.echo.check} (phase3-contracts.md 5.1) asks the fake agent's
 * {@code test.echo} to echo the same input and agrees when the answers match; {@code test.echo.shapes} does the same
 * with the answer in a set, an array and a record.
 */
public final class CliTestPack implements DomainPack {

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
    public ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue resolution() {
        return ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue.builder().oracle(new Echo())
                .crossCheck(new EchoCheck()).crossCheck(new EchoShapesCheck()).build();
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
