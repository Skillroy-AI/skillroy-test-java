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
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * {@code swing.pointer} (phase7-contracts.md sections 3.3 and 3.4, A63, D293, D294, D343, D344, D352): a pointer
 * action at coordinates.
 *
 * <p><b>ROUTED</b> (the default): mouse events whose source is the heavyweight window, in window coordinates,
 * dispatched non-posted from one {@code invokeLater} task per event, so that AWT's own lightweight dispatcher picks the
 * target, tracks enter and exit and retargets (BS10). A pre-check (one bounded EDT read: the hit-test at the point)
 * refuses a window a modal dialog blocks ({@code MODAL_OPEN}) and a point a ranked covering window covers
 * ({@code INPUT_REJECTED "covered"}), both unless {@code force}, and checks {@code expectTarget}. <b>Every event task
 * checks two things before it dispatches</b> (D352): the call's {@code cancelled} flag (set when the call's deadline
 * passes) and the modal state again; a task that finds either set dispatches nothing, except that a press already
 * dispatched is balanced by one routed {@code MOUSE_DRAGGED} to the window point (-1, -1), outside every component
 * (the lightweight dispatcher delivers {@code MOUSE_EXITED} to the pressed component, which disarms it), then a
 * {@code MOUSE_RELEASED} there with click count 0 and no click: no action fires and no model stays pressed. The real
 * pointer is never moved.
 *
 * <p><b>ROBOT</b> (behind the double opt-in): the real pointer through {@link RobotPort} on an agent worker; see
 * {@link RobotPath}.
 *
 * <p>Thread-safe: the last routed position per window is kept weakly under its own lock.
 */
final class Pointer {

    static final String ROUTED = "ROUTED";
    static final String ROBOT = "ROBOT";

    /** The actions. */
    enum Kind { MOVE, PRESS, RELEASE, CLICK, DOUBLE_CLICK, DRAG, WHEEL }

    /** Most drag samples and wheel notches. */
    static final int MAX_STEPS = 50;
    static final int MAX_NOTCHES = 50;
    static final int DEFAULT_STEPS = 6;
    /** X11's scroll amount of a wheel notch (BS10). */
    static final int SCROLL_AMOUNT = 3;
    /** Time kept back from the call's budget for the result read. */
    static final long RESULT_RESERVE_MS = 600;
    /** Time kept back so that the result reaches the caller before the dispatcher's TIMEOUT. */
    static final long SAFETY_MS = 150;

    /** {@code fired: false} reasons. */
    static final String EDT_BUSY = "EDT_BUSY";
    static final String MODAL_OPENED = "MODAL_OPENED";

    /** A unique {@code when} per call, so that the call's listener recognises its own (retargeted) events. */
    private static final AtomicLong WHEN = new AtomicLong();

    private final AgentContext ctx;
    private final Windows windows;
    private final RecentCaptures recent;
    private final boolean popupOnPress;
    private final Map<Window, Point> lastRouted = new WeakHashMap<Window, Point>();

    Pointer(AgentContext ctx, Windows windows, RecentCaptures recent) {
        this(ctx, windows, recent, System.getProperty("os.name", ""));
    }

    /** For tests: the popup trigger follows {@code osName} (on the press on X11 and macOS, the release on Windows). */
    Pointer(AgentContext ctx, Windows windows, RecentCaptures recent, String osName) {
        this.ctx = ctx;
        this.windows = windows;
        this.recent = recent;
        this.popupOnPress = !osName.toLowerCase(Locale.ROOT).contains("windows");
    }

    // ----------------------------------------------------------------------------------------------- parameters

    /** The parsed parameters of one call. */
    static final class Request {
        Kind kind;
        String via = ROUTED;
        int button = MouseEvent.BUTTON1;
        int modifiers;
        int clickCount = 1;
        int notches;
        int steps = DEFAULT_STEPS;
        boolean force;
        String expectTarget;
        boolean requireFocus = true;
        Point point;
        PointSpace space;
        Point to;
        TargetSpec target;
        String region;
        Integer row;
        double fx = 0.5;
        double fy = 0.5;
        Double tfx;
        Double tfy;
    }

    /**
     * Reads and checks the parameters (on the calling thread).
     *
     * @throws RpcException INVALID_PARAMS for anything malformed or not allowed together
     */
    static Request parse(JsonView a) {
        Request r = new Request();
        String action = a.str("action");
        if (action == null) {
            throw Errors.invalid("missing required parameter 'action'", Errors.data("param", "action"));
        }
        try {
            r.kind = Kind.valueOf(action.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw Errors.invalid("unknown action '" + action + "': expected one of MOVE, PRESS, RELEASE, CLICK, "
                    + "DOUBLE_CLICK, DRAG, WHEEL", Errors.data("param", "action"));
        }
        String via = a.str("via", ROUTED).trim().toUpperCase(Locale.ROOT);
        if (!ROUTED.equals(via) && !ROBOT.equals(via)) {
            throw Errors.invalid("unknown via '" + a.str("via") + "': expected ROUTED or ROBOT",
                    Errors.data("param", "via"));
        }
        r.via = via;
        String button = a.str("button", "LEFT").trim().toUpperCase(Locale.ROOT);
        if ("LEFT".equals(button)) {
            r.button = MouseEvent.BUTTON1;
        } else if ("MIDDLE".equals(button)) {
            r.button = MouseEvent.BUTTON2;
        } else if ("RIGHT".equals(button)) {
            r.button = MouseEvent.BUTTON3;
        } else {
            throw Errors.invalid("unknown button '" + a.str("button") + "': expected LEFT, MIDDLE or RIGHT",
                    Errors.data("param", "button"));
        }
        List<Object> mods = a.list("modifiers");
        r.modifiers = mods == null ? 0 : Keys.modifiers(mods);
        long count = a.num("clickCount", r.kind == Kind.DOUBLE_CLICK ? 2 : 1);
        if (count < 1 || count > 3) {
            throw Errors.invalid("clickCount must be 1 to 3", Errors.data("param", "clickCount"));
        }
        r.clickCount = (int) count;
        JsonView wheel = a.obj("wheel");
        if (r.kind == Kind.WHEEL) {
            long n = wheel == null ? 0 : wheel.num("notches", 0);
            if (n == 0 || Math.abs(n) > MAX_NOTCHES) {
                throw Errors.invalid("WHEEL needs wheel {notches: n}, n not 0 and at most " + MAX_NOTCHES
                        + " either way", Errors.data("param", "wheel"));
            }
            r.notches = (int) n;
        }
        long steps = a.num("steps", DEFAULT_STEPS);
        if (steps < 1 || steps > MAX_STEPS) {
            throw Errors.invalid("steps must be 1 to " + MAX_STEPS, Errors.data("param", "steps"));
        }
        r.steps = (int) steps;
        r.force = a.bool("force", false);
        r.expectTarget = a.str("expectTarget");
        r.requireFocus = a.bool("requireFocus", true);
        Object point = a.get("point");
        Object to = a.get("to");
        if (to != null && r.kind != Kind.DRAG) {
            throw Errors.invalid("'to' is for DRAG only", Errors.data("param", "to"));
        }
        if (r.kind == Kind.DRAG && to == null) {
            throw Errors.invalid("DRAG needs 'to'", Errors.data("param", "to"));
        }
        if (point != null) {
            if (a.get("target") != null || a.get("by") != null) {
                throw Errors.invalid("give either 'point' or 'target'/'by', not both", Errors.data("param", "point"));
            }
            if (a.get("region") != null || a.get("row") != null || a.get("at") != null) {
                throw Errors.invalid("'region', 'row' and 'at' are for the component form (target or by)",
                        Errors.data("param", a.get("region") != null ? "region" : a.get("row") != null ? "row"
                                : "at"));
            }
            r.point = PointSpace.point(point, "point");
            r.space = PointSpace.parse(a.get("space"));
            if (to != null) {
                r.to = PointSpace.point(to, "to");
            }
        } else {
            r.target = TargetSpec.parse(a, true);
            String region = a.str("region");
            if (region != null && !SwingSpis.validRegionId(region)) {
                throw Errors.invalid("region id '" + region + "' is not valid: " + SwingSpis.REGION_ID.pattern(),
                        Errors.data("param", "region"));
            }
            r.region = region;
            if (a.get("row") != null) {
                long row = a.num("row", -1);
                if (row < 0 || row > Integer.MAX_VALUE) {
                    throw Errors.invalid("row must be a model index of at least 0", Errors.data("param", "row"));
                }
                r.row = Integer.valueOf((int) row);
            }
            if (region != null && r.row != null) {
                throw Errors.invalid("give either 'region' or 'row', not both", Errors.data("param", "region"));
            }
            double[] at = fractions(a.get("at"), "at", 0.5, 0.5);
            r.fx = at[0];
            r.fy = at[1];
            if (to != null) {
                double[] t = fractions(to, "to", 0.5, 0.5);
                r.tfx = Double.valueOf(t[0]);
                r.tfy = Double.valueOf(t[1]);
            }
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static double[] fractions(Object v, String param, double dx, double dy) {
        if (v == null) {
            return new double[] {dx, dy};
        }
        if (v instanceof Map) {
            Object fx = ((Map<String, Object>) v).get("fx");
            Object fy = ((Map<String, Object>) v).get("fy");
            if (fx instanceof Number && fy instanceof Number) {
                double x = ((Number) fx).doubleValue();
                double y = ((Number) fy).doubleValue();
                if (x >= 0 && x <= 1 && y >= 0 && y <= 1) {
                    return new double[] {x, y};
                }
            }
        }
        throw Errors.invalid("parameter '" + param + "' must be {fx, fy}, fractions of 0 to 1",
                Errors.data("param", param));
    }

    // ---------------------------------------------------------------------------------------------- addressing

    /** Where the action lands: the window, the point and (DRAG) the end point, in the window's coordinates. */
    static final class Target {
        Window window;
        Point p;
        Point to;
        boolean screenSpace;
    }

    /** Resolves the address on the EDT (section 3.3, "Addressing"). */
    Target address(ComponentView view, SwingSpis spis, Request r) {
        Target t = new Target();
        if (r.point != null) {
            PointSpace.At at = r.space.resolve(ctx, windows, recent, r.point);
            t.window = at.window;
            t.p = at.point;
            t.screenSpace = at.screen;
            if (r.to != null) {
                PointSpace.At end = r.space.resolve(ctx, windows, recent, r.to);
                if (end.window != at.window) {
                    throw Errors.invalid("'to' lies in another window than 'point'", Errors.data("param", "to"));
                }
                t.to = end.point;
            }
            return t;
        }
        Component c = r.target.resolve(view);
        if (!c.isShowing()) {
            throw Errors.invalid(view.describe(c) + " is not showing", Errors.data("ref", view.ref(c), "reason",
                    "not showing"));
        }
        Rectangle rect = new Rectangle(0, 0, c.getWidth(), c.getHeight());
        if (r.region != null) {
            rect = spis.rectOf(c, r.region);
            if (rect == null) {
                throw Errors.notFound("no region '" + r.region + "' on " + view.describe(c),
                        Errors.data("region", r.region, "ref", view.ref(c)));
            }
        } else if (r.row != null) {
            rect = rowRect(c, r.row.intValue());
            if (rect == null) {
                throw Errors.notFound("no row " + r.row + " in " + view.describe(c), Errors.data("row",
                        Long.valueOf(r.row.longValue()), "ref", view.ref(c)));
            }
        }
        Window w = c instanceof Window ? (Window) c : Windows.windowOf(c);
        t.window = w;
        t.p = SwingUtilities.convertPoint(c, at(rect, r.fx, r.fy), w);
        if (r.tfx != null) {
            t.to = SwingUtilities.convertPoint(c, at(rect, r.tfx.doubleValue(), r.tfy.doubleValue()), w);
        }
        return t;
    }

    /** The point of a rectangle at fractions of its size, kept inside it. */
    static Point at(Rectangle rect, double fx, double fy) {
        int x = rect.x + (int) Math.floor(fx * rect.width);
        int y = rect.y + (int) Math.floor(fy * rect.height);
        x = Math.max(rect.x, Math.min(x, rect.x + Math.max(0, rect.width - 1)));
        y = Math.max(rect.y, Math.min(y, rect.y + Math.max(0, rect.height - 1)));
        return new Point(x, y);
    }

    /** A table's model row (converted to the view first) or a list's index as a rectangle, or null. */
    static Rectangle rowRect(Component c, int row) {
        if (c instanceof JTable) {
            JTable table = (JTable) c;
            if (row >= table.getModel().getRowCount()) {
                return null;
            }
            int view = table.convertRowIndexToView(row);
            if (view < 0) {
                return null;
            }
            Rectangle cell = table.getCellRect(view, 0, true);
            return new Rectangle(0, cell.y, table.getWidth(), cell.height);
        }
        if (c instanceof JList) {
            JList<?> list = (JList<?>) c;
            if (row >= list.getModel().getSize()) {
                return null;
            }
            return list.getCellBounds(row, row);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------- routed

    /** What the pre-check decided: the hit, the events and the call's state. */
    static final class Plan {
        Window window;
        /** The events' source: the window (a stand-in component in headless tests). */
        Component source;
        /** Whether a modal dialog blocks the source now (the per-event re-read, D352). */
        BooleanSupplier blocked;
        HitTest.Hit hit;
        boolean wasFocused;
        final List<MouseEvent> events = new ArrayList<MouseEvent>();
    }

    /** The call's dispatch state, shared by its event tasks (on the EDT) and the waiting caller. */
    static final class State {
        final int of;
        final boolean pressIntended;
        /** Events whose dispatch began. */
        int started;
        /** Events whose dispatch returned ({@code dispatched.sent}). */
        int sent;
        boolean stopped;
        String reason;
        boolean pressDown;
        boolean balanced;
        int pressMods;
        int button;
        final CountDownLatch done = new CountDownLatch(1);

        State(int of, boolean pressIntended) {
            this.of = of;
            this.pressIntended = pressIntended;
        }
    }

    /** {@code swing.pointer {via: ROUTED}}. */
    Map<String, Object> routed(final Invocation inv, final Request r) throws Exception {
        final long tag = nextWhen();
        final Plan plan = Edt.read(ctx, inv.remainingMs(), new Callable<Plan>() {
            @Override
            public Plan call() {
                ComponentView view = new ComponentView(ctx);
                SwingSpis spis = new SwingSpis(ctx);
                Target t = address(view, spis, r);
                HitTest.Hit hit = HitTest.at(view, windows, spis, t.window, t.p, null, t.screenSpace,
                        ctx.events().lastSeq());
                precheck(view, r, hit, false);
                Plan p = new Plan();
                p.window = t.window;
                p.source = t.window;
                final Window w = t.window;
                p.blocked = new BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        return windows.blockingModal(w) != null;
                    }
                };
                p.hit = hit;
                p.wasFocused = t.window.isFocused();
                Point origin = t.window.getLocationOnScreen();
                Point last;
                synchronized (lastRouted) {
                    last = lastRouted.get(t.window);
                }
                p.events.addAll(events(r, t.window, t.p, t.to, origin, last, tag, popupOnPress));
                return p;
            }
        });
        final Window window = plan.window;
        final State st = new State(plan.events.size(), r.kind == Kind.PRESS);
        st.button = r.button;
        st.pressMods = InputEvent.getMaskForButton(r.button) | r.modifiers;
        final AtomicReference<Component> received = new AtomicReference<Component>();
        final int receivedId = r.kind == Kind.MOVE ? MouseEvent.MOUSE_MOVED
                : r.kind == Kind.WHEEL ? MouseEvent.MOUSE_WHEEL : MouseEvent.MOUSE_PRESSED;
        AWTEventListener listener = new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (!(event instanceof MouseEvent)) {
                    return;
                }
                MouseEvent e = (MouseEvent) event;
                if (e.getWhen() != tag || e.getID() != receivedId) {
                    return;
                }
                Component source = e.getComponent();
                if (source == window && !HitTest.listens(window)) {
                    return; // the window itself only receives when it listens
                }
                if (receivedId == MouseEvent.MOUSE_WHEEL || received.get() == null) {
                    received.set(source); // the wheel's last receiver is the ancestor that handled it
                }
            }
        };
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                | AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        try {
            for (int i = 0; i < plan.events.size(); i++) {
                final int index = i;
                final MouseEvent e = plan.events.get(i);
                Edt.post(ctx, inv.remainingMs(), "swing.pointer", new Edt.Action() {
                    @Override
                    public void run() {
                        dispatch(plan, st, index, e, r.force);
                    }
                });
            }
            long wait = Math.max(0, inv.remainingMs() - RESULT_RESERVE_MS - SAFETY_MS);
            boolean finished = st.done.await(wait, TimeUnit.MILLISECONDS);
            boolean promise = finished ? false : cancel(st);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("via", ROUTED);
            m.put("window", ref(window));
            m.put("hit", hitSubset(plan.hit));
            int sent;
            String reason;
            boolean balanced;
            synchronized (st) {
                sent = st.sent;
                reason = st.reason;
                balanced = st.balanced || promise;
            }
            boolean fired = reason == null && sent == st.of;
            m.put("fired", Boolean.valueOf(fired));
            if (!fired) {
                m.put("reason", reason != null ? reason : EDT_BUSY);
            }
            Map<String, Object> dispatched = new LinkedHashMap<String, Object>();
            dispatched.put("sent", Long.valueOf(sent));
            dispatched.put("of", Long.valueOf(st.of));
            m.put("dispatched", dispatched);
            m.put("balanced", Boolean.valueOf(balanced));
            Object[] after = afterRead(inv, window, plan.wasFocused, !EDT_BUSY.equals(reason));
            Component got = received.get();
            m.put("received", got == null ? null : ref(got));
            m.put("focusOwner", after[0]);
            m.put("activated", after[1]);
            m.put("seq", Long.valueOf(ctx.events().lastSeq()));
            return m;
        } finally {
            toolkit.removeAWTEventListener(listener);
        }
    }

    /**
     * The deadline passed (the caller's side): sets the {@code cancelled} state unless the sequence already ended,
     * so that no event of the call is delivered later (D352).
     *
     * @return whether a dispatched press is still to be balanced by the next task the EDT runs
     */
    static boolean cancel(State st) {
        synchronized (st) {
            if (!st.stopped && st.sent < st.of) {
                st.stopped = true;
                st.reason = EDT_BUSY;
            }
            return st.pressDown && !st.pressIntended && st.stopped && !st.balanced;
        }
    }

    /** One event task (EDT): the two checks, then the event, or the balance (D352). Package-private for tests. */
    void dispatch(Plan plan, State st, int index, MouseEvent e, boolean force) {
        boolean balance;
        boolean act = false;
        boolean finish = false;
        synchronized (st) {
            if (st.stopped) {
                balance = needsBalance(st);
            } else if (!force && plan.blocked.getAsBoolean()) {
                st.stopped = true;
                st.reason = MODAL_OPENED;
                balance = needsBalance(st);
                finish = true;
            } else {
                balance = false;
                act = true;
                st.started++;
                if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                    st.pressDown = true;
                } else if (e.getID() == MouseEvent.MOUSE_RELEASED) {
                    st.pressDown = false;
                }
            }
        }
        if (balance) {
            balance(plan.source, st, e.getWhen());
        }
        if (finish) {
            st.done.countDown();
        }
        if (!act) {
            return;
        }
        plan.source.dispatchEvent(e);
        synchronized (st) {
            st.sent++;
        }
        if (plan.window != null) {
            synchronized (lastRouted) {
                lastRouted.put(plan.window, new Point(e.getX(), e.getY()));
            }
        }
        if (index == st.of - 1) {
            st.done.countDown();
        }
    }

    /** True (and marked) when a dispatched press of a cancelled or withheld sequence is still to be balanced. */
    private static boolean needsBalance(State st) {
        if (st.pressDown && !st.pressIntended && !st.balanced) {
            st.balanced = true;
            st.pressDown = false;
            return true;
        }
        return false;
    }

    /**
     * The disarming balance (D352): a routed {@code MOUSE_DRAGGED} to the window point (-1, -1), outside every
     * component, then a {@code MOUSE_RELEASED} there with click count 0 and no click. EDT only.
     */
    private void balance(Component w, State st, long when) {
        Point origin;
        try {
            origin = w.getLocationOnScreen();
        } catch (RuntimeException e) {
            origin = new Point(0, 0);
        }
        w.dispatchEvent(new MouseEvent(w, MouseEvent.MOUSE_DRAGGED, when, st.pressMods, -1, -1, origin.x - 1,
                origin.y - 1, 0, false, MouseEvent.NOBUTTON));
        w.dispatchEvent(new MouseEvent(w, MouseEvent.MOUSE_RELEASED, when, st.pressMods
                & ~InputEvent.getMaskForButton(st.button), -1, -1, origin.x - 1, origin.y - 1, 0, false, st.button));
        if (w instanceof Window) {
            synchronized (lastRouted) {
                lastRouted.put((Window) w, new Point(-1, -1));
            }
        }
    }

    /**
     * The routed event sequence of an action (BS10 RESULT "What {@code swing.pointer} ROUTED must do"): a move first
     * when the last routed position differs, press, release and click with the button masks and counts, the popup
     * trigger for button 3, a drag's samples and its release with click count 0 and no click, one wheel event per
     * notch. Package-private for the headless tests.
     */
    static List<MouseEvent> events(Request r, Component w, Point p, Point to, Point origin, Point last, long when,
                                   boolean popupOnPress) {
        List<MouseEvent> out = new ArrayList<MouseEvent>();
        int mods = r.modifiers;
        int down = InputEvent.getMaskForButton(r.button) | mods;
        boolean popup = r.button == MouseEvent.BUTTON3;
        if (r.kind == Kind.MOVE || last == null || !last.equals(p)) {
            out.add(new MouseEvent(w, MouseEvent.MOUSE_MOVED, when, mods, p.x, p.y, origin.x + p.x, origin.y + p.y, 0,
                    false, MouseEvent.NOBUTTON));
        }
        switch (r.kind) {
            case PRESS:
                out.add(press(w, p, origin, when, down, r.clickCount, popup && popupOnPress, r.button));
                break;
            case RELEASE:
                out.add(release(w, p, origin, when, mods, r.clickCount, popup && !popupOnPress, r.button));
                break;
            case CLICK:
                click(out, w, p, origin, when, down, mods, r.clickCount, popup, popupOnPress, r.button);
                break;
            case DOUBLE_CLICK:
                click(out, w, p, origin, when, down, mods, 1, popup, popupOnPress, r.button);
                click(out, w, p, origin, when, down, mods, 2, popup, popupOnPress, r.button);
                break;
            case DRAG: {
                out.add(press(w, p, origin, when, down, 1, popup && popupOnPress, r.button));
                Point q = p;
                for (int i = 1; i <= r.steps; i++) {
                    q = new Point(p.x + (to.x - p.x) * i / r.steps, p.y + (to.y - p.y) * i / r.steps);
                    out.add(new MouseEvent(w, MouseEvent.MOUSE_DRAGGED, when, down, q.x, q.y, origin.x + q.x,
                            origin.y + q.y, 0, false, MouseEvent.NOBUTTON));
                }
                // the native release after a drag has click count 0 and no click follows (BS10)
                out.add(release(w, q, origin, when, mods, 0, false, r.button));
                break;
            }
            case WHEEL: {
                int rotation = r.notches < 0 ? -1 : 1;
                for (int i = 0; i < Math.abs(r.notches); i++) {
                    out.add(new MouseWheelEvent(w, MouseEvent.MOUSE_WHEEL, when, mods, p.x, p.y, origin.x + p.x,
                            origin.y + p.y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, SCROLL_AMOUNT, rotation));
                }
                break;
            }
            default: // MOVE: the move alone
                break;
        }
        return out;
    }

    private static void click(List<MouseEvent> out, Component w, Point p, Point origin, long when, int down, int mods,
                              int count, boolean popup, boolean popupOnPress, int button) {
        out.add(press(w, p, origin, when, down, count, popup && popupOnPress, button));
        out.add(release(w, p, origin, when, mods, count, popup && !popupOnPress, button));
        out.add(new MouseEvent(w, MouseEvent.MOUSE_CLICKED, when, mods, p.x, p.y, origin.x + p.x, origin.y + p.y,
                count, false, button));
    }

    private static MouseEvent press(Component w, Point p, Point origin, long when, int down, int count,
                                    boolean popup, int button) {
        return new MouseEvent(w, MouseEvent.MOUSE_PRESSED, when, down, p.x, p.y, origin.x + p.x, origin.y + p.y, count,
                popup, button);
    }

    private static MouseEvent release(Component w, Point p, Point origin, long when, int mods, int count,
                                      boolean popup, int button) {
        return new MouseEvent(w, MouseEvent.MOUSE_RELEASED, when, mods, p.x, p.y, origin.x + p.x, origin.y + p.y,
                count, popup, button);
    }

    // ------------------------------------------------------------------------------------------------- shared

    /**
     * The pre-check's refusals (section 3.3 step 1, section 3.4): {@code MODAL_OPEN} (routed only), the covering
     * rule (routed: a ranked window; Robot: any window), {@code expectTarget}; all but {@code expectTarget} skipped
     * by {@code force}. EDT only.
     */
    void precheck(ComponentView view, Request r, HitTest.Hit hit, boolean robot) {
        if (!robot && !r.force) {
            Object blockedBy = hit.json.get("blockedBy");
            if (blockedBy instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> b = (Map<String, Object>) blockedBy;
                Map<String, Object> data = Errors.data("dialog", b.get("ref"), "title", b.get("title"), "ref",
                        hit.target == null ? view.ref(hit.window) : view.ref(hit.target));
                if (Boolean.TRUE.equals(b.get("inWindow"))) {
                    data.put("inWindow", Boolean.TRUE);
                }
                throw new RpcException(ErrorCodes.MODAL_OPEN, "the point lies in " + view.describe(hit.window)
                        + ", which the dialog '" + b.get("title") + "' (" + b.get("ref") + ") blocks; answer the "
                        + "dialog first, or pass force:true to act anyway", data);
            }
        }
        if (!r.force && (robot ? !hit.covering.isEmpty() : hit.rankedCovering())) {
            throw new RpcException(ErrorCodes.INPUT_REJECTED, "another window of the JVM covers the point"
                    + (robot ? " (the Robot presses whatever is really on top)" : "") + "; pass force:true to act "
                    + "anyway", Errors.data("reason", "covered", "covering", hit.json.get("covering")));
        }
        if (r.expectTarget != null) {
            String actual = hit.target == null ? null : view.ref(hit.target);
            if (!r.expectTarget.equals(actual)) {
                throw new RpcException(ErrorCodes.INPUT_REJECTED, "the component at the point is " + actual
                        + ", not the expected " + r.expectTarget, Errors.data("reason", "target changed", "hit",
                        hit.json));
            }
        }
    }

    /** The pointer result's {@code hit}: the pre-check's target, targetKind, deepest, enabled, masked, covering. */
    static Map<String, Object> hitSubset(HitTest.Hit hit) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (String key : new String[] {"target", "targetKind", "deepest", "enabled", "masked", "covering"}) {
            m.put(key, hit.json.get(key));
        }
        return m;
    }

    /** The focus owner after the sequence and whether the window became the focused window; nulls on a busy EDT. */
    Object[] afterRead(Invocation inv, final Window window, final boolean wasFocused, boolean tryRead)
            throws Exception {
        if (!tryRead) {
            return new Object[] {null, Boolean.FALSE};
        }
        try {
            return Edt.read(ctx, Math.max(1, inv.remainingMs() - SAFETY_MS), new Callable<Object[]>() {
                @Override
                public Object[] call() {
                    Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    return new Object[] {owner == null ? null : ref(owner),
                        Boolean.valueOf(!wasFocused && window.isFocused())};
                }
            });
        } catch (RpcException e) {
            if (e.error().code() == ErrorCodes.TIMEOUT) {
                return new Object[] {null, Boolean.FALSE};
            }
            throw e;
        }
    }

    String ref(Component c) {
        return ctx.handles().handleFor(c, ComponentView.KIND);
    }

    /** A {@code when} no other call uses (milliseconds, kept increasing). */
    static long nextWhen() {
        long now = System.currentTimeMillis();
        while (true) {
            long prev = WHEN.get();
            long next = Math.max(now, prev + 1);
            if (WHEN.compareAndSet(prev, next)) {
                return next;
            }
        }
    }
}
