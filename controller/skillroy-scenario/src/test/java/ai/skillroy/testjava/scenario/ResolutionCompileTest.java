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

import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 compilation (phase2-contracts.md 3 and 5, 14.1 item 5): the grammar and each compile error with its line,
 * {@code CompileContext.slot}, the plan version written (3 since Phase 3) and versions 1 to 3 read, byte-identical
 * recompilation, {@code resolveHash}, the resolution graph and its cycle error, captures, and the SQL of {@code db}
 * checks. Phase 3's additions to the grammar are in {@link Phase3CompileTest}.
 */
class ResolutionCompileTest {

    private final ToyResolution toy = new ToyResolution();
    private final ToyPack pack = toy.pack();

    private Plan compile(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
    }

    private ScenarioException fails(String yaml) {
        return assertThrows(ScenarioException.class, () -> compile(yaml));
    }

    private ScenarioException failsAt(int line, String text, String yaml) {
        ScenarioException e = fails(yaml);
        assertEquals(line, e.line(), e.getMessage());
        assertTrue(e.getMessage().contains(text), e.getMessage());
        return e;
    }

    @Test
    void theToyResolveScenarioCompilesToAPlanOfTheCurrentVersion() throws Exception {
        Plan plan = compile(BinderTest.YAML);
        assertEquals(7, plan.planVersion(), "Phase 8 writes version 7 (phase8-contracts.md 8.2)");
        assertEquals("none", plan.inference());
        assertEquals(List.of("toy.item"), plan.requires(), "the verifiers' capability ids are added");
        Plan.Slot item = plan.slot("item");
        assertEquals("query", item.kind());
        assertEquals(List.of("toy.item.check"), item.query().verify(), "the pack's verifiers by default");
        assertEquals(List.of(), item.query().strategy(), "strategy only when the scenario wrote it");
        assertEquals("staff", plan.slot("assistant").credential().realm());
        assertEquals(List.of("max", "item", "assistant", "oracle.neg", "oracle.needs"), plan.resolutionGraph());
        assertTrue(plan.needsBindings());
        assertEquals(60_000L, plan.bindTimeoutMs());
        String text = plan.toJsonText();
        assertEquals(text, compile(BinderTest.YAML).toJsonText(), "byte-identical recompilation");
        assertEquals(text, Plan.parse(text).toJsonText(), "reads back");
        List<String> keys = new ArrayList<>(plan.toJson().keySet());
        assertEquals(List.of("planVersion", "scenarioId", "pack", "packVersion", "sourceHash", "inference",
                "requires", "slots", "oracles", "resolutionGraph", "steps", "verify", "cleanup", "launchProperties",
                "artifacts", "onPrompt", "stepTimeoutMs", "bindTimeoutMs"), keys);
        assertTrue(text.contains("\"mustEqual\": \"PROMPT\""), text);
        assertFalse(text.contains("toy.sql"), "the plan names no resolver ids (D24)");
    }

    @Test
    void versionsOneToSevenAreReadOthersAreRefused() throws Exception {
        String v2 = compile(BinderTest.YAML).toJsonText().replace("\"planVersion\": 7", "\"planVersion\": 2");
        assertEquals(7, Plan.parse(v2).planVersion(), "a version 2 plan is read as version 7");
        ScenarioException eight = assertThrows(ScenarioException.class, () -> Plan.parse(v2.replace(
                "\"planVersion\": 2", "\"planVersion\": 8")));
        assertTrue(eight.getMessage().contains("not supported"), eight.getMessage());
        Plan v1 = SecretMaskTest.version1(compile("scenario: t\npack: toy\nsteps: []\n"), Map.of());
        assertEquals(7, v1.planVersion(), "a version 1 plan is read as version 7");
        Plan legacy = SecretMaskTest.version1(compile("scenario: t\npack: toy\nresolve:\n  a: { fixture: 1 }\n"
                + "steps: []\n"), Map.of());
        assertEquals("none", legacy.inference());
        assertEquals(List.of(), legacy.resolutionGraph());
        assertEquals(60_000L, legacy.bindTimeoutMs());
        ScenarioException run = assertThrows(ScenarioException.class, () -> Plan.parse(v2.replace(
                "\"inference\": \"none\"", "\"inference\": \"run\"")));
        assertTrue(run.getMessage().contains("inference 'run' needs a version 6 plan"), run.getMessage());
        ScenarioException graph = assertThrows(ScenarioException.class, () -> Plan.parse(v2.replace(
                "\"oracle.needs\"\n", "\"oracle.nope\"\n")));
        assertTrue(graph.getMessage().contains("resolutionGraph"), graph.getMessage());
    }

    @Test
    void theResolveHashFollowsSlotsAndOraclesOnly() throws Exception {
        String base = compile(BinderTest.YAML).resolveHash();
        assertEquals(base, compile(BinderTest.YAML + "  - mark: extra\n").resolveHash(), "a step edit");
        pack.version = "9.9.9";
        assertEquals(base, compile(BinderTest.YAML).resolveHash(), "a pack version change (D7)");
        assertNotEquals(compile(BinderTest.YAML).hash(), compile(BinderTest.YAML + "  - mark: x\n").hash());
        assertNotEquals(base, compile(BinderTest.YAML.replace("\"50.00\"", "\"51.00\"")).resolveHash(),
                "a slot edit");
        assertNotEquals(base, compile(BinderTest.YAML.replace("must_equal: PROMPT", "must_equal: NONE"))
                .resolveHash(), "an oracle edit");
    }

    @Test
    void theGraphOrderFollowsReferencesIncludingMustEqualAndACycleIsNamed() throws Exception {
        Plan plan = compile("""
                scenario: t
                pack: toy
                resolve:
                  late: { fixture: $oracle.first }
                  early: { fixture: "x" }
                oracle:
                  first: { fn: Identity, args: ["1"], must_equal: $early }
                steps: []
                """);
        assertEquals(List.of("early", "oracle.first", "late"), plan.resolutionGraph());
        failsAt(4, "cycle: a -> b -> oracle.c -> a", """
                scenario: t
                pack: toy
                resolve:
                  a: { fixture: $b }
                  b: { fixture: $oracle.c }
                oracle:
                  c: { fn: Identity, args: ["1"], must_equal: $a }
                steps: []
                """);
    }

    @Test
    void grammarErrorsNameTheLine() {
        failsAt(4, "exactly one of 'fixture' and 'query'", "scenario: t\npack: toy\nresolve:\n  x: {}\nsteps: []\n");
        failsAt(4, "reserved", "scenario: t\npack: toy\nresolve:\n  env: { fixture: 1 }\nsteps: []\n");
        failsAt(4, "pack toy declares no SQL allow-list: llm-sql cannot be served", "scenario: t\npack: toy\n"
                + "resolve:\n  x: { query: Item, strategy: [llm-sql] }"
                + "\nsteps: []\n");
        failsAt(4, "unknown strategy", "scenario: t\npack: toy\nresolve:\n  x: { query: Item, strategy: [magic] }"
                + "\nsteps: []\n");
        failsAt(4, "has no java resolver for kind Item; it offers: sql", "scenario: t\npack: toy\nresolve:\n"
                + "  x: { query: Item, strategy: [java] }\nsteps: []\n");
        failsAt(4, "no query kind 'Nope'", "scenario: t\npack: toy\nresolve:\n  x: { query: Nope }\nsteps: []\n");
        failsAt(4, "no verifier toy.limit for kind Item", "scenario: t\npack: toy\nresolve:\n"
                + "  x: { query: Item, verify: toy.limit }\nsteps: []\n");
        failsAt(4, "args do not fit query Item", "scenario: t\npack: toy\nresolve:\n"
                + "  x: { query: Item, args: { colour: red } }\nsteps: []\n");
        failsAt(4, "'verify' belongs to a query slot", "scenario: t\npack: toy\nresolve:\n"
                + "  x: { fixture: 1, verify: v }\nsteps: []\n");
        failsAt(4, "the fixture field 'token'", "scenario: t\npack: toy\nresolve:\n"
                + "  x: { fixture: { token: abc } }\nsteps: []\n");
        failsAt(4, "credential", "scenario: t\npack: toy\nresolve:\n  x: { fixture: {a: 1}, credential: { realm: X } }"
                + "\nsteps: []\n");
        failsAt(4, "crossCheck: 'check' must name a cross-check of the pack (missing)", "scenario: t\npack: toy\n"
                + "oracle:\n  o: { fn: Identity, args: [1], crossCheck: {} }\nsteps: []\n");
        failsAt(4, "no oracle function 'Nope'", "scenario: t\npack: toy\noracle:\n  o: { fn: Nope }\nsteps: []\n");
        failsAt(4, "names no oracle", "scenario: t\npack: toy\nresolve:\n  x: { fixture: $oracle.zz }\nsteps: []\n");
        failsAt(4, "$env has only the field id", "scenario: t\npack: toy\nresolve:\n  x: { fixture: $env.name }\n"
                + "steps: []\n");
        failsAt(4, "$env.now: $env has the field id only; a query that depends on the date reads the target's"
                + " business date at bind time (see the pack's documentation)", "scenario: t\npack: toy\nresolve:\n"
                + "  x: { fixture: $env.now }\nsteps: []\n");
        fails("scenario: t\npack: toy\ntimeouts: { bind: 11m }\nsteps: []\n");
        failsAt(4, "aiAct: 'intent' must be a sentence", "scenario: t\npack: toy\nsteps:\n  - aiAct: {}\n");
    }

    @Test
    void everyQuerySlotNeedsAVerifier() {
        pack.resolution = ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue.builder()
                .query(ai.skillroy.testjava.scenario.resolve.QueryType.of("Limit", ToyResolution.Limit.class))
                .resolver(toy.new LimitResolver()).build();
        failsAt(4, "every query slot needs one", "scenario: t\npack: toy\nresolve:\n  x: { query: Limit }\n"
                + "steps: []\n");
    }

    @Test
    void reservedPackSurfacesAreCompileErrors() {
        pack.facts = List.of(new FactProvider() {
            @Override
            public String root() {
                return "db";
            }

            @Override
            public Object fetch(ai.skillroy.testjava.controller.AgentConnection agent) {
                return null;
            }
        });
        assertTrue(fails("scenario: t\npack: toy\nsteps: []\n").getMessage().contains("fact root 'db'"));
        assertTrue(DomainPacks.reservedId("inference.x"));
        assertFalse(DomainPacks.reservedId("inferences"));
    }

    @Test
    void compileContextSlotDescribesSlotsAndNeverValues() throws Exception {
        List<CompileContext.SlotInfo> seen = new ArrayList<>();
        ToyPack recording = new ToyPack();
        recording.custom = List.of();
        recording.onCustom = ctx -> {
            seen.add(ctx.slot("item"));
            seen.add(ctx.slot("assistant"));
            seen.add(ctx.slot("$loan"));
            seen.add(ctx.slot("nothing"));
        };
        recording.resolution = toy.catalogue();
        PlanCompiler.compile(("""
                scenario: t
                pack: toy
                resolve:
                  item: { query: Item }
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                steps:
                  - waitFor: { topic: loan.posted, capture: { slot: loan, fields: { id: loanId } } }
                  - custom: {}
                """).getBytes(StandardCharsets.UTF_8), "t.yaml", recording);
        assertEquals(new CompileContext.SlotInfo("item", "query", "Item", null, null), seen.get(0));
        assertEquals("staff", seen.get(1).credential().realm());
        assertEquals("fixture", seen.get(1).kind());
        assertEquals(new CompileContext.SlotInfo("loan", "capture", null, null, java.util.Set.of("id")),
                seen.get(2));
        assertNull(seen.get(3));
        failsAt(6, "has no credential", "scenario: t\npack: toy\nresolve:\n  s: { fixture: { code: \"1\" } }\n"
                + "steps:\n  - signOn: { staff: $s }\n");
    }

    @Test
    void capturesAreDeclaredAndCheckedAtCompileTime() throws Exception {
        Plan plan = compile("""
                scenario: t
                pack: toy
                steps:
                  - waitFor: { topic: loan.posted, within: 2s,
                               capture: { slot: loan, fields: { total: total, id: loanId } } }
                  - call: { primitive: toy.emit, sync: EVENT,
                            await: { topic: toy.done, capture: { slot: done, fields: { at: header.at } } } }
                  - enter: { value: $loan.id }
                verify:
                  - { "loan.total": $loan.total }
                """);
        Plan.Slot loan = plan.slot("loan");
        assertEquals("capture", loan.kind());
        assertEquals(new Plan.CaptureDecl("s1", List.of("id", "total")), loan.capture());
        assertEquals("loanId", plan.steps().get(0).calls().get(0).capture().fields().get("id"));
        assertEquals(4, plan.steps().get(0).line());
        assertEquals("header.at", plan.steps().get(1).calls().get(0).capture().fields().get("at"));
        assertFalse(plan.needsBindings(), "a captured slot alone needs no bindings file");
        assertFalse(plan.resolutionGraph().contains("loan"));
        String text = plan.toJsonText();
        assertTrue(text.contains("\"capture\": {"), text);
        assertEquals(text, Plan.parse(text).toJsonText());

        failsAt(4, "captures; it may be referenced only in later steps", """
                scenario: t
                pack: toy
                steps:
                  - enter: { value: $loan.id }
                  - waitFor: { topic: loan.posted, capture: { slot: loan, fields: { id: loanId } } }
                """);
        failsAt(5, "has no field 'total'", """
                scenario: t
                pack: toy
                steps:
                  - waitFor: { topic: loan.posted, capture: { slot: loan, fields: { id: loanId } } }
                  - enter: { value: $loan.total }
                """);
        failsAt(4, "refers to a captured slot", """
                scenario: t
                pack: toy
                resolve:
                  copy: { fixture: $loan.id }
                steps:
                  - waitFor: { topic: loan.posted, capture: { slot: loan, fields: { id: loanId } } }
                """);
        failsAt(6, "also declared in resolve", """
                scenario: t
                pack: toy
                resolve:
                  loan: { fixture: 1 }
                steps:
                  - waitFor: { topic: loan.posted, capture: { slot: loan, fields: { id: loanId } } }
                """);
        failsAt(5, "already captured", """
                scenario: t
                pack: toy
                steps:
                  - waitFor: { topic: a, capture: { slot: loan, fields: { id: loanId } } }
                  - waitFor: { topic: b, capture: { slot: loan, fields: { id: loanId } } }
                """);
        fails("scenario: t\npack: toy\nsteps:\n  - waitFor: { topic: a, capture: { slot: loan } }\n");
        assertThrows(IllegalArgumentException.class, () -> new PlannedCall("toy.x", Map.of(), Sync.READY, 0)
                .withCapture(ai.skillroy.testjava.scenario.pack.CaptureSpec.of("x", Map.of("a", "b"))));
    }

    @Test
    void aSlotAPackVerbCapturesIsACompileErrorInResolveAndOracle() throws Exception {
        pack.custom = List.of(PlannedCall.event("toy.emit", Map.of(),
                ai.skillroy.testjava.scenario.pack.AwaitSpec.of("toy.done"))
                .withCapture(ai.skillroy.testjava.scenario.pack.CaptureSpec.of("done", Map.of("id", "id"))));
        Plan plan = compile("""
                scenario: t
                pack: toy
                steps:
                  - custom: {}
                  - enter: { value: $done.id }
                """);
        assertEquals("capture", plan.slot("done").kind(), "a pack verb may capture (3.4)");
        failsAt(4, "refers to a captured slot", """
                scenario: t
                pack: toy
                resolve:
                  copy: { fixture: $done.id }
                steps:
                  - custom: {}
                """);
        failsAt(4, "refers to a captured slot", """
                scenario: t
                pack: toy
                resolve:
                  item: { query: Item, args: { deposit: { lte: $done.id } } }
                steps:
                  - custom: {}
                """);
        failsAt(6, "refers to a captured slot", """
                scenario: t
                pack: toy
                resolve:
                  max: { query: Limit, args: { type: RETURN } }
                oracle:
                  neg: { fn: Negate, args: [$done.id] }
                steps:
                  - custom: {}
                """);
        failsAt(6, "refers to a captured slot", """
                scenario: t
                pack: toy
                resolve:
                  max: { query: Limit, args: { type: RETURN } }
                oracle:
                  same: { fn: Identity, args: [$max], must_equal: $done.id }
                steps:
                  - custom: {}
                """);
    }

    @Test
    void dbChecksCompileToGuardedSqlFromThePacksTables() throws Exception {
        pack.storedTables = List.of(StoredTable.of("loan_line", "toy", "loan_line", List.of(
                StoredColumn.of("loan_id", ColumnKind.INTEGER),
                StoredColumn.of("line_no", ColumnKind.INTEGER),
                StoredColumn.of("item_code", ColumnKind.TEXT),
                StoredColumn.of("amount", ColumnKind.DECIMAL).param("CAST(? AS DECIMAL(10,2))"),
                StoredColumn.of("returned", ColumnKind.BOOLEAN),
                StoredColumn.of("posted_at", ColumnKind.TIMESTAMP),
                StoredColumn.of("order_no", ColumnKind.INTEGER).sql("\"ORDER\"")), List.of("loan_id", "line_no")));
        Plan plan = compile("""
                scenario: t
                pack: toy
                resolve:
                  item: { fixture: { code: A1 } }
                steps:
                  - expect:
                      db.loan_line: { where: { loan_id: 7, item_code: $item.code, amount: "-3.50", returned: true,
                                               posted_at: "2026-10-02T10:00:00Z", order_no: null }, rows: { gte: 1 } }
                verify:
                  - db.loan_line: { where: {}, returned: true, amount: "-3.50" }
                """);
        RowCheck check = (RowCheck) plan.steps().get(0).expect().get(0);
        assertEquals("SELECT loan_id, line_no, item_code, amount, returned, posted_at, \"ORDER\" FROM loan_line"
                + " WHERE loan_id = ? AND item_code = ? AND amount = CAST(? AS DECIMAL(10,2)) AND returned = ? AND"
                + " posted_at = ? AND \"ORDER\" IS NULL ORDER BY loan_id, line_no", check.sql());
        assertEquals(List.of(ColumnKind.INTEGER, ColumnKind.TEXT, ColumnKind.DECIMAL, ColumnKind.BOOLEAN,
                ColumnKind.TIMESTAMP), check.params().stream().map(RowCheck.Param::kind).toList());
        assertEquals("$item.code", check.params().get(1).value());
        assertEquals(Map.of("gte", 1L), check.rows());
        RowCheck verify = (RowCheck) plan.verify().get(0);
        assertEquals("SELECT loan_id, line_no, item_code, amount, returned, posted_at, \"ORDER\" FROM loan_line"
                + " ORDER BY loan_id, line_no", verify.sql());
        assertEquals(Map.of("equals", 1L), verify.rows(), "rows default to 1");
        assertEquals(List.of("amount", "returned"), List.copyOf(verify.expect().keySet()), "sorted");
        String text = plan.toJsonText();
        assertEquals(text, Plan.parse(text).toJsonText());

        String bad = "scenario: t\npack: toy\nverify:\n  - db.%s\nsteps: []\n";
        failsAt(4, "declares no stored table 'nope'", bad.formatted("nope: { where: {}, rows: 1 }"));
        failsAt(4, "'colour' is not a column", bad.formatted("loan_line: { where: { colour: 1 } , rows: 1 }"));
        failsAt(4, "'where' is required", bad.formatted("loan_line: { rows: 1 }"));
        failsAt(4, "at least one column matcher", bad.formatted("loan_line: { where: {} }"));
        failsAt(4, "avg(amount) is not an aggregate; the aggregates are sum(<column>), min(<column>),"
                + " max(<column>)", bad.formatted("loan_line: { where: {}, avg(amount): 1 }"));
        failsAt(4, "comparisons other than equality", bad.formatted("loan_line: { where: { amount: { gt: 1 } },"
                + " rows: 1 }"));
        assertTrue(fails("scenario: t\npack: toy\nverify:\n"
                + "  - { db.loan_line: { where: {}, rows: 1 }, \"state.name\": X }\n"
                + "steps: []\n").getMessage().contains("exactly one key"));
        pack.storedTables = List.of(StoredTable.of("evil", "toy", "loan_line; DELETE FROM loan_line", List.of(
                StoredColumn.of("a", ColumnKind.TEXT)), List.of("a")));
        failsAt(4, "statement guard refuses", bad.formatted("evil: { where: {}, rows: 1 }"));
    }
}
