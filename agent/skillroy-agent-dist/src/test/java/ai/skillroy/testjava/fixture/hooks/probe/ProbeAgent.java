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


package ai.skillroy.testjava.fixture.hooks.probe;

import java.lang.instrument.Instrumentation;

/** The test's own probe agent (BS3's probe): {@code -javaagent:} on the child, it keeps the Instrumentation. */
public final class ProbeAgent {

    private static volatile Instrumentation instrumentation;

    private ProbeAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    public static Instrumentation instrumentation() {
        return instrumentation;
    }
}
