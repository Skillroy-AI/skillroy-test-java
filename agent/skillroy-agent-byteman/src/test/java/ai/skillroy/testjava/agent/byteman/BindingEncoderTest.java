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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
