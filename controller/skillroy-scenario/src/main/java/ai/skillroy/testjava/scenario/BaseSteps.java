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
 *       does not count as handling a prompt. A destructive primitive needs {@code confirm: true} in {@code args}
 *       (phase5-contracts.md 13.1), which is passed through: the executor sends it as the call's confirmation
 *       ({@code _meta.confirm}), not as a parameter. The name {@code call} is reserved: a pack may not declare a verb
 *       of that name ({@link PlanCompiler#RESERVED_VERBS}).</li>
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
 * <p>The base web verbs (phase4-contracts.md 7.1) drive an embedded web view through the {@code web.*} primitives,
 * whichever path serves them (the agent's in-process path or the controller's CDP path, P4 6.1). Each selects its
 * browser with {@code browser:} (a {@code by} object: {@code {url}}, {@code {tag}}, {@code {title}} or
 * {@code {nativeId}}), else with the scenario's {@code web.browser} (both compile to the primitive's {@code by};
 * without either the agent picks the single showing browser), and a frame with {@code frame:} (a frame key):
 *
 * <ul>
 *   <li>{@code webClick: {selector | role + name, index?, expectVisible?, browser?, frame?, await?}} ->
 *       {@code web.dom.click}, SETTLE; {@code name} is a text (exact after trimming) or {@code {regex, ignoreCase?}}.
 *       </li>
 *   <li>{@code webFill: {… as webClick, text, clear?, enter?}} -> {@code web.dom.fill}, SETTLE; a {@code text} from
 *       a joined credential field is announced as {@code _meta.secretParams} (A34) by the executor, as for
 *       {@code type}.</li>
 *   <li>{@code webDispatch: {name, detail?, target?, browser?, frame?, await?}} -> {@code web.dispatchEvent},
 *       SETTLE.</li>
 *   <li>{@code webWaitFlag: {expression, timeoutMs?, pollMs?, browser?, frame?}} -> {@code web.waitFlag}, NONE (the
 *       primitive waits; {@code timeoutMs} at most 119 s, the call's timeout 1 s more).</li>
 *   <li>{@code webResize: {width, height | clear: true, browser?, await?}} -> {@code web.resize}, SETTLE.</li>
 *   <li>{@code webTap: {patterns, target?, browser?}} -> {@code web.tap}, NONE, with the cleanup call
 *       {@code web.tap {patterns: []}} on the same browser and target.</li>
 * </ul>
 *
 * <p>{@code await:} on {@code webClick}, {@code webFill}, {@code webDispatch} and {@code webResize} follows A32: the
 * primitive call with sync EVENT, then a {@code swing.settle} call with sync NONE (a page action may open a Swing
 * dialog). Every step of a scenario with a base web verb adds {@code web.evaluate} and {@code web.browsers} to the
 * plan's {@code requires} (the compiler does it).
 *
 * <p>Unknown argument keys are errors. Stateless; safe from any thread.
 */
public final class BaseSteps implements StepLibrary {

    /** The shared instance. */
    public static final BaseSteps INSTANCE = new BaseSteps();

    /** The base verbs in documentation order (the web verbs since Phase 4). */
    public static final List<String> VERBS = List.of("call", "click", "type", "press", "dismiss", "waitFor",
            "expect", "mark", "webClick", "webFill", "webDispatch", "webWaitFlag", "webResize", "webTap");

    /** The base web verbs (phase4-contracts.md 7.1). */
    static final Set<String> WEB_VERBS = Set.of("webClick", "webFill", "webDispatch", "webWaitFlag", "webResize",
            "webTap");

    /** The longest {@code timeoutMs} of {@code webWaitFlag}: the agent caps a call at 120 s. */
    static final long MAX_WAIT_FLAG_MS = 119_000L;

    /** The largest width or height {@code webResize} accepts (phase4-contracts.md 4.2). */
    static final long MAX_VIEWPORT = 16_384L;

    /** The shortest poll interval of {@code webWaitFlag} (phase4-contracts.md 4.2). */
    static final long MIN_POLL_MS = 20L;

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
            case "webClick" -> webAction(verb, args, ctx, "web.dom.click", false);
            case "webFill" -> webAction(verb, args, ctx, "web.dom.fill", true);
            case "webDispatch" -> webDispatch(args, ctx);
            case "webWaitFlag" -> webWaitFlag(args, ctx);
            case "webResize" -> webResize(args, ctx);
            case "webTap" -> webTap(args, ctx);
            default -> throw new ScenarioException("unknown base verb '" + verb + "'; base verbs: " + VERBS);
        };
    }

    // ------------------------------------------------------------------------------------------------ web verbs

    /** {@code webClick} and {@code webFill}: the element by selector or by role and name, then the fill's text. */
    private static List<PlannedCall> webAction(String verb, Object args, CompileContext ctx, String primitive,
                                               boolean fill) throws ScenarioException {
        List<String> allowed = new ArrayList<>(List.of("selector", "role", "name", "index", "expectVisible",
                "browser", "frame", "await"));
        if (fill) {
            allowed.addAll(List.of("text", "clear", "enter"));
        }
        Map<String, Object> a = map(verb, args, allowed, false);
        AwaitArg await = verbAwait(verb, a);
        Map<String, Object> params = new LinkedHashMap<>();
        browser(verb, a, params, ctx);
        frame(verb, a, params);
        element(verb, a, params);
        if (fill) {
            Object text = a.get("text");
            if (text == null || text instanceof Map || text instanceof List) {
                throw new ScenarioException(verb + ": 'text' must be a string, found " + ScenarioLoader.describe(text));
            }
            params.put("text", text instanceof String ? text : Values.stringOf(text));
            flag(verb, a, "clear", params);
            flag(verb, a, "enter", params);
        }
        return webCalls(primitive, params, await, ctx);
    }

    /** {@code webDispatch}: a {@code CustomEvent} with an optional detail, on the window or a selected element. */
    private static List<PlannedCall> webDispatch(Object args, CompileContext ctx) throws ScenarioException {
        String verb = "webDispatch";
        Map<String, Object> a = map(verb, args, List.of("name", "detail", "target", "browser", "frame", "await"),
                false);
        AwaitArg await = verbAwait(verb, a);
        Map<String, Object> params = new LinkedHashMap<>();
        browser(verb, a, params, ctx);
        frame(verb, a, params);
        params.put("name", text(verb, a, "name", "the event's name"));
        if (a.containsKey("detail")) {
            params.put("detail", a.get("detail"));
        }
        if (a.containsKey("target")) {
            params.put("target", text(verb, a, "target", "a CSS selector"));
        }
        return webCalls("web.dispatchEvent", params, await, ctx);
    }

    /**
     * {@code webWaitFlag}: the primitive evaluates the expression until it is truthy; sync NONE. Without
     * {@code timeoutMs} the primitive waits for the call's budget, the step timeout.
     */
    private static List<PlannedCall> webWaitFlag(Object args, CompileContext ctx) throws ScenarioException {
        String verb = "webWaitFlag";
        Map<String, Object> a = map(verb, args, List.of("expression", "timeoutMs", "pollMs", "browser", "frame"),
                false);
        Map<String, Object> params = new LinkedHashMap<>();
        browser(verb, a, params, ctx);
        frame(verb, a, params);
        params.put("expression", text(verb, a, "expression", "a JavaScript expression"));
        long callTimeoutMs = 0;
        if (a.containsKey("timeoutMs")) {
            long ms = Durations.toMillis(a.get("timeoutMs"), verb + ".timeoutMs");
            if (ms > MAX_WAIT_FLAG_MS) {
                throw new ScenarioException(verb + ".timeoutMs must be at most " + MAX_WAIT_FLAG_MS + " ms (the agent"
                        + " caps a call at 120 s), found " + ms + " ms");
            }
            params.put("timeoutMs", ms);
            callTimeoutMs = ms + 1000;
        }
        if (a.containsKey("pollMs")) {
            if (!(a.get("pollMs") instanceof Long poll) || poll < MIN_POLL_MS) {
                throw new ScenarioException(verb + ": 'pollMs' must be a whole number of " + MIN_POLL_MS + " or more,"
                        + " found " + ScenarioLoader.describe(a.get("pollMs")));
            }
            params.put("pollMs", poll);
        }
        return List.of(new PlannedCall("web.waitFlag", params, Sync.NONE, callTimeoutMs));
    }

    /** {@code webResize}: a viewport override of {@code width} x {@code height}, or {@code clear: true}. */
    private static List<PlannedCall> webResize(Object args, CompileContext ctx) throws ScenarioException {
        String verb = "webResize";
        Map<String, Object> a = map(verb, args, List.of("width", "height", "clear", "browser", "await"), false);
        AwaitArg await = verbAwait(verb, a);
        Map<String, Object> params = new LinkedHashMap<>();
        browser(verb, a, params, ctx);
        String usage = verb + ": give 'width' and 'height' (1 to " + MAX_VIEWPORT + "), or 'clear: true'";
        if (a.containsKey("clear")) {
            if (!Boolean.TRUE.equals(a.get("clear")) || a.containsKey("width") || a.containsKey("height")) {
                throw new ScenarioException(usage);
            }
            params.put("clear", true);
        } else {
            if (!a.containsKey("width") || !a.containsKey("height")) {
                throw new ScenarioException(usage);
            }
            for (String key : List.of("width", "height")) {
                if (!(a.get(key) instanceof Long n) || n < 1 || n > MAX_VIEWPORT) {
                    throw new ScenarioException(verb + ": '" + key + "' must be a whole number from 1 to "
                            + MAX_VIEWPORT + ", found " + ScenarioLoader.describe(a.get(key)));
                }
                params.put(key, n);
            }
        }
        return webCalls("web.resize", params, await, ctx);
    }

    /**
     * {@code webTap}: the page events to tap, sync NONE, and the cleanup call that removes the tap from the same
     * browser and target when the scenario ends.
     */
    private static List<PlannedCall> webTap(Object args, CompileContext ctx) throws ScenarioException {
        String verb = "webTap";
        Map<String, Object> a = map(verb, args, List.of("patterns", "target", "browser"), false);
        Map<String, Object> params = new LinkedHashMap<>();
        browser(verb, a, params, ctx);
        Object raw = a.get("patterns");
        List<String> patterns = new ArrayList<>();
        if (raw instanceof String one && !one.isBlank()) {
            patterns.add(one.strip());
        } else if (raw instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(p -> p instanceof String text && !text.isBlank())) {
            list.forEach(p -> patterns.add(((String) p).strip()));
        } else {
            throw new ScenarioException(verb + ": 'patterns' must be a list of event names or globs such as"
                    + " [app.ready, app.*], found " + ScenarioLoader.describe(raw));
        }
        params.put("patterns", patterns);
        if (a.containsKey("target")) {
            String target = a.get("target") instanceof String t ? t.strip().toLowerCase(Locale.ROOT) : null;
            if (!"window".equals(target) && !"document".equals(target)) {
                throw new ScenarioException(verb + ": 'target' must be window or document, found "
                        + ScenarioLoader.describe(a.get("target")));
            }
            params.put("target", target);
        }
        Map<String, Object> removal = new LinkedHashMap<>(params);
        removal.put("patterns", List.of());
        ctx.addCleanup(new PlannedCall("web.tap", removal, Sync.NONE, 0));
        return List.of(new PlannedCall("web.tap", params, Sync.NONE, 0));
    }

    /**
     * The calls of a web verb that acts on the page: the primitive with SETTLE, or, with an {@code await}, the
     * primitive with EVENT followed by a {@code swing.settle} call (sync NONE) bounded by the step timeout (A32).
     */
    private static List<PlannedCall> webCalls(String primitive, Map<String, Object> params, AwaitArg await,
                                              CompileContext ctx) {
        return uiCalls(primitive, params, await, ctx);
    }

    /**
     * The step's {@code browser} (a {@code by} object), else the scenario's {@code web.browser}, as the primitive's
     * {@code by}; nothing when there is neither.
     */
    private static void browser(String verb, Map<String, Object> a, Map<String, Object> params, CompileContext ctx)
            throws ScenarioException {
        if (a.containsKey("browser")) {
            String problem = WebGrammar.byProblem("browser", a.get("browser"));
            if (problem != null) {
                throw new ScenarioException(verb + ": " + problem);
            }
            params.put("by", a.get("browser"));
            return;
        }
        Map<String, Object> fallback = ctx.web() == null ? null : ctx.web().defaultBrowser();
        if (fallback != null) {
            params.put("by", new LinkedHashMap<>(fallback));
        }
    }

    private static void frame(String verb, Map<String, Object> a, Map<String, Object> params)
            throws ScenarioException {
        if (a.containsKey("frame")) {
            params.put("frame", text(verb, a, "frame", "a frame key"));
        }
    }

    /**
     * {@code selector}, or {@code role} with {@code name} ({@code index} and {@code expectVisible} with either):
     * the element of {@code web.dom.click} and {@code web.dom.fill}.
     */
    private static void element(String verb, Map<String, Object> a, Map<String, Object> params)
            throws ScenarioException {
        boolean selector = a.containsKey("selector");
        if (selector == a.containsKey("role")) {
            throw new ScenarioException(verb + ": give exactly one of 'selector' (a CSS selector) or 'role' with"
                    + " 'name' (an ARIA role and the accessible name)");
        }
        if (selector) {
            if (a.containsKey("name")) {
                throw new ScenarioException(verb + ": 'name' goes with 'role', not with 'selector'");
            }
            params.put("selector", text(verb, a, "selector", "a CSS selector"));
        } else {
            params.put("role", text(verb, a, "role", "an ARIA role such as button or textbox"));
            if (!a.containsKey("name")) {
                throw new ScenarioException(verb + ": 'role' needs 'name' (the accessible name: a text, or {regex,"
                        + " ignoreCase?})");
            }
            params.put("name", accessibleName(verb, a.get("name")));
        }
        if (a.containsKey("index")) {
            if (!(a.get("index") instanceof Long index) || index < 0) {
                throw new ScenarioException(verb + ": 'index' must be a whole number of 0 or more, found "
                        + ScenarioLoader.describe(a.get("index")));
            }
            params.put("index", index);
        }
        flag(verb, a, "expectVisible", params);
    }

    /** A text (exact after trimming and collapsing whitespace) or {@code {regex, ignoreCase?}}. */
    private static Object accessibleName(String verb, Object value) throws ScenarioException {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        if (value instanceof Map<?, ?> map && map.get("regex") instanceof String regex && !regex.isBlank()
                && map.keySet().stream().allMatch(k -> "regex".equals(k) || "ignoreCase".equals(k))
                && (!map.containsKey("ignoreCase") || map.get("ignoreCase") instanceof Boolean)) {
            Map<String, Object> name = new LinkedHashMap<>();
            name.put("regex", regex);
            if (map.containsKey("ignoreCase")) {
                name.put("ignoreCase", map.get("ignoreCase"));
            }
            return name;
        }
        throw new ScenarioException(verb + ": 'name' must be a text or {regex, ignoreCase?} (ignoreCase true or"
                + " false), found " + ScenarioLoader.describe(value));
    }

    /** The argument {@code key} as a non-empty string. */
    private static String text(String verb, Map<String, Object> a, String key, String what)
            throws ScenarioException {
        if (!(a.get(key) instanceof String text) || text.isBlank()) {
            throw new ScenarioException(verb + ": '" + key + "' must be " + what + " (a non-empty string), found "
                    + ScenarioLoader.describe(a.get(key)));
        }
        return text;
    }

    /** The optional boolean argument {@code key}, copied to the params when present. */
    private static void flag(String verb, Map<String, Object> a, String key, Map<String, Object> params)
            throws ScenarioException {
        if (!a.containsKey(key)) {
            return;
        }
        if (!(a.get(key) instanceof Boolean b)) {
            throw new ScenarioException(verb + ": '" + key + "' must be true or false, found "
                    + ScenarioLoader.describe(a.get(key)));
        }
        params.put(key, b);
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
