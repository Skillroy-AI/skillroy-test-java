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

/**
 * The module's test agent (phase6-contracts.md 2.3): {@code target/<module>-test-agent.jar} with this class as
 * {@code Premain-Class}, on the surefire command line as {@code -javaagent}, so that the tests that need an
 * {@code Instrumentation} in process get one (launch-time loading, JEP 451-exempt, also on the Java 8 leg).
 */
public final class TestAgent {

    private static volatile Instrumentation instrumentation;

    private TestAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    /** The JVM's Instrumentation, or null when the test JVM was started without the test agent. */
    public static Instrumentation instrumentation() {
        return instrumentation;
    }
}
