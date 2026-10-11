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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ActionKey;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ActiveWaiverRuleWithTriggers;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ItemRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ItemsTriggering;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LendableItem;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LimitCondition;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffWithPermission;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.SystemLimit;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.WaiverItem;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.WaiverRuleRef;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The lending pack's verifiers (phase2-contracts.md 13.3, with the project lead's answer Q12, and phase3-contracts.md
 * 10.3): each one asks the desk's own decision code, through a read-only domain primitive, whether a candidate answers
 * its query. None re-runs the resolver's SQL. A refusal is a failed {@link VerifierResult} with a reason, never an
 * exception; the evidence lists every call made and what the desk answered.
 *
 * <p>Every primitive used here is read-only and answers before any sign-on, as a bind requires. Stateless; shared by
 * every bind in the JVM.
 */
final class LendingVerifiers {

    /** The primitive of the desk's dry run of its waiver rules. */
    static final String EVALUATE = "lending.waiver.evaluate";

    private LendingVerifiers() {
    }

    /** One read-only call and its answer, recorded as evidence. */
    static Map<String, Object> call(AgentConnection agent, String primitive, Map<String, Object> params,
                                    List<Object> evidence) throws AgentRpcException, AgentLostException {
        Object answer = agent.call(primitive, params).value();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("primitive", primitive);
        entry.put("params", params);
        entry.put("answer", answer);
        evidence.add(entry);
        if (answer instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    static Map<String, Object> evidence(List<Object> calls) {
        return Map.of("calls", calls);
    }

    /**
     * The evidence of a dry run: the calls and the date the desk evaluated its rules on. The date belongs here, not in
     * a reason: a reason is written to the bindings file, which must be the same on every day.
     */
    static Map<String, Object> evidence(List<Object> calls, String date) {
        return Map.of("calls", calls, "date", date);
    }

    static String text(BigDecimal amount) {
        return amount == null ? "unlimited" : LendingResolvers.money(amount).toPlainString();
    }

    static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** {@code lending.limits}: the desk reports a system maximum equal to the candidate. */
    static final class LimitsVerifier implements Verifier<SystemLimit, BigDecimal> {

        @Override
        public String id() {
            return "lending.limits";
        }

        @Override
        public Class<SystemLimit> queryClass() {
            return SystemLimit.class;
        }

        @Override
        public List<String> requires() {
            return List.of("lending.limits");
        }

        @Override
        public VerifierResult verify(SystemLimit query, BigDecimal candidate, VerifyContext ctx)
                throws AgentRpcException, AgentLostException {
            List<Object> calls = new ArrayList<>();
            if (query.type() != null && !"RETURN".equals(query.type())) {
                return VerifierResult.fail("the desk reports a system maximum for RETURN only, not " + query.type(),
                        evidence(calls));
            }
            Map<String, Object> answer = call(ctx.agent(), "lending.limits", Map.of(), calls);
            BigDecimal reported = LendingResolvers.decimal(answer.get("maxReturn"));
            if (!sameAmount(reported, candidate)) {
                return VerifierResult.fail("the desk reports maxReturn " + text(reported) + ", not "
                        + text(candidate), evidence(calls));
            }
            return VerifierResult.pass("the desk reports maxReturn " + text(reported), evidence(calls));
        }
    }

    /**
     * {@code lending.deposit}: a scan of the candidate's barcode finds the candidate, active, at its deposit, lent the
     * way the query asks (per day or counted) and, when the query names it, with or without the induction check.
     */
    static final class DepositVerifier implements Verifier<LendableItem, ItemRef> {

        @Override
        public String id() {
            return "lending.deposit";
        }

        @Override
        public Class<LendableItem> queryClass() {
            return LendableItem.class;
        }

        @Override
        public List<String> requires() {
            return List.of("lending.deposit");
        }

        @Override
        public VerifierResult verify(LendableItem query, ItemRef candidate, VerifyContext ctx)
                throws AgentRpcException, AgentLostException {
            List<Object> calls = new ArrayList<>();
            Scan scan = scan(candidate, Boolean.TRUE.equals(query.perDay()), ctx, calls);
            if (scan.failure() != null) {
                return scan.failure();
            }
            if (query.inductionRequired() != null
                    && !query.inductionRequired().equals(scan.answer().get("inductionRequired"))) {
                return VerifierResult.fail("the desk " + (query.inductionRequired() ? "asks for no induction check"
                        : "asks for the induction check") + " for " + candidate.code(), evidence(calls));
            }
            return VerifierResult.pass(scan.found() + (query.inductionRequired() == null ? ""
                    : query.inductionRequired() ? ", induction checked" : ", no induction"), evidence(calls));
        }
    }

    /**
     * What a scan of a candidate's barcode showed ({@code lending.deposit {barcode}}): the failure, or the desk's
     * answer and the reason so far.
     *
     * @param failure the first condition that does not hold, or null
     * @param answer the desk's answer
     * @param found the pass reason so far ({@code "a scan of … finds … at …, active, counted"}); null on failure
     */
    record Scan(VerifierResult failure, Map<String, Object> answer, String found) {
    }

    /**
     * The scan the {@code lending.deposit} verifiers make: a scan of the candidate's barcode finds the candidate,
     * active, at its deposit, lent per day or counted as asked.
     */
    static Scan scan(ItemRef candidate, boolean perDay, VerifyContext ctx, List<Object> calls)
            throws AgentRpcException, AgentLostException {
        Map<String, Object> answer = call(ctx.agent(), "lending.deposit", Map.of("barcode", candidate.barcode()),
                calls);
        String scan = "a scan of " + candidate.barcode();
        if (!Boolean.TRUE.equals(answer.get("found"))) {
            return failed(scan + " finds no item", answer, calls);
        }
        if (!candidate.code().equals(answer.get("code"))) {
            return failed(scan + " finds item " + answer.get("code") + ", not " + candidate.code(), answer, calls);
        }
        if (!Boolean.TRUE.equals(answer.get("active"))) {
            return failed("the desk reports item " + candidate.code() + " not for loan", answer, calls);
        }
        BigDecimal deposit = LendingResolvers.decimal(answer.get("deposit"));
        if (!sameAmount(deposit, candidate.deposit())) {
            return failed("the desk charges a deposit of " + text(deposit) + " for " + candidate.code() + ", not "
                    + text(candidate.deposit()), answer, calls);
        }
        if (!Boolean.valueOf(perDay).equals(answer.get("perDay"))) {
            return failed("the desk lends " + candidate.code() + (perDay ? " counted, not per day"
                    : " per day, not counted"), answer, calls);
        }
        return new Scan(null, answer, scan + " finds " + candidate.code() + " at " + text(deposit) + ", active, "
                + (perDay ? "per day" : "counted"));
    }

    private static Scan failed(String reason, Map<String, Object> answer, List<Object> calls) {
        return new Scan(VerifierResult.fail(reason, evidence(calls)), answer, null);
    }

    /**
     * {@code lending.deposit} for {@code ItemsTriggering} (phase3-contracts.md 10.3; one verifier id for two kinds):
     * the candidate is one of the items of the query's rule, and, as for {@code LendableItem}, a scan of its barcode
     * finds it, active, at its deposit, lent counted or per day as the query asks; and the desk asks for the induction
     * check exactly when the query does.
     */
    static final class TriggeringItemVerifier implements Verifier<ItemsTriggering, ItemRef> {

        @Override
        public String id() {
            return "lending.deposit";
        }

        @Override
        public Class<ItemsTriggering> queryClass() {
            return ItemsTriggering.class;
        }

        @Override
        public List<String> requires() {
            return List.of("lending.deposit");
        }

        @Override
        public VerifierResult verify(ItemsTriggering query, ItemRef candidate, VerifyContext ctx)
                throws AgentRpcException, AgentLostException {
            List<Object> calls = new ArrayList<>();
            WaiverRuleRef rule = query.rule();
            if (rule == null) {
                return VerifierResult.fail("the query names no rule", evidence(calls));
            }
            if (rule.items() == null || rule.items().stream().noneMatch(item -> item.code().equals(candidate.code()))) {
                return VerifierResult.fail("item " + candidate.code() + " is not one that rule " + rule.code()
                        + " covers", evidence(calls));
            }
            Scan scan = scan(candidate, Boolean.TRUE.equals(query.perDay()), ctx, calls);
            if (scan.failure() != null) {
                return scan.failure();
            }
            boolean induction = Boolean.TRUE.equals(query.inductionRequired());
            if (!Boolean.valueOf(induction).equals(scan.answer().get("inductionRequired"))) {
                return VerifierResult.fail(induction ? "the desk lends " + candidate.code() + " without the induction"
                        + " check, which the query asks for" : "the desk asks for the induction check when it lends "
                        + candidate.code(), evidence(calls));
            }
            return VerifierResult.pass(scan.found() + (induction ? ", induction check" : ", no induction check"),
                    evidence(calls));
        }
    }

    /**
     * {@code lending.waiver.evaluate} for {@code ActiveWaiverRuleWithTriggers} (phase3-contracts.md 10.3): the desk's
     * own dry run of one group ({@code borrowQty + waivedQty} units) of the candidate's cheapest counted item (the
     * first of its items that is not lent per day), on the desk's business date (the call names no date), answers a
     * waiver under the candidate's code of {@code waivedQty} times that item's deposit; with
     * {@code exclusiveItems: true} it is the only waiver. Before the call, the candidate must answer the query's own
     * conditions (its kind, and its quantities where the query names them). The reasons name no date (a reason is
     * written to the bindings file, which must be the same on every day); the date the desk answered with is in the
     * evidence ({@code date}).
     *
     * <p>A rule that has left its date window, or was switched off, since it was resolved fails here with the desk's
     * own answer (the desk waives nothing under the rule): the check that keeps a replayed binding honest without a
     * date in the scenario (phase3-contracts.md 3.3, decision D39). An item the desk does not know
     * ({@code INVALID_PARAMS}, reason {@code unknown item}) is a failure too; any other error of the call is not
     * handled.
     */
    static final class WaiverVerifier implements Verifier<ActiveWaiverRuleWithTriggers, WaiverRuleRef> {

        @Override
        public String id() {
            return "lending.waiver.evaluate";
        }

        @Override
        public Class<ActiveWaiverRuleWithTriggers> queryClass() {
            return ActiveWaiverRuleWithTriggers.class;
        }

        @Override
        public List<String> requires() {
            return List.of("lending.waiver.evaluate");
        }

        @Override
        public VerifierResult verify(ActiveWaiverRuleWithTriggers query, WaiverRuleRef candidate, VerifyContext ctx)
                throws AgentRpcException, AgentLostException {
            List<Object> calls = new ArrayList<>();
            String rule = candidate.code();
            if (!query.kindOrDefault().equals(candidate.kind())) {
                return VerifierResult.fail("rule " + rule + " is of kind " + candidate.kind() + ", not "
                        + query.kindOrDefault(), evidence(calls));
            }
            if (query.borrowQty() != null && query.borrowQty() != candidate.borrowQty()
                    || query.waivedQty() != null && query.waivedQty() != candidate.waivedQty()) {
                return VerifierResult.fail("rule " + rule + " is " + candidate.borrowQty() + " + "
                        + candidate.waivedQty() + ", not what the query asks", evidence(calls));
            }
            WaiverItem item = cheapestCounted(candidate);
            if (item == null) {
                return VerifierResult.fail("the rule covers no counted item the desk can lend", evidence(calls));
            }
            long units = (long) candidate.borrowQty() + candidate.waivedQty();
            if (candidate.waivedQty() < 1 || units < 1) {
                return VerifierResult.fail("rule " + rule + " is " + candidate.borrowQty() + " + "
                        + candidate.waivedQty() + ", which waives no unit", evidence(calls));
            }
            BigDecimal expected = LendingResolvers.money(item.deposit().multiply(
                    BigDecimal.valueOf(candidate.waivedQty())));
            String lot = units(units, item.code());
            Map<String, Object> params = Map.of("lines", List.of(Map.of("code", item.code(), "qty", units)));
            Map<String, Object> answer;
            try {
                answer = call(ctx.agent(), EVALUATE, params, calls);
            } catch (AgentRpcException e) {
                if (!unknownItem(e)) {
                    throw e;
                }
                calls.add(errorEntry(EVALUATE, params, e));
                return VerifierResult.fail("the desk does not know item " + item.code(), evidence(calls));
            }
            String date = String.valueOf(answer.get("date"));
            Map<String, BigDecimal> waivers = waivers(answer);
            BigDecimal desk = waivers.get(rule);
            if (desk == null) {
                return VerifierResult.fail("the desk waives nothing under " + rule + " for " + lot
                        + (waivers.isEmpty() ? "" : "; it waives " + describe(waivers)), evidence(calls, date));
            }
            if (desk.compareTo(expected) != 0) {
                return VerifierResult.fail("the desk waives " + text(desk) + " for " + lot + " under " + rule + ", not "
                        + text(expected), evidence(calls, date));
            }
            if (Boolean.TRUE.equals(query.exclusiveItems()) && waivers.size() > 1) {
                Map<String, BigDecimal> others = new LinkedHashMap<>(waivers);
                others.remove(rule);
                return VerifierResult.fail(lot + " trigger other rules too: " + describe(others),
                        evidence(calls, date));
            }
            return VerifierResult.pass("the desk waives " + text(desk) + " for " + lot + " under " + rule,
                    evidence(calls, date));
        }
    }

    /** The first item of the rule that is not lent per day (the cheapest: the items come cheapest first), or null. */
    static WaiverItem cheapestCounted(WaiverRuleRef rule) {
        if (rule.items() == null) {
            return null;
        }
        for (WaiverItem item : rule.items()) {
            if (!item.perDay()) {
                return item;
            }
        }
        return null;
    }

    /** {@code "3 units of HND-003"}, {@code "1 unit of HND-003"}. */
    static String units(long units, String code) {
        return units + (units == 1 ? " unit of " : " units of ") + code;
    }

    /**
     * The waivers of a {@code lending.waiver.evaluate} answer, rule code to amount in the answer's order.
     *
     * @throws IllegalStateException when the answer has not the primitive's shape (a defect, reported as ENVIRONMENT)
     */
    static Map<String, BigDecimal> waivers(Map<String, Object> answer) {
        if (!(answer.get("waivers") instanceof List<?> list)) {
            throw new IllegalStateException(EVALUATE + " answered without a waivers list");
        }
        Map<String, BigDecimal> waivers = new LinkedHashMap<>();
        for (Object entry : list) {
            BigDecimal amount = entry instanceof Map<?, ?> waiver ? LendingResolvers.decimal(waiver.get("amount"))
                    : null;
            if (amount == null || !(((Map<?, ?>) entry).get("ruleCode") instanceof String code)) {
                throw new IllegalStateException(EVALUATE + " answered a waiver without a rule code and an amount");
            }
            waivers.put(code, amount);
        }
        return waivers;
    }

    /** {@code "B2W1-HAND 0.99, B2W1-CYCLE 0.79"}. */
    static String describe(Map<String, BigDecimal> waivers) {
        List<String> parts = new ArrayList<>();
        waivers.forEach((code, amount) -> parts.add(code + " " + text(amount)));
        return String.join(", ", parts);
    }

    /** {@code INVALID_PARAMS} with {@code data.reason = "unknown item"}: the desk's catalogue does not know an item. */
    static boolean unknownItem(AgentRpcException e) {
        return e.error().code() == ErrorCodes.INVALID_PARAMS && e.error().data() != null
                && "unknown item".equals(e.error().data().get("reason"));
    }

    /** A failed call as evidence: the primitive, the params and the error. */
    static Map<String, Object> errorEntry(String primitive, Map<String, Object> params, AgentRpcException e) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("primitive", primitive);
        entry.put("params", params);
        entry.put("error", e.error().toJson());
        return entry;
    }

    /**
     * {@code lending.access.check}: the desk's access control agrees, for the candidate, with every condition of the
     * query: not one of {@code distinctFrom}; every permission of {@code perform} allowed; the limit the desk reports
     * equals the candidate's and satisfies every bound; each bound probed with an amount on {@code RETURN} and
     * {@code RETURN_OVERRIDE} where the query asks for them (allowed at a lower bound, refused at a strict upper
     * bound); and, for {@code interactiveSignOn: true}, the desk asks the staff member for a password (its
     * {@code autoSignOn} is false: the project lead's answer Q12).
     */
    static final class AccessVerifier implements Verifier<StaffWithPermission, StaffRef> {

        private static final List<String> AMOUNT_PERMISSIONS = List.of("RETURN", "RETURN_OVERRIDE");

        @Override
        public String id() {
            return "lending.access.check";
        }

        @Override
        public Class<StaffWithPermission> queryClass() {
            return StaffWithPermission.class;
        }

        @Override
        public List<String> requires() {
            return List.of("lending.access.check");
        }

        @Override
        public VerifierResult verify(StaffWithPermission query, StaffRef candidate, VerifyContext ctx)
                throws AgentRpcException, AgentLostException {
            List<Object> calls = new ArrayList<>();
            String code = candidate.code();
            List<String> perform = query.perform() == null ? List.of() : query.perform();
            if (query.distinctFrom() != null && query.distinctFrom().contains(code)) {
                return VerifierResult.fail("staff " + code + " is one of " + query.distinctFrom(), evidence(calls));
            }
            LimitCondition limit = query.limit();
            String kind = limit == null ? "RETURN" : limit.kind();
            if (!"RETURN".equals(kind)) {
                return VerifierResult.fail("the desk reports RETURN limits only, not " + kind, evidence(calls));
            }
            Map<String, Object> first = null;
            for (String permission : perform) {
                Map<String, Object> answer = check(ctx, code, permission, null, calls);
                if (first == null) {
                    first = answer;
                }
                if (!Boolean.TRUE.equals(answer.get("allowed"))) {
                    return VerifierResult.fail("the desk refuses " + permission + " to staff " + code + ": "
                            + answer.get("reason"), evidence(calls));
                }
            }
            if (Boolean.TRUE.equals(query.interactiveSignOn())) {
                Map<String, Object> answer = first != null ? first : check(ctx, code, "SIGN_ON", null, calls);
                if (!Boolean.FALSE.equals(answer.get("autoSignOn"))) {
                    return VerifierResult.fail("the desk signs staff " + code + " on without a password",
                            evidence(calls));
                }
            }
            String limitPermission = perform.contains("RETURN") || !perform.contains("RETURN_OVERRIDE") ? "RETURN"
                    : "RETURN_OVERRIDE";
            Map<String, Object> limitAnswer = check(ctx, code, limitPermission, null, calls);
            BigDecimal reported = LendingResolvers.decimal(limitAnswer.get("limit"));
            BigDecimal claimed = candidate.limits() == null ? null : candidate.limits().get(kind);
            if (!sameAmount(reported, claimed)) {
                return VerifierResult.fail("the desk reports a RETURN limit of " + text(reported) + " for staff "
                        + code + ", not " + text(claimed), evidence(calls));
            }
            if (limit != null && !limit.test(reported)) {
                return VerifierResult.fail("the desk's RETURN limit " + text(reported) + " for staff " + code
                        + " does not satisfy " + bounds(limit), evidence(calls));
            }
            List<String> probes = new ArrayList<>();
            if (limit != null) {
                for (String permission : AMOUNT_PERMISSIONS) {
                    if (!perform.contains(permission)) {
                        continue;
                    }
                    for (BigDecimal lower : nonNull(limit.gte(), limit.gt())) {
                        Map<String, Object> answer = check(ctx, code, permission, lower, calls);
                        if (!Boolean.TRUE.equals(answer.get("allowed"))) {
                            return VerifierResult.fail("the desk refuses " + permission + " of " + text(lower)
                                    + " to staff " + code + ": " + answer.get("reason"), evidence(calls));
                        }
                        probes.add(permission + " of " + text(lower) + " allowed");
                    }
                    if (limit.lt() != null) {
                        Map<String, Object> answer = check(ctx, code, permission, limit.lt(), calls);
                        if (Boolean.TRUE.equals(answer.get("allowed"))) {
                            return VerifierResult.fail("the desk allows " + permission + " of " + text(limit.lt())
                                    + " to staff " + code + ", which the query needs refused", evidence(calls));
                        }
                        probes.add(permission + " of " + text(limit.lt()) + " refused");
                    }
                }
            }
            StringBuilder reason = new StringBuilder();
            reason.append(perform.isEmpty() ? "staff " + code + " is known" : String.join(" and ", perform)
                    + " permitted");
            for (String probe : probes) {
                reason.append("; ").append(probe);
            }
            reason.append("; limit ").append(text(reported));
            if (Boolean.TRUE.equals(query.interactiveSignOn())) {
                reason.append("; asked for a password");
            }
            return VerifierResult.pass(reason.toString(), evidence(calls));
        }

        private static Map<String, Object> check(VerifyContext ctx, String code, String permission, BigDecimal amount,
                                                 List<Object> calls) throws AgentRpcException, AgentLostException {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("staff", code);
            params.put("permission", permission);
            if (amount != null) {
                params.put("amount", text(amount));
            }
            return call(ctx.agent(), "lending.access.check", params, calls);
        }

        private static List<BigDecimal> nonNull(BigDecimal... values) {
            List<BigDecimal> out = new ArrayList<>();
            for (BigDecimal value : values) {
                if (value != null) {
                    out.add(value);
                }
            }
            return out;
        }

        private static String bounds(LimitCondition limit) {
            List<String> parts = new ArrayList<>();
            if (limit.lt() != null) {
                parts.add("below " + text(limit.lt()));
            }
            if (limit.lte() != null) {
                parts.add("at most " + text(limit.lte()));
            }
            if (limit.gt() != null) {
                parts.add("above " + text(limit.gt()));
            }
            if (limit.gte() != null) {
                parts.add("at least " + text(limit.gte()));
            }
            return String.join(" and ", parts);
        }
    }

    /**
     * {@code swing.find}: every key action of the candidate names exactly one component of the running window
     * ({@code swing.find {by: {action: <id>}, all: true}}). The window is the application's own answer to "which key
     * does this".
     */
    static final class KeyVerifier implements Verifier<ActionKey, Map<String, String>> {

        @Override
        public String id() {
            return "swing.find";
        }

        @Override
        public Class<ActionKey> queryClass() {
            return ActionKey.class;
        }

        @Override
        public List<String> requires() {
            return List.of("swing.tree");
        }

        @Override
        public VerifierResult verify(ActionKey query, Map<String, String> candidate, VerifyContext ctx)
                throws AgentRpcException, AgentLostException {
            List<Object> calls = new ArrayList<>();
            List<String> wanted = query.actions() == null ? List.of() : query.actions();
            if (!candidate.keySet().containsAll(wanted)) {
                return VerifierResult.fail("the keys " + candidate.keySet() + " do not cover " + wanted,
                        evidence(calls));
            }
            for (String id : new TreeSet<>(new TreeMap<>(candidate).values())) {
                Map<String, Object> answer = call(ctx.agent(), "swing.find", Map.of("by", Map.of("action", id),
                        "all", true), calls);
                int matches = answer.get("matches") instanceof List<?> list ? list.size() : 0;
                if (matches != 1) {
                    return VerifierResult.fail("the window has " + matches + " keys with action " + id
                            + ", not exactly one", evidence(calls));
                }
            }
            return VerifierResult.pass("the window has one key for each of " + new TreeSet<>(candidate.values()),
                    evidence(calls));
        }
    }
}
