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
import java.lang.reflect.Method;
import java.nio.charset.Charset;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code EmbeddedAgent.start} of contract amendment A6: idempotent in any load mode, re-entrant from the starting
 * thread and from agent threads, bounded when another thread is starting, callable by name, never throwing.
 */
class EmbeddedStartTest {

    private static final String ENABLED = "skillroy.agent.enabled";

    @TempDir
    Path tmp;

    private File home;
    private PrintStream originalErr;
    private ByteArrayOutputStream err;
    private ExecutorService hostThreads;

    @BeforeEach
    void setUp() throws IOException {
        FakeRecorder.reset();
        home = BootJars.fakeHome(tmp.resolve("home").toFile());
        BootLauncher.environment = Collections.emptyMap();
        System.clearProperty(ENABLED);
        System.clearProperty(BootLauncher.PROP_ERROR);
        originalErr = System.err;
        err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, "UTF-8"));
        hostThreads = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        FakeRecorder.startGate.countDown();
        hostThreads.shutdownNow();
        System.setErr(originalErr);
        BootLauncher.lockWaitMs = BootLauncher.LOCK_WAIT_MS;
        BootLauncher.stop();
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

    private String errText() {
        return new String(err.toByteArray(), Charset.forName("UTF-8"));
    }

    @Test
    void aStartWhileAJavaagentRunsReturnsTrueAndChangesNothing() {
        System.setProperty(ENABLED, "true");
        AgentBootstrap.premain("home=" + home.getPath(), null);
        assertEquals(Collections.singletonList("start JAVAAGENT"), FakeRecorder.CALLS);
        Object hostContext = new Object();
        assertTrue(EmbeddedAgent.start(options("port", "1234"), getClass().getClassLoader(), hostContext));
        assertTrue(EmbeddedAgent.start(options()));
        assertEquals(Collections.singletonList("start JAVAAGENT"), FakeRecorder.CALLS,
                "no second start, no re-publish, no options applied");
        assertNull(FakeRecorder.lastHostContext, "the host context of a later EMBEDDED call is not applied");
        assertEquals("", errText());
    }

    @Test
    void aStartFromTheStartingThreadAnswersTrueAtOnce() {
        assertTrue(EmbeddedAgent.start(options("fake.reenter", "same")));
        assertEquals(Boolean.TRUE, FakeRecorder.reentrantResult, "the nested call returned true without waiting");
        assertEquals(Collections.singletonList("start EMBEDDED"), FakeRecorder.CALLS, "one agent, not two");
        assertTrue(BootLauncher.isRunning());
    }

    @Test
    void aStartFromAnAgentThreadTheStartWaitsForAnswersTrueAtOnce() {
        long before = System.nanoTime();
        assertTrue(EmbeddedAgent.start(options("fake.reenter", "agentThread")));
        assertEquals(Boolean.TRUE, FakeRecorder.reentrantResult,
                "a module's install calling EmbeddedAgent.start must not deadlock the start");
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before) < 30, "no wait for the bound");
        assertEquals(Collections.singletonList("start EMBEDDED"), FakeRecorder.CALLS);
    }

    @Test
    void aStopFromAnAgentThreadDuringTheStartStopsTheAgentWhenTheStartHasFinished() {
        assertFalse(EmbeddedAgent.start(options("fake.reenter", "stop")), "the agent is not running afterwards");
        assertEquals("stop returned", FakeRecorder.reentrantResult, "the stop did not wait for the start");
        assertEquals(Arrays.asList("start EMBEDDED", "close"), FakeRecorder.CALLS);
        assertFalse(BootLauncher.isRunning());
        assertTrue(EmbeddedAgent.start(options()), "and it can be started again");
    }

    @Test
    void aStopFromAnAgentThreadDuringTheStopReturnsAtOnce() {
        assertTrue(EmbeddedAgent.start(options("fake.stopInClose", "true")));
        long before = System.nanoTime();
        EmbeddedAgent.stop();
        assertEquals("stop returned", FakeRecorder.reentrantResult,
                "a module's uninstall calling EmbeddedAgent.stop must not wait for the stop it is part of");
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before) < 30, "no wait for the bound");
        assertEquals(Arrays.asList("start EMBEDDED", "close"), FakeRecorder.CALLS);
        assertFalse(BootLauncher.isRunning());
    }

    @Test
    void concurrentStartsStartOneAgent() throws Exception {
        Future<Boolean> first = hostThreads.submit(startCall(options("fake.gate", "true")));
        assertTrue(FakeRecorder.startEntered.await(30, TimeUnit.SECONDS));
        Future<Boolean> second = hostThreads.submit(startCall(options()));
        FakeRecorder.startGate.countDown();
        assertTrue(first.get(30, TimeUnit.SECONDS));
        assertTrue(second.get(30, TimeUnit.SECONDS));
        assertEquals(Collections.singletonList("start EMBEDDED"), FakeRecorder.CALLS);
    }

    @Test
    void aStartThatFindsAnotherStartInProgressWaitsOnlyForTheBound() throws Exception {
        BootLauncher.lockWaitMs = 200L;
        Future<Boolean> first = hostThreads.submit(startCall(options("fake.gate", "true")));
        assertTrue(FakeRecorder.startEntered.await(30, TimeUnit.SECONDS));
        long before = System.nanoTime();
        assertTrue(EmbeddedAgent.start(options()), "an agent is starting");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 20000,
                "returned after the bound, not after the other start");
        assertFalse(first.isDone(), "the other start is still held at the gate");
        FakeRecorder.startGate.countDown();
        assertTrue(first.get(30, TimeUnit.SECONDS));
        assertEquals(Collections.singletonList("start EMBEDDED"), FakeRecorder.CALLS);
    }

    @Test
    void theMethodsAreCallableByName() throws Exception {
        Class<?> type = Class.forName("ai.skillroy.testjava.agent.bootstrap.EmbeddedAgent", true,
                getClass().getClassLoader());
        Method start = type.getMethod("start", Properties.class, ClassLoader.class, Object.class);
        Object hostContext = new Object();
        assertEquals(Boolean.TRUE, start.invoke(null, options(), getClass().getClassLoader(), hostContext));
        assertSame(hostContext, FakeRecorder.lastHostContext);
        assertEquals(Boolean.TRUE, type.getMethod("start", Properties.class).invoke(null, options()));
        assertEquals(Boolean.TRUE, type.getMethod("start", Properties.class, ClassLoader.class)
                .invoke(null, options(), null));
        type.getMethod("stop").invoke(null);
        assertEquals(Arrays.asList("start EMBEDDED", "close"), FakeRecorder.CALLS);
    }

    @Test
    void nothingThrowsEvenForNullOrBrokenInput() {
        assertFalse(EmbeddedAgent.start(null), "null options: not enabled");
        assertFalse(EmbeddedAgent.start(null, null, null));
        Properties broken = options("home", tmp.resolve("absent").toString());
        assertFalse(EmbeddedAgent.start(broken));
        assertTrue(errText().startsWith("[skillroy] EMBEDDED start failed: "), errText());
        EmbeddedAgent.stop();
        assertTrue(FakeRecorder.CALLS.isEmpty());
    }

    private static Callable<Boolean> startCall(final Properties props) {
        return new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return Boolean.valueOf(EmbeddedAgent.start(props));
            }
        };
    }
}
