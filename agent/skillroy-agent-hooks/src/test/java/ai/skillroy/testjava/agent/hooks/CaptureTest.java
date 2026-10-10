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

import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.fixture.hooks.Capturing;
import ai.skillroy.testjava.fixture.hooks.Returns;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoding of a captured value and its masking (phase6-contracts.md section 4.3, D125; section 13.1 item 3): every
 * scalar kind, a list cut at 100, a map with a secret-looking key, {@code secretArgs} and {@code secretResult}, mask
 * policies on numbers and strings, a policy that throws, an application object as a handle (its {@code toString()}
 * never called), the input budget, and re-entrancy.
 */
class CaptureTest {

    private Harness harness;
    private final Capturing capturing = new Capturing();

    @BeforeEach
    void install() throws Exception {
        harness = new Harness();
        harness.install("toys",
                spec("take", HookMode.CAPTURE, Capturing.class, "take", Harness.capture(false, true, 0, 1, 2)),
                Harness.spec("sign.on", HookMode.CAPTURE, Capturing.class.getName(), "signOn",
                        Harness.descriptor(Capturing.class, "signOn"), "system", Harness.capture(false, true, 0, 1),
                        Collections.singletonList(1), false),
                Harness.spec("token.issued", HookMode.CAPTURE, Capturing.class.getName(), "issueToken",
                        Harness.descriptor(Capturing.class, "issueToken"), "system", Harness.capture(false, true, 0),
                        null, true),
                spec("label.read", HookMode.OBSERVE, Capturing.class, "label"));
    }

    @AfterEach
    void close() throws Exception {
        harness.ctx.call("hook.remove", map());
        harness.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> take(Object a, Object b, Object c) {
        capturing.take(a, b, c);
        return harness.ctx.events.last("hook.take").payload();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> args(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("args");
    }

    @Test
    void everyScalarKind() {
        Map<String, Object> p = take("drill", Integer.valueOf(42), Boolean.TRUE);
        assertEquals("drill", args(p).get("0"));
        assertEquals(42L, ((Number) args(p).get("1")).longValue());
        assertEquals(Boolean.TRUE, args(p).get("2"));
        assertEquals("drill", p.get("returned"));
        p = take(Character.valueOf('x'), Double.valueOf(3.5), Double.valueOf(Double.NaN));
        assertEquals("x", args(p).get("0"));
        assertEquals(3.5d, ((Number) args(p).get("1")).doubleValue(), 0.0);
        assertEquals("NaN", args(p).get("2"), "a non-finite number as text: JSON has none");
        p = take(new BigDecimal("1E+3"), new BigInteger("123456789012345678901234567890"), Returns.Kind.POWER_TOOL);
        assertEquals("1000", args(p).get("0"), "a BigDecimal as its plain string");
        assertEquals("123456789012345678901234567890", args(p).get("1"));
        assertEquals("POWER_TOOL", args(p).get("2"), "an enum by name");
        p = take(new Date(0L), LocalDate.of(2026, 10, 9), Short.valueOf((short) 7));
        assertEquals("1970-01-01T00:00:00Z", args(p).get("0"));
        assertEquals("2026-10-09", args(p).get("1"));
        assertEquals(7L, ((Number) args(p).get("2")).longValue());
        p = take(null, Long.valueOf(Long.MAX_VALUE), Float.valueOf(1.5f));
        assertTrue(args(p).containsKey("0"));
        assertEquals(null, args(p).get("0"));
        assertFalse(p.containsKey("truncated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectionsAreCutAtOneHundredAndOneLevelDeep() {
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 101; i++) {
            many.add("T" + i);
        }
        List<Object> nested = Arrays.<Object>asList("a", Arrays.asList("b"), Collections.singletonMap("k", "v"));
        Map<String, Object> p = take(many, nested, new String[] {"x", "y"});
        List<Object> cut = (List<Object>) args(p).get("0");
        assertEquals(100, cut.size());
        assertEquals(Boolean.TRUE, p.get("truncated"));
        List<Object> deep = (List<Object>) args(p).get("1");
        assertEquals("a", deep.get(0));
        assertTrue(((Map<String, Object>) deep.get(1)).containsKey("ref"), "a nested list is a handle: " + deep);
        assertTrue(((Map<String, Object>) deep.get(2)).containsKey("ref"), "a nested map is a handle");
        assertEquals(Arrays.asList("x", "y"), args(p).get("2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMapEntryWithASecretKeyIsRedacted() {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("staffCode", "100");
        details.put("password", "hunter2");
        details.put("apiKey", "k-123");
        Map<String, Object> p = take(details, null, null);
        Map<String, Object> encoded = (Map<String, Object>) args(p).get("0");
        assertEquals("100", encoded.get("staffCode"));
        assertEquals("<redacted>", encoded.get("password"));
        assertEquals("<redacted>", encoded.get("apiKey"));
    }

    /**
     * Phase 6 security fix, pass 2, P2-L2-F7: a captured string, top-level, inside a list and inside a map (its key
     * too), passes the core's {@code redactText} after its cut, as a thrown message does: a secret {@code key=value}
     * pair's value and the password of URL user-info never leave the agent, although the catalogue marked no index.
     * Before the fix the three values reached the event in clear.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aCapturedStringPassesTheAgentsTextRedaction() {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("url", "jdbc:x://desk:pw-inner@h/db");
        options.put("note password=k3y", "kept");
        Map<String, Object> p = take("url=jdbc:x://desk:pw-outer@h/db;password=hunter2 mode=desk",
                Arrays.asList("token=abc123", "plain"), options);
        String text = String.valueOf(p);
        for (String secret : Arrays.asList("hunter2", "pw-outer", "abc123", "pw-inner", "k3y")) {
            assertFalse(text.contains(secret), secret + " left the agent: " + text);
        }
        assertEquals("url=jdbc:x://desk:<redacted>@h/db;password=<redacted> mode=desk", args(p).get("0"));
        assertEquals(Arrays.asList("token=<redacted>", "plain"), args(p).get("1"));
        Map<String, Object> encoded = (Map<String, Object>) args(p).get("2");
        assertEquals("jdbc:x://desk:<redacted>@h/db", encoded.get("url"));
        assertTrue(encoded.containsKey("note password=<redacted>"), "a map key's text too: " + encoded);
        assertTrue(harness.ctx.redacting.contains("plain"), "every string went through the core's rule");
        assertEquals("url=jdbc:x://desk:<redacted>@h/db;password=<redacted> mode=desk", p.get("returned"));
    }

    @Test
    void theCataloguesSecretArgumentsAndResultAreNeverCaptured() {
        assertTrue(capturing.signOn("100", "hunter2"));
        Map<String, Object> p = harness.ctx.events.last("hook.sign.on").payload();
        assertEquals("100", args(p).get("0"));
        assertEquals("<redacted>", args(p).get("1"));
        assertEquals(Boolean.TRUE, p.get("returned"));
        capturing.issueToken("100");
        Map<String, Object> t = harness.ctx.events.last("hook.token.issued").payload();
        assertEquals("<redacted>", t.get("returned"));
        assertEquals("100", args(t).get("0"));
        assertFalse(harness.ctx.events.topic("hook.sign.on").toString().contains("hunter2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anApplicationObjectIsAHandleAndItsToStringIsNeverCalled() {
        Capturing.Member member = new Capturing.Member("100");
        Map<String, Object> p = take(member, null, null);
        Map<String, Object> ref = (Map<String, Object>) args(p).get("0");
        assertEquals(Capturing.Member.class.getName(), ref.get("class"));
        assertEquals(member, harness.ctx.handles.resolve(String.valueOf(ref.get("ref"))));
        assertEquals(0, member.toStringCalls, "no application code ran");
        assertEquals(ref, p.get("returned"));
    }

    @Test
    void maskPoliciesSeeEveryScalarOnTheCallersThread() {
        final List<String> seen = new ArrayList<String>();
        final String caller = Thread.currentThread().getName();
        harness.ctx.spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                if (!Thread.currentThread().getName().equals(caller)) {
                    throw new AssertionError("not on the application's thread");
                }
                seen.add(sourceId + "/" + column);
                if ("arg0".equals(column) && value instanceof String) {
                    return "***";
                }
                if ("arg1".equals(column) && value instanceof Number) {
                    return "##";
                }
                return value;
            }
        });
        Map<String, Object> p = take("secret-ish", Integer.valueOf(1234), Arrays.asList("in", "list"));
        assertEquals("***", args(p).get("0"));
        assertEquals("##", args(p).get("1"));
        assertEquals(Arrays.asList("in", "list"), args(p).get("2"));
        assertTrue(seen.contains("hook.take/arg2"), "a scalar inside a list is masked as its argument: " + seen);
        assertTrue(seen.contains("hook.take/return"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPolicyThatThrowsRedactsTheValueCountsAFailureAndTheHookIsInError() throws Exception {
        harness.ctx.spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                if ("arg2".equals(column)) {
                    throw new IllegalStateException("policy broke");
                }
                return value;
            }
        });
        long failures = Harness.counters()[2];
        Map<String, Object> p = take("a", "b", "c");
        assertEquals("<redacted>", args(p).get("2"));
        assertEquals("a", args(p).get("0"));
        assertEquals(failures + 1, Harness.counters()[2], "counted as a handler failure by Spy");
        Map<String, Object> health = (Map<String, Object>) ((Map<String, Object>) harness.ctx.map("hook.health",
                map()).get("hooks")).get("take");
        assertEquals("ERROR", health.get("state"));
        assertEquals("mask policy threw", health.get("reason"));
        harness.ctx.capabilities.reprobe(harness.ctx);
        assertEquals("UNAVAILABLE", harness.ctx.capabilities.get("hook.take").state().name());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBudgetIsCountedOnTheInput() {
        char[] chars = new char[1000000];
        Arrays.fill(chars, 'm');
        String million = new String(chars);
        Map<String, Object> p = take(million, null, null);
        assertEquals(Capture.STRING_CUT, String.valueOf(args(p).get("0")).length());
        assertEquals(Boolean.TRUE, p.get("truncated"));
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 100; i++) {
            many.add(new String(chars, 0, 10000));
        }
        Capture.Run run = new Capture(harness.ctx, 16384).run("hook.take");
        List<Object> read = (List<Object>) run.top(many, "arg0");
        int total = 0;
        for (Object o : read) {
            total += o == null ? 0 : String.valueOf(o).length();
        }
        assertTrue(total <= 16384, "the encoder reads at most the budget: " + total);
        assertTrue(read.size() < 100, "and stops: " + read.size() + " of 100 elements");
        assertTrue(run.truncated);
        p = take(many, "after", null);
        assertEquals(Boolean.TRUE, p.get("truncated"));
        assertFalse(p.containsKey("args") && p.toString().length() > 3 * 16384, "the payload stays bounded");
    }

    /**
     * Section 4.3 as built (D182; the Phase 6 review's L2-F2 and L2-S1): only exact JDK container classes whose
     * iteration takes no lock and calls no method of an element are iterated; a view or wrapper of one (it may sit over
     * a locking or an application collection, or compare with the application's {@code compareTo}), a locking
     * collection and every blocking queue are handles.
     */
    @Test
    @SuppressWarnings("unchecked")
    void onlyTheJdkContainersWhoseIterationTakesNoLockAreIterated() throws Exception {
        List<String> list = new ArrayList<String>(Arrays.asList("a", "b"));
        Map<String, String> map = new HashMap<String, String>();
        map.put("k", "v");
        Map<Returns.Kind, String> byKind = new EnumMap<Returns.Kind, String>(Returns.Kind.class);
        byKind.put(Returns.Kind.STANDARD, "v");
        List<Object> iterated = new ArrayList<Object>(Arrays.<Object>asList(list, new LinkedList<String>(list),
                new ArrayDeque<String>(list), new PriorityQueue<String>(list), new HashSet<String>(list),
                new LinkedHashSet<String>(list), EnumSet.of(Returns.Kind.STANDARD), EnumSet.allOf(Returns.Kind.class),
                new HashMap<String, String>(map), new LinkedHashMap<String, String>(map),
                new TreeMap<String, String>(map), byKind, new IdentityHashMap<String, String>(map),
                Arrays.asList("a", "b"), Collections.emptyList(), Collections.emptySet(), Collections.emptyMap(),
                Collections.singletonList("a"), Collections.singleton("a"), Collections.singletonMap("k", "v"),
                new ConcurrentHashMap<String, String>(map), new ConcurrentLinkedQueue<String>(list),
                new ConcurrentLinkedDeque<String>(list), new CopyOnWriteArrayList<String>(list),
                new CopyOnWriteArraySet<String>(list), new ConcurrentSkipListMap<String, String>(map)));
        try {
            iterated.add(List.class.getMethod("of", Object.class, Object.class).invoke(null, "a", "b"));
            iterated.add(Map.class.getMethod("of", Object.class, Object.class).invoke(null, "k", "v"));
        } catch (NoSuchMethodException java8) {
            // ImmutableCollections exist from Java 9 on.
        }
        for (Object o : iterated) {
            assertTrue(Capture.iterable(o), o.getClass().getName() + " is iterated");
        }
        Object[] handles = {new Vector<String>(list), new Stack<String>(), new Hashtable<String, String>(map),
            new Properties(), Collections.synchronizedList(list), Collections.synchronizedMap(map),
            Collections.unmodifiableList(new Vector<String>(list)),
            Collections.unmodifiableMap(Collections.synchronizedMap(map)), Collections.unmodifiableList(list),
            Collections.unmodifiableSet(new HashSet<String>(list)), new ArrayBlockingQueue<String>(4),
            new LinkedBlockingQueue<String>(), new LinkedBlockingDeque<String>(), new PriorityBlockingQueue<String>(),
            new DelayQueue<Delayed>(), new SynchronousQueue<String>(), new LinkedTransferQueue<String>(),
            list.subList(0, 1), new TreeMap<String, String>(map).headMap("z"), new TreeSet<String>(list),
            new TreeSet<String>(list).subSet("a", "b"), new ConcurrentSkipListSet<String>(list),
            new HashMap<String, String>(map).keySet(), new HashMap<String, String>(map).values(), map.entrySet(),
            new CopyOnWriteArrayList<String>(list).subList(0, 1)};
        for (Object o : handles) {
            assertFalse(Capture.iterable(o), o.getClass().getName() + " is a handle");
        }
        Map<String, Object> p = take(new DelayQueue<Delayed>(), Collections.unmodifiableList(new Vector<String>(list)),
                new TreeMap<String, String>(map).headMap("z"));
        for (String i : new String[] {"0", "1", "2"}) {
            assertTrue(((Map<String, Object>) args(p).get(i)).containsKey("ref"), i + ": " + args(p).get(i));
        }
    }

    /**
     * D182 as amended by the fix wave (FB's probe of the shared rule): a {@code BigDecimal} is judged by its unscaled
     * value's bit length before any text is made, as a {@code BigInteger} is. Its {@code precision()} and its 1.2
     * million-character {@code toString()} took seconds on the application's thread.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aHugeBigDecimalIsCutByItsBitLengthBeforeAnyTextIsMade() {
        BigDecimal huge = new BigDecimal(BigInteger.ONE.shiftLeft(4000000), 0);
        BigDecimal fits = new BigDecimal(BigInteger.ONE.shiftLeft(Capture.MAX_BITS - 1), 3);
        Capture.Run run = new Capture(harness.ctx, 16384).run("hook.take");
        long t0 = System.nanoTime();
        Object cut = run.top(huge, "arg0");
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals(null, cut);
        assertTrue(run.truncated);
        assertTrue(ms < 1000, "judged by its bit length, not its text: " + ms + " ms");
        assertEquals(fits.toPlainString(), new Capture(harness.ctx, 16384).run("hook.take").top(fits, "arg0"),
                "a value within the bound (about 1,800 digits) is its plain text");
        Map<String, Object> p = take(huge, new BigDecimal("12.50"), null);
        assertEquals(null, args(p).get("0"));
        assertEquals("12.50", args(p).get("1"));
        assertEquals(Boolean.TRUE, p.get("truncated"));
    }

    /** The documented idiom for iterating a synchronized map, held by another thread, never blocks the caller. */
    @Test
    @SuppressWarnings("unchecked")
    void aViewOverASynchronizedMapHeldByAnotherThreadDoesNotBlockTheCaller() throws Exception {
        final Map<String, String> shared = Collections.synchronizedMap(new HashMap<String, String>());
        shared.put("k", "v");
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        Thread holder = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (shared) {
                    held.countDown();
                    try {
                        done.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "lending-desk-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS), "the holder took the map's lock");
        long t0 = System.nanoTime();
        Map<String, Object> p;
        try {
            p = take(Collections.unmodifiableMap(shared), null, null);
        } finally {
            done.countDown();
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        holder.join(10000);
        assertTrue(((Map<String, Object>) args(p).get("0")).containsKey("ref"), String.valueOf(args(p).get("0")));
        assertTrue(ms < 2000, "the caller waited for the application's lock: " + ms + " ms");
    }

    @Test
    void aPolicyThatCallsAHookedMethodDoesNotRecurse() {
        final AtomicInteger calls = new AtomicInteger();
        harness.ctx.spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                calls.incrementAndGet();
                capturing.label();
                return value;
            }
        });
        int before = Harness.events(harness.ctx, "hook.label.read").size();
        long enters = Harness.counters()[0];
        take("a", null, null);
        assertTrue(calls.get() >= 2);
        assertEquals(before, Harness.events(harness.ctx, "hook.label.read").size(),
                "the nested call inside the handler fired no event");
        assertTrue(Harness.counters()[0] >= enters + 1 + calls.get(), "but Spy counted its enters");
        capturing.label();
        assertEquals(before + 1, Harness.events(harness.ctx, "hook.label.read").size(), "outside, it fires");
    }
}
