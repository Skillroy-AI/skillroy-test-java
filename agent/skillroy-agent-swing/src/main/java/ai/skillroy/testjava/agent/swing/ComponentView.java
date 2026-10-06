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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.accessibility.AccessibleContext;

/**
 * Turns live components into the JSON shapes of the contract (Node, window entry) and applies the name and id rules. One
 * instance serves one primitive call: it takes the registered {@link NameEnricher}s at construction, so modules that
 * register later are seen by the next call.
 *
 * <p>Must be used on the EDT. Not thread-safe; never shared between calls.
 */
final class ComponentView {

    /** Handle kind of every component and window ref. */
    static final String KIND = "component";

    private final AgentContext ctx;
    private final List<NameEnricher> enrichers;
    private boolean enricherFailureLogged;
    private boolean readFailureLogged;

    ComponentView(AgentContext ctx) {
        this.ctx = ctx;
        this.enrichers = ctx.spi().all(NameEnricher.class);
    }

    AgentContext context() {
        return ctx;
    }

    /** The component's stable ref. */
    String ref(Component c) {
        return ctx.handles().handleFor(c, KIND);
    }

    /**
     * The name rule: the first non-empty of the {@link NameEnricher} results (in registration order), the accessible
     * name and {@code Component.getName()} (ignoring names AWT generated). HTML in an accessible name is reduced to
     * its text. Null if none.
     */
    String name(Component c) {
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
        m.put("name", name(c));
        m.put("text", text(c));
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
            Object value = ComponentText.value(c);
            if (value != null) {
                m.put("value", value);
            }
        } catch (RuntimeException e) {
            readFailed(c, e);
        }
        return m;
    }

    /**
     * The component's visible text ({@link ComponentText#text}); null if the component's own code (a custom model or
     * document) throws, so that one broken widget does not fail the whole snapshot or search.
     */
    String text(Component c) {
        try {
            return ComponentText.text(c);
        } catch (RuntimeException e) {
            readFailed(c, e);
            return null;
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
