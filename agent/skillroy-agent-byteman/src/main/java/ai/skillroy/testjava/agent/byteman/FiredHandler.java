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

package ai.skillroy.testjava.agent.byteman;

import ai.skillroy.testjava.agent.byteman.sys.RuleHelper;

import java.lang.reflect.Method;

/**
 * The module's handler behind {@code RuleHelper}'s slot (phase6-contracts.md 7.3, 7.4 step 3). It names a sys-jar type,
 * so it is loaded <b>by name, only after</b> the sys jar is on the system class path (a class linked against a type
 * before the append keeps failing with {@code NoClassDefFoundError} after it, BS3 RESULT section 9 fact 9); nothing
 * else in the module names a sys type in a signature or a field. Its static methods are called by reflection.
 */
public final class FiredHandler implements RuleHelper.Handler {

    private final FiredSink sink;

    private FiredHandler(FiredSink sink) {
        this.sink = sink;
    }

    /** A new handler installed in {@code RuleHelper}'s slot; null when another live handler holds it. */
    public static Object arm(FiredSink sink) {
        FiredHandler handler = new FiredHandler(sink);
        return RuleHelper.install(handler) ? handler : null;
    }

    /** Releases the slot if {@code handler} holds it. */
    public static boolean release(Object handler) {
        return handler instanceof FiredHandler && RuleHelper.uninstall((FiredHandler) handler);
    }

    /** True while a handler (this module's or an earlier instance's) holds the slot. */
    public static boolean held() {
        return RuleHelper.held();
    }

    /** Drops a rule's counters and flags in the helper (package-private there, so by reflection). */
    public static void forget(String storedName) {
        try {
            Method forget = RuleHelper.class.getDeclaredMethod("forget", String.class);
            forget.setAccessible(true);
            forget.invoke(null, storedName);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A rule's counters left behind are bounded and harmless.
        }
    }

    @Override
    public void fired(String rule, String className, String method, Object[] bindings) {
        sink.fired(rule, className, method, bindings);
    }
}
