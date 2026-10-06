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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.samples.lending.Engine;
import ai.skillroy.testjava.samples.lending.LendingApp;

/**
 * Finds the running desk. In EMBEDDED mode the plugin hands the {@link LendingApp} over as the agent's host
 * context and that object is used; in the other load modes the application's own accessor
 * {@link LendingApp#instance()} is.
 *
 * <p>Why the engine-thread check: in JAVAAGENT mode the agent starts before the application's {@code main}. The
 * application's classes are not touched (not even loaded) until a thread named {@value Engine#THREAD_NAME} exists,
 * which the application starts only after its database and window are up. The thread name is a compile-time
 * constant, so the check itself loads nothing of the application.
 *
 * <p>Thread-safe.
 */
final class LendingAccess {

    private final Object hostContext;
    /** Set once the engine thread was seen; from then on the application's classes may be used. */
    private volatile boolean engineSeen;

    /** @param hostContext what the host passed to {@code EmbeddedAgent.start}, or null */
    LendingAccess(Object hostContext) {
        this.hostContext = hostContext;
    }

    /** @return the running desk, or null while it is not (yet) running */
    LendingApp app() {
        if (hostContext != null && hostContext instanceof LendingApp) {
            return (LendingApp) hostContext;
        }
        if (!engineSeen) {
            if (!engineThreadExists()) {
                return null;
            }
            engineSeen = true;
        }
        return LendingApp.instance();
    }

    /**
     * @return the running desk
     * @throws RpcException CAPABILITY_UNAVAILABLE when it is not running
     */
    LendingApp requireApp() {
        LendingApp app = app();
        if (app == null) {
            throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "the lending application is not running");
        }
        return app;
    }

    /**
     * True for the desk's engine thread, which the agent must never block or run work on. Before the desk
     * is known the thread is recognised by its name.
     */
    boolean isEngineThread(Thread thread) {
        if (thread == null) {
            return false;
        }
        if (!Engine.THREAD_NAME.equals(thread.getName())) {
            return false;
        }
        LendingApp app = engineSeen || hostContext != null ? app() : null;
        if (app == null) {
            return true;
        }
        Thread engineThread = app.engine().thread();
        return engineThread == null || engineThread == thread;
    }

    /** True if a live thread with the engine thread's name exists in this JVM. */
    static boolean engineThreadExists() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        try {
            while (root.getParent() != null) {
                root = root.getParent();
            }
        } catch (SecurityException e) {
            // A security manager hides the parent groups; the own group is searched instead.
        }
        Thread[] threads = new Thread[root.activeCount() + 32];
        int n = root.enumerate(threads, true);
        for (int i = 0; i < n; i++) {
            Thread t = threads[i];
            if (t != null && t.isAlive() && Engine.THREAD_NAME.equals(t.getName())) {
                return true;
            }
        }
        return false;
    }
}
