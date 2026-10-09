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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The class Byteman's {@code manager:} option names (BS14 item 1). Byteman's {@code Main.premain} loads it through the
 * system class loader and calls {@code initialize} with the {@code Retransformer} it built. The one public method is
 * {@code initialize}; the {@code Retransformer} is kept as {@code Object} in a private static and leaves only through
 * the package-private {@link #take()}, which the probe reaches by reflection. Nothing public hands it out.
 */
public final class RuleManager {

    private static final Object LOCK = new Object();
    private static Object retransformer;
    // Evidence for the spike only: how and where initialize was called.
    private static final AtomicInteger INITIALIZE_CALLS = new AtomicInteger();
    private static volatile String initializeEvidence = "";

    private RuleManager() {
    }

    /**
     * Byteman looks this method up by name and the parameter types {@code (Retransformer, String, Integer)} first and
     * {@code (Retransformer)} second; the receiver is null (static). The body is branch-free on purpose: a method with
     * a branch gets a StackMapTable whose frames name every local's type, the Byteman-typed parameter included, as a
     * CONSTANT_Class (the spike's first build showed it); everything else happens in {@link #accept}.
     */
    public static void initialize(org.jboss.byteman.agent.Retransformer r, String hostname, Integer port) {
        accept(r, hostname, port);
    }

    private static void accept(Object r, String hostname, Integer port) {
        INITIALIZE_CALLS.incrementAndGet();
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder caller = new StringBuilder();
        for (int i = 2; i < stack.length && i < 7; i++) {
            caller.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName()).append(' ');
        }
        initializeEvidence = "thread=" + Thread.currentThread().getName() + ";hostname=" + hostname + ";port=" + port
                + ";argClass=" + (r == null ? "null" : r.getClass().getName())
                + ";argLoader=" + (r == null ? "null" : String.valueOf(r.getClass().getClassLoader()))
                + ";callers=" + caller.toString().trim();
        synchronized (LOCK) {
            retransformer = r;
        }
    }

    /** Returns the Retransformer once and clears the field. Package-private: reached by reflection only. */
    static Object take() {
        synchronized (LOCK) {
            Object r = retransformer;
            retransformer = null;
            return r;
        }
    }

    /** Gives a Retransformer back for a later module instance of this JVM. Package-private. */
    static void park(Object r) {
        synchronized (LOCK) {
            retransformer = r;
        }
    }

    /** Spike evidence: {calls, evidence}. Package-private. */
    static String evidence() {
        return INITIALIZE_CALLS.get() + "|" + initializeEvidence;
    }
}
