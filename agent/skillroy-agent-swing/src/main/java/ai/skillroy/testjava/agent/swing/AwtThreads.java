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

package ai.skillroy.testjava.agent.swing;

/**
 * Answers "is AWT running in this JVM" from thread names alone, so that the question itself never starts AWT. The
 * toolkit names its event-dispatch threads {@code AWT-EventQueue-N} (and its other threads {@code AWT-XAWT},
 * {@code AWT-Windows}, {@code AWT-AppKit}, {@code AWT-Shutdown}); none of them exists before the application first
 * uses AWT. An event-dispatch thread exists only once the toolkit has been initialised, which also fixes the headless
 * decision, so from then on the module may call into AWT without changing how the application starts.
 *
 * <p>Stateless; safe from any thread. Takes no AWT lock and loads no AWT class.
 */
final class AwtThreads {

    /** Name prefix of every thread the AWT toolkit starts. */
    static final String ANY = "AWT-";
    /** Name prefix of an event-dispatch thread. */
    static final String EVENT_QUEUE = "AWT-EventQueue";

    private AwtThreads() {
    }

    /** True if a live event-dispatch thread exists. */
    static boolean eventQueueAlive() {
        return alive(EVENT_QUEUE);
    }

    /** True if a live thread whose name starts with {@code prefix} exists; false if threads cannot be listed. */
    static boolean alive(String prefix) {
        try {
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) {
                root = root.getParent();
            }
            Thread[] threads = new Thread[root.activeCount() * 2 + 16];
            int n = root.enumerate(threads, true);
            for (int i = 0; i < n; i++) {
                Thread t = threads[i];
                if (t != null && t.isAlive() && t.getName().startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }
}
