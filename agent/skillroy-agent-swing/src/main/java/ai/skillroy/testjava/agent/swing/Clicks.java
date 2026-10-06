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
import ai.skillroy.testjava.agent.api.spi.ActionFirer;

import java.awt.Component;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;

/**
 * The click rungs of {@code swing.click}. ACTION fires the widget's semantic action ({@link ActionFirer}, else
 * {@code doClick()}, else a dispatched mouse click); COMPONENT focuses the component first and clicks only once it owns
 * the focus, optionally activating its window first ({@code toFront()} + {@code requestFocus()}, then waiting for the
 * window to be the focused window: {@code requestFocusInWindow()} fails in any window but the focused one); DISPATCH
 * delivers a mouse press/release/click with {@code Component.dispatchEvent}. Every click is posted to the EDT and the
 * call returns once it is queued, because the click may open a modal dialog. Unless {@code force}, a component whose
 * window a modal dialog blocks, or that lies outside a blocking dialog drawn inside its window, is not clicked
 * ({@code MODAL_OPEN}, see {@link Windows#requireNotBlocked}).
 *
 * <p>Fidelity (amendment A28): a click that fired on the rung asked for reports {@code fidelity: "FULL"} and the
 * {@code requestedRung}. COMPONENT with {@code fallback: "ACTION"} fires through the ACTION rung when the focus does
 * not arrive and reports {@code {rung: "ACTION", requestedRung: "COMPONENT", fidelity: "DEGRADED", reason:
 * "FOCUS_FAILED: ...", fired: true}}; without the fallback such a click reports
 * {@code {rung: "FOCUS_FAILED", fired: false, reason}} and nothing is clicked.
 *
 * <p>Thread-safe. {@link #click} runs on an agent thread and hops to the EDT itself; it never holds the EDT while it
 * waits for focus, and every listener it adds is removed by a task it posts afterwards.
 */
final class Clicks {

    static final String AUTO = "AUTO";
    static final String ACTION = "ACTION";
    static final String COMPONENT = "COMPONENT";
    static final String DISPATCH = "DISPATCH";
    static final String ROBOT = "ROBOT";
    static final String FOCUS_FAILED = "FOCUS_FAILED";
    /** The click fired on the rung that was asked for. */
    static final String FULL = "FULL";
    /** The click fired on a lower rung than the one asked for (the COMPONENT to ACTION fall-back). */
    static final String DEGRADED = "DEGRADED";
    static final Set<String> RUNGS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            AUTO, ACTION, COMPONENT, DISPATCH, ROBOT)));

    /** Time kept back from the call budget so that FOCUS_FAILED is reported before the dispatcher's TIMEOUT. */
    private static final long SAFETY_MS = 100;
    /**
     * Further time kept back when a COMPONENT click may fall back to ACTION: one bounded EDT read and one post, so that
     * the fall-back still fires before the dispatcher's TIMEOUT.
     */
    private static final long FALLBACK_RESERVE_MS = 400;
    /** Budget for posting cleanup work after the call budget is spent. */
    private static final long CLEANUP_POST_MS = 1000;

    private final AgentContext ctx;
    private final Windows windows;

    Clicks(AgentContext ctx, Windows windows) {
        this.ctx = ctx;
        this.windows = windows;
    }

    /**
     * Clicks the target with the given rung (AUTO already mapped to ACTION; ROBOT rejected by the caller).
     *
     * @param requested the rung as the caller asked for it (AUTO stays AUTO), reported as {@code requestedRung}
     * @param activateWindow COMPONENT only: activate the component's window before asking for the focus
     * @param force click even if a modal dialog blocks the component's window
     * @param fallback COMPONENT only (the caller rejects it with other rungs): fire through ACTION when the focus does
     *     not arrive
     * @return {@code {rung, requestedRung, fidelity, reason?, fired: true}} when the click fired, or
     *     {@code {rung: "FOCUS_FAILED", fired: false, reason}}
     */
    Map<String, Object> click(Invocation inv, TargetSpec target, String requested, String rung, long focusTimeoutMs,
                              boolean activateWindow, boolean force, boolean fallback) throws Exception {
        if (COMPONENT.equals(rung)) {
            return component(inv, target, requested, focusTimeoutMs, activateWindow, force, fallback);
        }
        final boolean dispatch = DISPATCH.equals(rung);
        final boolean f = force;
        final TargetSpec t = target;
        final Edt.Action plan = Edt.read(ctx, inv.remainingMs(), new Callable<Edt.Action>() {
            @Override
            public Edt.Action call() {
                ComponentView view = new ComponentView(ctx);
                final Component c = t.resolve(view);
                requireClickable(view, c);
                if (!f) {
                    windows.requireNotBlocked(view, c);
                }
                return dispatch ? mouseClickAction(c) : actionPlan(ctx, c);
            }
        });
        Edt.post(ctx, inv.remainingMs(), "swing.click " + rung, plan);
        return fired(rung, requested);
    }

    private Map<String, Object> component(Invocation inv, final TargetSpec target, String requested,
                                          long focusTimeoutMs, final boolean activateWindow, final boolean force,
                                          boolean fallback) throws Exception {
        long reserve = fallback ? FALLBACK_RESERVE_MS : 0;
        final FocusWait wait = new FocusWait();
        final WindowWait windowWait = new WindowWait();
        final AtomicReference<Component> listenedTo = new AtomicReference<Component>();
        final AtomicReference<Window> activated = new AtomicReference<Window>();
        final String[] refusal = new String[1];
        try {
            Boolean accepted;
            try {
                accepted = Edt.read(ctx, inv.remainingMs(), new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        if (wait.cancelled.get()) {
                            return Boolean.FALSE; // the caller gave up before this ran
                        }
                        ComponentView view = new ComponentView(ctx);
                        Component c = target.resolve(view);
                        requireClickable(view, c);
                        if (!force) {
                            windows.requireNotBlocked(view, c);
                        }
                        listenedTo.set(c);
                        if (activateWindow) {
                            Window w = Windows.windowOf(c);
                            if (w != null && !w.isFocused()) {
                                if (!w.isFocusableWindow()) {
                                    refusal[0] = view.describe(w) + " cannot be the focused window (it is not a "
                                            + "focusable window)";
                                    return Boolean.FALSE;
                                }
                                w.addWindowFocusListener(windowWait);
                                activated.set(w);
                                w.toFront();
                                w.requestFocus();
                                if (w.isFocused()) {
                                    windowWait.arrived();
                                }
                                return null; // the component's focus is requested once the window is focused
                            }
                        }
                        return requestFocus(c, wait);
                    }
                });
            } catch (Exception e) {
                wait.cancelled.set(true);
                throw e;
            }
            if (refusal[0] != null) {
                return focusFailed(inv, listenedTo.get(), requested, refusal[0], force, fallback);
            }
            long focusStart = System.nanoTime();
            if (accepted == null) {
                // the window was asked to become the focused window: wait for it, then ask for the component's focus;
                // focusTimeoutMs bounds both waits together
                long budget = budget(focusTimeoutMs, 0, reserve, inv);
                if (!windowWait.hasArrived() && !windowWait.await(budget)) {
                    return focusFailed(inv, listenedTo.get(), requested, "the window did not become the focused window"
                            + " within " + budget + " ms of toFront() and requestFocus()", force, fallback);
                }
                try {
                    accepted = Edt.read(ctx, inv.remainingMs(), new Callable<Boolean>() {
                        @Override
                        public Boolean call() {
                            if (wait.cancelled.get()) {
                                return Boolean.FALSE;
                            }
                            return requestFocus(listenedTo.get(), wait);
                        }
                    });
                } catch (Exception e) {
                    wait.cancelled.set(true);
                    throw e;
                }
            }
            boolean focused = wait.hasArrived();
            if (!focused) {
                long budget = budget(focusTimeoutMs, elapsedMs(focusStart), reserve, inv);
                focused = wait.await(budget);
                if (!focused) {
                    return focusFailed(inv, listenedTo.get(), requested, "focus did not arrive within " + budget
                            + " ms" + (accepted.booleanValue() ? "" : " (requestFocusInWindow() returned false)"),
                            force, fallback);
                }
            }
            final Component c = listenedTo.get();
            Edt.post(ctx, inv.remainingMs(), "swing.click COMPONENT", new Edt.Action() {
                @Override
                public void run() {
                    if (c instanceof AbstractButton) {
                        ((AbstractButton) c).doClick();
                    } else {
                        mouseClick(c);
                    }
                }
            });
            return fired(COMPONENT, requested);
        } finally {
            removeLater(wait, windowWait, activated);
        }
    }

    /**
     * The end of a COMPONENT click whose focus did not arrive: {@code {rung: "FOCUS_FAILED", fired: false, reason}}, or
     * with the fall-back the ACTION rung fired instead and reported as DEGRADED. The component is checked again on the
     * EDT first (still showing and enabled, and, unless {@code force}, not blocked by a modal dialog), because time has
     * passed since the first check.
     */
    private Map<String, Object> focusFailed(Invocation inv, final Component c, String requested, String reason,
                                            final boolean force, boolean fallback) throws Exception {
        if (!fallback || c == null) {
            return notFired(reason);
        }
        Edt.Action plan = Edt.read(ctx, inv.remainingMs(), new Callable<Edt.Action>() {
            @Override
            public Edt.Action call() {
                ComponentView view = new ComponentView(ctx);
                requireClickable(view, c);
                if (!force) {
                    windows.requireNotBlocked(view, c);
                }
                return actionPlan(ctx, c);
            }
        });
        Edt.post(ctx, inv.remainingMs(), "swing.click ACTION (fall-back from COMPONENT)", plan);
        return degraded(ACTION, requested, FOCUS_FAILED + ": " + reason);
    }

    /**
     * Adds the focus listener and asks for the focus in the component's window. EDT only.
     *
     * @return what {@code requestFocusInWindow()} returned
     */
    private static Boolean requestFocus(Component c, FocusWait wait) {
        wait.listening(c);
        c.addFocusListener(wait);
        boolean ok = c.requestFocusInWindow();
        if (c.hasFocus()) {
            wait.arrived();
        }
        return Boolean.valueOf(ok);
    }

    /**
     * What is left of {@code focusTimeoutMs} once {@code usedMs} of it are spent, cut to the call's remaining time
     * minus a safety margin (and the time a fall-back needs) so that FOCUS_FAILED, or the fall-back click, is reported
     * before the dispatcher's TIMEOUT.
     */
    private static long budget(long focusTimeoutMs, long usedMs, long reserveMs, Invocation inv) {
        long left = focusTimeoutMs - Math.min(Math.max(0, usedMs), focusTimeoutMs);
        return Math.max(0, Math.min(left, inv.remainingMs() - SAFETY_MS - reserveMs));
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Posts the listener removal; it runs after the tasks that added the listeners, whenever those run. */
    private void removeLater(final FocusWait wait, final WindowWait windowWait,
                             final AtomicReference<Window> activated) {
        try {
            Edt.post(ctx, CLEANUP_POST_MS, "remove focus listeners", new Edt.Action() {
                @Override
                public void run() {
                    Component c = wait.listenedTo();
                    if (c != null) {
                        c.removeFocusListener(wait);
                    }
                    Window w = activated.get();
                    if (w != null) {
                        w.removeWindowFocusListener(windowWait);
                    }
                }
            });
        } catch (Exception e) {
            ctx.log().warn("swing: could not post the removal of a focus listener: " + e, e);
        }
    }

    /**
     * The ACTION rung for a component, chosen on the EDT: the first {@link ActionFirer} that can fire it, else
     * {@code AbstractButton.doClick()}, else a dispatched mouse click.
     */
    static Edt.Action actionPlan(AgentContext ctx, final Component c) {
        for (final ActionFirer firer : ctx.spi().all(ActionFirer.class)) {
            boolean can;
            try {
                can = firer.canFire(c);
            } catch (RuntimeException | LinkageError e) {
                ctx.log().warn("swing: ActionFirer " + firer.getClass().getName() + ".canFire failed: " + e, e);
                continue;
            }
            if (can) {
                return new Edt.Action() {
                    @Override
                    public void run() throws Exception {
                        firer.fire(c);
                    }
                };
            }
        }
        if (c instanceof AbstractButton) {
            return new Edt.Action() {
                @Override
                public void run() {
                    ((AbstractButton) c).doClick();
                }
            };
        }
        return mouseClickAction(c);
    }

    private static Edt.Action mouseClickAction(final Component c) {
        return new Edt.Action() {
            @Override
            public void run() {
                mouseClick(c);
            }
        };
    }

    /**
     * Delivers MOUSE_PRESSED, MOUSE_RELEASED and MOUSE_CLICKED (left button, one click) at the component's centre with
     * {@code dispatchEvent}; never posted to the system queue. EDT only.
     */
    static void mouseClick(Component c) {
        int x = Math.max(0, c.getWidth() / 2);
        int y = Math.max(0, c.getHeight() / 2);
        long when = System.currentTimeMillis();
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, when, InputEvent.BUTTON1_DOWN_MASK, x, y, 1,
                false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, when, 0, x, y, 1, false, MouseEvent.BUTTON1));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, when, 0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    /** INVALID_PARAMS unless the component is showing and enabled. EDT only. */
    static void requireClickable(ComponentView view, Component c) {
        if (!c.isShowing()) {
            throw Errors.invalid(view.describe(c) + " is not showing", Errors.data("ref", view.ref(c), "reason",
                    "not showing"));
        }
        if (!c.isEnabled()) {
            throw Errors.invalid(view.describe(c) + " is disabled", Errors.data("ref", view.ref(c), "reason",
                    "disabled"));
        }
    }

    /** {@code {rung, requestedRung, fidelity: "FULL", fired: true}}: the click fired on the rung asked for. */
    static Map<String, Object> fired(String rung, String requested) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rung", rung);
        m.put("requestedRung", requested);
        m.put("fidelity", FULL);
        m.put("fired", Boolean.TRUE);
        return m;
    }

    /** {@code {rung, requestedRung, fidelity: "DEGRADED", reason, fired: true}}: it fired on a lower rung. */
    static Map<String, Object> degraded(String rung, String requested, String reason) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rung", rung);
        m.put("requestedRung", requested);
        m.put("fidelity", DEGRADED);
        m.put("reason", reason);
        m.put("fired", Boolean.TRUE);
        return m;
    }

    /** {@code {rung: "FOCUS_FAILED", fired: false, reason}}: nothing was clicked (unchanged by amendment A28). */
    static Map<String, Object> notFired(String reason) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("rung", FOCUS_FAILED);
        m.put("fired", Boolean.FALSE);
        m.put("reason", reason);
        return m;
    }

    /** Counts down when the component gains focus; added for one COMPONENT click and removed afterwards. */
    private static final class FocusWait extends FocusAdapter {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<Component> component = new AtomicReference<Component>();
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void focusGained(FocusEvent e) {
            latch.countDown();
        }

        /** Records the component this listener is added to, so that the cleanup removes it there. */
        void listening(Component c) {
            component.set(c);
        }

        Component listenedTo() {
            return component.get();
        }

        void arrived() {
            latch.countDown();
        }

        boolean hasArrived() {
            return latch.getCount() == 0;
        }

        boolean await(long ms) throws InterruptedException {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Counts down when the window becomes the focused window; added for one COMPONENT click with
     * {@code activateWindow} and removed afterwards.
     */
    private static final class WindowWait extends WindowAdapter {
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void windowGainedFocus(WindowEvent e) {
            latch.countDown();
        }

        void arrived() {
            latch.countDown();
        }

        boolean hasArrived() {
            return latch.getCount() == 0;
        }

        boolean await(long ms) throws InterruptedException {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        }
    }
}
