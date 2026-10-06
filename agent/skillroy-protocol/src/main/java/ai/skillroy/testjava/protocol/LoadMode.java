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

package ai.skillroy.testjava.protocol;

/**
 * How the agent got into the target JVM. Reported by {@code /ping}, {@code agent.info} and the discovery file because
 * some capabilities (anything needing {@code Instrumentation}) depend on it.
 */
public enum LoadMode {
    /** Started by the target application itself through {@code EmbeddedAgent.start}; usually no Instrumentation. */
    EMBEDDED,
    /** Started from {@code -javaagent} (directly or through {@code JAVA_TOOL_OPTIONS}) before {@code main}. */
    JAVAAGENT,
    /** Loaded into a running JVM through the attach API. */
    ATTACH
}
