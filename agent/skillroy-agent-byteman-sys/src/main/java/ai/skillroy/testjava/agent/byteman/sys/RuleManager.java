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

/**
 * The class Byteman's {@code manager:} option names (phase6-contracts.md 7.3, D132; spike BS14). Byteman's
 * {@code Main.premain} loads it through the system class loader and calls {@link #initialize} once with the
 * {@code Retransformer} it built, on the thread that called {@code premain} (the module's agent worker), with
 * {@code hostname} and {@code port} null since no listener exists.
 *
 * <p>The {@code Retransformer} is kept as {@code Object} in a private static and leaves only through the
 * package-private {@link #take()}, which the module reaches by reflection right after {@code premain} returns, on the
 * same thread. {@link #park(Object)} gives it back when the module's {@code uninstall} has removed every rule, so that
 * a later agent instance in the same JVM reuses it (Byteman cannot be loaded twice); when rules are left behind the
 * module parks the list of their names instead, which tells the next instance why it must refuse. <b>The system class
 * path offers no public accessor at any time</b>: a public one would hand any class in the JVM a {@code Retransformer}
 * that takes any rule text with Byteman's default helper, past the lint, {@code confirm} and the channel; code that
 * reaches the package-private statics by reflection is in-JVM code reflecting on internals, out of scope
 * ({@code threat-model.md} A12).
 *
 * <p>{@link #initialize} is the one public method and accepts the first {@code Retransformer} of the JVM only: a later
 * call (any class may call a public method) is ignored, so no class can replace or clear what Byteman handed over.
 */
public final class RuleManager {

    private static final Object LOCK = new Object();
    /** The Retransformer, the parked list of leftover rule names, or null. */
    private static Object slot;
    /** Set by the first {@link #initialize}; never cleared. */
    private static boolean initialized;

    private RuleManager() {
    }

    /**
     * Called by Byteman's {@code Main.premain}, which looks it up by name with the parameter types
     * {@code (Retransformer, String, Integer)}. The body is branch-free on purpose: a method with a branch gets a
     * {@code StackMapTable} whose frames name the Byteman-typed parameter as a {@code CONSTANT_Class} (BS14 RESULT
     * condition 4); everything else happens in {@link #accept}.
     */
    public static void initialize(org.jboss.byteman.agent.Retransformer retransformer, String hostname, Integer port) {
        accept(retransformer);
    }

    private static void accept(Object retransformer) {
        synchronized (LOCK) {
            if (initialized || retransformer == null) {
                return;
            }
            initialized = true;
            slot = retransformer;
        }
    }

    /** Returns what the slot holds (the Retransformer, or a parked list of names) once, and clears it. */
    static Object take() {
        synchronized (LOCK) {
            Object value = slot;
            slot = null;
            return value;
        }
    }

    /** Puts the Retransformer (no rule of the module left) or the list of leftover rule names back. */
    static void park(Object value) {
        synchronized (LOCK) {
            slot = value;
        }
    }
}
