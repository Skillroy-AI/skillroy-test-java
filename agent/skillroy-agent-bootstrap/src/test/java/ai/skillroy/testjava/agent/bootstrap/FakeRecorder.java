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

package ai.skillroy.testjava.agent.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Records what the test double of the core runtime saw. It lives in the bootstrap package, which the agent loader
 * always delegates to its parent, so the double (loaded child-first from a jar) and the tests share these statics.
 */
public final class FakeRecorder {

    public static final List<String> CALLS = Collections.synchronizedList(new ArrayList<String>());
    public static volatile Map<String, String> lastOptions;
    public static volatile Object lastHostContext;
    public static volatile ClassLoader lastHostLoader;
    public static volatile ClassLoader runtimeLoader;
    public static volatile boolean lastHadInstrumentation;
    /** Set by a double whose close is still "in progress": running it finishes that close. */
    public static volatile Runnable finishClose;
    /** What a re-entrant call made from inside the double's start returned (null: it did not return in time). */
    public static volatile Object reentrantResult;
    /** Counted down when the double's start has begun (option fake.gate). */
    public static volatile CountDownLatch startEntered;
    /** The double's start waits for this before returning (option fake.gate). */
    public static volatile CountDownLatch startGate;
    /** Counted down when the double's close has begun (option fake.closeGate). */
    public static volatile CountDownLatch closeEntered;
    /** The double's close waits for this (option fake.closeGate). */
    public static volatile CountDownLatch closeGate;
    /** Closes the running double the way {@code agent.disable} does: not through the bootstrap. */
    public static volatile Runnable selfClose;
    /** Name and context class loader of the thread the double's start ran on. */
    public static volatile String lastStartThread;
    public static volatile ClassLoader lastStartContextLoader;
    /** True if the bootstrap passed a release hook (the six-argument start of finding S32). */
    public static volatile boolean lastHadReleaseHook;

    private FakeRecorder() {
    }

    public static void reset() {
        CALLS.clear();
        lastOptions = null;
        lastHostContext = null;
        lastHostLoader = null;
        runtimeLoader = null;
        lastHadInstrumentation = false;
        finishClose = null;
        reentrantResult = null;
        startEntered = new CountDownLatch(1);
        startGate = new CountDownLatch(1);
        closeEntered = new CountDownLatch(1);
        closeGate = new CountDownLatch(1);
        selfClose = null;
        lastStartThread = null;
        lastStartContextLoader = null;
        lastHadReleaseHook = false;
    }
}
