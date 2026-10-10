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

package ai.skillroy.testjava.inference.resolve;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.sql.LendingDatabase;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.CapabilityContributor;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.BatchableResolver;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.ContributionException;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverContributor;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 5.5 and 15.1 items 6 and 7 from this module's side: the contributor is found through
 * {@code ServiceLoader}, offers {@code inference.sql.<kind>} for every kind of a pack with an allow-list when a gateway
 * is named and nothing otherwise, reports its capabilities, refuses bad settings; and the real binder, finding it on
 * the class path, resolves a slot that names {@code llm-sql} from the committed recording through the JDBC channel:
 * strategy {@code LLM_SQL}, approval {@code REQUIRED}, the confidence, the usage from the ledger.
 */
class InferenceContributorTest {

    @TempDir
    Path tmp;

    private static InferenceSettings settings(String gateway, Path recordings, Map<String, String> thresholds) {
        return new InferenceSettings(gateway, recordings, false, null, null, null, null, thresholds, null, true, false,
                null, false, null);
    }

    @Test
    void theContributorIsFoundThroughServiceLoader() {
        List<Class<?>> found = new ArrayList<>();
        for (ResolverContributor c : ServiceLoader.load(ResolverContributor.class)) {
            found.add(c.getClass());
        }
        assertEquals(List.of(InferenceContributor.class), found);
    }

    @Test
    void itOffersOneResolverPerKindWhenAGatewayIsNamed() throws Exception {
        try (InferenceContributor c = new InferenceContributor()) {
            List<Resolver<?, ?>> offered = c.resolvers(new DeskPack(), settings("recorded", tmp, null),
                    new CostLedger(), Clock.systemUTC());
            assertEquals(List.of("inference.sql.ItemByCategory", "inference.sql.StaffWithRole",
                    "inference.sql.StaffPlain", "inference.sql.LoanCount", "inference.sql.LoansByStatus"),
                    offered.stream().map(Resolver::id).toList());
            for (Resolver<?, ?> r : offered) {
                assertEquals(Strategy.LLM_SQL, r.strategy());
                assertInstanceOf(BatchableResolver.class, r);
            }
            assertEquals(DeskPack.ItemByCategory.class, offered.get(0).queryClass());
            assertNotNull(((LlmSqlResolver<?, ?>) offered.get(1)).valueLoader(), "the kind's loader is attached");
            assertTrue(((LlmSqlResolver<?, ?>) offered.get(2)).valueLoader() == null);
            assertNotNull(c.meter());
            assertEquals(new java.math.BigDecimal("0.50"), c.meter().limit(), "the scenario budget's default");
        }
    }

    @Test
    void itOffersNothingWithoutAGatewayOrAnAllowList() throws Exception {
        try (InferenceContributor c = new InferenceContributor()) {
            assertTrue(c.resolvers(new DeskPack(), InferenceSettings.none(), new CostLedger(), Clock.systemUTC())
                    .isEmpty());
            assertTrue(c.resolvers(new DeskPack(Set.of(), false), settings("recorded", tmp, null), new CostLedger(),
                    Clock.systemUTC()).isEmpty());
        }
    }

    /**
     * phase7-contracts.md 8.5 and 13.2 item 9 (D326, D354): the contributor is found as a {@code CapabilityContributor}
     * too and answers {@code inference.gateway} and {@code inference.sql} with their reasons; it no longer answers
     * {@code inference.vision} (the vision module's contributor owns it alone), and the deprecated
     * {@code ResolverContributor.capabilities} answers nothing.
     */
    @Test
    @SuppressWarnings("deprecation")
    void itReportsItsCapabilities() {
        List<Class<?>> found = new ArrayList<>();
        for (CapabilityContributor contributor : CapabilityContributor.load(getClass().getClassLoader())) {
            found.add(contributor.getClass());
        }
        assertTrue(found.contains(InferenceContributor.class), "found through ServiceLoader: " + found);
        InferenceContributor c = new InferenceContributor();
        assertEquals("inference", c.id());
        CapabilityMatrix agent = new CapabilityMatrix(Map.of());
        Map<String, Capability> on = c.capabilities(new CapabilityContributor.CapabilityContext(settings("recorded",
                tmp, null), BindMode.LIVE, agent, Set.of(), new DeskPack()));
        assertEquals(List.of("inference.gateway", "inference.sql"), new ArrayList<>(on.keySet()),
                "inference.vision is no longer answered here");
        assertEquals(new Capability("inference.gateway", CapabilityState.OK, null, "recorded"),
                on.get("inference.gateway"));
        assertEquals(new Capability("inference.sql", CapabilityState.OK, null, "recorded"), on.get("inference.sql"));
        Map<String, Capability> noList = c.capabilities(new CapabilityContributor.CapabilityContext(settings(
                "recorded", tmp, null), BindMode.LIVE, agent, Set.of(), new DeskPack(Set.of(), false)));
        assertEquals(new Capability("inference.sql", CapabilityState.UNAVAILABLE, "no allow-list", null),
                noList.get("inference.sql"));
        Map<String, Capability> off = c.capabilities(new CapabilityContributor.CapabilityContext(
                InferenceSettings.none(), BindMode.LIVE, agent, Set.of(), new DeskPack()));
        assertEquals(new Capability("inference.gateway", CapabilityState.UNAVAILABLE, "gateway none", null),
                off.get("inference.gateway"));
        assertEquals(new Capability("inference.sql", CapabilityState.UNAVAILABLE, "gateway none", null),
                off.get("inference.sql"));
        assertEquals(Map.of(), c.capabilities(settings("recorded", tmp, null)), "the deprecated method answers"
                + " nothing");
    }

    @Test
    void badSettingsAreAContributionError() {
        try (InferenceContributor c = new InferenceContributor()) {
            ContributionException e = assertThrows(ContributionException.class, () -> c.resolvers(new DeskPack(),
                    settings("recorded", tmp, Map.of("confidence", "1.5")), new CostLedger(), Clock.systemUTC()));
            assertEquals("thresholds.confidence must be a number from 0 to 1", e.getMessage());
            assertThrows(ContributionException.class, () -> c.resolvers(new DeskPack(), settings("recorded", null,
                    null), new CostLedger(), Clock.systemUTC()), "recorded without a directory");
        }
    }

    /** An agent the bind never needs to call: the data is read over JDBC and the plan has no step. */
    private static final class QuietAgent implements AgentConnection {
        final List<String> calls = new ArrayList<>();

        @Override
        public RpcResult call(String method, Map<String, Object> params) {
            calls.add(method);
            throw new IllegalStateException("unexpected call " + method);
        }

        @Override
        public RpcResult call(String method, Map<String, Object> params, CallOptions options) {
            return call(method, params);
        }

        @Override
        public Event await(String topics, long sinceSeq, Map<String, Object> match, Duration timeout) {
            throw new IllegalStateException("unexpected await");
        }

        @Override
        public CapabilityMatrix capabilities() {
            return new CapabilityMatrix(Map.of());
        }

        @Override
        public List<PrimitiveDescriptor> schema() {
            return List.of();
        }

        @Override
        public Map<String, Object> ping() {
            return Map.of();
        }

        @Override
        public void close() {
        }
    }

    @Test
    void theBinderResolvesAnLlmSqlSlotThroughTheContributorFromTheCommittedRecording() throws Exception {
        String yaml = """
                scenario: desk-llm
                pack: lending
                resolve:
                  item: { query: ItemByCategory, args: { category: POWER, maxDeposit: "60.00" } }
                steps: []
                """;
        DeskPack pack = new DeskPack();
        Plan compiled = PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "desk-llm.yaml", pack);
        Plan plan = withStrategy(compiled, "item", List.of("llm-sql"));
        Path recordings = Path.of(InferenceContributorTest.class.getResource("/inference").toURI());
        CostLedger ledger = new CostLedger();
        QuietAgent agent = new QuietAgent();
        try (LendingDatabase db = LendingDatabase.create("bind")) {
            BindOptions options = BindOptions.defaults().withMode(BindMode.LIVE).withEnvId("test-env")
                    .withBindingsRoot(tmp).withCredentials(db.credentials()).withLedger(ledger)
                    .withInference(settings("recorded", recordings, null));
            List<Map<String, Object>> records = new ArrayList<>();
            BindOutcome outcome = Binder.bind(plan, pack, agent, options, (type, fields) -> {
                Map<String, Object> r = new java.util.LinkedHashMap<>(fields);
                r.put("type", type);
                records.add(r);
            });
            Resolution item = outcome.bindings().resolutions().get("item");
            assertEquals(Map.of("code", "DRL-01", "deposit", "40.00", "title", "Cordless drill"), item.value());
            assertEquals(Strategy.LLM_SQL, item.strategy());
            assertEquals("inference.sql.ItemByCategory", item.provenance().resolverId());
            assertEquals(new Approval(Approval.State.REQUIRED, null), item.approval());
            assertEquals(0.9, item.confidence());
            assertNotNull(item.usage(), "filled from the ledger");
            assertEquals("0", item.usage().costUsd(), "a recorded answer costs nothing");
            assertEquals(1, ledger.size());
            assertEquals("recorded", ledger.entries().get(0).provider());
            assertEquals("desk-llm", ledger.entries().get(0).scenarioId());
            assertTrue(item.provenance().statements().stream().anyMatch(s -> s.sql().equals("SELECT i.code, i.title,"
                    + " i.deposit FROM item i WHERE i.category = ? AND i.deposit <= ? ORDER BY i.deposit, i.code LIMIT"
                    + " 200")), "the guard's rewrite ran and is in the provenance");
            assertEquals("the items of the category within the deposit, cheapest first",
                    item.provenance().rationale(), "the rationale through the engine's seam");
            assertTrue(item.provenance().notes().stream().noneMatch(n -> n.startsWith("rationale: ")));
            String file = outcome.file().toJsonText();
            BindingsFile read = BindingsFile.parse(file);
            assertEquals(item.provenance().rationale(), read.resolutions().get("item").provenance().rationale(),
                    "the bindings file writes and reads the rationale");
            assertEquals(file, read.toJsonText(), "byte-identical when read back");
            assertTrue(records.stream().anyMatch(r -> "resolve".equals(r.get("type"))
                    && item.provenance().rationale().equals(r.get("rationale"))), "the resolve record has it too");
            assertTrue(agent.calls.isEmpty(), "the agent was not asked");
            assertTrue(records.stream().anyMatch(r -> "inference".equals(r.get("type"))));
            assertSame(InferenceLog.forLedger(ledger), InferenceLog.forLedger(ledger));
            assertEquals(1, InferenceLog.forLedger(ledger).records().size(), "the run's inference.jsonl record");
        }
    }

    private static Plan withStrategy(Plan plan, String slotName, List<String> tokens) {
        List<Plan.Slot> slots = new ArrayList<>();
        for (Plan.Slot slot : plan.slots()) {
            if (slot.name().equals(slotName)) {
                Plan.QuerySpec q = slot.query();
                slot = new Plan.Slot(slot.name(), slot.kind(), slot.value(), new Plan.QuerySpec(q.query(), q.args(),
                        tokens, q.verify()), slot.credential(), slot.capture());
            }
            slots.add(slot);
        }
        return new Plan(plan.planVersion(), plan.scenarioId(), plan.pack(), plan.packVersion(), plan.sourceHash(),
                plan.requires(), slots, plan.steps(), plan.verify(), plan.cleanup(), plan.launchProperties(),
                plan.artifacts(), plan.onPrompt(), plan.stepTimeoutMs(), plan.ui(), "bind", plan.oracles(),
                plan.resolutionGraph(), plan.bindTimeoutMs(), plan.web());
    }
}
