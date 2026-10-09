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

package ai.skillroy.testjava.spikes.bs3.engine;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import ai.skillroy.testjava.spikes.bs3.spy.Spy;

/**
 * The runtime registry behind {@link Spy} (design 2.1, {@code skillroy-agent-hooks}): per hook id a mode, a stub
 * value, counters, the last captured copies; events go to a bounded ring. Changing a mode or a stub value, or
 * swapping the whole handler, takes effect at the next call and needs no retransformation.
 *
 * <p>Loaded only after {@code Spy} is on the bootstrap path ({@link SpyBoot}): its interface is {@code Spy.Handler}.
 */
final class HookRegistry implements Spy.Handler {

    /** The state of one hook. */
    static final class Hook {
        final String id;
        volatile HookMode mode;
        volatile Object stubValue;
        final LongAdder enters = new LongAdder();
        final LongAdder exits = new LongAdder();
        final LongAdder skips = new LongAdder();
        volatile Object[] capturedArgs;
        volatile Object capturedResult;
        volatile Throwable capturedThrown;

        Hook(String id, HookMode mode) {
            this.id = id;
            this.mode = mode;
        }
    }

    final String name;
    final EventRing events;
    private final ConcurrentHashMap<String, Hook> hooks = new ConcurrentHashMap<String, Hook>();

    HookRegistry(String name, EventRing events) {
        this.name = name;
        this.events = events;
    }

    Hook define(String id, HookMode mode) {
        Hook h = new Hook(id, mode);
        hooks.put(id, h);
        return h;
    }

    Hook hook(String id) {
        return hooks.get(id);
    }

    void mode(String id, HookMode mode) {
        Hook h = hooks.get(id);
        if (h != null) {
            h.mode = mode;
        }
    }

    void stub(String id, Object value) {
        Hook h = hooks.get(id);
        if (h != null) {
            h.stubValue = value;
            h.mode = HookMode.STUB;
        }
    }

    void forget(String id) {
        hooks.remove(id);
    }

    @Override
    public Object enter(String hookId, Object self, Object[] args) {
        Hook h = hooks.get(hookId);
        if (h == null) {
            return null;
        }
        HookMode mode = h.mode;
        if (mode == HookMode.OFF) {
            return null;
        }
        h.enters.increment();
        if (mode == HookMode.STUB) {
            h.skips.increment();
            Object value = h.stubValue;
            events.add(hookId, name, "skip", Render.args(args) + " -> " + Render.value(value));
            return Spy.stub(value);
        }
        if (mode == HookMode.CAPTURE) {
            Object[] copy = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                copy[i] = Render.copy(args[i]);
            }
            h.capturedArgs = copy;
        }
        events.add(hookId, name, "enter", Render.args(args));
        return null;
    }

    @Override
    public void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown) {
        Hook h = hooks.get(hookId);
        if (h == null) {
            return;
        }
        HookMode mode = h.mode;
        if (mode == HookMode.OFF) {
            return;
        }
        h.exits.increment();
        if (mode == HookMode.CAPTURE) {
            h.capturedResult = Render.copy(returned);
            h.capturedThrown = thrown;
        }
        events.add(hookId, name, "exit", thrown != null
                ? "threw " + thrown.getClass().getName() : "returned " + Render.value(returned));
    }
}
