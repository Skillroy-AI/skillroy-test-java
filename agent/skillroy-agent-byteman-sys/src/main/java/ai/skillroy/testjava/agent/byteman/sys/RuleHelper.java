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

package ai.skillroy.testjava.agent.byteman.sys;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The curated helper of the module's rules (phase6-contracts.md 7.3): the module adds
 * {@code HELPER ai.skillroy.testjava.agent.byteman.sys.RuleHelper} to every rule it submits. Byteman loads it through
 * the trigger class's loader (so it lives on the system class path) and builds a subclass of it per rule whose
 * constructor calls this class's {@code (Rule)} constructor, once per firing (spike BS14 §4).
 *
 * <p>Observe-only: {@code fired} with one to eight bindings (Byteman does not match a varargs method, BS14 condition
 * 2, so the overloads are fixed-arity), {@code count}, {@code countOf}, {@code flag}, {@code flagged}, {@code clear}
 * (per rule, bounded) and {@code format} (a bounded text of a scalar, never {@code toString()} of an application
 * object). None of Byteman's {@code Helper} built-ins is inherited: this class does not extend
 * {@code org.jboss.byteman.rule.helper.Helper}, so a rule has no {@code killJVM}, no trace file, no wait.
 *
 * <p>{@code fired} hands the raw objects to the static slot {@link Handler}, set-once, held weakly, released by
 * identity (the shape of {@code Spy}'s slot): the module installs its handler there when it loads Byteman, and the
 * handler encodes and publishes {@code rule.fired}. Nothing here throws into the host: a failure is counted.
 *
 * <p>The rule is kept as {@code Object} and read by reflection, and the one method with a Byteman-typed parameter, the
 * constructor, is branch-free, so that the class's constant pool holds no {@code CONSTANT_Class} outside {@code java/}
 * and {@code ai/skillroy/} (BS14 RESULT condition 4).
 */
public class RuleHelper {

    /** The module's side: receives the rule's name, its trigger class and method, and the raw bindings. */
    public interface Handler {
        void fired(String rule, String className, String method, Object[] bindings);
    }

    /** The most per-rule counter and flag keys kept in this JVM; beyond it count and flag do nothing. */
    static final int MAX_KEYS = 1024;
    /** The longest text {@link #format} returns. */
    static final int MAX_FORMAT = 256;

    private static final Object SLOT_LOCK = new Object();
    private static volatile WeakReference<Handler> slot;
    private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<String, AtomicInteger>();
    private static final Map<String, Boolean> FLAGS = new ConcurrentHashMap<String, Boolean>();
    private static final AtomicLong FIRED = new AtomicLong();
    private static final AtomicLong UNHANDLED = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static volatile Method getName;
    private static volatile Method getTriggerClass;
    private static volatile Method getTriggerMethod;

    /** Not final: assigned once by the constructor; a test sets it by reflection without a final-field mutation. */
    private Object rule;

    /** The constructor Byteman's helper adapter calls, once per firing; the rule is kept as {@code Object}. */
    public RuleHelper(org.jboss.byteman.rule.Rule rule) {
        this.rule = rule;
    }

    // ---------------------------------------------------------------------------------------------------- the slot

    /** Installs the handler if the slot is free; false while another live handler holds it. */
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

    /** Clears the slot if, and only if, {@code handler} is the installed one (identity); false otherwise. */
    public static boolean uninstall(Handler handler) {
        synchronized (SLOT_LOCK) {
            WeakReference<Handler> current = slot;
            if (handler == null || current == null || current.get() != handler) {
                return false;
            }
            slot = null;
            return true;
        }
    }

    /** True while a handler holds the slot. The handler itself is never handed out. */
    public static boolean held() {
        WeakReference<Handler> current = slot;
        return current != null && current.get() != null;
    }

    /** {fired, unhandled (no handler), failures} since the class was loaded. */
    public static long[] counters() {
        return new long[] {FIRED.get(), UNHANDLED.get(), FAILURES.get()};
    }

    /** Drops the counters and flags of one rule (the module calls it by reflection when it unloads the rule). */
    static void forget(String ruleName) {
        String prefix = ruleName + '\u0000';
        for (String key : COUNTS.keySet()) {
            if (key.startsWith(prefix)) {
                COUNTS.remove(key);
            }
        }
        for (String key : FLAGS.keySet()) {
            if (key.startsWith(prefix)) {
                FLAGS.remove(key);
            }
        }
    }

    // ------------------------------------------------------------------------------------ what a rule may call

    public void fired(Object a) {
        publish(new Object[] {a});
    }

    public void fired(Object a, Object b) {
        publish(new Object[] {a, b});
    }

    public void fired(Object a, Object b, Object c) {
        publish(new Object[] {a, b, c});
    }

    public void fired(Object a, Object b, Object c, Object d) {
        publish(new Object[] {a, b, c, d});
    }

    public void fired(Object a, Object b, Object c, Object d, Object e) {
        publish(new Object[] {a, b, c, d, e});
    }

    public void fired(Object a, Object b, Object c, Object d, Object e, Object f) {
        publish(new Object[] {a, b, c, d, e, f});
    }

    public void fired(Object a, Object b, Object c, Object d, Object e, Object f, Object g) {
        publish(new Object[] {a, b, c, d, e, f, g});
    }

    public void fired(Object a, Object b, Object c, Object d, Object e, Object f, Object g, Object h) {
        publish(new Object[] {a, b, c, d, e, f, g, h});
    }

    /** Adds one to this rule's counter {@code key}; returns the new value (0 when the key bound is reached). */
    public int count(String key) {
        try {
            String k = key(key);
            AtomicInteger counter = COUNTS.get(k);
            if (counter == null) {
                if (COUNTS.size() >= MAX_KEYS) {
                    return 0;
                }
                AtomicInteger fresh = new AtomicInteger();
                AtomicInteger raced = ((ConcurrentHashMap<String, AtomicInteger>) COUNTS).putIfAbsent(k, fresh);
                counter = raced == null ? fresh : raced;
            }
            return counter.incrementAndGet();
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            return 0;
        }
    }

    /** This rule's counter {@code key} (0 when never counted). */
    public int countOf(String key) {
        try {
            AtomicInteger counter = COUNTS.get(key(key));
            return counter == null ? 0 : counter.get();
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            return 0;
        }
    }

    /** Sets this rule's flag {@code key}; true when it was not set before. */
    public boolean flag(String key) {
        try {
            if (FLAGS.size() >= MAX_KEYS) {
                return false;
            }
            return FLAGS.put(key(key), Boolean.TRUE) == null;
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            return false;
        }
    }

    /** True while this rule's flag {@code key} is set. */
    public boolean flagged(String key) {
        try {
            return FLAGS.containsKey(key(key));
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            return false;
        }
    }

    /** Clears this rule's flag {@code key}; true when it was set. */
    public boolean clear(String key) {
        try {
            return FLAGS.remove(key(key)) != null;
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            return false;
        }
    }

    /**
     * A bounded text of a scalar: a string (at most {@value #MAX_FORMAT} characters), a boolean, a character, a number
     * of the JDK, an enum constant's name, {@code "null"}; anything else is {@code <class name>}, so that no
     * application code runs.
     */
    public String format(Object value) {
        try {
            String text;
            if (value == null) {
                text = "null";
            } else if (value instanceof String) {
                text = (String) value;
            } else if (value instanceof Enum) {
                text = ((Enum<?>) value).name();
            } else if ((value instanceof Number || value instanceof Boolean || value instanceof Character)
                    && value.getClass().getClassLoader() == null) {
                text = String.valueOf(value);
            } else {
                text = "<" + value.getClass().getName() + ">";
            }
            return text.length() > MAX_FORMAT ? text.substring(0, MAX_FORMAT) : text;
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            return "<unformattable>";
        }
    }

    // ----------------------------------------------------------------------------------------------- internals

    private String key(String key) {
        return read(0) + '\u0000' + key;
    }

    private void publish(Object[] bindings) {
        FIRED.incrementAndGet();
        try {
            WeakReference<Handler> current = slot;
            Handler handler = current == null ? null : current.get();
            if (handler == null) {
                UNHANDLED.incrementAndGet();
                return;
            }
            handler.fired(read(0), read(1), read(2), bindings);
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
        }
    }

    /** The rule's name (0), trigger class (1) or trigger method (2), read by reflection; null when unreadable. */
    String read(int which) {
        Object r = rule;
        if (r == null) {
            return null;
        }
        try {
            Method m = getter(r.getClass(), which);
            Object value = m.invoke(r);
            return value == null ? null : value.toString();
        } catch (Throwable t) {
            FAILURES.incrementAndGet();
            return null;
        }
    }

    private static Method getter(Class<?> ruleClass, int which) throws NoSuchMethodException {
        Method m = which == 0 ? getName : which == 1 ? getTriggerClass : getTriggerMethod;
        if (m == null || m.getDeclaringClass() != ruleClass) {
            m = ruleClass.getMethod(which == 0 ? "getName" : which == 1 ? "getTriggerClass" : "getTriggerMethod");
            if (which == 0) {
                getName = m;
            } else if (which == 1) {
                getTriggerClass = m;
            } else {
                getTriggerMethod = m;
            }
        }
        return m;
    }
}
