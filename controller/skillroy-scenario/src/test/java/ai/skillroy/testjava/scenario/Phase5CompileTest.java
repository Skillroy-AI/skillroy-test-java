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
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 acceptance 15.1 item 7, the plan and compiler parts (phase5-contracts.md 13.1, 13.2): {@code llm-sql}
 * accepted when the pack declares an SQL allow-list and refused naming the pack otherwise, tier (c) refused as not in
 * this version, plan version 5 with {@code inference: bind}, the reader's 1-5 and its refusals, the base verb
 * {@code call} reserved and unchanged, and the new pack SPI defaults.
 */
class Phase5CompileTest {

    private final ToyResolution toy = new ToyResolution();
    private final ToyPack pack = toy.pack();

    static final SqlAllowList TOY_ALLOW_LIST = SqlAllowList.builder("toy", SqlDialect.HSQLDB)
            .table("item", List.of("code"), SqlAllowList.AllowedColumn.of("code", "VARCHAR(10)"),
                    SqlAllowList.AllowedColumn.of("deposit", "DECIMAL(10,2)"))
            .build();

    private static final String LLM_ITEM = """
            scenario: toy-llm
            pack: toy
            resolve:
              max: { query: Limit, args: { type: RETURN } }
              item: { query: Item, args: { deposit: { lte: $max } }, strategy: [sql, llm-sql] }
            steps:
              - enter: { value: $item.code }
            """;

    private Plan compile(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
    }

    private ScenarioException fails(String yaml) {
        return assertThrows(ScenarioException.class, () -> compile(yaml));
    }

    // ------------------------------------------------------------------------------------------------ llm-sql

    @Test
    void llmSqlIsAcceptedWhenThePackDeclaresAnAllowListAndThePlanSaysBind() throws Exception {
        pack.sqlAllowLists = List.of(TOY_ALLOW_LIST);
        Plan plan = compile(LLM_ITEM);
        assertEquals(7, plan.planVersion());
        assertEquals(Plan.INFERENCE_BIND, plan.inference(), "a slot that may need a model: inference bind");
        assertEquals(List.of("sql", "llm-sql"), plan.slot("item").query().strategy());
        String text = plan.toJsonText();
        assertTrue(text.contains("\"inference\": \"bind\""), text);
        assertEquals(text, Plan.parse(text).toJsonText(), "a bind plan reads back byte for byte");
        Plan alone = compile(LLM_ITEM.replace("strategy: [sql, llm-sql]", "strategy: [llm-sql]"));
        assertEquals(List.of("llm-sql"), alone.slot("item").query().strategy(), "llm-sql alone: the model only");
        assertEquals(Plan.INFERENCE_BIND, alone.inference());
        Plan none = compile(LLM_ITEM.replace(", strategy: [sql, llm-sql]", ""));
        assertEquals(Plan.INFERENCE_NONE, none.inference());
        assertNotEquals(none.resolveHash(), plan.resolveHash(), "gaining llm-sql changes the hash bindings are tied"
                + " to, as a strategy change should");
    }

    @Test
    void llmSqlWithoutAnAllowListNamesThePack() {
        ScenarioException e = fails(LLM_ITEM);
        assertEquals("slot 'item': pack toy declares no SQL allow-list: llm-sql cannot be served", e.reason());
        assertEquals(5, e.line());
        pack.sqlAllowLists = null;
        assertTrue(fails(LLM_ITEM).reason().contains("declares no SQL allow-list"), "null is none");
    }

    @Test
    void aPackWhoseAllowListsThrowIsACompileError() {
        DomainPack broken = new Delegating(pack) {
            @Override
            public List<SqlAllowList> sqlAllowLists() {
                throw new IllegalStateException("no schema");
            }
        };
        ScenarioException e = assertThrows(ScenarioException.class, () -> PlanCompiler.compile(
                LLM_ITEM.getBytes(StandardCharsets.UTF_8), "t.yaml", broken));
        assertTrue(e.reason().contains("its SQL allow-lists are invalid: no schema"), e.reason());
    }

    @Test
    void tierCStaysOutOfThisVersionInTheLoader() {
        for (String token : List.of("llm-reason", "llm-vision")) {
            ScenarioException e = fails(LLM_ITEM.replace("strategy: [sql, llm-sql]", "strategy: [sql, " + token
                    + "]"));
            assertEquals("slot 'item': strategy " + token + " is not in this version", e.reason());
            assertEquals(5, e.line());
        }
    }

    // ------------------------------------------------------------------------------------------------ plan.json

    @Test
    void theReaderAcceptsBindInVersion5OnlyAndRefusesRun() throws Exception {
        pack.sqlAllowLists = List.of(TOY_ALLOW_LIST);
        String bind = compile(LLM_ITEM).toJsonText();
        String none = compile(LLM_ITEM.replace(", strategy: [sql, llm-sql]", "")).toJsonText();
        assertEquals(bind, Plan.parse(bind).toJsonText());
        assertEquals("invalid plan: inference is 'run' but no step's verb is aiAct",
                reason(bind.replace("\"inference\": \"bind\"", "\"inference\": \"run\"")));
        assertEquals("invalid plan: inference 'run' needs a version 6 plan", reason(bind.replace(
                "\"inference\": \"bind\"", "\"inference\": \"run\"").replace("\"planVersion\": 7",
                "\"planVersion\": 5")));
        assertEquals("invalid plan: unknown inference 'maybe' (expected none, bind or run)",
                reason(bind.replace("\"inference\": \"bind\"", "\"inference\": \"maybe\"")));
        assertEquals("invalid plan: inference is 'bind' but no slot names a strategy that needs a model",
                reason(none.replace("\"inference\": \"none\"", "\"inference\": \"bind\"")));
        assertTrue(reason(bind.replace("\"inference\": \"bind\"", "\"inference\": \"none\""))
                .contains("inference is 'none' but a slot names a strategy that needs a model"));
        String v4 = bind.replace("\"planVersion\": 7", "\"planVersion\": 4");
        assertEquals("invalid plan: inference 'bind' needs a version 5 plan; a version 4 plan has inference 'none'",
                reason(v4));
        assertEquals("invalid plan: slot item: strategy llm-sql needs a version 5 plan",
                reason(v4.replace("\"inference\": \"bind\"", "\"inference\": \"none\"")));
        for (String token : List.of("llm-reason", "llm-vision")) {
            assertEquals("invalid plan: slot item: strategy " + token + " is not in this version",
                    reason(bind.replace("\"llm-sql\"", "\"" + token + "\"")));
        }
        assertEquals(Plan.parse(none).toJsonText(), Plan.parse(none.replace("\"planVersion\": 7",
                "\"planVersion\": 4")).toJsonText(), "a version 4 plan is read as version 7");
        assertTrue(reason(none.replace("\"planVersion\": 7", "\"planVersion\": 8")).contains("not supported"));
    }

    private static String reason(String planText) {
        return assertThrows(ScenarioException.class, () -> Plan.parse(planText)).reason();
    }

    @Test
    void inferenceOfLooksAtTheTokensThatNeedAModel() {
        Plan.Slot sql = new Plan.Slot("a", "query", null, new Plan.QuerySpec("Item", Map.of(), List.of("sql"),
                List.of("v")), null, null);
        Plan.Slot model = new Plan.Slot("b", "query", null, new Plan.QuerySpec("Item", Map.of(), List.of("sql",
                "llm-sql"), List.of("v")), null, null);
        Plan.Slot fixture = new Plan.Slot("c", "fixture", 1L);
        assertEquals(Plan.INFERENCE_NONE, Plan.inferenceOf(List.of(sql, fixture)));
        assertEquals(Plan.INFERENCE_BIND, Plan.inferenceOf(List.of(fixture, sql, model)));
        assertEquals(Plan.INFERENCE_NONE, Plan.inferenceOf(List.of()));
    }

    // ------------------------------------------------------------------------------------------------ call

    @Test
    void callIsReservedForTheBaseVerb() {
        assertEquals(Set.of("aiAct", "aiExpect", "pointer", "expectImage", "call"), PlanCompiler.RESERVED_VERBS);
        DomainPack shadowing = new Delegating(pack) {
            @Override
            public StepLibrary steps() {
                return new StepLibrary() {
                    @Override
                    public Set<String> verbs() {
                        return Set.of("call", "enter");
                    }

                    @Override
                    public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) {
                        return List.of();
                    }
                };
            }
        };
        ScenarioException e = assertThrows(ScenarioException.class, () -> PlanCompiler.compile(
                "scenario: t\npack: toy\nsteps: []\n".getBytes(StandardCharsets.UTF_8), "t.yaml", shadowing));
        assertTrue(e.reason().contains("declares the verb 'call', which is reserved"), e.reason());
        assertTrue(fails("scenario: t\npack: toy\nsteps:\n  - aiAct: { goal: x }\n").reason()
                .contains("aiAct: unknown argument 'goal'"), "the Phase 7 verbs are base verbs with their own"
                + " arguments (phase7-contracts.md 8.1)");
    }

    /**
     * The base verb {@code call} as built (P1 8.3), which an exported step relies on: {@code sync} NONE by default,
     * the capture inside {@code await}, and {@code confirm: true} in {@code args} passed through to the plan (the
     * executor sends it as the confirmation, see {@code ExecutorSessionTest}).
     */
    @Test
    void theCallVerbIsUnchangedAndPassesConfirmThrough() throws Exception {
        Plan plan = compile("""
                scenario: t
                pack: toy
                steps:
                  - call: { primitive: toy.wipe, args: { confirm: true, scope: all } }
                  - call: { primitive: toy.emit, sync: EVENT, await: { topic: toy.done, within: 2s,
                            capture: { slot: done, fields: { id: id } } } }
                """);
        Plan.Call wipe = plan.steps().get(0).calls().get(0);
        assertEquals(Sync.NONE, wipe.sync(), "sync defaults to NONE");
        assertEquals(Map.of("confirm", true, "scope", "all"), wipe.args(), "confirm passes through in args");
        Plan.Call emit = plan.steps().get(1).calls().get(0);
        assertEquals("done", emit.capture().slot());
        assertEquals(2000L, emit.await().timeoutMs());
        assertTrue(fails("scenario: t\npack: toy\nsteps:\n  - call: { primitive: toy.emit, timeoutMs: 100 }\n")
                .reason().contains("unknown argument 'timeoutMs'"), "no key was added to call");
        assertTrue(fails("scenario: t\npack: toy\nsteps:\n  - call: { primitive: toy.emit, capture: { slot: a,"
                + " fields: { x: y } } }\n").reason().contains("unknown argument 'capture'"), "capture sits in await");
    }

    /**
     * A step's arguments may not carry {@code _meta} (phase5-contracts.md 13.1, the fix wave): a call's {@code _meta}
     * is the executor's, built from its options. Refused at any depth of any verb's arguments ({@code expect} aside:
     * its matchers are never sent) and in the calls a pack's verb compiles to, cleanup calls included, naming the
     * key's path; a key that merely resembles it, and {@code confirm}, stay allowed.
     */
    @Test
    void aStepsArgumentsMayNotCarryMeta() throws Exception {
        ScenarioException call = fails("scenario: t\npack: toy\nsteps:\n"
                + "  - call: { primitive: toy.wipe, args: { _meta: { confirm: true } } }\n");
        assertEquals("verb 'call': a step's arguments may not carry _meta (at args._meta); a call's _meta is built by"
                + " the executor from its options", call.reason());
        assertEquals(4, call.line());
        ScenarioException deep = fails("scenario: t\npack: toy\nsteps:\n  - enter: { value: B2 }\n"
                + "  - enter: { value: B2, extra: [ { note: x }, { _meta: {} } ] }\n");
        assertTrue(deep.reason().contains("a step's arguments may not carry _meta (at extra[1]._meta)"),
                deep.reason());
        assertEquals(5, deep.line());
        pack.custom = List.of(PlannedCall.of("toy.wipe", Map.of("_meta", Map.of("confirm", true)), Sync.NONE));
        ScenarioException packCall = fails("scenario: t\npack: toy\nsteps:\n  - custom: {}\n");
        assertTrue(packCall.reason().contains("call toy.wipe: a step's arguments may not carry _meta (at _meta)"),
                packCall.reason());
        pack.custom = List.of();
        pack.onCustom = ctx -> ctx.addCleanup(PlannedCall.of("toy.wipe", Map.of("lines", List.of(Map.of("_meta",
                true))), Sync.NONE));
        ScenarioException cleanup = fails("scenario: t\npack: toy\nsteps:\n  - custom: {}\n");
        assertTrue(cleanup.reason().contains("cleanup: call toy.wipe: a step's arguments may not carry _meta (at"
                + " lines[0]._meta)"), cleanup.reason());
        pack.onCustom = ctx -> { };
        Plan fine = compile("scenario: t\npack: toy\nsteps:\n  - expect: { state.name: IDLE }\n"
                + "  - call: { primitive: toy.wipe, args: { confirm: true, meta: x, _metadata: y } }\n");
        assertEquals(Map.of("confirm", true, "meta", "x", "_metadata", "y"), fine.steps().get(1).calls().get(0).args());
    }

    // ------------------------------------------------------------------------------------------------ SPI defaults

    @Test
    void thePackSpiDefaultsAreNoneAndNull() {
        DomainPack minimal = new Delegating(new ToyPack());
        assertEquals(List.of(), minimal.sqlAllowLists());
        assertNull(minimal.assistAnswerPrimitive());
        StepLibrary library = new StepLibrary() {
            @Override
            public Set<String> verbs() {
                return Set.of("enter");
            }

            @Override
            public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) {
                return List.of();
            }
        };
        assertNull(library.argsSchema("enter"), "no schema: a free-form object");
    }

    /** A pack that forwards to another, for overriding one method; the defaults are DomainPack's own. */
    private static class Delegating implements DomainPack {

        private final ToyPack inner;

        Delegating(ToyPack inner) {
            this.inner = inner;
        }

        @Override
        public String id() {
            return inner.id();
        }

        @Override
        public String version() {
            return inner.version();
        }

        @Override
        public StepLibrary steps() {
            return inner.steps();
        }

        @Override
        public SyncPolicy syncPolicy() {
            return inner.syncPolicy();
        }

        @Override
        public List<FactProvider> facts() {
            return inner.facts();
        }

        @Override
        public ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue resolution() {
            return inner.resolution();
        }

        @Override
        public void recover(AgentConnection agent) {
        }
    }
}
