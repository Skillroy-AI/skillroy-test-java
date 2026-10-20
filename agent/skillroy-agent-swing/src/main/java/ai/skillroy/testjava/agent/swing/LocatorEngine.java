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
import ai.skillroy.testjava.agent.api.spi.LocatorResolver;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.SwingUtilities;

/**
 * Resolves {@link Locator}s against the live component tree. Built-in strategies walk the tree in pre-order (document
 * order) from each root; custom strategies are delegated to the first registered {@link LocatorResolver} that names
 * them, looked up at call time. Only components on screen match unless {@code includeHidden}: showing and not
 * collapsed (an effective visible area, Phase 8, phase8-contracts.md 3.3; a component scrolled out of a viewport stays
 * on screen).
 *
 * <p>Must be used on the EDT. One instance per primitive call.
 */
final class LocatorEngine {

    /** Most candidates an AMBIGUOUS error lists. */
    static final int MAX_CANDIDATES = 20;

    private final AgentContext ctx;
    private final ComponentView view;
    private final Locator.RegexBudget regexBudget = new Locator.RegexBudget();

    LocatorEngine(ComponentView view) {
        this.ctx = view.context();
        this.view = view;
    }

    /**
     * Every match in document order, after the role filter and before the index is applied.
     *
     * @param root search only this component and its descendants; null = every root window
     */
    List<Component> find(Locator loc, Component root, boolean includeHidden) {
        List<Component> roots = new ArrayList<Component>();
        if (root != null) {
            roots.add(root);
        } else {
            roots.addAll(Windows.roots(includeHidden));
        }
        List<Component> out = new ArrayList<Component>();
        String strategy = loc.strategy();
        if ("ref".equals(strategy)) {
            Component c = ctx.handles().resolve(loc.value(), Component.class);
            if (accepts(c, loc, includeHidden) && (root == null || SwingUtilities.isDescendingFrom(c, root))) {
                out.add(c);
            }
            return out;
        }
        if ("path".equals(strategy)) {
            for (Component r : roots) {
                Component c = walkPath(r, loc.path());
                if (c != null && accepts(c, loc, includeHidden)) {
                    out.add(c);
                }
            }
            return out;
        }
        if (loc.builtIn()) {
            for (Component r : roots) {
                collect(r, loc, includeHidden, out);
            }
            return out;
        }
        LocatorResolver resolver = resolverFor(strategy);
        Set<Component> seen = Collections.newSetFromMap(new IdentityHashMap<Component, Boolean>());
        for (Component r : roots) {
            List<Component> found = resolver.resolve(strategy, loc.value(), r);
            if (found == null) {
                continue;
            }
            for (Component c : found) {
                if (c != null && seen.add(c) && SwingUtilities.isDescendingFrom(c, r)
                        && accepts(c, loc, includeHidden)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * Exactly one component: the {@code index}-th match if an index is given, else the only match.
     *
     * @throws ai.skillroy.testjava.protocol.RpcException NOT_FOUND for no match (or an index out of range),
     *     AMBIGUOUS for several matches without an index (candidates in {@code data.candidates}, at most 20)
     */
    Component single(Locator loc, List<Component> found) {
        if (loc.index() != null) {
            int i = loc.index().intValue();
            if (i < found.size()) {
                return found.get(i);
            }
            throw Errors.notFound("locator " + loc.raw() + " has " + found.size() + " match(es); index " + i
                    + " is out of range", Errors.data("locator", loc.raw(), "count", Long.valueOf(found.size())));
        }
        if (found.isEmpty()) {
            throw Errors.notFound("no component matches " + loc.raw(), Errors.data("locator", loc.raw()));
        }
        if (found.size() > 1) {
            List<Object> candidates = new ArrayList<Object>();
            for (int i = 0; i < found.size() && i < MAX_CANDIDATES; i++) {
                candidates.add(view.node(found.get(i)));
            }
            throw Errors.ambiguous(found.size() + " components match " + loc.raw() + "; add role or index",
                    Errors.data("locator", loc.raw(), "count", Long.valueOf(found.size()), "candidates",
                            candidates));
        }
        return found.get(0);
    }

    /** Shorthand for {@code single(loc, find(loc, root, includeHidden))}. */
    Component one(Locator loc, Component root, boolean includeHidden) {
        return single(loc, find(loc, root, includeHidden));
    }

    private void collect(Component c, Locator loc, boolean includeHidden, List<Component> out) {
        if (!includeHidden && !view.onScreen(c)) {
            return; // nothing below a component that is not on screen is on screen (hereditary)
        }
        if (matches(c, loc) && accepts(c, loc, includeHidden)) {
            out.add(c);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collect(child, loc, includeHidden, out);
            }
        }
    }

    private boolean matches(Component c, Locator loc) {
        String s = loc.strategy();
        if ("name".equals(s)) {
            return loc.match().test(view.name(c), regexBudget);
        }
        if ("text".equals(s)) {
            return loc.match().test(view.text(c), regexBudget);
        }
        if ("id".equals(s)) {
            return loc.value().equals(view.id(c));
        }
        // "class": simple name or fully qualified name
        return loc.value().equals(ComponentView.className(c)) || loc.value().equals(c.getClass().getName());
    }

    private boolean accepts(Component c, Locator loc, boolean includeHidden) {
        return (includeHidden || view.onScreen(c)) && (loc.role() == null || loc.role().equals(Roles.of(c)));
    }

    /** The component at the child-index path below {@code root} ({@code root} itself for an empty path), or null. */
    static Component walkPath(Component root, int[] path) {
        Component c = root;
        for (int index : path) {
            if (!(c instanceof Container)) {
                return null;
            }
            Container parent = (Container) c;
            if (index >= parent.getComponentCount()) {
                return null;
            }
            c = parent.getComponent(index);
        }
        return c;
    }

    private LocatorResolver resolverFor(String strategy) {
        Set<String> registered = new TreeSet<String>();
        for (LocatorResolver r : ctx.spi().all(LocatorResolver.class)) {
            Set<String> names = r.strategies();
            if (names != null) {
                if (names.contains(strategy)) {
                    return r;
                }
                registered.addAll(names);
            }
        }
        Map<String, Object> data = Errors.data("param", "by." + strategy, "strategy", strategy, "builtIn",
                new ArrayList<Object>(Locator.BUILT_IN), "registered", new ArrayList<Object>(registered));
        throw Errors.invalid("unknown locator strategy '" + strategy + "'; built-in: " + Locator.BUILT_IN
                + ", registered: " + registered, data);
    }
}
