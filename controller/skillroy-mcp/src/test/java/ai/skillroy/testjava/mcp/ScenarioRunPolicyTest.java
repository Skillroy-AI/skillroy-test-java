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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.budget.SessionMetering;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 9.3, decision D96: {@code skillroy_scenario_run} runs a scenario only when every call of it is
 * one the server would make for the client tool by tool. Refused before anything runs, as a tool error naming the step
 * and the primitive: a raw {@code call} of a destructive primitive with {@code confirm}, of {@code agent.disable}, of
 * {@code data.query} (the statement the {@code data_query} tool refuses), of {@code web.evaluate} or
 * {@code web.waitFlag} (and the base verb {@code webWaitFlag}), a {@code _meta} in a call's arguments, a pack verb that
 * wraps a destructive primitive, a verb whose cleanup does. Run: the pack's verbs,
 * a raw call of an offered primitive, {@code mark}, and a pack verb over another module's primitive (the verb tools run
 * those already). Every expectation of such a run is evaluated on masked values
 * ({@code RunOptions.withExpectationsOnMaskedValues}).
 */
class ScenarioRunPolicyTest {

    @TempDir
    Path dir;

    /**
     * The desk's pack with three more verbs: {@code clear} (the destructive {@code desk.wipe}), {@code tidy}
     * ({@code desk.poke}, registering {@code desk.wipe} as its cleanup) and {@code peek} ({@code probe.check}, a
     * primitive of a module that is neither the domain's nor the base's).
     */
    static DomainPack extended(Desk desk) {
        DomainPack base = desk.pack;
        StepLibrary baseSteps = base.steps();
        return new DomainPack() {
            @Override
            public String id() {
                return base.id();
            }

            @Override
            public String version() {
                return base.version();
            }

            @Override
            public StepLibrary steps() {
                return new StepLibrary() {
                    @Override
                    public Set<String> verbs() {
                        Set<String> verbs = new TreeSet<>(baseSteps.verbs());
                        verbs.addAll(List.of("clear", "tidy", "peek"));
                        return verbs;
                    }

                    @Override
                    public List<PlannedCall> compile(String verb, Object args, CompileContext ctx)
                            throws ScenarioException {
                        return switch (verb) {
                            case "clear" -> List.of(new PlannedCall("desk.wipe", Map.of(), Sync.NONE, 0));
                            case "tidy" -> {
                                ctx.addCleanup(new PlannedCall("desk.wipe", Map.of(), Sync.NONE, 0));
                                yield List.of(new PlannedCall("desk.poke", Map.of(), Sync.NONE, 0));
                            }
                            case "peek" -> List.of(new PlannedCall("probe.check", Map.of(), Sync.NONE, 0));
                            default -> baseSteps.compile(verb, args, ctx);
                        };
                    }

                    @Override
                    public Map<String, Object> argsSchema(String verb) {
                        return baseSteps.argsSchema(verb);
                    }
                };
            }

            @Override
            public SyncPolicy syncPolicy() {
                return base.syncPolicy();
            }

            @Override
            public List<FactProvider> facts() {
                return base.facts();
            }

            @Override
            public void recover(AgentConnection connection) throws Exception {
                base.recover(connection);
            }

            @Override
            public ResolutionCatalogue resolution() {
                return base.resolution();
            }

            @Override
            public List<SqlAllowList> sqlAllowLists() {
                return base.sqlAllowLists();
            }
        };
    }

    private static String scenario(String id, String steps) {
        return "scenario: " + id + "\npack: desk\nsteps:\n" + steps;
    }

    private static McpSchema.CallToolResult run(McpFixture fx, String yaml) {
        return fx.call("skillroy_scenario_run", Map.of("yaml", yaml, "mode", "live"));
    }

    private static void refused(McpFixture fx, String yaml, String... fragments) {
        McpSchema.CallToolResult result = run(fx, yaml);
        String text = McpFixture.text(result);
        assertTrue(result.isError(), text);
        for (String fragment : fragments) {
            assertTrue(text.contains(fragment), "'" + fragment + "' in: " + text);
        }
    }

    @Test
    void aScenarioCallingWhatTheServerDoesNotOfferIsRefusedBeforeAnythingRuns() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options(extended(fx.desk)).mutating(true).build());
            refused(fx, scenario("wipe-confirmed", "  - enter: {value: HAM-1}\n"
                    + "  - call: {primitive: desk.wipe, args: {confirm: true}}\n"), "the scenario is refused",
                    "step s2 (call, line 5)", "the destructive primitive desk.wipe");
            refused(fx, scenario("agent-off", "  - call: {primitive: agent.disable, args: {confirm: true}}\n"),
                    "step s1 (call", "agent.disable");
            refused(fx, scenario("raw-query", "  - call: {primitive: data.query, args: {source: desk, sql: \"SELECT"
                    + " * FROM item WHERE 1=1\"}}\n"), "step s1 (call", "data.query", "data_query");
            refused(fx, scenario("evaluate", "  - call: {primitive: web.evaluate, args: {script: \"1\"}}\n"),
                    "web.evaluate", "--allow-evaluate");
            refused(fx, scenario("wait-flag", "  - call: {primitive: web.waitFlag, args: {expression: \"1\"}}\n"),
                    "web.waitFlag", "--allow-evaluate");
            refused(fx, scenario("wait-flag-verb", "  - webWaitFlag: {expression: \"1\"}\n"),
                    "step s1 (webWaitFlag", "web.waitFlag");
            refused(fx, scenario("meta", "  - call: {primitive: desk.lend, args: {item: HAM-1, _meta: {confirm:"
                    + " true}}}\n"), "_meta");
            refused(fx, scenario("confirming-verb", "  - lend: {item: HAM-1, confirm: true}\n"),
                    "step s1 (lend", "'confirm'");
            refused(fx, scenario("clear", "  - clear: {}\n"), "step s1 (clear", "desk.wipe");
            refused(fx, scenario("tidy", "  - tidy: {}\n"), "cleanup call 1", "desk.wipe");
            refused(fx, scenario("unoffered", "  - call: {primitive: evt.query, args: {}}\n"), "evt.query",
                    "does not offer");
            for (String method : List.of("desk.wipe", "agent.disable", "web.evaluate", "web.waitFlag", "desk.lend",
                    "desk.input", "desk.poke")) {
                assertTrue(fx.desk.agent.callsTo(method).isEmpty(), method + " was never called");
            }
            assertTrue(fx.desk.queries().isEmpty(), "data.query was never called but by the allow-list check at the"
                    + " start: " + fx.desk.agent.callsTo("data.query"));
            assertFalse(fx.desk.agent.callsTo("desk.wipe").stream().anyMatch(c -> c.options().confirm()));
        }
    }

    /**
     * phase6-contracts.md 4.2, 13.1 item 12: {@code PlanPolicy} refuses every {@code hook.*} and {@code rule.*}
     * primitive in a client's plan but the three reads, which run.
     */
    @Test
    void hookAndRulePrimitivesAreRefusedButTheThreeReads() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            for (String name : List.of("hook.probe", "hook.install", "hook.remove", "hook.health", "hook.events",
                    "rule.check", "rule.unload", "rule.list")) {
                fx.desk.agent.describe(name, false, false, null);
            }
            fx.desk.agent.describe("hook.stub", true, true, null);
            fx.desk.agent.describe("rule.submit", true, true, null);
            fx.desk.agent.value("hook.health", Map.of("hooks", Map.of(), "stubs", List.of()));
            fx.desk.agent.value("hook.events", Map.of("events", List.of()));
            fx.desk.agent.value("rule.list", Map.of("rules", List.of()));
            fx.start(fx.options(extended(fx.desk)).mutating(true).build());
            for (String never : List.of("hook.probe", "hook.install", "hook.remove", "rule.check", "rule.unload")) {
                refused(fx, scenario("never-" + never.replace('.', '-'), "  - call: {primitive: " + never
                        + ", args: {}}\n"), "step s1 (call", never, "never run for a client");
                assertTrue(fx.desk.agent.callsTo(never).isEmpty(), never);
            }
            for (String read : List.of("hook.health", "hook.events", "rule.list")) {
                McpSchema.CallToolResult result = run(fx, scenario("read-" + read.replace('.', '-'),
                        "  - call: {primitive: " + read + ", args: {}}\n"));
                assertFalse(result.isError(), read + ": " + McpFixture.text(result));
                assertEquals(1, fx.desk.agent.callsTo(read).size(), read);
            }
        }
    }

    /**
     * phase6-contracts.md 12.9, D156 (security fix S42): a primitive whose descriptor carries the {@code script} flag
     * is refused in a client's plan as the named script primitives are, when a pack verb reaches it too.
     */
    @Test
    void aFlaggedScriptPrimitiveIsRefusedLikeTheNamedOnes() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.desk.agent.describeScript("probe.check", false, null);
            fx.desk.agent.value("probe.check", Map.of("ok", true));
            fx.start(fx.options(extended(fx.desk)).mutating(true).build());
            refused(fx, scenario("peek-script", "  - peek: {}\n"), "step s1 (peek", "probe.check",
                    "--allow-evaluate");
            refused(fx, scenario("raw-script", "  - call: {primitive: probe.check, args: {}}\n"), "probe.check");
            assertTrue(fx.desk.agent.callsTo("probe.check").isEmpty(), "never called");
        }
    }

    @Test
    void aScenarioOfWhatTheServerOffersRuns() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.desk.agent.value("probe.check", Map.of("ok", true));
            fx.start(fx.options(extended(fx.desk)).mutating(true).build());
            McpSchema.CallToolResult result = run(fx, scenario("offered", "  - enter: {value: SAW-2}\n"
                    + "  - call: {primitive: swing.click, args: {target: h12}}\n  - mark: offered\n"
                    + "  - peek: {}\n  - lend: {item: SAW-2}\n  - expect: {desk.mode: LOAN}\n"));
            assertFalse(result.isError(), McpFixture.text(result));
            assertEquals("PASS", McpFixture.json(result).get("status"), McpFixture.text(result));
            assertEquals(1, fx.desk.agent.callsTo("swing.click").size());
            assertEquals(1, fx.desk.agent.callsTo("probe.check").size(), "a pack verb over another module's"
                    + " primitive runs, as its verb tool would");
            assertEquals(Map.of("item", "SAW-2"), fx.desk.agent.callsTo("desk.lend").get(0).params());
        }
    }

    /**
     * Phase 6 security fix S56 (D157): the server's one session meter bounds {@code skillroy_scenario_run} with
     * {@code skillroy_session_ask} and {@code skillroy_resolve}. The budget lets one tier (b) resolve's estimate through
     * but not the run's real cost plus another estimate: the first run of a scenario with one {@code llm-sql} slot makes
     * its model call and is settled on the session meter; then {@code skillroy_session_ask} ends {@code BUDGET} and the
     * second run is refused {@code BUDGET} with the meter's figures, each without a gateway call (before the fix the
     * runs were bounded by the scenario budget only and the actor's meter was untouched).
     */
    @Test
    void aScenarioRunIsMeteredOnTheSessionsBudget() throws Exception {
        try (McpFixture fx = new McpFixture(dir); TierB tierB = new TierB("scripted-mcp-session-budget", fx.desk,
                "0.20")) {
            BigDecimal estimate = SessionMetering.tierBEstimate(tierB.settings, PriceTable.of(tierB.settings));
            CostLedger ledger = new CostLedger();
            BudgetMeter meter = new BudgetMeter(BudgetMeter.Scope.SESSION, null, estimate.add(new BigDecimal("0.10")),
                    ledger);
            ServerOptions.Builder options = fx.options();
            SessionOptions session = SessionOptions.defaults(null).withBind(BindOptions.defaults()
                    .withCredentials(Desk.credentials()).withEnvId("seed-a").withLedger(ledger)).withId("sess-1");
            fx.start(options.session(session).mutating(true).inference(tierB.settings).sessionMeter(meter)
                    .actor((s, o) -> serviceActor(s, o, tierB, ledger)).build());
            String yaml = "scenario: metered\npack: desk\nresolve:\n  item: { query: Item, args: { category: tools },"
                    + " strategy: [llm-sql] }\nsteps:\n  - enter: { value: $item.code }\n";

            McpSchema.CallToolResult first = run(fx, yaml);
            assertFalse(first.isError(), McpFixture.text(first));
            int calls = tierB.gateway.requests().size();
            assertEquals(1, calls, "the run's one tier (b) call");
            assertEquals(0, new BigDecimal("0.20").compareTo(meter.spent()), "settled on the session meter: "
                    + meter.spent());
            assertTrue(fx.server.session().journal().entries().stream()
                    .anyMatch(e -> e instanceof StepJournal.BudgetEntry), "the session's budget record is journaled");

            McpSchema.CallToolResult ask = fx.call("skillroy_session_ask", Map.of("request", "lend an item"));
            assertFalse(ask.isError(), McpFixture.text(ask));
            assertEquals("BUDGET", McpFixture.json(ask).get("outcome"), McpFixture.text(ask));
            assertEquals(calls, tierB.gateway.requests().size(), "the actor shares the meter: no model call");

            McpSchema.CallToolResult second = run(fx, yaml.replace("metered", "metered-again"));
            String text = McpFixture.text(second);
            assertTrue(second.isError(), text);
            Map<String, Object> refused = McpFixture.json(second);
            assertEquals("BUDGET", refused.get("refused"), text);
            assertEquals("SESSION", refused.get("scope"), text);
            assertEquals(meter.limit().toPlainString(), refused.get("limitUsd"), text);
            assertTrue(text.contains("nothing ran and no model call was made"), text);
            assertEquals(calls, tierB.gateway.requests().size(), "no gateway call for the refused run");
            assertTrue(meter.exhausted(), "D74: the session makes no further model call");
        }
    }

    /** The session service over the server's session, metered on the server's meter, as skillroy mcp builds it. */
    private static SessionActor serviceActor(ExecutorSession session, ServerOptions options, TierB tierB,
                                             CostLedger ledger) {
        PromptMask mask = PromptMask.of(Desk.credentials().mask(), List.of(), List.of());
        LedgerWriter writer = new LedgerWriter(ledger, options.sessionMeter(), InferenceLog.NONE, mask, false, null);
        SessionService service = new SessionService(session, tierB.gateway, SessionPolicy.acting(),
                options.sessionMeter(), writer, PriceTable.builtIn(), null);
        return new SessionActor() {
            @Override
            public Object ask(String request) throws AgentLostException, InterruptedException {
                Turn turn = service.ask(request);
                return Map.of("outcome", turn.outcome().name(), "answer", String.valueOf(turn.answer()));
            }

            @Override
            public Resolution resolve(ExecutorSession s, String slot, String kind, Map<String, Object> args,
                                      List<String> strategy, Origin origin)
                    throws BindException, AgentLostException, InterruptedException {
                return service.resolve(slot, kind, args, strategy, List.of(), null, origin);
            }

            @Override
            public void close() {
                service.close();
            }
        };
    }

    /** A scenario with the credential slot {@code staff} (the desk's {@code 100}) and the given steps text. */
    private static String staffScenario(String id, String steps) {
        return "scenario: " + id + "\npack: desk\nresolve:\n  staff: { fixture: { code: \"100\" }, credential:"
                + " { realm: staff, key: code } }\n" + steps;
    }

    /**
     * Phase 7 security fix, S101, S102 and S128 (L3-F1, L3-S1, L3-S3; phase7-contracts.md 9.2, 9.4): the credential
     * rule's plan form reads a client's references with the executor's tokenizer and judges a pack verb's arguments
     * as written by step identity. Refused before anything runs, naming the step and the slot: the slot as a whole
     * through a pass-through verb ({@code x$staff}, and {@code $staff} handed whole, which its compiled call passes
     * on); a secret field on a pack-verb step that shares its source line with another (flow style, and a scenario
     * written as one line of JSON), the refusal naming the step that holds it; a value nested deeper than the bound.
     * A field the fixture provides still runs.
     */
    @Test
    void aClientsScenarioReachesNoCredentialInAnySpellingOrLayout() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options().mutating(true).build());
            refused(fx, staffScenario("whole-slot", "steps:\n  - enter: {value: \"x$staff\"}\n"),
                    "the scenario is refused", "step s1 (enter", "the credential slot 'staff' as a whole");
            refused(fx, staffScenario("whole-slot-dots", "steps:\n  - enter: {value: \"x$staff..password\"}\n"),
                    "step s1 (enter", "the credential slot 'staff' as a whole");
            refused(fx, staffScenario("slot-handed-on", "steps:\n  - enter: {value: $staff}\n"),
                    "step s1 (enter", "the credential slot 'staff' as a whole");
            refused(fx, staffScenario("base-whole", "steps:\n  - call: {primitive: desk.input, args: {source: Entry,"
                    + " value: \"$staff\"}}\n"), "step s1 (call", "the credential slot 'staff' as a whole");
            refused(fx, staffScenario("flow-field", "steps: [ {enter: {value: \"$staff.password\"}}, {enter: {value:"
                    + " \"1\"}} ]\n"), "step s1 (enter", "the field 'password' of the credential slot 'staff'");
            McpSchema.CallToolResult reversed = run(fx, staffScenario("flow-field-last", "steps: [ {enter: {value:"
                    + " \"1\"}}, {enter: {value: \"$staff.password\"}} ]\n"));
            String text = McpFixture.text(reversed);
            assertTrue(reversed.isError() && text.contains("step s2 (enter") && !text.contains("step s1 (enter"),
                    "the step that holds it, not the innocent one: " + text);
            refused(fx, "{\"scenario\": \"json-one-line\", \"pack\": \"desk\", \"resolve\": {\"staff\":"
                    + " {\"fixture\": {\"code\": \"100\"}, \"credential\": {\"realm\": \"staff\", \"key\":"
                    + " \"code\"}}}, \"steps\": [{\"enter\": {\"value\": \"$staff.password\"}}, {\"enter\":"
                    + " {\"value\": \"1\"}}]}\n", "step s1 (enter, line 1)", "'password'");
            StringBuilder open = new StringBuilder();
            StringBuilder close = new StringBuilder();
            for (int i = 0; i < 66; i++) {
                open.append("{a: ");
                close.append("}");
            }
            refused(fx, staffScenario("deep", "steps:\n  - call: {primitive: desk.input, args: {source: Entry, value:"
                    + " \"1\", deep: " + open + "\"$staff.password\"" + close + "}}\n"), "step s1 (call",
                    "nested deeper than 64 levels");
            for (McpSchema.CallToolResult r : List.of(reversed)) {
                assertFalse(McpFixture.text(r).contains(Desk.SECRET), "never a value");
            }
            assertTrue(fx.desk.agent.callsTo("desk.input").isEmpty(), "nothing ran");
            McpSchema.CallToolResult code = run(fx, staffScenario("provided", "steps: [ {enter: {value: $staff.code}},"
                    + " {enter: {value: \"1\"}} ]\n"));
            assertFalse(code.isError(), "a field the fixture provides runs: " + McpFixture.text(code));
            assertEquals(2, fx.desk.agent.callsTo("desk.input").size());
        }
    }

    @Test
    void aRunOnAClientsBehalfEvaluatesExpectationsOnMaskedValues() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            McpSchema.CallToolResult result = run(fx, scenario("pin-prefix", "  - expect: {app.operatorPin:"
                    + " {matches: \"^pw-staff\"}}\ntimeouts: {step: 200ms}\n"));
            assertTrue(result.isError(), McpFixture.text(result));
            assertEquals("FAIL", McpFixture.json(result).get("status"), "a credential's prefix is not an oracle: "
                    + McpFixture.text(result));
        }
    }
}
