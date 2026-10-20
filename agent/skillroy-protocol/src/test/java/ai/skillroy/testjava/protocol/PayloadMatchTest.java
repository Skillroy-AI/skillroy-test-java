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

package ai.skillroy.testjava.protocol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The payload rule of an event wait (phase8-contracts.md 8.9, 13.1 row 35). */
class PayloadMatchTest {

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> has(Object scalar) {
        return Collections.singletonMap(PayloadMatch.HAS, scalar);
    }

    @Test
    void aScalarHoldsWhenItsTextEqualsThePayloadValuesText() {
        Map<String, Object> payload = map("state", "DEPOSIT", "qty", 2L, "price", 2.0, "open", Boolean.TRUE,
                "none", null);
        assertTrue(PayloadMatch.matches(payload, map("state", "DEPOSIT")));
        assertTrue(PayloadMatch.matches(payload, map("state", "DEPOSIT", "qty", 2L)), "every entry holds");
        assertFalse(PayloadMatch.matches(payload, map("state", "DEPOSIT", "qty", 3L)), "one entry fails the match");
        assertTrue(PayloadMatch.matches(payload, map("qty", "2")), "numbers by text");
        assertFalse(PayloadMatch.matches(payload, map("qty", 2.0)), "2 and 2.0 are unequal texts");
        assertFalse(PayloadMatch.matches(payload, map("price", 2L)));
        assertTrue(PayloadMatch.matches(payload, map("price", "2.0")));
        assertTrue(PayloadMatch.matches(payload, map("open", "true")), "booleans by text");
        assertTrue(PayloadMatch.matches(payload, map("open", Boolean.TRUE)));
        assertFalse(PayloadMatch.matches(payload, map("open", Boolean.FALSE)));
        assertTrue(PayloadMatch.matches(payload, map("none", null)), "null equals null");
        assertTrue(PayloadMatch.matches(payload, map("missing", null)), "a missing key reads as null, as today");
        assertFalse(PayloadMatch.matches(payload, map("missing", "x")));
        assertFalse(PayloadMatch.matches(payload, map("state", "deposit")), "case-sensitive");
    }

    @Test
    void aNullMatchHoldsOnAnyPayloadAndANullPayloadIsEmpty() {
        assertTrue(PayloadMatch.matches(map("a", 1L), null));
        assertTrue(PayloadMatch.matches(null, null));
        assertTrue(PayloadMatch.matches(map("a", 1L), map()), "an empty match holds");
        assertFalse(PayloadMatch.matches(null, map("a", 1L)));
        assertTrue(PayloadMatch.matches(null, map("a", null)));
        assertFalse(PayloadMatch.matches(null, map("a", has("x"))));
    }

    @Test
    void hasHoldsWhenAListElementHasTheScalarsText() {
        Map<String, Object> payload = map("validInputs", Arrays.<Object>asList("Key.DEPOSIT", "Key.RETURN"),
                "codes", Arrays.<Object>asList(1L, 2L, 30L),
                "nested", Arrays.<Object>asList(map("code", "x"), Arrays.<Object>asList("y")),
                "empty", Collections.emptyList(),
                "text", "Key.DEPOSIT and more",
                "number", 2L);
        assertTrue(PayloadMatch.matches(payload, map("validInputs", has("Key.DEPOSIT"))));
        assertTrue(PayloadMatch.matches(payload, map("validInputs", has("Key.RETURN"))));
        assertFalse(PayloadMatch.matches(payload, map("validInputs", has("Key.SIGN_OFF"))));
        assertFalse(PayloadMatch.matches(payload, map("validInputs", has("Key"))), "no substring meaning");
        assertTrue(PayloadMatch.matches(payload, map("codes", has(2L))), "numbers in a list");
        assertTrue(PayloadMatch.matches(payload, map("codes", has("30"))), "by text");
        assertFalse(PayloadMatch.matches(payload, map("codes", has(2.0))), "2.0 is not the text of 2");
        assertFalse(PayloadMatch.matches(payload, map("codes", has(3L))));
        assertFalse(PayloadMatch.matches(payload, map("nested", has("{code=x}"))), "a map element never equals");
        assertFalse(PayloadMatch.matches(payload, map("nested", has("[y]"))), "a list element never equals");
        assertFalse(PayloadMatch.matches(payload, map("empty", has("x"))));
        assertFalse(PayloadMatch.matches(payload, map("text", has("Key.DEPOSIT and more"))),
                "a scalar payload value is not a list");
        assertFalse(PayloadMatch.matches(payload, map("number", has(2L))));
        assertFalse(PayloadMatch.matches(payload, map("missing", has("x"))), "a missing value is not a list");
        assertFalse(PayloadMatch.matches(map("none", null), map("none", has("x"))), "nor is null");
        assertTrue(PayloadMatch.matches(payload, map("validInputs", has("Key.DEPOSIT"), "number", 2L)),
                "has beside a scalar entry");
        assertFalse(PayloadMatch.matches(payload, map("validInputs", has("Key.DEPOSIT"), "number", 3L)));
    }

    /**
     * Security fix S158 (security L2-S1): a scalar never equals a map, collection or array payload value, and the
     * value's text is never built (the agent matches under its event ring's lock, which publishers wait on).
     */
    @Test
    void aScalarNeverEqualsANestedPayloadValueAndItsTextIsNeverBuilt() {
        final AtomicInteger built = new AtomicInteger();
        Map<String, Object> nested = new LinkedHashMap<String, Object>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String toString() {
                built.incrementAndGet();
                throw new IllegalStateException("the whole subtree as text");
            }
        };
        nested.put("k", "v");
        List<Object> list = new ArrayList<Object>() {
            private static final long serialVersionUID = 1L;

            @Override
            public String toString() {
                built.incrementAndGet();
                return "[a]";
            }
        };
        list.add("a");
        Map<String, Object> payload = map("map", nested, "list", list, "array", new String[] {"a"});
        assertFalse(PayloadMatch.matches(payload, map("map", "{k=v}")));
        assertFalse(PayloadMatch.matches(payload, map("list", "[a]")), "not the list's text either");
        assertFalse(PayloadMatch.matches(payload, map("array", "a")));
        assertFalse(PayloadMatch.matches(payload, map("map", null)), "a map is not null");
        assertEquals(0, built.get(), "no nested value was turned into text");
        assertTrue(PayloadMatch.matches(payload, map("list", has("a"))), "has still reads the list");
    }

    @Test
    void anInvalidEntryNeverHolds() {
        Map<String, Object> payload = map("list", Arrays.<Object>asList("a", "b"), "map", map("k", "v"));
        assertFalse(PayloadMatch.matches(payload, map("list", Arrays.<Object>asList("a", "b"))),
                "a list value is invalid, not a whole-list text comparison");
        assertFalse(PayloadMatch.matches(payload, map("map", map("k", "v"))));
        assertFalse(PayloadMatch.matches(payload, map("list", has(null))));
        assertFalse(PayloadMatch.matches(payload, map("list", has(Arrays.<Object>asList("a")))));
        assertFalse(PayloadMatch.matches(payload, map("list", map("has", "a", "other", "b"))));
        assertFalse(PayloadMatch.matches(payload, map("list", map())));
    }

    @Test
    void problemNamesTheFirstInvalidEntry() {
        assertNull(PayloadMatch.problem(null));
        assertNull(PayloadMatch.problem(map()));
        assertNull(PayloadMatch.problem(map("state", "DEPOSIT", "qty", 2L, "open", Boolean.TRUE, "none", null)));
        assertNull(PayloadMatch.problem(map("validInputs", has("Key.DEPOSIT"), "state", "DEPOSIT")));
        assertNull(PayloadMatch.problem(map("codes", has(2L))));
        assertNull(PayloadMatch.problem(map("item", has("$item.code"))), "a reference is a string until resolved");

        String prefix = "'match.sources' must be a scalar or {has: scalar}, found ";
        assertEquals(prefix + "{has: a list}", PayloadMatch.problem(map("sources", has(Arrays.<Object>asList("x")))));
        assertEquals(prefix + "{has: a mapping}", PayloadMatch.problem(map("sources", has(map()))));
        assertEquals(prefix + "{has: null}", PayloadMatch.problem(map("sources", has(null))));
        assertEquals(prefix + "a mapping with keys [has, also]", PayloadMatch.problem(map("sources",
                map("has", "x", "also", "y"))));
        assertEquals(prefix + "a mapping with keys [in]", PayloadMatch.problem(map("sources",
                map("in", Arrays.<Object>asList("a", "b")))), "an unknown operator");
        assertEquals(prefix + "an empty mapping", PayloadMatch.problem(map("sources", map())));
        assertEquals(prefix + "a list", PayloadMatch.problem(map("sources", Arrays.<Object>asList("a", "b"))));
        assertEquals(prefix + "a list", PayloadMatch.problem(map("sources", new String[] {"a"})), "an array too");
        assertEquals(prefix + "a mapping with keys [code]", PayloadMatch.problem(map("sources", map("code", "x"))),
                "a map value");
        assertEquals(prefix + "a list", PayloadMatch.problem(map("state", "DEPOSIT", "sources",
                Arrays.<Object>asList("a"))), "after a valid entry");
    }

    @Test
    void problemQuotesALongKeyCut() {
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            key.append('k');
        }
        String reason = PayloadMatch.problem(map(key.toString(), Arrays.<Object>asList("a")));
        assertTrue(reason.startsWith("'match." + key.substring(0, 100) + "...'"), reason);
        assertTrue(reason.length() < 200, reason);
    }

    @Test
    void usesOperatorIsTrueForAnyMapValue() {
        assertFalse(PayloadMatch.usesOperator(null));
        assertFalse(PayloadMatch.usesOperator(map()));
        assertFalse(PayloadMatch.usesOperator(map("state", "DEPOSIT", "none", null)));
        assertFalse(PayloadMatch.usesOperator(map("list", Arrays.<Object>asList("a"))), "a list is invalid, not an"
                + " operator");
        assertTrue(PayloadMatch.usesOperator(map("state", "DEPOSIT", "validInputs", has("Key.DEPOSIT"))));
        assertTrue(PayloadMatch.usesOperator(map("x", map("in", "y"))), "an invalid operator object too");
        assertTrue(PayloadMatch.usesOperator(map("x", map())));
    }
}
