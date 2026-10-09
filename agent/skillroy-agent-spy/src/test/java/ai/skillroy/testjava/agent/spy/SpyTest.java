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

package ai.skillroy.testjava.agent.spy;

import ai.skillroy.testjava.agent.api.instrument.ClassFileRefs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Spy} (phase6-contracts.md sections 3.1 and 13.1 item 1): the no-op without a handler, the set-once slot
 * released by identity only, a throwing handler counted and swallowed, the weak slot freed when its handler is
 * collected, the stub round trip, and a class-file scan that the classes reference nothing outside {@code java/}.
 */
class SpyTest {

    private final List<Spy.Handler> installed = new ArrayList<Spy.Handler>();

    @AfterEach
    void freeTheSlot() {
        for (Spy.Handler h : installed) {
            Spy.uninstall(h);
        }
        assertFalse(Spy.held(), "a test left the slot held");
    }

    private Spy.Handler install(Spy.Handler h) {
        installed.add(h);
        assertTrue(Spy.install(h));
        return h;
    }

    /** A handler that records what it was given and answers what it is told. */
    static class Recording implements Spy.Handler {
        volatile Object answer;
        volatile String lastId;
        volatile Object lastReturned;
        volatile Throwable lastThrown;

        @Override
        public Object enter(String hookId, Object self, Object[] args) {
            lastId = hookId;
            return answer;
        }

        @Override
        public void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown) {
            lastId = hookId;
            lastReturned = returned;
            lastThrown = thrown;
        }
    }

    private static long[] delta(long[] before) {
        long[] after = Spy.counters();
        long[] d = new long[after.length];
        for (int i = 0; i < d.length; i++) {
            d[i] = after[i] - before[i];
        }
        return d;
    }

    @Test
    void withoutAHandlerEveryCallIsACountedNoOp() {
        assertFalse(Spy.held());
        long[] before = Spy.counters();
        assertNull(Spy.enter("loan.posted", this, new Object[] {"100"}));
        Spy.exit("loan.posted", this, new Object[] {"100"}, "ok", null);
        assertTrue(Arrays.equals(new long[] {1, 2, 0, 0}, delta(before)), Arrays.toString(delta(before)));
    }

    @Test
    void theSlotIsSetOnceAndReleasedByIdentityOnly() {
        Recording first = new Recording();
        Recording second = new Recording();
        install(first);
        assertTrue(Spy.held());
        assertFalse(Spy.install(second), "another live handler holds the slot");
        assertTrue(Spy.install(first), "the holder installing again is a no-op that answers true");
        assertFalse(Spy.uninstall(second), "only the holder releases the slot");
        assertFalse(Spy.install(null));
        assertFalse(Spy.uninstall(null));
        assertTrue(Spy.held());
        Spy.enter("deposit.checked", null, new Object[0]);
        assertEquals("deposit.checked", first.lastId, "the holder receives the calls");
        assertNull(second.lastId);
        assertTrue(Spy.uninstall(first));
        assertFalse(Spy.held());
        install(second);
        Spy.exit("deposit.checked", null, new Object[0], null, null);
        assertEquals("deposit.checked", second.lastId);
    }

    @Test
    void aThrowingHandlerIsCountedAndNeverReachesTheCaller() {
        install(new Spy.Handler() {
            @Override
            public Object enter(String hookId, Object self, Object[] args) {
                throw new IllegalStateException("handler broke");
            }

            @Override
            public void exit(String hookId, Object self, Object[] args, Object returned, Throwable thrown) {
                throw new StackOverflowError("deep");
            }
        });
        long[] before = Spy.counters();
        assertNull(Spy.enter("waiver.checked", null, null));
        Spy.exit("waiver.checked", null, null, null, new RuntimeException("body threw"));
        assertTrue(Arrays.equals(new long[] {1, 0, 2, 0}, delta(before)), Arrays.toString(delta(before)));
    }

    @Test
    void aStubRoundTripsAndOnlyAStubSkips() {
        Recording handler = new Recording();
        install(handler);
        handler.answer = Spy.stub(Integer.valueOf(42));
        long[] before = Spy.counters();
        Object result = Spy.enter("line.amount", null, new Object[0]);
        assertTrue(result instanceof Spy.Stub);
        assertSame(handler.answer, result);
        assertEquals(Integer.valueOf(42), ((Spy.Stub) result).value());
        assertNull(Spy.stub(null).value(), "null is a valid stub value");
        handler.answer = "not a stub";
        assertNull(Spy.enter("line.amount", null, new Object[0]), "anything but a Stub runs the original body");
        assertTrue(Arrays.equals(new long[] {2, 0, 0, 1}, delta(before)), Arrays.toString(delta(before)));
        RuntimeException thrown = new RuntimeException("x");
        Spy.exit("line.amount", null, null, "returned", thrown);
        assertEquals("returned", handler.lastReturned);
        assertSame(thrown, handler.lastThrown);
    }

    @Test
    void theWeakSlotFreesItselfOnceTheHandlerIsCollected() throws InterruptedException {
        assertTrue(Spy.install(new Recording()), "held only by the slot's weak reference");
        long deadline = System.currentTimeMillis() + 20000;
        while (Spy.held() && System.currentTimeMillis() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
        assertFalse(Spy.held(), "the collected handler no longer holds the slot");
        Recording next = new Recording();
        install(next);
        assertTrue(Spy.held());
    }

    @Test
    void theClassesReferenceNothingOutsideJavaAndThemselves() throws IOException {
        File root = new File(System.getProperty("skillroy.spy.classes", "target/classes"));
        File dir = new File(root, "ai/skillroy/testjava/agent/spy");
        File[] files = dir.listFiles();
        assertTrue(files != null, "compiled classes under " + dir);
        TreeSet<String> classes = new TreeSet<String>();
        for (File f : files) {
            if (f.getName().endsWith(".class")) {
                classes.add(f.getName());
            }
        }
        assertEquals(new TreeSet<String>(Arrays.asList("Spy.class", "Spy$Handler.class", "Spy$Stub.class")), classes,
                "one class and its two nested types, no synthetic accessor");
        String pkg = "ai/skillroy/testjava/agent/spy/";
        List<String> own = Arrays.asList(pkg + "Spy", pkg + "Spy$Handler", pkg + "Spy$Stub");
        for (String name : classes) {
            byte[] bytes = Files.readAllBytes(new File(dir, name).toPath());
            assertEquals(52, ClassFileRefs.majorVersion(bytes), name + " is a Java 8 class file");
            for (String ref : ClassFileRefs.classReferences(bytes)) {
                assertTrue(ref.startsWith("java/") || own.contains(ref), name + " references " + ref);
            }
        }
    }
}
