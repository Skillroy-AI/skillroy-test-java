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

import ai.skillroy.testjava.scenario.Schemas;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schemas of the lending verbs' arguments ({@code StepLibrary.argsSchema}, phase5-contracts.md 9.5, 14.1):
 * what the tools a model is offered take as input, and what the scenario schema a planner writes against says about
 * each verb.
 *
 * <p>Each schema is an object with {@code additionalProperties: false}, the keys the verb accepts, the ones it
 * requires, a description of what the verb does at the desk, and per key what it means. A key that takes a reference
 * is typed as the referenced value's type with "or a $reference" in its description (a reference is a string that
 * starts with {@code $} and a letter or {@code _}); {@code staff}, which takes nothing but a reference, is a string
 * that must be one. Everything stays within the subset of JSON Schema that {@code ScenarioSchema.validate} reads and a
 * provider's structured outputs accept: no numeric bounds, no lengths, and no {@code $ref}, since a verb's schema
 * stands alone as a tool's input schema; what only the verb checks (a count from 1 to 99, a wait of at most 119 s,
 * exactly one of {@code item} and {@code barcode}) is said in the description. A verb whose arguments may be left
 * out takes an empty mapping ({@code deposit: {}}); the YAML form with no value at all, which the verb also accepts,
 * is not a tool input and is left out of the schema.
 *
 * <p>Information only, as the contract says: {@link LendingSteps#compile} stays the authority on what compiles.
 *
 * <p>Stateless; every call builds new, mutable maps with a fixed key order.
 */
final class LendingStepSchemas {

    /** A {@code $slot} reference and nothing more: what {@code signOn} and {@code authorise} take as {@code staff}. */
    static final String SLOT_REFERENCE = "^\\$[A-Za-z_][A-Za-z0-9_]*$";
    /** A reference into a slot's field, such as {@code $keys.RETURN}: what {@code key} takes as a reference. */
    static final String FIELD_REFERENCE = "^\\$[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z0-9_]+$";
    /** A duration as the scenario grammar writes it ({@code "10s"}, {@code "500ms"}, {@code "2m"}, digits). */
    static final String DURATION = "^\\s*(\\d{1,15}|\\d{1,12}\\s*([Mm][Ss]|[Ss]|[Mm]))\\s*$";

    private LendingStepSchemas() {
    }

    /** The schema of {@code verb}'s arguments, or null for a verb that is not the pack's. */
    static Map<String, Object> of(String verb) {
        if (verb == null) {
            return null;
        }
        return switch (verb) {
            case "signOn" -> object("Signs a staff member on at the desk: enters their code, then their password,"
                    + " which comes from credentials.yaml and never from the scenario, each time waiting until the"
                    + " desk is ready again. A scenario that passes signs off again at its end.",
                    props("staff", staff("The staff member to sign on")), List.of("staff"));
            case "signOff" -> object("Signs the signed-on staff member off (the SIGN_OFF key) and waits until the"
                    + " desk is ready again. Takes no arguments: write signOff: {}.", props(), List.of());
            case "scan" -> object("Scans an item: one barcode per unit, each time waiting until the desk is ready"
                    + " again or asks a question (unknown-item, item-not-for-loan, induction-check,"
                    + " supervisor-required, ...; expect it with expectPrompt). Give exactly one of item and"
                    + " barcode.",
                    props("item", item(),
                            "barcode", described(type("string"), "The barcode to send, quoted, such as"
                                    + " \"5000000000017\", or a $reference to one, such as $item.barcode."),
                            "times", described(type("integer"), "How many units to scan, from 1 to 99; 1 when"
                                    + " left out.")),
                    List.of());
            case "key" -> object("Presses a key of the desk's keypad, with a value when one is given, and waits"
                    + " until the desk is ready again.",
                    props("action", action(),
                            "value", scalar("A value sent with the key, as if typed before pressing it: text, a"
                                    + " number, or a $reference.")),
                    List.of("action"));
            case "enter" -> object("Types a value into the desk's entry field and presses Enter, then waits until"
                    + " the desk is ready again: a staff code at sign-on, a barcode or an item code on a loan.",
                    props("value", scalar("The value to enter: text, a number, or a $reference, such as"
                            + " $assistant.code.")),
                    List.of("value"));
            case "quantity" -> object("Sets the quantity of the next item scanned (the QTY key with the quantity):"
                    + " units of an item counted in units, or days of an item lent by the day.",
                    props("n", described(anyOf(type("number"), reference()), "The quantity: a number of units or"
                            + " days (up to three decimals), or a $reference to one, such as"
                            + " $oracle.needed.qty.")),
                    List.of("n"));
            case "deposit" -> object("Takes the deposit (the DEPOSIT key) and waits until the desk is ready"
                    + " again: with an amount, the deposit received, which must be at least the amount due; without"
                    + " one, exactly the amount due. A return hands its deposit back and takes no amount: write"
                    + " deposit: {}.",
                    props("amount", money("The deposit received: money as a decimal string, such as \"10.00\","
                            + " or a number, or a $reference, such as $oracle.due.total.")),
                    List.of());
            case "expectPrompt" -> object("Waits until the desk asks a question (a prompt), matched by its id or"
                    + " its text when given, and counts the question as expected; the step fails when none comes in"
                    + " time. Answer it next with answerPrompt, or with authorise for supervisor-required.",
                    props("id", described(type("string"), "The prompt's id, such as supervisor-required,"
                                    + " unknown-item or induction-check; or a $reference to one."),
                            "text", described(type("string"), "The prompt's text, such as \"Unknown item\"; or a"
                                    + " $reference to one."),
                            "within", duration()),
                    List.of());
            case "answerPrompt" -> object("Answers the open prompt by pressing one of its buttons, then waits"
                    + " until the desk is ready again or asks its next question.",
                    props("choice", described(type("string"), "The button's label, such as OK, Cancel, Yes or No;"
                                    + " or a $reference to one."),
                            "id", described(type("string"), "Answer only if the open prompt has this id, such as"
                                    + " unknown-item; or a $reference to one.")),
                    List.of("choice"));
            case "authorise" -> object("A supervisor authorises what the desk asked for with the"
                    + " supervisor-required prompt: answers it with OK, then enters the supervisor's code and"
                    + " password, which comes from credentials.yaml, each time waiting until the desk is ready"
                    + " again. If the desk refuses, its authorisation-failed prompt fails the step.",
                    props("staff", staff("The supervisor who authorises")), List.of("staff"));
            // phase8-contracts.md 7.5, 7.6 (ES): the composites and the scanner device.
            case "signOnAny" -> object("Signs a staff member on whichever way the desk asks: an auto-sign-on"
                    + " member at once, a member with a password with the password from credentials.yaml (only when"
                    + " the slot declares a credential), in one call that reports the branch it took (auto,"
                    + " password, passwordRequired with the warning lending.PASSWORD_REQUIRED, refused). A scenario"
                    + " that passes signs off again at its end.",
                    props("staff", anySlot()), List.of("staff"));
            case "scanChecked" -> object("Scans an item and answers the desk's induction check itself when it asks"
                    + " one (Yes when inducted, No otherwise), then waits until the desk is ready again. Another"
                    + " question the desk asks stays open and fails the step unless expected. Give exactly one of"
                    + " item and barcode.",
                    props("item", item(),
                            "barcode", described(type("string"), "The barcode to send, quoted, or a $reference to"
                                    + " one."),
                            "inducted", described(type("boolean"), "Whether the borrower completed the safety"
                                    + " induction.")),
                    List.of("inducted"));
            case "deviceScan" -> object("Scans an item with the desk's barcode scanner device: through the scanner"
                    + " driver when the desk runs one (rung PHYSICAL), else straight into the desk (rung SEMANTIC,"
                    + " a lower fidelity that makes the run DEGRADED). A step requires the physical path with"
                    + " minRung: PHYSICAL. Give exactly one of item and barcode.",
                    props("item", item(),
                            "barcode", described(type("string"), "The barcode to send, quoted, or a $reference to"
                                    + " one.")),
                    List.of());
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------- the keys

    /** {@code staff}: a {@code $slot} reference to a slot with a credential, nothing else. */
    private static Map<String, Object> staff(String what) {
        Map<String, Object> schema = type("string");
        schema.put("pattern", SLOT_REFERENCE);
        return described(schema, what + ": a $reference to a slot that declares a credential (credential: {realm:"
                + " staff, key: code}), such as $assistant; never a staff member or a password written out.");
    }

    /** {@code signOnAny.staff}: a {@code $slot} reference, with or without a credential. */
    private static Map<String, Object> anySlot() {
        Map<String, Object> schema = type("string");
        schema.put("pattern", SLOT_REFERENCE);
        return described(schema, "The staff member to sign on: a $reference to a slot with their code, such as"
                + " $assistant; when the slot declares a credential (credential: {realm: staff, key: code}) the"
                + " password comes from credentials.yaml; never a password written out.");
    }

    /** {@code scan.item}: an item as the item queries answer it, with at least its barcode, or a reference. */
    private static Map<String, Object> item() {
        Map<String, Object> written = object(null, props(
                "code", described(type("string"), "The item's code."),
                "barcode", described(type("string"), "What scanning the item sends."),
                "title", described(type("string"), "The item's name as the slip prints it."),
                "deposit", money("The deposit for one unit or one day.")), List.of("barcode"));
        return described(anyOf(reference(), written), "The item to scan, whose barcode is sent: an item"
                + " ({code, barcode, title, deposit}, at least its barcode) or a $reference to an item slot, such as"
                + " $item.");
    }

    /** {@code key.action}: one of the keypad's actions, or a reference into an {@code ActionKey} slot. */
    private static Map<String, Object> action() {
        Map<String, Object> named = type("string");
        named.put("enum", new ArrayList<>(LendingPack.KEY_ACTIONS));
        Map<String, Object> referenced = type("string");
        referenced.put("pattern", FIELD_REFERENCE);
        return described(anyOf(named, referenced), "The key: DUE (the deposit due), DEPOSIT (the deposit"
                + " received), RETURN (return mode), CANCEL (cancel the open loan), SIGN_OFF, or QTY (the quantity"
                + " of the next item); or a $reference into an ActionKey slot, such as $keys.RETURN, which also"
                + " checks that the key is on the keypad.");
    }

    /** {@code expectPrompt.within}: a duration, at most 119 s. */
    private static Map<String, Object> duration() {
        Map<String, Object> text = type("string");
        text.put("pattern", DURATION);
        return described(anyOf(type("integer"), text), "How long to wait: a duration such as \"10s\" or"
                + " \"500ms\", or a number of milliseconds; at most 119 s; the step timeout when left out.");
    }

    /** Money as a scenario writes it, with the decimal schema of {@link Schemas#ofArguments} and its own words. */
    private static Map<String, Object> money(String description) {
        Map<String, Object> decimal = Schemas.ofArguments(BigDecimal.class);
        decimal.remove("description");
        return described(decimal, description);
    }

    /** Text, a number or a boolean, sent to the desk as text; a reference is text. */
    private static Map<String, Object> scalar(String description) {
        return described(anyOf(type("string"), type("number"), type("boolean")), description);
    }

    /** A reference: a string that starts with {@code $} and a letter or {@code _}. */
    private static Map<String, Object> reference() {
        Map<String, Object> schema = type("string");
        schema.put("pattern", Schemas.REFERENCE_PATTERN);
        return described(schema, "a $reference to a slot, an oracle or $env");
    }

    // ------------------------------------------------------------------------------------------- builders

    private static Map<String, Object> object(String description, Map<String, Object> properties,
                                              List<String> required) {
        Map<String, Object> schema = type("object");
        if (description != null) {
            schema.put("description", description);
        }
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", new ArrayList<>(required));
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            props.put((String) keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    private static Map<String, Object> type(String name) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", name);
        return schema;
    }

    @SafeVarargs
    private static Map<String, Object> anyOf(Map<String, Object>... alternatives) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("anyOf", new ArrayList<>(List.of(alternatives)));
        return schema;
    }

    private static Map<String, Object> described(Map<String, Object> schema, String description) {
        schema.put("description", description);
        return schema;
    }
}
