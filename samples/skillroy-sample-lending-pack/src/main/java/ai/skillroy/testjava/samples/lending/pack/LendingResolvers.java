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
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.protocol.sql.Rows;
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
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.NumberRange;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.Strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The lending pack's tier (a) resolvers (phase2-contracts.md 13.3, phase3-contracts.md 10.3): committed SQL against
 * the desk's schema, read through whichever channel serves source {@value #SOURCE} (the agent's {@code data.query} or
 * the controller-side JDBC channel), and committed Java resolvers. Every statement is a constant with {@code ?}
 * parameters in the forms of section 7.1 (decimals as plain strings, cast in the statement) and ends in an
 * {@code ORDER BY} that determines the order completely, so the same data always gives the same candidates in the
 * same order. No statement selects a secret column, and no parameter is a date, which would change every day.
 *
 * <p>Stateless; one instance of each serves every bind in the JVM, called on the executor's thread.
 */
final class LendingResolvers {

    /** The data source id of the desk's database. */
    static final String SOURCE = "lending";

    /** The configured system maximum of a kind. Parameter: {@code max_<type>}. */
    static final String SYSTEM_LIMIT_SQL = "SELECT value FROM desk_setting WHERE name = ? ORDER BY name";

    /** Active scannable items in a deposit range, cheapest first. Parameters: perDay, lower bound, upper bound. */
    static final String LENDABLE_ITEM_SQL = "SELECT code, barcode, title, deposit FROM item"
            + " WHERE active = TRUE AND barcode IS NOT NULL AND per_day = ?"
            + " AND deposit >= CAST(? AS DECIMAL(10,2)) AND deposit <= CAST(? AS DECIMAL(10,2))"
            + " ORDER BY deposit, code";

    /**
     * Every active staff member with the auto-sign-on flag and the maximum limit of a kind over their roles (null =
     * unlimited), by code. Parameter: the limit kind.
     */
    static final String STAFF_SQL = "SELECT s.code AS code, s.auto_sign_on AS auto_sign_on,"
            + " (SELECT MAX(rl.amount) FROM staff_role sr JOIN role_cap rl ON rl.role_id = sr.role_id"
            + " WHERE sr.staff_code = s.code AND rl.kind = ?) AS limit_amount"
            + " FROM staff s WHERE s.active = TRUE ORDER BY s.code";

    /** One staff member's permissions, the union over their roles. Parameter: the staff code. */
    static final String PERMISSIONS_SQL = "SELECT DISTINCT rp.permission AS permission"
            + " FROM staff_role sr JOIN role_permission rp ON rp.role_id = sr.role_id"
            + " WHERE sr.staff_code = ? ORDER BY permission";

    /**
     * The active waiver rules of a kind with their date windows, each with the items it covers that the desk can lend
     * (phase3-contracts.md 10.3): by rule id, and within a rule cheapest first. Parameter: the kind. The window is
     * applied in Java ({@link WaiverRuleResolver}), so that no statement parameter is a date: a bindings file records
     * its statements' parameters and must not change from day to day. A rule without such an item has no row.
     */
    static final String WAIVER_RULE_SQL = "SELECT r.id, r.code, r.kind, r.borrow_qty, r.waived_qty, r.starts_on,"
            + " r.ends_on, i.code, i.barcode, i.title, i.deposit, i.per_day, i.induction_required"
            + " FROM waiver_rule r JOIN waiver_rule_item ri ON ri.rule_id = r.id"
            + " JOIN item i ON i.code = ri.item_code"
            + " WHERE r.active = TRUE AND r.kind = ? AND i.active = TRUE AND i.barcode IS NOT NULL"
            + " ORDER BY r.id, i.deposit, i.code";

    /** Lower and upper bound of a deposit when the query leaves them open. */
    static final String NO_LOWER = "0";
    static final String NO_UPPER = "99999999.99";

    /** The resolution reason of a desk that reports no business date. */
    static final String NO_DATE = "NO_DATE";

    private LendingResolvers() {
    }

    /** Money as the pack holds it: scale 2, half up. */
    static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** A decimal read from a column (a plain decimal string or a number), or null if it is not one. */
    static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A whole number read from a column that fits an int, or null if it is not one. */
    static Integer whole(Object value) {
        BigDecimal d = decimal(value);
        if (d == null) {
            return null;
        }
        try {
            return d.intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * The desk's business date: {@code app.state}, section {@code lending}, field {@code today}, read through the
     * resolver's agent view, which records the call (and not its answer) in the provenance (phase3-contracts.md 3.3).
     * A scenario never writes the date: in a query's arguments it would change the arguments' hash every day.
     *
     * @throws ResolutionException reason {@value #NO_DATE} when the desk reports none, or not as an ISO-8601 date
     */
    static LocalDate businessDate(AgentConnection agent)
            throws ResolutionException, AgentRpcException, AgentLostException {
        Object state = agent.call("app.state", Map.of()).value();
        Object today = state instanceof Map<?, ?> m && m.get(LendingPack.ID) instanceof Map<?, ?> lending
                ? lending.get("today") : null;
        if (!(today instanceof String text)) {
            throw new ResolutionException(NO_DATE, null, "the desk reports no business date (app.state "
                    + LendingPack.ID + ".today), so whether a waiver rule is effective cannot be decided");
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new ResolutionException(NO_DATE, null, "the desk reports a business date that is not an ISO-8601"
                    + " date (app.state " + LendingPack.ID + ".today): " + text);
        }
    }

    /** {@code lending.sql.SystemLimit}: the {@code desk_setting} row {@code max_<type>}. */
    static final class SystemLimitResolver implements Resolver<SystemLimit, BigDecimal> {

        @Override
        public String id() {
            return "lending.sql.SystemLimit";
        }

        @Override
        public Class<SystemLimit> queryClass() {
            return SystemLimit.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_SQL;
        }

        @Override
        public List<ResolverCandidate<BigDecimal>> candidates(SystemLimit query, ResolveContext ctx)
                throws DataException, AgentLostException, InterruptedException {
            String type = query.type() == null ? "RETURN" : query.type();
            String name = "max_" + type.toLowerCase(Locale.ROOT);
            Rows rows = ctx.data().query(SOURCE, SYSTEM_LIMIT_SQL, List.of(name), DataChannel.MAX_ROWS, null);
            if (rows.rowCount() != 1) {
                return List.of();
            }
            BigDecimal value = decimal(rows.get(0, 0));
            if (value == null) {
                return List.of();
            }
            BigDecimal max = money(value);
            return List.of(ResolverCandidate.of(max, name + " " + max.toPlainString()));
        }
    }

    /**
     * {@code lending.sql.LendableItem}: active scannable items in the range, cheapest first. The read and the building
     * of an {@code ItemRef} ({@link #lendable}, {@link #itemRef}) are shared with the tier (b) value loader
     * {@link LendingLoaders.ItemLoader} (decision D90).
     */
    static final class LendableItemResolver implements Resolver<LendableItem, ItemRef> {

        @Override
        public String id() {
            return "lending.sql.LendableItem";
        }

        @Override
        public Class<LendableItem> queryClass() {
            return LendableItem.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_SQL;
        }

        @Override
        public List<ResolverCandidate<ItemRef>> candidates(LendableItem query, ResolveContext ctx)
                throws DataException, AgentLostException, InterruptedException {
            Rows rows = lendable(query, ctx);
            List<ResolverCandidate<ItemRef>> out = new ArrayList<>();
            for (int r = 0; r < rows.rowCount(); r++) {
                ItemRef item = itemRef(query, rows, r);
                if (item != null) {
                    out.add(ResolverCandidate.of(item, "item " + item.code()));
                }
            }
            return out;
        }

        /**
         * The query's read ({@link #LENDABLE_ITEM_SQL}): the active scannable items lent the way the query asks
         * whose deposit lies within the range's outer bounds, cheapest first. Shared with the tier (b) value loader
         * ({@link LendingLoaders.ItemLoader}).
         */
        static Rows lendable(LendableItem query, ResolveContext ctx)
                throws DataException, AgentLostException, InterruptedException {
            NumberRange range = query.deposit();
            BigDecimal lower = range == null ? null : range.lowerBound();
            BigDecimal upper = range == null ? null : range.upperBound();
            List<Object> params = new ArrayList<>();
            params.add(Boolean.TRUE.equals(query.perDay()));
            params.add(lower == null ? NO_LOWER : lower.toPlainString());
            params.add(upper == null ? NO_UPPER : upper.toPlainString());
            return ctx.data().query(SOURCE, LENDABLE_ITEM_SQL, params, DataChannel.MAX_ROWS, null);
        }

        /**
         * The item of row {@code r} of {@link #lendable}, or null when its deposit is not a decimal or does not
         * satisfy the range exactly (the statement narrows by the outer bounds; strict bounds and every other
         * condition of the range are applied here).
         */
        static ItemRef itemRef(LendableItem query, Rows rows, int r) {
            NumberRange range = query.deposit();
            BigDecimal deposit = decimal(rows.get(r, 3));
            if (deposit == null || range != null && !range.test(deposit)) {
                return null;
            }
            return new ItemRef(String.valueOf(rows.get(r, 0)), String.valueOf(rows.get(r, 1)),
                    String.valueOf(rows.get(r, 2)), money(deposit));
        }
    }

    /**
     * {@code lending.sql.StaffWithPermission}: one statement over the active staff, the conditions applied in Java in
     * code order (so that "unlimited" is decided in one reviewed place), then one small statement per staff member
     * still in question for their permissions. Staff without a credentials entry are skipped before their
     * permissions are read, when the slot declares a credential keyed by the code ("credentials early").
     *
     * <p>One exception keeps the failure honest: when no staff member with an entry qualifies but some were skipped
     * only for want of one, those are offered after all (in code order, permissions checked), so that the binder
     * drops them and reports why ({@code ALL_REJECTED}, "no credential staff/&lt;id&gt;", phase2-contracts.md 4) rather
     * than claiming that nobody in the desk qualifies ({@code NO_CANDIDATE}). A bind that succeeds reads exactly what
     * it read without this rule.
     *
     * <p>The two reads and the building of a {@code StaffRef} ({@link #activeStaff}, {@link #staffRef}) are shared
     * with the tier (b) value loader {@link LendingLoaders.StaffLoader} (decision D90), so that a staff member a model
     * found is loaded exactly as this resolver loads one.
     */
    static final class StaffResolver implements Resolver<StaffWithPermission, StaffRef> {

        @Override
        public String id() {
            return "lending.sql.StaffWithPermission";
        }

        @Override
        public Class<StaffWithPermission> queryClass() {
            return StaffWithPermission.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_SQL;
        }

        @Override
        public List<ResolverCandidate<StaffRef>> candidates(StaffWithPermission query, ResolveContext ctx)
                throws ResolutionException, DataException, AgentLostException, InterruptedException {
            LimitCondition limit = query.limit();
            String kind = limit == null ? "RETURN" : limit.kind();
            List<String> perform = query.perform() == null ? List.of() : query.perform();
            List<String> distinct = query.distinctFrom() == null ? List.of() : query.distinctFrom();
            CredentialSpec credential = ctx.credential();
            boolean skipEarly = credential != null && "code".equals(credential.key());
            Rows staff = activeStaff(kind, ctx);
            List<ResolverCandidate<StaffRef>> out = new ArrayList<>();
            List<Integer> withoutCredential = new ArrayList<>();
            for (int r = 0; r < staff.rowCount() && out.size() < ctx.maxCandidates(); r++) {
                String code = String.valueOf(staff.get(r, 0));
                BigDecimal amount = money(decimal(staff.get(r, 2)));
                if (limit != null && !limit.test(amount)) {
                    continue;
                }
                if (Boolean.TRUE.equals(query.interactiveSignOn()) && Boolean.TRUE.equals(staff.get(r, 1))) {
                    continue;
                }
                if (distinct.contains(code)) {
                    continue;
                }
                if (skipEarly && !ctx.credentials().has(credential.realm(), code)) {
                    withoutCredential.add(r);
                    continue;
                }
                addIfPermitted(staff, r, kind, perform, ctx, out);
            }
            if (out.isEmpty()) {
                for (int i = 0; i < withoutCredential.size() && out.size() < ctx.maxCandidates(); i++) {
                    addIfPermitted(staff, withoutCredential.get(i), kind, perform, ctx, out);
                }
            }
            return out;
        }

        /** Reads one staff member's value and adds the candidate if their permissions cover {@code perform}. */
        private static void addIfPermitted(Rows staff, int r, String kind, List<String> perform, ResolveContext ctx,
                                           List<ResolverCandidate<StaffRef>> out)
                throws ResolutionException, DataException, AgentLostException, InterruptedException {
            StaffRef value = staffRef(staff, r, kind, ctx);
            if (value.permissions().containsAll(perform)) {
                out.add(ResolverCandidate.of(value, "staff " + value.code()));
            }
        }

        /**
         * Every active staff member with the auto-sign-on flag and their limit of {@code kind} ({@link #STAFF_SQL},
         * by code): the first read of the resolver, and of the tier (b) value loader
         * ({@link LendingLoaders.StaffLoader}), so that both see a staff member the same way.
         */
        static Rows activeStaff(String kind, ResolveContext ctx)
                throws DataException, AgentLostException, InterruptedException {
            return ctx.data().query(SOURCE, STAFF_SQL, List.of(kind), DataChannel.MAX_ROWS, null);
        }

        /**
         * The value of the staff member in row {@code r} of {@link #activeStaff}: the code, the permissions their roles
         * grant ({@link #PERMISSIONS_SQL}, read whole, sorted) and their limit of {@code kind} at scale 2 (null =
         * unlimited). The one place a {@code StaffRef} is built from the database, for the resolver and the tier (b)
         * value loader alike (decision D90).
         */
        static StaffRef staffRef(Rows staff, int r, String kind, ResolveContext ctx)
                throws ResolutionException, DataException, AgentLostException, InterruptedException {
            String code = String.valueOf(staff.get(r, 0));
            BigDecimal amount = money(decimal(staff.get(r, 2)));
            Rows permissionRows = ctx.requireComplete(ctx.data().query(SOURCE, PERMISSIONS_SQL, List.of(code),
                    DataChannel.MAX_ROWS, null));
            TreeSet<String> permissions = new TreeSet<>();
            for (int p = 0; p < permissionRows.rowCount(); p++) {
                permissions.add(String.valueOf(permissionRows.get(p, 0)));
            }
            Map<String, BigDecimal> limits = Collections.singletonMap(kind, amount);
            return new StaffRef(code, List.copyOf(permissions), limits);
        }
    }

    /**
     * {@code lending.java.ActionKey}: the pack knows the desk's keypad (its key actions are named after the logical
     * actions), so the answer is the identity map, provided every requested action is a key action. The verifier
     * proves it against the running window.
     */
    static final class ActionKeyResolver implements Resolver<ActionKey, Map<String, String>> {

        @Override
        public String id() {
            return "lending.java.ActionKey";
        }

        @Override
        public Class<ActionKey> queryClass() {
            return ActionKey.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_JAVA;
        }

        @Override
        public List<ResolverCandidate<Map<String, String>>> candidates(ActionKey query, ResolveContext ctx) {
            List<String> actions = query.actions();
            if (actions == null || actions.isEmpty()) {
                return List.of();
            }
            Map<String, String> keys = new LinkedHashMap<>();
            for (String action : actions) {
                if (!LendingPack.KEY_ACTIONS.contains(action)) {
                    return List.of();
                }
                keys.put(action, action);
            }
            return List.of(ResolverCandidate.of(keys, "keys " + String.join(", ", keys.keySet())));
        }
    }

    /**
     * {@code lending.sql.ActiveWaiverRuleWithTriggers} (phase3-contracts.md 10.3): first the desk's business date from
     * {@code app.state} ({@link #businessDate}), then {@link #WAIVER_RULE_SQL} with the kind, read whole
     * ({@code requireComplete}: a cut result would cut a rule's items). The rows are grouped by rule id into one
     * rule each, its items in the statement's order (cheapest first), and a rule is kept when the business date lies in
     * its window: {@code starts_on <= today <= ends_on}, a null bound open, the desk's own rule
     * ({@code WaiverRule.effectiveOn}). Why in Java and not in the statement: the date then appears in no parameter,
     * so a bindings file, which records the statement and its parameters, is the same on every day.
     *
     * <p>With {@code exclusiveItems: true}, an effective rule that shares an item with another effective rule of the
     * kind is dropped, and so is the other, so that a loan of a kept rule's items triggers that rule alone. This is
     * decided before {@code borrowQty} and {@code waivedQty} narrow the result, because a rule of other quantities
     * would still waive at the desk. Candidates in rule-id order.
     */
    static final class WaiverRuleResolver implements Resolver<ActiveWaiverRuleWithTriggers, WaiverRuleRef> {

        @Override
        public String id() {
            return "lending.sql.ActiveWaiverRuleWithTriggers";
        }

        @Override
        public Class<ActiveWaiverRuleWithTriggers> queryClass() {
            return ActiveWaiverRuleWithTriggers.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_SQL;
        }

        @Override
        public List<ResolverCandidate<WaiverRuleRef>> candidates(ActiveWaiverRuleWithTriggers query,
                                                                 ResolveContext ctx)
                throws ResolutionException, DataException, AgentRpcException, AgentLostException,
                InterruptedException {
            LocalDate today = businessDate(ctx.agent());
            Rows rows = ctx.requireComplete(ctx.data().query(SOURCE, WAIVER_RULE_SQL, List.of(query.kindOrDefault()),
                    DataChannel.MAX_ROWS, null));
            List<WaiverRuleRef> rules = rules(rows, today);
            if (Boolean.TRUE.equals(query.exclusiveItems())) {
                rules = exclusive(rules);
            }
            List<ResolverCandidate<WaiverRuleRef>> out = new ArrayList<>();
            for (WaiverRuleRef rule : rules) {
                if (query.borrowQty() != null && query.borrowQty() != rule.borrowQty()
                        || query.waivedQty() != null && query.waivedQty() != rule.waivedQty()) {
                    continue;
                }
                out.add(ResolverCandidate.of(rule, "rule " + rule.code()));
            }
            return out;
        }

        /**
         * The rules of the rows (one run of rows per rule id) that are effective on {@code today}, with the items of
         * each in row order. A rule whose quantities are not whole numbers, or one of whose window bounds is not an
         * ISO-8601 date, is skipped, as is an item whose deposit is not a decimal.
         */
        static List<WaiverRuleRef> rules(Rows rows, LocalDate today) {
            List<WaiverRuleRef> rules = new ArrayList<>();
            String id = null;
            Head head = null;
            List<WaiverItem> items = new ArrayList<>();
            for (int r = 0; r < rows.rowCount(); r++) {
                String ruleId = String.valueOf(rows.get(r, 0));
                if (head == null || !ruleId.equals(id)) {
                    add(rules, head, items);
                    id = ruleId;
                    head = new Head(String.valueOf(rows.get(r, 1)), String.valueOf(rows.get(r, 2)),
                            whole(rows.get(r, 3)), whole(rows.get(r, 4)), effective(rows.get(r, 5),
                            rows.get(r, 6), today));
                    items = new ArrayList<>();
                }
                BigDecimal deposit = decimal(rows.get(r, 10));
                if (deposit == null) {
                    continue;
                }
                items.add(new WaiverItem(String.valueOf(rows.get(r, 7)), String.valueOf(rows.get(r, 8)),
                        String.valueOf(rows.get(r, 9)), money(deposit), Boolean.TRUE.equals(rows.get(r, 11)),
                        Boolean.TRUE.equals(rows.get(r, 12))));
            }
            add(rules, head, items);
            return rules;
        }

        /**
         * Whether {@code today} lies in the window {@code [startsOn, endsOn]}, a null bound open: exactly the desk's
         * {@code WaiverRule.effectiveOn} (the active flag is in the statement). The bounds arrive as the data channels
         * convert a date, ISO-8601 text; a bound that is not one makes the rule no candidate.
         */
        static boolean effective(Object startsOn, Object endsOn, LocalDate today) {
            try {
                LocalDate starts = startsOn == null ? null : LocalDate.parse(String.valueOf(startsOn));
                LocalDate ends = endsOn == null ? null : LocalDate.parse(String.valueOf(endsOn));
                return (starts == null || !today.isBefore(starts)) && (ends == null || !today.isAfter(ends));
            } catch (DateTimeParseException e) {
                return false;
            }
        }

        /**
         * The rule columns of a run of rows; a quantity that is not a whole number is null.
         *
         * @param effective whether the business date lies in the rule's window
         */
        private record Head(String code, String kind, Integer borrowQty, Integer waivedQty, boolean effective) {
        }

        private static void add(List<WaiverRuleRef> rules, Head head, List<WaiverItem> items) {
            if (head == null || items.isEmpty() || head.borrowQty() == null || head.waivedQty() == null
                    || !head.effective()) {
                return;
            }
            rules.add(new WaiverRuleRef(head.code(), head.kind(), head.borrowQty(), head.waivedQty(),
                    List.copyOf(items)));
        }

        /** The rules none of whose items another of the rules covers. */
        static List<WaiverRuleRef> exclusive(List<WaiverRuleRef> rules) {
            Map<String, Integer> covering = new HashMap<>();
            for (WaiverRuleRef rule : rules) {
                for (String code : codes(rule)) {
                    covering.merge(code, 1, Integer::sum);
                }
            }
            List<WaiverRuleRef> out = new ArrayList<>();
            for (WaiverRuleRef rule : rules) {
                if (codes(rule).stream().allMatch(code -> covering.get(code) == 1)) {
                    out.add(rule);
                }
            }
            return out;
        }

        private static Set<String> codes(WaiverRuleRef rule) {
            Set<String> codes = new LinkedHashSet<>();
            rule.items().forEach(item -> codes.add(item.code()));
            return codes;
        }
    }

    /**
     * {@code lending.java.ItemsTriggering} (phase3-contracts.md 10.3): committed Java over the rule's own value, which
     * already holds everything a scan needs, so no row is read. The candidates are the rule's items lent the way the
     * query asks (per day or counted, with or without the induction check; by default a plain counted tool), in the
     * rule's order, cheapest first.
     */
    static final class ItemsTriggeringResolver implements Resolver<ItemsTriggering, ItemRef> {

        @Override
        public String id() {
            return "lending.java.ItemsTriggering";
        }

        @Override
        public Class<ItemsTriggering> queryClass() {
            return ItemsTriggering.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_JAVA;
        }

        @Override
        public List<ResolverCandidate<ItemRef>> candidates(ItemsTriggering query, ResolveContext ctx) {
            WaiverRuleRef rule = query.rule();
            if (rule == null || rule.items() == null) {
                return List.of();
            }
            boolean perDay = Boolean.TRUE.equals(query.perDay());
            boolean induction = Boolean.TRUE.equals(query.inductionRequired());
            List<ResolverCandidate<ItemRef>> out = new ArrayList<>();
            for (WaiverItem item : rule.items()) {
                if (item.perDay() == perDay && item.inductionRequired() == induction) {
                    out.add(ResolverCandidate.of(new ItemRef(item.code(), item.barcode(), item.title(),
                            item.deposit()), "item " + item.code()));
                }
            }
            return out;
        }
    }
}
