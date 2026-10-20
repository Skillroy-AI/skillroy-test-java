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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.NameEnricher;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.accessibility.AccessibleContext;
import javax.swing.JList;
import javax.swing.JTable;

/**
 * Turns live components into the JSON shapes of the contract (Node, window entry) and applies the name and id rules. One
 * instance serves one primitive call: it takes the registered {@link NameEnricher}s and {@code CaptureMask}s at
 * construction, so modules that register later are seen by the next call.
 *
 * <p>The masked-text rule (phase7-contracts.md section 3.6, D349): a component {@link Redaction} names (or one inside
 * it) has no {@code text} and no {@code value} in a node, and its node carries {@code masked: true}; the role is
 * unchanged. Phase 7 security fix: a masked component's {@code name} is withheld when it is the component's own text
 * (Swing's accessible-name fallback of a label or a button, S106), and a rectangle a mask's {@code regions} blank
 * withholds the text under it (a component under it is masked; a table's cells and a list's selected item under it
 * are withheld one by one, S122).
 *
 * <p>Phase 8 (phase8-contracts.md 3.2, 3.3): a node carries {@code vetoed: true} when a registered {@code InputVeto}
 * refuses input to the component on any route ({@link #vetoed}, asked with route {@code ANY}, ancestor rule), and
 * {@code collapsed: true} when it is showing but has no effective visible area ({@link #collapsedBy}); both keys only
 * when true, after {@code masked}. The veto answers and the visible areas are memoised for the call.
 *
 * <p>Must be used on the EDT. Not thread-safe; never shared between calls.
 */
final class ComponentView {

    /** Handle kind of every component and window ref. */
    static final String KIND = "component";

    private final AgentContext ctx;
    private final List<NameEnricher> enrichers;
    private final Redaction redaction;
    private final Map<Component, Boolean> maskedCache = new IdentityHashMap<Component, Boolean>();
    private final Map<Component, String> ruleCache = new IdentityHashMap<Component, String>();
    private final Map<Component, List<Rectangle>> ownRegionsCache = new IdentityHashMap<Component, List<Rectangle>>();
    private final Map<Component, List<Rectangle>> regionsOverCache =
            new IdentityHashMap<Component, List<Rectangle>>();
    private boolean enricherFailureLogged;
    private boolean readFailureLogged;
    private InputVetoes vetoes;
    private final Visibility visibility = new Visibility();

    ComponentView(AgentContext ctx) {
        this.ctx = ctx;
        this.enrichers = ctx.spi().all(NameEnricher.class);
        this.redaction = new Redaction(ctx);
    }

    AgentContext context() {
        return ctx;
    }

    /** The call's redaction rule (the built-in rule and the registered masks). */
    Redaction redaction() {
        return redaction;
    }

    /** The call's input vetoes (Phase 8, phase8-contracts.md 3.2): asked lazily, memoised per route. */
    InputVetoes vetoes() {
        if (vetoes == null) {
            vetoes = new InputVetoes(ctx);
        }
        return vetoes;
    }

    /**
     * True when a registered {@code InputVeto} refuses input to the component or an ancestor up to its window on any
     * route (route {@code ANY}, the readers' question, D506); memoised for the call.
     */
    boolean vetoed(Component c) {
        return c != null && vetoes().vetoed(c);
    }

    /** The effective visible area in the component's own coordinates (phase8-contracts.md 3.3); memoised. */
    Rectangle area(Component c) {
        return visibility.areaOf(c);
    }

    /** The component or the ancestor that leaves it no visible area; null when it has one; memoised. */
    Component collapsedBy(Component c) {
        return visibility.collapsedByOf(c);
    }

    /** Showing and not collapsed (phase8-contracts.md 3.3, D510); memoised. */
    boolean onScreen(Component c) {
        return visibility.onScreenOf(c);
    }

    /** The call's memo of visible areas. */
    Visibility visibility() {
        return visibility;
    }

    /**
     * True when the component or one of its ancestors is masked (phase7-contracts.md section 3.6); memoised for the
     * call, so a tree walk asks each mask once per component.
     */
    boolean masked(Component c) {
        if (c == null) {
            return false;
        }
        Boolean known = maskedCache.get(c);
        if (known != null) {
            return known.booleanValue();
        }
        boolean m = rule(c) != null
                || (!(c instanceof Window) && c.getParent() != null && masked(c.getParent()))
                || underRegion(c);
        maskedCache.put(c, Boolean.valueOf(m));
        return m;
    }

    /** {@link Redaction#ownRule}, memoised for the call. */
    private String rule(Component c) {
        if (ruleCache.containsKey(c)) {
            return ruleCache.get(c);
        }
        String r = redaction.ownRule(c);
        ruleCache.put(c, r);
        return r;
    }

    /**
     * True when a blanked {@code regions} rectangle withholds the component's text (Phase 7 security fix, S122): one
     * of the rectangles the masks' {@code regions} blank on the component or an ancestor (up to its window) holds the
     * component's whole rectangle, or, for a component that shows a text or a value (a label, a button, a field; not a
     * plain container, whose children are judged one by one), meets any part of it. A table's cells and a list's
     * items are judged one by one instead ({@link #cellHidden}, {@link #text}).
     */
    private boolean underRegion(Component c) {
        if (!redaction.hasMasks()) {
            return false;
        }
        List<Rectangle> over = regionsOver(c);
        if (over.isEmpty()) {
            return false;
        }
        Rectangle own = new Rectangle(0, 0, c.getWidth(), c.getHeight());
        for (Rectangle r : over) {
            if (r.equals(own)) {
                return true;
            }
        }
        if (c instanceof JTable || c instanceof JList) {
            return false;
        }
        boolean bears;
        try {
            bears = ComponentText.text(c) != null || ComponentText.value(c) != null;
        } catch (RuntimeException e) {
            bears = true; // the safe side
        }
        return bears;
    }

    /**
     * The rectangles the masks' {@code regions} blank on the component itself and on its ancestors up to its window
     * that are not masked whole (an ancestor masked whole masks the component through {@link #masked}'s chain), in the
     * component's own coordinates and clipped to its bounds; memoised for the call (Phase 7 security fix, S122).
     */
    List<Rectangle> regionsOver(Component c) {
        List<Rectangle> known = regionsOverCache.get(c);
        if (known != null) {
            return known;
        }
        List<Rectangle> out = new ArrayList<Rectangle>();
        if (redaction.hasMasks()) {
            Rectangle bounds = new Rectangle(0, 0, c.getWidth(), c.getHeight());
            int dx = 0;
            int dy = 0;
            for (Component a = c; a != null; a = a.getParent()) {
                if (rule(a) == null) {
                    for (Rectangle r : ownRegions(a)) {
                        Rectangle in = new Rectangle(r.x - dx, r.y - dy, r.width, r.height).intersection(bounds);
                        if (!in.isEmpty()) {
                            out.add(in);
                        }
                    }
                }
                if (a instanceof Window) {
                    break;
                }
                dx += a.getX();
                dy += a.getY();
            }
        }
        regionsOverCache.put(c, out);
        return out;
    }

    private List<Rectangle> ownRegions(Component a) {
        List<Rectangle> known = ownRegionsCache.get(a);
        if (known == null) {
            known = redaction.blanked(a, null);
            ownRegionsCache.put(a, known);
        }
        return known;
    }

    /**
     * True when a blanked {@code regions} rectangle meets a table's cell (view row and column), so that its value is
     * withheld from the node's {@code value} and from a row mark's label (Phase 7 security fix, S122). EDT only.
     */
    boolean cellHidden(JTable table, int row, int col) {
        List<Rectangle> over = regionsOver(table);
        if (over.isEmpty()) {
            return false;
        }
        Rectangle cell = table.getCellRect(row, col, true);
        for (Rectangle r : over) {
            if (r.intersects(cell)) {
                return true;
            }
        }
        return false;
    }

    /** True when a blanked {@code regions} rectangle meets a list's item (S122). EDT only. */
    private boolean itemHidden(JList<?> list, int index) {
        List<Rectangle> over = regionsOver(list);
        if (over.isEmpty() || index < 0) {
            return false;
        }
        Rectangle cell = list.getCellBounds(index, index);
        if (cell == null) {
            return false;
        }
        for (Rectangle r : over) {
            if (r.intersects(cell)) {
                return true;
            }
        }
        return false;
    }

    /** The component's stable ref. */
    String ref(Component c) {
        return ctx.handles().handleFor(c, KIND);
    }

    /**
     * The name rule: the first non-empty of the {@link NameEnricher} results (in registration order), the accessible
     * name and {@code Component.getName()} (ignoring names AWT generated). HTML in an accessible name is reduced to
     * its text. Null if none.
     *
     * <p>The masked name rule (phase7-contracts.md section 3.6; Phase 7 security fix, S106): for a masked component a
     * name equal to the component's own text is withheld (null), since Swing's accessible name of a label or a button
     * falls back to its text, so that no node, no {@code {name, role}} candidate, no {@code name} match and no
     * description carries what the mask hides; a name the application or a pack set apart from the text (the
     * accessible name, the client property, a border title, a {@code labelFor} label, {@code setName}, a
     * {@code NameEnricher}) stays.
     */
    String name(Component c) {
        String n = rawName(c);
        if (n == null || !masked(c)) {
            return n;
        }
        String own;
        try {
            own = ComponentText.text(c);
        } catch (RuntimeException e) {
            return null; // the safe side
        }
        if (own != null && (n.equals(own) || n.trim().equals(own.trim()))) {
            return null;
        }
        return n;
    }

    /** {@link #name} before the masked name rule. */
    private String rawName(Component c) {
        for (NameEnricher enricher : enrichers) {
            try {
                String n = enricher.nameFor(c);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            } catch (Throwable t) {
                if (!enricherFailureLogged) {
                    enricherFailureLogged = true;
                    ctx.log().warn("swing: NameEnricher " + enricher.getClass().getName() + " failed: " + t, t);
                }
            }
        }
        String accessible = ComponentText.visible(accessibleName(c));
        if (accessible != null && !accessible.isEmpty()) {
            return accessible;
        }
        String own = c.getName();
        if (own != null && !own.isEmpty() && !ComponentText.generatedName(c, own)) {
            return own;
        }
        return null;
    }

    /** The first non-empty {@link NameEnricher} name of the component, or null (the marks rule's custom components). */
    String enrichedName(Component c) {
        for (NameEnricher enricher : enrichers) {
            try {
                String n = enricher.nameFor(c);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            } catch (Throwable t) {
                if (!enricherFailureLogged) {
                    enricherFailureLogged = true;
                    ctx.log().warn("swing: NameEnricher " + enricher.getClass().getName() + " failed: " + t, t);
                }
            }
        }
        return null;
    }

    /**
     * The id rule: the component's explicitly set {@code Component.getName()}, or null when it has none. AWT makes up
     * a name ({@code frame0}, {@code dialog3}, ...) for a heavyweight component that was never named; such a name is
     * not an id. Asking a heavyweight component for its name lets AWT fix that made-up name at that moment, as any
     * other {@code getName()} call (including {@code toString()}) would; nothing else changes.
     */
    String id(Component c) {
        String own = c.getName();
        return own != null && !own.isEmpty() && !ComponentText.generatedName(c, own) ? own : null;
    }

    private static String accessibleName(Component c) {
        try {
            AccessibleContext ac = c.getAccessibleContext();
            return ac == null ? null : ac.getAccessibleName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A Node without children; {@code id} only when the component has one. */
    Map<String, Object> node(Component c) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ref", ref(c));
        m.put("role", Roles.of(c));
        m.put("class", className(c));
        String id = id(c);
        if (id != null) {
            m.put("id", id);
        }
        boolean masked = masked(c);
        m.put("name", name(c));
        m.put("text", masked ? null : text(c));
        m.put("enabled", Boolean.valueOf(c.isEnabled()));
        m.put("visible", Boolean.valueOf(c.isVisible()));
        m.put("showing", Boolean.valueOf(c.isShowing()));
        m.put("focused", Boolean.valueOf(c instanceof Window ? ((Window) c).isFocused() : c.hasFocus()));
        m.put("bounds", bounds(c));
        try {
            Boolean selected = ComponentText.selected(c);
            if (selected != null) {
                m.put("selected", selected);
            }
            Object value = masked ? null : ComponentText.value(c);
            if (value != null && c instanceof JTable) {
                hideCells((JTable) c, value);
            }
            if (value != null) {
                m.put("value", value);
            }
        } catch (RuntimeException e) {
            readFailed(c, e);
        }
        if (masked) {
            m.put("masked", Boolean.TRUE);
        }
        if (vetoed(c)) {
            m.put("vetoed", Boolean.TRUE);
        }
        if (c.isShowing() && collapsedBy(c) != null) {
            m.put("collapsed", Boolean.TRUE);
        }
        return m;
    }

    /**
     * The component's visible text ({@link ComponentText#text}); null for a masked component (D349), and null if the
     * component's own code (a custom model or document) throws, so that one broken widget does not fail the whole
     * snapshot or search.
     */
    String text(Component c) {
        if (masked(c)) {
            return null;
        }
        try {
            String t = ComponentText.text(c);
            if (t != null && c instanceof JList && itemHidden((JList<?>) c, ((JList<?>) c).getSelectedIndex())) {
                return null; // the selected item lies under a blanked region (S122)
            }
            return t;
        } catch (RuntimeException e) {
            readFailed(c, e);
            return null;
        }
    }

    /** Nulls the cells of a table value that a blanked {@code regions} rectangle meets (S122). */
    @SuppressWarnings("unchecked")
    private void hideCells(JTable table, Object value) {
        if (regionsOver(table).isEmpty() || !(value instanceof Map)) {
            return;
        }
        Object cells = ((Map<String, Object>) value).get("cells");
        if (!(cells instanceof List)) {
            return;
        }
        List<Object> rows = (List<Object>) cells;
        for (int row = 0; row < rows.size(); row++) {
            if (rows.get(row) instanceof List) {
                List<Object> line = (List<Object>) rows.get(row);
                for (int col = 0; col < line.size(); col++) {
                    if (cellHidden(table, row, col)) {
                        line.set(col, null);
                    }
                }
            }
        }
    }

    private void readFailed(Component c, RuntimeException e) {
        if (!readFailureLogged) {
            readFailureLogged = true;
            ctx.log().warn("swing: could not read " + className(c) + " (" + ref(c) + "): " + e, e);
        }
    }

    /** One entry of {@code swing.windows}; {@code id} only when the window has one. */
    Map<String, Object> window(Window w) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ref", ref(w));
        m.put("type", Windows.type(w));
        m.put("title", Windows.title(w));
        String id = id(w);
        if (id != null) {
            m.put("id", id);
        }
        m.put("name", name(w));
        m.put("class", className(w));
        m.put("visible", Boolean.valueOf(w.isVisible()));
        m.put("active", Boolean.valueOf(w.isActive()));
        m.put("focused", Boolean.valueOf(w.isFocused()));
        m.put("modal", Boolean.valueOf(Windows.modal(w)));
        m.put("bounds", bounds(w));
        return m;
    }

    /** A short description for error messages: {@code JButton 'ok' (h12)}. */
    String describe(Component c) {
        String n = name(c);
        return className(c) + (n == null ? "" : " '" + n + "'") + " (" + ref(c) + ")";
    }

    /** Simple class name; for anonymous and local classes the last segment of the binary name ({@code Foo$1}). */
    static String className(Component c) {
        Class<?> k = c.getClass();
        String simple = k.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        String full = k.getName();
        return full.substring(full.lastIndexOf('.') + 1);
    }

    /** A window's bounds in screen coordinates; any other component's relative to its containing window. */
    static Map<String, Object> bounds(Component c) {
        Rectangle r;
        if (c instanceof Window) {
            r = c.getBounds();
        } else {
            Point p = windowOffset(c);
            r = new Rectangle(p.x, p.y, c.getWidth(), c.getHeight());
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", Long.valueOf(r.x));
        m.put("y", Long.valueOf(r.y));
        m.put("w", Long.valueOf(r.width));
        m.put("h", Long.valueOf(r.height));
        return m;
    }

    /** Location of the component relative to its containing window (or its topmost ancestor if it has none). */
    static Point windowOffset(Component c) {
        int x = 0;
        int y = 0;
        for (Component p = c; p != null && !(p instanceof Window); p = p.getParent()) {
            x += p.getX();
            y += p.getY();
        }
        return new Point(x, y);
    }
}
