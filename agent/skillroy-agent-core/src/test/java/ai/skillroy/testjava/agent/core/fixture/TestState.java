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

package ai.skillroy.testjava.agent.core.fixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Static switches and records shared between the in-process tests and the test modules. */
public final class TestState {

    public static final List<String> INSTALLED = Collections.synchronizedList(new ArrayList<String>());
    public static final List<String> UNINSTALLED = Collections.synchronizedList(new ArrayList<String>());
    public static final List<String> READY_CALLS = Collections.synchronizedList(new ArrayList<String>());
    public static final AtomicBoolean READY = new AtomicBoolean();
    public static final AtomicInteger PROBE_RUNS = new AtomicInteger();
    public static final AtomicInteger APP_BODY_RUNS = new AtomicInteger();
    public static final AtomicInteger MUTATIONS = new AtomicInteger();
    public static volatile boolean appOnForbidden;
    public static volatile CountDownLatch blocker = new CountDownLatch(1);
    /** What coretest's slow install / slow onTargetReady wait for, and the latch that says one of them was entered. */
    public static volatile CountDownLatch gate = new CountDownLatch(1);
    public static volatile CountDownLatch entered = new CountDownLatch(1);
    /** coretest's ready-enter, ready-exit and uninstall, in the order they happened. */
    public static final List<String> LIFECYCLE = Collections.synchronizedList(new ArrayList<String>());
    /** What coretest's stuck primitives wait for, ignoring interrupts (finding S30). */
    public static volatile CountDownLatch stuck = new CountDownLatch(1);
    /** coretest's ThreadPolicyProvider reports {@code available()} as this (contract amendment A19). */
    public static volatile boolean appAvailable = true;
    /** Bodies of coretest's stuck primitives that are running now. */
    public static final AtomicInteger STUCK_RUNNING = new AtomicInteger();
    /** The {@code value} argument each call of coretest's {@code test.leak} received (amendment A34). */
    public static final List<Object> LEAK_SEEN = Collections.synchronizedList(new ArrayList<Object>());
    /** What coretest's {@code test.leak.slow} waits for, ignoring interrupts (amendment A34, a timed-out call). */
    public static volatile CountDownLatch leakRelease = new CountDownLatch(1);
    /** The class loaders the jar fixture modules were installed from. */
    public static final List<ClassLoader> JAR_LOADERS = Collections.synchronizedList(new ArrayList<ClassLoader>());

    private TestState() {
    }

    public static void reset() {
        LEAK_SEEN.clear();
        leakRelease = new CountDownLatch(1);
        INSTALLED.clear();
        UNINSTALLED.clear();
        READY_CALLS.clear();
        READY.set(false);
        PROBE_RUNS.set(0);
        APP_BODY_RUNS.set(0);
        MUTATIONS.set(0);
        appOnForbidden = false;
        blocker = new CountDownLatch(1);
        gate = new CountDownLatch(1);
        entered = new CountDownLatch(1);
        LIFECYCLE.clear();
        stuck = new CountDownLatch(1);
        appAvailable = true;
        STUCK_RUNNING.set(0);
        JAR_LOADERS.clear();
    }
}
