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
import ai.skillroy.testjava.agent.api.spi.InputVeto;

import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Component;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;

/**
 * The click rungs of {@code swing.click}. ACTION fires the widget's semantic action ({@link ActionFirer}, else
 * {@code doClick()}, else a dispatched mouse click); COMPONENT focuses the component first and clicks only once it owns
 * the focus, optionally activating its window first ({@code toFront()} + {@code requestFocus()}, then waiting for the
 * window to be the focused window, re-issuing the activation once a second while it waits (amendment A39):
 * {@code requestFocusInWindow()} fails in any window but the focused one); DISPATCH
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
 * <p>Phase 8 (phase8-contracts.md 3.1, 3.2, 3.3, 3.5): every rung checks, in the EDT read that resolves the target,
 * clickable (not showing, then collapsed, then disabled: {@code INVALID_PARAMS}), then the input veto
 * ({@code INPUT_REJECTED vetoed}, never skipped by {@code force}; COMPONENT asks before the window activation and the
 * focus request), then the modal state; every posted click asks the veto again immediately before it acts (a refusal
 * there acts on nothing and is logged and counted, {@link InputVetoes.Withheld}). ACTION and COMPONENT fire an AWT
 * {@code Button} or {@code Checkbox} semantically ({@link #semantic}).
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
    /**
     * Amendment A39: how often the COMPONENT rung re-issues {@code toFront()} + {@code requestFocus()} while it waits
     * for the component's window to become the focused window. Under an X server without a window manager the
     * activation is a single {@code XSetInputFocus}, and it loses against the activation of a window shown a few
     * milliseconds earlier (whose own activation ends with one more native request for that window); the last
     * request wins, the window system and the toolkit end consistent with the earlier window focused, and nothing
     * re-issues the lost request. A fresh request a second later takes the focus at once.
     */
    static final long REACTIVATE_MS = 1000;
    /** The clock of {@link #awaitActivation} in production. */
    static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long millis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
    };

    private final AgentContext ctx;
    private final Windows windows;
    /** The module's count of calls whose posted task a veto withheld ({@code vetoWithheld}, D503). */
    private final AtomicLong withheld;

    Clicks(AgentContext ctx, Windows windows) {
        this(ctx, windows, new AtomicLong());
    }

    Clicks(AgentContext ctx, Windows windows, AtomicLong withheld) {
        this.ctx = ctx;
        this.windows = windows;
        this.withheld = withheld;
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
        final String route = dispatch ? InputVeto.CLICK_DISPATCH : InputVeto.CLICK_ACTION;
        final InputVetoes.Withheld task = new InputVetoes.Withheld(ctx, withheld);
        final Edt.Action plan = Edt.read(ctx, inv.remainingMs(), new Callable<Edt.Action>() {
            @Override
            public Edt.Action call() {
                ComponentView view = new ComponentView(ctx);
                final Component c = t.resolve(view);
                requireClickable(view, c);
                requireNotVetoed(view, c, route);
                if (!f) {
                    windows.requireNotBlocked(view, c);
                }
                return InputVetoes.guarded(task, route, dispatch ? mouseClickAction(c) : actionPlan(ctx, c), c);
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
                        // before the activation and the focus request: they run the application's focus listeners
                        requireNotVetoed(view, c, InputVeto.CLICK_COMPONENT);
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
                // focusTimeoutMs bounds both waits together. A39: the activation is re-issued once a second while
                // waiting (posted, never waited for), because a single request can lose against the activation of a
                // window shown just before, after which nothing re-issues it
                long budget = budget(focusTimeoutMs, 0, reserve, inv);
                final Window w = activated.get();
                final AtomicInteger reissued = new AtomicInteger();
                Edt.Action reactivate = new Edt.Action() {
                    @Override
                    public void run() throws Exception {
                        reissued.incrementAndGet();
                        Edt.post(ctx, inv.remainingMs(), "swing.click COMPONENT re-activate", new Edt.Action() {
                            @Override
                            public void run() {
                                if (wait.cancelled.get() || windowWait.hasArrived() || !w.isDisplayable()) {
                                    return;
                                }
                                if (!w.isFocused()) {
                                    w.toFront();
                                    w.requestFocus();
                                }
                                if (w.isFocused()) {
                                    windowWait.arrived();
                                }
                            }
                        });
                    }
                };
                if (!windowWait.hasArrived()
                        && !awaitActivation(windowWait, budget, REACTIVATE_MS, SYSTEM_CLOCK, reactivate)) {
                    return focusFailed(inv, listenedTo.get(), requested, "the window did not become the focused window"
                            + " within " + budget + " ms of toFront() and requestFocus()" + (reissued.get() == 0 ? ""
                            : " (re-issued " + reissued.get() + (reissued.get() == 1 ? " time)" : " times)")),
                            force, fallback);
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
            final InputVetoes.Withheld task = new InputVetoes.Withheld(ctx, withheld);
            Edt.post(ctx, inv.remainingMs(), "swing.click COMPONENT", InputVetoes.guarded(task,
                    InputVeto.CLICK_COMPONENT, new Edt.Action() {
                        @Override
                        public void run() throws Exception {
                            Edt.Action semantic = semantic(c);
                            if (semantic != null) {
                                semantic.run(); // focus, then the semantic click (D515)
                            } else {
                                mouseClick(c);
                            }
                        }
                    }, c));
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
        final InputVetoes.Withheld task = new InputVetoes.Withheld(ctx, withheld);
        Edt.Action plan = Edt.read(ctx, inv.remainingMs(), new Callable<Edt.Action>() {
            @Override
            public Edt.Action call() {
                ComponentView view = new ComponentView(ctx);
                requireClickable(view, c);
                requireNotVetoed(view, c, InputVeto.CLICK_ACTION);
                if (!force) {
                    windows.requireNotBlocked(view, c);
                }
                return InputVetoes.guarded(task, InputVeto.CLICK_ACTION, actionPlan(ctx, c), c);
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

    /**
     * The window-activation wait of the COMPONENT rung (amendment A39): waits on {@code wait} in slices of at most
     * {@code retryMs}, and after every slice that ends without the window being the focused window re-issues the
     * activation through {@code reissue}, until the focus arrives or {@code budgetMs} is spent; nothing is re-issued
     * once the budget is spent. The wait and the clock are parameters so that a unit test proves the re-issue
     * without a display.
     *
     * @return whether the window became the focused window within the budget
     */
    static boolean awaitActivation(Waiter wait, long budgetMs, long retryMs, Clock clock, Edt.Action reissue)
            throws Exception {
        long slice = Math.max(1, retryMs);
        long start = clock.millis();
        long left = budgetMs;
        while (true) {
            if (wait.await(Math.max(0, Math.min(left, slice)))) {
                return true;
            }
            left = budgetMs - (clock.millis() - start);
            if (left <= 0) {
                return false;
            }
            reissue.run();
        }
    }

    /** A bounded wait for a window to become the focused window; {@link WindowWait} in production, a fake in a test. */
    interface Waiter {
        /** @return whether the window became the focused window within {@code ms} */
        boolean await(long ms) throws InterruptedException;
    }

    /** A clock in milliseconds; {@link #SYSTEM_CLOCK} in production, a fake in a test. */
    interface Clock {
        long millis();
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
     * The ACTION rung for a component, chosen on the EDT: the first {@link ActionFirer} that can fire it, else the
     * semantic click ({@link #semantic}: {@code AbstractButton.doClick()}, an AWT {@code Button}'s
     * {@code ActionEvent}, an AWT {@code Checkbox}'s state and {@code ItemEvent}), else a dispatched mouse click. The
     * caller wraps it in the in-task veto re-ask ({@link InputVetoes#guarded}).
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
        Edt.Action semantic = semantic(c);
        return semantic != null ? semantic : mouseClickAction(c);
    }

    /**
     * The semantic click of a component (Phase 8, phase8-contracts.md 3.5, D515), run inside the posted task:
     * {@code AbstractButton.doClick()}; an AWT {@code Button} gets one {@code ActionEvent} with its action command
     * dispatched to it (no mouse event, so its peer fires nothing beside it); an AWT {@code Checkbox} changes as the X
     * peer changes it (in a {@code CheckboxGroup} and already selected: nothing, no event; else its state flips, in a
     * group to selected) and gets one {@code ItemEvent}; anything else null (the caller dispatches a mouse click).
     */
    static Edt.Action semantic(final Component c) {
        if (c instanceof AbstractButton) {
            return new Edt.Action() {
                @Override
                public void run() {
                    ((AbstractButton) c).doClick();
                }
            };
        }
        if (c instanceof Button) {
            return new Edt.Action() {
                @Override
                public void run() {
                    Button b = (Button) c;
                    b.dispatchEvent(new ActionEvent(b, ActionEvent.ACTION_PERFORMED, b.getActionCommand(),
                            System.currentTimeMillis(), 0));
                }
            };
        }
        if (c instanceof Checkbox) {
            return new Edt.Action() {
                @Override
                public void run() {
                    Checkbox box = (Checkbox) c;
                    boolean grouped = box.getCheckboxGroup() != null;
                    if (grouped && box.getState()) {
                        return; // a selected radio stays selected; no event (the call still reports fired)
                    }
                    boolean now = grouped || !box.getState();
                    box.setState(now);
                    box.dispatchEvent(new ItemEvent(box, ItemEvent.ITEM_STATE_CHANGED, box.getLabel(),
                            now ? ItemEvent.SELECTED : ItemEvent.DESELECTED));
                }
            };
        }
        return null;
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

    /**
     * INVALID_PARAMS unless the component is showing, on screen (not collapsed, phase8-contracts.md 3.3) and enabled,
     * checked in that order. EDT only.
     */
    static void requireClickable(ComponentView view, Component c) {
        requireOnScreen(view, c);
        if (!c.isEnabled()) {
            throw Errors.invalid(view.describe(c) + " is disabled", Errors.data("ref", view.ref(c), "reason",
                    "disabled"));
        }
    }

    /**
     * INVALID_PARAMS unless the component is showing ({@code reason: "not showing"}) and has an effective visible area
     * ({@code reason: "collapsed"} with {@code by}, phase8-contracts.md 3.3, D510). EDT only.
     */
    static void requireOnScreen(ComponentView view, Component c) {
        if (!c.isShowing()) {
            throw Errors.invalid(view.describe(c) + " is not showing", Errors.data("ref", view.ref(c), "reason",
                    "not showing"));
        }
        Component by = view.collapsedBy(c);
        if (by != null) {
            throw Errors.collapsed(view, c, by);
        }
    }

    /**
     * INPUT_REJECTED {@code vetoed} when a registered {@code InputVeto} refuses the component or an ancestor on the
     * route (phase8-contracts.md 3.2, D502); never skipped by {@code force}. EDT only.
     */
    static void requireNotVetoed(ComponentView view, Component c, String route) {
        InputVetoes.Refusal r = view.vetoes().check(c, route);
        if (r != null) {
            throw Errors.vetoed(view, c, r, null);
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
    static final class WindowWait extends WindowAdapter implements Waiter {
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

        @Override
        public boolean await(long ms) throws InterruptedException {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        }
    }
}
