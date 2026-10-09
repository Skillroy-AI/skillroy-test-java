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


package ai.skillroy.testjava.agent.hooks;

import java.lang.instrument.Instrumentation;

/**
 * The test-only premain (phase6-contracts.md section 2.3): the module's test JVMs start with
 * {@code -javaagent:<the module's test-agent jar>}, and this keeps the {@code Instrumentation} for the tests.
 */
public final class TestAgent {

    private static volatile Instrumentation instrumentation;

    private TestAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    /** The JVM's Instrumentation; fails when the test JVM was started without the test agent. */
    public static Instrumentation instrumentation() {
        if (instrumentation == null) {
            throw new IllegalStateException("the test JVM was started without the test agent (-javaagent)");
        }
        return instrumentation;
    }
}
