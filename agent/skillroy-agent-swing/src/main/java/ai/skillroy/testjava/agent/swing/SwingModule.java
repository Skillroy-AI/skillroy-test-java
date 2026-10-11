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
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.CapabilityRegistry;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.CapabilityState;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/**
 * The generic Swing module (id {@code "swing"}, contract section 6): window and tree snapshots with stable refs,
 * locators, click rungs, typing and keys without focus, dialog reading and dismissal, PAINT capture, EDT health and
 * settle, and the AWT window tap. Discovered through {@code META-INF/services}; needs nothing from other modules
 * but picks up the {@code NameEnricher}, {@code LocatorResolver}, {@code ActionFirer}, {@code DialogTextExtractor} and
 * {@code DialogLocator} implementations domain modules register, at call time.
 *
 * <p><b>AWT is never initialised by the module.</b> In a {@code -javaagent} load, install (and, with no readiness
 * source registered, {@code onTargetReady} too) runs before the application's {@code main}; initialising the toolkit
 * or freezing the headless decision then could change how the application starts, and would turn a JVM that never
 * uses AWT into one that does. So {@link #install} and {@link #onTargetReady} load no AWT class that initialises the
 * toolkit and call nothing in AWT. They only look for an event-dispatch thread (a live thread whose name starts with
 * {@code AWT-EventQueue}); while there is none, a daemon thread ({@code skillroy-swing-tap}) looks again four times a second
 * and ends once one exists or the module is uninstalled. Capability states:
 * <ul>
 *   <li>before an event-dispatch thread exists: {@code swing.tree}, {@code swing.click.action},
 *       {@code swing.click.component}, {@code swing.click.dispatch} and {@code swing.capture.paint} are
 *       UNAVAILABLE("AWT not started by the application yet"), or UNAVAILABLE("headless") while the
 *       {@code java.awt.headless} system property is {@code true} (the only headless signal that can be read without
 *       AWT). The dispatcher therefore rejects every {@code swing.*} primitive except {@code swing.edtState}, which
 *       names no capability and reports without starting the toolkit.</li>
 *   <li>once it exists (the toolkit is then initialised and the headless decision taken): the module asks
 *       {@code GraphicsEnvironment.isHeadless()}; headless keeps them UNAVAILABLE("headless") and adds
 *       no tap; otherwise they become OK and the AWT window tap is added, which reports the windows already showing.
 *       </li>
 * </ul>
 * Phase 7 (phase7-contracts.md section 3.9): {@code swing.hittest}, {@code swing.pointer.routed} and
 * {@code swing.capture.screen} follow AWT's state as the display capabilities above do. {@code swing.pointer.robot} and
 * {@code swing.capture.robot} are UNAVAILABLE("robot: not enabled (agent option robot=true)") without the agent option
 * {@code robot} (read from {@code ctx.options()}, never through the live fallback of {@code ctx.option}) and are never
 * probed then; with it they wait as the display capabilities do and are declared, once AWT is confirmed, by one probe on
 * an agent worker that constructs the Robot and moves nothing ({@link RobotPath#declare}).
 * {@code swing.click.robot} is always UNAVAILABLE("opt-in rung, not implemented"). The check runs at install, at
 * {@code onTargetReady} and on every tick of the watcher thread, so a {@code swing.*} call made within a quarter of a
 * second of the application starting AWT can still be rejected with the "not started" reason.
 *
 * <p>The core calls the lifecycle methods one at a time; all mutable state is guarded by this object's lock.
 */
public final class SwingModule implements AgentModule {

    /** Module id. */
    public static final String ID = "swing";
    /** Reading the component tree: every primitive except capture and edtState depends on it. */
    public static final String CAP_TREE = "swing.tree";
    /** Prefix of the per-rung click capabilities ({@code swing.click.action}, ...). */
    public static final String CAP_CLICK_PREFIX = "swing.click.";
    /** The ACTION rung (also AUTO). */
    public static final String CAP_CLICK_ACTION = "swing.click.action";
    /** The COMPONENT rung (focus, then click). */
    public static final String CAP_CLICK_COMPONENT = "swing.click.component";
    /** The DISPATCH rung (dispatched mouse events). */
    public static final String CAP_CLICK_DISPATCH = "swing.click.dispatch";
    /** Always UNAVAILABLE in this version. */
    public static final String CAP_CLICK_ROBOT = "swing.click.robot";
    /** PAINT capture ({@code printAll} into an image). */
    public static final String CAP_CAPTURE_PAINT = "swing.capture.paint";
    /** {@code swing.hitTest} (Phase 7). */
    public static final String CAP_HITTEST = "swing.hittest";
    /** {@code swing.pointer} with {@code via: ROUTED} (Phase 7). */
    public static final String CAP_POINTER_ROUTED = "swing.pointer.routed";
    /** {@code swing.capture {scope: SCREEN}} in PAINT mode (Phase 7). */
    public static final String CAP_CAPTURE_SCREEN = "swing.capture.screen";
    /** {@code swing.pointer} with {@code via: ROBOT} (Phase 7, the agent option {@code robot}). */
    public static final String CAP_POINTER_ROBOT = "swing.pointer.robot";
    /** A person's {@code swing.capture {mode: ROBOT}} (Phase 7, the agent option {@code robot}). */
    public static final String CAP_CAPTURE_ROBOT = "swing.capture.robot";
    /** The agent option that enables the Robot path (A65). */
    public static final String ROBOT_OPTION = "robot";

    /** Reason of the display capabilities in a headless JVM. */
    static final String HEADLESS_REASON = "headless";
    /** Reason of the display capabilities until the application starts AWT. */
    static final String NOT_STARTED_REASON = "AWT not started by the application yet";
    static final String ROBOT_REASON = "opt-in rung, not implemented";

    private static final String[] DISPLAY_CAPABILITIES = {
        CAP_TREE, CAP_CLICK_ACTION, CAP_CLICK_COMPONENT, CAP_CLICK_DISPATCH, CAP_CAPTURE_PAINT, CAP_HITTEST,
        CAP_POINTER_ROUTED, CAP_CAPTURE_SCREEN
    };
    private static final String[] ROBOT_CAPABILITIES = {CAP_POINTER_ROBOT, CAP_CAPTURE_ROBOT};

    /** How often the watcher thread looks for an event-dispatch thread (4 Hz). */
    static final long AWT_POLL_MS = 250;
    /** Name of the watcher thread. */
    static final String TAP_THREAD = "skillroy-swing-tap";

    private AgentContext ctx;
    private Windows windows;
    private WindowTap tap;
    private AwtState awt;
    private boolean tapAdded;
    private boolean awtConfirmed;
    private boolean uninstalled;
    private String waitingReason;
    private int awtChecks;
    /** Counts installs and uninstalls, so that a watcher thread of an earlier install never outlives it. */
    private int generation;
    private final List<String> registered = new ArrayList<String>();
    private final BooleanSupplier edtRunning;
    private final RobotFactory robotFactory;
    private boolean robotEnabled;
    private SwingPrimitives primitives;

    /** For {@code ServiceLoader}. */
    public SwingModule() {
        this(new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return AwtThreads.eventQueueAlive();
            }
        });
    }

    /** For tests: {@code edtRunning} replaces the look for an event-dispatch thread. */
    SwingModule(BooleanSupplier edtRunning) {
        this(edtRunning, RobotFactory.DEFAULT);
    }

    /** For tests: also the Robot seam. */
    SwingModule(BooleanSupplier edtRunning, RobotFactory robotFactory) {
        this.edtRunning = edtRunning;
        this.robotFactory = robotFactory;
    }

    /** {@value #ID}. */
    @Override
    public String id() {
        return ID;
    }

    /** The module's Maven version. */
    @Override
    public String version() {
        return Version.VALUE;
    }

    /**
     * Declares the capabilities, registers the primitives and arranges for the AWT tap (see the class comment).
     * Called once on the agent's own thread; never initialises AWT and never waits.
     */
    @Override
    public synchronized void install(AgentContext context) throws Exception {
        this.ctx = context;
        this.windows = new Windows();
        this.awt = new AwtState();
        this.tap = new WindowTap(context, windows);
        this.tapAdded = false;
        this.awtConfirmed = false;
        this.uninstalled = false;
        this.waitingReason = null;
        this.awtChecks = 0;
        final int myGeneration = ++generation;
        context.capabilities().set(CAP_CLICK_ROBOT, CapabilityState.UNAVAILABLE, ROBOT_REASON, null);
        Map<String, String> options = context.options();
        this.robotEnabled = options != null && "true".equalsIgnoreCase(options.get(ROBOT_OPTION));
        if (!robotEnabled) {
            for (String id : ROBOT_CAPABILITIES) {
                context.capabilities().set(id, CapabilityState.UNAVAILABLE, RobotPath.NOT_ENABLED, null);
            }
        }
        this.primitives = new SwingPrimitives(context, windows, tap, awt, robotFactory);
        for (Primitive p : primitives.all()) {
            context.primitives().register(p);
            registered.add(p.spec().name());
        }
        if (!checkAwt()) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    awaitAwt(myGeneration);
                }
            }, TAP_THREAD);
            t.setDaemon(true);
            t.start();
        }
        context.log().info("swing: installed " + registered.size() + " primitives");
    }

    /**
     * Checks once more for an event-dispatch thread and, if one exists, completes the set-up at once (otherwise the
     * watcher thread keeps looking). Called on an agent worker once the application is initialised; never initialises
     * AWT, never waits, never throws.
     */
    @Override
    public synchronized void onTargetReady(AgentContext context) {
        checkAwt();
    }

    /** Stops the watcher thread and removes the AWT tap and the primitives. Never throws, never initialises AWT. */
    @Override
    public synchronized void uninstall() {
        uninstalled = true;
        generation++;
        notifyAll(); // ends the watcher thread's wait
        if (tap != null) {
            tap.deactivate();
        }
        if (tapAdded) {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(tap);
            } catch (Throwable t) {
                log("swing: could not remove the AWT tap: " + t, t);
            }
            tapAdded = false;
        }
        if (ctx != null) {
            for (String name : registered) {
                try {
                    ctx.primitives().unregister(name);
                } catch (Throwable t) {
                    log("swing: could not unregister " + name + ": " + t, t);
                }
            }
        }
        registered.clear();
    }

    /**
     * Body of the watcher thread: looks again every {@link #AWT_POLL_MS} until AWT runs or the install that started it
     * ends (uninstall, or a later install).
     */
    private synchronized void awaitAwt(int myGeneration) {
        try {
            while (myGeneration == generation && !checkAwt()) {
                wait(AWT_POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One look for an event-dispatch thread. Without one: keeps the display capabilities UNAVAILABLE with the reason
     * the {@code java.awt.headless} property gives, touching nothing in AWT. With one: confirms the headless decision
     * with AWT, sets the capabilities and, with a display, activates the tap, adds it to the toolkit and lets it
     * report the windows already showing.
     *
     * @return true when there is nothing left to wait for (done, or uninstalled)
     */
    private synchronized boolean checkAwt() {
        if (awtConfirmed || uninstalled) {
            return true;
        }
        awtChecks++;
        if (!edtRunning.getAsBoolean()) {
            String reason = headlessProperty() ? HEADLESS_REASON : NOT_STARTED_REASON;
            if (!reason.equals(waitingReason)) {
                waitingReason = reason;
                applyCapabilities(CapabilityState.UNAVAILABLE, reason);
            }
            return false;
        }
        awtConfirmed = true;
        try {
            boolean headless = GraphicsEnvironment.isHeadless();
            awt.markStarted(headless);
            applyCapabilities(headless ? CapabilityState.UNAVAILABLE : CapabilityState.OK,
                    headless ? HEADLESS_REASON : null);
            if (robotEnabled) {
                final RobotPath robot = primitives.robot();
                if (headless) {
                    robot.declare(true);
                } else {
                    // until the worker's probe declares them (and for good when the post is refused) both ids say so,
                    // and a Robot call is refused rather than constructing the Robot on its own thread (fix wave of
                    // the Phase 7 review, FA, L2-F8)
                    for (String id : ROBOT_CAPABILITIES) {
                        ctx.capabilities().set(id, CapabilityState.UNAVAILABLE, RobotPath.PROBE_NOT_RUN, null);
                    }
                    ctx.threads().post(ThreadPolicy.WORKER, new Runnable() {
                        @Override
                        public void run() {
                            robot.declare(false);
                        }
                    });
                }
            }
            if (!headless) {
                tap.activate();
                Toolkit.getDefaultToolkit().addAWTEventListener(tap, WindowTap.EVENT_MASK);
                tapAdded = true;
                final WindowTap t = tap;
                ctx.threads().post(ThreadPolicy.EDT_ASYNC, new Runnable() {
                    @Override
                    public void run() {
                        t.sync();
                    }
                });
            }
        } catch (Throwable t) {
            log("swing: could not install the AWT tap: " + t, t);
        }
        return true;
    }

    /** True while the AWT window tap is installed (for tests and diagnostics). */
    synchronized boolean tapInstalled() {
        return tapAdded;
    }

    /** How many times the module has looked for an event-dispatch thread since install (for tests). */
    synchronized int awtChecks() {
        return awtChecks;
    }

    private void applyCapabilities(CapabilityState state, String reason) {
        CapabilityRegistry caps = ctx.capabilities();
        for (String id : DISPLAY_CAPABILITIES) {
            caps.set(id, state, reason, null);
        }
        if (robotEnabled && state == CapabilityState.UNAVAILABLE) {
            for (String id : ROBOT_CAPABILITIES) {
                caps.set(id, state, reason, null);
            }
        }
    }

    /** The module's primitives (for tests). */
    synchronized SwingPrimitives primitivesForTest() {
        return primitives;
    }

    private void log(String msg, Throwable t) {
        try {
            if (ctx != null) {
                ctx.log().warn(msg, t);
            }
        } catch (Throwable ignored) {
            // uninstall must not throw
        }
    }

    /**
     * True when the {@code java.awt.headless} system property is {@code true}, the only headless signal that can be
     * read without asking AWT (which would fix the decision). Case-insensitive like the JDK's own reading.
     */
    static boolean headlessProperty() {
        try {
            return Boolean.parseBoolean(System.getProperty("java.awt.headless"));
        } catch (SecurityException e) {
            return false;
        }
    }

    /** The version from the filtered {@code swing-module.properties}. */
    private static final class Version {
        static final String VALUE = load();

        private static String load() {
            InputStream in = SwingModule.class.getResourceAsStream("swing-module.properties");
            if (in == null) {
                return "unknown";
            }
            try {
                Properties p = new Properties();
                p.load(in);
                return p.getProperty("version", "unknown");
            } catch (IOException e) {
                return "unknown";
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // read-only resource
                }
            }
        }
    }
}
