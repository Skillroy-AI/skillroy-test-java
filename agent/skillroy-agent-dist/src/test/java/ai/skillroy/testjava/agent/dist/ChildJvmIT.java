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


package ai.skillroy.testjava.agent.dist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The pid line of a child JVM run with {@code -verbose:class}: on a Java 8 child a class-loading line can land between
 * the digits and their newline (the first Linux matrix round of Phase 6, the Java 8 test-JVM leg:
 * {@code OptionalModulesIT} failed with {@code NumberFormatException} on {@code "3403[Loaded ai.skillroy…"}). A
 * failsafe class although it launches nothing: the module's {@code pom} packaging binds no surefire.
 */
class ChildJvmIT {

    @Test
    void thePidIsTheLeadingDigitsWhateverTheJvmWroteBehindThem() {
        assertEquals(3403L, ChildJvm.pidOf("3403"));
        assertEquals(3403L, ChildJvm.pidOf("3403[Loaded ai.skillroy.testjava.agent.core.AgentRuntime from "
                + "file:/work/home/skillroy-agent-core.jar]"));
        assertEquals(41L, ChildJvm.pidOf("41[0.212s][info][class,load] java.lang.Thread source: jrt:/java.base"));
        assertThrows(NumberFormatException.class, () -> ChildJvm.pidOf("[Loaded x from y]"));
        assertThrows(NumberFormatException.class, () -> ChildJvm.pidOf(""));
    }
}
