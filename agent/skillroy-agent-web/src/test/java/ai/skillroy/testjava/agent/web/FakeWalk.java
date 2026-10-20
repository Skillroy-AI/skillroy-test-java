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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake Swing walk: the components a test lists, showing as the test says, their states; counts the reads. A state's
 * {@code masked} follows the {@code CaptureMask}s registered in {@link #spi} (Phase 7 security fix S111), computed by
 * the module's own {@code ComponentReads.masked} over the component's parents; {@link #masked} (Phase 8, the rebuild's
 * one read for all components) answers from the same registry, and {@link #masksRegistered()} counts the
 * {@code BrowserMask}s too.
 */
final class FakeWalk implements SeedWalk {

    /** The registry the masks are looked up in (the harness's context's), or null for none. */
    volatile ai.skillroy.testjava.agent.api.SpiRegistry spi;

    final List<Object> components = new CopyOnWriteArrayList<Object>();
    final Set<Object> showing = Collections.synchronizedSet(Collections.newSetFromMap(
            new IdentityHashMap<Object, Boolean>()));
    final AtomicInteger componentReads = new AtomicInteger();
    final AtomicInteger showingReads = new AtomicInteger();
    volatile RuntimeException failure;

    @Override
    public List<Object> components(long budgetMs) {
        componentReads.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        return new ArrayList<Object>(components);
    }

    /** The states {@link #state} answers, by component (identity); a component not listed is 800 x 600, unblocked. */
    final Map<Object, ComponentState> states = Collections.synchronizedMap(new IdentityHashMap<Object,
            ComponentState>());
    final AtomicInteger stateReads = new AtomicInteger();

    /** A component's state as a test sets it. */
    void state(Object component, int width, int height, double scale, Object blocker, String title) {
        states.put(component, new ComponentState(showing.contains(component), width, height, scale, blocker, title));
    }

    @Override
    public ComponentState state(Object component, long budgetMs) {
        stateReads.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        ComponentState s = states.get(component);
        boolean masked = component instanceof java.awt.Component && ComponentReads.masked(
                (java.awt.Component) component, masks());
        if (s != null) {
            return new ComponentState(showing.contains(component), s.width, s.height, s.scale, s.blocker,
                    s.blockerTitle, masked);
        }
        return component == null ? ComponentState.UNKNOWN : new ComponentState(showing.contains(component), 800, 600,
                1.0, null, null, masked);
    }

    @Override
    public boolean masksRegistered() {
        ai.skillroy.testjava.agent.api.SpiRegistry r = spi;
        return !masks().isEmpty() || r != null
                && !r.all(ai.skillroy.testjava.agent.api.spi.web.BrowserMask.class).isEmpty();
    }

    /** How many times the rebuild's one masked read ran (Phase 8). */
    final AtomicInteger maskedReads = new AtomicInteger();
    /** When set, {@link #masked} throws it (the read failed). */
    volatile RuntimeException maskedFailure;

    @Override
    public Map<Object, Boolean> masked(Collection<?> objects, long budgetMs) {
        maskedReads.incrementAndGet();
        if (failure != null) {
            throw failure;
        }
        if (maskedFailure != null) {
            throw maskedFailure;
        }
        Map<Object, Boolean> out = new IdentityHashMap<Object, Boolean>();
        for (Object o : objects) {
            out.put(o, Boolean.valueOf(!(o instanceof java.awt.Component)
                    || ComponentReads.masked((java.awt.Component) o, masks())));
        }
        return out;
    }

    private List<ai.skillroy.testjava.agent.api.spi.CaptureMask> masks() {
        ai.skillroy.testjava.agent.api.SpiRegistry r = spi;
        List<ai.skillroy.testjava.agent.api.spi.CaptureMask> all = r == null ? null
                : r.all(ai.skillroy.testjava.agent.api.spi.CaptureMask.class);
        return all == null ? Collections.<ai.skillroy.testjava.agent.api.spi.CaptureMask>emptyList() : all;
    }

    @Override
    public Map<Object, Boolean> showing(Collection<?> objects, long budgetMs) {
        showingReads.incrementAndGet();
        Map<Object, Boolean> out = new IdentityHashMap<Object, Boolean>();
        for (Object o : objects) {
            out.put(o, Boolean.valueOf(showing.contains(o)));
        }
        return out;
    }
}
