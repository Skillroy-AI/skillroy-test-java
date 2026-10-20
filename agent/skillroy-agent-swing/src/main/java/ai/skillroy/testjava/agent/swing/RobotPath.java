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
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.InputVeto;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * The V3 tier's agent half (phase7-contracts.md section 3.4, A63, A65, D290, D341, D343, D344): {@code swing.pointer
 * {via: ROBOT}} and a person's {@code swing.capture {mode: ROBOT}}, through {@link RobotPort} on an agent worker, never
 * the EDT. Reachable only with the agent option {@code robot} (the module sets both capabilities
 * {@code UNAVAILABLE("robot: not enabled…")} without it and never constructs this class's port); the controller's
 * half of the double opt-in is the executor's and the session's.
 *
 * <ul>
 *   <li><b>The capability probe</b> ({@link #declare}): after {@code checkAwt}, on a worker: the Robot is constructed
 *       for the default screen and nothing moves; {@code OK("robot")}, or {@code UNAVAILABLE} with the reason.</li>
 *   <li><b>The delivery probe</b>: before the first action after agent start, and again after an action whose press
 *       was not received, the real pointer is moved to the target point and a native {@code MOUSE_MOVED} at a
 *       component of the target window is awaited for at most {@value #PROBE_MS} ms (BS9: macOS without the
 *       Accessibility permission drops everything silently); nothing arriving degrades {@code swing.pointer.robot}
 *       and refuses the call; the pointer is put back where it was either way. It runs before the pre-check, never
 *       between the pre-check and the press; its own read applies the pre-check's refusals first (nothing moves for a
 *       refused call), and it is skipped for a window a modal dialog blocks or a point another window covers under
 *       {@code force}, where AWT would drop or divert its move (fix wave of the Phase 7 review, FA).</li>
 *   <li><b>The pre-check</b>: a window the toolkit reports iconified, or one such a frame owns, refuses the point
 *       ({@code NOT_FOUND "iconified"}); the hit-test; a screen point (and a drag's end point) no screen device shows
 *       is refused ({@code NOT_FOUND "off-screen"}: the platform would press at the nearest screen edge; Phase 7
 *       security fix, S104, S105); {@code requireFocus} (default true) refuses a JVM without a focused window
 *       ({@code INPUT_REJECTED "not focused"}); any covering window of the JVM refuses the point unless
 *       {@code force}, but an owner of the target's window while that window is the active one (gap 101). {@code modifiers} are refused (the Robot path holds no key; S124).</li>
 *   <li><b>The press</b>: {@code PRESS} and {@code RELEASE} are refused; a press and its release run in one worker task
 *       with no wait between them, a {@code finally} releases every button the call pressed before anything else, and
 *       the pointer is restored only afterwards (D343).</li>
 *   <li><b>The observation</b>: an {@code AWTEventListener} for the call's duration; nothing received is
 *       {@code fired: false, reason: "NOT_DELIVERED"}, a receiver outside the target {@code "received elsewhere"};
 *       either makes the next action probe delivery again (S105).</li>
 * </ul>
 *
 * <p>Thread-safe.
 */
final class RobotPath {

    /** The capabilities' reason without the agent option. */
    static final String NOT_ENABLED = "robot: not enabled (agent option robot=true)";
    /** The capabilities' reason from the moment AWT is confirmed until the capability probe has run (L2-F8). */
    static final String PROBE_NOT_RUN = "robot: probe not run";
    /** The reason of a failed delivery probe. */
    static final String NOT_DELIVERED_REASON = "robot: native input is not delivered (no event within 1000 ms)";
    /** {@code fired: false} reasons. */
    static final String NOT_DELIVERED = "NOT_DELIVERED";
    static final String RECEIVED_ELSEWHERE = "received elsewhere";
    /** The delivery probe's bound. */
    static final long PROBE_MS = 1000;
    /** The longest wait for a press to be observed (within the call's remaining time). */
    static final long OBSERVE_MAX_MS = 3000;
    /** The sentence a glass capture carries. */
    static final String GLASS_NOTE = "pixels inside a window's rectangle may belong to a window of another process "
            + "that covers it";

    /**
     * The screens as the toolkit reports them (Phase 7 security fix, S105, S126): the bounds of every screen device's
     * default configuration, and the UI scale of the configuration at a screen point, the one {@code Robot} reads for a
     * screen capture. A package-private seam, so that a headless test can say what the screens are.
     */
    interface Screens {
        /** The bounds of every screen device's default configuration, in screen coordinates. */
        List<Rectangle> bounds();

        /** {@code {scaleX, scaleY}} of the configuration whose bounds hold the point (the default screen's else). */
        double[] scaleAt(double x, double y);
    }

    /** The toolkit's screens: {@code GraphicsEnvironment.getScreenDevices()}. */
    static final Screens TOOLKIT_SCREENS = new Screens() {
        @Override
        public List<Rectangle> bounds() {
            List<Rectangle> out = new ArrayList<Rectangle>();
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                out.add(d.getDefaultConfiguration().getBounds());
            }
            return out;
        }

        @Override
        public double[] scaleAt(double x, double y) {
            GraphicsConfiguration at = defaultDevice().getDefaultConfiguration();
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                GraphicsConfiguration gc = d.getDefaultConfiguration();
                if (gc.getBounds().contains(x, y)) {
                    at = gc;
                    break;
                }
            }
            AffineTransform tx = at.getDefaultTransform();
            return new double[] {tx.getScaleX(), tx.getScaleY()};
        }
    };

    /** The screens seam (tests only replace it, and put {@link #TOOLKIT_SCREENS} back). */
    static volatile Screens screens = TOOLKIT_SCREENS;

    private final AgentContext ctx;
    private final Windows windows;
    private final Pointer pointer;
    private final RecentCaptures recent;
    private final RobotFactory factory;
    private final Map<GraphicsDevice, RobotPort> ports = new IdentityHashMap<GraphicsDevice, RobotPort>();
    private volatile boolean deliveryOk;

    RobotPath(AgentContext ctx, Windows windows, Pointer pointer, RecentCaptures recent, RobotFactory factory) {
        this.ctx = ctx;
        this.windows = windows;
        this.pointer = pointer;
        this.recent = recent;
        this.factory = factory;
    }

    // ------------------------------------------------------------------------------------------------ capability

    /**
     * The capability probe (section 3.9): {@code UNAVAILABLE("headless")} in a headless JVM, {@code UNAVAILABLE(
     * "robot: <message>")} when the Robot cannot be constructed, else {@code OK(strategy = "robot")}. Constructs the
     * Robot and moves nothing. Run on an agent worker after {@code checkAwt} confirmed AWT.
     */
    void declare(boolean headless) {
        if (headless) {
            setBoth(CapabilityState.UNAVAILABLE, SwingModule.HEADLESS_REASON, null);
            return;
        }
        try {
            port(defaultDevice());
            setBoth(CapabilityState.OK, null, "robot");
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError && !(t instanceof OutOfMemoryError)) {
                throw (VirtualMachineError) t;
            }
            setBoth(CapabilityState.UNAVAILABLE, "robot: " + (t.getMessage() == null ? t.getClass().getSimpleName()
                    : t.getMessage()), null);
        }
    }

    private void setBoth(CapabilityState state, String reason, String strategy) {
        ctx.capabilities().set(SwingModule.CAP_POINTER_ROBOT, state, reason, strategy);
        ctx.capabilities().set(SwingModule.CAP_CAPTURE_ROBOT, state, reason, strategy);
    }

    private static GraphicsDevice defaultDevice() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    }

    /** The port of a device, constructed here when it has none yet: the capability probe's, already on a worker. */
    private RobotPort port(GraphicsDevice device) throws Exception {
        synchronized (ports) {
            RobotPort p = ports.get(device);
            if (p == null) {
                p = factory.create(device);
                ports.put(device, p);
            }
            return p;
        }
    }

    /**
     * The port of the window's device (the default screen's when it has none). The default device's port is the one
     * the capability probe built; a port for another device is constructed on an agent worker within the call's
     * remaining time, never on the call's thread (section 3.4; Phase 7 security fix, S121).
     */
    private RobotPort portFor(final Window w, Invocation inv) throws Exception {
        GraphicsDevice d = null;
        try {
            if (w != null && w.getGraphicsConfiguration() != null) {
                d = w.getGraphicsConfiguration().getDevice();
            }
        } catch (RuntimeException ignored) {
            // the default screen
        }
        final GraphicsDevice device = d != null ? d : defaultDevice();
        synchronized (ports) {
            RobotPort p = ports.get(device);
            if (p != null) {
                return p;
            }
        }
        RobotPort made = ctx.threads().call(ThreadPolicy.WORKER, new Callable<RobotPort>() {
            @Override
            public RobotPort call() throws Exception {
                return factory.create(device);
            }
        }, Math.max(1, inv.remainingMs() - Pointer.SAFETY_MS));
        synchronized (ports) {
            RobotPort p = ports.get(device);
            if (p == null) {
                ports.put(device, made);
                p = made;
            }
            return p;
        }
    }

    // --------------------------------------------------------------------------------------------------- pointer

    /** What the Robot pre-check decided. */
    private static final class Plan {
        Window window;
        HitTest.Hit hit;
        Point screen;
        Point screenTo;
        boolean wasFocused;
    }

    /** {@code swing.pointer {via: ROBOT}} (the capability is checked by the caller). */
    Map<String, Object> pointer(final Invocation inv, final Pointer.Request r) throws Exception {
        if (r.kind == Pointer.Kind.PRESS || r.kind == Pointer.Kind.RELEASE) {
            throw Errors.invalid("a Robot press is never left held: use CLICK or DRAG", Errors.data("param",
                    "action", "via", Pointer.ROBOT));
        }
        refuseModifiers(r);
        if (!deliveryOk) {
            // the probe's own read refuses what the pre-check would refuse, so that nothing moves for such a call
            // (fix wave of the Phase 7 review, FA, L2-F4); the pre-check below repeats it right before the press
            Plan where = Edt.read(ctx, inv.remainingMs(), new Callable<Plan>() {
                @Override
                public Plan call() {
                    return check(r);
                }
            });
            if (where.hit.blocker == null && !where.hit.robotCovering()) {
                probe(inv, where.window, where.screen);
            }
            // else AWT drops the probe's move into a blocked window, and a (forced) covering window takes it: the probe
            // could prove nothing, so the action's own observation reports what arrived (NOT_DELIVERED, received
            // elsewhere) and the capability is not degraded
        }
        final Plan plan = Edt.read(ctx, inv.remainingMs(), new Callable<Plan>() {
            @Override
            public Plan call() {
                return check(r);
            }
        });
        final int observed = r.kind == Pointer.Kind.MOVE ? MouseEvent.MOUSE_MOVED
                : r.kind == Pointer.Kind.WHEEL ? MouseEvent.MOUSE_WHEEL : MouseEvent.MOUSE_PRESSED;
        final AtomicReference<Component> received = new AtomicReference<Component>();
        final CountDownLatch got = new CountDownLatch(1);
        AWTEventListener listener = new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (event instanceof MouseEvent && event.getID() == observed && received.get() == null) {
                    Component source = ((MouseEvent) event).getComponent();
                    if (source != null && Windows.windowOf(source) != null) {
                        received.set(source);
                        got.countDown();
                    }
                }
            }
        };
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                | AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        try {
            final RobotPort port = portFor(plan.window, inv);
            ctx.threads().call(ThreadPolicy.WORKER, new Callable<Object>() {
                @Override
                public Object call() {
                    act(port, r, plan.screen, plan.screenTo);
                    return null;
                }
            }, Math.max(1, inv.remainingMs() - Pointer.SAFETY_MS));
            long wait = Math.min(OBSERVE_MAX_MS, Math.max(0, inv.remainingMs() - Pointer.RESULT_RESERVE_MS
                    - Pointer.SAFETY_MS));
            got.await(wait, TimeUnit.MILLISECONDS);
        } finally {
            toolkit.removeAWTEventListener(listener);
        }
        Component target = plan.hit.target;
        Component in = received.get();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("via", Pointer.ROBOT);
        m.put("window", pointer.ref(plan.window));
        m.put("hit", Pointer.hitSubset(plan.hit));
        if (in == null) {
            deliveryOk = false;
            m.put("fired", Boolean.FALSE);
            m.put("reason", NOT_DELIVERED);
        } else if (target != null && in != target && !SwingUtilities.isDescendingFrom(in, target)) {
            // an unexplained receiver is probed again before the next press, never repeated without a probe (Phase 7
            // security fix, S105: a point the platform moved to a screen's edge lands on another component)
            deliveryOk = false;
            m.put("fired", Boolean.FALSE);
            m.put("reason", RECEIVED_ELSEWHERE);
        } else {
            m.put("fired", Boolean.TRUE);
        }
        m.put("received", in == null ? null : pointer.ref(in));
        Object[] after = pointer.afterRead(inv, plan.window, plan.wasFocused, true);
        m.put("focusOwner", after[0]);
        m.put("activated", after[1]);
        m.put("stackingAssumed", Boolean.TRUE);
        m.put("seq", Long.valueOf(ctx.events().lastSeq()));
        return m;
    }

    /**
     * The pre-check (section 3.4), one EDT read: the address, the hit-test (a point outside the window is
     * {@code NOT_FOUND outside}), a {@code DRAG}'s end point bounded by the window and checked for covering windows too
     * (fix wave, L2-S2), the input veto (Phase 8, phase8-contracts.md 3.2: {@code INPUT_REJECTED vetoed} for the
     * target, the deepest component, the component form's component and the drag's end, never skipped by
     * {@code force}), {@code requireFocus} ({@code INPUT_REJECTED not focused}), then the covering rule and
     * {@code expectTarget} ({@link Pointer#precheck}). Run by the delivery probe's read and again right before the
     * press, so a veto refuses before anything moves. EDT only.
     */
    private Plan check(Pointer.Request r) {
        ComponentView view = new ComponentView(ctx);
        SwingSpis spis = new SwingSpis(ctx);
        Pointer.Target t = pointer.address(view, spis, r);
        if (Windows.hidden(t.window)) {
            // Phase 7 security fix, S104: AWT calls an iconified frame (and a window it owns) showing, but the glass
            // shows whatever lies at its last position
            throw Errors.notFound(view.describe(t.window) + " is iconified, or owned by an iconified frame: the "
                    + "screen does not show it, so a Robot press there would land on whatever the desktop shows; "
                    + "restore the window first", Errors.data("reason", "iconified", "window", view.ref(t.window)));
        }
        HitTest.Hit hit = HitTest.at(view, windows, spis, t.window, t.p, null, true, ctx.events().lastSeq());
        Point o = t.window.getLocationOnScreen();
        List<Rectangle> shown = screens.bounds();
        Point screen = new Point(o.x + t.p.x, o.y + t.p.y);
        requireOnAScreen(view, t.window, screen, shown, null);
        Point screenTo = null;
        if (t.to != null) {
            if (t.to.x < 0 || t.to.y < 0 || t.to.x >= t.window.getWidth() || t.to.y >= t.window.getHeight()) {
                throw Errors.notFound("the drag's end point (" + t.to.x + ", " + t.to.y + ") lies outside "
                        + view.describe(t.window), Errors.data("reason", "outside", "param", "to", "window",
                        view.ref(t.window)));
            }
            screenTo = new Point(o.x + t.to.x, o.y + t.to.y);
            requireOnAScreen(view, t.window, screenTo, shown, "to");
        }
        // Phase 8 (phase8-contracts.md 3.2 rule 2, D502): the input veto for the hit's target and deepest component,
        // the component form's component and a drag's end, before anything moves; never skipped by force
        Pointer.requireNotVetoed(view, hit, InputVeto.POINTER_ROBOT, t.component,
                t.to == null ? null : HitTest.targetsAt(spis, t.window, t.to));
        if (r.requireFocus && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == null) {
            throw new RpcException(ErrorCodes.INPUT_REJECTED, "the target JVM holds no focused window, so its windows "
                    + "may not be in front; bring it to the front (requireFocus)", Errors.data("reason",
                    "not focused"));
        }
        pointer.precheck(view, r, hit, true);
        if (screenTo != null && !r.force) {
            List<Object> covering = HitTest.coveringAt(view, windows, t.window, screenTo);
            if (!covering.isEmpty()) {
                throw new RpcException(ErrorCodes.INPUT_REJECTED, "another window of the JVM covers the drag's end "
                        + "point (the Robot releases on whatever is really on top); pass force:true to act anyway",
                        Errors.data("reason", "covered", "param", "to", "covering", covering));
            }
        }
        Plan p = new Plan();
        p.window = t.window;
        p.hit = hit;
        p.wasFocused = t.window.isFocused();
        p.screen = screen;
        p.screenTo = screenTo;
        return p;
    }

    /**
     * {@code NOT_FOUND off-screen} unless a screen device's bounds hold the screen point (Phase 7 security fix, S105):
     * the platform moves the pointer to the nearest screen edge instead (X11 keeps the pointer on the screen), and the
     * press would land there, on another component of the same window or on whatever lies at the edge.
     *
     * @param param null for the point, {@code "to"} for a drag's end point
     */
    private static void requireOnAScreen(ComponentView view, Window window, Point screen, List<Rectangle> shown,
                                         String param) {
        if (onAScreen(shown, screen)) {
            return;
        }
        Map<String, Object> data = Errors.data("reason", "off-screen", "window", view.ref(window), "screen",
                Capture.point(screen.x, screen.y));
        if (param != null) {
            data.put("param", param);
        }
        throw Errors.notFound((param == null ? "the point" : "the drag's end point") + " lies on no screen (screen "
                + "point " + screen.x + ", " + screen.y + " of " + view.describe(window) + "): the platform would move "
                + "the pointer to the nearest screen edge and press there; bring the window onto the screen", data);
    }

    /** True when one of the screens' bounds holds the point. */
    static boolean onAScreen(List<Rectangle> shown, Point screen) {
        for (Rectangle r : shown) {
            if (r.contains(screen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code INVALID_PARAMS} for {@code modifiers} with {@code via: ROBOT} (Phase 7 security fix, S124): the Robot
     * path presses no key, and V3 has no Robot keyboard in Phase 7, so a modified click would be pressed plain and
     * reported as the modified one.
     */
    static void refuseModifiers(Pointer.Request r) {
        if (r.modifiers != 0) {
            throw Errors.invalid("modifiers are refused with via: ROBOT (the Robot path holds no key in this "
                    + "version): click without them, or ask the person", Errors.data("param", "modifiers", "via",
                    Pointer.ROBOT));
        }
    }

    /**
     * The native sequence on the worker (D343): the pointer saved, moved, the buttons pressed and released with no
     * wait between, every button the call pressed released in a {@code finally} before anything else (a timeout or an
     * interruption too), then the pointer restored. Package-private for the headless tests.
     */
    static void act(RobotPort port, Pointer.Request r, Point p, Point to) {
        Point saved = port.pointer();
        int mask = InputEvent.getMaskForButton(r.button);
        boolean down = false;
        try {
            port.mouseMove(p.x, p.y);
            switch (r.kind) {
                case CLICK:
                    for (int i = 0; i < r.clickCount; i++) {
                        down = true; // before the press: one that throws after taking effect is released too (L2-F5)
                        port.mousePress(mask);
                        port.mouseRelease(mask);
                        down = false;
                    }
                    break;
                case DOUBLE_CLICK:
                    for (int i = 0; i < 2; i++) {
                        down = true;
                        port.mousePress(mask);
                        port.mouseRelease(mask);
                        down = false;
                    }
                    break;
                case DRAG: {
                    down = true;
                    port.mousePress(mask);
                    for (int i = 1; i <= r.steps; i++) {
                        port.mouseMove(p.x + (to.x - p.x) * i / r.steps, p.y + (to.y - p.y) * i / r.steps);
                        if (Thread.currentThread().isInterrupted()) {
                            break;
                        }
                    }
                    port.mouseRelease(mask);
                    down = false;
                    break;
                }
                case WHEEL: {
                    int rotation = r.notches < 0 ? -1 : 1;
                    for (int i = 0; i < Math.abs(r.notches); i++) {
                        port.mouseWheel(rotation);
                    }
                    break;
                }
                default: // MOVE
                    break;
            }
        } finally {
            if (down) {
                port.mouseRelease(mask);
            }
            if (saved != null) {
                port.mouseMove(saved.x, saved.y);
            }
        }
    }

    /**
     * The delivery probe: moves the real pointer to the point and awaits a native {@code MOUSE_MOVED} at a component
     * of the window for at most {@value #PROBE_MS} ms, then puts the pointer back where it was, whether the move
     * arrived or not (fix wave of the Phase 7 review, FA, L2-F6: the action that follows saves and restores the
     * person's location, not the probed point).
     *
     * @throws RpcException CAPABILITY_UNAVAILABLE (and {@code swing.pointer.robot} DEGRADED) when nothing arrives
     */
    void probe(Invocation inv, final Window window, final Point screen) throws Exception {
        // the port first, outside the listener's lifetime: a port that cannot be had leaves no listener behind (Phase
        // 7 security fix, S121)
        final RobotPort port = portFor(window, inv);
        final CountDownLatch moved = new CountDownLatch(1);
        AWTEventListener listener = new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (event.getID() == MouseEvent.MOUSE_MOVED || event.getID() == MouseEvent.MOUSE_ENTERED) {
                    Component source = ((MouseEvent) event).getComponent();
                    if (source != null && Windows.windowOf(source) == window) {
                        moved.countDown();
                    }
                }
            }
        };
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        final Point[] saved = new Point[1];
        boolean arrived;
        try {
            ctx.threads().call(ThreadPolicy.WORKER, new Callable<Object>() {
                @Override
                public Object call() {
                    saved[0] = port.pointer();
                    Point here = saved[0];
                    if (here != null && here.equals(screen)) {
                        port.mouseMove(screen.x + 1, screen.y);
                    }
                    port.mouseMove(screen.x, screen.y);
                    return null;
                }
            }, Math.max(1, inv.remainingMs() - Pointer.SAFETY_MS));
            arrived = moved.await(Math.min(PROBE_MS, Math.max(0, inv.remainingMs() - Pointer.SAFETY_MS)),
                    TimeUnit.MILLISECONDS);
        } finally {
            toolkit.removeAWTEventListener(listener);
        }
        if (saved[0] != null) {
            final Point back = saved[0];
            ctx.threads().call(ThreadPolicy.WORKER, new Callable<Object>() {
                @Override
                public Object call() {
                    port.mouseMove(back.x, back.y);
                    return null;
                }
            }, Math.max(1, inv.remainingMs() - Pointer.SAFETY_MS));
        }
        if (!arrived) {
            ctx.capabilities().set(SwingModule.CAP_POINTER_ROBOT, CapabilityState.DEGRADED, NOT_DELIVERED_REASON,
                    "robot");
            throw Errors.unavailable(SwingModule.CAP_POINTER_ROBOT, NOT_DELIVERED_REASON);
        }
        deliveryOk = true;
        ai.skillroy.testjava.protocol.Capability cap = ctx.capabilities().get(SwingModule.CAP_POINTER_ROBOT);
        if (cap != null && cap.state() == CapabilityState.DEGRADED) {
            ctx.capabilities().set(SwingModule.CAP_POINTER_ROBOT, CapabilityState.OK, null, "robot");
        }
    }

    // --------------------------------------------------------------------------------------------------- capture

    /** What a glass capture reads on the EDT before and after the grab. */
    private static final class Read {
        Rectangle screen;
        List<Rectangle> windowRects = new ArrayList<Rectangle>();
        Capture.Facts facts;
        long seq;
        Component window;
    }

    /**
     * A person's {@code swing.capture {mode: ROBOT}} (section 3.4, D341): the glass of the target's screen rectangle
     * through the Robot, every pixel outside the screen rectangles of the JVM's showing windows blanked grey, the mask
     * rectangles of a read before and after the grab blanked (taken once more when a window opened or closed in
     * between; {@code unstable} after a second such attempt), {@code glass: true}. Never offered to a model or a
     * client (the controller refuses it for every origin but a person's).
     *
     * @return the shot: the raster, the fields (without the PNG) and the geometry
     */
    Capture.Shot capture(final Invocation inv, final Component target, final Capture.Request req) throws Exception {
        boolean unstable = false;
        BufferedImage image = null;
        Read before = null;
        Read afterRead = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            before = read(inv, target, req);
            final Read b = before;
            // section 2.4's heap bound and out-of-memory catch, as for a PAINT capture (fix wave, FA, L2-S3), with what
            // the grab allocates at the screen's UI scale (Phase 7 security fix, S126), before the port is looked up
            double[] s;
            try {
                s = screens.scaleAt(b.screen.getCenterX(), b.screen.getCenterY());
            } catch (RuntimeException e) {
                s = new double[] {1.0, 1.0};
            }
            Capture.heapBound(grabBytes(b.screen, s[0], s[1]), "scope", "capture a smaller scope");
            final RobotPort port = portFor(b.window instanceof Window ? (Window) b.window : null, inv);
            try {
                image = ctx.threads().call(ThreadPolicy.WORKER, new Callable<BufferedImage>() {
                    @Override
                    public BufferedImage call() {
                        return port.createScreenCapture(b.screen);
                    }
                }, Math.max(1, inv.remainingMs() - Pointer.SAFETY_MS));
            } catch (OutOfMemoryError e) {
                throw outOfMemory(b.screen);
            }
            afterRead = read(inv, target, req);
            List<Event> between = ctx.events().since(before.seq, TopicFilter.parse("window.opened,window.closed"),
                    10);
            if (between.isEmpty()) {
                unstable = false;
                break;
            }
            unstable = true;
        }
        if (image == null) {
            throw new RpcException(ErrorCodes.INTERNAL_ERROR, "the screen capture returned no image",
                    Errors.data("reason", "no image"));
        }
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            Capture.heapBound(4L * image.getWidth() * image.getHeight(), "scope", "capture a smaller scope");
            try {
                rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                try {
                    g.drawImage(image, 0, 0, null);
                } finally {
                    g.dispose();
                }
            } catch (OutOfMemoryError e) {
                throw outOfMemory(before.screen);
            }
        }
        blankOutside(rgb, before.screen, before.windowRects);
        if (req.redact) {
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Redaction.FILL);
                for (Rectangle r : before.facts.blanked) {
                    g.fillRect(r.x, r.y, r.width, r.height);
                }
                for (Rectangle r : afterRead.facts.blanked) {
                    g.fillRect(r.x, r.y, r.width, r.height);
                }
            } finally {
                g.dispose();
            }
        }
        Capture.Scope scope = req.scope != null ? req.scope
                : (target == null || target instanceof Window ? Capture.Scope.WINDOW : Capture.Scope.COMPONENT);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("seq", Long.valueOf(afterRead.seq));
        m.put("mode", "ROBOT");
        m.put("scope", scope.name());
        m.put("space", "SCREEN");
        m.put("origin", Capture.point(before.screen.x, before.screen.y));
        m.put("scale", Double.valueOf(1.0));
        m.put("windows", before.facts.windows);
        m.put("surfaces", before.facts.surfaces);
        m.put("carets", afterRead.facts.carets);
        m.put("focus", afterRead.facts.focus);
        List<Object> redacted = new ArrayList<Object>(before.facts.redacted);
        m.put("redacted", redacted);
        if (before.facts.redactedTruncated) {
            m.put("redactedTruncated", Boolean.TRUE);
        }
        if (scope == Capture.Scope.SCREEN) {
            m.put("stackingAssumed", Boolean.FALSE);
        }
        m.put("glass", Boolean.TRUE);
        m.put("glassNote", GLASS_NOTE);
        if (unstable) {
            m.put("unstable", Boolean.TRUE);
        }
        Capture.Geometry geometry = new Capture.Geometry("SCREEN", null, before.screen.x, before.screen.y, 1.0,
                rgb.getWidth(), rgb.getHeight());
        return new Capture.Shot(rgb, m, geometry, new ArrayList<Capture.Skipped>());
    }

    /**
     * What a screen grab of the rectangle allocates (Phase 7 security fix, S126): at a UI scale of 1 the user-space
     * raster ({@code 4 w h} bytes); at another scale {@code Robot} reads the device-space pixels
     * ({@code ceil(w sx) x ceil(h sy)}) and then allocates the user-space image beside them, about
     * {@code (s² + 1) 4 w h} bytes.
     */
    static long grabBytes(Rectangle screen, double sx, double sy) {
        long user = 4L * screen.width * screen.height;
        if (sx == 1.0 && sy == 1.0) {
            return user;
        }
        long device = 4L * (long) Math.ceil(screen.width * Math.max(sx, 0.0))
                * (long) Math.ceil(screen.height * Math.max(sy, 0.0));
        return user + device;
    }

    private static RpcException outOfMemory(Rectangle screen) {
        return new RpcException(ErrorCodes.INTERNAL_ERROR, "out of memory while capturing the screen rectangle of "
                + screen.width + " x " + screen.height + " pixels; capture a smaller scope", Errors.data("reason",
                "out of memory"));
    }

    private Read read(Invocation inv, final Component target, final Capture.Request req) throws Exception {
        return Edt.read(ctx, inv.remainingMs(), new Callable<Read>() {
            @Override
            public Read call() {
                ComponentView view = new ComponentView(ctx);
                SwingSpis spis = new SwingSpis(ctx);
                Read r = new Read();
                r.seq = ctx.events().lastSeq();
                List<Windows.Layer> layers = windows.stacking();
                for (Windows.Layer l : layers) {
                    r.windowRects.add(new Rectangle(l.screen));
                }
                Capture.Scope scope = req.scope != null ? req.scope : (target instanceof Window
                        ? Capture.Scope.WINDOW : Capture.Scope.COMPONENT);
                List<Windows.Layer> used = new ArrayList<Windows.Layer>();
                if (scope == Capture.Scope.SCREEN) {
                    Rectangle union = null;
                    for (Windows.Layer l : layers) {
                        union = union == null ? new Rectangle(l.screen) : union.union(l.screen);
                    }
                    if (union == null) {
                        throw Errors.notFound("nothing to capture: no window is showing", Errors.data());
                    }
                    r.screen = union;
                    used.addAll(layers);
                } else {
                    Window w = target instanceof Window ? (Window) target : Windows.windowOf(target);
                    r.window = w;
                    if (w == null) {
                        throw Errors.invalid(view.describe(target) + " is in no window", Errors.data("ref",
                                view.ref(target)));
                    }
                    Point o = w.getLocationOnScreen();
                    if (scope == Capture.Scope.WINDOW) {
                        r.screen = new Rectangle(o, w.getSize());
                    } else {
                        Point to = target instanceof Window ? new Point(0, 0)
                                : SwingUtilities.convertPoint(target, 0, 0, w);
                        Rectangle local = new Rectangle(0, 0, target.getWidth(), target.getHeight());
                        if (scope == Capture.Scope.REGION) {
                            Rectangle region = req.region;
                            if (req.regionId != null) {
                                region = spis.rectOf(target, req.regionId);
                                if (region == null) {
                                    throw Errors.notFound("no region '" + req.regionId + "' on "
                                            + view.describe(target), Errors.data("region", req.regionId));
                                }
                            }
                            local = region.intersection(local);
                            if (local.isEmpty()) {
                                throw Errors.invalid("the region lies outside " + view.describe(target),
                                        Errors.data("param", "region", "reason", "outside"));
                            }
                        }
                        r.screen = new Rectangle(o.x + to.x + local.x, o.y + to.y + local.y, local.width,
                                local.height);
                    }
                    // every window of the JVM the glass holds a part of, the target's and any other that meets the
                    // rectangle (a dialog over the frame): its masks are blanked too (fix wave, FA, L2-S1)
                    for (Windows.Layer l : layers) {
                        if (l.window == w || l.screen.intersects(r.screen)) {
                            used.add(l);
                        }
                    }
                }
                if (r.screen.width > Capture.MAX_SIDE || r.screen.height > Capture.MAX_SIDE) {
                    throw Errors.invalid("a screen capture is at most " + Capture.MAX_SIDE + " pixels a side",
                            Errors.data("param", "scope"));
                }
                r.facts = Capture.facts(view, spis, used, r.screen, req.redact, r.window);
                return r;
            }
        });
    }

    /**
     * Blanks grey every pixel of the image outside the screen rectangles of the JVM's showing windows, by filling the
     * complement's rectangles band by band (fix wave of the Phase 7 review, FA, L2-S3: at most 2n + 1 bands of at most
     * n + 1 gaps for n windows, instead of a pixel walk over up to 4096 x 4096 pixels).
     */
    static void blankOutside(BufferedImage image, Rectangle screen, List<Rectangle> windowRects) {
        Rectangle bounds = new Rectangle(0, 0, image.getWidth(), image.getHeight());
        List<Rectangle> inside = new ArrayList<Rectangle>();
        java.util.TreeSet<Integer> edges = new java.util.TreeSet<Integer>();
        edges.add(Integer.valueOf(0));
        edges.add(Integer.valueOf(bounds.height));
        for (Rectangle r : windowRects) {
            Rectangle local = new Rectangle(r.x - screen.x, r.y - screen.y, r.width, r.height).intersection(bounds);
            if (!local.isEmpty()) {
                inside.add(local);
                edges.add(Integer.valueOf(local.y));
                edges.add(Integer.valueOf(local.y + local.height));
            }
        }
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Redaction.FILL);
            Integer top = null;
            for (Integer edge : edges) {
                if (top != null && edge.intValue() > top.intValue()) {
                    int y0 = top.intValue();
                    int h = edge.intValue() - y0;
                    List<int[]> spans = new ArrayList<int[]>();
                    for (Rectangle r : inside) {
                        if (r.y <= y0 && r.y + r.height >= y0 + h) {
                            spans.add(new int[] {r.x, r.x + r.width});
                        }
                    }
                    java.util.Collections.sort(spans, new java.util.Comparator<int[]>() {
                        @Override
                        public int compare(int[] a, int[] b) {
                            return a[0] < b[0] ? -1 : a[0] == b[0] ? 0 : 1;
                        }
                    });
                    int x = 0;
                    for (int[] span : spans) {
                        if (span[0] > x) {
                            g.fillRect(x, y0, span[0] - x, h);
                        }
                        x = Math.max(x, span[1]);
                    }
                    if (x < bounds.width) {
                        g.fillRect(x, y0, bounds.width - x, h);
                    }
                }
                top = edge;
            }
        } finally {
            g.dispose();
        }
    }
}
