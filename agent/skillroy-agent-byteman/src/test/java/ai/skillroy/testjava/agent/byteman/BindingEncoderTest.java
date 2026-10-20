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

package ai.skillroy.testjava.agent.byteman;

import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.fixture.byteman.DepositDesk;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoding of a rule's bindings (phase6-contracts.md 7.3 with 4.3's rule, D125): scalars, the cuts, containers one
 * level deep, handles for application objects (never their {@code toString()}), the secret-key rule, every
 * {@code MaskPolicy} on every scalar, a throwing policy, and the input budget.
 */
class BindingEncoderTest {

    private final FakeAgentContext ctx = new FakeAgentContext(null);

    /** An application object whose toString must never run. */
    static final class Loud {
        @Override
        public String toString() {
            throw new AssertionError("toString of an application object ran");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bindings(BindingEncoder encoder, Object... values) {
        return (Map<String, Object>) encoder.encode("limit", values).get("bindings");
    }

    @Test
    void scalarsAreJsonAndApplicationObjectsAreHandles() {
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        DepositDesk desk = new DepositDesk();
        Map<String, Object> b = bindings(e, desk, "STAFF-7", new BigDecimal("150.00"), 7, 2.5f, true, 'x',
                TimeUnit.SECONDS, new BigInteger("12345678901234567890"), LocalDate.of(2026, 10, 9),
                new Date(0), null, new Loud());
        Map<?, ?> handle = (Map<?, ?>) b.get("0");
        assertEquals(DepositDesk.class.getName(), handle.get("class"));
        assertTrue(String.valueOf(handle.get("ref")).startsWith("h"));
        assertEquals(desk, ctx.handles.resolve((String) handle.get("ref")));
        assertEquals("STAFF-7", b.get("1"));
        assertEquals("150.00", b.get("2"));
        assertEquals(7L, b.get("3"));
        assertEquals(2.5d, b.get("4"));
        assertEquals(true, b.get("5"));
        assertEquals("x", b.get("6"));
        assertEquals("SECONDS", b.get("7"));
        assertEquals("12345678901234567890", b.get("8"));
        assertEquals("2026-10-09", b.get("9"));
        assertEquals("1970-01-01T00:00:00Z", b.get("10"));
        assertNull(b.get("11"));
        assertEquals(Loud.class.getName(), ((Map<?, ?>) b.get("12")).get("class"));
    }

    /**
     * Phase 6 security fix, pass 2, P2-L2-F7: a bound string, top-level, inside a list and inside a map (its key too),
     * passes the core's {@code redactText} after its cut, so that a secret {@code key=value} pair's value and the
     * password of URL user-info are hidden before the mask policies; before the fix they reached {@code rule.fired}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aBoundStringPassesTheAgentsTextRedaction() {
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        Map<String, Object> options = new java.util.LinkedHashMap<String, Object>();
        options.put("url", "jdbc:x://desk:pw-inner@h/db");
        options.put("note password=k3y", "kept");
        Map<String, Object> b = bindings(e, "url=jdbc:x://desk:pw-outer@h/db;password=hunter2 mode=desk",
                Arrays.asList("token=abc123", "plain"), options);
        String text = String.valueOf(b);
        for (String secret : Arrays.asList("hunter2", "pw-outer", "abc123", "pw-inner", "k3y")) {
            assertFalse(text.contains(secret), secret + " left the agent: " + text);
        }
        assertEquals("url=jdbc:x://desk:<redacted>@h/db;password=<redacted> mode=desk", b.get("0"));
        assertEquals(Arrays.asList("token=<redacted>", "plain"), b.get("1"));
        assertEquals("jdbc:x://desk:<redacted>@h/db", ((Map<String, Object>) b.get("2")).get("url"));
        assertTrue(ctx.redacting.contains("plain"), "every bound string went through the core's rule");
    }

    @Test
    void containersAreReadOneLevelDeepAndCut() {
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        List<Object> list = new ArrayList<Object>();
        for (int i = 0; i < 101; i++) {
            list.add(i);
        }
        Map<String, Object> out = e.encode("limit", new Object[] {list, Arrays.asList(Arrays.asList(1, 2)),
                new int[] {1, 2, 3}});
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) out.get("bindings");
        assertEquals(100, ((List<?>) b.get("0")).size(), "at most 100 elements");
        assertEquals(Boolean.TRUE, out.get("truncated"));
        Object nested = ((List<?>) b.get("1")).get(0);
        assertTrue(nested instanceof Map && ((Map<?, ?>) nested).containsKey("ref"), "a nested list is a handle");
        assertEquals(Arrays.asList(1L, 2L, 3L), b.get("2"));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            longText.append('a');
        }
        Map<String, Object> cut = bindings(e, longText.toString(), Collections.singletonList(longText.toString()));
        assertEquals(BindingEncoder.MAX_STRING, ((String) cut.get("0")).length());
        assertEquals(BindingEncoder.MAX_STRING_IN_COLLECTION, ((String) ((List<?>) cut.get("1")).get(0)).length());
    }

    @Test
    void anApplicationCollectionIsAHandleNotIterated() {
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        List<Object> appList = new ArrayList<Object>() {
            @Override
            public java.util.Iterator<Object> iterator() {
                throw new AssertionError("an application collection was iterated");
            }
        };
        Map<?, ?> h = (Map<?, ?>) bindings(e, appList).get("0");
        assertTrue(h.containsKey("ref"), h.toString());
    }

    /** A delayed element for a {@code DelayQueue}. */
    static final class Due implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }
    }

    private static boolean isHandle(Object encoded) {
        return encoded instanceof Map && ((Map<?, ?>) encoded).containsKey("ref")
                && ((Map<?, ?>) encoded).containsKey("class");
    }

    /**
     * Fix wave (L2-F1, L2-S1): only an allow-list of JDK collection and map classes is read, those whose iteration
     * takes no lock and runs no method of an element; a locking collection, a blocking queue, a synchronized wrapper,
     * and every view (an unmodifiable or synchronized wrapper, a sub-map, a key set) is a handle.
     */
    @Test
    void aCollectionWhoseIterationTakesALockOrIsAViewIsAHandle() {
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        Map<String, Object> plain = new HashMap<String, Object>();
        plain.put("k", 1);
        Vector<Object> vector = new Vector<Object>(Arrays.asList(1, 2));
        Stack<Object> stack = new Stack<Object>();
        stack.push(1);
        Hashtable<String, Object> table = new Hashtable<String, Object>(plain);
        Properties properties = new Properties();
        properties.setProperty("k", "v");
        DelayQueue<Due> delays = new DelayQueue<Due>();
        delays.add(new Due());
        TreeMap<String, Object> tree = new TreeMap<String, Object>(plain);
        Object[] refused = {vector, stack, table, properties, new ArrayBlockingQueue<Object>(4, false, Arrays.asList(1)),
            new LinkedBlockingQueue<Object>(Arrays.asList(1)), new LinkedBlockingDeque<Object>(Arrays.asList(1)),
            new PriorityBlockingQueue<Object>(Arrays.asList(1)), delays,
            new LinkedTransferQueue<Object>(Arrays.asList(1)),
            Collections.synchronizedList(new ArrayList<Object>(Arrays.asList(1))),
            Collections.synchronizedMap(new HashMap<String, Object>(plain)),
            Collections.unmodifiableList(vector), Collections.unmodifiableMap(Collections.synchronizedMap(plain)),
            Collections.unmodifiableCollection(Arrays.asList(1)), tree.headMap("z"), plain.keySet(),
            plain.values(), tree.descendingMap(), new TreeSet<Integer>(Arrays.asList(1)),
            new ConcurrentSkipListSet<Integer>(Arrays.asList(1))};
        Map<String, Object> b = bindings(e, refused);
        for (int i = 0; i < refused.length; i++) {
            assertTrue(isHandle(b.get(Integer.toString(i))), refused[i].getClass().getName() + " is a handle: "
                    + b.get(Integer.toString(i)));
            assertEquals(refused[i].getClass().getName(), ((Map<?, ?>) b.get(Integer.toString(i))).get("class"));
        }
    }

    @Test
    void theJdkCollectionsWithoutALockAreRead() {
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        List<Integer> one = Arrays.asList(7);
        Map<String, Object> single = Collections.<String, Object>singletonMap("k", 7);
        Object[] read = {new ArrayList<Integer>(one), new LinkedList<Integer>(one), new ArrayDeque<Integer>(one),
            new PriorityQueue<Integer>(one), new HashSet<Integer>(one), new LinkedHashSet<Integer>(one),
            EnumSet.of(TimeUnit.SECONDS), new ConcurrentLinkedQueue<Integer>(one),
            new ConcurrentLinkedDeque<Integer>(one), new CopyOnWriteArrayList<Integer>(one),
            new CopyOnWriteArraySet<Integer>(one), one,
            Collections.singletonList(7), Collections.singleton(7), Collections.emptyList(),
            new HashMap<String, Object>(single), new LinkedHashMap<String, Object>(single),
            new TreeMap<String, Object>(single), new ConcurrentHashMap<String, Object>(single),
            new ConcurrentSkipListMap<String, Object>(single), new IdentityHashMap<String, Object>(single), single,
            Collections.emptyMap()};
        Map<String, Object> b = bindings(e, read);
        for (int i = 0; i < read.length; i++) {
            Object v = b.get(Integer.toString(i));
            assertFalse(isHandle(v), read[i].getClass().getName() + " is read: " + v);
            assertTrue(v instanceof List || v instanceof Map, read[i].getClass().getName() + ": " + v);
        }
        Map<TimeUnit, Object> byUnit = new EnumMap<TimeUnit, Object>(TimeUnit.class);
        byUnit.put(TimeUnit.SECONDS, 1);
        assertEquals(Collections.singletonMap("SECONDS", 1L), bindings(e, byUnit).get("0"));
        assertEquals(Arrays.asList("SECONDS"), bindings(e, EnumSet.of(TimeUnit.SECONDS)).get("0"));
    }

    /** An application key that counts its comparisons. */
    static final class Code implements Comparable<Code> {
        static final AtomicInteger COMPARED = new AtomicInteger();
        final int n;

        Code(int n) {
            this.n = n;
        }

        @Override
        public int compareTo(Code o) {
            COMPARED.incrementAndGet();
            return Integer.compare(n, o.n);
        }
    }

    /**
     * Fix wave (L2-S1): a {@code TreeSet} may be a view (its {@code subSet} is a {@code TreeSet} over a bounded sub-map,
     * whose iteration compares the keys), so it is a handle and no {@code compareTo} of the application runs.
     */
    @Test
    void aSortedSetViewRunsNoCompareToOfTheApplication() {
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        java.util.TreeSet<Code> codes = new java.util.TreeSet<Code>();
        for (int i = 0; i < 5; i++) {
            codes.add(new Code(i));
        }
        java.util.NavigableSet<Code> middle = codes.subSet(new Code(1), true, new Code(3), true);
        java.util.concurrent.ConcurrentSkipListSet<Code> skip = new java.util.concurrent.ConcurrentSkipListSet<Code>(
                codes);
        java.util.NavigableSet<Code> skipMiddle = skip.subSet(new Code(1), true, new Code(3), true);
        java.util.NavigableSet<Code> descending = codes.descendingSet();
        Code.COMPARED.set(0);
        Map<String, Object> b = bindings(e, middle, skipMiddle, descending);
        assertEquals(0, Code.COMPARED.get(), "no compareTo of the application ran");
        assertTrue(isHandle(b.get("0")) && isHandle(b.get("1")) && isHandle(b.get("2")), b.toString());
    }

    /** Fix wave (L2-F1): the encoding takes no lock of the application, so a held monitor cannot block it. */
    @Test
    void aLockHeldByTheApplicationDoesNotBlockTheEncoding() throws Exception {
        final BindingEncoder e = new BindingEncoder(ctx, 16384);
        final Vector<Object> vector = new Vector<Object>(Arrays.asList(1, 2));
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (vector) {
                    held.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "test-vector-holder");
        holder.setDaemon(true);
        holder.start();
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            long t0 = System.nanoTime();
            Map<String, Object> b = bindings(e, vector, Collections.unmodifiableList(vector));
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(ms < 1000, "the encoding waited " + ms + " ms for the application's lock");
            assertTrue(isHandle(b.get("0")) && isHandle(b.get("1")), b.toString());
        } finally {
            release.countDown();
            holder.join(10000);
        }
    }

    /**
     * Fix wave (L2-F3): D182's number rules. Only the JDK's boxes, {@code BigDecimal} and {@code BigInteger} are
     * numbers (an accumulator or an atomic is a handle, its {@code longValue()} never runs); a {@code BigInteger} or a
     * {@code BigDecimal} whose unscaled value is above 6,000 bits is cut ({@code null}, {@code truncated}) before any
     * text is made; a {@code BigDecimal} of extreme scale is its scientific text.
     */
    @Test
    void bigNumbersAreGuardedAndOnlyTheJdkBoxesAreNumbers() {
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        BigInteger huge = BigInteger.ONE.shiftLeft(4000000);
        LongAccumulator accumulator = new LongAccumulator(Math::max, 3);
        LongAdder adder = new LongAdder();
        Map<String, Object> out = e.encode("limit", new Object[] {huge, new BigDecimal(huge, 2),
            new BigDecimal("1E-20000000"), new BigDecimal("1E+20000000"), new BigDecimal("150.00"),
            new BigInteger("12345"), accumulator, adder, new AtomicInteger(4)});
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) out.get("bindings");
        assertTrue(b.containsKey("0") && b.get("0") == null, "a BigInteger above 6,000 bits is cut: " + b.keySet());
        assertTrue(b.containsKey("1") && b.get("1") == null, "so is a BigDecimal whose unscaled value is");
        assertEquals("1E-20000000", b.get("2"), "an extreme scale is the scientific text");
        assertEquals("1E+20000000", b.get("3"));
        assertEquals("150.00", b.get("4"));
        assertEquals("12345", b.get("5"));
        assertTrue(isHandle(b.get("6")), "an accumulator is a handle: " + b.get("6"));
        assertTrue(isHandle(b.get("7")), "an adder is a handle: " + b.get("7"));
        assertTrue(isHandle(b.get("8")), "an atomic is a handle: " + b.get("8"));
        assertEquals(Boolean.TRUE, out.get("truncated"));
    }

    @Test
    void secretKeysAndMaskPoliciesAndAThrowingPolicy() {
        Map<String, Object> creds = new LinkedHashMap<String, Object>();
        creds.put("staff", "100");
        creds.put("password", "hunter2");
        ctx.spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                assertEquals("rule.limit", sourceId);
                if ("binding1".equals(column) && value instanceof Long) {
                    return 0L;
                }
                return "STAFF-7".equals(value) ? "STAFF-*" : value;
            }
        });
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        Map<String, Object> b = bindings(e, "STAFF-7", 150, creds);
        assertEquals("STAFF-*", b.get("0"), "a string through the policy");
        assertEquals(0L, b.get("1"), "a number through the policy");
        Map<?, ?> m = (Map<?, ?>) b.get("2");
        assertEquals("100", m.get("staff"));
        assertEquals(BindingEncoder.REDACTED, m.get("password"));
        ctx.spi.register(MaskPolicy.class, new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                throw new IllegalStateException("broken policy");
            }
        });
        Map<String, Object> broken = bindings(e, "STAFF-7");
        assertEquals(BindingEncoder.REDACTED, broken.get("0"));
        assertEquals(1, e.maskFailures());
    }

    /**
     * Phase 8 (phase8-contracts.md 5.1, section 13.1 row 18): a {@code rule.fired} payload masked at the encoder as
     * {@code rule.<name>} passes the ring's second pass, {@code event:rule.fired}, unchanged under an idempotent policy,
     * so publishing it on the core's bus changes nothing.
     */
    @Test
    void aRuleFiredPayloadIsUnchangedByTheRingsSecondPassUnderAnIdempotentPolicy() {
        final java.util.List<String> sources = new java.util.ArrayList<String>();
        MaskPolicy idempotent = new MaskPolicy() {
            @Override
            public Object mask(String sourceId, String column, Object value) {
                sources.add(sourceId);
                return "STAFF-7".equals(value) || "STAFF-*".equals(value) ? "STAFF-*" : value;
            }
        };
        ctx.spi.register(MaskPolicy.class, idempotent);
        Map<String, Object> creds = new LinkedHashMap<String, Object>();
        creds.put("staff", "STAFF-7");
        creds.put("password", "hunter2");
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("rule", "limit");
        payload.put("class", "ai.skillroy.testjava.agent.byteman.Desk");
        payload.put("method", "lend");
        payload.putAll(e.encode("limit", new Object[] {"STAFF-7", 150, creds}));
        Map<?, ?> bound = (Map<?, ?>) payload.get("bindings");
        assertEquals("STAFF-*", bound.get("0"), "masked at the encoder");
        assertEquals(BindingEncoder.REDACTED, ((Map<?, ?>) bound.get("2")).get("password"));
        sources.clear();
        Object again = secondPass(java.util.Arrays.asList(idempotent), "event:rule.fired", null, payload);
        assertEquals(payload, again, "the second pass changes nothing");
        for (String s : sources) {
            assertEquals("event:rule.fired", s);
        }
    }

    /**
     * Phase 8 (phase8-contracts.md 5.1, section 13.1 row 18): what the core's ring pass does to a payload at publish,
     * by the contract's rule (this module cannot see the core's package-private pass): every registered policy, in
     * order, on every non-null scalar under {@code event:<topic>} and the dotted map keys (a list element adds no
     * segment). Returns the payload as the pass would store it; the core's own EventBusTest proves the pass itself.
     */
    @SuppressWarnings("unchecked")
    static Object secondPass(java.util.List<MaskPolicy> policies, String sourceId, String column, Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                out.put(e.getKey(), secondPass(policies, sourceId, column == null ? e.getKey()
                        : column + "." + e.getKey(), e.getValue()));
            }
            return out;
        }
        if (value instanceof java.util.List) {
            java.util.List<Object> out = new java.util.ArrayList<Object>();
            for (Object o : (java.util.List<Object>) value) {
                out.add(secondPass(policies, sourceId, column, o));
            }
            return out;
        }
        Object v = value;
        for (MaskPolicy p : policies) {
            if (v == null) {
                break;
            }
            v = p.mask(sourceId, column, v);
        }
        return v;
    }

    @Test
    void theBudgetIsOnTheInput() {
        BindingEncoder e = new BindingEncoder(ctx, 16384);
        StringBuilder million = new StringBuilder();
        for (int i = 0; i < 1000000; i++) {
            million.append('m');
        }
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 100; i++) {
            many.add(million.toString());
        }
        Map<String, Object> out = e.encode("limit", new Object[] {many, million.toString(), "after"});
        assertEquals(Boolean.TRUE, out.get("truncated"));
        assertTrue(ai.skillroy.testjava.protocol.Json.write(out).length() <= 16384 + 200, "bounded output");
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) out.get("bindings");
        assertFalse(b.containsKey("2"), "nothing is read once the budget is spent: " + b.keySet());
    }
}
