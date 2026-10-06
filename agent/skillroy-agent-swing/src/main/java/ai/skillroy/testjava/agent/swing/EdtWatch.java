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
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.protocol.Topics;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * {@code swing.settle} and {@code swing.edtState}: is the EDT idle with respect to window activity, and is it alive at
 * all. Both use EDT marker round trips (a task posted with {@code invokeLater}); a marker also runs while a modal
 * dialog is showing, because the dialog's secondary loop keeps pumping events. A settle marker also lets the window
 * tap compare the showing windows with those it reported, so a window hidden or shown again is window activity too.
 *
 * <p>{@code swing.edtState} posts nothing while no event-dispatch thread exists: before the application has started
 * AWT it answers from what it knows without touching AWT, and after AWT has shut its dispatch thread down it reads the
 * window state off the EDT instead of starting a new one.
 *
 * <p>Thread-safe. Runs on an agent thread; never holds the EDT.
 */
final class EdtWatch {

    /** Longest a {@code swing.edtState} call takes. */
    static final long EDT_STATE_BUDGET_MS = 500;
    /** Part of the budget kept for reading the state off the EDT when the marker does not run. */
    static final long FALLBACK_WAIT_MS = 100;

    private static final TopicFilter WINDOW_TOPICS = TopicFilter.parse(Topics.WINDOW_OPENED + ","
            + Topics.WINDOW_CLOSED);

    private final AgentContext ctx;
    private final WindowTap tap;
    private final AwtState awt;

    EdtWatch(AgentContext ctx, WindowTap tap, AwtState awt) {
        this.ctx = ctx;
        this.tap = tap;
        this.awt = awt;
    }

    /**
     * Waits until two consecutive EDT marker round trips complete with no {@code window.opened}/{@code window.closed}
     * between them and none for {@code quietMs} after, or until {@code timeoutMs} (cut to the call's remaining time)
     * passes. Every marker lets the tap compare the showing windows, and after a quiet period one more marker does so
     * before the call answers, because a window hidden or shown again raises no AWT event that could end the quiet
     * wait early. Returns {@code {settled, ms}}.
     */
    Map<String, Object> settle(Invocation inv, long timeoutMs, long quietMs) throws Exception {
        long start = System.nanoTime();
        long remaining = inv.remainingMs();
        long budget = Math.max(0, Math.min(timeoutMs, remaining - Math.min(250, remaining / 10)));
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(budget);
        while (true) {
            if (!marker(deadline)) {
                return settleResult(false, start);
            }
            long afterFirst = ctx.events().lastSeq();
            if (!marker(deadline)) {
                return settleResult(false, start);
            }
            if (!ctx.events().since(afterFirst, WINDOW_TOPICS, 1).isEmpty()) {
                continue; // a window opened or closed between the markers: start again
            }
            if (quietMs <= 0) {
                return settleResult(true, start);
            }
            long wait = Math.min(quietMs, leftMs(deadline));
            Event activity = wait <= 0 ? null : ctx.events().await(afterFirst, WINDOW_TOPICS, null, wait);
            if (activity != null) {
                continue;
            }
            if (wait < quietMs) {
                return settleResult(false, start);
            }
            if (!marker(deadline)) {
                return settleResult(false, start);
            }
            if (!ctx.events().since(afterFirst, WINDOW_TOPICS, 1).isEmpty()) {
                continue; // the closing comparison found a window hidden or shown again during the quiet period
            }
            return settleResult(true, start);
        }
    }

    /** One EDT round trip, in which the tap compares the showing windows, finished before the deadline. */
    private boolean marker(long deadlineNanos) throws Exception {
        long left = leftMs(deadlineNanos);
        if (left <= 0) {
            return false;
        }
        try {
            Edt.read(ctx, left, new Callable<Object>() {
                @Override
                public Object call() {
                    tap.sync();
                    return null;
                }
            });
            return true;
        } catch (RpcException e) {
            if (e.error().code() == ErrorCodes.TIMEOUT) {
                return false;
            }
            throw e;
        }
    }

    private static long leftMs(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    private static Map<String, Object> settleResult(boolean settled, long startNanos) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("settled", Boolean.valueOf(settled));
        m.put("ms", Long.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)));
        return m;
    }

    /**
     * {@code {dispatchThreadAlive, edtBlocked, awtStarted, modalDialogs, windowCount, focusOwner?}}. The whole call
     * takes at most 500 ms:
     * <ul>
     *   <li>no event-dispatch thread and AWT never seen running: answered without touching AWT
     *       ({@code awtStarted:false}, no modal dialogs, no windows), because any AWT call could initialise the
     *       toolkit on the application's behalf;</li>
     *   <li>no event-dispatch thread but AWT seen running (AWT ends an idle dispatch thread): the state is read off
     *       the EDT on an agent worker, so that no new dispatch thread is started;</li>
     *   <li>otherwise up to 400 ms waiting for an EDT marker; if it does not run, the state is read off the EDT on an
     *       agent worker within the rest of the budget and {@code edtBlocked} is true.</li>
     * </ul>
     */
    Map<String, Object> edtState(Invocation inv) throws Exception {
        boolean alive = AwtThreads.eventQueueAlive();
        if (!alive && !awt.started()) {
            return notStarted();
        }
        if (!alive) {
            return offEdt(false, false, Math.max(1, Math.min(EDT_STATE_BUDGET_MS, inv.remainingMs())));
        }
        long wait = Math.max(1, Math.min(EDT_STATE_BUDGET_MS - FALLBACK_WAIT_MS, inv.remainingMs()));
        try {
            return Edt.read(ctx, wait, new Callable<Map<String, Object>>() {
                @Override
                public Map<String, Object> call() {
                    return state(true, false);
                }
            });
        } catch (RpcException e) {
            if (e.error().code() != ErrorCodes.TIMEOUT) {
                throw e;
            }
        }
        return offEdt(AwtThreads.eventQueueAlive(), true, FALLBACK_WAIT_MS);
    }

    /** The state read on an agent worker, never on the EDT; a minimal answer if even that does not finish in time. */
    private Map<String, Object> offEdt(final boolean alive, final boolean blocked, long waitMs) throws Exception {
        try {
            Map<String, Object> state = ctx.threads().call(ThreadPolicy.WORKER, new Callable<Map<String, Object>>() {
                @Override
                public Map<String, Object> call() {
                    return state(alive, blocked);
                }
            }, waitMs);
            if (state != null) {
                return state;
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            ctx.log().warn("swing: edtState could not read window state off the EDT: " + e);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dispatchThreadAlive", Boolean.valueOf(alive));
        m.put("edtBlocked", Boolean.valueOf(blocked));
        m.put("awtStarted", Boolean.TRUE);
        m.put("modalDialogs", new ArrayList<Object>());
        m.put("windowCount", Long.valueOf(-1));
        return m;
    }

    /** The answer before the application has started AWT; touches no AWT class. */
    private static Map<String, Object> notStarted() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dispatchThreadAlive", Boolean.FALSE);
        m.put("edtBlocked", Boolean.FALSE);
        m.put("awtStarted", Boolean.FALSE);
        m.put("modalDialogs", new ArrayList<Object>());
        m.put("windowCount", Long.valueOf(0));
        return m;
    }

    private Map<String, Object> state(boolean alive, boolean blocked) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dispatchThreadAlive", Boolean.valueOf(alive));
        m.put("edtBlocked", Boolean.valueOf(blocked));
        m.put("awtStarted", Boolean.TRUE);
        List<Object> modal = new ArrayList<Object>();
        for (Dialog d : Windows.showingModalDialogs()) {
            modal.add(ctx.handles().handleFor(d, ComponentView.KIND));
        }
        m.put("modalDialogs", modal);
        m.put("windowCount", Long.valueOf(Windows.displayable().size()));
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null) {
            m.put("focusOwner", ctx.handles().handleFor(owner, ComponentView.KIND));
        }
        return m;
    }
}
