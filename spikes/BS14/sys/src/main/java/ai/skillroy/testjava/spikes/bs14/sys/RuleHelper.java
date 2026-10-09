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

package ai.skillroy.testjava.spikes.bs14.sys;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The curated helper a rule names in its {@code HELPER} line (BS14 item 3). Byteman loads it through the trigger
 * class's loader and builds a subclass (its "helper adapter") whose constructor calls this class's {@code (Rule)}
 * constructor when one is declared, else the no-arg one. The spike declares both, counts which runs, keeps the rule as
 * {@code Object} and reads its name, trigger class and method reflectively. Two call shapes are offered so that the
 * spike can tell whether Byteman resolves a varargs method: {@code fired(Object...)} and the fixed-arity
 * {@code observe(Object)} to {@code observe(Object x 8)}.
 */
public class RuleHelper {

    /** The module's side, filled once at load; set-once, weak, identity-released (the shape of Spy's slot). */
    public interface Handler {
        void fired(String rule, String className, String method, Object[] bindings);
    }

    private static volatile WeakReference<Handler> slot;
    private static final Object SLOT_LOCK = new Object();
    private static final AtomicInteger RULE_CONSTRUCTOR = new AtomicInteger();
    private static final AtomicInteger NO_ARG_CONSTRUCTOR = new AtomicInteger();
    private static final AtomicInteger FAILURES = new AtomicInteger();

    private final Object rule;

    /** The constructor Byteman's helper adapter calls when it exists. The rule is kept as Object. */
    public RuleHelper(org.jboss.byteman.rule.Rule rule) {
        this.rule = rule;
        RULE_CONSTRUCTOR.incrementAndGet();
    }

    /** The fallback constructor; the spike counts whether it is ever used. */
    protected RuleHelper() {
        this.rule = null;
        NO_ARG_CONSTRUCTOR.incrementAndGet();
    }

    public static boolean install(Handler handler) {
        if (handler == null) {
            return false;
        }
        synchronized (SLOT_LOCK) {
            WeakReference<Handler> current = slot;
            if (current != null && current.get() != null) {
                return false;
            }
            slot = new WeakReference<Handler>(handler);
            return true;
        }
    }

    public static boolean uninstall(Handler handler) {
        synchronized (SLOT_LOCK) {
            WeakReference<Handler> current = slot;
            if (current == null || current.get() != handler) {
                return false;
            }
            slot = null;
            return true;
        }
    }

    public static boolean held() {
        WeakReference<Handler> current = slot;
        return current != null && current.get() != null;
    }

    /** Spike evidence: {constructed with the rule, constructed without, failures}. */
    public static long[] counters() {
        return new long[] {RULE_CONSTRUCTOR.get(), NO_ARG_CONSTRUCTOR.get(), FAILURES.get()};
    }

    /** A varargs call shape: does Byteman match {@code fired($0, $1)} to it? */
    public void fired(Object... bindings) {
        publish(bindings);
    }

    public void observe(Object a) {
        publish(new Object[] {a});
    }

    public void observe(Object a, Object b) {
        publish(new Object[] {a, b});
    }

    public void observe(Object a, Object b, Object c) {
        publish(new Object[] {a, b, c});
    }

    public void observe(Object a, Object b, Object c, Object d) {
        publish(new Object[] {a, b, c, d});
    }

    public void observe(Object a, Object b, Object c, Object d, Object e) {
        publish(new Object[] {a, b, c, d, e});
    }

    public void observe(Object a, Object b, Object c, Object d, Object e, Object f) {
        publish(new Object[] {a, b, c, d, e, f});
    }

    public void observe(Object a, Object b, Object c, Object d, Object e, Object f, Object g) {
        publish(new Object[] {a, b, c, d, e, f, g});
    }

    public void observe(Object a, Object b, Object c, Object d, Object e, Object f, Object g, Object h) {
        publish(new Object[] {a, b, c, d, e, f, g, h});
    }

    private void publish(Object[] bindings) {
        WeakReference<Handler> current = slot;
        Handler handler = current == null ? null : current.get();
        if (handler == null) {
            return;
        }
        try {
            handler.fired(read("getName"), read("getTriggerClass"), read("getTriggerMethod"), bindings);
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
        }
    }

    /** Reads a String property of the rule by reflection: no Byteman type in this class's constant pool. */
    public String read(String getter) {
        if (rule == null) {
            return null;
        }
        try {
            Method m = rule.getClass().getMethod(getter);
            Object v = m.invoke(rule);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return "<" + t.getClass().getName() + ">";
        }
    }
}
