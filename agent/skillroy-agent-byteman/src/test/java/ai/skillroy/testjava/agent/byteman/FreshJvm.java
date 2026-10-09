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

import java.lang.instrument.Instrumentation;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The precondition of the three test classes that need a JVM of their own ({@code ProbeTest},
 * {@code CheckLoadsNothingTest}, {@code InProcessRouteTest}): the module's surefire runs each in an execution of its
 * own, but a {@code -Dtest} on the command line collapses those executions into one JVM, where an earlier test may
 * have loaded Byteman (which cannot be unloaded) or the sys jar. Such a class is then skipped with this message
 * rather than failing for the wrong reason.
 */
final class FreshJvm {

    private FreshJvm() {
    }

    /**
     * Skips the calling test unless the JVM is fresh enough: with {@code strict}, nothing of Byteman and nothing of the
     * sys jar is loaded; without, Byteman's {@code Main} is not loaded (Byteman was never started in this JVM: a
     * reflective look at {@code RuleManager} loads its parameter type {@code Retransformer}, nothing more).
     */
    static void assume(boolean strict) {
        Instrumentation inst = TestAgent.instrumentation();
        if (inst == null) {
            return;
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            boolean taken = strict ? n.startsWith("org.jboss.byteman.")
                    || n.startsWith("ai.skillroy.testjava.agent.byteman.sys.") : n.equals("org.jboss.byteman.agent.Main");
            assumeFalse(taken, "needs a fresh JVM (run without -Dtest: the module's surefire executions give it one): "
                    + n + " is already loaded");
        }
    }
}
