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

import ai.skillroy.testjava.agent.api.spi.RegionResolver;
import ai.skillroy.testjava.agent.api.spi.SurfaceSource;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Scrollbar;
import java.awt.TextComponent;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * {@code swing.hitTest} (phase7-contracts.md section 3.2, A63, D292): a point of a window to the component under it,
 * with its locator candidates each checked to match exactly that component, the point inside it, what AWT would
 * deliver a mouse event to, the windows of the JVM that cover the point and the modal blocker. One bounded EDT read;
 * nothing is changed.
 *
 * <p>The mouse target re-implements AWT's lightweight dispatcher's target search with public API (BS10 RESULT "What
 * {@code swing.hitTest} must report"): the deepest visible lightweight component containing the point that has a
 * mouse, mouse-motion or mouse-wheel listener, or is a {@code JComponent} with a component popup menu or with
 * autoscrolls, searching siblings and then the container; the enabled-event-mask half of AWT's filter is not readable,
 * so {@code mouseTargetInferred} is always true (gap 79).
 *
 * <p>Phase 8 (phase8-contracts.md 3.4, D513, D518): the routed event's {@code source} is the innermost showing
 * heavyweight component on the containment path at the point that no {@code SurfaceSource} claims ({@link #sourceAt}),
 * else the window; the mouse-target search runs below it as that heavyweight's own lightweight dispatcher would
 * (lightweight children, the heavyweight itself when it listens; a heavyweight leaf when it listens or is an AWT
 * control whose peer acts on a press). HW/LW mixing cut-outs are not modelled. The result gains {@code source} (after
 * {@code surface}) and {@code vetoed} (always, after {@code masked}: a registered {@code InputVeto} refuses input to
 * the target or the deepest component on some route, phase8-contracts.md 3.2).
 *
 * <p>Stateless; every method EDT only.
 */
final class HitTest {

    /** Most locator candidates. */
    static final int MAX_LOCATORS = 6;
    /** Most covering windows reported. */
    static final int MAX_COVERING = 32;
    /** Longest text a {@code {text, role}} candidate carries. */
    static final int MAX_TEXT_LOCATOR = 100;

    private HitTest() {
    }

    /** The outcome of one hit-test: the facts the pointer's pre-check needs and the JSON of the result. */
    static final class Hit {
        Window window;
        /** The point in the window's coordinates. */
        Point point;
        /** The point in screen coordinates. */
        Point screen;
        Component deepest;
        Component mouseTarget;
        Component target;
        /** The routed event's source: the window, or the innermost unclaimed heavyweight at the point (D513). */
        Component source;
        String targetKind;
        Point local;
        boolean masked;
        boolean vetoed;
        boolean enabled;
        Dialog blocker;
        List<Window> covering = new ArrayList<Window>();
        List<Boolean> ranked = new ArrayList<Boolean>();
        Map<String, Object> json;

        boolean rankedCovering() {
            return ranked.contains(Boolean.TRUE);
        }

        /** True when a covering window refuses a Robot press at the point ({@link #refusesRobot}). EDT only. */
        boolean robotCovering() {
            Window active = Windows.activeWindow();
            for (Window other : covering) {
                if (refusesRobot(other, window, active)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Whether a window covering a point of {@code window} refuses a Robot press there (phase7-contracts.md 3.4): every
     * covering window does, but one that owns {@code window} while {@code window} is the active window (gap 101): an
     * owner lies below the windows it owns, and the active window was raised when it was activated.
     */
    static boolean refusesRobot(Window other, Window window, Window active) {
        return window == null || window != active || other == null || !Windows.ownedBy(window, other);
    }

    /**
     * The hit-test of a point of a window. EDT only.
     *
     * @param given the component named by the target form (it becomes {@code target}), or null for the point form
     * @param screenSpace the point was given in screen coordinates ({@code stackingAssumed})
     * @throws RpcException NOT_FOUND ({@code data.reason: "outside"}) when the point lies outside the window
     */
    static Hit at(ComponentView view, Windows windows, SwingSpis spis, Window window, Point p, Component given,
                  boolean screenSpace, long seq) {
        if (!window.isShowing()) {
            throw Errors.invalid(view.describe(window) + " is not showing", Errors.data("ref", view.ref(window),
                    "reason", "not showing"));
        }
        if (p.x < 0 || p.y < 0 || p.x >= window.getWidth() || p.y >= window.getHeight()) {
            throw Errors.notFound("the point (" + p.x + ", " + p.y + ") lies outside " + view.describe(window),
                    Errors.data("reason", "outside", "window", view.ref(window)));
        }
        Hit hit = new Hit();
        hit.window = window;
        hit.point = new Point(p);
        Point origin = window.getLocationOnScreen();
        hit.screen = new Point(origin.x + p.x, origin.y + p.y);
        Rectangle windowRect = new Rectangle(origin, window.getSize());

        hit.deepest = SwingUtilities.getDeepestComponentAt(window, p.x, p.y);
        List<SurfaceSource.Surface> surfaces = spis.surfaces();
        hit.source = sourceAt(window, p, surfaces);
        hit.mouseTarget = mouseTarget(window, hit.source, p);
        if (given != null) {
            hit.target = given;
            hit.targetKind = "TARGET";
        } else if (hit.mouseTarget != null) {
            hit.target = hit.mouseTarget;
            hit.targetKind = "MOUSE_TARGET";
        } else {
            hit.target = hit.deepest;
            hit.targetKind = "DEEPEST";
        }
        Component target = hit.target;
        hit.masked = target != null && view.masked(target);
        hit.vetoed = target != null && (view.vetoed(target) || view.vetoed(hit.deepest));
        hit.enabled = target != null && target.isEnabled();

        covering(windows, window, hit.screen, hit.covering, hit.ranked);
        boolean unranked = hit.ranked.contains(Boolean.FALSE);
        hit.blocker = windows.blockingModal(window);
        Map<String, Object> blockedBy = null;
        if (hit.blocker != null) {
            blockedBy = new LinkedHashMap<String, Object>();
            blockedBy.put("ref", view.ref(hit.blocker));
            blockedBy.put("title", hit.blocker.getTitle());
        } else if (target != null) {
            InWindowDialogs.Found f = new InWindowDialogs(view.context()).blocking(target);
            if (f != null) {
                blockedBy = new LinkedHashMap<String, Object>();
                blockedBy.put("ref", view.ref(f.root));
                blockedBy.put("title", new DialogReader(view).inWindowTitle(f.root));
                blockedBy.put("inWindow", Boolean.TRUE);
            }
        }

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("window", view.ref(window));
        m.put("windowRect", Capture.rectJson(windowRect));
        m.put("covering", coveringJson(view, hit.covering, hit.ranked));
        m.put("deepest", hit.deepest == null ? null : view.node(hit.deepest));
        m.put("mouseTarget", hit.mouseTarget == null ? null : view.node(hit.mouseTarget));
        m.put("mouseTargetInferred", Boolean.TRUE);
        Component wheel = null;
        for (Component c = hit.mouseTarget; c != null; c = c.getParent()) {
            if (c.getMouseWheelListeners().length > 0) {
                wheel = c;
                break;
            }
            if (c instanceof Window) {
                break;
            }
        }
        m.put("wheelTarget", wheel == null ? null : view.node(wheel));
        m.put("target", target == null ? null : view.ref(target));
        m.put("targetKind", hit.targetKind);
        if (given != null) {
            // The target form's own node (fix wave FC, L1-F5): deepest and mouseTarget are what lies at its centre.
            m.put("targetNode", view.node(given));
        }
        String surface = "NONE";
        if (target != null) {
            hit.local = SwingUtilities.convertPoint(window, p, target);
            m.put("local", Capture.point(hit.local.x, hit.local.y));
            Map<String, Object> rel = new LinkedHashMap<String, Object>();
            rel.put("fx", Double.valueOf(fraction(hit.local.x, target.getWidth())));
            rel.put("fy", Double.valueOf(fraction(hit.local.y, target.getHeight())));
            m.put("relative", rel);
            Long row = rowAt(target, hit.local);
            if (row != null) {
                m.put("row", row);
            }
            m.put("locators", locators(view, spis, target, MAX_LOCATORS));
            m.put("fires", Boolean.valueOf(spis.fires(target)));
            Object[] region = spis.regionAt(target, hit.local);
            if (region != null) {
                RegionResolver.Region r = (RegionResolver.Region) region[0];
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("id", r.id);
                one.put("rect", Capture.rectJson(r.rect));
                one.put("resolver", region[1].getClass().getName());
                m.put("region", one);
            }
            SurfaceSource.Surface claimed = spis.claiming(surfaces, target);
            if (claimed != null) {
                surface = claimed.kind == null ? "WEB" : claimed.kind;
                if (claimed.browserRef != null) {
                    m.put("browser", claimed.browserRef);
                }
                Point sl = SwingUtilities.convertPoint(window, p, claimed.component);
                m.put("surfaceLocal", Capture.point(sl.x, sl.y));
            } else if (heavyweight(target, window)) {
                surface = "HEAVYWEIGHT";
            } else {
                surface = "SWING";
            }
        } else {
            m.put("locators", new ArrayList<Object>());
            m.put("fires", Boolean.FALSE);
        }
        m.put("surface", surface);
        m.put("source", view.ref(hit.source));
        m.put("enabled", Boolean.valueOf(hit.enabled));
        m.put("listening", Boolean.valueOf(hit.mouseTarget != null));
        m.put("masked", Boolean.valueOf(hit.masked));
        m.put("vetoed", Boolean.valueOf(hit.vetoed));
        if (blockedBy != null) {
            m.put("blockedBy", blockedBy);
        }
        m.put("stackingAssumed", Boolean.valueOf(screenSpace || unranked));
        m.put("seq", Long.valueOf(seq));
        hit.json = m;
        return hit;
    }

    /**
     * The JVM's other showing windows whose screen rectangle holds the screen point, at most {@value #MAX_COVERING},
     * each with whether it may be ranked above the window ({@code covering} of the hit-test). EDT only.
     */
    static void covering(Windows windows, Window window, Point screen, List<Window> out, List<Boolean> ranked) {
        Window active = Windows.activeWindow();
        for (Window other : Windows.roots(false)) {
            if (other == window || out.size() >= MAX_COVERING || Windows.hidden(other)) {
                continue; // an iconified frame and the windows it owns are not on the glass (security fix, S104)
            }
            Rectangle r;
            try {
                r = new Rectangle(other.getLocationOnScreen(), other.getSize());
            } catch (RuntimeException e) {
                continue;
            }
            if (r.contains(screen)) {
                out.add(other);
                ranked.add(Boolean.valueOf(rankedAbove(windows, other, window, active)));
            }
        }
    }

    /**
     * The covering windows of a screen point that refuse a Robot press there ({@link #refusesRobot}), as the hit-test
     * reports them ({@code [{ref, rect, ranked}]}); used for a Robot drag's end point (fix wave of the Phase 7 review,
     * FA, L2-S2; gap 101). EDT only.
     */
    static List<Object> coveringAt(ComponentView view, Windows windows, Window window, Point screen) {
        List<Window> out = new ArrayList<Window>();
        List<Boolean> ranked = new ArrayList<Boolean>();
        covering(windows, window, screen, out, ranked);
        Window active = Windows.activeWindow();
        for (int i = out.size() - 1; i >= 0; i--) {
            if (!refusesRobot(out.get(i), window, active)) {
                out.remove(i);
                ranked.remove(i);
            }
        }
        return coveringJson(view, out, ranked);
    }

    /**
     * The components at a screen point of the JVM's other showing windows that hold it ({@link #covering}), for the
     * input veto (Phase 8 security fixes, S155, S156, FA): the target and the deepest component at the point of each
     * such window ({@link #targetsAt}); with {@code every}, of all of them (the Robot presses and releases on whatever
     * window is really on top, so an owner exempted from the covering rule, gap 101, or a window {@code force} acts
     * past is asked too); otherwise of those ranked above the window and those of type {@code POPUP} (a heavyweight
     * popup, a window the application showed while a button was down: the routed release's question). EDT only.
     *
     * @return the components (none null), or null when {@value #MAX_COVERING} windows hold the point: the list is
     *     full and a further window, unasked, could be the one on top (the caller refuses, fail closed)
     */
    static List<Component> coveringTargets(SwingSpis spis, Windows windows, Window window, Point screen,
                                           boolean every) {
        List<Window> others = new ArrayList<Window>();
        List<Boolean> ranked = new ArrayList<Boolean>();
        covering(windows, window, screen, others, ranked);
        if (others.size() >= MAX_COVERING) {
            return null;
        }
        List<Component> out = new ArrayList<Component>();
        for (int i = 0; i < others.size(); i++) {
            Window other = others.get(i);
            if (!every && !ranked.get(i).booleanValue() && other.getType() != Window.Type.POPUP) {
                continue;
            }
            Point o;
            try {
                o = other.getLocationOnScreen();
            } catch (RuntimeException e) {
                continue; // stopped showing in between: nothing of it is on the glass
            }
            Point local = new Point(screen.x - o.x, screen.y - o.y);
            for (Component c : targetsAt(spis, other, local)) {
                if (c != null && !out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * The components a release at a screen point may act on besides the component the press went to
     * (phase8-contracts.md 3.2 rule 6; Phase 8 security fix, S155, FA): the target and the deepest component at the
     * point in the window (a lightweight popup lies in its layered pane), then {@link #coveringTargets} (a
     * heavyweight popup is a window of its own). Swing's menu selection and a popup's mouse grabber hand the release
     * of a press on a menu or on a popup's invoker to the item under the release's screen point, which the pressed
     * component's chain does not hold. EDT only.
     *
     * @return the components (none null), or null when the covering list is full (the caller refuses)
     */
    static List<Component> releaseTargets(SwingSpis spis, Windows windows, Window window, Point screen,
                                          boolean every) {
        List<Component> out = new ArrayList<Component>();
        Point o = window.getLocationOnScreen();
        Point local = new Point(screen.x - o.x, screen.y - o.y);
        if (window.contains(local)) {
            for (Component c : targetsAt(spis, window, local)) {
                if (c != null && !out.contains(c)) {
                    out.add(c);
                }
            }
        }
        List<Component> others = coveringTargets(spis, windows, window, screen, every);
        if (others == null) {
            return null;
        }
        for (Component c : others) {
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<Object> coveringJson(ComponentView view, List<Window> windows, List<Boolean> ranked) {
        List<Object> covering = new ArrayList<Object>();
        for (int i = 0; i < windows.size(); i++) {
            Window other = windows.get(i);
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("ref", view.ref(other));
            one.put("rect", Capture.rectJson(new Rectangle(other.getLocationOnScreen(), other.getSize())));
            one.put("ranked", ranked.get(i));
            covering.add(one);
        }
        return covering;
    }

    /** A component's centre in its window's space, the point of the target form. */
    static Point centre(Component c) {
        Point off = Capture.offsetIn(c, Windows.windowOf(c));
        return new Point(off.x + c.getWidth() / 2, off.y + c.getHeight() / 2);
    }

    private static double fraction(int v, int size) {
        if (size <= 0) {
            return 0.0;
        }
        double f = Math.max(0.0, Math.min(1.0, (double) v / size));
        return Math.round(f * 10000.0) / 10000.0;
    }

    /** The model row under a point of a table or a list, or null. */
    static Long rowAt(Component c, Point local) {
        if (c instanceof JTable) {
            JTable table = (JTable) c;
            int row = table.rowAtPoint(local);
            return row < 0 ? null : Long.valueOf(table.convertRowIndexToModel(row));
        }
        if (c instanceof JList) {
            JList<?> list = (JList<?>) c;
            int i = list.locationToIndex(local);
            if (i < 0) {
                return null;
            }
            Rectangle cell = list.getCellBounds(i, i);
            return cell != null && cell.contains(local) ? Long.valueOf(i) : null;
        }
        return null;
    }

    private static boolean heavyweight(Component target, Window window) {
        for (Component c = target; c != null && c != window; c = c.getParent()) {
            if (c.isDisplayable() && !c.isLightweight()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code other} can be placed above {@code window} with confidence (vision F8; BS10 RESULT): a window
     * owned by it, a modal dialog over a window that is not modal (or ranked higher among modal dialogs), or the
     * active window; never a window that owns it.
     */
    static boolean rankedAbove(Windows windows, Window other, Window window, Window active) {
        if (Windows.ownedBy(other, window)) {
            return true;
        }
        if (Windows.ownedBy(window, other)) {
            return false;
        }
        boolean om = Windows.modal(other);
        boolean wm = Windows.modal(window);
        if (om && !wm) {
            return true;
        }
        if (wm && !om) {
            return false;
        }
        if (om) {
            List<Window> order = windows.stackingOrder();
            return order.indexOf(other) > order.indexOf(window);
        }
        if (other == active) {
            return true;
        }
        return false;
    }

    // --------------------------------------------------------------------------------------------- mouse target

    /**
     * What the window's lightweight dispatcher would deliver a mouse event at {@code (x, y)} to (the window itself
     * when it listens and no lightweight does), or null.
     */
    static Component mouseTarget(Container window, int x, int y) {
        return target(window, x, y, true);
    }

    /**
     * The routed event's source at a point of {@code root} (Phase 8, phase8-contracts.md 3.4 rule 1, D513): walking
     * up from the deepest component at the point to {@code root}, the first one that is displayable and not
     * lightweight (other than {@code root}); {@code root} when there is none, or when that heavyweight or any component
     * on the path is claimed by a {@code SurfaceSource} (a browser's surface keeps page input on the web routes'
     * checks, rule 2). {@code root} is the window in production and a container in the headless tests. EDT only.
     */
    static Component sourceAt(Container root, Point p, List<SurfaceSource.Surface> claimed) {
        Component deepest = SwingUtilities.getDeepestComponentAt(root, p.x, p.y);
        Component source = null;
        for (Component c = deepest; c != null && c != root; c = c.getParent()) {
            if (claims(claimed, c)) {
                return root;
            }
            if (source == null && c.isDisplayable() && !c.isLightweight()) {
                source = c;
            }
        }
        return source != null ? source : root;
    }

    private static boolean claims(List<SurfaceSource.Surface> claimed, Component c) {
        if (claimed == null) {
            return false;
        }
        for (SurfaceSource.Surface s : claimed) {
            if (s != null && s.component == c) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the routed event's source would deliver a mouse event at a point of {@code root} to (D513 rule 6): the
     * window's dispatcher when the source is {@code root}; below a heavyweight container its own dispatcher's search
     * (lightweight children, the container itself when it listens); a heavyweight leaf itself when it listens or is an
     * AWT control whose peer acts on a press ({@link #peerActs}); else null. EDT only.
     */
    static Component mouseTarget(Container root, Component source, Point p) {
        if (source == null || source == root) {
            return target(root, p.x, p.y, true);
        }
        Point q = SwingUtilities.convertPoint(root, p, source);
        if (source instanceof Container) {
            return target((Container) source, q.x, q.y, true);
        }
        return listens(source) || peerActs(source) ? source : null;
    }

    /**
     * The component a routed event at {@code (x, y)} of its source reaches (the per-event veto's question, D505): the
     * source's mouse target, else the deepest component there, else the source itself. EDT only.
     */
    static Component deliveryTarget(Component source, int x, int y) {
        if (!(source instanceof Container)) {
            return source;
        }
        Component t = target((Container) source, x, y, true);
        if (t == null) {
            t = SwingUtilities.getDeepestComponentAt(source, x, y);
        }
        return t != null ? t : source;
    }

    /**
     * The target and the deepest component at a point of the window, for a drag's end (the veto's second hit-test,
     * phase8-contracts.md 3.2 rule 2): {@code [target, deepest]}, either null. EDT only.
     */
    static Component[] targetsAt(SwingSpis spis, Window window, Point p) {
        Component deepest = SwingUtilities.getDeepestComponentAt(window, p.x, p.y);
        Component source = sourceAt(window, p, spis.surfaces());
        Component mouse = mouseTarget(window, source, p);
        return new Component[] {mouse != null ? mouse : deepest, deepest};
    }

    /** An AWT control whose peer acts on a press: {@code Button}, {@code Checkbox}, {@code Choice}, {@code List},
     *  {@code Scrollbar}, {@code TextComponent}. */
    static boolean peerActs(Component c) {
        return c instanceof Button || c instanceof Checkbox || c instanceof Choice || c instanceof java.awt.List
                || c instanceof Scrollbar || c instanceof TextComponent;
    }

    /** A heavyweight component that is not a container (a {@code Button}, a {@code Checkbox}, a {@code Canvas}). */
    static boolean heavyweightLeaf(Component c) {
        return c != null && !(c instanceof Container) && !c.isLightweight();
    }

    private static Component target(Container parent, int x, int y, boolean includeSelf) {
        synchronized (parent.getTreeLock()) {
            int n = parent.getComponentCount();
            for (int i = 0; i < n; i++) {
                Component comp = parent.getComponent(i);
                if (comp != null && comp.isVisible() && comp.isLightweight()
                        && comp.contains(x - comp.getX(), y - comp.getY())) {
                    if (comp instanceof Container) {
                        Component deeper = target((Container) comp, x - comp.getX(), y - comp.getY(), includeSelf);
                        if (deeper != null) {
                            return deeper;
                        }
                    } else if (listens(comp)) {
                        return comp;
                    }
                }
            }
            boolean peerOk = parent.isLightweight() || includeSelf;
            if (parent.contains(x, y) && peerOk && listens(parent)) {
                return parent;
            }
            return null;
        }
    }

    /** The public half of AWT's mouse-event target filter, with the two rules BS10 found (condition 3). */
    static boolean listens(Component c) {
        if (c.getMouseListeners().length > 0 || c.getMouseMotionListeners().length > 0
                || c.getMouseWheelListeners().length > 0) {
            return true;
        }
        if (c instanceof JComponent) {
            JComponent j = (JComponent) c;
            return j.getComponentPopupMenu() != null || j.getAutoscrolls();
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------- locators

    /**
     * The locator candidates of a component in authoring preference (design 3.7; phase7-contracts.md section 3.2),
     * each checked with the locator engine over every showing window to match exactly this component, at most
     * {@code max}: every registered {@code LocatorResolver.describe} entry, {@code {id}}, {@code {name, role}},
     * {@code {text, role}} (never for a masked component), {@code {class, index}}, {@code {path}}.
     */
    static List<Object> locators(ComponentView view, SwingSpis spis, Component target, int max) {
        return locators(view, spis, target, max, null, false);
    }

    /**
     * {@link #locators} searched under {@code root} instead of every showing window (headless tests, whose trees are
     * in no window).
     */
    static List<Object> locators(ComponentView view, SwingSpis spis, Component target, int max, Component root,
                                 boolean includeHidden) {
        List<Object> out = new ArrayList<Object>();
        LocatorEngine engine = new LocatorEngine(view);
        for (Map<String, Object> candidate : candidates(view, spis, target, root, includeHidden, true)) {
            if (out.size() >= max) {
                break;
            }
            if (unique(engine, candidate, target, root, includeHidden)) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** The first candidate of {@link #locators}' order that matches the component alone, or null. */
    static Map<String, Object> firstUniqueLocator(ComponentView view, SwingSpis spis, Component target) {
        return firstUniqueLocator(view, spis, target, null, false);
    }

    /** {@link #firstUniqueLocator} under a root (headless tests). */
    static Map<String, Object> firstUniqueLocator(ComponentView view, SwingSpis spis, Component target,
                                                  Component root, boolean includeHidden) {
        return firstUniqueLocator(view, spis, target, root, includeHidden, true);
    }

    /**
     * {@link #firstUniqueLocator} under a root, with or without the {@code {text, role}} candidate. The focus owner of
     * a capture is described without it ({@code focus.ownerLocator}, Phase 7 security fix, S146): a text field's text
     * is its content, which a baseline's or an anchor's {@code focus.owner} would carry into a committed bindings
     * file; the {@code {class, index}} or {@code {path}} candidate names such a field instead.
     */
    static Map<String, Object> firstUniqueLocator(ComponentView view, SwingSpis spis, Component target,
                                                  Component root, boolean includeHidden, boolean withText) {
        LocatorEngine engine = new LocatorEngine(view);
        for (Map<String, Object> candidate : candidates(view, spis, target, root, includeHidden, withText)) {
            if (unique(engine, candidate, target, root, includeHidden)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean unique(LocatorEngine engine, Map<String, Object> candidate, Component target,
                                  Component root, boolean includeHidden) {
        try {
            Locator loc = Locator.parse(candidate, "locator");
            List<Component> found = engine.find(loc, root, includeHidden);
            if (loc.index() != null) {
                int i = loc.index().intValue();
                return i < found.size() && found.get(i) == target;
            }
            return found.size() == 1 && found.get(0) == target;
        } catch (RpcException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<Map<String, Object>> candidates(ComponentView view, SwingSpis spis, Component target,
                                                        Component root, boolean includeHidden, boolean withText) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, String> e : spis.describe(target).entrySet()) {
            out.add(one(e.getKey(), e.getValue(), null, null));
        }
        String role = Roles.of(target);
        String id = view.id(target);
        if (id != null) {
            out.add(one("id", id, null, null));
        }
        String name = view.name(target); // withheld for a masked component when it is the component's text (S106)
        if (name != null && !name.isEmpty()) {
            out.add(one("name", name, role, null));
        }
        if (withText && !view.masked(target)) {
            String text = view.text(target);
            if (text != null && !text.isEmpty() && text.length() <= MAX_TEXT_LOCATOR && text.indexOf('\n') < 0) {
                out.add(one("text", text, role, null));
            }
        }
        out.add(classIndex(view, target, root, includeHidden));
        String path = path(target);
        if (path != null) {
            out.add(one("path", path, null, null));
        }
        return out;
    }

    private static Map<String, Object> classIndex(ComponentView view, Component target, Component root,
                                                  boolean includeHidden) {
        String simple = ComponentView.className(target);
        LocatorEngine engine = new LocatorEngine(view);
        List<Component> all = engine.find(Locator.parse(one("class", simple, null, null), "locator"), root,
                includeHidden);
        int i = all.indexOf(target);
        return one("class", simple, null, Integer.valueOf(Math.max(0, i)));
    }

    /**
     * The child-index path from the component's window (or, outside a window, its topmost ancestor) to it
     * ({@code "0/2/1"}).
     */
    static String path(Component target) {
        List<Integer> steps = new ArrayList<Integer>();
        Component c = target;
        while (c != null && !(c instanceof Window)) {
            Container parent = c.getParent();
            if (parent == null) {
                break;
            }
            int index = -1;
            Component[] kids = parent.getComponents();
            for (int i = 0; i < kids.length; i++) {
                if (kids[i] == c) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return null;
            }
            steps.add(0, Integer.valueOf(index));
            c = parent;
        }
        if (c == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Integer s : steps) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static Map<String, Object> one(String strategy, String value, String role, Integer index) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(strategy, value);
        if (role != null) {
            m.put("role", role);
        }
        if (index != null) {
            m.put("index", Long.valueOf(index.longValue()));
        }
        return m;
    }
}
