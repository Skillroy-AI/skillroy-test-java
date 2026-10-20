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

package ai.skillroy.testjava.junit;

import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.CrossCheck;
import ai.skillroy.testjava.scenario.resolve.CrossCheckResult;
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A pack with no verbs of its own (scenarios use the base verbs and the base {@code app} fact), and one oracle,
 * {@code Echo}, so that a scenario can need bindings, with a cross-check, {@code mini.echo.check}, that asks the
 * application's {@code mini.echo} (phase3-contracts.md 5.1).
 */
public final class MiniPack implements DomainPack {

    /** The pack's pre-flight checks (none by default; phase8-contracts.md 13.1 row 47 sets a SUITE one). */
    volatile List<PreflightCheck> preflight = List.of();

    @Override
    public String id() {
        return "mini";
    }

    @Override
    public String version() {
        return "0.0.1";
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
                return List.of();
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
    public List<PreflightCheck> preflight() {
        return preflight;
    }

    @Override
    public ResolutionCatalogue resolution() {
        return ResolutionCatalogue.builder().oracle(new Echo()).crossCheck(new EchoCheck()).build();
    }

    /** Agrees when the application echoes what the oracle computed. */
    static final class EchoCheck implements CrossCheck<String, String> {
        @Override
        public String id() {
            return "mini.echo.check";
        }

        @Override
        public String oracleFn() {
            return "Echo";
        }

        @Override
        public CrossCheckResult check(String input, String output, BigDecimal tolerance, VerifyContext ctx)
                throws ai.skillroy.testjava.controller.AgentRpcException,
                ai.skillroy.testjava.controller.AgentLostException {
            Object answer = ((Map<?, ?>) ctx.agent().call("mini.echo", Map.of("text", input)).value()).get("text");
            return output.equals(answer) ? CrossCheckResult.agree("echoes " + answer, answer, Map.of())
                    : CrossCheckResult.disagree("echoes " + answer, answer, Map.of());
        }
    }

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
}
