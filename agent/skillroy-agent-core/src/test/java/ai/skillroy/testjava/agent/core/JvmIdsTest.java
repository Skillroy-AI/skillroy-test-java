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

package ai.skillroy.testjava.agent.core;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Findings S20/S29: every pid source agrees with {@code RuntimeMXBean.getName()} without calling it. */
class JvmIdsTest {

    private static long fromName() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return Long.parseLong(name.substring(0, name.indexOf('@')));
    }

    private static boolean java8() {
        return "1.8".equals(System.getProperty("java.specification.version"));
    }

    @Test
    void thePidWithoutTheNameLookupIsThePidOfTheName() {
        assertEquals(fromName(), JvmIds.pidWithoutNameLookup());
        assertEquals(fromName(), JvmIds.pid());
        assertEquals(fromName(), JvmIds.fromRuntimeName());
    }

    @Test
    void java9AndLaterUseProcessHandle() {
        Assumptions.assumeFalse(java8());
        assertEquals(fromName(), JvmIds.fromProcessHandle());
        assertEquals(-1L, JvmIds.fromVmManagement(), "the Java 8 internals are not reachable (or not tried) here");
    }

    @Test
    void java8UsesTheManagementObjectBehindTheRuntimeBean() {
        Assumptions.assumeTrue(java8(), "branch 2 of the fix exists on Java 8 only");
        assertEquals(-1L, JvmIds.fromProcessHandle());
        assertEquals(fromName(), JvmIds.fromVmManagement());
    }

    @Test
    void linuxAlsoHasProcSelf() {
        Assumptions.assumeTrue(new File("/proc/self").exists(), "no /proc on this host");
        assertEquals(fromName(), JvmIds.fromProcSelf());
    }

    @Test
    void theStartTimeIsPositive() {
        assertTrue(JvmIds.startTimeMs() > 0);
    }
}
