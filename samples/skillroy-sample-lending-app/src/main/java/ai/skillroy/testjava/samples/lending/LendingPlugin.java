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

package ai.skillroy.testjava.samples.lending;

/**
 * The desk's extension point. Plugins are named in the system property {@code lending.plugins} (comma-separated
 * class names, each with a public no-argument constructor), instantiated with the application's class loader and
 * started once the desk is up: the database is seeded, the engine runs and the window (if any) is showing.
 *
 * <p>{@link #start} runs on the thread that launched the application (the main thread); it should return promptly
 * and do long work on its own threads. A plugin that throws is logged and skipped; the desk keeps running.
 * {@link #stop} is called once, in reverse start order, when the application shuts down (also on JVM exit).
 */
public interface LendingPlugin {

    /** Starts the plugin. {@code app} gives access to the engine, services, database and window. */
    void start(LendingApp app);

    /** Stops the plugin and releases whatever it added to the application. */
    default void stop() {
    }
}
