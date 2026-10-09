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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
