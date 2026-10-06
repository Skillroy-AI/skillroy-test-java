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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The bootstrap's part of the hardening wave: a self-closed agent is forgotten (finding S31), the loader release hook
 * of a failed start (S32), a stop on the event-dispatch thread or a forbidden thread never waits for another start or
 * stop (S33), and {@code EmbeddedAgent.startAsync} (contract amendment A22).
 */
class BootHardeningTest {

    private static final String ENABLED = "skillroy.agent.enabled";

    @TempDir
    Path tmp;

    private File home;
    private PrintStream originalErr;
    private ExecutorService hostThreads;

    @BeforeEach
    void setUp() throws IOException {
        FakeRecorder.reset();
        home = BootJars.fakeHome(tmp.resolve("home").toFile());
        BootLauncher.environment = Collections.emptyMap();
        System.clearProperty(ENABLED);
        System.clearProperty(BootLauncher.PROP_ERROR);
        originalErr = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));
        hostThreads = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws Exception {
        FakeRecorder.startGate.countDown();
        FakeRecorder.closeGate.countDown();
        hostThreads.shutdownNow();
        System.setErr(originalErr);
        BootLauncher.lockWaitMs = BootLauncher.LOCK_WAIT_MS;
        BootLauncher.stop();
        awaitTrue("no agent held after the test", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(!BootLauncher.holdsAgent());
            }
        });
        BootLauncher.environment = null;
        System.clearProperty(ENABLED);
        System.clearProperty(BootLauncher.PROP_ERROR);
    }

    private Properties options(String... keyValues) {
        Properties props = new Properties();
        props.setProperty("enabled", "true");
        props.setProperty("home", home.getPath());
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    private static void awaitTrue(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.call().booleanValue()) {
            if (System.nanoTime() - deadline > 0) {
                fail("timed out waiting: " + what);
            }
            Thread.sleep(10);
        }
    }

    // ---- S31 ----

    @Test
    void anAgentThatClosedItselfIsForgottenAndItsLoaderCanBeCollected() throws Exception {
        assertTrue(EmbeddedAgent.start(options()));
        assertTrue(BootLauncher.holdsAgent());
        assertTrue(FakeRecorder.lastHadReleaseHook, "the six-argument start hands the release hook over");
        URLClassLoader loader = (URLClassLoader) FakeRecorder.runtimeLoader;
        WeakReference<ClassLoader> collected = new WeakReference<ClassLoader>(loader);
        loader = null;

        // agent.disable or a production marker: the runtime closes itself, nobody calls the bootstrap.
        FakeRecorder.selfClose.run();
        assertFalse(BootLauncher.holdsAgent(), "the close hook forgets the closed agent at once");
        assertFalse(BootLauncher.isRunning());

        FakeRecorder.selfClose = null;
        FakeRecorder.runtimeLoader = null;
        FakeRecorder.finishClose = null;
        FakeRecorder.lastOptions = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (collected.get() != null && System.nanoTime() - deadline < 0) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(collected.get(), "nothing in the bootstrap keeps the closed agent's loader");

        assertTrue(EmbeddedAgent.start(options()), "a later start starts a fresh agent");
        assertEquals(Arrays.asList("start EMBEDDED", "close", "start EMBEDDED"), FakeRecorder.CALLS);
    }

    @Test
    void aLateHookOfAnOldAgentDoesNotForgetANewerOne() {
        assertTrue(EmbeddedAgent.start(options("fake.asyncClose", "true")));
        Runnable oldSelfClose = FakeRecorder.selfClose;
        EmbeddedAgent.stop();   // the old agent is still "shutting down on its own thread"
        Runnable oldHook = FakeRecorder.finishClose;
        assertNotNull(oldHook);
        assertTrue(EmbeddedAgent.start(options()));
        assertTrue(BootLauncher.isRunning());
        oldHook.run();   // the old agent's close finishes after the new agent started
        assertTrue(BootLauncher.isRunning(), "compare-and-clear: the newer agent stays held");
        assertTrue(oldSelfClose != FakeRecorder.selfClose);
    }

    // ---- S32 ----

    @Test
    void aFailedStartReleasesTheLoaderThroughTheRuntimesHook() {
        assertFalse(EmbeddedAgent.start(options("fake.fail", "true")));
        assertTrue(FakeRecorder.lastHadReleaseHook);
        assertFalse(BootLauncher.holdsAgent());
        URLClassLoader loader = (URLClassLoader) FakeRecorder.runtimeLoader;
        if (loader != null) {
            assertNull(loader.findResource(BootJars.FAKE_RUNTIME), "the hook closed the agent loader");
        }
        assertTrue(EmbeddedAgent.start(options()));
    }

    // ---- S33 ----

    @Test
    void aStopOnTheEventDispatchThreadDuringAnotherStartReturnsAtOnceAndStopsAfterIt() throws Exception {
        Future<Boolean> start = hostThreads.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(EmbeddedAgent.start(options("fake.gate", "true")));
            }
        });
        assertTrue(FakeRecorder.startEntered.await(30, TimeUnit.SECONDS));
        long took = stopOnThreadNamed("AWT-EventQueue-0");
        assertTrue(took < 2000, "the EDT's stop returned at once, not after the other start: " + took + " ms");
        assertFalse(start.isDone(), "the start is still held at the gate");
        FakeRecorder.startGate.countDown();
        start.get(30, TimeUnit.SECONDS);
        awaitTrue("the stop ran after the start", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(FakeRecorder.CALLS.contains("close") && !BootLauncher.holdsAgent());
            }
        });
        assertEquals(Arrays.asList("start EMBEDDED", "close"), FakeRecorder.CALLS);
    }

    @Test
    void aStopOnAForbiddenThreadDuringAnotherStopReturnsAtOnce() throws Exception {
        assertTrue(EmbeddedAgent.start(options("fake.closeGate", "true")));
        Future<?> firstStop = hostThreads.submit(new Runnable() {
            @Override
            public void run() {
                EmbeddedAgent.stop();
            }
        });
        assertTrue(FakeRecorder.closeEntered.await(30, TimeUnit.SECONDS));
        long took = stopOnThreadNamed("fake-forbidden-1");
        assertTrue(took < 2000, "a thread the agent forbids never waits for the lock: " + took + " ms");
        FakeRecorder.closeGate.countDown();
        firstStop.get(30, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("start EMBEDDED", "close"), FakeRecorder.CALLS);
    }

    /** Calls EmbeddedAgent.stop() on a new thread with the given name; returns how long it took (ms). */
    private static long stopOnThreadNamed(String name) throws InterruptedException {
        final long[] took = {-1L};
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                long before = System.nanoTime();
                EmbeddedAgent.stop();
                took[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(thread.isAlive(), "the stop on " + name + " returned");
        return took[0];
    }

    // ---- A22 ----

    @Test
    void startAsyncReturnsAtOnceAndStartsOnItsOwnThread() throws Exception {
        ClassLoader host = new ClassLoader(getClass().getClassLoader()) {
        };
        Object context = new Object();
        long before = System.nanoTime();
        assertTrue(EmbeddedAgent.startAsync(options("fake.gate", "true"), host, context));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2000, "returned at once");
        assertTrue(FakeRecorder.startEntered.await(30, TimeUnit.SECONDS), "the start runs in the background");
        assertTrue(EmbeddedAgent.startAsync(options(), host, context), "a start is in progress: true, no second");
        FakeRecorder.startGate.countDown();
        awaitTrue("the agent runs", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(BootLauncher.isRunning());
            }
        });
        assertEquals("skillroy-start", FakeRecorder.lastStartThread);
        assertSame(host, FakeRecorder.lastStartContextLoader, "the start thread carries the host loader");
        assertSame(host, FakeRecorder.lastHostLoader);
        assertSame(context, FakeRecorder.lastHostContext);
        assertTrue(EmbeddedAgent.startAsync(options(), host, null), "already running");
        assertEquals(Collections.singletonList("start EMBEDDED"), FakeRecorder.CALLS);
    }

    @Test
    void startAsyncRefusesWhenNotEnabledAndReportsAFailedStartInTheErrorProperty() throws Exception {
        Properties notEnabled = options();
        notEnabled.remove("enabled");
        assertFalse(EmbeddedAgent.startAsync(notEnabled, null, null));
        assertFalse(EmbeddedAgent.startAsync(null, null, null));
        System.setProperty(BootLauncher.PROP_ERROR, "an earlier failure");
        assertTrue(EmbeddedAgent.startAsync(options("fake.fail", "true"), null, null));
        awaitTrue("the failure is visible", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                String error = System.getProperty(BootLauncher.PROP_ERROR);
                return Boolean.valueOf(error != null && error.contains("fake start failure"));
            }
        });
        assertTrue(FakeRecorder.CALLS.isEmpty());
        assertFalse(BootLauncher.isRunning());
    }

    @Test
    void aStopAfterStartAsyncWithdrawsAStartThatHasNotBegun() throws Exception {
        assertTrue(EmbeddedAgent.start(options("fake.closeGate", "true")));
        Future<?> firstStop = hostThreads.submit(new Runnable() {
            @Override
            public void run() {
                EmbeddedAgent.stop();
            }
        });
        assertTrue(FakeRecorder.closeEntered.await(30, TimeUnit.SECONDS));
        // The agent is closing and the lock is held: the asynchronous start waits for it on its own thread.
        assertTrue(EmbeddedAgent.startAsync(options(), null, null));
        Future<?> secondStop = hostThreads.submit(new Runnable() {
            @Override
            public void run() {
                EmbeddedAgent.stop();
            }
        });
        Thread.sleep(100);
        FakeRecorder.closeGate.countDown();
        firstStop.get(30, TimeUnit.SECONDS);
        secondStop.get(30, TimeUnit.SECONDS);
        Thread.sleep(300);
        assertEquals(Arrays.asList("start EMBEDDED", "close"), FakeRecorder.CALLS,
                "the stop made after startAsync withdrew the start");
        assertFalse(BootLauncher.isRunning());
    }

    @Test
    void startAsyncIsCallableByName() throws Exception {
        Class<?> type = Class.forName("ai.skillroy.testjava.agent.bootstrap.EmbeddedAgent", true,
                getClass().getClassLoader());
        Method startAsync = type.getMethod("startAsync", Properties.class, ClassLoader.class, Object.class);
        assertEquals(Boolean.TRUE, startAsync.invoke(null, options(), null, null));
        awaitTrue("the agent runs", new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(BootLauncher.isRunning());
            }
        });
    }
}
