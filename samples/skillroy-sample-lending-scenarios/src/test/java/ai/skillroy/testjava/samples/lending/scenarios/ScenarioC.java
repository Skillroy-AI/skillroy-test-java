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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.resolve.Resolution;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sample scenario C, {@code return-refunds-deposit} (phase5-contracts.md 14.3): the scenario the planner authored from
 * the brief of 14.3 with {@code skillroy author}, reviewed and kept as written, committed with its authoring record.
 * Where its files are, what it resolves to on each seed, and what a replay of it leaves at the desk (15.2 item 3).
 * Shared by the headless tests and the end-to-end checks.
 *
 * <p>The desk posts a return as a posting of its own: the scenario lends its item and captures the posting
 * ({@code lent}), then takes the item back and captures the return posting ({@code returned}), whose total is the
 * deposit negated (the oracle {@code refund}, {@code Money.negate}); the two postings net to zero, which is the
 * desk's reading of the brief's "the loan's total is back to zero" (the review section of the authoring record).
 *
 * <p>The planner's envelope and the YAML its post-processing rendered before the review are the fixture of the
 * planner's replay ({@code AuthorEnvelopeTest}, 15.1 item 11), under {@code src/test/resources/author/}.
 *
 * <p>Stateless; safe from any thread.
 */
final class ScenarioC {

    static final String ID = "return-refunds-deposit";
    static final String YAML = ID + ".yaml";

    /** The brief of 14.3, in full: what the planner was asked, as its authoring record and the YAML's head hold it. */
    static final String BRIEF = "A borrower returns the only item of a loan on the same day it was lent. The desk"
            + " takes the item back, hands the deposit back in cash, and prints a slip for the return. Choose an item"
            + " that needs no induction and an assistant who may lend and take returns within the item's deposit."
            + " Check the stored loan line is marked returned, the loan's total is back to zero, and the slip shows"
            + " the deposit handed back.";

    /** The slots and oracles in evaluation order ({@code resolutionGraph}). */
    static final List<String> GRAPH = List.of("item", "assistant", "oracle.refund");
    /** The query slots, in plan order. */
    static final List<String> QUERY_SLOTS = List.of("item", "assistant");
    /** The captured slots, in plan order: the lent posting, then the return posting. */
    static final List<String> CAPTURES = List.of("lent", "returned");
    /** The verbs of its steps in order: sign on, lend the item, check the posting, return it, check the return. */
    static final List<String> VERBS = List.of("signOn", "scan", "key", "deposit", "waitFor", "expect", "key", "scan",
            "key", "deposit", "waitFor", "expect", "expect");
    /**
     * The {@code approval.by} its bindings files may carry: {@code approve}'s own label, or the label of a later
     * approval through the recordings ({@code recorded <yyyy-mm-dd>}); the tests read it from the files
     * ({@link #approvedBy}), so that a new approval changes the files and not the tests.
     */
    static final Pattern APPROVED_BY = Pattern.compile("approve|recorded \\d{4}-\\d{2}-\\d{2}");

    /**
     * The values of the committed bindings of one seed, as the codec encodes them (the review of 14.3: the cheapest
     * counted item, which needs no induction on either seed, and the first assistant who may sign on and take a return
     * of its deposit, asked for a password).
     */
    record Expected(Map<String, Object> item, Map<String, Object> assistant, String refund,
                    String assistantPassword) {

        String itemCode() {
            return (String) item.get("code");
        }

        String itemTitle() {
            return (String) item.get("title");
        }

        String deposit() {
            return (String) item.get("deposit");
        }

        String assistantCode() {
            return (String) assistant.get("code");
        }
    }

    private ScenarioC() {
    }

    static Expected expected(Env env) {
        if (env == Env.SEED_A) {
            return new Expected(item("HND-003", "5000000000062", "Screwdriver set", "0.99"), staff("100", "20.00"),
                    "-0.99", SampleCredentials.SEED_A.get("100"));
        }
        return new Expected(item("T1002", "4006000000028", "Tyre levers", "0.79"), staff("4711", "15.00"), "-0.79",
                SampleCredentials.SEED_B.get("4711"));
    }

    private static Map<String, Object> item(String code, String barcode, String title, String deposit) {
        return Map.of("code", code, "barcode", barcode, "title", title, "deposit", deposit);
    }

    private static Map<String, Object> staff(String code, String limit) {
        return Map.of("code", code, "limits", Map.of("RETURN", limit), "permissions", List.of("RETURN", "SIGN_ON"));
    }

    /** {@code scenarios/<id>.yaml} (committed). */
    static Path yaml() {
        return ItEnv.scenario(YAML);
    }

    /** {@code plan/<id>.plan.json} (committed). */
    static Path plan() {
        return ItEnv.plan(ID);
    }

    /** {@code scenarios/<id>.authoring.json} (committed beside the YAML): the planner's record and the review. */
    static Path authoring() {
        return ItEnv.scenario(ID + ".authoring.json");
    }

    /** {@code bindings/<env>/<id>.bindings.json} (committed), in the module's {@code bindings/} root. */
    static Path committedBindings(Env env) {
        return BindingsFile.locate(ScenarioA.bindingsRoot(), env.id, ID);
    }

    /** {@code src/test/resources/author/<id>.envelope.json}: the envelopes the planner printed, masked (D112). */
    static Path envelopeFixture() {
        return author(ID + ".envelope.json");
    }

    /** {@code src/test/resources/author/<id>.authored.yaml}: the YAML the planner rendered, before the review. */
    static Path authoredYaml() {
        return author(ID + ".authored.yaml");
    }

    private static Path author(String file) {
        return ItEnv.moduleDir().resolve("src").resolve("test").resolve("resources").resolve("author").resolve(file);
    }

    /**
     * The {@code approval.by} of the committed bindings file of {@code env}: one label for every slot, which
     * {@link #APPROVED_BY} admits.
     *
     * @throws AssertionError when the slots carry different labels or a label the pattern does not admit
     */
    static String approvedBy(Env env) throws Exception {
        Path file = committedBindings(env);
        Set<String> labels = new TreeSet<>();
        for (Resolution resolution : BindingsFile.read(file).resolutions().values()) {
            labels.add(String.valueOf(resolution.approval().by()));
        }
        if (labels.size() != 1 || !APPROVED_BY.matcher(labels.iterator().next()).matches()) {
            throw new AssertionError(file + ": every slot approved under one label, 'approve' or 'recorded"
                    + " <yyyy-mm-dd>', found " + labels);
        }
        return labels.iterator().next();
    }

    /**
     * What a passing {@code replay} of scenario C on {@code env} wrote and left at the desk (15.2 item 3), for the
     * end-to-end check and its headless counterpart: no resolver ran, each query slot was replayed and its verifier
     * ran once, in plan order; no model was called and the run's ledger is empty; the run captured two postings, the
     * loan and then its return; each {@code db} check held at its first attempt with one row over {@code channel},
     * with the captured ids as its parameters (the lent posting's {@code loan} row with the item's deposit as its
     * total, the return posting's {@code loan} row with the refund as its total and its one {@code loan_line} row,
     * the item's, marked returned, with the refund as its amount: the two postings net to zero). Read back from the
     * desk: its last posting ({@code lending.loan}) is the return, one line of the item marked returned, the refund
     * as its total and as the deposit handed back in cash, no change; and its two last slips ({@code lending.slip})
     * are the return's (naming {@code RETURN}, the posting, the item and the refund) and the loan's (naming
     * {@code LOAN}, the posting, the item and the deposit).
     *
     * @param channel the data channel the {@code db} checks read through: {@code agent} end to end, {@code jdbc}
     *     against the headless desk
     */
    static void assertReplayed(Runs.Outcome outcome, Env env, String channel, AgentConnection desk) throws Exception {
        Expected expected = expected(env);
        List<Map<String, Object>> log = Runs.runLog(outcome.dir());
        assertFalse(log.stream().anyMatch(r -> "resolver".equals(r.get("type"))), "no resolver ran in replay");
        List<Map<String, Object>> resolves = log.stream().filter(r -> "resolve".equals(r.get("type"))).toList();
        assertEquals(QUERY_SLOTS, resolves.stream().map(r -> r.get("slot")).toList());
        assertTrue(resolves.stream().allMatch(r -> "REPLAY".equals(r.get("strategy"))), String.valueOf(resolves));
        assertEquals(QUERY_SLOTS, log.stream().filter(r -> "verifier".equals(r.get("type"))).map(r -> r.get("slot"))
                .toList(), "every slot's verifier ran once");
        assertEquals(List.of(), outcome.result().modelCalls(), "zero model calls");
        assertEquals(0L, Files.size(outcome.dir().resolve("cost-ledger.jsonl")), "the run's ledger is empty");

        Map<String, Object> bindings = Json.parseObject(Files.readString(outcome.dir().resolve("bindings.json")));
        Map<?, ?> captured = (Map<?, ?>) bindings.get("captured");
        assertEquals(Set.copyOf(CAPTURES), captured.keySet(), String.valueOf(captured));
        Object lentId = ((Map<?, ?>) captured.get("lent")).get("id");
        Object returnedId = ((Map<?, ?>) captured.get("returned")).get("id");
        assertTrue(lentId instanceof Number && returnedId instanceof Number, String.valueOf(captured));
        assertNotEquals(lentId, returnedId, "the return is a posting of its own");
        assertTrue(((Number) returnedId).longValue() > ((Number) lentId).longValue(), "posted after the loan");

        List<Map<String, Object>> db = log.stream().filter(r -> "db".equals(r.get("type"))).toList();
        assertEquals(List.of("loan", "loan", "loan_line"), db.stream().map(r -> r.get("table")).toList(),
                "each db check held at its first attempt");
        assertTrue(db.stream().allMatch(r -> Boolean.TRUE.equals(r.get("ok")) && Long.valueOf(1).equals(
                r.get("rowCount")) && channel.equals(r.get("channel"))), String.valueOf(db));
        assertEquals(List.of(List.of(lentId), List.of(returnedId), List.of(returnedId)), db.stream()
                .map(r -> r.get("params")).toList(), "the lent posting's row, then the return posting's rows");
        assertEquals(0, new BigDecimal(expected.deposit()).add(new BigDecimal(expected.refund())).signum(),
                "the lent posting's total and the return posting's net to zero");

        Map<String, Object> loan = LoadModeChecks.value(desk.call("lending.loan", Map.of(), ResolutionChecks.CALL));
        assertEquals(returnedId, loan.get("id"), "the desk's last posting is the return");
        assertEquals(Boolean.TRUE, loan.get("returning"));
        List<?> lines = (List<?>) loan.get("lines");
        assertEquals(1, lines.size(), String.valueOf(lines));
        Map<?, ?> line = (Map<?, ?>) lines.get(0);
        assertEquals(expected.itemCode(), line.get("code"));
        assertEquals(Boolean.TRUE, line.get("returned"), "the loan line is marked returned");
        assertEquals(expected.refund(), line.get("amount"));
        assertEquals(expected.refund(), loan.get("total"));
        assertEquals(List.of(Map.of("amount", expected.refund())), loan.get("deposits"), "the deposit handed back");
        assertEquals("0.00", loan.get("change"));

        Map<String, Object> slips = LoadModeChecks.value(desk.call("lending.slip", Map.of("last", 2L),
                ResolutionChecks.CALL));
        List<?> printed = (List<?>) slips.get("slips");
        assertEquals(2, printed.size(), String.valueOf(slips));
        Map<?, ?> returnSlip = (Map<?, ?>) printed.get(0);
        Map<?, ?> loanSlip = (Map<?, ?>) printed.get(1);
        assertEquals(returnedId, returnSlip.get("loanId"), "the newest slip is the return's");
        String returnText = String.valueOf(returnSlip.get("text"));
        assertTrue(returnText.contains("RETURN " + returnedId) && returnText.contains(expected.itemTitle())
                && returnText.contains(expected.refund()), returnText);
        assertEquals(lentId, loanSlip.get("loanId"), "the slip before it is the loan's");
        String loanText = String.valueOf(loanSlip.get("text"));
        assertTrue(loanText.contains("LOAN " + lentId) && loanText.contains(expected.itemTitle())
                && loanText.contains(expected.deposit()) && !loanText.contains(expected.refund()), loanText);
    }
}
