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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.samples.lending.Engine;
import ai.skillroy.testjava.samples.lending.LendingApp;

/**
 * Owns the module's {@link EngineEvents} listener and the rule the application sets for it: the engine's listener
 * list is a plain {@code ArrayList} that the engine thread iterates, so a listener may be added or removed only while
 * the engine is blocked waiting for input ({@code Engine.isAwaitingInput()}).
 *
 * <p>Attaching: {@link #tryAttach} is called from the module's readiness source, which the agent polls until it
 * reports ready, and again by everything of the module that looks at the desk afterwards (its primitives, its
 * probes, its {@code app.state} section); it attaches at the first call that finds the engine waiting for input.
 * Normally that is a readiness poll, so the module is ready exactly when its listener is in place.
 *
 * <p>A desk that is asking a question ({@link #promptBlocksAttach}) does not wait for input and may stay that way
 * until somebody answers. The module reports ready all the same, without a listener, because only a ready agent can
 * answer the question; {@code lending.input} is UNAVAILABLE until the listener is there. Whoever answers (the agent
 * through {@code lending.prompt.answer}, see {@link #attachWhenWaiting}, or a person at the desk), the listener
 * follows: from then on a daemon thread {@code skillroy-lending-attach} looks at the engine every {@value #WATCH_MS} ms
 * (the engine reports nothing to a module without a listener) and ends once the listener is attached or the module
 * is uninstalled. On such a <em>late</em> attach the slot sets the capability {@code lending.input} to OK and publishes
 * one {@code input.requested} event from the engine's current status, so that a controller waiting for the desk
 * to be ready again has its event.
 *
 * <p>Detaching ({@link #detach}): the listener is made inert at once; it is removed now if the engine waits for
 * input or has stopped, otherwise at the engine's next {@code inputRequested} callback (an event, not a timed poll),
 * waiting at most the given time. If even that does not happen (a prompt stays open), a daemon thread
 * {@code skillroy-lending-detach} finishes the removal later; until then the inert listener does nothing.
 *
 * <p>Two agents can meet at one desk: one that was disabled while a prompt was open still has to remove its
 * inert listener when the next one attaches its own, both at the moment the engine waits for input again. Every
 * change of the engine's listener list therefore happens under {@link #LIST_LOCK}, a monitor shared by every copy of
 * this class in the JVM, whatever class loader loaded it.
 *
 * <p>Thread-safe.
 */
final class ListenerSlot {

    /** How long the deferred removal may wait for the engine to wait for input again. */
    static final long DEFERRED_DETACH_MS = 30L * 60L * 1000L;
    /** Pause between two looks at the engine in {@link #attachWhenWaiting}. */
    static final long ATTACH_POLL_MS = 5L;
    /** Pause between two looks of the {@code skillroy-lending-attach} thread. */
    static final long WATCH_MS = 250L;
    /** Name of the thread that attaches the listener after a module became ready without one. */
    static final String WATCHER_NAME = "skillroy-lending-attach";

    /**
     * Guards additions to and removals from the engine's listener list by agent modules. An interned string is one
     * object per JVM, so module copies in different class loaders (a disabled agent and its successor) share it
     * without touching a monitor of the application.
     */
    private static final Object LIST_LOCK =
            "ai.skillroy.testjava.samples.lending.agent.ListenerSlot.LIST_LOCK".intern();

    private final AgentContext ctx;
    private EngineEvents events;
    private boolean closed;
    /** True once the module reported ready without a listener: the attach that follows is a late one. */
    private boolean readyWithoutListener;
    /** The {@code skillroy-lending-attach} thread, while one runs. */
    private Thread watcher;

    ListenerSlot(AgentContext ctx) {
        this.ctx = ctx;
    }

    /** @return the attached listener, or null */
    synchronized EngineEvents events() {
        return events;
    }

    /**
     * Attaches the listener if the engine is waiting for input now.
     *
     * @return true if the listener is attached (now or before)
     */
    boolean tryAttach(LendingApp app) {
        EngineEvents created;
        boolean late;
        synchronized (this) {
            if (closed || app == null) {
                return false;
            }
            if (events != null) {
                return true;
            }
            Engine engine = app.engine();
            if (!engine.isRunning() || !engine.isAwaitingInput()) {
                return false;
            }
            created = new EngineEvents(ctx, engine);
            synchronized (LIST_LOCK) {
                if (!engine.isAwaitingInput()) {
                    return false;
                }
                engine.addListener(created);
            }
            events = created;
            late = readyWithoutListener;
            readyWithoutListener = false;
            ctx.log().info("lending: engine listener attached (" + engine.listenerCount() + " listeners)"
                    + (late ? ", after the desk's prompt was answered" : ""));
        }
        if (late) {
            // Outside the slot's monitor: neither call may ever wait for a thread that wants the slot.
            try {
                ctx.capabilities().set(LendingPrimitives.INPUT, CapabilityState.OK, null, "inbox");
                created.announceWaiting();
            } catch (RuntimeException e) {
                ctx.log().warn("lending: announcing the late engine listener failed: " + e, e);
            }
        }
        return true;
    }

    /**
     * True if the desk runs and is asking a question, so that no listener can be attached now. The module then
     * counts as ready without one (see the class comment), and the attach that follows is a late attach.
     */
    synchronized boolean promptBlocksAttach(LendingApp app) {
        if (closed || app == null || events != null) {
            return false;
        }
        if (!app.engine().isRunning() || app.prompts().current() == null) {
            return false;
        }
        if (!readyWithoutListener) {
            readyWithoutListener = true;
            ctx.log().warn("lending: the desk has a prompt open; ready without the engine listener, which is "
                    + "attached once the desk waits for input again");
            startWatcher(app);
        }
        return true;
    }

    /** Starts the thread that attaches the listener once the engine waits for input. Called under the monitor. */
    private void startWatcher(final LendingApp app) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (app.engine().isRunning() && !tryAttach(app)) {
                        synchronized (ListenerSlot.this) {
                            if (closed) {
                                return;
                            }
                        }
                        Thread.sleep(WATCH_MS);
                    }
                } catch (InterruptedException stopped) {
                    // The module was uninstalled.
                } catch (Throwable t) {
                    try {
                        ctx.log().warn("lending: watching for a moment to attach the engine listener failed: " + t, t);
                    } catch (Throwable ignored) {
                        // Nothing may escape an agent thread.
                    }
                }
            }
        }, WATCHER_NAME);
        t.setDaemon(true);
        watcher = t;
        t.start();
    }

    /**
     * Waits until the listener could be attached, for a caller that has just answered the desk's prompt and so
     * knows the engine is about to wait for input or to ask again. Without a listener the engine reports neither, so
     * this looks at its status every {@value #ATTACH_POLL_MS} ms, on the calling (agent) thread, for at most
     * {@code waitMs}.
     *
     * @return true if the listener is attached; false if the desk asked another question, stopped, or did
     *     neither within {@code waitMs}
     */
    boolean attachWhenWaiting(LendingApp app, long waitMs) {
        long deadline = System.nanoTime() + Math.max(0L, waitMs) * 1000000L;
        try {
            while (true) {
                if (tryAttach(app)) {
                    return true;
                }
                synchronized (this) {
                    if (closed) {
                        return false;
                    }
                }
                if (!app.engine().isRunning() || app.prompts().current() != null) {
                    return false;
                }
                if (System.nanoTime() - deadline >= 0) {
                    return false;
                }
                Thread.sleep(ATTACH_POLL_MS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Makes the listener inert and removes it from the engine (see the class comment). Never throws.
     *
     * @param waitMs how long to wait for a moment when removal is allowed before handing it to a daemon thread
     */
    void detach(long waitMs) {
        final EngineEvents e;
        synchronized (this) {
            closed = true;
            e = events;
            events = null;
            if (watcher != null) {
                watcher.interrupt();
                watcher = null;
            }
        }
        if (e == null) {
            return;
        }
        try {
            e.deactivate();
            if (removeWhenSafe(e, waitMs)) {
                ctx.log().info("lending: engine listener removed (" + e.engine().listenerCount() + " listeners)");
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        removeWhenSafe(e, DEFERRED_DETACH_MS);
                    } catch (Throwable ignored) {
                        // Best effort; the listener is inert either way.
                    }
                }
            }, "skillroy-lending-detach");
            t.setDaemon(true);
            t.start();
            ctx.log().warn("lending: the engine is not waiting for input; the inert listener is removed later");
        } catch (Throwable t) {
            try {
                ctx.log().warn("lending: removing the engine listener failed: " + t, t);
            } catch (Throwable ignored) {
                // uninstall never throws.
            }
        }
    }

    /**
     * Removes {@code e} once the engine waits for input or its thread has ended, waiting at most {@code waitMs}.
     *
     * @return true if removed
     */
    static boolean removeWhenSafe(EngineEvents e, long waitMs) {
        Engine engine = e.engine();
        long deadline = System.nanoTime() + waitMs * 1000000L;
        try {
            while (true) {
                long seen = e.requestedCount();
                Thread t = engine.thread();
                synchronized (LIST_LOCK) {
                    if (t == null || !t.isAlive() || engine.isAwaitingInput()) {
                        engine.removeListener(e);
                        return true;
                    }
                }
                long remaining = (deadline - System.nanoTime()) / 1000000L;
                if (remaining <= 0) {
                    return false;
                }
                if (!engine.isRunning()) {
                    // Stopping: the thread ends soon, after which removal is safe.
                    t.join(remaining);
                } else {
                    e.awaitRequested(seen, remaining);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
