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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Immutability and value semantics of the wire types, topic globs and JsonView coercions under awkward input. */
class ContractHardeningTest {

    // ---- wire types: frozen, always writable, equal to their round trip ----

    @Test
    void payloadObjectsThatAreNotJsonAreSnapshottedAsStrings() {
        StringBuilder mutableText = new StringBuilder("before");
        Date date = new Date(0);
        LongAdder adder = new LongAdder();
        adder.add(5);
        Map<Object, Object> oddKeys = new LinkedHashMap<Object, Object>();
        oddKeys.put(Integer.valueOf(1), "one");
        oddKeys.put(null, "none");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("text", mutableText);
        payload.put("date", date);
        payload.put("adder", adder);
        payload.put("char", Character.valueOf('c'));
        payload.put("bytes", new byte[] {1, 2});
        payload.put("chars", new char[] {'a'});
        payload.put("big", new BigDecimal("1.50"));
        payload.put("huge", BigInteger.ONE.shiftLeft(70));
        payload.put("small", BigInteger.TEN);
        payload.put("keys", oddKeys);
        payload.put("state", CapabilityState.DEGRADED);
        String dateText = date.toString();
        Event event = new Event(1, 2, "t", null, null, null, payload);
        mutableText.append("-after");
        date.setTime(86400000L * 365);
        adder.add(100);

        assertEquals("before", event.payload().get("text"));
        assertEquals(dateText, event.payload().get("date"));
        assertEquals(Long.valueOf(5), event.payload().get("adder"));
        assertEquals("c", event.payload().get("char"));
        assertEquals(Arrays.<Object>asList(1L, 2L), event.payload().get("bytes"));
        assertEquals(Arrays.<Object>asList("a"), event.payload().get("chars"));
        assertEquals(new BigDecimal("1.50"), event.payload().get("big"));
        assertEquals(BigInteger.ONE.shiftLeft(70), event.payload().get("huge"));
        assertEquals(Long.valueOf(10), event.payload().get("small"));
        assertEquals("DEGRADED", event.payload().get("state"));
        @SuppressWarnings("unchecked")
        Map<String, Object> keys = (Map<String, Object>) event.payload().get("keys");
        assertEquals(Arrays.asList("1", "null"), new ArrayList<String>(keys.keySet()));
        // Writing never fails for a constructed event, and what was written reads back as an equal payload shape.
        Event back = Event.fromJson(Json.parseObject(Json.write(event.toJson())));
        assertEquals("before", back.payload().get("text"));
        assertEquals(Double.valueOf(1.5), back.payload().get("big"));
    }

    @Test
    void wireTypesRejectValuesThatCouldNeverBeWritten() {
        Map<String, Object> nan = Collections.<String, Object>singletonMap("x", Double.valueOf(Double.NaN));
        assertThrows(JsonException.class, () -> new RpcResult(nan, null, 0, 0, 0, null));
        assertThrows(JsonException.class, () -> new RpcResult(Float.valueOf(Float.POSITIVE_INFINITY), null, 0, 0, 0,
                null));
        assertThrows(JsonException.class, () -> new RpcError(1, "m", nan));
        assertThrows(JsonException.class, () -> new RpcException(1, "m", nan));
        assertThrows(JsonException.class,
                () -> new PrimitiveDescriptor("a.b", null, nan, null, null, 0, null, false, false));
        List<Object> cycle = new ArrayList<Object>();
        cycle.add(cycle);
        assertThrows(JsonException.class, () -> new RpcResult(cycle, null, 0, 0, 0, null));
    }

    @Test
    void toJsonOfAWireTypeCannotBeUsedToMutateIt() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("list", new ArrayList<Object>(Arrays.<Object>asList("a")));
        Event event = new Event(7, 8, "t", "s", "c", "tp", payload);
        Map<String, Object> json = event.toJson();
        json.put("seq", Long.valueOf(99));
        json.remove("topic");
        @SuppressWarnings("unchecked")
        Map<String, Object> exposed = (Map<String, Object>) json.get("payload");
        assertThrows(UnsupportedOperationException.class, () -> exposed.put("x", "y"));
        @SuppressWarnings("unchecked")
        List<Object> exposedList = (List<Object>) exposed.get("list");
        assertThrows(UnsupportedOperationException.class, () -> exposedList.add("b"));
        assertEquals(7, event.seq());
        assertEquals("t", event.toJson().get("topic"));

        RpcResult result = new RpcResult(payload, Collections.singletonList(event), 1, 2, 3, "w");
        Map<String, Object> resultJson = result.toJson();
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) resultJson.get("events");
        events.clear();
        assertEquals(1, result.events().size());
        assertEquals(1, ((List<?>) result.toJson().get("events")).size());

        RpcError error = new RpcError(ErrorCodes.NOT_FOUND, "gone", payload);
        payload.put("later", "x");
        assertFalse(error.data().containsKey("later"));
        assertThrows(UnsupportedOperationException.class, () -> error.data().clear());
    }

    @Test
    void equalityFollowsTheWireFormNotTheJavaTypesPassedIn() {
        Map<String, Object> ints = new LinkedHashMap<String, Object>();
        ints.put("n", Integer.valueOf(3));
        ints.put("f", Float.valueOf(0.1f));
        ints.put("l", new int[] {1, 2});
        Map<String, Object> longs = new LinkedHashMap<String, Object>();
        longs.put("n", Long.valueOf(3));
        longs.put("f", Double.valueOf(0.1));
        longs.put("l", Arrays.<Object>asList(1L, 2L));
        Event a = new Event(1, 2, "t", null, null, null, ints);
        Event b = new Event(1, 2, "t", null, null, null, longs);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, Event.fromJson(Json.parseObject(Json.write(a.toJson()))));
        assertNotEquals(a, new Event(1, 2, "t", "", null, null, ints));
        assertNotEquals(a, new Event(2, 2, "t", null, null, null, ints));
        assertEquals(new RpcResult(ints, null, 1, 2, 3, null), new RpcResult(longs, new ArrayList<Event>(), 1, 2, 3,
                null));
        assertEquals(new RpcError(-1, "m", ints), new RpcError(-1, "m", longs));
        assertNotEquals(new RpcError(-1, "m", ints), new RpcError(-1, "m", null));
        assertEquals(new Capability("a", CapabilityState.OK, null, "s"), new Capability("a", CapabilityState.OK, null,
                "s"));
        assertNotEquals(new Capability("a", CapabilityState.OK, null, "s"),
                new Capability("b", CapabilityState.OK, null, "s"));
    }

    @Test
    void theDeepestValueAWireTypeAcceptsStillFitsEveryEnvelope() {
        Object deepest = "leaf";
        for (int i = 0; i < Coerce.MAX_VALUE_DEPTH; i++) {
            deepest = Collections.singletonList(deepest);
        }
        Object tooDeep = Collections.singletonList(deepest);
        assertThrows(JsonException.class, () -> new RpcResult(tooDeep, null, 0, 0, 0, null));
        assertThrows(JsonException.class,
                () -> new Event(1, 2, "t", null, null, null, Collections.singletonMap("x", tooDeep)));

        // payload -> event -> events[] -> result -> response -> batch: the worst case the protocol produces.
        Map<String, Object> payload = Collections.<String, Object>singletonMap("x", ((List<?>) deepest).get(0));
        Event event = new Event(1, 2, "t", null, null, null, payload);
        RpcResult result = new RpcResult(deepest, Collections.singletonList(event), 0, 1, 0, null);
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", Long.valueOf(1));
        response.put("result", result.toJson());
        String batch = Json.write(Collections.singletonList(response));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) ((List<?>) Json.parse(batch)).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultJson = (Map<String, Object>) back.get("result");
        assertEquals(result, RpcResult.fromJson(resultJson));
        assertEquals(batch, Json.write(Json.parse(batch)));
    }

    // ---- TopicFilter ----

    @Test
    void globsBacktrackCorrectly() {
        assertTrue(TopicFilter.parse("a*b*c").matches("aXbXbXc"));
        assertTrue(TopicFilter.parse("a*b*c").matches("abc"));
        assertFalse(TopicFilter.parse("a*b*c").matches("abcbd"));
        assertFalse(TopicFilter.parse("a*b*c").matches("acb"));
        assertTrue(TopicFilter.parse("*.printed").matches("slip.printed"));
        assertTrue(TopicFilter.parse("*.printed").matches(".printed"));
        assertFalse(TopicFilter.parse("*.printed").matches("slip.printed.x"));
        assertTrue(TopicFilter.parse("window.*").matches("window."));
        assertFalse(TopicFilter.parse("window.*").matches("window"));
        assertFalse(TopicFilter.parse("window.*").matches("windows.opened"));
        assertTrue(TopicFilter.parse("**").matches(""));
        assertTrue(TopicFilter.parse("a**b").matches("ab"));
        assertTrue(TopicFilter.parse("*a").matches("aaa"));
        assertFalse(TopicFilter.parse("*a").matches("aab"));
        assertFalse(TopicFilter.parse("a").matches(""));
        assertFalse(TopicFilter.parse("a").matches("A"));
        assertFalse(TopicFilter.parse("a.b").matches("aXb"));
        assertFalse(TopicFilter.parse("a?b").matches("aXb"));
        assertTrue(TopicFilter.parse("a?b,[x]").matches("[x]"));
        assertTrue(TopicFilter.parse("state.changed, input.*").matches("input.acked"));
        assertFalse(TopicFilter.parse("state.changed, input.*").matches("prompt.waiting"));
    }

    @Test
    void pathologicalGlobsFinishQuickly() {
        char[] many = new char[20000];
        Arrays.fill(many, 'a');
        String text = new String(many);
        TopicFilter filter = TopicFilter.parse("*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*b");
        long start = System.nanoTime();
        assertFalse(filter.matches(text));
        assertTrue(filter.matches(text + "b"));
        assertTrue((System.nanoTime() - start) < 5_000_000_000L);
    }

    @Test
    void emptyFiltersAndRoundTrips() {
        for (String empty : new String[] {null, "", " ", ",", " , ,", "*", "a,*", " * "}) {
            TopicFilter filter = TopicFilter.parse(empty);
            assertTrue(filter.matchesEverything(), String.valueOf(empty));
            assertTrue(filter.matches("anything.at.all"));
            assertTrue(filter.matches(""));
            assertTrue(filter.matches(null));
        }
        assertTrue(TopicFilter.of(null).matchesEverything());
        assertTrue(TopicFilter.of(Collections.<String>emptyList()).matchesEverything());
        assertTrue(TopicFilter.of(Arrays.asList(null, " ")).matchesEverything());
        assertFalse(TopicFilter.parse("a.*").matches(null));
        for (String text : new String[] {"", "a", "a.b, c.* ,a.b", "*", "x,*,y", " window.* ,, input.acked "}) {
            TopicFilter filter = TopicFilter.parse(text);
            assertEquals(filter, TopicFilter.parse(filter.toString()));
            assertEquals(filter.hashCode(), TopicFilter.parse(filter.toString()).hashCode());
            assertEquals(filter, TopicFilter.of(filter.globs()));
        }
        assertEquals("a.b,c.*", TopicFilter.of(Arrays.asList("a.b", "c.*, a.b")).toString());
        assertThrows(UnsupportedOperationException.class, () -> TopicFilter.parse("a").globs().add("b"));
    }

    // ---- JsonView ----

    @Test
    void jsonViewCoercionEdges() {
        Map<String, Object> map = Json.parseObject("{\"i\":7,\"d\":7.0,\"frac\":7.5,\"big\":1e19,\"s\":\" 42 \","
                + "\"exp\":\"1e3\",\"empty\":\"\",\"word\":\"seven\",\"t\":true,\"ts\":\"TRUE\",\"nul\":null,"
                + "\"o\":{\"p\":{\"q\":[1]}},\"l\":[1,\"a\"],\"neg\":-0.0,\"huge\":\"1e400\"}");
        JsonView v = JsonView.of(map);
        assertEquals(7, v.num("i", 0));
        assertEquals(7, v.num("d", 0));
        assertEquals(42, v.num("s", 0));
        assertEquals(1000, v.num("exp", 0));
        assertEquals(0, v.num("neg", 5));
        assertEquals(9, v.num("nul", 9));
        assertEquals(9, v.num("absent", 9));
        assertInvalid(() -> v.num("frac", 0), "frac");
        assertInvalid(() -> v.num("big", 0), "big");
        assertInvalid(() -> v.num("empty", 0), "empty");
        assertInvalid(() -> v.num("word", 0), "word");
        assertInvalid(() -> v.num("t", 0), "t");
        assertInvalid(() -> v.num("o", 0), "o");
        assertInvalid(() -> v.num("l", 0), "l");
        assertInvalid(() -> v.requireNum("nul"), "nul");
        assertInvalid(() -> v.requireNum("absent"), "absent");
        assertEquals(7.5, v.dbl("frac", 0));
        assertEquals(1e19, v.dbl("big", 0));
        assertEquals(42.0, v.dbl("s", 0));
        assertInvalid(() -> v.dbl("huge", 0), "huge");
        assertInvalid(() -> v.dbl("t", 0), "t");
        assertInvalid(() -> v.dbl("word", 0), "word");
        assertEquals("7", v.str("i"));
        assertEquals("7.0", v.str("d"));
        assertEquals("10000000000000000000.0", v.str("big"));
        assertEquals("", v.requireStr("empty"));
        assertNull(v.str("nul"));
        assertEquals("x", v.str("nul", "x"));
        assertInvalid(() -> v.str("o"), "o");
        assertInvalid(() -> v.str("l"), "l");
        assertInvalid(() -> v.requireStr("nul"), "nul");
        assertTrue(v.bool("t", false));
        assertTrue(v.bool("ts", false));
        assertTrue(v.bool("nul", true));
        assertInvalid(() -> v.bool("i", false), "i");
        assertInvalid(() -> v.bool("word", false), "word");
        assertFalse(v.has("nul"));
        assertFalse(v.has("absent"));
        assertTrue(v.has("empty"));
        assertEquals(Arrays.<Object>asList(1L), v.obj("o").obj("p").list("q"));
        assertTrue(v.obj("absent").obj("deeper").raw().isEmpty());
        assertTrue(v.obj("nul").list("x").isEmpty());
        assertInvalid(() -> v.obj("o").obj("p").requireStr("z"), "o.p.z");
        assertInvalid(() -> v.obj("absent").obj("deeper").requireNum("z"), "absent.deeper.z");
        assertInvalid(() -> v.obj("i"), "i");
        assertInvalid(() -> v.obj("l"), "l");
        assertInvalid(() -> v.list("o"), "o");
        assertInvalid(() -> v.list("word"), "word");
        assertThrows(UnsupportedOperationException.class, () -> v.raw().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> v.list("l").add("x"));
        assertTrue(JsonView.of(null).raw().isEmpty());
        assertEquals(Long.valueOf(7), v.get("i"));
        assertNull(v.get("absent"));
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable call, String param) {
        RpcException e = assertThrows(RpcException.class, call);
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertEquals(param, e.error().data().get("param"));
        assertTrue(e.getMessage().contains("'" + param + "'"), e.getMessage());
        assertInstanceOf(String.class, Json.write(e.error().toJson()));
    }
}
