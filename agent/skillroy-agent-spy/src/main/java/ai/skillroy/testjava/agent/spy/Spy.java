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

package ai.skillroy.testjava.agent.spy;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * The only class the hooks put in front of the host (phase6-contracts.md section 3.1): appended to the bootstrap
 * search path by the hooks module at its first {@code hook.install}, called by the two lines of advice inlined into
 * every hooked method, and pointing at one {@link Handler} that the hook engine installs from its isolated loader.
 * It references {@code java.*} only, so that it links in any loader that delegates to the bootstrap loader.
 *
 * <p><b>The slot (D117).</b> One volatile weak reference to the handler: set once (another live handler holding it
 * makes {@link #install} return false), released only by the handler that holds it ({@link #uninstall}, by identity),
 * and held weakly as a last resort, so that a handler whose loader vanished without an uninstall frees the slot once
 * collected. The hook engine keeps the invariant that the slot is held while any class carries an advice; the
 * handler object itself is never handed out.
 *
 * <p><b>Never throws, never blocks.</b> With no handler every call is a no-op (counted as unhandled); a handler that
 * throws (a {@code StackOverflowError} or {@code OutOfMemoryError} included) is counted and ignored. Re-entrancy is
 * the handler's business: this class keeps no thread-local. {@link #enter} and {@link #exit} are callable by every
 * class in the JVM; the threat model records that in-JVM code can forge a hook event.
 *
 * <p>Thread-safe.
 */
public final class Spy {

    /**
     * Bumped when the shape of this class changes. A bootstrap append is permanent: a JVM keeps the first Spy it was
     * given, and a hooks module whose Spy has another VERSION reports {@code hook.engine} UNAVAILABLE.
     */
    public static final int VERSION = 1;

    /** The hook engine's side; implementations live in the hooks module's isolated loader. */
    public interface Handler {
        /**
         * @return null to run the original body, or a {@link Stub} (from {@link Spy#stub}) to skip it and return the
         *     stub's value
         */
        Object enter(String hookId, Object self, Object[] args);

        /** After the original body ran (never after a skip), with its return value or the throwable it threw. */
        void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown);
    }

    /** A handler-supplied return value, carried from the enter advice to the exit advice in a local variable. */
    public static final class Stub {
        private final Object value;

        // Package-private, not private: a private constructor called from Spy would make javac (release 8) add a
        // synthetic accessor class, and the spy jar holds Spy and its two nested types only.
        Stub(Object value) {
            this.value = value;
        }

        /** The value the skipped method returns (null is a valid value). */
        public Object value() {
            return value;
        }
    }

    private static final Object LOCK = new Object();
    private static volatile WeakReference<Handler> slot;

    private static final LongAdder ENTERS = new LongAdder();
    private static final LongAdder UNHANDLED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder SKIPS = new LongAdder();

    private Spy() {
    }

    /** Wraps the value a skipped method returns; for handlers. */
    public static Stub stub(Object value) {
        return new Stub(value);
    }

    /**
     * Installs the handler if the slot is free (D117).
     *
     * @return true when {@code handler} now holds the slot (also when it already did); false while another live
     *     handler holds it, or for null
     */
    public static boolean install(Handler handler) {
        if (handler == null) {
            return false;
        }
        synchronized (LOCK) {
            Handler current = current();
            if (current == handler) {
                return true;
            }
            if (current != null) {
                return false;
            }
            slot = new WeakReference<Handler>(handler);
            return true;
        }
    }

    /** Clears the slot if, and only if, {@code handler} is the installed one (identity); false otherwise. */
    public static boolean uninstall(Handler handler) {
        if (handler == null) {
            return false;
        }
        synchronized (LOCK) {
            if (current() != handler) {
                return false;
            }
            slot = null;
            return true;
        }
    }

    /** True while a handler holds the slot. The handler object itself is never handed out. */
    public static boolean held() {
        return current() != null;
    }

    private static Handler current() {
        WeakReference<Handler> ref = slot;
        return ref == null ? null : ref.get();
    }

    /** Called by the enter advice: null, or the Stub the handler returned. Counted; never throws. */
    public static Object enter(String hookId, Object self, Object[] args) {
        try {
            ENTERS.increment();
            WeakReference<Handler> ref = slot;
            Handler handler = ref == null ? null : ref.get();
            if (handler == null) {
                UNHANDLED.increment();
                return null;
            }
            Object result;
            try {
                result = handler.enter(hookId, self, args);
            } catch (Throwable t) {
                FAILURES.increment();
                return null;
            }
            if (result instanceof Stub) {
                SKIPS.increment();
                return result;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called by the exit advice. Counted; never throws. */
    public static void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown) {
        try {
            WeakReference<Handler> ref = slot;
            Handler handler = ref == null ? null : ref.get();
            if (handler == null) {
                UNHANDLED.increment();
                return;
            }
            try {
                handler.exit(hookId, self, args, returned, thrown);
            } catch (Throwable t) {
                FAILURES.increment();
            }
        } catch (Throwable t) {
            // Never into the host.
        }
    }

    /** {enters, unhandled, failures, skips} since the class was loaded. */
    public static long[] counters() {
        return new long[] {ENTERS.sum(), UNHANDLED.sum(), FAILURES.sum(), SKIPS.sum()};
    }
}
