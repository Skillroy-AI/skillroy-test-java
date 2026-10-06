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

import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CaptureSpec;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The verbs every pack has (contract section 8.3), looked up after the pack's own verbs:
 *
 * <ul>
 *   <li>{@code call: {primitive, args?, sync?, await?}}: any primitive, sync {@code NONE} by default (any case).
 *       {@code sync: EVENT} requires {@code await: {topic|topics, match?, within?}}: the call is complete when the
 *       first event on {@code topic} (a glob, or a list of globs) whose payload holds every {@code match} entry is
 *       published after the call began; {@code within} defaults to the step timeout and is at most 600 s. Any other
 *       sync forbids {@code await}. To wait for a prompt the scenario expects, use {@code waitFor}: an EVENT wait
 *       does not count as handling a prompt.</li>
 *   <li>{@code click: {by|target, rung?, focusTimeoutMs?, activateWindow?, force?, fallback?, strict?, await?}} ->
 *       {@code swing.click}, SETTLE. The scenario's {@code ui} settings (amendment A29) apply to a click that names
 *       no {@code rung}: with {@code clickRung: COMPONENT} it is sent with {@code rung: COMPONENT},
 *       {@code activateWindow: true} and, unless strict, {@code fallback: ACTION}; the step's own {@code rung},
 *       {@code activateWindow} and {@code fallback} win. {@code strict} (a boolean, default the scenario's
 *       {@code ui.strict}) only decides whether that fall-back is added and is not sent. {@code fallback} accepts
 *       {@code ACTION} only, needs the COMPONENT rung, and contradicts {@code strict: true} on the same step.</li>
 *   <li>{@code type: {by|target, text, enter?, mode?, clear?, force?, await?}} -> {@code swing.type}, SETTLE.</li>
 *   <li>{@code press: {key, target?|by?, modifiers?, force?, await?}} -> {@code swing.key} ({@code by} is sent as
 *       {@code target}), SETTLE.</li>
 *   <li>{@code dismiss: {button?, dialog?, close?}} -> {@code swing.dismiss}, SETTLE; answers a prompt.</li>
 *   <li>{@code waitFor: {topic, match?, within?, capture?}} -> {@code sync.await} with {@code timeoutMs} =
 *       {@code within}
 *       (default: the step timeout; at most 119 s, because the agent caps a call at 120 s); the executor adds
 *       {@code since}: the {@code seq.before} of the last call before it other than a wait or a mark.</li>
 *   <li>{@code expect: {path: matcher, …}}: no calls; the expectations are attached to the step.</li>
 *   <li>{@code mark: label} (or {@code {label, attrs?}}) -> {@code trace.mark}.</li>
 * </ul>
 *
 * <p>{@code await: {topic|topics, match?, within?}} on {@code click}, {@code type} and {@code press} (amendment A32,
 * the same shape as {@code call}'s): the verb compiles to its primitive with sync EVENT and that await, followed by a
 * {@code swing.settle} call with sync NONE, so that the step ends when the application's own event has arrived and
 * the event-dispatch thread is quiet. The executor fails such a settle that reports {@code settled: false} as
 * SYNC_TIMEOUT, as it does for the SETTLE sync.
 *
 * <p>{@code capture: {slot, fields: {field: payloadPath}}} (phase2-contracts.md 3.4) is accepted by {@code waitFor}
 * and inside the {@code await} of {@code call}, {@code click}, {@code type} and {@code press}: when the awaited event
 * arrives, the executor binds the slot to the named payload values.
 *
 * <p>{@code force} and {@code activateWindow} are passed to the Swing module as they are (contract amendments A8 and
 * A10): {@code force: true} sends the input even though a modal dialog blocks the target's window (otherwise the
 * call fails with MODAL_OPEN), {@code activateWindow: true} lets the COMPONENT rung bring the target's window to the
 * front first.
 *
 * <p>Unknown argument keys are errors. Stateless; safe from any thread.
 */
public final class BaseSteps implements StepLibrary {

    /** The shared instance. */
    public static final BaseSteps INSTANCE = new BaseSteps();

    /** The base verbs in documentation order. */
    public static final List<String> VERBS = List.of("call", "click", "type", "press", "dismiss", "waitFor",
            "expect", "mark");

    private static final Set<String> VERB_SET = Collections.unmodifiableSet(new LinkedHashSet<>(VERBS));

    /** The longest wait one {@code sync.await} can make: the agent caps a call at 120 s and adds 1 s for the wait. */
    private static final long MAX_WAIT_MS = 119_000L;

    /**
     * The longest {@code timeoutMs} of the {@code swing.settle} call that follows an awaited UI verb: the call itself
     * gets 2 s more, and the executor caps a call at 120 s.
     */
    static final long MAX_SETTLE_MS = 118_000L;

    /** The base verbs whose calls the executor checks for {@code fired: false} (amendment A29). */
    static final Set<String> UI_VERBS = Set.of("click", "type", "press");

    private BaseSteps() {
    }

    @Override
    public Set<String> verbs() {
        return VERB_SET;
    }

    @Override
    public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) throws ScenarioException {
        return switch (verb) {
            case "call" -> call(args);
            case "click" -> {
                Map<String, Object> a = map(verb, args, List.of("target", "by", "rung", "focusTimeoutMs",
                        "activateWindow", "force", "fallback", "strict", "await"), false);
                requireOneTarget(verb, a);
                AwaitArg await = verbAwait(verb, a);
                clickFidelity(a, ctx.ui());
                yield uiCalls("swing.click", a, await, ctx);
            }
            case "type" -> {
                Map<String, Object> a = map(verb, args, List.of("target", "by", "text", "enter", "mode", "clear",
                        "force", "await"), false);
                requireOneTarget(verb, a);
                AwaitArg await = verbAwait(verb, a);
                Object text = a.get("text");
                if (text == null || text instanceof Map || text instanceof List) {
                    throw new ScenarioException("type: 'text' must be a string");
                }
                if (!(text instanceof String)) {
                    a.put("text", Values.stringOf(text));
                }
                yield uiCalls("swing.type", a, await, ctx);
            }
            case "press" -> {
                Map<String, Object> a = map(verb, args, List.of("key", "target", "by", "modifiers", "force", "await"),
                        false);
                AwaitArg await = verbAwait(verb, a);
                if (!(a.get("key") instanceof String key) || key.isBlank()) {
                    throw new ScenarioException("press: 'key' must be a key name such as ENTER or F2");
                }
                if (a.containsKey("by")) {
                    if (a.containsKey("target")) {
                        throw new ScenarioException("press: give either 'by' or 'target', not both");
                    }
                    a.put("target", a.remove("by"));
                }
                yield uiCalls("swing.key", a, await, ctx);
            }
            case "dismiss" -> {
                Map<String, Object> a = map(verb, args, List.of("button", "dialog", "close"), true);
                PlannedCall call = new PlannedCall("swing.dismiss", a, Sync.SETTLE, 0);
                ctx.markHandlesPrompt(call);
                yield List.of(call);
            }
            case "waitFor" -> waitFor(args, ctx);
            case "expect" -> {
                if (!(args instanceof Map<?, ?> m) || m.isEmpty()) {
                    throw new ScenarioException("expect: needs a mapping of path: matcher");
                }
                yield List.of();
            }
            case "mark" -> mark(args);
            default -> throw new ScenarioException("unknown base verb '" + verb + "'; base verbs: " + VERBS);
        };
    }

    private static List<PlannedCall> call(Object args) throws ScenarioException {
        Map<String, Object> a = map("call", args, List.of("primitive", "args", "sync", "await"), false);
        if (!(a.get("primitive") instanceof String primitive) || primitive.isBlank()) {
            throw new ScenarioException("call: 'primitive' must be a primitive name");
        }
        Object params = a.get("args");
        if (params != null && !(params instanceof Map)) {
            throw new ScenarioException("call: 'args' must be a mapping");
        }
        Sync sync = Sync.NONE;
        Object rawSync = a.get("sync");
        if (rawSync != null) {
            try {
                sync = Sync.valueOf(String.valueOf(rawSync).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ScenarioException("call: 'sync' must be one of NONE, ACK, READY, SETTLE, EVENT, found "
                        + rawSync);
            }
        }
        AwaitArg await = null;
        if (sync == Sync.EVENT) {
            if (!a.containsKey("await")) {
                throw new ScenarioException("call: sync EVENT needs 'await: {topic|topics, match?, within?}'");
            }
            await = await("call.await", a.get("await"));
        } else if (a.containsKey("await")) {
            throw new ScenarioException("call: 'await' needs sync: EVENT, found sync " + sync);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) params;
        return List.of(new PlannedCall(primitive, typed, sync, 0, await == null ? null : await.spec(),
                await == null ? null : await.capture()));
    }

    /** A parsed {@code await} argument: the spec and the optional capture. */
    private record AwaitArg(AwaitSpec spec, CaptureSpec capture) {
    }

    /** {@code capture: {slot, fields: {field: payloadPath, …}}}. */
    static CaptureSpec capture(String where, Object value) throws ScenarioException {
        Map<String, Object> a = map(where, value, List.of("slot", "fields"), false);
        if (!(a.get("slot") instanceof String slot) || slot.isBlank()) {
            throw new ScenarioException(where + ": 'slot' must name the slot to bind");
        }
        if (!(a.get("fields") instanceof Map<?, ?> fields) || fields.isEmpty()) {
            throw new ScenarioException(where + ": 'fields' is required: a mapping of slot field to payload path"
                    + " ({id: loanId})");
        }
        Map<String, String> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : fields.entrySet()) {
            if (!(e.getValue() instanceof String path)) {
                throw new ScenarioException(where + ": field '" + e.getKey() + "' needs a payload path such as"
                        + " loanId or header.id");
            }
            typed.put(String.valueOf(e.getKey()), path.strip());
        }
        try {
            return CaptureSpec.of(slot.strip(), typed);
        } catch (IllegalArgumentException e) {
            throw new ScenarioException(where + ": " + e.getMessage());
        }
    }

    /**
     * {@code await: {topic|topics, match?, within?}} of {@code call} with sync EVENT, or of a UI verb. {@code within}
     * absent gives 0, which the compiler turns into the step timeout (at most 600 s).
     *
     * @param where the key for messages, {@code call.await} or {@code click.await}
     */
    private static AwaitArg await(String where, Object args) throws ScenarioException {
        Map<String, Object> a = map(where, args, List.of("topic", "topics", "match", "within", "capture"), false);
        if (a.containsKey("topic") == a.containsKey("topics")) {
            throw new ScenarioException(where + ": give exactly one of 'topic' or 'topics'");
        }
        String topics = topics(a.containsKey("topic") ? a.get("topic") : a.get("topics"));
        if (topics == null) {
            throw new ScenarioException(where + ": 'topic' or 'topics' must be a topic (glob) or a list of"
                    + " topics");
        }
        Object match = a.get("match");
        if (match != null && !(match instanceof Map)) {
            throw new ScenarioException(where + ": 'match' must be a mapping of payload key to value");
        }
        long within = 0;
        if (a.containsKey("within")) {
            within = Durations.toMillis(a.get("within"), where + ".within");
            if (within > AwaitRules.MAX_WAIT_MS) {
                throw new ScenarioException(where + ".within must be at most " + AwaitRules.MAX_WAIT_MS
                        + " ms (the agent's limit for sync.await), found " + within + " ms");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typedMatch = (Map<String, Object>) match;
        CaptureSpec capture = a.containsKey("capture") ? capture(where + ".capture", a.get("capture")) : null;
        return new AwaitArg(new AwaitSpec(topics, typedMatch, within), capture);
    }

    /** Removes the UI verb's {@code await} argument and returns it parsed, or null when the step has none. */
    private static AwaitArg verbAwait(String verb, Map<String, Object> a) throws ScenarioException {
        return a.containsKey("await") ? await(verb + ".await", a.remove("await")) : null;
    }

    /**
     * The calls of a UI verb: the primitive with SETTLE, or, with an await, the primitive with EVENT followed by a
     * {@code swing.settle} call (sync NONE) bounded by the step timeout.
     */
    private static List<PlannedCall> uiCalls(String primitive, Map<String, Object> args, AwaitArg await,
                                             CompileContext ctx) {
        if (await == null) {
            return List.of(new PlannedCall(primitive, args, Sync.SETTLE, 0));
        }
        long settleMs = Math.max(1L, Math.min(ctx.stepTimeoutMs(), MAX_SETTLE_MS));
        Map<String, Object> settle = new LinkedHashMap<>();
        settle.put("timeoutMs", settleMs);
        return List.of(new PlannedCall(primitive, args, Sync.EVENT, 0, await.spec(), await.capture()),
                new PlannedCall("swing.settle", settle, Sync.NONE, settleMs + 2000));
    }

    /**
     * Applies the scenario's {@code ui} settings and the step's {@code strict} and {@code fallback} to the click's
     * arguments (see the class description); {@code strict} is removed, since the primitive does not know it.
     */
    private static void clickFidelity(Map<String, Object> a, UiSettings ui) throws ScenarioException {
        Boolean stepStrict = null;
        if (a.containsKey("strict")) {
            if (!(a.remove("strict") instanceof Boolean b)) {
                throw new ScenarioException("click: 'strict' must be true or false");
            }
            stepStrict = b;
        }
        boolean strict = stepStrict != null ? stepStrict : ui.strict();
        if (a.containsKey("fallback")) {
            if (!(a.get("fallback") instanceof String f) || !f.strip().equalsIgnoreCase(UiSettings.ACTION)) {
                throw new ScenarioException("click: 'fallback' must be ACTION (the only rung a COMPONENT click can"
                        + " fall back to), found " + ScenarioLoader.describe(a.get("fallback")));
            }
            if (Boolean.TRUE.equals(stepStrict)) {
                throw new ScenarioException("click: 'fallback: ACTION' and 'strict: true' contradict each other");
            }
            a.put("fallback", UiSettings.ACTION);
        }
        if (!a.containsKey("rung") && ui.componentClicks()) {
            a.put("rung", UiSettings.COMPONENT);
            a.putIfAbsent("activateWindow", true);
            if (!strict) {
                a.putIfAbsent("fallback", UiSettings.ACTION);
            }
        }
        if (a.containsKey("fallback")) {
            Object rung = a.get("rung");
            boolean reference = rung instanceof String r && References.containsReference(r);
            if (!reference && !(rung instanceof String r2 && r2.strip().equalsIgnoreCase(UiSettings.COMPONENT))) {
                throw new ScenarioException("click: 'fallback: ACTION' needs rung COMPONENT (on the step, or"
                        + " ui.clickRung: COMPONENT), found rung " + (rung == null ? "(none)"
                        : ScenarioLoader.describe(rung)));
            }
        }
    }

    /** A topic glob, or a non-empty list of them joined with commas; null if the value is neither. */
    private static String topics(Object value) {
        String topics = null;
        if (value instanceof String s) {
            topics = s;
        } else if (value instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(t -> t instanceof String s && !s.isBlank())) {
            topics = String.join(",", list.stream().map(String.class::cast).toList());
        }
        return topics == null || TopicFilter.parse(topics).globs().isEmpty() ? null : topics;
    }

    private static List<PlannedCall> waitFor(Object args, CompileContext ctx) throws ScenarioException {
        Map<String, Object> a = map("waitFor", args, List.of("topic", "match", "within", "capture"), false);
        Object topic = a.get("topic");
        boolean valid = topic instanceof String s && !s.isBlank();
        if (topic instanceof List<?> list && !list.isEmpty()) {
            valid = list.stream().allMatch(t -> t instanceof String s && !s.isBlank());
        }
        if (!valid) {
            throw new ScenarioException("waitFor: 'topic' must be a topic (glob) or a list of topics");
        }
        Object match = a.get("match");
        if (match != null && !(match instanceof Map)) {
            throw new ScenarioException("waitFor: 'match' must be a mapping of payload key to value");
        }
        long within = a.containsKey("within") ? Durations.toMillis(a.get("within"), "waitFor.within")
                : Math.min(ctx.stepTimeoutMs(), MAX_WAIT_MS);
        if (within > MAX_WAIT_MS) {
            throw new ScenarioException("waitFor.within must be at most " + MAX_WAIT_MS + " ms (the agent caps a"
                    + " call at 120 s), found " + within + " ms");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("topics", topic);
        if (match != null) {
            params.put("match", match);
        }
        params.put("timeoutMs", within);
        CaptureSpec capture = a.containsKey("capture") ? capture("waitFor.capture", a.get("capture")) : null;
        return List.of(new PlannedCall("sync.await", params, Sync.NONE, within + 1000, null, capture));
    }

    private static List<PlannedCall> mark(Object args) throws ScenarioException {
        Map<String, Object> params = new LinkedHashMap<>();
        if (args instanceof Map<?, ?>) {
            Map<String, Object> a = map("mark", args, List.of("label", "attrs"), false);
            if (!(a.get("label") instanceof String label) || label.isBlank()) {
                throw new ScenarioException("mark: 'label' must be a string");
            }
            params.put("label", label);
            if (a.get("attrs") != null) {
                if (!(a.get("attrs") instanceof Map)) {
                    throw new ScenarioException("mark: 'attrs' must be a mapping");
                }
                params.put("attrs", a.get("attrs"));
            }
        } else if (args != null && !(args instanceof List)) {
            params.put("label", Values.stringOf(args));
        } else {
            throw new ScenarioException("mark: needs a label");
        }
        return List.of(new PlannedCall("trace.mark", params, Sync.NONE, 0));
    }

    private static void requireOneTarget(String verb, Map<String, Object> a) throws ScenarioException {
        if (a.containsKey("target") == a.containsKey("by")) {
            throw new ScenarioException(verb + ": give exactly one of 'by' (a locator) or 'target' (a ref)");
        }
    }

    /** The verb's arguments as a mutable map, rejecting keys outside {@code allowed}. */
    private static Map<String, Object> map(String verb, Object args, List<String> allowed, boolean nullAllowed)
            throws ScenarioException {
        if (args == null && nullAllowed) {
            return new LinkedHashMap<>();
        }
        if (!(args instanceof Map<?, ?> raw)) {
            throw new ScenarioException(verb + ": arguments must be a mapping with keys " + allowed + ", found "
                    + ScenarioLoader.describe(args));
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

    /** For messages: the verbs available with a pack, pack verbs (sorted) first. */
    static List<String> available(Set<String> packVerbs) {
        List<String> all = new ArrayList<>(new TreeSet<>(packVerbs));
        for (String verb : VERBS) {
            if (!all.contains(verb)) {
                all.add(verb);
            }
        }
        return all;
    }
}
