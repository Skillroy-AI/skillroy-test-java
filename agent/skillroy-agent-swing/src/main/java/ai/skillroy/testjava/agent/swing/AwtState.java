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
 * What the module has learned about AWT in this JVM: whether the application has started it (an event-dispatch thread
 * has been seen, so the toolkit is initialised and the headless decision is taken) and, from then on, whether it is
 * headless. Until {@link #started()} is true nothing in the module may call into AWT, because the first call could
 * initialise the toolkit or freeze the headless decision on the application's behalf.
 *
 * <p>Thread-safe: written once by the module, read by the primitives.
 */
final class AwtState {

    private volatile boolean headless;
    private volatile boolean started;

    /** True once an event-dispatch thread has been seen. */
    boolean started() {
        return started;
    }

    /** The toolkit's headless decision; meaningful only once {@link #started()}. */
    boolean headless() {
        return headless;
    }

    /** Records that AWT runs, with the toolkit's headless decision. */
    void markStarted(boolean headlessNow) {
        headless = headlessNow;
        started = true;
    }
}
