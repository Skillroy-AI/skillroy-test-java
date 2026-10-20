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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 grammar and compilation (phase3-contracts.md 3, 4, 11.1 item 1): the {@code crossCheck} shorthand and
 * mapping forms and their errors, the tolerance, the requirements of unguarded and guarded checks, aggregates of each
 * form and their errors, {@code $env.now}, plan version 3 written and versions 1 to 3 read, the secret-fixture rule,
 * byte-identical recompilation and the {@code resolveHash}.
 */
class Phase3CompileTest {

    /** The {@code resolveHash} the Phase 2 code computed for {@link BinderTest#YAML} with the toy pack. */
    static final String PHASE2_RESOLVE_HASH = "sha256:129f202f1a667abef58739ce44d39c6a9de34fb65219c4082eeaa72398744871";

    static final String WAIVERS = """
            scenario: t
            pack: toy
            resolve:
              item: { fixture: { code: B2, deposit: "5.00", loan: 41 } }
            oracle:
              neg: %s
            steps: []
            verify:
              - db.loan_waiver: %s
            """;

    private final ToyResolution toy = new ToyResolution();
    private ToyPack pack;

    @BeforeEach
    void setUp() {
        toy.withCrossChecks = true;
        pack = toy.pack();
        pack.storedTables = List.of(waiverTable());
    }

    /** A stored table with every column kind: two INTEGER, one DECIMAL, TEXT, BOOLEAN and TIMESTAMP. */
    static StoredTable waiverTable() {
        return StoredTable.of("loan_waiver", ToyResolution.SOURCE, "loan_waiver", List.of(
                StoredColumn.of("loan_id", ColumnKind.INTEGER),
                StoredColumn.of("rule_code", ColumnKind.TEXT),
                StoredColumn.of("amount", ColumnKind.DECIMAL).param("CAST(? AS DECIMAL(10,2))"),
                StoredColumn.of("units", ColumnKind.INTEGER),
                StoredColumn.of("waived", ColumnKind.BOOLEAN),
                StoredColumn.of("posted_at", ColumnKind.TIMESTAMP)), List.of("loan_id", "rule_code"));
    }

    private Plan compile(String yaml) throws ScenarioException {
        return PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
    }

    private ScenarioException failsAt(int line, String text, String yaml) {
        ScenarioException e = assertThrows(ScenarioException.class, () -> compile(yaml));
        assertEquals(line, e.line(), e.getMessage());
        assertTrue(e.getMessage().contains(text), e.getMessage());
        return e;
    }

    private static String waivers(String oracle, String check) {
        return WAIVERS.formatted(oracle, check);
    }

    private static String negWith(String crossCheck) {
        return "{ fn: Negate, args: [$item.deposit], crossCheck: " + crossCheck + " }";
    }

    private static final String PLAIN_CHECK = "{ where: { loan_id: $item.loan }, rule_code: B2W1 }";

    // ------------------------------------------------------------------------------------------- cross-checks

    @Test
    void theShorthandAndTheMappingForm() throws Exception {
        Plan shorthand = compile(waivers(negWith("toy.negate.check"), PLAIN_CHECK));
        assertEquals(new Plan.CrossCheckSpec("toy.negate.check", null, null), shorthand.oracle("neg").crossCheck());
        Plan mapping = compile(waivers(negWith("{ check: toy.negate.check, tolerance: \"0.00\","
                + " capability: toy.negate }"), PLAIN_CHECK));
        assertEquals(new Plan.CrossCheckSpec("toy.negate.check", "0.00", "toy.negate"),
                mapping.oracle("neg").crossCheck());
        Map<?, ?> oracle = (Map<?, ?>) ((List<?>) mapping.toJson().get("oracles")).get(0);
        assertEquals(List.of("name", "fn", "args", "crossCheck"), new ArrayList<>(oracle.keySet()),
                "crossCheck is the last key of an oracle entry");
        assertEquals(List.of("check", "tolerance", "capability"), new ArrayList<>(((Map<?, ?>) oracle
                .get("crossCheck")).keySet()));
        Map<?, ?> plain = (Map<?, ?>) ((List<?>) shorthand.toJson().get("oracles")).get(0);
        assertEquals(Map.of("check", "toy.negate.check"), plain.get("crossCheck"), "tolerance and capability only"
                + " when written");
        Plan withGuard = compile(waivers("{ fn: Negate, args: [$item.deposit], must_equal: \"-5.00\","
                + " crossCheck: toy.negate.check }", PLAIN_CHECK));
        Map<?, ?> guarded = (Map<?, ?>) ((List<?>) withGuard.toJson().get("oracles")).get(0);
        assertEquals(List.of("name", "fn", "args", "mustEqual", "crossCheck"), new ArrayList<>(guarded.keySet()));
    }

    @Test
    void theLoaderKeepsTheCrossCheckInSourceForm() throws Exception {
        Scenario scenario = ScenarioLoader.load(waivers(negWith("{ check: \" toy.negate.check \", tolerance: 0.5 }"),
                PLAIN_CHECK).getBytes(StandardCharsets.UTF_8), "t.yaml");
        assertEquals(Map.of("check", "toy.negate.check", "tolerance", 0.5), scenario.oracles().get(0).crossCheck());
        assertEquals(Map.of("fn", "Negate", "args", List.of("$item.deposit"), "crossCheck", Map.of("check",
                "toy.negate.check", "tolerance", 0.5)), scenario.oracle().get("neg"));
        Scenario shorthand = ScenarioLoader.load(waivers(negWith("toy.negate.check"), PLAIN_CHECK)
                .getBytes(StandardCharsets.UTF_8), "t.yaml");
        assertEquals("toy.negate.check", shorthand.oracles().get(0).crossCheck());
        Scenario none = ScenarioLoader.load(waivers("{ fn: Negate, args: [$item.deposit] }", PLAIN_CHECK)
                .getBytes(StandardCharsets.UTF_8), "t.yaml");
        assertNull(none.oracles().get(0).crossCheck());
        assertEquals(new Scenario.OracleEntry("o", "Negate", null, null, false, 3, null),
                new Scenario.OracleEntry("o", "Negate", null, null, false, 3), "the Phase 2 constructor");
    }

    @Test
    void shapeErrorsInsideCrossCheckNameTheKeyAndLine() {
        failsAt(6, "crossCheck: unknown key 'colour'; allowed: check, tolerance, capability", waivers(negWith(
                "{ check: toy.negate.check, colour: red }"), PLAIN_CHECK));
        failsAt(6, "crossCheck: 'check' must name a cross-check of the pack (missing)", waivers(negWith(
                "{ tolerance: \"0.01\" }"), PLAIN_CHECK));
        failsAt(6, "crossCheck must be a cross-check id or a mapping {check, tolerance?, capability?}, found 5",
                waivers(negWith("5"), PLAIN_CHECK));
        failsAt(6, "'tolerance' must be a decimal number or its text", waivers(negWith(
                "{ check: toy.negate.check, tolerance: [1] }"), PLAIN_CHECK));
        failsAt(6, "'capability' must name a capability", waivers(negWith(
                "{ check: toy.negate.check, capability: 7 }"), PLAIN_CHECK));
    }

    @Test
    void aCheckThePackLacksForTheFunctionListsWhatItOffers() {
        failsAt(6, "oracle 'neg': no cross-check toy.needs.check for oracle Negate; the pack offers: toy.negate.check",
                waivers(negWith("toy.needs.check"), PLAIN_CHECK));
        failsAt(6, "no cross-check nope for oracle Identity; the pack offers: none", waivers(
                "{ fn: Identity, args: [$item.deposit], crossCheck: nope }", PLAIN_CHECK));
    }

    @Test
    void aToleranceIsADecimalOfZeroOrMoreForACheckThatTakesOne() throws Exception {
        failsAt(6, "cross-check toy.needs.check takes no tolerance", waivers("{ fn: Needs, args: { limit: \"1\","
                + " amount: $item.deposit }, crossCheck: { check: toy.needs.check, tolerance: \"0\" } }", PLAIN_CHECK));
        failsAt(6, "the tolerance of cross-check toy.negate.check must be a decimal of zero or more, found \"-1\"",
                waivers(negWith("{ check: toy.negate.check, tolerance: \"-1\" }"), PLAIN_CHECK));
        failsAt(6, "must be a decimal of zero or more, found \"abc\"", waivers(negWith(
                "{ check: toy.negate.check, tolerance: abc }"), PLAIN_CHECK));
        Map<String, String> normalised = Map.of("\"0.00\"", "0.00", "0.5", "0.5", "2", "2", "\"1e1\"", "10",
                "\"0.010\"", "0.010");
        for (Map.Entry<String, String> e : normalised.entrySet()) {
            Plan plan = compile(waivers(negWith("{ check: toy.negate.check, tolerance: " + e.getKey() + " }"),
                    PLAIN_CHECK));
            assertEquals(e.getValue(), plan.oracle("neg").crossCheck().tolerance(), e.getKey());
            assertTrue(plan.toJsonText().contains("\"tolerance\": \"" + e.getValue() + "\""), "a string in the plan");
        }
    }

    @Test
    void anUnguardedCheckAddsItsRequirementsAndAGuardedOneDoesNot() throws Exception {
        String yaml = """
                scenario: t
                pack: toy
                requires: [toy.input, toy.negate]
                resolve:
                  item: { query: Item, args: { deposit: { lte: "50.00" } } }
                oracle:
                  neg: { fn: Negate, args: [$item.deposit], crossCheck: toy.negate.check }
                  needs: { fn: Needs, args: { limit: "1", amount: $item.deposit }, crossCheck: %s }
                steps: []
                """;
        Plan unguarded = compile(yaml.formatted("toy.needs.check"));
        assertEquals(List.of("toy.input", "toy.negate", "toy.item", "toy.needs"), unguarded.requires(),
                "the scenario's list, then the added ids sorted, without duplicates");
        Plan guarded = compile(yaml.formatted("{ check: toy.needs.check, capability: toy.needs }"));
        assertEquals(List.of("toy.input", "toy.negate", "toy.item"), guarded.requires(),
                "a guarded check adds nothing to requires");
        assertEquals("toy.needs", guarded.oracle("needs").crossCheck().capability());
    }

    // ------------------------------------------------------------------------------------------- aggregates

    @Test
    void aggregatesOfEachFormCompileWithSortedKeysAfterExpect() throws Exception {
        Plan plain = compile(waivers("{ fn: Negate, args: [$item.deposit] }", PLAIN_CHECK));
        Plan plan = compile(waivers("{ fn: Negate, args: [$item.deposit] }", "{ where: { loan_id: $item.loan },"
                + " rule_code: B2W1, sum(amount): $item.deposit, min(units): 1, max(amount): { lte: \"5.00\" },"
                + " capability: toy.db }"));
        RowCheck check = (RowCheck) plan.verify().get(0);
        RowCheck plainCheck = (RowCheck) plain.verify().get(0);
        assertEquals(plainCheck.sql(), check.sql(), "aggregates add no column and no clause");
        assertEquals(List.of("max(amount)", "min(units)", "sum(amount)"), new ArrayList<>(check.aggregates()
                .keySet()), "sorted");
        assertEquals(Map.of("equals", "$item.deposit"), check.aggregates().get("sum(amount)"));
        assertEquals(Map.of("equals", 1L), check.aggregates().get("min(units)"));
        assertEquals(Map.of("lte", "5.00"), check.aggregates().get("max(amount)"));
        assertEquals(List.of("db", "source", "sql", "params", "rows", "expect", "aggregates", "capability"),
                new ArrayList<>(check.toJson().keySet()), "aggregates after expect and before capability");
        assertEquals(List.of("db", "source", "sql", "params", "rows", "expect"), new ArrayList<>(plainCheck.toJson()
                .keySet()), "no aggregates key without an aggregate");
        assertEquals(Map.of("equals", 1L), check.rows(), "rows still defaults to 1");
        String text = plan.toJsonText();
        assertEquals(text, Plan.parse(text).toJsonText(), "reads back");
        assertEquals(check, Plan.parse(text).verify().get(0));
        assertEquals(new RowCheck.Aggregate("sum", "amount"), RowCheck.Aggregate.parse("sum(amount)"));
        assertEquals("max(units)", RowCheck.Aggregate.parse("max(units)").key());
        for (String notOne : List.of("count()", "avg(amount)", "SUM(amount)", "sum( amount )", "sum(amount", "amount",
                "sum(a.b)")) {
            assertNull(RowCheck.Aggregate.parse(notOne), notOne);
        }
        Plan onlyAggregates = compile(waivers("{ fn: Negate, args: [$item.deposit] }",
                "{ where: { loan_id: 1 }, sum(amount): \"0.99\" }"));
        assertEquals(1, ((RowCheck) onlyAggregates.verify().get(0)).aggregates().size(), "an aggregate alone is"
                + " enough to make a check");
    }

    @Test
    void anAggregateOfATextBooleanOrTimestampColumnIsRefused() {
        failsAt(9, "sum(rule_code): rule_code is TEXT; aggregates take INTEGER or DECIMAL columns", waivers(
                "{ fn: Identity, args: [1] }", "{ where: {}, sum(rule_code): 1 }"));
        failsAt(9, "min(waived): waived is BOOLEAN; aggregates take INTEGER or DECIMAL columns", waivers(
                "{ fn: Identity, args: [1] }", "{ where: {}, min(waived): 1 }"));
        failsAt(9, "max(posted_at): posted_at is TIMESTAMP; aggregates take INTEGER or DECIMAL columns", waivers(
                "{ fn: Identity, args: [1] }", "{ where: {}, max(posted_at): 1 }"));
        failsAt(9, "'colour' is not a column of table loan_waiver", waivers("{ fn: Identity, args: [1] }",
                "{ where: {}, sum(colour): 1 }"));
    }

    @Test
    void otherFunctionCallsAreRefusedWithTheListOfThree() {
        for (String key : List.of("count()", "avg(amount)", "SUM(amount)", "\"sum( amount )\"")) {
            failsAt(9, "is not an aggregate; the aggregates are sum(<column>), min(<column>), max(<column>) over an"
                    + " INTEGER or DECIMAL column", waivers("{ fn: Identity, args: [1] }", "{ where: {}, " + key
                    + ": 1 }"));
        }
    }

    // ------------------------------------------------------------------------------------------- $env, versions

    @Test
    void envNowIsRefusedWithTheMessageOfTheDecision() {
        failsAt(4, "$env.now: $env has the field id only; a query that depends on the date reads the target's"
                + " business date at bind time (see the pack's documentation)", "scenario: t\npack: toy\nresolve:\n"
                + "  item: { query: Item, args: { deposit: { lte: $env.now } } }\nsteps: []\n");
    }

    /**
     * Phase 4 (phase4-contracts.md 7.3) raised the written version to 4, Phase 5 (phase5-contracts.md 13.2) to 5,
     * Phase 7 (phase7-contracts.md 8.2) to 6 and Phase 8 (phase8-contracts.md 8.2) to 7; the version 3 rules stand: a
     * version 3 plan still reads, and the Phase 3 keys still need version 3.
     */
    @Test
    void version7IsWrittenVersions1To7AreReadAndOthersRefused() throws Exception {
        Plan plan = compile(waivers(negWith("{ check: toy.negate.check, tolerance: \"0.00\" }"), PLAIN_CHECK));
        assertEquals(7, plan.planVersion());
        assertEquals(7, Plan.VERSION);
        String v7 = plan.toJsonText();
        assertEquals(v7, Plan.parse(v7).toJsonText());
        for (String version : List.of("0", "8", "-1")) {
            ScenarioException e = assertThrows(ScenarioException.class, () -> Plan.parse(v7.replace(
                    "\"planVersion\": 7", "\"planVersion\": " + version)));
            assertTrue(e.getMessage().contains("not supported (expected 1, 2, 3, 4, 5, 6 or 7)"), e.getMessage());
        }
        String v6 = v7.replace("\"planVersion\": 7", "\"planVersion\": 6");
        assertEquals(v7, Plan.parse(v6).toJsonText(), "a version 6 plan is read as version 7");
        String v5 = v7.replace("\"planVersion\": 7", "\"planVersion\": 5");
        assertEquals(v7, Plan.parse(v5).toJsonText(), "a version 5 plan is read as version 7");
        String v4 = v7.replace("\"planVersion\": 7", "\"planVersion\": 4");
        assertEquals(v7, Plan.parse(v4).toJsonText(), "a version 4 plan is read as version 7");
        String v3 = v7.replace("\"planVersion\": 7", "\"planVersion\": 3");
        assertEquals(v7, Plan.parse(v3).toJsonText(), "a version 3 plan with cross-checks is read as version 7");
        String v2 = v7.replace("\"planVersion\": 7", "\"planVersion\": 2");
        ScenarioException crossCheck = assertThrows(ScenarioException.class, () -> Plan.parse(v2));
        assertTrue(crossCheck.getMessage().contains("'crossCheck' needs a version 3 plan"), crossCheck.getMessage());
        Plan withoutCheck = compile(waivers("{ fn: Negate, args: [$item.deposit] }",
                "{ where: { loan_id: $item.loan }, sum(amount): \"0.99\" }"));
        String v2Aggregates = withoutCheck.toJsonText().replace("\"planVersion\": 7", "\"planVersion\": 2");
        ScenarioException aggregates = assertThrows(ScenarioException.class, () -> Plan.parse(v2Aggregates));
        assertTrue(aggregates.getMessage().contains("'aggregates' needs a version 3 plan"), aggregates.getMessage());

        Plan phase2 = compile(waivers("{ fn: Negate, args: [$item.deposit] }", PLAIN_CHECK));
        Plan read = Plan.parse(phase2.toJsonText().replace("\"planVersion\": 7", "\"planVersion\": 2"));
        assertEquals(7, read.planVersion(), "a version 2 plan is read as version 7");
        assertNull(read.oracle("neg").crossCheck(), "without cross-checks");
        assertEquals(Map.of(), ((RowCheck) read.verify().get(0)).aggregates(), "and without aggregates");
        assertEquals(phase2.resolveHash(), read.resolveHash());
        Plan v1 = SecretMaskTest.version1(compile("scenario: t\npack: toy\nsteps: []\n"), Map.of());
        assertEquals(7, v1.planVersion(), "a version 1 plan is read as version 7");
    }

    @Test
    void theSecretFixtureRuleAppliesToVersion2AndLater() throws Exception {
        Plan plan = compile("scenario: t\npack: toy\nresolve:\n  vault: { fixture: { label: main } }\nsteps: []\n");
        String text = plan.toJsonText().replace("\"label\": \"main\"", "\"secret\": \"main\"");
        for (String version : List.of("2", "3", "4", "5", "6", "7")) {
            ScenarioException e = assertThrows(ScenarioException.class, () -> Plan.parse(text.replace(
                    "\"planVersion\": 7", "\"planVersion\": " + version)), version);
            assertTrue(e.getMessage().contains("the fixture field 'secret' looks secret"), e.getMessage());
        }
        Plan v1 = SecretMaskTest.version1(plan, Map.of("label", "secret"));
        assertEquals("main", ((Map<?, ?>) v1.slot("vault").value()).get("secret"), "version 1 relies on A25");
    }

    @Test
    void recompilationIsByteIdentical() throws Exception {
        String yaml = waivers(negWith("{ check: toy.negate.check, tolerance: 0.5, capability: toy.negate }"),
                "{ where: { loan_id: $item.loan }, sum(amount): \"0.99\", max(units): 2, rule_code: B2W1 }");
        String first = compile(yaml).toJsonText();
        for (int i = 0; i < 3; i++) {
            ToyResolution again = new ToyResolution();
            again.withCrossChecks = true;
            ToyPack other = again.pack();
            other.storedTables = List.of(waiverTable());
            assertEquals(first, PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "elsewhere.yaml", other)
                    .toJsonText());
        }
        assertEquals(first, Plan.parse(first).toJsonText());
        assertEquals(Json.parse(first), Json.parse(Plan.parse(first).toJsonText()));
    }

    @Test
    void theResolveHashIgnoresAggregatesAndCoversACrossCheck() throws Exception {
        String base = waivers("{ fn: Negate, args: [$item.deposit] }", PLAIN_CHECK);
        Plan plain = compile(base);
        Plan aggregate = compile(waivers("{ fn: Negate, args: [$item.deposit] }", "{ where: { loan_id: $item.loan },"
                + " rule_code: B2W1, sum(amount): \"0.99\" }"));
        assertEquals(plain.resolveHash(), aggregate.resolveHash(), "an aggregate is an assertion on a run (D42)");
        assertNotEquals(plain.hash(), aggregate.hash());
        Plan checked = compile(waivers(negWith("toy.negate.check"), PLAIN_CHECK));
        assertNotEquals(plain.resolveHash(), checked.resolveHash(), "adding a cross-check asks for re-approval");
        Plan tolerant = compile(waivers(negWith("{ check: toy.negate.check, tolerance: \"0.01\" }"), PLAIN_CHECK));
        assertNotEquals(checked.resolveHash(), tolerant.resolveHash());
    }

    @Test
    void aPhase2ScenarioKeepsTheResolveHashPhase2Computed() throws Exception {
        assertEquals(PHASE2_RESOLVE_HASH, compile(BinderTest.YAML).resolveHash(),
                "an oracle without a cross-check is written exactly as in Phase 2 (4: the hash does not change)");
        assertEquals(PHASE2_RESOLVE_HASH, Plan.parse(compile(BinderTest.YAML).toJsonText().replace(
                "\"planVersion\": 7", "\"planVersion\": 2")).resolveHash(), "and a version 2 plan of it alike");
    }
}
