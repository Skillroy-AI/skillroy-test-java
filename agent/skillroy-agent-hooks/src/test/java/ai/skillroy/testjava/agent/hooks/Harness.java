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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.hook.HookCapture;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.protocol.hook.HookTarget;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The hooks module installed on a {@link FakeHookContext} with the test agent's {@code Instrumentation}, and the
 * helpers the tests share. The tests reach {@code Spy} only reflectively, through the bootstrap loader, after the first
 * install (the spy module is kept off the test class path).
 */
final class Harness implements AutoCloseable {

    static final String SPY = "ai.skillroy.testjava.agent.spy.Spy";

    final FakeHookContext ctx;
    final HooksModule module = new HooksModule();

    Harness() {
        this(new FakeHookContext());
    }

    Harness(FakeHookContext ctx) {
        this.ctx = ctx;
        module.install(ctx);
        ctx.ready();
    }

    HookEngineImpl engine() {
        return module.engine();
    }

    /** Installs the specs as catalogue {@code id} with a hash of its own; the primitive's answer. */
    Map<String, Object> install(String catalogueId, HookSpec... specs) throws Exception {
        return ctx.map("hook.install", FakeHookContext.map("specs", json(specs), "catalogue",
                FakeHookContext.map("id", catalogueId, "hash", hash(specs))));
    }

    static String hash(HookSpec... specs) {
        StringBuilder text = new StringBuilder();
        for (HookSpec s : specs) {
            text.append(s.hash());
        }
        return "sha256:" + Integer.toHexString(text.toString().hashCode());
    }

    static List<Object> json(HookSpec... specs) {
        List<Object> out = new ArrayList<Object>();
        for (HookSpec s : specs) {
            out.add(s.toJson());
        }
        return out;
    }

    /** Uninstalls the module (the detach) and checks that it left the Spy slot free. */
    @Override
    public void close() {
        module.uninstall();
        ctx.rollback();
        assertFalse(slotHeld(), "the module left the Spy slot held");
    }

    // ---------------------------------------------------------------------------------------------- spec helpers

    static HookSpec spec(String id, HookMode mode, Class<?> type, String method) {
        return spec(id, mode, type, method, null);
    }

    /**
     * A spec on a class of the test's own loader, pinned to the system loader ({@code loader: system}), so that a copy
     * of the same fixture class that another test class of the JVM left in a child loader never widens it.
     */
    static HookSpec spec(String id, HookMode mode, Class<?> type, String method, HookCapture capture) {
        String loader = type.getClassLoader() == ClassLoader.getSystemClassLoader() ? "system" : null;
        return new HookSpec(id, mode, new HookTarget(type.getName(), method,
                Collections.singletonList(descriptor(type, method)), loader), null, capture, null, false, null);
    }

    static HookSpec spec(String id, HookMode mode, String className, String method, String descriptor,
                         String loader, HookCapture capture, List<Integer> secretArgs, boolean secretResult) {
        return new HookSpec(id, mode, new HookTarget(className, method, Collections.singletonList(descriptor),
                loader), null, capture, secretArgs, secretResult, null);
    }

    /** The descriptor of the one declared method of that name. */
    static String descriptor(Class<?> type, String method) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(method)) {
                return Descriptors.of(m);
            }
        }
        throw new IllegalArgumentException("no method " + method + " in " + type);
    }

    static HookCapture capture(boolean self, boolean result, Integer... args) {
        return new HookCapture(Arrays.asList(args), self, result);
    }

    // ---------------------------------------------------------------------------------------------- Spy, reflectively

    static Class<?> bootSpy() {
        try {
            return Class.forName(SPY, false, null);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * The precondition of every {@code *FreshJvmTest} class (phase6-contracts.md section 2.3 as built; the Phase 6
     * review's L3-F2): no {@code Spy} on the bootstrap path yet. The module's surefire executions give each such class
     * a JVM of its own; a command-line {@code -Dtest} merges them into one, and then a class that finds {@code Spy}
     * appended is skipped with the reason rather than failing for the wrong one. Without {@code -Dtest} (surefire
     * hands the property to the fork) a JVM that is not fresh fails: never a green skip in the matrix.
     */
    static void assumeFreshJvm() {
        if (bootSpy() == null) {
            return;
        }
        String why = "needs a fresh JVM (run without -Dtest: the module's surefire executions give it one): Spy is"
                + " already on the bootstrap path";
        assumeTrue(System.getProperty("test") == null, why);
        fail(why);
    }

    static boolean slotHeld() {
        Class<?> spy = bootSpy();
        if (spy == null) {
            return false;
        }
        try {
            return (Boolean) spy.getMethod("held").invoke(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static long[] counters() {
        try {
            return (long[]) bootSpy().getMethod("counters").invoke(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static Instrumentation inst() {
        return TestAgent.instrumentation();
    }

    // ---------------------------------------------------------------------------------------------- loaders and events

    static URL testClasses() {
        try {
            return new File(System.getProperty("skillroy.hooks.testClasses", "target/test-classes")).toURI().toURL();
        } catch (MalformedURLException e) {
            throw new AssertionError(e);
        }
    }

    static List<Event> events(FakeHookContext ctx, String topic) {
        return ctx.events.topic(topic);
    }

    static void assertLogContains(FakeHookContext ctx, String text) {
        for (String line : ctx.log.all()) {
            if (line.contains(text)) {
                return;
            }
        }
        assertTrue(false, "no log line contains '" + text + "': " + ctx.log.all());
    }
}
