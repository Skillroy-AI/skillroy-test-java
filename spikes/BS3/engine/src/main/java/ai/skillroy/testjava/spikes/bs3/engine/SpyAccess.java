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

import ai.skillroy.testjava.spikes.bs3.spy.Spy;

/**
 * Everything the engine does with {@link Spy} itself; loaded only after the append ({@link SpyBoot}).
 *
 * <p>{@link #handler(Object)} takes {@code Object} on purpose: a caller that passed a {@code HookRegistry} where a
 * {@code Spy.Handler} is expected would make the verifier load {@code Spy.Handler} when that caller is linked, which
 * for the class that performs the append is before the append (the first smoke run failed exactly so).
 */
final class SpyAccess {

    private SpyAccess() {
    }

    static void handler(Object handler) {
        Spy.handler = (Spy.Handler) handler;
    }

    static Object handler() {
        return Spy.handler;
    }

    static long[] counters() {
        return Spy.counters();
    }

    /** Read reflectively: the constant is inlined at compile time, the loaded class may be an older copy. */
    static String version() {
        try {
            return String.valueOf(Spy.class.getField("VERSION").get(null));
        } catch (Exception e) {
            return "unreadable: " + e;
        }
    }

    static Class<?> spyClass() {
        return Spy.class;
    }
}
