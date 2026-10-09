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

package ai.skillroy.testjava.fixture.byteman;

import java.lang.instrument.Instrumentation;

/**
 * A second {@code -javaagent} of {@code BytemanIT}'s toy: keeps the JVM's {@code Instrumentation}, so that the toy can
 * play a re-attach after {@code agent.disable} by calling the bootstrap's {@code agentmain} itself (what the attach
 * mechanism calls), without the attach API in a Java 8 test.
 */
public final class InstrumentationKeeper {

    private static volatile Instrumentation instrumentation;

    private InstrumentationKeeper() {
    }

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    static Instrumentation instrumentation() {
        return instrumentation;
    }
}
