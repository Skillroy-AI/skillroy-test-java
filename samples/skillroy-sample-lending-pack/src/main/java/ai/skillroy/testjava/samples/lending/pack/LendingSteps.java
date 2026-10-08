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

import ai.skillroy.testjava.scenario.Durations;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lending desk's verbs (contract section 9.3), each compiled to {@code lending.*} primitive calls:
 *
 * <ul>
 *   <li>{@code signOn: {staff}}: {@code lending.input} Entry with the staff code, then Entry with the password, each
 *       READY. {@code staff} is a {@code $slot} reference whose slot declares a {@code credential} (checked with
 *       {@code CompileContext.slot}): the password is the field the credential join adds in memory, never a value
 *       in the scenario (phase2-contracts.md 3.1 rule 5). Registers the cleanup call
 *       {@code lending.input Key.SIGN_OFF} (READY), so that a passing scenario leaves the desk signed off.</li>
 *   <li>{@code signOff} (no arguments): {@code lending.input Key.SIGN_OFF}, READY.</li>
 *   <li>{@code scan: {item|barcode, times?}}: {@code times} (1 to 99, default 1) × {@code lending.input Barcode},
 *       READY. {@code item} is a {@code $slot} reference (its {@code barcode} field is used) or a mapping with
 *       {@code barcode}.</li>
 *   <li>{@code key: {action, value?}}: {@code lending.input Key.<action>}, READY; action is one of
 *       {@link LendingPack#KEY_ACTIONS}, or a reference into an {@code ActionKey} slot ({@code $keys.RETURN}), whose
 *       verifier checks it against the running window instead.</li>
 *   <li>{@code enter: {value}}: {@code lending.input Entry}, READY.</li>
 *   <li>{@code quantity: {n}}: {@code lending.input Key.QTY} with the quantity, READY.</li>
 *   <li>{@code deposit: {amount?}} (or {@code deposit} alone): {@code lending.input Key.DEPOSIT} with the deposit
 *       received (exactly the amount due when absent), READY.</li>
 *   <li>{@code expectPrompt: {text?, id?, within?}}: {@code sync.await} on {@code prompt.waiting} (payload match on
 *       {@code text} and {@code id} when given) for {@code within} (default the step timeout, at most 119 s).
 *       Since the wait is on a prompt topic it counts as handling the prompt.</li>
 *   <li>{@code answerPrompt: {choice, id?}}: {@code lending.prompt.answer}. The answer publishes no acknowledgement,
 *       so the call is {@code EVENT} on the pack's ready topics instead of READY (contract amendment A1); it is
 *       marked as handling the prompt.</li>
 *   <li>{@code authorise: {staff}}: a supervisor authorises what the desk asked for with {@code supervisor-required}:
 *       {@code lending.prompt.answer {choice: OK, id: supervisor-required}} (EVENT on the ready topics, handling the
 *       prompt), then {@code lending.input} Entry with the staff code and Entry with the password, each READY.
 *       {@code staff} is a {@code $slot} reference whose slot declares a {@code credential}. If the desk refuses,
 *       its {@code authorisation-failed} prompt is unexpected and fails the step.</li>
 * </ul>
 *
 * <p>Every READY call waits for {@code input.acked} and then for the desk to wait again
 * ({@code input.requested}) or to ask a question ({@code prompt.waiting}). Values are sent as strings; quote codes,
 * barcodes and money in YAML. Unknown argument keys are errors. Each verb declares the JSON Schema of its arguments
 * ({@link #argsSchema}, built by {@link LendingStepSchemas}; phase5-contracts.md 9.5), for tools and for authoring:
 * information only, {@link #compile} stays the authority. Stateless; a pure function of its arguments.
 */
final class LendingSteps implements StepLibrary {

    static final LendingSteps INSTANCE = new LendingSteps();

    /** The keypad actions of the desk. */
    static final List<String> KEYS = LendingPack.KEY_ACTIONS;
    /** The prompt {@code authorise} answers. */
    static final String SUPERVISOR_REQUIRED = "supervisor-required";
    /** The query kind a {@code key} reference must point into. */
    static final String ACTION_KEY = "ActionKey";
    /** The longest {@code expectPrompt} wait: the agent caps a call at 120 s and adds 1 s for the wait. */
    static final long MAX_WAIT_MS = 119_000L;

    static final String INPUT = "lending.input";
    static final String PROMPT_ANSWER = "lending.prompt.answer";

    private static final Set<String> VERBS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "signOn", "signOff", "scan", "key", "enter", "quantity", "deposit", "expectPrompt", "answerPrompt",
            "authorise")));

    private LendingSteps() {
    }

    @Override
    public Set<String> verbs() {
        return VERBS;
    }

    /**
     * The JSON Schema of a verb's arguments (phase5-contracts.md 9.5, 14.1), as {@link LendingStepSchemas} describes
     * them; a new map on every call.
     *
     * @return the schema, or null for a verb this library does not have
     */
    @Override
    public Map<String, Object> argsSchema(String verb) {
        return LendingStepSchemas.of(verb);
    }

    @Override
    public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
        return switch (verb) {
            case "signOn" -> signOn(args, ctx);
            case "signOff" -> {
                noArgs(verb, args);
                yield List.of(input("Key.SIGN_OFF", null));
            }
            case "scan" -> scan(args);
            case "key" -> key(args, ctx);
            case "enter" -> {
                Map<String, Object> a = map(verb, args, List.of("value"));
                yield List.of(input("Entry", text(verb, "value", a.get("value"), true)));
            }
            case "quantity" -> {
                Map<String, Object> a = map(verb, args, List.of("n"));
                yield List.of(input("Key.QTY", text(verb, "n", a.get("n"), true)));
            }
            case "deposit" -> {
                Map<String, Object> a = args == null ? Map.of() : map(verb, args, List.of("amount"));
                yield List.of(input("Key.DEPOSIT", text(verb, "amount", a.get("amount"), false)));
            }
            case "expectPrompt" -> expectPrompt(args, ctx);
            case "answerPrompt" -> answerPrompt(args, ctx);
            case "authorise" -> authorise(args, ctx);
            default -> throw new ScenarioException("unknown lending verb '" + verb + "'; lending verbs: " + VERBS);
        };
    }

    private static List<PlannedCall> signOn(Object args, CompileContext ctx) throws ScenarioException {
        Map<String, Object> a = map("signOn", args, List.of("staff"));
        String ref = staffSlot("signOn", a.get("staff"), ctx);
        ctx.addCleanup(input("Key.SIGN_OFF", null));
        return List.of(input("Entry", ref + ".code"), input("Entry", ref + ".password"));
    }

    private static List<PlannedCall> authorise(Object args, CompileContext ctx) throws ScenarioException {
        Map<String, Object> a = map("authorise", args, List.of("staff"));
        String ref = staffSlot("authorise", a.get("staff"), ctx);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("choice", "OK");
        params.put("id", SUPERVISOR_REQUIRED);
        PlannedCall answer = PlannedCall.event(PROMPT_ANSWER, params,
                AwaitSpec.anyOf(ctx.pack().syncPolicy().readyTopics()));
        ctx.markHandlesPrompt(answer);
        return List.of(answer, input("Entry", ref + ".code"), input("Entry", ref + ".password"));
    }

    /**
     * The {@code $slot} a staff argument names, which must declare a {@code credential}: a sign-on input's password
     * comes from {@code credentials.yaml}, never from the scenario.
     */
    private static String staffSlot(String verb, Object staff, CompileContext ctx) throws ScenarioException {
        String name = staff instanceof String ref && ref.startsWith("$") && !ref.startsWith("$$")
                ? ref.substring(1) : null;
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ScenarioException(verb + ": 'staff' must be a $slot reference to a slot with a credential "
                    + "(credential: {realm: staff, key: code}); a literal staff member or password is not accepted, "
                    + "found " + staff);
        }
        CompileContext.SlotInfo slot = ctx.slot(name);
        if (slot == null) {
            throw new ScenarioException(verb + ": 'staff' names $" + name + ", which is not a slot of the scenario");
        }
        if (slot.credential() == null) {
            throw new ScenarioException(verb + ": slot '" + name + "' has no credential; add credential: {realm: "
                    + "staff, key: code} so that the password comes from credentials.yaml");
        }
        return "$" + name;
    }

    private static List<PlannedCall> key(Object args, CompileContext ctx) throws ScenarioException {
        Map<String, Object> a = map("key", args, List.of("action", "value"));
        String action = text("key", "action", a.get("action"), true);
        if (action.startsWith("$") && !action.startsWith("$$")) {
            String slotName = action.substring(1).split("\\.", -1)[0];
            if (!action.matches("\\$[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z0-9_]+")) {
                throw new ScenarioException("key: 'action' must be a key action or a reference $<slot>.<action>, "
                        + "found " + action);
            }
            CompileContext.SlotInfo slot = ctx.slot(slotName);
            if (slot == null) {
                throw new ScenarioException("key: 'action' names $" + slotName + ", which is not a slot of the "
                        + "scenario");
            }
            if (!"query".equals(slot.kind()) || !ACTION_KEY.equals(slot.queryKind())) {
                throw new ScenarioException("key: 'action' must refer to a slot of query " + ACTION_KEY + "; slot '"
                        + slotName + "' is a " + slot.kind() + " slot");
            }
        } else if (!KEYS.contains(action)) {
            throw new ScenarioException("key: 'action' must be one of " + KEYS + ", found " + action);
        }
        return List.of(input("Key." + action, text("key", "value", a.get("value"), false)));
    }

    private static List<PlannedCall> scan(Object args) throws ScenarioException {
        Map<String, Object> a = map("scan", args, List.of("item", "barcode", "times"));
        if (a.containsKey("item") == a.containsKey("barcode")) {
            throw new ScenarioException("scan: give exactly one of 'item' (a $slot reference or a mapping with "
                    + "barcode) or 'barcode'");
        }
        String barcode;
        if (a.containsKey("barcode")) {
            barcode = text("scan", "barcode", a.get("barcode"), true);
        } else if (a.get("item") instanceof String ref && ref.startsWith("$") && !ref.startsWith("$$")) {
            barcode = ref + ".barcode";
        } else if (a.get("item") instanceof Map<?, ?> m && m.containsKey("barcode")) {
            barcode = text("scan", "item.barcode", m.get("barcode"), true);
        } else {
            throw new ScenarioException("scan: 'item' must be a $slot reference or a mapping with barcode");
        }
        long times = 1;
        if (a.containsKey("times")) {
            if (!(a.get("times") instanceof Long n) || n < 1 || n > 99) {
                throw new ScenarioException("scan: 'times' must be a whole number from 1 to 99, found "
                        + a.get("times"));
            }
            times = n;
        }
        List<PlannedCall> calls = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            calls.add(input("Barcode", barcode));
        }
        return calls;
    }

    private static List<PlannedCall> expectPrompt(Object args, CompileContext ctx) throws ScenarioException {
        Map<String, Object> a = args == null ? new LinkedHashMap<>()
                : map("expectPrompt", args, List.of("text", "id", "within"));
        long within = a.containsKey("within") ? Durations.toMillis(a.get("within"), "expectPrompt.within")
                : Math.min(ctx.stepTimeoutMs(), MAX_WAIT_MS);
        if (within <= 0 || within > MAX_WAIT_MS) {
            throw new ScenarioException("expectPrompt.within must be positive and at most " + MAX_WAIT_MS
                    + " ms, found " + within + " ms");
        }
        Map<String, Object> match = new LinkedHashMap<>();
        if (a.containsKey("id")) {
            match.put("id", text("expectPrompt", "id", a.get("id"), true));
        }
        if (a.containsKey("text")) {
            match.put("text", text("expectPrompt", "text", a.get("text"), true));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("topics", "prompt.waiting");
        if (!match.isEmpty()) {
            params.put("match", match);
        }
        params.put("timeoutMs", within);
        return List.of(new PlannedCall("sync.await", params, Sync.NONE, within + 1000));
    }

    private static List<PlannedCall> answerPrompt(Object args, CompileContext ctx) throws ScenarioException {
        Map<String, Object> a = map("answerPrompt", args, List.of("choice", "id"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("choice", text("answerPrompt", "choice", a.get("choice"), true));
        if (a.containsKey("id")) {
            params.put("id", text("answerPrompt", "id", a.get("id"), true));
        }
        PlannedCall call = PlannedCall.event(PROMPT_ANSWER, params,
                AwaitSpec.anyOf(ctx.pack().syncPolicy().readyTopics()));
        ctx.markHandlesPrompt(call);
        return List.of(call);
    }

    /** {@code lending.input {source, value?}} with READY and the step timeout. */
    static PlannedCall input(String source, String value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", source);
        if (value != null) {
            params.put("value", value);
        }
        return new PlannedCall(INPUT, params, Sync.READY, 0);
    }

    // ------------------------------------------------------------------ argument helpers

    private static void noArgs(String verb, Object args) throws ScenarioException {
        if (args != null && !(args instanceof Map<?, ?> m && m.isEmpty())) {
            throw new ScenarioException(verb + ": takes no arguments, found " + args);
        }
    }

    /** The verb's arguments as a map, rejecting keys outside {@code allowed}; null values are dropped. */
    private static Map<String, Object> map(String verb, Object args, List<String> allowed) throws ScenarioException {
        if (!(args instanceof Map<?, ?> raw)) {
            throw new ScenarioException(verb + ": arguments must be a mapping with keys " + allowed + ", found "
                    + (args == null ? "nothing" : args));
        }
        Map<String, Object> a = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (!allowed.contains(key)) {
                throw new ScenarioException(verb + ": unknown argument '" + key + "'; allowed: " + allowed);
            }
            if (e.getValue() != null) {
                a.put(key, e.getValue());
            }
        }
        return a;
    }

    /**
     * A scalar argument as the string the desk receives: strings as they are, whole numbers in decimal, other
     * numbers in plain decimal notation, booleans as {@code true}/{@code false}.
     *
     * @param required true if a missing value is an error; otherwise null is returned for it
     */
    private static String text(String verb, String key, Object value, boolean required) throws ScenarioException {
        if (value == null) {
            if (required) {
                throw new ScenarioException(verb + ": '" + key + "' is required");
            }
            return null;
        }
        if (value instanceof String s) {
            if (required && s.isEmpty()) {
                throw new ScenarioException(verb + ": '" + key + "' must not be empty");
            }
            return s;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        throw new ScenarioException(verb + ": '" + key + "' must be a single value (quote it), found " + value);
    }
}
