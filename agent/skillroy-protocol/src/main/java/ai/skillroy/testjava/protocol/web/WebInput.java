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

package ai.skillroy.testjava.protocol.web;

import ai.skillroy.testjava.protocol.text.TextBounds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What both web paths share for {@code web.hitTest} and {@code web.pointer} (phase7-contracts.md sections 4.2 and 4.3):
 * the reading of their parameters (so that a wrong one is the same {@code INVALID_PARAMS} with {@code data.param} on
 * either path), the plan of protocol events an action sends, the parameters of {@code Input.dispatchMouseEvent} (so
 * that the agent's {@code jcef} adapter and the CDP path send byte-identical parameters, D335), the receiving side's
 * reading of the script library's {@code hit} value (bounded again, S43: a hit's role, name, tag, id, rectangle and
 * locators are page-chosen data), the rule that finds the child frame of a frame element, and the two result shapes
 * (D48). Java 8; no dependency beyond the JDK and this module.
 *
 * <p>Coordinates are whole CSS pixels of the main frame's layout viewport (or, for {@code space: COMPONENT}, pixels of
 * the browser's Swing component, which the agent converts); a fractional coordinate is rounded.
 *
 * <p>Stateless; safe from any thread.
 */
public final class WebInput {

    /** The protocol method both paths send. */
    public static final String MOUSE_EVENT = "Input.dispatchMouseEvent";
    /** {@code web.pointer}'s actions. */
    public static final List<String> ACTIONS = Collections.unmodifiableList(Arrays.asList("MOVE", "PRESS", "RELEASE",
            "CLICK", "DOUBLE_CLICK", "WHEEL"));
    /** {@code web.pointer}'s buttons. */
    public static final List<String> BUTTONS = Collections.unmodifiableList(Arrays.asList("LEFT", "MIDDLE", "RIGHT"));
    /** {@code web.pointer}'s modifiers, in the order of their protocol bits (1, 2, 4, 8). */
    public static final List<String> MODIFIERS = Collections.unmodifiableList(Arrays.asList("ALT", "CTRL", "META",
            "SHIFT"));
    /** The point's spaces. */
    public static final String VIEWPORT = "VIEWPORT";
    public static final String COMPONENT = "COMPONENT";
    /** A coordinate's magnitude above this is malformed. */
    public static final int MAX_COORDINATE = 16384;
    /** The largest click count. */
    public static final int MAX_CLICK_COUNT = 3;
    /** The largest wheel delta per axis, in CSS pixels. */
    public static final int MAX_DELTA = 16384;
    /** How many frame elements a hit-test descends through (the main frame is depth 0). */
    public static final int MAX_FRAME_DEPTH = 3;
    /** The protocol's event types. */
    public static final String MOVED = "mouseMoved";
    public static final String PRESSED = "mousePressed";
    public static final String RELEASED = "mouseReleased";
    public static final String WHEEL = "mouseWheel";

    /** The receiving side's bounds of a hit's page-chosen texts (S43). */
    static final int MAX_ROLE = 64;
    static final int MAX_TAG = 64;
    static final int MAX_TEXT = 1000;
    static final int MAX_SELECTOR = 4096;
    /** A rectangle's coordinates are clamped to this magnitude. */
    static final long MAX_RECT = 1L << 24;
    /** At most this many locators are read from a hit (the library proposes two at most). */
    static final int MAX_LOCATORS = 6;

    private WebInput() {
    }

    // ------------------------------------------------------------------------------------------------ parameters

    /** A parameter given wrongly: the caller answers {@code INVALID_PARAMS} with its message and {@code data.param}. */
    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String param;

        Invalid(String param, String message) {
            super(message);
            this.param = param;
        }

        /** The parameter's name ({@code point}, {@code space}, {@code action}, ...). */
        public String param() {
            return param;
        }
    }

    /** A point and the space it is in. Immutable. */
    public static final class Target {
        private final int x;
        private final int y;
        private final String space;

        Target(int x, int y, String space) {
            this.x = x;
            this.y = y;
            this.space = space;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        /** {@link #VIEWPORT} or {@link #COMPONENT}. */
        public String space() {
            return space;
        }

        public boolean component() {
            return COMPONENT.equals(space);
        }
    }

    /**
     * {@code point: {x, y}} (required; numbers, rounded to whole pixels) and {@code space} ({@link #VIEWPORT} by
     * default, or {@link #COMPONENT}).
     *
     * @throws Invalid for a missing or malformed point or an unknown space
     */
    public static Target target(Map<String, ?> args) {
        Object point = args.get("point");
        if (point == null) {
            throw new Invalid("point", "point is required: {x, y}");
        }
        if (!(point instanceof Map)) {
            throw new Invalid("point", "point must be an object {x, y}");
        }
        int x = coordinate(((Map<?, ?>) point).get("x"), "x");
        int y = coordinate(((Map<?, ?>) point).get("y"), "y");
        Object space = args.get("space");
        String s = space == null ? VIEWPORT : String.valueOf(space);
        if (!(space == null || space instanceof String) || !(VIEWPORT.equals(s) || COMPONENT.equals(s))) {
            throw new Invalid("space", "space must be VIEWPORT or COMPONENT");
        }
        return new Target(x, y, s);
    }

    private static int coordinate(Object v, String axis) {
        if (!(v instanceof Number)) {
            throw new Invalid("point", "point." + axis + " must be a number");
        }
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > MAX_COORDINATE) {
            throw new Invalid("point", "point." + axis + " must be a number within -" + MAX_COORDINATE + ".."
                    + MAX_COORDINATE);
        }
        return (int) Math.round(d);
    }

    /** {@code web.pointer}'s parameters, read. Immutable. */
    public static final class Pointer {
        private final String action;
        private final Target target;
        private final String button;
        private final int clickCount;
        private final List<String> modifiers;
        private final int deltaX;
        private final int deltaY;
        private final boolean force;

        Pointer(String action, Target target, String button, int clickCount, List<String> modifiers, int deltaX,
                int deltaY, boolean force) {
            this.action = action;
            this.target = target;
            this.button = button;
            this.clickCount = clickCount;
            this.modifiers = modifiers;
            this.deltaX = deltaX;
            this.deltaY = deltaY;
            this.force = force;
        }

        /** One of {@link #ACTIONS}. */
        public String action() {
            return action;
        }

        public Target target() {
            return target;
        }

        /** One of {@link #BUTTONS}. */
        public String button() {
            return button;
        }

        public int clickCount() {
            return clickCount;
        }

        /** The modifiers named, each once, in the order of {@link #MODIFIERS}. */
        public List<String> modifiers() {
            return modifiers;
        }

        public int deltaX() {
            return deltaX;
        }

        public int deltaY() {
            return deltaY;
        }

        /** Act although a modal dialog blocks the browser's window (a person's argument only). */
        public boolean force() {
            return force;
        }

        /** {@link #modifierBits} of {@link #modifiers()}. */
        public int modifierBits() {
            return WebInput.modifierBits(modifiers);
        }
    }

    /**
     * {@code web.pointer}'s parameters: {@code action} (required, one of {@link #ACTIONS}), the point of
     * {@link #target}, {@code button} ({@code LEFT}), {@code modifiers} (a list of {@link #MODIFIERS}),
     * {@code clickCount} ({@code 1..3}; 1, and 2 for {@code DOUBLE_CLICK}), {@code wheel: {dx, dy}} (whole CSS pixels
     * within {@code -16384..16384}; required for {@code WHEEL} and refused for every other action) and {@code force}.
     *
     * @throws Invalid for a missing or wrong parameter
     */
    public static Pointer pointer(Map<String, ?> args) {
        Object a = args.get("action");
        if (a == null) {
            throw new Invalid("action", "action is required: one of " + ACTIONS);
        }
        if (!(a instanceof String) || !ACTIONS.contains(a)) {
            throw new Invalid("action", "action must be one of " + ACTIONS);
        }
        String action = (String) a;
        Target target = target(args);
        Object b = args.get("button");
        if (b != null && (!(b instanceof String) || !BUTTONS.contains(b))) {
            throw new Invalid("button", "button must be one of " + BUTTONS);
        }
        String button = b == null ? "LEFT" : (String) b;
        int clickCount = "DOUBLE_CLICK".equals(action) ? 2 : 1;
        Object c = args.get("clickCount");
        if (c != null) {
            if (!(c instanceof Number) || ((Number) c).doubleValue() != Math.rint(((Number) c).doubleValue())
                    || ((Number) c).doubleValue() < 1 || ((Number) c).doubleValue() > MAX_CLICK_COUNT) {
                throw new Invalid("clickCount", "clickCount must be an integer 1.." + MAX_CLICK_COUNT);
            }
            clickCount = ((Number) c).intValue();
        }
        List<String> modifiers = modifiers(args.get("modifiers"));
        int dx = 0;
        int dy = 0;
        Object w = args.get("wheel");
        if ("WHEEL".equals(action)) {
            if (!(w instanceof Map)) {
                throw new Invalid("wheel", "wheel {dx, dy} is required for WHEEL");
            }
            dx = delta(((Map<?, ?>) w).get("dx"), "dx");
            dy = delta(((Map<?, ?>) w).get("dy"), "dy");
        } else if (w != null) {
            throw new Invalid("wheel", "wheel is for the action WHEEL only");
        }
        Object f = args.get("force");
        if (f != null && !(f instanceof Boolean)) {
            throw new Invalid("force", "force must be true or false");
        }
        return new Pointer(action, target, button, clickCount, modifiers, dx, dy, Boolean.TRUE.equals(f));
    }

    private static List<String> modifiers(Object v) {
        if (v == null) {
            return Collections.emptyList();
        }
        if (!(v instanceof List)) {
            throw new Invalid("modifiers", "modifiers must be a list of " + MODIFIERS);
        }
        TreeSet<Integer> bits = new TreeSet<Integer>();
        for (Object m : (List<?>) v) {
            int i = m instanceof String ? MODIFIERS.indexOf(m) : -1;
            if (i < 0) {
                throw new Invalid("modifiers", "modifiers must be a list of " + MODIFIERS);
            }
            bits.add(Integer.valueOf(i));
        }
        List<String> out = new ArrayList<String>();
        for (Integer i : bits) {
            out.add(MODIFIERS.get(i.intValue()));
        }
        return Collections.unmodifiableList(out);
    }

    private static int delta(Object v, String axis) {
        if (v == null) {
            return 0;
        }
        if (!(v instanceof Number) || ((Number) v).doubleValue() != Math.rint(((Number) v).doubleValue())
                || Math.abs(((Number) v).doubleValue()) > MAX_DELTA) {
            throw new Invalid("wheel", "wheel." + axis + " must be an integer within -" + MAX_DELTA + ".." + MAX_DELTA);
        }
        return ((Number) v).intValue();
    }

    // ------------------------------------------------------------------------------------------------ events

    /** One event of an action's plan: the protocol type, the button ({@code none} or lower case), the click count. */
    public static final class Step {
        private final String type;
        private final String button;
        private final int clickCount;

        Step(String type, String button, int clickCount) {
            this.type = type;
            this.button = button;
            this.clickCount = clickCount;
        }

        /** {@link #MOVED}, {@link #PRESSED}, {@link #RELEASED} or {@link #WHEEL}. */
        public String type() {
            return type;
        }

        /** {@code none}, {@code left}, {@code middle} or {@code right}. */
        public String button() {
            return button;
        }

        public int clickCount() {
            return clickCount;
        }

        @Override
        public String toString() {
            return type + "(" + button + "," + clickCount + ")";
        }
    }

    /**
     * The events an action sends, in order, each awaited before the next: always a {@code mouseMoved} to the point
     * first (as a person's pointer arrives before it acts, so that hover and pointer-enter run), then {@code PRESS} a
     * {@code mousePressed}, {@code RELEASE} a {@code mouseReleased}, {@code CLICK} with click count {@code n} the pairs
     * {@code mousePressed}/{@code mouseReleased} with click counts 1 to {@code n} (as Chromium needs them to raise
     * {@code dblclick}), {@code DOUBLE_CLICK} the same with {@code n} 2 by default, {@code WHEEL} a {@code mouseWheel}.
     */
    public static List<Step> plan(Pointer p) {
        List<Step> out = new ArrayList<Step>();
        out.add(new Step(MOVED, "none", 0));
        String b = p.button().toLowerCase(java.util.Locale.ROOT);
        String action = p.action();
        if ("PRESS".equals(action)) {
            out.add(new Step(PRESSED, b, p.clickCount()));
        } else if ("RELEASE".equals(action)) {
            out.add(new Step(RELEASED, b, p.clickCount()));
        } else if ("CLICK".equals(action) || "DOUBLE_CLICK".equals(action)) {
            for (int i = 1; i <= p.clickCount(); i++) {
                out.add(new Step(PRESSED, b, i));
                out.add(new Step(RELEASED, b, i));
            }
        } else if ("WHEEL".equals(action)) {
            out.add(new Step(WHEEL, "none", 0));
        }
        return Collections.unmodifiableList(out);
    }

    /** The protocol's modifier bits: ALT 1, CTRL 2, META 4, SHIFT 8; unknown names count nothing. */
    public static int modifierBits(Collection<String> names) {
        int bits = 0;
        for (String n : names) {
            int i = MODIFIERS.indexOf(n);
            if (i >= 0) {
                bits |= 1 << i;
            }
        }
        return bits;
    }

    /**
     * The parameters of {@code Input.dispatchMouseEvent}, in this key order: {@code type, x, y, button, clickCount,
     * modifiers}, and {@code deltaX, deltaY} for a {@code mouseWheel} only. Both paths send exactly this.
     */
    public static Map<String, Object> mouseEvent(String type, int x, int y, String button, int clickCount,
                                                 int modifiers, int deltaX, int deltaY) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("x", Long.valueOf(x));
        m.put("y", Long.valueOf(y));
        m.put("button", button);
        m.put("clickCount", Long.valueOf(clickCount));
        m.put("modifiers", Long.valueOf(modifiers));
        if (WHEEL.equals(type)) {
            m.put("deltaX", Long.valueOf(deltaX));
            m.put("deltaY", Long.valueOf(deltaY));
        }
        return m;
    }

    /** {@link #mouseEvent} for one step of a plan at a point. */
    public static Map<String, Object> mouseEvent(Step step, int x, int y, Pointer p) {
        return mouseEvent(step.type(), x, y, step.button(), step.clickCount(), p.modifierBits(), p.deltaX(),
                p.deltaY());
    }

    // ------------------------------------------------------------------------------------------------ hit values

    /** The library's {@code hit} value, read and bounded on the receiving side. Immutable. */
    public static final class Hit {
        private final Map<String, Object> element;
        private final List<Object> locators;
        private final boolean unique;
        private final Child child;

        Hit(Map<String, Object> element, List<Object> locators, boolean unique, Child child) {
            this.element = element;
            this.locators = locators;
            this.unique = unique;
            this.child = child;
        }

        /** {@code {role, name, tag, id?, rect: {x, y, w, h}}}, bounded; a new map on every call. */
        public Map<String, Object> element() {
            return new LinkedHashMap<String, Object>(element);
        }

        /** {@code {role, name, tag, id?}}: the element without its rectangle (web.pointer's {@code hit}). */
        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<String, Object>(element);
            m.remove("rect");
            return m;
        }

        /** {@code [{role, name} | {selector}]}, bounded. */
        public List<Object> locators() {
            return locators;
        }

        public boolean unique() {
            return unique;
        }

        /** For a frame element: where the caller evaluates {@code hit} next; else null. */
        public Child child() {
            return child;
        }

        /** The element's rectangle moved by {@code (dx, dy)} (into an ancestor frame's viewport). */
        public Hit translated(int dx, int dy) {
            Map<String, Object> e = new LinkedHashMap<String, Object>(element);
            e.put("rect", move(element.get("rect"), dx, dy));
            return new Hit(e, locators, unique, child);
        }
    }

    /** A frame element's child point and the frame element's name and URL. Immutable. */
    public static final class Child {
        private final int x;
        private final int y;
        private final String name;
        private final String url;

        Child(int x, int y, String name, String url) {
            this.x = x;
            this.y = y;
            this.name = name;
            this.url = url;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public String name() {
            return name;
        }

        public String url() {
            return url;
        }
    }

    /** A value that is not the library's {@code hit} shape (a page that replaced the library). */
    public static final class Malformed extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message);
        }
    }

    /**
     * Reads the library's {@code hit} value (not an error object: the caller maps those first with
     * {@link WebLib#errorOf}), cutting every page-chosen text again (role and tag at 64, name and id at 1000, a
     * selector at 4096, a child's name at 1000 and URL at 4096), clamping the rectangle's numbers to whole pixels
     * within {@code -16777216..16777216} and reading at most six well-formed locators.
     *
     * @throws Malformed when the value is not the shape of a hit
     */
    public static Hit hit(Object value) {
        if (!(value instanceof Map) || !(((Map<?, ?>) value).get("element") instanceof Map)) {
            throw new Malformed("the page's script library answered no hit");
        }
        Map<?, ?> v = (Map<?, ?>) value;
        Map<?, ?> raw = (Map<?, ?>) v.get("element");
        Map<String, Object> element = new LinkedHashMap<String, Object>();
        element.put("role", text(raw.get("role"), MAX_ROLE));
        element.put("name", text(raw.get("name"), MAX_TEXT));
        element.put("tag", text(raw.get("tag"), MAX_TAG));
        if (raw.get("id") instanceof String && !((String) raw.get("id")).isEmpty()) {
            element.put("id", text(raw.get("id"), MAX_TEXT));
        }
        element.put("rect", rect(raw.get("rect")));
        List<Object> locators = new ArrayList<Object>();
        if (v.get("locators") instanceof List) {
            for (Object l : (List<?>) v.get("locators")) {
                if (locators.size() >= MAX_LOCATORS || !(l instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) l;
                Map<String, Object> out = new LinkedHashMap<String, Object>();
                if (m.get("role") instanceof String && m.get("name") instanceof String) {
                    out.put("role", text(m.get("role"), MAX_ROLE));
                    out.put("name", text(m.get("name"), MAX_TEXT));
                } else if (m.get("selector") instanceof String) {
                    out.put("selector", text(m.get("selector"), MAX_SELECTOR));
                } else {
                    continue;
                }
                locators.add(out);
            }
        }
        Child child = null;
        if (v.get("child") instanceof Map) {
            Map<?, ?> c = (Map<?, ?>) v.get("child");
            child = new Child(clampInt(c.get("x")), clampInt(c.get("y")), text(c.get("name"), MAX_TEXT),
                    text(c.get("url"), WebLib.MAX_URL_CHARS));
        }
        return new Hit(element, Collections.unmodifiableList(locators), !locators.isEmpty(), child);
    }

    private static String text(Object v, int max) {
        return v == null ? "" : TextBounds.cut(String.valueOf(v), max);
    }

    private static Map<String, Object> rect(Object v) {
        Map<?, ?> r = v instanceof Map ? (Map<?, ?>) v : Collections.emptyMap();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (String k : new String[] {"x", "y", "w", "h"}) {
            out.put(k, Long.valueOf(clamp(r.get(k))));
        }
        return out;
    }

    private static long clamp(Object v) {
        if (!(v instanceof Number)) {
            return 0;
        }
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d)) {
            return 0;
        }
        return Math.max(-MAX_RECT, Math.min(MAX_RECT, Math.round(d)));
    }

    private static int clampInt(Object v) {
        return (int) Math.max(-MAX_COORDINATE * 4L, Math.min(MAX_COORDINATE * 4L, clamp(v)));
    }

    private static Map<String, Object> move(Object rect, int dx, int dy) {
        Map<String, Object> r = new LinkedHashMap<String, Object>(rect(rect));
        r.put("x", Long.valueOf(((Long) r.get("x")).longValue() + dx));
        r.put("y", Long.valueOf(((Long) r.get("y")).longValue() + dy));
        return r;
    }

    /** {@code {x, y, w, h}} of whole pixels (a rectangle a result reports). */
    public static Map<String, Object> rect(long x, long y, long w, long h) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("x", Long.valueOf(x));
        r.put("y", Long.valueOf(y));
        r.put("w", Long.valueOf(w));
        r.put("h", Long.valueOf(h));
        return r;
    }

    // ------------------------------------------------------------------------------------------------ frames

    /** A frame as both paths know it: its key, its parent's key, its name and URL. Immutable. */
    public static final class Frame {
        private final String key;
        private final String parentKey;
        private final String name;
        private final String url;

        public Frame(String key, String parentKey, String name, String url) {
            this.key = key;
            this.parentKey = parentKey;
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }

        public String key() {
            return key;
        }

        public String parentKey() {
            return parentKey;
        }

        public String name() {
            return name;
        }

        public String url() {
            return url;
        }
    }

    /**
     * The key of the child frame a frame element of frame {@code parentKey} shows, or null: among the frames whose
     * parent is {@code parentKey}, the one with the element's name (when it has one and exactly one frame has it), else
     * the one with its URL (when exactly one has it), else the only child frame there is. Both paths apply this rule to
     * the same frame tree ({@code Page.getFrameTree}), so they descend into the same frame.
     */
    public static String childFrame(List<Frame> frames, String parentKey, Child child) {
        if (parentKey == null || child == null) {
            return null;
        }
        List<Frame> children = new ArrayList<Frame>();
        for (Frame f : frames) {
            if (parentKey.equals(f.parentKey())) {
                children.add(f);
            }
        }
        if (!child.name().isEmpty()) {
            Frame byName = onlyOne(children, child.name(), true);
            if (byName != null) {
                return byName.key();
            }
        }
        if (!child.url().isEmpty()) {
            Frame byUrl = onlyOne(children, child.url(), false);
            if (byUrl != null) {
                return byUrl.key();
            }
        }
        return children.size() == 1 ? children.get(0).key() : null;
    }

    private static Frame onlyOne(List<Frame> frames, String value, boolean byName) {
        Frame found = null;
        for (Frame f : frames) {
            if (value.equals(byName ? f.name() : f.url())) {
                if (found != null) {
                    return null;
                }
                found = f;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------------------------------------ results

    /**
     * {@code web.hitTest}'s value: {@code {browser, frame?, viewportPoint: {x, y}, element, locators, unique,
     * iframe?: {key, rect}, scaleAssumed?}}. {@code frame} is omitted when the frame's key is unknown (a browser
     * without frames), {@code iframe} when the element was found in the frame the call started in, {@code scaleAssumed}
     * unless the point was a component point.
     */
    public static Map<String, Object> hitResult(String browser, String frame, int x, int y, Hit hit, String iframeKey,
                                                Map<String, Object> iframeRect, Boolean scaleAssumed) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("browser", browser);
        if (frame != null) {
            out.put("frame", frame);
        }
        out.put("viewportPoint", point(x, y));
        out.put("element", hit.element());
        out.put("locators", hit.locators());
        out.put("unique", Boolean.valueOf(hit.unique()));
        if (iframeRect != null) {
            Map<String, Object> iframe = new LinkedHashMap<String, Object>();
            if (iframeKey != null) {
                iframe.put("key", iframeKey);
            }
            iframe.put("rect", iframeRect);
            out.put("iframe", iframe);
        }
        if (scaleAssumed != null) {
            out.put("scaleAssumed", scaleAssumed);
        }
        return out;
    }

    /**
     * {@code web.pointer}'s value: {@code {via, viewportPoint: {x, y}, dispatched: [types], hit?: {role, name, tag,
     * id?}, visibleInSwing?: false}} ({@code visibleInSwing} only for a browser whose component is not showing).
     */
    public static Map<String, Object> pointerResult(String via, int x, int y, List<String> dispatched,
                                                    Map<String, Object> hit, boolean visibleInSwing) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("via", via);
        out.put("viewportPoint", point(x, y));
        out.put("dispatched", new ArrayList<String>(dispatched));
        if (hit != null) {
            out.put("hit", hit);
        }
        if (!visibleInSwing) {
            out.put("visibleInSwing", Boolean.FALSE);
        }
        return out;
    }

    /** {@code {x, y}}. */
    public static Map<String, Object> point(long x, long y) {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("x", Long.valueOf(x));
        p.put("y", Long.valueOf(y));
        return p;
    }
}
