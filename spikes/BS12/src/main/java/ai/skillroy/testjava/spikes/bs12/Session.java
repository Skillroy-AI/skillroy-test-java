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

package ai.skillroy.testjava.spikes.bs12;

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One short task end to end as a loop: capture -> model -> act -> wait for the desk to settle -> capture. The desk
 * starts signed on (the person's sign-on verb, as in Phase 5's session; the model never sees a password).
 *
 * <p>V2 (the computer toolset): the model's point is hit-tested in the harness on the capture it saw (the deepest
 * showing component, see {@link Screen#hitTest}) and the action goes through the existing rungs on that component:
 * {@code swing.click} (AUTO, i.e. ACTION; the lending module's {@code ActionFirer} presses the keypad keys and the
 * prompt's painted buttons), {@code swing.type} into the text component that has the keyboard focus (the one last
 * clicked, else {@code swing.edtState.focusOwner}), {@code swing.key}; never Robot. Before acting on a point the set
 * of showing windows is compared with the capture's; a changed set is a stale capture and is not acted on (design
 * §3.10, step 1). V1 (custom tools): {@code click(ref)}, {@code type_text(ref, text, enter)}, {@code press_key(key)}.
 *
 * <p>Waiting: an action that can feed the desk's engine (a key, a prompt button, Enter) waits for the desk's own
 * {@code input.requested} or {@code prompt.waiting} after the action's sequence number (at most 3 s; a dropped input
 * publishes nothing), then {@code swing.settle}; any other action only settles. No sleeps.
 *
 * <p>Caching (R6): one breakpoint on the system text (after the tools), one advancing on the latest tool result.
 */
final class Session {

    static final String SYS_V2 = """
            You are operating a desktop application, a tool-library lending desk, through the computer tools. \
            Screenshots show only the application window (%d x %d pixels); coordinates are pixels of that screenshot. \
            After each of your turns the last tool result carries a fresh screenshot, so you do not need to ask for \
            one. Keyboard input goes to the component that has the keyboard focus. When the task is complete, reply \
            with one short sentence and no tool call. Do not ask questions.""";
    static final String SYS_V1 = """
            You are operating a desktop application, a tool-library lending desk. Each observation is a screenshot of \
            the application window plus a text snapshot of its component tree, where each component line ends with \
            [ref=...]. Act with the tools click, type_text and press_key, naming components by ref. After each of \
            your turns the last tool result carries a fresh observation. When the task is complete, reply with one \
            short sentence and no tool call. Do not ask questions.""";
    static final Map<String, String> TASKS = Map.of(
        "lend", "A walk-in borrower wants to borrow one Garden spade (item code YRD-001). Lend it and take exactly "
            + "the deposit that is due. A staff member is already signed on.",
        "ladder", "A walk-in borrower wants to borrow one Extension ladder (item code LAD-001); they have completed "
            + "the safety induction. Lend it and take exactly the deposit that is due. A staff member is already "
            + "signed on.");
    /**
     * A screen legend such as a domain pack could supply for icon-only controls ({@code --hint legend}); the
     * grounding runs and the other sessions do without it.
     */
    static final String LEGEND = """
            Screen legend (supplied by the application's domain pack): the six keys along the bottom are, left to \
            right, QTY (quantity), CANCEL, RETURN (return mode), DUE (the sigma: ends item entry and works out the \
            deposit due), DEPOSIT (takes the deposit: the amount typed in the entry field first, else exactly the \
            amount due) and SIGN OFF. Item codes and amounts are typed into the long entry field above the keys and \
            submitted with Enter.""";
    static final Map<String, String[]> EXPECT = Map.of("lend", new String[] {"YRD-001", "3.50"},
        "ladder", new String[] {"LAD-001", "6.50"});

    /** The calls that act on the UI (counted as actions); screenshot and zoom are observations, the rest "other". */
    static final java.util.Set<String> UI_ACTIONS = java.util.Set.of("left_click", "double_click", "triple_click",
            "right_click", "middle_click", "type", "key", "left_click_drag", "scroll", "click", "type_text", "press_key");

    private Session() {
    }

    /** The pending wait after actions: the desk's own ready event after the first engine action, then a settle. */
    static final class Settler {
        long pendingSeq = -1;
        boolean pendingSettle;

        void now(Desk d, ObjectNode turnRec) throws Exception {
            if (pendingSeq >= 0) {
                Event e = d.await(Desk.READY_TOPICS, pendingSeq, 3000);
                turnRec.put("readyEvent", e == null ? "none within 3 s" : e.topic());
            }
            if (pendingSettle || pendingSeq >= 0) {
                d.settle();
            }
            pendingSeq = -1;
            pendingSettle = false;
        }
    }

    /** What one action did. */
    record Outcome(boolean ok, String message, boolean engine, long seqBefore, boolean observation) {
    }

    /** {@code session --task lend|ladder --variant v2-toolset|v1-text --model sonnet --effort low --tag x}. */
    static int run(Map<String, String> o) throws Exception {
        String task = o.getOrDefault("task", "lend");
        String variant = o.getOrDefault("variant", "v2-toolset");
        String model = Grounding.model(o.getOrDefault("model", "sonnet"));
        String effort = o.getOrDefault("effort", "low");
        int maxActions = Integer.parseInt(o.getOrDefault("max-actions", "30"));
        int maxTurns = Integer.parseInt(o.getOrDefault("max-turns", "40"));
        BigDecimal sessionCap = new BigDecimal(o.getOrDefault("session-usd", "1.00"));
        String tag = o.getOrDefault("tag", task + "-" + variant + "-" + model);
        Model api = new Model(new BigDecimal(o.getOrDefault("max-usd", Bs12.HARD_CAP.toPlainString())));
        String sessionId = tag + "-" + Instant.now().toEpochMilli();
        boolean v2 = variant.equals("v2-toolset");
        try (Desk d = Desk.launch("session-" + tag)) {
            d.signOn();
            Object before = d.loan().get("id");
            Screen cur = Screen.take(d, "s0");
            Screen seen = cur;
            Settler settler = new Settler();
            int[] counters = new int[1];
            String hint = o.getOrDefault("hint", "none");
            boolean feedback = !"none".equals(o.getOrDefault("feedback", "hit"));
            String system = (v2 ? SYS_V2.formatted(cur.width(), cur.height()) : SYS_V1)
                    + ("legend".equals(hint) ? "\n\n" + LEGEND : "");
            ObjectNode body = Grounding.base(model, effort, 8192, system);
            ArrayNode tools = body.putArray("tools");
            if (v2) {
                tools.addObject().put("type", Grounding.TOOLSET);
            } else {
                tools.add(Grounding.tool("click", "Click a component (a button, a key, a field) by its ref.",
                        Map.of("ref", Map.of("type", "string")), List.of("ref")));
                tools.add(Grounding.tool("type_text", "Replace the text of a text field (by ref) with text; with enter "
                        + "true, press Enter in the field afterwards.",
                        Map.of("ref", Map.of("type", "string"), "text", Map.of("type", "string"),
                               "enter", Map.of("type", "boolean")), List.of("ref", "text", "enter")));
                tools.add(Grounding.tool("press_key", "Press one key where the keyboard focus is: a "
                        + "java.awt.event.KeyEvent VK_ name without the prefix, for example ENTER or ESCAPE.",
                        Map.of("key", Map.of("type", "string")), List.of("key")));
            }
            ArrayNode messages = body.putArray("messages");
            List<ObjectNode> first = new ArrayList<>();
            first.add(Grounding.text("Task: " + TASKS.get(task)));
            if (!v2) {
                first.add(Grounding.text("Text snapshot:\n" + cur.text()));
            }
            first.add(Grounding.image(cur.png));
            first.get(first.size() - 1).putObject("cache_control").put("type", "ephemeral");
            Grounding.userMessage(messages, first);

            String focus = null;
            int actions = 0;
            int observations = 0;
            int others = 0;
            int turns = 0;
            int stale = 0;
            int errors = 0;
            int maxCallsPerTurn = 0;
            long modelMs = 0;
            long actMs = 0;
            long settleMs = 0;
            long captureMs = cur.captureMs;
            int captures = 1;
            long in = 0;
            long out = 0;
            long cr = 0;
            long cw = 0;
            BigDecimal cost = BigDecimal.ZERO;
            String end = "running";
            List<String> memberLog = new ArrayList<>();
            List<String> stops = new ArrayList<>();
            while (true) {
                if (turns >= maxTurns) {
                    end = "turn limit";
                    break;
                }
                if (actions >= maxActions) {
                    end = "action limit";
                    break;
                }
                BigDecimal worst = Model.worstCase(body);
                if (cost.add(worst).compareTo(sessionCap) > 0) {
                    end = "session budget";
                    break;
                }
                Model.Call c;
                try {
                    c = api.send(body);
                } catch (Model.BudgetExceeded e) {
                    end = "spike budget: " + e.getMessage();
                    break;
                }
                turns++;
                modelMs += c.latencyMs();
                in += c.input();
                out += c.output();
                cr += c.cacheRead();
                cw += c.cacheWrite();
                cost = cost.add(c.cost());
                stops.add(c.stopReason());
                JsonNode content = c.message().path("content");
                ObjectNode assistant = messages.addObject();
                assistant.put("role", "assistant");
                assistant.set("content", content.deepCopy());
                List<JsonNode> uses = new ArrayList<>();
                StringBuilder said = new StringBuilder();
                for (JsonNode b : content) {
                    if ("tool_use".equals(b.path("type").asText())) {
                        uses.add(b);
                    } else if ("text".equals(b.path("type").asText())) {
                        said.append(b.path("text").asText()).append(' ');
                    }
                }
                maxCallsPerTurn = Math.max(maxCallsPerTurn, uses.size());
                ObjectNode turnRec = Bs12.MAPPER.createObjectNode();
                turnRec.put("kind", "session-turn");
                turnRec.put("session", sessionId);
                turnRec.put("task", task);
                turnRec.put("variant", variant);
                turnRec.put("model", model);
                turnRec.put("effort", effort);
                turnRec.put("turn", turns);
                turnRec.setAll(c.toJson());
                if (!said.isEmpty()) {
                    turnRec.put("text", Screen.clip(said.toString(), 300));
                }
                ArrayNode turnActions = turnRec.putArray("actions");
                if (uses.isEmpty()) {
                    end = "end_turn".equals(c.stopReason()) ? "model finished" : "stop " + c.stopReason();
                    Bs12.record("sessions", turnRec);
                    break;
                }
                List<ObjectNode> results = new ArrayList<>();
                boolean failed = false;
                long tAct = 0;
                long tSettle = 0;
                long tCapture = 0;
                // What the model saw last (its coordinates refer to it) and what it will have seen after this turn.
                Screen shown = seen;
                boolean lastObservation = false;
                for (JsonNode u : uses) {
                    String name = u.path("name").asText();
                    JsonNode input = u.path("input");
                    ObjectNode r = Bs12.MAPPER.createObjectNode();
                    r.put("type", "tool_result");
                    r.put("tool_use_id", u.path("id").asText());
                    if (v2) {
                        r.put("toolset_name", "computer");
                    }
                    ObjectNode act = turnActions.addObject();
                    act.put("name", name);
                    act.set("input", input.deepCopy());
                    memberLog.add(name);
                    if (failed) {
                        r.put("is_error", true);
                        r.put("content", "Not executed: an earlier action in this turn failed.");
                        act.put("result", "skipped");
                        results.add(r);
                        continue;
                    }
                    Outcome oc;
                    try {
                        if (v2 && ("screenshot".equals(name) || "zoom".equals(name))) {
                            // An observation in the middle of a batch is taken on a settled desk.
                            long t1 = System.nanoTime();
                            settler.now(d, turnRec);
                            tSettle += (System.nanoTime() - t1) / 1_000_000L;
                            if ("screenshot".equals(name)) {
                                long t2 = System.nanoTime();
                                shown = Screen.take(d, "shot" + turns);
                                tCapture += (System.nanoTime() - t2) / 1_000_000L;
                                captures++;
                                r.set("content", Bs12.MAPPER.createArrayNode().add(Grounding.image(shown.png)));
                                oc = new Outcome(true, "screenshot", false, -1, true);
                            } else {
                                JsonNode reg = input.path("region");
                                r.set("content", Bs12.MAPPER.createArrayNode().add(Grounding.image(shown.zoom(
                                        reg.path(0).asInt(), reg.path(1).asInt(), reg.path(2).asInt(), reg.path(3).asInt()))));
                                oc = new Outcome(true, "zoom", false, -1, true);
                            }
                        } else {
                            long t0 = System.nanoTime();
                            if (v2) {
                                String[] focusBox = {focus};
                                oc = actV2(d, seen, name, input, focusBox, r, counters);
                                focus = focusBox[0];
                            } else {
                                oc = actV1(d, seen, name, input);
                            }
                            tAct += (System.nanoTime() - t0) / 1_000_000L;
                        }
                    } catch (AgentRpcException e) {
                        oc = new Outcome(false, "error: " + Screen.clip(String.valueOf(e.getMessage()), 300), false, -1,
                                false);
                    }
                    act.put("result", oc.message());
                    lastObservation = oc.observation();
                    if (oc.observation()) {
                        observations++;
                    } else {
                        if (UI_ACTIONS.contains(name)) {
                            actions++;
                        } else {
                            others++;
                        }
                        settler.pendingSettle = true;
                    }
                    if (oc.message().startsWith("stale")) {
                        stale++;
                    }
                    if (oc.engine() && settler.pendingSeq < 0) {
                        settler.pendingSeq = oc.seqBefore();
                    }
                    if (!oc.ok()) {
                        failed = true;
                        errors++;
                        r.put("is_error", true);
                    }
                    if (!r.has("content")) {
                        // --feedback none: the model gets "OK" (or the error), not what the harness hit.
                        r.put("content", feedback || !oc.ok() ? oc.message() : "OK");
                    }
                    results.add(r);
                }
                actMs += tAct;
                List<ObjectNode> trailing = new ArrayList<>();
                if (!lastObservation) {
                    // The batch did not end with an observation: settle, capture, and attach the capture to the last
                    // result (an error result may only carry text, so then as blocks after the results).
                    long t1 = System.nanoTime();
                    settler.now(d, turnRec);
                    tSettle += (System.nanoTime() - t1) / 1_000_000L;
                    long t2 = System.nanoTime();
                    shown = Screen.take(d, "s" + turns);
                    tCapture += (System.nanoTime() - t2) / 1_000_000L;
                    captures++;
                    ObjectNode last = results.get(results.size() - 1);
                    List<ObjectNode> obs = new ArrayList<>();
                    if (!v2) {
                        obs.add(Grounding.text("Text snapshot:\n" + shown.text()));
                    }
                    obs.add(Grounding.image(shown.png));
                    if (last.path("is_error").asBoolean()) {
                        trailing.add(Grounding.text("The application window after your actions:"));
                        trailing.addAll(obs);
                    } else {
                        ArrayNode lc = Bs12.MAPPER.createArrayNode();
                        JsonNode prior = last.get("content");
                        if (prior != null && prior.isTextual()) {
                            lc.add(Grounding.text(prior.asText()));
                        } else if (prior != null && prior.isArray()) {
                            prior.forEach(lc::add);
                        }
                        obs.forEach(lc::add);
                        last.set("content", lc);
                    }
                }
                seen = shown;
                settleMs += tSettle;
                captureMs += tCapture;
                turnRec.put("actMs", tAct);
                turnRec.put("settleMs", tSettle);
                turnRec.put("captureMs", tCapture);
                for (JsonNode m : messages) {
                    for (JsonNode b : m.path("content")) {
                        if (b instanceof ObjectNode ob) {
                            ob.remove("cache_control");
                        }
                    }
                }
                List<ObjectNode> content2 = new ArrayList<>(results);
                content2.addAll(trailing);
                content2.get(content2.size() - 1).putObject("cache_control").put("type", "ephemeral");
                Grounding.userMessage(messages, content2);
                Bs12.record("sessions", turnRec);
                Bs12.log("turn %d: %s -> %s (model %d ms, act %d ms, settle %d ms, capture %d ms, USD %s)", turns,
                        memberLog.subList(memberLog.size() - uses.size(), memberLog.size()), turnActions.findValuesAsText("result"),
                        c.latencyMs(), tAct, tSettle, tCapture, c.cost().toPlainString());
            }
            // Verify through the desk's own facts.
            Map<String, Object> loan = d.loan();
            Map<String, Object> state = d.state();
            String[] exp = EXPECT.get(task);
            boolean posted = loan.get("id") != null && !loan.get("id").equals(before) && Boolean.FALSE.equals(loan.get("open"));
            List<?> lines = loan.get("lines") instanceof List<?> l ? l : List.of();
            boolean oneLine = lines.size() == 1 && lines.get(0) instanceof Map<?, ?> line
                    && exp[0].equals(line.get("code")) && new BigDecimal(String.valueOf(line.get("qty"))).compareTo(BigDecimal.ONE) == 0;
            boolean total = exp[1].equals(String.valueOf(loan.get("total")));
            boolean idle = "IDLE".equals(state.get("name"));
            boolean success = posted && oneLine && total && idle;
            ObjectNode rec = Bs12.MAPPER.createObjectNode();
            rec.put("kind", "session");
            rec.put("session", sessionId);
            rec.put("at", Instant.now().toString());
            rec.put("task", task);
            rec.put("variant", variant);
            rec.put("model", model);
            rec.put("effort", effort);
            rec.put("hint", hint);
            rec.put("feedback", feedback ? "hit" : "none");
            rec.put("tag", tag);
            rec.put("success", success);
            rec.put("end", end);
            rec.put("posted", posted);
            rec.put("oneLine", oneLine);
            rec.put("totalOk", total);
            rec.put("idle", idle);
            rec.put("loanTotal", String.valueOf(loan.get("total")));
            rec.put("loanLines", lines.size());
            rec.put("state", String.valueOf(state.get("name")));
            rec.put("actions", actions);
            rec.put("observations", observations);
            rec.put("otherCalls", others);
            rec.put("turns", turns);
            rec.put("stale", stale);
            rec.put("revalidated", counters[0]);
            rec.put("errors", errors);
            rec.put("maxCallsPerTurn", maxCallsPerTurn);
            rec.set("members", Bs12.MAPPER.valueToTree(memberLog));
            rec.set("stopReasons", Bs12.MAPPER.valueToTree(stops));
            rec.put("modelMs", modelMs);
            rec.put("actMs", actMs);
            rec.put("settleMs", settleMs);
            rec.put("captureMs", captureMs);
            rec.put("captures", captures);
            rec.put("input", in);
            rec.put("output", out);
            rec.put("cacheRead", cr);
            rec.put("cacheWrite", cw);
            long allIn = in + cr + cw;
            rec.put("cacheHitRatio", allIn == 0 ? 0 : (double) cr / allIn);
            rec.put("sessionCostUsd", cost.toPlainString());
            rec.put("targetJava", d.targetJava);
            Bs12.record("sessions", rec);
            Bs12.log("session %s: success=%s end=%s actions=%d turns=%d USD %s (model %d ms, act %d ms, settle %d ms, "
                    + "capture %d ms)", tag, success, end, actions, turns, cost.toPlainString(), modelMs, actMs, settleMs,
                    captureMs);
            return 0;
        }
    }

    // ------------------------------------------------------------------------------------------------ V2

    private static Outcome actV2(Desk d, Screen cur, String name, JsonNode input, String[] focus, ObjectNode result,
                                 int[] counters) throws Exception {
        switch (name) {
            case "left_click", "double_click", "triple_click" -> {
                JsonNode coord = input.path("coordinate");
                if (!coord.isArray() || coord.size() != 2) {
                    return new Outcome(false, "error: a click needs a coordinate", false, -1, false);
                }
                int x = coord.get(0).asInt();
                int y = coord.get(1).asInt();
                Screen.Hit h = cur.hitTest(x, y);
                if (!Screen.windowsKey(d).equals(cur.windowsKey)) {
                    // The set of windows changed since the capture the model saw (usually through its own earlier
                    // action in this batch): act only if the same component is still under the point.
                    Screen now = Screen.take(d, "revalidate");
                    Screen.Hit h2 = now.hitTest(x, y);
                    if (h.node() == null || h2.node() == null || !h.node().ref.equals(h2.node().ref) || h.row() != h2.row()) {
                        return new Outcome(false, "stale: the screen changed before the click and a different component "
                                + "is now under the point; nothing was clicked", false, -1, false);
                    }
                    counters[0]++;
                    h = h2;
                }
                if (h.node() == null || h.node().depth == 0) {
                    return new Outcome(false, "error: no component at (" + x + ", " + y + ")", false, -1, false);
                }
                Screen.Node n = h.node();
                RpcResult r = d.call("swing.click", Map.of("target", n.ref));
                boolean engine = !n.isText() && ("IconKey".equals(n.cls) || "PromptButton".equals(n.cls)
                        || "button".equals(n.role));
                if (n.isText()) {
                    focus[0] = n.ref;
                }
                return new Outcome(true, "OK (hit " + n.role + " \"" + Screen.clip(n.label(), 30) + "\" "
                        + n.ref + (h.row() >= 0 ? " row " + (h.row() + 1) : "") + ", " + ((Map<?, ?>) r.value()).get("rung")
                        + ")", engine, r.seqBefore(), false);
            }
            case "type" -> {
                String text = input.path("text").asText("");
                boolean enter = text.endsWith("\n");
                if (enter) {
                    text = text.substring(0, text.length() - 1);
                }
                String target = textTarget(d, cur, focus);
                if (target == null) {
                    return new Outcome(false, "error: no text field has the keyboard focus", false, -1, false);
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("target", target);
                p.put("text", text);
                p.put("clear", false);
                if (enter) {
                    p.put("enter", true);
                }
                RpcResult r = d.call("swing.type", p);
                return new Outcome(true, "OK (typed into " + target + ")", enter, r.seqBefore(), false);
            }
            case "key" -> {
                String k = input.path("text").asText("");
                String lower = k.toLowerCase(Locale.ROOT);
                if (lower.equals("return") || lower.equals("enter") || lower.equals("kp_enter")) {
                    String target = textTarget(d, cur, focus);
                    if (target != null) {
                        RpcResult r = d.call("swing.type", Map.of("target", target, "text", "", "clear", false,
                                "enter", true));
                        return new Outcome(true, "OK (Enter in " + target + ")", true, r.seqBefore(), false);
                    }
                }
                Map<String, Object> p = new LinkedHashMap<>();
                List<String> mods = new ArrayList<>();
                String[] parts = k.split("\\+");
                for (int i = 0; i < parts.length - 1; i++) {
                    mods.add(switch (parts[i].toLowerCase(Locale.ROOT)) {
                        case "ctrl", "control" -> "CTRL";
                        case "alt" -> "ALT";
                        case "shift" -> "SHIFT";
                        default -> "META";
                    });
                }
                p.put("key", vk(parts[parts.length - 1]));
                if (!mods.isEmpty()) {
                    p.put("modifiers", mods);
                }
                RpcResult r = d.call("swing.key", p);
                return new Outcome(true, "OK (key " + p.get("key") + ")", true, r.seqBefore(), false);
            }
            case "wait" -> {
                // No pause: the loop settles after the batch (and before any screenshot) anyway.
                return new Outcome(true, "OK", false, -1, false);
            }
            case "cursor_position" -> {
                return new Outcome(true, "unknown", false, -1, false);
            }
            default -> {
                return new Outcome(true, "OK (not performed by this harness: " + name + ")", false, -1, false);
            }
        }
    }

    /** The text component keyboard input goes to: the one last clicked, else the focus owner when it is one. */
    private static String textTarget(Desk d, Screen cur, String[] focus) throws Exception {
        if (focus[0] != null) {
            return focus[0];
        }
        Object owner = d.value("swing.edtState", Map.of()).get("focusOwner");
        if (owner != null) {
            Screen.Node n = cur.byRef(String.valueOf(owner));
            if (n != null && n.isText()) {
                focus[0] = n.ref;
                return n.ref;
            }
        }
        return null;
    }

    static String vk(String keysym) {
        String k = keysym.trim();
        return switch (k.toLowerCase(Locale.ROOT)) {
            case "return", "enter", "kp_enter" -> "ENTER";
            case "escape", "esc" -> "ESCAPE";
            case "tab" -> "TAB";
            case "backspace" -> "BACK_SPACE";
            case "delete" -> "DELETE";
            case "space" -> "SPACE";
            case "up" -> "UP";
            case "down" -> "DOWN";
            case "left" -> "LEFT";
            case "right" -> "RIGHT";
            case "home" -> "HOME";
            case "end" -> "END";
            default -> k.toUpperCase(Locale.ROOT);
        };
    }

    // ------------------------------------------------------------------------------------------------ V1

    private static Outcome actV1(Desk d, Screen cur, String name, JsonNode input) throws Exception {
        String ref = input.path("ref").asText(null);
        Screen.Node n = ref == null ? null : cur.byRef(ref);
        switch (name) {
            case "click" -> {
                if (n == null) {
                    return new Outcome(false, "error: no component with ref " + ref, false, -1, false);
                }
                RpcResult r = d.call("swing.click", Map.of("target", ref));
                boolean engine = "IconKey".equals(n.cls) || "PromptButton".equals(n.cls) || "button".equals(n.role);
                return new Outcome(true, "OK", engine, r.seqBefore(), false);
            }
            case "type_text" -> {
                if (n == null) {
                    return new Outcome(false, "error: no component with ref " + ref, false, -1, false);
                }
                boolean enter = input.path("enter").asBoolean(false);
                RpcResult r = d.call("swing.type", Map.of("target", ref, "text", input.path("text").asText(""),
                        "clear", true, "enter", enter));
                return new Outcome(true, "OK", enter, r.seqBefore(), false);
            }
            case "press_key" -> {
                RpcResult r = d.call("swing.key", Map.of("key", vk(input.path("key").asText(""))));
                return new Outcome(true, "OK", true, r.seqBefore(), false);
            }
            default -> {
                return new Outcome(false, "error: unknown tool " + name, false, -1, false);
            }
        }
    }
}
