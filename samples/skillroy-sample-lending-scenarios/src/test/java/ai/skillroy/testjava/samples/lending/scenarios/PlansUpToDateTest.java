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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.ScenarioLoader;
import ai.skillroy.testjava.scenario.RowCheck;
import ai.skillroy.testjava.scenario.UiSettings;
import ai.skillroy.testjava.scenario.pack.CaptureSpec;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The committed plans ({@code plan/<id>.plan.json}) are exactly what compiling the committed scenarios
 * ({@code scenarios/*.yaml}) with the lending pack gives now, byte for byte, so that a reviewer reading a plan reads
 * what runs. After changing a scenario or the pack, regenerate them with {@code -Dskillroy.plans.update=true}.
 */
class PlansUpToDateTest {

    static Path moduleDir() {
        return Path.of(System.getProperty("skillroy.it.moduleDir", ".")).toAbsolutePath().normalize();
    }

    static List<Path> committedScenarios() throws IOException {
        try (Stream<Path> files = Files.list(moduleDir().resolve("scenarios"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
    }

    static Path planFor(Path yaml) throws Exception {
        String id = ScenarioLoader.load(yaml).id();
        return moduleDir().resolve("plan").resolve(id + ".plan.json");
    }

    @Test
    void everyCommittedPlanMatchesItsScenario() throws Exception {
        List<Path> scenarios = committedScenarios();
        assertEquals(List.of("basic-loan-ui.yaml", "basic-loan.yaml", "borrow-2-waive-1-slip.yaml",
                "return-over-limit-needs-supervisor.yaml"), scenarios.stream().map(p -> p.getFileName().toString())
                .toList());
        boolean update = Boolean.getBoolean("skillroy.plans.update");
        List<String> stale = new ArrayList<>();
        for (Path yaml : scenarios) {
            Path plan = planFor(yaml);
            if (update) {
                PlanCompiler.compileTo(yaml, new LendingPack(), plan);
            }
            if (!PlanCompiler.isUpToDate(yaml, plan, new LendingPack())) {
                stale.add(moduleDir().relativize(plan).toString());
            }
        }
        assertTrue(stale.isEmpty(), "out of date (regenerate with -Dskillroy.plans.update=true): " + stale);
        try (Stream<Path> plans = Files.list(moduleDir().resolve("plan"))) {
            assertEquals(scenarios.size(), plans.count(), "one plan per committed scenario, nothing else");
        }
    }

    /**
     * Every call is followed by an event wait or the UI settle, or is itself a wait: a {@code sync.await}, or the
     * {@code swing.settle} (sync NONE) that ends a UI verb with {@code await:} right after its EVENT call (A32).
     */
    @Test
    void thePlansUseEventDrivenSyncOnly() throws Exception {
        for (Path yaml : committedScenarios()) {
            Plan plan = Plan.read(planFor(yaml));
            assertEquals("lending", plan.pack());
            assertEquals(Plan.VERSION, plan.planVersion());
            assertEquals("none", plan.inference());
            if (plan.scenarioId().startsWith("basic-loan")) {
                assertEquals("A", plan.launchProperties().get("lending.seed"), "Phase 1 scenarios name their seed");
            } else {
                assertEquals(Map.of(), plan.launchProperties(), "scenario A runs on whichever seed it is given");
            }
            assertFalse(plan.steps().isEmpty());
            for (Plan.Step step : plan.steps()) {
                List<Plan.Call> calls = step.calls();
                for (int i = 0; i < calls.size(); i++) {
                    Plan.Call call = calls.get(i);
                    boolean settlesAnAwaitedUiCall = call.primitive().equals("swing.settle") && i > 0
                            && calls.get(i - 1).sync() == Sync.EVENT;
                    boolean waitedOn = call.sync() != Sync.NONE || call.primitive().equals("sync.await")
                            || settlesAnAwaitedUiCall;
                    assertTrue(waitedOn, plan.scenarioId() + " " + step.id() + ": every call is followed by an "
                            + "event wait or the UI settle, or is itself a wait: " + call);
                }
            }
        }
    }

    /**
     * {@code basic-loan-ui} as amendments A29 and A32 shape it: {@code ui: {clickRung: COMPONENT}} recorded in the
     * plan, every click sent with {@code rung: COMPONENT}, {@code activateWindow: true} and {@code fallback: ACTION},
     * its UI steps synchronised by their own {@code await:} (an EVENT call followed by {@code swing.settle}), and one
     * explicit {@code waitFor} left, so that both forms stay covered.
     */
    @Test
    void theUiScenarioClicksOnTheComponentRungAndAwaitsTheDesksEvents() throws Exception {
        Plan plan = Plan.read(moduleDir().resolve("plan").resolve("basic-loan-ui.plan.json"));
        assertEquals(new UiSettings("COMPONENT", false), plan.ui());
        assertTrue(plan.requires().contains("swing.click.component"), String.valueOf(plan.requires()));
        int clicks = 0;
        int awaited = 0;
        int waitFors = 0;
        for (Plan.Step step : plan.steps()) {
            List<Plan.Call> calls = step.calls();
            if (step.verb().equals("waitFor")) {
                waitFors++;
                assertEquals("sync.await", calls.get(0).primitive());
            }
            if (!List.of("click", "type").contains(step.verb())) {
                continue;
            }
            Plan.Call ui = calls.get(0);
            if (step.verb().equals("click")) {
                clicks++;
                assertEquals("swing.click", ui.primitive());
                assertEquals("COMPONENT", ui.args().get("rung"), step.id());
                assertEquals(Boolean.TRUE, ui.args().get("activateWindow"), step.id());
                assertEquals("ACTION", ui.args().get("fallback"), step.id());
            }
            if (ui.sync() == Sync.EVENT) {
                awaited++;
                assertNotNull(ui.await(), step.id());
                assertEquals(2, calls.size(), step.id());
                assertEquals("swing.settle", calls.get(1).primitive(), step.id());
                assertEquals(Sync.NONE, calls.get(1).sync(), step.id());
            } else {
                assertEquals(Sync.SETTLE, ui.sync(), step.id());
            }
        }
        assertEquals(3, clicks);
        assertEquals(7, awaited, "every UI step that a wait directly followed now awaits the event itself");
        assertEquals(1, waitFors, "one explicit waitFor stays");
    }

    @Test
    void theNegativeScenariosCompile() throws Exception {
        Path dir = moduleDir().resolve("src/test/resources/negative");
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> yamls = files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
            assertEquals(List.of("click-fallback-degraded.yaml", "click-strict-fails.yaml", "guarded-unavailable.yaml",
                    "missing-capability.yaml", "modal-open-step.yaml", "no-item-in-range.yaml", "no-rule-of-kind.yaml",
                    "prompt-left-open.yaml", "return-within-limit.yaml", "waiver-guard-fails.yaml",
                    "wrong-expectation.yaml"), yamls.stream().map(p -> p.getFileName().toString()).toList());
            for (Path yaml : yamls) {
                Plan plan = PlanCompiler.compile(yaml, new LendingPack());
                assertEquals(yaml.getFileName().toString(), plan.scenarioId() + ".yaml");
            }
        }
        // The fidelity negatives differ in one setting only: strict decides whether the click may fall back.
        Plan.Call degraded = click(PlanCompiler.compile(dir.resolve("click-fallback-degraded.yaml"),
                new LendingPack()));
        assertEquals(Map.of("by", Map.of("name", "OK"), "focusTimeoutMs", 1000L, "rung", "COMPONENT", "activateWindow",
                true, "fallback", "ACTION"), degraded.args());
        Plan.Call strict = click(PlanCompiler.compile(dir.resolve("click-strict-fails.yaml"), new LendingPack()));
        assertEquals(Map.of("by", Map.of("name", "OK"), "focusTimeoutMs", 1000L, "rung", "COMPONENT", "activateWindow",
                true), strict.args());
        Plan.Call modal = click(PlanCompiler.compile(dir.resolve("modal-open-step.yaml"), new LendingPack()));
        assertEquals(Map.of("by", Map.of("action", "DUE")), modal.args(), "no ui key: the primitive's default rung");
    }

    /**
     * Phase 2 (3.1 rule 5, 13.4): every sample scenario's staff slot takes its password from {@code credentials.yaml};
     * no fixture holds one, and no plan does.
     */
    @Test
    void noScenarioOrPlanHoldsAPassword() throws Exception {
        List<Path> files = new ArrayList<>(committedScenarios());
        try (Stream<Path> negatives = Files.list(moduleDir().resolve("src/test/resources/negative"));
             Stream<Path> plans = Files.list(moduleDir().resolve("plan"))) {
            files.addAll(negatives.toList());
            files.addAll(plans.toList());
        }
        for (Path file : files) {
            String text = Files.readString(file);
            assertFalse(text.contains("password:") || text.contains("\"password\""), file + " holds a password");
        }
        for (Path yaml : committedScenarios()) {
            Plan plan = Plan.read(planFor(yaml));
            for (Plan.Slot slot : plan.slots()) {
                if (List.of("assistant", "supervisor").contains(slot.name())) {
                    assertEquals(new CredentialSpec("staff", "code"), slot.credential(), plan.scenarioId() + " "
                            + slot.name());
                }
            }
        }
    }

    /**
     * Scenario A's committed plan as phase2-contracts.md 5 and 13.4 describe it: version 2, five query slots in file
     * order and a captured {@code loan}, the two oracles with the guard, the evaluation order, the verifiers'
     * capabilities in {@code requires}, the generated {@code db} statements, and the capture on the {@code waitFor}.
     */
    @Test
    void scenarioAsPlanHasTheResolutionModel() throws Exception {
        Plan plan = Plan.read(ScenarioA.plan());
        assertEquals("0.3.0", plan.packVersion());
        assertEquals(List.of("systemMax/query", "item/query", "assistant/query", "supervisor/query", "keys/query",
                "loan/capture"), plan.slots().stream().map(sl -> sl.name() + "/" + sl.kind()).toList());
        assertEquals(List.of("SystemLimit", "LendableItem", "StaffWithPermission", "StaffWithPermission",
                "ActionKey"), plan.slots().stream().filter(sl -> sl.query() != null).map(sl -> sl.query().query())
                .toList());
        Plan.Slot item = plan.slots().get(1);
        assertEquals(List.of("sql"), item.query().strategy());
        assertEquals(List.of("lending.deposit"), item.query().verify());
        assertEquals(List.of(), plan.slots().get(0).query().strategy(), "not restricted unless written");
        assertEquals(List.of("lending.limits"), plan.slots().get(0).query().verify(), "the pack's default");
        assertEquals(List.of("swing.find"), plan.slots().get(4).query().verify());
        assertEquals(List.of("id", "total"), plan.slots().get(5).capture().fields());
        assertEquals(ScenarioA.GRAPH, plan.resolutionGraph());
        assertEquals(List.of("auth/ExpectedAuthorisation/SUPERVISOR_PROMPT", "returnLine/Money.negate/null"),
                plan.oracles().stream().map(o -> o.name() + "/" + o.fn() + "/" + o.mustEqual()).toList());
        assertTrue(plan.requires().containsAll(List.of("lending.input", "lending.limits", "lending.deposit",
                "lending.access.check", "swing.tree")), String.valueOf(plan.requires()));
        assertFalse(plan.requires().contains("lending.sql"), "the served-source check covers the channel");
        assertEquals(60_000L, plan.bindTimeoutMs());
        assertTrue(plan.needsBindings());
        List<String> sql = plan.verify().stream().filter(c -> c instanceof RowCheck).map(c -> ((RowCheck) c).sql())
                .toList();
        assertEquals(List.of(
                "SELECT id, staff_code, posted_at, total FROM loan WHERE id = ? ORDER BY id",
                "SELECT loan_id, line_no, item_code, qty, unit_deposit, amount, returned FROM loan_line"
                        + " WHERE loan_id = ? AND item_code = ? ORDER BY loan_id, line_no",
                "SELECT loan_id, kind, staff_code FROM loan_authorisation WHERE loan_id = ?"
                        + " ORDER BY loan_id, kind, staff_code"), sql);
        Plan.Step waitFor = plan.steps().get(plan.steps().size() - 1);
        assertEquals("waitFor", waitFor.verb());
        assertEquals(CaptureSpec.of("loan", Map.of("id", "loanId", "total", "total")), waitFor.calls().get(0)
                .capture());
        Plan.Step authorise = plan.steps().stream().filter(st -> st.verb().equals("authorise")).findFirst()
                .orElseThrow();
        assertEquals(List.of("lending.prompt.answer", "lending.input", "lending.input"), authorise.calls().stream()
                .map(Plan.Call::primitive).toList());
        assertEquals(Sync.EVENT, authorise.calls().get(0).sync());
        assertTrue(authorise.calls().get(0).handlesPrompt());
        assertEquals(Map.of("source", "Entry", "value", "$supervisor.password"), authorise.calls().get(2).args());
        assertEquals(Map.of("source", "Key.$keys.RETURN"), plan.steps().get(2).calls().get(0).args());
        assertEquals(plan.resolveHash(), PlanCompiler.compile(ScenarioA.yaml(), new LendingPack()).resolveHash());
    }

    /**
     * Scenario B's committed plan as phase3-contracts.md 4 and 10.4 describe it, written as version 4 since Phase 4
     * (phase4-contracts.md 7.3; the only difference to its version 3 form) and pack 0.3.0, four query
     * slots in file order and two captures (the posted loan and the printed slip, in that order), the waiver rule
     * resolved by SQL and verified by the desk's dry run, three oracles of which {@code waiver} alone carries the
     * cross-check (tolerance {@code "0.00"}, unguarded, so its capability is in {@code requires}), the stored-row
     * checks with the aggregate {@code sum(amount)}, the printer read through the base root {@code devices}, and no
     * date anywhere.
     */
    @Test
    void scenarioBsPlanHasTheWaiverModel() throws Exception {
        Plan plan = Plan.read(ScenarioB.plan());
        assertEquals(4, plan.planVersion());
        assertEquals("0.3.0", plan.packVersion());
        assertEquals(List.of("assistant/query", "rule/query", "item/query", "keys/query", "loan/capture",
                "slip/capture"), plan.slots().stream().map(sl -> sl.name() + "/" + sl.kind()).toList());
        assertEquals(List.of("StaffWithPermission", "ActiveWaiverRuleWithTriggers", "ItemsTriggering", "ActionKey"),
                plan.slots().stream().filter(sl -> sl.query() != null).map(sl -> sl.query().query()).toList());
        Plan.Slot rule = plan.slot("rule");
        assertEquals(List.of("sql"), rule.query().strategy());
        assertEquals(List.of("lending.waiver.evaluate"), rule.query().verify());
        assertEquals(Map.of("kind", "BORROW_N_WAIVE_M", "exclusiveItems", true), rule.query().args(),
                "no date in the arguments (decision D39)");
        assertEquals(Map.of("rule", "$rule"), plan.slot("item").query().args());
        assertEquals(List.of("lending.deposit"), plan.slot("item").query().verify());
        assertEquals(List.of("swing.find"), plan.slot("keys").query().verify(), "the kind's default");
        assertEquals(ScenarioB.GRAPH, plan.resolutionGraph());
        assertEquals(List.of("needed/LoanFromTrigger", "waiver/ExpectedWaiver", "due/ExpectedTotals"),
                plan.oracles().stream().map(o -> o.name() + "/" + o.fn()).toList());
        assertEquals(new Plan.CrossCheckSpec("lending.waiver.evaluate", "0.00", null), plan.oracle("waiver")
                .crossCheck());
        assertNull(plan.oracle("needed").crossCheck());
        assertNull(plan.oracle("due").crossCheck());
        assertTrue(plan.oracles().stream().noneMatch(Plan.Oracle::hasMustEqual), "no guard in scenario B");
        assertEquals(List.of("lending.input", "lending.state", "lending.loan", "lending.slip", "lending.access.check",
                "lending.deposit", "lending.waiver.evaluate", "swing.tree"), plan.requires());
        assertFalse(plan.requires().contains("lending.sql"), "the served-source check covers the channel");
        List<RowCheck> db = plan.verify().stream().filter(c -> c instanceof RowCheck).map(c -> (RowCheck) c).toList();
        assertEquals(List.of(
                "SELECT id, staff_code, posted_at, total FROM loan WHERE id = ? ORDER BY id",
                "SELECT loan_id, line_no, item_code, qty, unit_deposit, amount, returned FROM loan_line"
                        + " WHERE loan_id = ? ORDER BY loan_id, line_no",
                "SELECT loan_id, rule_code, amount FROM loan_waiver WHERE loan_id = ? ORDER BY loan_id, rule_code"),
                db.stream().map(RowCheck::sql).toList());
        assertEquals(Map.of("sum(amount)", Map.of("equals", "$oracle.waiver.amount")), db.get(2).aggregates());
        assertEquals(Map.of("rule_code", Map.of("equals", "$rule.code")), db.get(2).expect());
        assertEquals(Map.of(), db.get(0).aggregates());
        List<Plan.Step> waits = plan.steps().stream().filter(st -> st.verb().equals("waitFor")).toList();
        assertEquals(List.of(CaptureSpec.of("loan", Map.of("id", "loanId", "total", "total")),
                CaptureSpec.of("slip", Map.of("loanId", "loanId"))), waits.stream().map(st -> st.calls().get(0)
                .capture()).toList(), "two waits after one input, captured one after the other (3.5)");
        Plan.Step quantity = plan.steps().stream().filter(st -> st.verb().equals("quantity")).findFirst()
                .orElseThrow();
        assertEquals(Map.of("source", "Key.QTY", "value", "$oracle.needed.qty"), quantity.calls().get(0).args());
        Plan.Step deposit = plan.steps().stream().filter(st -> st.verb().equals("deposit")).findFirst().orElseThrow();
        assertEquals(Map.of("source", "Key.DEPOSIT", "value", "$oracle.due.total"), deposit.calls().get(0).args());
        assertTrue(plan.toJsonText().contains("\"devices[id=printer].state.last.text\""), "the base root devices");
        assertTrue(plan.needsBindings());
        assertEquals(plan.resolveHash(), PlanCompiler.compile(ScenarioB.yaml(), new LendingPack()).resolveHash());
    }

    /** The first {@code swing.click} call of a plan. */
    private static Plan.Call click(Plan plan) {
        return plan.steps().stream().flatMap(st -> st.calls().stream()).filter(c -> c.primitive().equals(
                "swing.click")).findFirst().orElseThrow();
    }
}
