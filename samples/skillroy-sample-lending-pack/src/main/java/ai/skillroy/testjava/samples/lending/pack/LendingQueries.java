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

import ai.skillroy.testjava.scenario.resolve.NumberRange;
import ai.skillroy.testjava.scenario.resolve.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The lending pack's query kinds and their value types (phase2-contracts.md 13.3, phase3-contracts.md 10.3). A
 * scenario's {@code resolve} section asks one of these instead of naming test data; the pack's resolvers find
 * candidates and its verifiers ask the desk whether a candidate really answers. Money is {@code BigDecimal} at scale 2,
 * encoded as a decimal string.
 *
 * <p>Why records nested here: they are the pack's whole query vocabulary, read together, and the codec reads them by
 * reflection (so they are public). Every record is immutable and safe to share between threads.
 */
public final class LendingQueries {

    /** The only waiver rule kind the desk evaluates, and the default of {@link ActiveWaiverRuleWithTriggers#kind()}. */
    public static final String BORROW_N_WAIVE_M = "BORROW_N_WAIVE_M";

    private LendingQueries() {
    }

    /**
     * The system maximum of a kind the desk enforces ({@code desk_setting max_<type>}).
     *
     * @param type the kind; only {@code RETURN} is configured by the desk; null means {@code RETURN}
     */
    public record SystemLimit(String type) implements Query<BigDecimal> {
    }

    /**
     * An active, scannable item whose deposit satisfies a range; the cheapest that fits comes first.
     *
     * @param deposit the deposit condition, or null for any deposit
     * @param perDay whether the item is lent per day; null means false (a counted item)
     * @param inductionRequired whether lending the item asks for the induction check; null means either (gap 49,
     *     pack 0.6.0: a scenario can ask for an item that needs no induction, as {@link ItemsTriggering} can)
     */
    public record LendableItem(NumberRange deposit, Boolean perDay, Boolean inductionRequired)
            implements Query<ItemRef> {

        /** The query without an induction condition (either kind of item), as before pack 0.6.0. */
        public LendableItem(NumberRange deposit, Boolean perDay) {
            this(deposit, perDay, null);
        }
    }

    /**
     * An item as a scenario uses it: what to scan and what to expect.
     *
     * @param code the item code
     * @param barcode what a scan sends
     * @param title the title printed on the slip
     * @param deposit the unit deposit at scale 2
     */
    public record ItemRef(String code, String barcode, String title, BigDecimal deposit) {
    }

    /**
     * An active staff member who may perform every listed action, whose limit satisfies a condition.
     *
     * @param perform permissions the staff member must hold ({@code SIGN_ON}, {@code RETURN}, {@code RETURN_OVERRIDE})
     * @param limit a condition on the staff member's limit of a kind, or null
     * @param interactiveSignOn true: the desk must ask the staff member for a password at sign-on (the {@code signOn}
     *     verb enters one); null means no condition
     * @param distinctFrom staff codes the answer must not be, or null
     */
    public record StaffWithPermission(List<String> perform, LimitCondition limit, Boolean interactiveSignOn,
                                      List<String> distinctFrom) implements Query<StaffRef> {
    }

    /**
     * The staff member's limit of this kind must satisfy every bound given. An unlimited staff member satisfies
     * {@code gt} and {@code gte}, never {@code lt} or {@code lte}.
     *
     * @param type the limit kind; null means {@code RETURN}
     * @param lt strictly below, or null
     * @param lte at most, or null
     * @param gt strictly above, or null
     * @param gte at least, or null
     */
    public record LimitCondition(String type, BigDecimal lt, BigDecimal lte, BigDecimal gt, BigDecimal gte) {

        /** The limit kind, {@code RETURN} by default. */
        public String kind() {
            return type == null ? "RETURN" : type;
        }

        /** True if {@code limit} (null = unlimited) satisfies every bound. */
        public boolean test(BigDecimal limit) {
            if (limit == null) {
                return lt == null && lte == null;
            }
            return (lt == null || limit.compareTo(lt) < 0)
                    && (lte == null || limit.compareTo(lte) <= 0)
                    && (gt == null || limit.compareTo(gt) > 0)
                    && (gte == null || limit.compareTo(gte) >= 0);
        }
    }

    /**
     * A staff member as a scenario uses it. No name and no secret: a binding holds semantic ids only (Q10); the
     * password is joined from {@code credentials.yaml} in memory.
     *
     * @param code the staff code, the id of the staff member's credentials entry
     * @param permissions the permissions the staff member's roles grant, sorted
     * @param limits limit kind to the limit at scale 2; a null value means unlimited
     */
    public record StaffRef(String code, List<String> permissions, Map<String, BigDecimal> limits) {
    }

    /**
     * The keypad keys that perform logical actions: logical action to the key's action id ({@code swing.find} locator
     * {@code {action: <id>}}).
     *
     * @param actions the logical actions a scenario needs
     */
    public record ActionKey(List<String> actions) implements Query<Map<String, String>> {
    }

    /**
     * An effective waiver rule of a kind, with the items it covers: active, inside its date window on the desk's
     * business date (which the resolver reads from the desk at bind time; a scenario never writes a date), and covering
     * at least one item the desk can lend. Candidates come in rule-id order.
     *
     * @param kind the rule kind; null means {@code BORROW_N_WAIVE_M}
     * @param borrowQty the units of a group that are paid in full, or null for any
     * @param waivedQty the units of a group whose deposit is waived, or null for any
     * @param exclusiveItems true: no other effective rule of the kind covers any of the rule's items, so that a loan of
     *     them triggers this rule alone; null means false
     */
    public record ActiveWaiverRuleWithTriggers(String kind, Integer borrowQty, Integer waivedQty,
                                               Boolean exclusiveItems) implements Query<WaiverRuleRef> {

        /** The kind, {@code BORROW_N_WAIVE_M} when the query leaves it open. */
        public String kindOrDefault() {
            return kind == null ? BORROW_N_WAIVE_M : kind;
        }
    }

    /**
     * A waiver rule as a scenario uses it. The rule's id is not part of it: it decides the order of candidates, not
     * what a scenario needs.
     *
     * @param code the rule code the desk writes into {@code loan_waiver} and prints on the slip
     * @param kind the rule kind
     * @param borrowQty the units of a group that are paid in full
     * @param waivedQty the units of a group whose deposit is waived
     * @param items every item the rule covers that is active and scannable (has a barcode), sorted by deposit and then
     *     code; an item the desk cannot lend is left out
     */
    public record WaiverRuleRef(String code, String kind, int borrowQty, int waivedQty, List<WaiverItem> items) {
    }

    /**
     * An item a waiver rule covers, with what a scan needs and the flags that decide whether a scenario can lend it
     * plainly.
     *
     * @param code the item code
     * @param barcode what a scan sends
     * @param title the title printed on the slip
     * @param deposit the unit deposit at scale 2 (per day for a per-day item)
     * @param perDay true for an item lent by the day, which the desk's waiver rules ignore
     * @param inductionRequired true when lending it asks for the induction check
     */
    public record WaiverItem(String code, String barcode, String title, BigDecimal deposit, boolean perDay,
                             boolean inductionRequired) {
    }

    /**
     * The items of a resolved waiver rule that a scenario can scan without a further prompt, cheapest first.
     *
     * @param rule the rule, a {@code $slot} reference to an {@link ActiveWaiverRuleWithTriggers} slot
     * @param perDay whether the item is lent per day; null means false (a counted item)
     * @param inductionRequired whether lending the item asks for the induction check; null means false
     */
    public record ItemsTriggering(WaiverRuleRef rule, Boolean perDay, Boolean inductionRequired)
            implements Query<ItemRef> {
    }

    /**
     * One planned loan line, as the waiver oracles and the desk's dry run read it: a counted line, never a return.
     *
     * @param code the item code
     * @param qty the units
     * @param unitDeposit the unit deposit at scale 2
     */
    public record LoanLineRef(String code, long qty, BigDecimal unitDeposit) {
    }
}
