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
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.awt.AWTEvent;
import java.awt.Component;
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
 *       and refuses the call. It runs before the pre-check, never between the pre-check and the press.</li>
 *   <li><b>The pre-check</b>: the hit-test; {@code requireFocus} (default true) refuses a JVM without a focused window
 *       ({@code INPUT_REJECTED "not focused"}); any covering window of the JVM refuses the point unless
 *       {@code force}.</li>
 *   <li><b>The press</b>: {@code PRESS} and {@code RELEASE} are refused; a press and its release run in one worker task
 *       with no wait between them, a {@code finally} releases every button the call pressed before anything else, and
 *       the pointer is restored only afterwards (D343).</li>
 *   <li><b>The observation</b>: an {@code AWTEventListener} for the call's duration; nothing received is
 *       {@code fired: false, reason: "NOT_DELIVERED"}, a receiver outside the target {@code "received elsewhere"}.</li>
 * </ul>
 *
 * <p>Thread-safe.
 */
final class RobotPath {

    /** The capabilities' reason without the agent option. */
    static final String NOT_ENABLED = "robot: not enabled (agent option robot=true)";
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

    private RobotPort portFor(final Window w) throws Exception {
        GraphicsDevice d = null;
        try {
            if (w != null && w.getGraphicsConfiguration() != null) {
                d = w.getGraphicsConfiguration().getDevice();
            }
        } catch (RuntimeException ignored) {
            // the default screen
        }
        return port(d != null ? d : defaultDevice());
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
        if (!deliveryOk) {
            Plan where = Edt.read(ctx, inv.remainingMs(), new Callable<Plan>() {
                @Override
                public Plan call() {
                    ComponentView view = new ComponentView(ctx);
                    Pointer.Target t = pointer.address(view, new SwingSpis(ctx), r);
                    Plan p = new Plan();
                    p.window = t.window;
                    Point o = t.window.getLocationOnScreen();
                    p.screen = new Point(o.x + t.p.x, o.y + t.p.y);
                    return p;
                }
            });
            probe(inv, where.window, where.screen);
        }
        final Plan plan = Edt.read(ctx, inv.remainingMs(), new Callable<Plan>() {
            @Override
            public Plan call() {
                ComponentView view = new ComponentView(ctx);
                SwingSpis spis = new SwingSpis(ctx);
                Pointer.Target t = pointer.address(view, spis, r);
                HitTest.Hit hit = HitTest.at(view, windows, spis, t.window, t.p, null, true, ctx.events().lastSeq());
                if (r.requireFocus
                        && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == null) {
                    throw new RpcException(ErrorCodes.INPUT_REJECTED, "the target JVM holds no focused window, so "
                            + "its windows may not be in front; bring it to the front (requireFocus)",
                            Errors.data("reason", "not focused"));
                }
                pointer.precheck(view, r, hit, true);
                Plan p = new Plan();
                p.window = t.window;
                p.hit = hit;
                p.wasFocused = t.window.isFocused();
                Point o = t.window.getLocationOnScreen();
                p.screen = new Point(o.x + t.p.x, o.y + t.p.y);
                if (t.to != null) {
                    p.screenTo = new Point(o.x + t.to.x, o.y + t.to.y);
                }
                return p;
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
            final RobotPort port = portFor(plan.window);
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
                        port.mousePress(mask);
                        down = true;
                        port.mouseRelease(mask);
                        down = false;
                    }
                    break;
                case DOUBLE_CLICK:
                    for (int i = 0; i < 2; i++) {
                        port.mousePress(mask);
                        down = true;
                        port.mouseRelease(mask);
                        down = false;
                    }
                    break;
                case DRAG: {
                    port.mousePress(mask);
                    down = true;
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
     * of the window for at most {@value #PROBE_MS} ms.
     *
     * @throws RpcException CAPABILITY_UNAVAILABLE (and {@code swing.pointer.robot} DEGRADED) when nothing arrives
     */
    private void probe(Invocation inv, final Window window, final Point screen) throws Exception {
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
        final RobotPort port = portFor(window);
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
        if (!arrived) {
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
            final RobotPort port = portFor(b.window instanceof Window ? (Window) b.window : null);
            image = ctx.threads().call(ThreadPolicy.WORKER, new Callable<BufferedImage>() {
                @Override
                public BufferedImage call() {
                    return port.createScreenCapture(b.screen);
                }
            }, Math.max(1, inv.remainingMs() - Pointer.SAFETY_MS));
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
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
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
                    for (Windows.Layer l : layers) {
                        if (l.window == w) {
                            used.add(l);
                        }
                    }
                }
                if (r.screen.width > Capture.MAX_SIDE || r.screen.height > Capture.MAX_SIDE) {
                    throw Errors.invalid("a screen capture is at most " + Capture.MAX_SIDE + " pixels a side",
                            Errors.data("param", "scope"));
                }
                r.facts = Capture.facts(view, spis, used, r.screen, req.redact);
                return r;
            }
        });
    }

    /** Blanks grey every pixel of the image outside the screen rectangles of the JVM's showing windows. */
    static void blankOutside(BufferedImage image, Rectangle screen, List<Rectangle> windowRects) {
        int grey = Redaction.FILL.getRGB();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int sx = screen.x + x;
                int sy = screen.y + y;
                boolean inside = false;
                for (Rectangle r : windowRects) {
                    if (r.contains(sx, sy)) {
                        inside = true;
                        break;
                    }
                }
                if (!inside) {
                    image.setRGB(x, y, grey);
                }
            }
        }
    }
}
