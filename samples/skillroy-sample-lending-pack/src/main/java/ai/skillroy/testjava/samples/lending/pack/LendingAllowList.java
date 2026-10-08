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

import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;

import java.util.List;

/**
 * What a model may read from the desk's database (phase5-contracts.md 5.2, 14.1): the SQL allow-list of source
 * {@value LendingResolvers#SOURCE}, dialect HSQLDB, over the twelve tables the pack's own statements and stored-row
 * checks read ({@link LendingFingerprinter#TABLES}), so that a statement a model writes for a tier (b) slot reads no
 * table the committed SQL does not.
 *
 * <ul>
 *   <li>Every column of those tables is named, with the type the schema declares: the readable ones with a one-line
 *       description for the prompt catalogue, and the secret column {@code staff.password} (the column
 *       {@link LendingTables#PASSWORD_MASK} masks) among the table's other columns, which no statement may read but
 *       which the guard must know to resolve every column reference.</li>
 *   <li>{@code staff.name} is personal: a statement may filter, join, group or order on it, never select it, and it is
 *       never sampled. Staff codes are not personal: a binding holds them (they are the staff members' ids).</li>
 *   <li>Sampled (their distinct values, masked, go into the catalogue): {@code role_permission.permission},
 *       {@code desk_setting.name} (the setting's key), {@code item.per_day} and {@code waiver_rule.kind}.</li>
 *   <li>Keys (the tie-breakers the guard completes an {@code ORDER BY} with): the primary key, and for the stored
 *       tables the keys of {@link LendingTables#ALL} ({@code loan_authorisation} has no primary key).</li>
 *   <li>No procedure, and no function beyond the guard's built-in set.</li>
 * </ul>
 *
 * <p>The descriptions are what a model reads about the desk, so they are written in the desk's own terms, and they
 * say how a value arrives: money and quantities as decimal text, which is how both data channels return a decimal.
 * A test proves the list against the embedded database: every column named exists, the named columns are exactly the
 * table's, each type is the schema's, and no readable column is secret.
 *
 * <p>Immutable; safe to share between threads.
 */
final class LendingAllowList {

    private static final String MONEY = "DECIMAL(10,2)";
    private static final String CODE = "VARCHAR(16)";

    /** The one list of the pack, built once. */
    static final SqlAllowList LIST = build();

    private LendingAllowList() {
    }

    private static SqlAllowList build() {
        return SqlAllowList.builder(LendingResolvers.SOURCE, SqlDialect.HSQLDB)
                .table("staff", List.of("code"), "The desk's staff members. A staff member signs on with their"
                                + " code and a password; what they may do comes from their roles (staff_role).",
                        List.of(column("code", CODE, "The staff member's code, which they type to sign on; what a"
                                        + " staff query answers with."),
                                personal("name", "VARCHAR(64)", "The staff member's full name."),
                                column("active", "BOOLEAN", "FALSE for a former staff member, whom the desk refuses"
                                        + " to sign on."),
                                column("auto_sign_on", "BOOLEAN", "TRUE when the desk signs the staff member on by"
                                        + " code alone, without asking for a password.")),
                        List.of(LendingTables.PASSWORD_COLUMN))
                .table("staff_role", List.of("staff_code", "role_id"), "The roles each staff member holds; a staff"
                                + " member may hold several.",
                        List.of(column("staff_code", CODE, "The staff member (staff.code)."),
                                column("role_id", "INTEGER", "The role (role_permission.role_id,"
                                        + " role_cap.role_id).")),
                        List.of())
                .table("role_permission", List.of("role_id", "permission"), "The actions each role permits. A staff"
                                + " member may perform an action when any of their roles permits it.",
                        List.of(column("role_id", "INTEGER", "The role (staff_role.role_id)."),
                                sampled("permission", "VARCHAR(32)", "An action the role permits: SIGN_ON (sign on"
                                        + " at the desk), RETURN (take returns, up to one's own limit),"
                                        + " RETURN_OVERRIDE (authorise another staff member's return above their"
                                        + " limit, up to one's own).")),
                        List.of())
                .table("role_cap", List.of("role_id", "kind"), "Each role's limit per kind of action. A staff"
                                + " member's limit of a kind is the highest amount over their roles' rows of that"
                                + " kind; a staff member none of whose roles has a row of the kind has no limit for"
                                + " it.",
                        List.of(column("role_id", "INTEGER", "The role (staff_role.role_id)."),
                                column("kind", "VARCHAR(16)", "What the limit applies to. RETURN, the only kind"
                                        + " the desk uses, caps the deposits a staff member may hand back on one"
                                        + " return without a supervisor."),
                                column("amount", MONEY, "The limit: money as a decimal string, such as"
                                        + " \"20.00\".")),
                        List.of())
                .table("desk_setting", List.of("name"), "The desk's settings, one row per setting.",
                        List.of(sampled("name", "VARCHAR(32)", "The setting's key: max_return is the largest return"
                                        + " the desk accepts at all, supervisor or not; slip_header is the first"
                                        + " line of every slip."),
                                column("value", "VARCHAR(128)", "The setting's value, as text; max_return is money"
                                        + " as a decimal string, such as \"500.00\".")),
                        List.of())
                .table("item", List.of("code"), "The tools the library lends: one row per item, with its deposit"
                                + " and whether and how it can be lent.",
                        List.of(column("code", CODE, "The item's code, which loan lines and waiver rules refer"
                                        + " to."),
                                column("barcode", "VARCHAR(32)", "What scanning the item sends; NULL for an item"
                                        + " that cannot be scanned."),
                                column("title", "VARCHAR(64)", "The item's name as the slip prints it."),
                                column("deposit", MONEY, "The deposit for one unit, or for one day of a per-day"
                                        + " item: money as a decimal string, such as \"34.99\"."),
                                column("active", "BOOLEAN", "FALSE for a retired item, which the desk refuses to"
                                        + " lend."),
                                column("induction_required", "BOOLEAN", "TRUE when lending the item first asks"
                                        + " whether the borrower has completed the safety induction."),
                                sampled("per_day", "BOOLEAN", "TRUE for an item lent by the day, whose days are"
                                        + " entered before it is scanned; FALSE for an item counted in units.")),
                        List.of())
                .table("waiver_rule", List.of("id"), "Deposit waiver rules: a rule waives part of the deposit when"
                                + " one loan holds enough units of the items it covers (waiver_rule_item).",
                        List.of(column("id", "INTEGER", "The rule's number; the desk applies its rules in this"
                                        + " order."),
                                column("code", "VARCHAR(32)", "The rule's code, which the slip prints and"
                                        + " loan_waiver.rule_code records."),
                                column("description", "VARCHAR(64)", "What the rule offers, in words."),
                                sampled("kind", "VARCHAR(32)", "How the rule works; BORROW_N_WAIVE_M is the only"
                                        + " kind the desk applies."),
                                column("borrow_qty", "INTEGER", "The units of each group whose deposit is paid in"
                                        + " full."),
                                column("waived_qty", "INTEGER", "The units of each group whose deposit is waived:"
                                        + " of every borrow_qty + waived_qty units of the rule's items lent by the"
                                        + " unit on one loan, the waived_qty lowest deposits are waived."),
                                column("active", "BOOLEAN", "FALSE for a rule that is switched off."),
                                column("starts_on", "DATE", "The first day the rule applies; NULL when it has"
                                        + " none."),
                                column("ends_on", "DATE", "The last day the rule applies; NULL when it has none.")),
                        List.of())
                .table("waiver_rule_item", List.of("rule_id", "item_code"), "The items each waiver rule covers.",
                        List.of(column("rule_id", "INTEGER", "The rule (waiver_rule.id)."),
                                column("item_code", CODE, "An item the rule covers (item.code).")),
                        List.of())
                .table("loan", List.of("id"), "Posted loans and returns: one row per transaction the desk"
                                + " recorded, with its total.",
                        List.of(column("id", "BIGINT", "The loan's number, from a sequence whose start differs"
                                        + " from desk to desk."),
                                column("staff_code", CODE, "The staff member signed on when it was posted"
                                        + " (staff.code)."),
                                column("posted_at", "TIMESTAMP", "When it was posted, by the desk's clock."),
                                column("total", MONEY, "The deposit due after waivers: money as a decimal string,"
                                        + " such as \"7.00\"; negative for a return, whose deposit is handed"
                                        + " back.")),
                        List.of())
                .table("loan_line", List.of("loan_id", "line_no"), "The lines of each posted loan: one per item,"
                                + " with its quantity and deposit.",
                        List.of(column("loan_id", "BIGINT", "The loan (loan.id)."),
                                column("line_no", "INTEGER", "The line's position in the loan, from 1."),
                                column("item_code", CODE, "The item (item.code)."),
                                column("qty", "DECIMAL(10,3)", "The units, or the days of a per-day item, as a"
                                        + " decimal string; positive, also on a return line."),
                                column("unit_deposit", MONEY, "The deposit per unit or per day when the line was"
                                        + " entered: money as a decimal string."),
                                column("amount", MONEY, "qty times unit_deposit: money as a decimal string;"
                                        + " negative on a return line."),
                                column("returned", "BOOLEAN", "TRUE for a return line, an item brought back.")),
                        List.of())
                .table("loan_waiver", List.of("loan_id", "rule_code"), "The waivers applied to each posted loan:"
                                + " one row per waiver rule that waived part of its deposit.",
                        List.of(column("loan_id", "BIGINT", "The loan (loan.id)."),
                                column("rule_code", "VARCHAR(32)", "The waiver rule (waiver_rule.code)."),
                                column("amount", MONEY, "The deposit waived: money as a decimal string, such as"
                                        + " \"0.99\".")),
                        List.of())
                .table("loan_authorisation", List.of("loan_id", "kind", "staff_code"), "Authorisations given"
                                + " while a loan was entered, such as a supervisor approving a return above the"
                                + " signed-on staff member's limit.",
                        List.of(column("loan_id", "BIGINT", "The loan (loan.id)."),
                                column("kind", "VARCHAR(32)", "What was authorised: RETURN_OVERRIDE for a return"
                                        + " above the signed-on staff member's limit."),
                                column("staff_code", CODE, "The staff member who authorised it (staff.code).")),
                        List.of())
                .build();
    }

    private static AllowedColumn column(String name, String type, String description) {
        return AllowedColumn.of(name, type).withDescription(description);
    }

    private static AllowedColumn personal(String name, String type, String description) {
        return AllowedColumn.personal(name, type).withDescription(description);
    }

    private static AllowedColumn sampled(String name, String type, String description) {
        return AllowedColumn.sampled(name, type).withDescription(description);
    }
}
