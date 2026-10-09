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

package ai.skillroy.testjava.spikes.bs3.spy;

import java.util.concurrent.atomic.LongAdder;

/**
 * The only class the hooks put in front of the host: appended to the bootstrap search path, called by the two lines
 * of inlined Advice, and pointing at one replaceable {@link Handler} that the hook engine installs from its isolated
 * loader. It references nothing but {@code java.*}, so that it links in any loader that delegates to the bootstrap.
 *
 * <p>Never throws, never blocks: with no handler every call is a no-op (counted), a handler that throws is counted
 * and ignored. The spike's prototype of {@code skillroy-agent-spy}.
 */
public final class Spy {

    /** Bumped when the shape of this class changes; a bootstrap append is permanent, so a JVM keeps the first one. */
    public static final String VERSION = "bs3-spy-1";

    /** The hook engine's side. Implementations live in the agent's isolated loader. */
    public interface Handler {
        /**
         * @return null to run the original body; a {@link Stub} (from {@link Spy#stub}) to skip it and return the
         *     stub's value instead
         */
        Object enter(String hookId, Object self, Object[] args);

        /** Called after the original body ran (not after a skip), with its return value or throwable. */
        void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown);
    }

    /** A handler-supplied return value; carried from the enter advice to the exit advice in a local variable. */
    public static final class Stub {
        final Object value;

        Stub(Object value) {
            this.value = value;
        }
    }

    /** The current handler; null means every hook is a no-op. Replaced without any retransformation. */
    public static volatile Handler handler;

    private static final LongAdder ENTERS = new LongAdder();
    private static final LongAdder UNHANDLED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder SKIPS = new LongAdder();

    private Spy() {
    }

    /** Called by the enter advice. */
    public static Object enter(String hookId, Object self, Object[] args) {
        ENTERS.increment();
        Handler h = handler;
        if (h == null) {
            UNHANDLED.increment();
            return null;
        }
        try {
            Object result = h.enter(hookId, self, args);
            return result instanceof Stub ? result : null;
        } catch (Throwable t) {
            FAILURES.increment();
            return null;
        }
    }

    /** Called by the exit advice after the original body. */
    public static void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown) {
        Handler h = handler;
        if (h == null) {
            UNHANDLED.increment();
            return;
        }
        try {
            h.exit(hookId, self, args, returned, thrown);
        } catch (Throwable t) {
            FAILURES.increment();
        }
    }

    /** Called by the exit advice after a skip: the value the method returns. */
    public static Object stubbed(Object stub) {
        SKIPS.increment();
        return stub instanceof Stub ? ((Stub) stub).value : null;
    }

    /** For handlers: wraps the value a skipped method returns (null is a valid value). */
    public static Object stub(Object value) {
        return new Stub(value);
    }

    /** @return {enters, calls that found no handler, handler failures, skips} since the class was loaded */
    public static long[] counters() {
        return new long[] {ENTERS.sum(), UNHANDLED.sum(), FAILURES.sum(), SKIPS.sum()};
    }
}
