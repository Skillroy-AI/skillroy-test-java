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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.ThreadPolicy;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

/**
 * The module's own bounded walk of the Swing tree (the Swing module's walk is in another module jar, which this one
 * cannot see): every component of every displayable window, read in one {@link ThreadPolicy#EDT_SYNC} call through
 * the agent's thread broker, bounded by the caller's budget and by {@link #MAX_COMPONENTS}. Nothing is asked of AWT
 * unless an event-dispatch thread already runs ({@link AwtThreads}); this class is the only one of the module that
 * names AWT classes for the tree (with {@link WebSurfaces}, which runs inside the Swing module's own EDT read), and it
 * is loaded only when an index rebuild or an input primitive needs it. It also reads one browser component's state for
 * {@code web.hitTest} and {@code web.pointer} (Phase 7): showing, size, screen scale, the modal dialog that blocks its
 * window.
 *
 * <p>Thread-safe; call from an agent worker, never from the EDT.
 */
final class SwingSeeds implements SeedWalk {

    /** At most this many components are handed to the adapters, as {@code swing.snapshot} caps its nodes. */
    static final int MAX_COMPONENTS = 50000;

    private static final long MIN_WAIT_MS = 1;

    private final AgentContext ctx;
    /** Whether an event-dispatch thread runs: {@link AwtThreads#eventQueueAlive()}, or the tests' answer. */
    private final BooleanSupplier edt;

    SwingSeeds(AgentContext ctx) {
        this(ctx, null);
    }

    /** A walk whose "does an event-dispatch thread run" answer is {@code edt}'s (null: {@link AwtThreads}). */
    SwingSeeds(AgentContext ctx, BooleanSupplier edt) {
        this.ctx = ctx;
        this.edt = edt;
    }

    private boolean eventQueueAlive() {
        BooleanSupplier check = edt;
        return check != null ? check.getAsBoolean() : AwtThreads.eventQueueAlive();
    }

    @Override
    public List<Object> components(long budgetMs) throws Exception {
        if (!eventQueueAlive()) {
            return Collections.emptyList();
        }
        return read(budgetMs, new Callable<List<Object>>() {
            @Override
            public List<Object> call() {
                List<Object> out = new ArrayList<Object>();
                Deque<Component> todo = new ArrayDeque<Component>();
                for (Window w : Window.getWindows()) {
                    if (w.isDisplayable()) {
                        todo.add(w);
                    }
                }
                while (!todo.isEmpty() && out.size() < MAX_COMPONENTS) {
                    Component c = todo.poll();
                    if (!c.isDisplayable()) {
                        continue;
                    }
                    out.add(c);
                    if (c instanceof Container) {
                        for (Component child : ((Container) c).getComponents()) {
                            todo.add(child);
                        }
                    }
                }
                return out;
            }
        });
    }

    @Override
    public Map<Object, Boolean> showing(final Collection<?> components, long budgetMs) throws Exception {
        final Map<Object, Boolean> out = new IdentityHashMap<Object, Boolean>();
        if (components.isEmpty() || !eventQueueAlive()) {
            for (Object c : components) {
                out.put(c, Boolean.FALSE);
            }
            return out;
        }
        return read(budgetMs, new Callable<Map<Object, Boolean>>() {
            @Override
            public Map<Object, Boolean> call() {
                for (Object c : components) {
                    out.put(c, Boolean.valueOf(c instanceof Component && ((Component) c).isShowing()));
                }
                return out;
            }
        });
    }

    /**
     * {@inheritDoc} Phase 8 security fix S167 (FE): a state that cannot be read (not a component, or no event-dispatch
     * thread to read it on) is {@link ComponentState#UNKNOWN_MASKED} while a mask is registered, as {@link #masked}
     * answers every component masked then (D-EW-10), so that a per-call judgement never lowers a browser's flag on a
     * read that was not made.
     */
    @Override
    public ComponentState state(final Object component, long budgetMs) throws Exception {
        if (!(component instanceof Component) || !eventQueueAlive()) {
            return masksRegistered() ? ComponentState.UNKNOWN_MASKED : ComponentState.UNKNOWN;
        }
        final List<ai.skillroy.testjava.agent.api.spi.CaptureMask> masks = masks();
        return read(budgetMs, new Callable<ComponentState>() {
            @Override
            public ComponentState call() {
                return ComponentReads.stateOf(component, masks);
            }
        });
    }

    @Override
    public Map<Object, Boolean> masked(final Collection<?> components, long budgetMs) throws Exception {
        final List<ai.skillroy.testjava.agent.api.spi.CaptureMask> masks = masks();
        if (components.isEmpty() || !eventQueueAlive()) {
            // No event-dispatch thread to ask the masks on: every component is masked (the safe side, D547).
            Map<Object, Boolean> out = new IdentityHashMap<Object, Boolean>();
            for (Object c : components) {
                out.put(c, Boolean.TRUE);
            }
            return out;
        }
        return read(budgetMs, new Callable<Map<Object, Boolean>>() {
            @Override
            public Map<Object, Boolean> call() {
                return ComponentReads.maskedOf(components, masks);
            }
        });
    }

    /**
     * True when a {@code CaptureMask} (S111) or a {@code BrowserMask} (Phase 8, gap 110) is registered; a registry that
     * cannot answer counts as one that holds a mask (the safe side).
     */
    @Override
    public boolean masksRegistered() {
        try {
            return !masks().isEmpty() || BrowserMasks.anyRegistered(ctx);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The registered {@code CaptureMask}s, in registration order (Phase 7 security fix S111). */
    private List<ai.skillroy.testjava.agent.api.spi.CaptureMask> masks() {
        List<ai.skillroy.testjava.agent.api.spi.CaptureMask> all = ctx == null || ctx.spi() == null ? null
                : ctx.spi().all(ai.skillroy.testjava.agent.api.spi.CaptureMask.class);
        return all == null ? Collections.<ai.skillroy.testjava.agent.api.spi.CaptureMask>emptyList() : all;
    }

    /** Runs the read on the EDT, waiting at most the budget; exceptions are carried back as values. */
    private <T> T read(long budgetMs, final Callable<T> body) throws Exception {
        Outcome<T> outcome = ctx.threads().call(ThreadPolicy.EDT_SYNC, new Callable<Outcome<T>>() {
            @Override
            public Outcome<T> call() {
                try {
                    return new Outcome<T>(body.call(), null);
                } catch (Throwable t) {
                    return new Outcome<T>(null, t);
                }
            }
        }, Math.max(MIN_WAIT_MS, budgetMs));
        if (outcome == null) {
            throw new IllegalStateException("the EDT read produced no outcome");
        }
        return outcome.get();
    }

    /** A value or the throwable that replaced it. */
    private static final class Outcome<T> {
        private final T value;
        private final Throwable failure;

        Outcome(T value, Throwable failure) {
            this.value = value;
            this.failure = failure;
        }

        T get() throws Exception {
            if (failure == null) {
                return value;
            }
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            throw new ExecutionException(failure);
        }
    }
}
