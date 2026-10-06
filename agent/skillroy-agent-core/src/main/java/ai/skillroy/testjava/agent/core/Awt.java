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

package ai.skillroy.testjava.agent.core;

import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Window;

/**
 * The core's only door to AWT (contract amendment A4). In JAVAAGENT mode the agent starts before the application's
 * {@code main}; initialising the toolkit there would freeze the application's headless decision or start the
 * toolkit on the wrong thread. Every AWT reference of the core therefore lives here (a unit test checks that no other
 * core class refers to {@code java.awt}), and no start-up path or core primitive calls into it.
 *
 * <p>The checks that merely ask "is AWT in use?" (the modal-dialog detail of a TIMEOUT, "is this the EDT?" when the
 * agent closes) never initialise it: they look at thread names first, and a live event-dispatch thread (a thread
 * whose name starts with {@code AWT-EventQueue}) exists only once the application itself has started AWT. Only the
 * EDT thread policies initialise AWT, and only when a primitive that declared them is called.
 *
 * <p>Stateless; safe from any thread.
 */
final class Awt {

    /** The name prefix of AWT's event-dispatch threads. */
    static final String EDT_PREFIX = "AWT-EventQueue";

    private Awt() {
    }

    /**
     * True on an AWT event-dispatch thread. Decided by the thread name first, so that a target without a UI never
     * has AWT initialised by the question.
     */
    static boolean onEventDispatchThread() {
        return Thread.currentThread().getName().startsWith(EDT_PREFIX) && EventQueue.isDispatchThread();
    }

    /** True if a live event-dispatch thread exists, i.e. the application has started AWT. Touches no AWT class. */
    static boolean eventDispatchThreadRunning() {
        for (Thread t : CorePrimitives.allThreads()) {
            if (t.getName().startsWith(EDT_PREFIX) && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /** Posts the task to the event-dispatch thread; starts AWT if the application has not (EDT thread policies). */
    static void invokeLater(Runnable task) {
        EventQueue.invokeLater(task);
    }

    /**
     * True when AWT is in use and a modal dialog is showing. Checks for an event-dispatch thread first so that a
     * target without a UI never has AWT initialised by the agent. Best effort, read-only, off the EDT.
     */
    static boolean modalDialogShowing() {
        try {
            if (!eventDispatchThreadRunning()) {
                return false;
            }
            for (Window window : Window.getWindows()) {
                if (window instanceof Dialog && ((Dialog) window).isModal() && window.isShowing()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
        return false;
    }
}
