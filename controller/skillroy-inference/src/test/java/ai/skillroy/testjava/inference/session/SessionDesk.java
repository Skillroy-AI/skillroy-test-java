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

package ai.skillroy.testjava.inference.session;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.inference.fakes.FakeAgent;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lending desk a session's model acts on in these tests: three verbs ({@code signOn}, {@code lend},
 * {@code takeBack}) that compile to one {@code desk.*} call each, a {@code loan} fact, one query kind answered by a
 * committed Java resolver, and the overlay's answer primitive. {@link #agent()} programs a fake agent that answers
 * the desk's primitives (and offers a base mutating primitive and a destructive one, which a session never offers).
 */
final class SessionDesk implements DomainPack {

    /** A plain item of a category. */
    record PlainItem(String category) implements Query<ItemRef> {
    }

    /** An item as a step uses it. */
    record ItemRef(String code, String title) {
    }

    /** The desk's plain items, by category. */
    static final class Items implements Resolver<PlainItem, ItemRef> {

        @Override
        public String id() {
            return "lending.java.PlainItem";
        }

        @Override
        public Class<PlainItem> queryClass() {
            return PlainItem.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_JAVA;
        }

        @Override
        public List<ResolverCandidate<ItemRef>> candidates(PlainItem query, ResolveContext ctx) {
            List<ResolverCandidate<ItemRef>> out = new ArrayList<>();
            if ("GARDEN".equals(query.category())) {
                out.add(new ResolverCandidate<>(new ItemRef("GAR-02", "Lawn aerator"), "GAR-02", 1.0));
            }
            return out;
        }
    }

    /** An item the desk lends has a code (every query slot needs a verifier). */
    static final class HasCode implements Verifier<PlainItem, ItemRef> {

        @Override
        public String id() {
            return "lending.verify.code";
        }

        @Override
        public Class<PlainItem> queryClass() {
            return PlainItem.class;
        }

        @Override
        public VerifierResult verify(PlainItem query, ItemRef candidate, VerifyContext ctx) {
            return candidate.code() != null ? VerifierResult.pass("has a code", Map.of())
                    : VerifierResult.fail("no code", Map.of());
        }
    }

    String answerPrimitive = "desk.assistAnswer";
    private final ResolutionCatalogue catalogue = ResolutionCatalogue.builder()
            .query(QueryType.of("PlainItem", PlainItem.class)).resolver(new Items()).verifier(new HasCode()).build();

    /** A fake agent that answers the desk's primitives; {@code desk.loan} reflects what was lent. */
    static FakeAgent agent() {
        FakeAgent agent = new FakeAgent();
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("lines", new ArrayList<>());
        agent.describe("desk.signOn", true, false).describe("desk.lend", true, false)
                .describe("desk.takeBack", true, false).describe("desk.loan", false, false)
                .describe("desk.wipeLedger", true, true).describe("swing.click", true, false)
                .describe("swing.snapshot", false, false).describe("data.query", false, false)
                .describe("desk.assistAnswer", true, false);
        agent.on("desk.signOn", params -> {
            agent.publish("desk.signedOn", Map.of("staff", String.valueOf(params.get("staff"))));
            return Map.of("signedOn", true);
        });
        agent.on("desk.lend", params -> {
            @SuppressWarnings("unchecked")
            List<Object> lines = (List<Object>) loan.get("lines");
            lines.add(Map.of("item", String.valueOf(params.get("item"))));
            agent.publish("loan.line", Map.of("item", String.valueOf(params.get("item"))));
            return Map.of("lent", true);
        });
        agent.on("desk.takeBack", params -> Map.of("returned", true));
        agent.on("desk.loan", params -> new LinkedHashMap<>(loan));
        agent.value("swing.snapshot", Map.of("seq", 1L, "truncated", false, "windows", List.of(Map.of("ref", "h1",
                "role", "window", "name", "Desk"))));
        agent.on("desk.assistAnswer", params -> Map.of("shown", true));
        return agent;
    }

    @Override
    public String id() {
        return "lending";
    }

    @Override
    public String version() {
        return "0.0.2-test";
    }

    @Override
    public StepLibrary steps() {
        return new StepLibrary() {
            @Override
            public Set<String> verbs() {
                return Set.of("signOn", "lend", "takeBack");
            }

            @Override
            public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
                Map<?, ?> a = args instanceof Map<?, ?> m ? m : Map.of();
                Map<String, Object> params = new LinkedHashMap<>();
                a.forEach((k, v) -> params.put(String.valueOf(k), v));
                return switch (verb) {
                    case "signOn" -> List.of(new PlannedCall("desk.signOn", params, Sync.NONE, 0));
                    case "lend" -> {
                        if (!(a.get("item") instanceof String)) {
                            throw new ScenarioException("lend: 'item' is required");
                        }
                        yield List.of(new PlannedCall("desk.lend", params, Sync.NONE, 0));
                    }
                    case "takeBack" -> List.of(new PlannedCall("desk.takeBack", params, Sync.NONE, 0));
                    default -> throw new ScenarioException("unknown verb " + verb);
                };
            }

            @Override
            public Map<String, Object> argsSchema(String verb) {
                return switch (verb) {
                    case "signOn" -> Map.of("type", "object", "properties", Map.of("staff", Map.of("type",
                            "string")), "required", List.of("staff"));
                    case "lend" -> Map.of("type", "object", "properties", Map.of("item", Map.of("type", "string"),
                            "qty", Map.of("type", "integer")), "required", List.of("item"));
                    default -> null;
                };
            }
        };
    }

    @Override
    public SyncPolicy syncPolicy() {
        return new SyncPolicy(null, null, null, null, null);
    }

    @Override
    public List<FactProvider> facts() {
        return List.of(new FactProvider() {
            @Override
            public String root() {
                return "loan";
            }

            @Override
            public Object fetch(AgentConnection agent) throws Exception {
                return agent.call("desk.loan", Map.of()).value();
            }
        });
    }

    @Override
    public ResolutionCatalogue resolution() {
        return catalogue;
    }

    @Override
    public String assistAnswerPrimitive() {
        return answerPrimitive;
    }
}
