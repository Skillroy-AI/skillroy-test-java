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

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard of the dedicated test JVMs (fix wave, L3-S2): when Byteman is already loaded, {@link FreshJvm#assume}
 * skips only in a run that a command-line {@code -Dtest} merged into one JVM (surefire hands {@code test} to the
 * fork), and fails otherwise, so that a dedicated execution that is not fresh can never pass as a skip. Runs in the
 * default execution, whose JVM needs nothing of Byteman: it loads Byteman's {@code Main} (not initialised) to play the
 * non-fresh JVM.
 */
class FreshJvmTest {

    @Test
    void aLoadedBytemanFailsADedicatedRunAndSkipsAMergedOne() throws Exception {
        assertNotNull(TestAgent.instrumentation(), "the test agent's Instrumentation");
        Class.forName(Engine.MAIN, false, ClassLoader.getSystemClassLoader());
        String test = System.getProperty("test");
        try {
            System.clearProperty("test");
            AssertionFailedError failed = assertThrows(AssertionFailedError.class, () -> FreshJvm.assume(false));
            assertTrue(failed.getMessage().contains(Engine.MAIN + " is already loaded"), failed.getMessage());
            System.setProperty("test", "InProcessRouteTest");
            TestAbortedException skipped = assertThrows(TestAbortedException.class, () -> FreshJvm.assume(false));
            assertTrue(skipped.getMessage().contains("run without -Dtest"), skipped.getMessage());
        } finally {
            if (test == null) {
                System.clearProperty("test");
            } else {
                System.setProperty("test", test);
            }
        }
    }
}
