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

package ai.skillroy.testjava.agent.web;

/**
 * Answers "is AWT running in this JVM" from thread names alone, so that the question itself never starts AWT (copied
 * from the Swing module: module jars cannot see each other's classes). The toolkit names its event-dispatch threads
 * {@code AWT-EventQueue-N}; none exists before the application first uses AWT, and once one exists the toolkit is
 * initialised and the headless decision taken, so the module may call into AWT without changing how the application
 * starts (P1 amendment A4). Every EDT read of this module asks this first: the thread broker's EDT policies would
 * otherwise initialise AWT in a target that has no user interface.
 *
 * <p>Stateless; safe from any thread. Takes no AWT lock and loads no AWT class.
 */
final class AwtThreads {

    /** Name prefix of an event-dispatch thread. */
    static final String EVENT_QUEUE = "AWT-EventQueue";

    private AwtThreads() {
    }

    /** True if a live event-dispatch thread exists. */
    static boolean eventQueueAlive() {
        try {
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root.getParent() != null) {
                root = root.getParent();
            }
            Thread[] threads = new Thread[root.activeCount() * 2 + 16];
            int n = root.enumerate(threads, true);
            for (int i = 0; i < n; i++) {
                Thread t = threads[i];
                if (t != null && t.isAlive() && t.getName().startsWith(EVENT_QUEUE)) {
                    return true;
                }
            }
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }
}
