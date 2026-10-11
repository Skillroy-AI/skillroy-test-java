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

package ai.skillroy.testjava.vision;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.session.SessionActions;
import ai.skillroy.testjava.inference.tools.CallPolicy;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * From a model's grounded action to the step that will be replayed (phase7-contracts.md 6.5, decisions D306-D308,
 * D342, D357, D358): the locator chosen from the hit-test's verified candidates (one the session's mask would change
 * never), what a model never does refused before any step is built (a masked target for every input, a credential's
 * secret, a known secret value, any typing while a credential is in flight), the step chosen by the target's role (a
 * {@code click} where the position does not matter, a {@code pointer} with fractions, a row or a region where it does,
 * an anchored {@code pointer} on the window where no locator exists), and the step acted through the session's facade
 * with its provenance, so that the consequence observed while recording is the recorded step's. V3 clicks, drags and
 * wheels run as {@code pointer {…, via: ROBOT}} (the facade lets the module's own V3 steps set it, D347) and are listed
 * live-only by the export. A {@code WEB} hit becomes {@code webClick {role, name}} when the page's hit-test proposes a
 * unique one in the frame the call started in and the hit's browser is the one the step resolves to, else a live-only
 * raw {@code web.pointer}; a page under a modal dialog is refused (Phase 7 security fixes S109, S130, S132). A middle
 * click and a clipboard chord are refused (S110), a V3 click with modifier keys too (S124), and the target's text in an
 * answer reaches the model inside a {@code <target-state>} block (S134).
 *
 * <p>Owned by one session's thread.
 */
public final class Recorder {

    /** The roles of a click whose position inside the component does not matter (6.5). */
    static final Set<String> POSITIONLESS = Set.of("button", "toggle", "checkbox", "radio", "menuitem");
    /** The classes whose click position always matters (6.5, D306: no new role for them). */
    static final Set<String> POSITIONAL_CLASSES = Set.of("javax.swing.JSlider", "javax.swing.JSpinner",
            "javax.swing.JScrollBar");
    /** The locator keys of the base grammar; any other key is a pack's {@code describe} strategy. */
    static final Set<String> BASE_LOCATOR_KEYS = Set.of("id", "name", "role", "text", "class", "index", "path",
            "regex", "ignoreCase", "within");
    /** The refusal of an input into a masked component (D358). */
    public static final String MASKED = "the component takes a secret; ask the person to sign on";
    /** The refusal of a credential on a model's behalf (D342). */
    public static final String CREDENTIAL = "a credential is never used on a model's behalf; the person signs on first";
    /** The refusal of typing while a credential is in flight (D342). */
    public static final String IN_FLIGHT = "a credential is in flight; the person signs on first";
    /** The answer of V3 when the target holds no focused window (D344). */
    public static final String FOCUS_LOST = "the target lost the focus; bring it to the front and ask again";
    /**
     * The refusal of a clipboard chord or a middle click (phase7-contracts.md 11, the clipboard rule; Phase 7 security
     * fix S110): the person's clipboard and the system selection are a person's.
     */
    public static final String CLIPBOARD = "that would paste, copy or cut through the person's clipboard or the system"
            + " selection, which is never done on a model's behalf; type the text instead";
    /** The refusal of a V3 click with modifier keys (phase7-contracts.md 3.4; Phase 7 security fix S124). */
    public static final String V3_MODIFIERS = "V3 cannot hold a key while it clicks; click without it, or ask the"
            + " person";
    /** The refusal of a click on a page under a modal dialog (phase7-contracts.md 6.5 step 4; security fix S109). */
    public static final String PAGE_BLOCKED = "a modal dialog blocks the page; answer the dialog first";
    /** A {@code $slot.key} reference. */
    private static final Pattern REFERENCE = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_-]*)\\.([A-Za-z0-9_-]+)");
    /** The side of an anchor crop around the point (6.5). */
    static final int ANCHOR_SIDE = 64;

    /** What one recorded action gave: the step it ran (or null), the text the model reads, an error, a focus loss. */
    public record Recorded(StepOutcome step, String text, boolean error, boolean focusLost) {

        static Recorded refused(String text) {
            return new Recorded(null, text, true, false);
        }
    }

    /** How a grounded pointer action acts. */
    public sealed interface Gesture {
    }

    /** A click with a button, a count and modifiers. */
    public record Click(String button, int count, List<String> modifiers) implements Gesture {

        /** Copies the modifiers. */
        public Click {
            modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        }
    }

    /** A drag to a pixel of the same capture. */
    public record Drag(int toX, int toY) implements Gesture {
    }

    /** A wheel of notches (positive: towards the user, down). */
    public record Wheel(int notches) implements Gesture {
    }

    private final SessionActions actions;
    private final PromptMask mask;
    private final Captures captures;
    private VisionTier tier;
    private final Grounding grounding;
    private Map<String, Object> rendering;
    /** Each anchored window's insets, read once per session (L1-F4). */
    private final Map<String, Map<String, Object>> windowInsets = new LinkedHashMap<>();
    private int anchors;

    /**
     * @param actions the session's facade
     * @param mask the session's mask (a locator it would change is never chosen; a known secret value is refused)
     * @param captures where the acted-on captures and the anchor crops are kept
     * @param tier the session's tier (V3 acts through Robot)
     */
    public Recorder(SessionActions actions, PromptMask mask, Captures captures, VisionTier tier) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.mask = Objects.requireNonNull(mask, "mask");
        this.captures = captures == null ? Captures.none() : captures;
        this.tier = Objects.requireNonNull(tier, "tier");
        this.grounding = new Grounding(actions);
    }

    /** Acts as another tier from now on (a scenario step narrowing V3 to V2 or the reverse, 6.8). */
    void tier(VisionTier newTier) {
        this.tier = Objects.requireNonNull(newTier, "tier");
    }

    // ------------------------------------------------------------------------------------------------ pointer

    /**
     * Records and acts one grounded pointer action.
     *
     * @param grounded the capture and the hit-test of the point
     * @param x the point in the capture's pixels
     * @param y the point in the capture's pixels
     * @param gesture what to do there
     * @param mark the V1 mark the model named, or null
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Recorded pointer(Grounding.Grounded grounded, int x, int y, Gesture gesture, Integer mark)
            throws AgentLostException, InterruptedException {
        Map<String, Object> hit = grounded.hit();
        String surface = str(hit.get("surface"));
        if (gesture instanceof Click c && CallPolicy.middleButton(c.button())) {
            // Phase 7 security fix S110: a middle click pastes the system selection where the platform has one.
            return Recorded.refused(CLIPBOARD);
        }
        if (tier == VisionTier.V3 && gesture instanceof Click c && !c.modifiers().isEmpty()) {
            // Phase 7 security fix S124: the Robot path holds no key; a modified click is refused, never pressed plain.
            return Recorded.refused(V3_MODIFIERS);
        }
        // D358 for every surface (fix wave FC, L2-F2): a masked target is refused before the web branch, so that a page
        // a pack masked is never hit-tested and none of its text enters a step, the journal or the export.
        if (Boolean.TRUE.equals(hit.get("masked"))) {
            return Recorded.refused(MASKED);
        }
        if ("WEB".equals(surface)) {
            return web(grounded, x, y, gesture, mark);
        }
        if ("NONE".equals(surface) || hit.get("target") == null) {
            return Recorded.refused("nothing at that point can be grounded (no component of the application lies"
                    + " there)");
        }
        Map<String, Object> node = node(hit);
        String role = str(node.get("role"));
        String className = str(node.get("class"));
        Map<String, Object> locator = locator(hit);
        Map<String, Object> region = map(hit.get("region"));
        Integer row = hit.get("row") instanceof Number n ? n.intValue() : null;
        Map<String, Object> provenance = provenance(grounded.capture(), x, y, hit, locator, mark);
        if (locator == null && region.isEmpty() || "HEAVYWEIGHT".equals(surface)) {
            return anchored(grounded, x, y, gesture, provenance);
        }
        if (locator == null) {
            return Recorded.refused("the component at that point has no locator that names it alone; nothing was"
                    + " recorded");
        }
        boolean positionless = role != null && POSITIONLESS.contains(role) || Boolean.TRUE.equals(hit.get("fires"))
                || describeStrategy(locator);
        boolean positional = region.get("id") != null || row != null || className != null
                && POSITIONAL_CLASSES.contains(className);
        Map<String, Object> args = new LinkedHashMap<>();
        String verb;
        if (tier != VisionTier.V3 && gesture instanceof Click c && positionless && !positional
                && "LEFT".equals(c.button()) && c.count() == 1 && c.modifiers().isEmpty()) {
            verb = "click";
            args.put("by", locator);
        } else {
            verb = "pointer";
            args.put("by", locator);
            if (region.get("id") instanceof String id) {
                args.put("region", id);
            }
            if (row != null) {
                args.put("row", (long) row);
            }
            args.put("at", fractions(hit, region, row != null));
            gesture(args, gesture, hit, grounded.capture(), x, y);
        }
        if (tier == VisionTier.V3) {
            args.put("via", "ROBOT");
            provenance.put("via", "ROBOT");
        } else {
            provenance.put("via", "ROUTED");
        }
        return act(verb, args, provenance);
    }

    /**
     * The node the hit-test's {@code target} is about. The point form: its mouse target, else the deepest component.
     * The target form ({@code click_ref}, the focus owner) names the component itself (fix wave FC, L1-F5): the agent's
     * {@code targetNode}; from an agent that does not answer it, the mouse target or the deepest component at its
     * centre when either is that component, else the mouse target (a composite's listener is usually the component, a
     * label drawn at its centre is not), else the deepest.
     */
    static Map<String, Object> node(Map<String, Object> hit) {
        if ("TARGET".equals(hit.get("targetKind"))) {
            Map<String, Object> own = map(hit.get("targetNode"));
            if (!own.isEmpty()) {
                return own;
            }
            Object target = hit.get("target");
            Map<String, Object> mouse = map(hit.get("mouseTarget"));
            Map<String, Object> deepest = map(hit.get("deepest"));
            if (target != null && target.equals(mouse.get("ref"))) {
                return mouse;
            }
            if (target != null && target.equals(deepest.get("ref"))) {
                return deepest;
            }
            return mouse.isEmpty() ? deepest : mouse;
        }
        Map<String, Object> node = "MOUSE_TARGET".equals(hit.get("targetKind")) ? map(hit.get("mouseTarget"))
                : map(hit.get("deepest"));
        return node.isEmpty() ? map(hit.get("deepest")) : node;
    }

    /** The roles whose {@code text} is the component's content, not its caption (Phase 7 security fixes S106, S146). */
    static final Set<String> CONTENT_ROLES = Set.of("textbox", "text", "password");

    /**
     * The first of the hit-test's verified locators that the session's mask would not change (6.5 step 1: a locator
     * whose value is a joined credential is never chosen), or null. Phase 7 security fix, S146's rule on the recorder's
     * side (SG's handover): a {@code {text, …}} candidate of a component whose role is {@code textbox}, {@code text} or
     * {@code password} is never chosen either, since that text is the field's content (what a person or a model typed,
     * which the step would carry into the journal, the export and a committed scenario, and which changes with the
     * next {@code type}); the {@code {class, index}} or {@code {path}} candidate names such a field instead.
     */
    Map<String, Object> locator(Map<String, Object> hit) {
        if (!(hit.get("locators") instanceof List<?> list)) {
            return null;
        }
        boolean content = CONTENT_ROLES.contains(str(node(hit).get("role")));
        for (Object l : list) {
            if (content && l instanceof Map<?, ?> m && m.containsKey("text")) {
                continue;
            }
            if (l instanceof Map<?, ?> m && !m.isEmpty() && safe(m)) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                return copy;
            }
        }
        return null;
    }

    private boolean safe(Map<?, ?> locator) {
        for (Object value : locator.values()) {
            if (value instanceof String s && !s.equals(mask.text(s))) {
                return false;
            }
        }
        return true;
    }

    /** True for a locator in a pack's own vocabulary ({@code {action: DUE}}): a key outside the base grammar. */
    static boolean describeStrategy(Map<String, Object> locator) {
        for (String key : locator.keySet()) {
            if (!BASE_LOCATOR_KEYS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** The point as fractions of the component's, the region's or (for a row) the component's width. */
    private static Map<String, Object> fractions(Map<String, Object> hit, Map<String, Object> region, boolean row) {
        Map<String, Object> relative = map(hit.get("relative"));
        double fx = relative.get("fx") instanceof Number n ? n.doubleValue() : 0.5;
        double fy = relative.get("fy") instanceof Number n ? n.doubleValue() : 0.5;
        Map<String, Object> local = map(hit.get("local"));
        Capture.Rect rect = Capture.Rect.of(region.get("rect"));
        if (rect != null && rect.w() > 0 && rect.h() > 0 && local.get("x") instanceof Number lx
                && local.get("y") instanceof Number ly) {
            fx = (lx.doubleValue() - rect.x()) / rect.w();
            fy = (ly.doubleValue() - rect.y()) / rect.h();
        }
        if (row) {
            fy = 0.5;
        }
        return Map.of("fx", round(fx), "fy", round(fy));
    }

    /** Adds the gesture's arguments to a {@code pointer} step. */
    private static void gesture(Map<String, Object> args, Gesture gesture, Map<String, Object> hit, Capture capture,
                                int x, int y) {
        switch (gesture) {
            case Click c -> {
                args.put("action", c.count() >= 2 ? "DOUBLE_CLICK" : "CLICK");
                if (!"LEFT".equals(c.button())) {
                    args.put("button", c.button());
                }
                if (c.count() > 2) {
                    args.put("clickCount", (long) c.count());
                }
                if (!c.modifiers().isEmpty()) {
                    args.put("modifiers", c.modifiers());
                }
            }
            case Drag d -> {
                args.put("action", "DRAG");
                Map<String, Object> node = node(hit);
                Capture.Rect bounds = Capture.Rect.of(node.get("bounds"));
                Map<String, Object> local = map(hit.get("local"));
                double scale = capture.scale() <= 0 ? 1.0 : capture.scale();
                if (bounds != null && bounds.w() > 0 && bounds.h() > 0 && local.get("x") instanceof Number lx
                        && local.get("y") instanceof Number ly) {
                    double tx = (lx.doubleValue() + (d.toX() - x) / scale) / bounds.w();
                    double ty = (ly.doubleValue() + (d.toY() - y) / scale) / bounds.h();
                    args.put("to", Map.of("fx", round(tx), "fy", round(ty)));
                } else {
                    args.put("to", args.get("at"));
                }
            }
            case Wheel w -> {
                args.put("action", "WHEEL");
                args.put("wheel", Map.of("notches", (long) w.notches()));
            }
        }
    }

    private static Object round(double value) {
        return BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, value))).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------------------------------------------ anchored

    /**
     * An anchored step (6.5, D357): {@code pointer {by: <the window's locator>, at, guard: <label>}}, the anchor taken
     * with the guard's own call (a 64 x 64 {@code REGION} capture of the window's root pane around the point, redacted)
     * and kept with the rendering it was made under, so that the export can write the bindings entry.
     */
    private Recorded anchored(Grounding.Grounded grounded, int x, int y, Gesture gesture,
                              Map<String, Object> provenance) throws AgentLostException, InterruptedException {
        Map<String, Object> hit = grounded.hit();
        String window = str(hit.get("window"));
        Capture.Rect windowRect = Capture.Rect.of(hit.get("windowRect"));
        if (window == null || windowRect == null) {
            return Recorded.refused("the point lies in no window that can be recorded");
        }
        Map<String, Object> described;
        try {
            described = grounding.describe(window);
        } catch (AgentRpcException e) {
            return agentRefused("the window cannot be described", e);
        }
        Map<String, Object> windowLocator = locator(described);
        if (windowLocator == null) {
            return Recorded.refused("nothing at that point has a locator, and neither has its window; nothing was"
                    + " recorded");
        }
        Capture capture = grounded.capture();
        double scale = capture.scale() <= 0 ? 1.0 : capture.scale();
        int screenX = (int) Math.round(capture.originX() + x / scale);
        int screenY = (int) Math.round(capture.originY() + y / scale);
        int wx = screenX - windowRect.x();
        int wy = screenY - windowRect.y();
        int cx = Math.max(0, Math.min(windowRect.w() - ANCHOR_SIDE, wx - ANCHOR_SIDE / 2));
        int cy = Math.max(0, Math.min(windowRect.h() - ANCHOR_SIDE, wy - ANCHOR_SIDE / 2));
        Capture.Rect crop = new Capture.Rect(cx, cy, Math.min(ANCHOR_SIDE, windowRect.w()),
                Math.min(ANCHOR_SIDE, windowRect.h()));
        Capture anchor;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("target", window);
            params.put("scope", "REGION");
            params.put("region", crop.json());
            params.put("redact", true);
            anchor = Capture.of(actions.read("swing.capture", params));
        } catch (AgentRpcException e) {
            return agentRefused("the anchor could not be captured", e);
        } catch (IllegalArgumentException e) {
            return Recorded.refused("the anchor could not be captured: " + e.getMessage());
        }
        Map<String, Object> rendering;
        try {
            rendering = rendering();
        } catch (AgentRpcException e) {
            return agentRefused("the rendering could not be read", e);
        }
        String file;
        try {
            file = captures.write(anchor.hash(), anchor.png());
        } catch (IOException | IllegalArgumentException e) {
            file = null;
        }
        String label = "anchor-s" + (++anchors);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("label", label);
        a.put("rect", crop.json());
        a.put("x", (long) crop.x());
        a.put("y", (long) crop.y());
        a.put("sha256", anchor.hash());
        a.put("width", (long) anchor.width());
        a.put("height", (long) anchor.height());
        if (file != null) {
            a.put("file", file);
        }
        Map<String, Object> keyed = new LinkedHashMap<>(rendering);
        Object renderKey = keyed.remove("renderKey");
        keyed.remove("keyed");
        a.put("renderKey", renderKey);
        a.put("rendering", keyed);
        a.put("window", windowFacts(window, windowRect));
        List<Object> masks = new ArrayList<>();
        for (Capture.Rect r : anchor.redacted()) {
            masks.add(r.json());
        }
        a.put("masks", masks);
        List<Object> carets = new ArrayList<>();
        for (Capture.Rect r : anchor.carets()) {
            carets.add(r.json());
        }
        a.put("carets", carets);
        Map<String, Object> focus = new LinkedHashMap<>();
        focus.put("owner", anchor.focus().get("ownerLocator"));
        if (anchor.focus().get("ownerRect") != null) {
            focus.put("ownerRect", anchor.focus().get("ownerRect"));
        }
        focus.put("active", Boolean.TRUE.equals(anchor.focus().get("active")));
        a.put("focus", focus);
        provenance.put("anchor", a);
        provenance.put("via", tier == VisionTier.V3 ? "ROBOT" : "ROUTED");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("by", windowLocator);
        args.put("at", Map.of("fx", round((double) wx / Math.max(1, windowRect.w())), "fy",
                round((double) wy / Math.max(1, windowRect.h()))));
        gesture(args, gesture, Map.of(), capture, x, y);
        if (gesture instanceof Drag d) {
            // Fix wave FC (L1-F3): the end point mapped to the window's fractions as the start is, not the start again.
            int tx = (int) Math.round(capture.originX() + d.toX() / scale) - windowRect.x();
            int ty = (int) Math.round(capture.originY() + d.toY() / scale) - windowRect.y();
            args.put("to", Map.of("fx", round((double) tx / Math.max(1, windowRect.w())), "fy",
                    round((double) ty / Math.max(1, windowRect.h()))));
        }
        args.put("guard", label);
        if (tier == VisionTier.V3) {
            args.put("via", "ROBOT");
        }
        return act("pointer", args, provenance);
    }

    /**
     * The window's size and insets for the anchor's entry (8.7). The insets are the root pane's position in the window
     * (fix wave FC, L1-F4): a {@code WINDOW} capture's {@code origin} (3.1), read once per window per session at the
     * smallest scale (the pixels are not used), redacted as every capture; {@code {0, 0}} when that read fails, since
     * the insets are the reviewer's information and the guard does not depend on them (6.7).
     */
    private Map<String, Object> windowFacts(String window, Capture.Rect windowRect)
            throws AgentLostException, InterruptedException {
        Map<String, Object> insets = windowInsets.get(window);
        if (insets == null) {
            long top = 0;
            long left = 0;
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("target", window);
                params.put("scope", "WINDOW");
                params.put("scale", INSETS_SCALE);
                params.put("redact", true);
                Map<String, Object> origin = map(Grounding.map(actions.read("swing.capture", params)).get("origin"));
                if (origin.get("x") instanceof Number ox && origin.get("y") instanceof Number oy) {
                    left = Math.max(0, Math.round(ox.doubleValue()));
                    top = Math.max(0, Math.round(oy.doubleValue()));
                }
            } catch (AgentRpcException | IllegalArgumentException e) {
                // informative only: {0, 0}
            }
            insets = Map.of("top", top, "left", left);
            windowInsets.put(window, insets);
        }
        return Map.of("size", Map.of("w", (long) windowRect.w(), "h", (long) windowRect.h()), "insets", insets);
    }

    /** The scale of the read that finds a window's insets: the least the capture accepts (3.1). */
    static final double INSETS_SCALE = 0.25;

    /** {@code swing.rendering}, read once per session (cached). */
    private Map<String, Object> rendering() throws AgentRpcException, AgentLostException, InterruptedException {
        if (rendering == null) {
            rendering = new LinkedHashMap<>(Grounding.map(actions.read("swing.rendering", Map.of())));
        }
        return rendering;
    }

    // ------------------------------------------------------------------------------------------------ web

    private Recorded web(Grounding.Grounded grounded, int x, int y, Gesture gesture, Integer mark)
            throws AgentLostException, InterruptedException {
        Map<String, Object> hit = grounded.hit();
        String browser = str(hit.get("browser"));
        Map<String, Object> local = map(hit.get("surfaceLocal"));
        if (browser == null || local.isEmpty()) {
            return Recorded.refused("the page at that point cannot be grounded");
        }
        if (redactedPage(grounded.capture(), hit)) {
            // The capture kept this page grey (a mask names it, 3.6, 4.8): the same as masked (L2-F2).
            return Recorded.refused(MASKED);
        }
        if (hit.get("blockedBy") != null) {
            // Phase 7 security fix S109 (6.5 step 4; D294, D335): a page under a modal dialog, in-window dialogs
            // included, takes no click of a model's, as the routed pointer refuses it; nothing is read or sent.
            return Recorded.refused(PAGE_BLOCKED);
        }
        if (!(gesture instanceof Click c) || !"LEFT".equals(c.button()) || c.count() != 1) {
            return Recorded.refused("only a left click is recorded on a page");
        }
        Map<String, Object> page;
        try {
            page = Grounding.map(actions.read("web.hitTest", Map.of("browser", browser, "point", local, "space",
                    "COMPONENT")));
        } catch (AgentRpcException e) {
            return agentRefused("the page's hit-test failed", e);
        }
        Map<String, Object> provenance = provenance(grounded.capture(), x, y, hit, null, mark);
        provenance.put("via", "ROUTED");
        String liveOnly = page.get("iframe") != null ? CHILD_FRAME : null;
        if (liveOnly == null && !resolvesTo(browser, page)) {
            liveOnly = OTHER_BROWSER;
        }
        if (liveOnly == null && Boolean.TRUE.equals(page.get("unique")) && page.get("locators") instanceof List<?> ls) {
            for (Object l : ls) {
                if (l instanceof Map<?, ?> m && m.get("role") instanceof String role && m.get("name") instanceof String n
                        && n.equals(mask.text(n))) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("role", role);
                    args.put("name", n);
                    return act("webClick", args, provenance);
                }
            }
        }
        if (liveOnly != null) {
            provenance.put("liveOnly", liveOnly);
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("browser", browser);
        raw.put("point", local);
        raw.put("space", "COMPONENT");
        raw.put("action", "CLICK");
        return raw(new PlannedCall("web.pointer", raw, Sync.SETTLE, 10_000L), provenance);
    }

    /** The reason a page's hit found in a child frame is acted live-only (phase7-contracts.md 6.5 step 4; S130). */
    static final String CHILD_FRAME = "the element lies in a child frame; a frame key is a per-load handle, so the click"
            + " is acted as a live-only web.pointer";
    /** The reason a hit in a browser the step would not resolve to is acted live-only (6.5 step 4; S132). */
    static final String OTHER_BROWSER = "the page lies in a browser a recorded webClick would not reach (another"
            + " browser is the session's default or also showing), so the click is acted as a live-only web.pointer";

    /** True when the capture kept the hit's page grey (a mask names it, 3.6, 4.8). */
    static boolean redactedPage(Capture capture, Map<String, Object> hit) {
        String browser = str(hit.get("browser"));
        for (Capture.Surface s : capture.surfaces()) {
            if (s.redacted() && (browser != null && browser.equals(s.browser())
                    || s.ref() != null && s.ref().equals(hit.get("target")))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a recorded {@code webClick} without {@code browser} would act in the hit's browser (phase7-contracts.md
     * 6.5 step 4; Phase 7 security fix S132): with a default browser in the session's web settings, the browser that
     * selection names now ({@code web.hitTest} at the hit's viewport point with that {@code by}, the agent's own
     * selection rule) is the hit's; without one, the hit's browser is the only showing one ({@code web.browsers}).
     * A read that fails answers false (the click is then acted live-only).
     */
    private boolean resolvesTo(String browser, Map<String, Object> page) throws AgentLostException,
            InterruptedException {
        Map<String, Object> defaultBrowser = actions.webDefaultBrowser();
        try {
            if (defaultBrowser != null && !defaultBrowser.isEmpty()) {
                Map<String, Object> point = map(page.get("viewportPoint"));
                if (point.isEmpty()) {
                    return false;
                }
                Map<String, Object> selected = Grounding.map(actions.read("web.hitTest", Map.of("by",
                        new LinkedHashMap<>(defaultBrowser), "point", point)));
                return browser.equals(selected.get("browser"));
            }
            Object listed = Grounding.map(actions.read("web.browsers", Map.of())).get("browsers");
            int showing = 0;
            boolean hitShowing = false;
            for (Object b : listed instanceof List<?> l ? l : List.of()) {
                Map<String, Object> entry = map(b);
                if (Boolean.TRUE.equals(entry.get("visibleInSwing")) && !Boolean.TRUE.equals(entry.get("closed"))) {
                    showing++;
                    hitShowing |= browser.equals(entry.get("ref"));
                }
            }
            return showing == 1 && hitShowing;
        } catch (AgentRpcException | IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ keyboard

    /**
     * Records and acts a {@code type} at the focus owner (6.5): {@code type {by: <the focus owner's locator>, text,
     * enter?}}, refused for a masked focus owner, a credential reference, a known secret value and while a credential
     * is in flight.
     *
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Recorded type(String text, boolean enter) throws AgentLostException, InterruptedException {
        if (actions.secretsInFlight()) {
            return Recorded.refused(IN_FLIGHT);
        }
        if (namesSecret(text) || mask.holdsSecret(text)) {
            return Recorded.refused(CREDENTIAL);
        }
        Focus focus = focus();
        if (focus.lost != null) {
            return focus.lost;
        }
        Map<String, Object> hit = focus.hit;
        if (Boolean.TRUE.equals(hit.get("masked"))) {
            return Recorded.refused(MASKED);
        }
        Map<String, Object> locator = locator(hit);
        if (locator == null) {
            return Recorded.refused("the component with the focus has no locator that names it alone; click into a"
                    + " field first");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("by", locator);
        args.put("text", text);
        if (enter) {
            args.put("enter", true);
        }
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("tier", tier.name());
        provenance.put("via", "ROUTED");
        provenance.put("hitTest", hitFacts(hit, locator, null));
        return act("type", args, provenance);
    }

    /**
     * Records and acts a {@code press} at the focus owner (6.5), {@code repeat} times; refused for a masked focus owner
     * (D358) and, for a printable key, while a credential is in flight.
     *
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Recorded key(String key, List<String> modifiers, int repeat) throws AgentLostException,
            InterruptedException {
        if (CallPolicy.clipboardKey(key, modifiers)) {
            // Phase 7 security fix S110: refused before anything is read or grounded.
            return Recorded.refused(CLIPBOARD);
        }
        Focus focus = focus();
        if (focus.lost != null) {
            return focus.lost;
        }
        if (focus.hit != null && Boolean.TRUE.equals(focus.hit.get("masked"))) {
            return Recorded.refused(MASKED);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", key);
        if (modifiers != null && !modifiers.isEmpty()) {
            args.put("modifiers", modifiers);
        }
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("tier", tier.name());
        provenance.put("via", "ROUTED");
        Recorded last = null;
        for (int i = 0; i < Math.max(1, repeat); i++) {
            last = act("press", args, provenance);
            if (last.error()) {
                return last;
            }
        }
        return last;
    }

    /** The focus owner as the agent describes it, or the answer when there is none. */
    private record Focus(Map<String, Object> hit, Recorded lost) {
    }

    private Focus focus() throws AgentLostException, InterruptedException {
        Object owner;
        try {
            owner = Grounding.map(actions.read("swing.edtState", Map.of())).get("focusOwner");
        } catch (AgentRpcException e) {
            return new Focus(null, agentRefused("the focus cannot be read", e));
        }
        if (!(owner instanceof String ref)) {
            // D344: in V3 the turn stops when the target JVM holds no focused window.
            return new Focus(null, tier == VisionTier.V3 ? new Recorded(null, FOCUS_LOST, true, true)
                    : Recorded.refused("nothing has the focus; click into a field first"));
        }
        try {
            return new Focus(grounding.describe(ref), null);
        } catch (AgentRpcException e) {
            return new Focus(null, agentRefused("the focus owner cannot be described", e));
        }
    }

    /** True when the text names a slot's field whose key looks secret ({@code $staff.password}). */
    boolean namesSecret(String text) {
        if (text == null) {
            return false;
        }
        Matcher m = REFERENCE.matcher(text);
        while (m.find()) {
            if (mask.secretKey(m.group(2))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ acting

    /**
     * A live-only raw call (V2's hover and button halves, a page's raw pointer) through the facade.
     *
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Recorded raw(PlannedCall call, Map<String, Object> provenance)
            throws AgentLostException, InterruptedException {
        try {
            StepOutcome step = actions.execute(call, provenance);
            return outcome(call.primitive(), step);
        } catch (IllegalArgumentException e) {
            return Recorded.refused(e.getMessage());
        }
    }

    private Recorded act(String verb, Map<String, Object> args, Map<String, Object> provenance)
            throws AgentLostException, InterruptedException {
        try {
            StepOutcome step = actions.execute(verb, args, provenance);
            return outcome(verb, step);
        } catch (IllegalArgumentException e) {
            return Recorded.refused(e.getMessage());
        }
    }

    private Recorded outcome(String verb, StepOutcome step) {
        StringBuilder text = new StringBuilder(verb).append(": ").append(step.status());
        if (step.message() != null) {
            // Phase 7 security fix S134 (6.2 step 3): a step's message carries the target's text (a dialog's title and
            // text, a locator's component text, an agent's error), so it reaches the model inside <target-state>.
            text.append('\n').append(targetText(step.message()));
        }
        boolean focusLost = tier == VisionTier.V3 && !step.succeeded() && String.valueOf(step.message())
                .contains("not focused");
        return new Recorded(step, focusLost ? FOCUS_LOST : text.toString(), !step.succeeded(), focusLost);
    }

    // ------------------------------------------------------------------------------------------------ provenance

    /** The provenance of 9.2 for a grounded action; the capture is written to the captures directory here. */
    Map<String, Object> provenance(Capture capture, int x, int y, Map<String, Object> hit,
                                   Map<String, Object> locator, Integer mark) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tier", tier.name());
        String file;
        try {
            file = captures.write(capture.hash(), capture.png());
        } catch (IOException | IllegalArgumentException e) {
            file = null;
        }
        p.put("capture", capture.provenance(file));
        if (x >= 0 && y >= 0) {
            p.put("point", Map.of("x", (long) x, "y", (long) y));
        }
        p.put("hitTest", hitFacts(hit, locator, mark));
        return p;
    }

    private static Map<String, Object> hitFacts(Map<String, Object> hit, Map<String, Object> locator, Integer mark) {
        Map<String, Object> node = node(hit);
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("ref", hit.get("target"));
        if (node.get("name") != null) {
            h.put("name", node.get("name"));
        }
        if (node.get("class") != null) {
            h.put("className", node.get("class"));
        }
        if (node.get("role") != null) {
            h.put("role", node.get("role"));
        }
        if (locator != null) {
            h.put("locator", locator);
        }
        if (hit.get("relative") instanceof Map<?, ?> relative) {
            h.put("relative", relative);
        }
        if (hit.get("region") != null) {
            h.put("region", hit.get("region"));
        }
        if (hit.get("row") != null) {
            h.put("row", hit.get("row"));
        }
        if (mark != null) {
            h.put("mark", (long) mark);
        }
        return h;
    }

    /**
     * Text that came from the target (an agent's error, a step's message) as the model reads it: inside a
     * {@code <target-state>} block through the session's mask, a label tag in it neutralised (phase7-contracts.md 6.2
     * step 3; Phase 7 security fix S134). The module's own refusal texts stay bare.
     */
    String targetText(String text) {
        return mask.block("target-state", text == null ? "" : text);
    }

    /** A refusal whose cause is an agent's error: the module's own words, then the agent's text in its block. */
    private Recorded agentRefused(String what, AgentRpcException e) {
        return Recorded.refused(what + ":\n" + targetText(e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static String str(Object value) {
        return value instanceof String s ? s : null;
    }
}
