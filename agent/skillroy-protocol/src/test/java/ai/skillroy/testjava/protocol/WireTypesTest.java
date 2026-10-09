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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireTypesTest {

    // ---- Event ----

    private static Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("from", "IDLE");
        payload.put("to", "LOAN");
        payload.put("n", Integer.valueOf(3));
        payload.put("ratio", Float.valueOf(0.5f));
        payload.put("tags", new ArrayList<Object>(Arrays.asList("a", Integer.valueOf(1))));
        payload.put("mode", LoadMode.ATTACH);
        return payload;
    }

    @Test
    void eventRoundTripsThroughJsonText() {
        Event event = new Event(7, 1790000000000L, "state.changed", "lending", "c-1", "00-abc-def-01", payload());
        String text = Json.write(event.toJson());
        assertEquals("{\"seq\":7,\"ts\":1790000000000,\"topic\":\"state.changed\",\"source\":\"lending\","
                + "\"correlationId\":\"c-1\",\"traceparent\":\"00-abc-def-01\",\"payload\":{\"from\":\"IDLE\","
                + "\"to\":\"LOAN\",\"n\":3,\"ratio\":0.5,\"tags\":[\"a\",1],\"mode\":\"ATTACH\"}}", text);
        Event back = Event.fromJson(Json.parseObject(text));
        assertEquals(event, back);
        assertEquals(event.hashCode(), back.hashCode());
        assertEquals(event.toString(), back.toString());
    }

    @Test
    void eventNormalisesAndFreezesItsPayload() {
        Map<String, Object> source = payload();
        Event event = new Event(1, 2, "t", null, null, null, source);
        source.put("from", "CHANGED");
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) source.get("tags");
        tags.add("later");
        assertEquals("IDLE", event.payload().get("from"));
        assertEquals(Arrays.<Object>asList("a", 1L), event.payload().get("tags"));
        assertInstanceOf(Long.class, event.payload().get("n"));
        assertInstanceOf(Double.class, event.payload().get("ratio"));
        assertEquals("ATTACH", event.payload().get("mode"));
        assertThrows(UnsupportedOperationException.class, () -> event.payload().put("x", 1));
        @SuppressWarnings("unchecked")
        List<Object> frozenTags = (List<Object>) event.payload().get("tags");
        assertThrows(UnsupportedOperationException.class, () -> frozenTags.add("x"));
    }

    @Test
    void eventOptionalFieldsAreWrittenAsNullAndReadBack() {
        Event event = new Event(1, 2, "t", null, null, null, null);
        assertTrue(event.payload().isEmpty());
        assertEquals("{\"seq\":1,\"ts\":2,\"topic\":\"t\",\"source\":null,\"correlationId\":null,"
                + "\"traceparent\":null,\"payload\":{}}", Json.write(event.toJson()));
        assertEquals(event, Event.fromJson(Json.parseObject(Json.write(event.toJson()))));
        Map<String, Object> minimal = new LinkedHashMap<String, Object>();
        minimal.put("seq", Integer.valueOf(1));
        minimal.put("ts", Integer.valueOf(2));
        minimal.put("topic", "t");
        assertEquals(event, Event.fromJson(minimal));
    }

    @Test
    void eventFromJsonRejectsWrongShapes() {
        assertMessage(assertThrows(JsonException.class,
                () -> Event.fromJson(Json.parseObject("{\"seq\":1,\"ts\":2}"))),
                "Event: missing required field \"topic\"");
        assertMessage(assertThrows(JsonException.class,
                () -> Event.fromJson(Json.parseObject("{\"seq\":\"x\",\"ts\":2,\"topic\":\"t\"}"))),
                "Event: field \"seq\" must be an integer but was a string");
        assertMessage(assertThrows(JsonException.class,
                () -> Event.fromJson(Json.parseObject("{\"seq\":1,\"ts\":2,\"topic\":\"t\",\"payload\":[]}"))),
                "Event: field \"payload\" must be an object but was an array");
        assertMessage(assertThrows(JsonException.class,
                () -> Event.fromJson(Json.parseObject("{\"seq\":1.5,\"ts\":2,\"topic\":\"t\"}"))),
                "must be an integer");
        assertMessage(assertThrows(JsonException.class,
                () -> Event.fromJson(Json.parseObject("{\"seq\":true,\"ts\":2,\"topic\":\"t\"}"))),
                "must be an integer but was a boolean");
        assertThrows(JsonException.class, () -> Event.fromJson(null));
        assertThrows(NullPointerException.class, () -> new Event(1, 2, null, null, null, null, null));
    }

    @Test
    void eventRejectsPayloadsThatCouldNeverBeWritten() {
        Map<String, Object> nan = new LinkedHashMap<String, Object>();
        nan.put("x", Double.NaN);
        assertMessage(assertThrows(JsonException.class, () -> new Event(1, 2, "t", null, null, null, nan)), "NaN");
        Map<String, Object> infinite = new LinkedHashMap<String, Object>();
        infinite.put("x", Arrays.<Object>asList(Float.NEGATIVE_INFINITY));
        assertMessage(assertThrows(JsonException.class, () -> new Event(1, 2, "t", null, null, null, infinite)),
                "Infinity");
        Map<String, Object> cyclic = new LinkedHashMap<String, Object>();
        cyclic.put("self", cyclic);
        assertMessage(assertThrows(JsonException.class, () -> new Event(1, 2, "t", null, null, null, cyclic)),
                "nesting depth exceeds 184");
    }

    // ---- RpcError / RpcException ----

    @Test
    void rpcErrorRoundTripsWithAndWithoutData() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("reason", "headless");
        RpcError error = new RpcError(ErrorCodes.CAPABILITY_UNAVAILABLE, "capability UNAVAILABLE", data);
        String text = Json.write(error.toJson());
        assertEquals("{\"code\":-32011,\"message\":\"capability UNAVAILABLE\",\"data\":{\"reason\":\"headless\"}}",
                text);
        assertEquals(error, RpcError.fromJson(Json.parseObject(text)));

        RpcError bare = new RpcError(ErrorCodes.TIMEOUT, "timed out", null);
        assertEquals("{\"code\":-32001,\"message\":\"timed out\"}", Json.write(bare.toJson()));
        assertFalse(bare.toJson().containsKey("data"));
        assertNull(RpcError.fromJson(bare.toJson()).data());
        assertEquals(bare, RpcError.fromJson(Json.parseObject(Json.write(bare.toJson()))));
        assertNotEquals(error, bare);
    }

    @Test
    void rpcErrorDefaultsAndLenientData() {
        assertEquals("NOT_FOUND", new RpcError(ErrorCodes.NOT_FOUND, null, null).message());
        RpcError wrapped = RpcError.fromJson(Json.parseObject("{\"code\":-32603,\"message\":\"x\",\"data\":\"str\"}"));
        assertEquals(Collections.singletonMap("value", "str"), wrapped.data());
        RpcError noMessage = RpcError.fromJson(Json.parseObject("{\"code\":-32601}"));
        assertEquals("METHOD_NOT_FOUND", noMessage.message());
        assertThrows(JsonException.class, () -> RpcError.fromJson(Json.parseObject("{\"message\":\"x\"}")));
        assertThrows(JsonException.class,
                () -> RpcError.fromJson(Json.parseObject("{\"code\":4294967296,\"message\":\"x\"}")));
    }

    @Test
    void rpcErrorDataIsAFrozenCopy() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("k", "v");
        RpcError error = new RpcError(1, "m", data);
        data.put("k", "changed");
        assertEquals("v", error.data().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> error.data().put("x", 1));
    }

    @Test
    void rpcExceptionCarriesItsError() {
        Map<String, Object> data = Collections.<String, Object>singletonMap("state", "IDLE");
        RpcException e = new RpcException(ErrorCodes.INPUT_REJECTED, "not valid now", data);
        assertEquals("not valid now", e.getMessage());
        assertEquals(ErrorCodes.INPUT_REJECTED, e.error().code());
        assertEquals("not valid now", e.error().message());
        assertEquals("IDLE", e.error().data().get("state"));
        assertTrue(e.toString().contains("INPUT_REJECTED"));
        RpcException plain = new RpcException(ErrorCodes.NOT_READY, null);
        assertEquals("NOT_READY", plain.getMessage());
        assertNull(plain.error().data());
    }

    // ---- RpcResult ----

    @Test
    void rpcResultUsesTheNestedSeqWireShape() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("accepted", Boolean.TRUE);
        value.put("ackMs", Integer.valueOf(38));
        Map<String, Object> eventPayload = new LinkedHashMap<String, Object>();
        eventPayload.put("from", "signedOff");
        Event event = new Event(1042, 5, "state.changed", "lending", "c-17", null, eventPayload);
        RpcResult result = new RpcResult(value, Collections.singletonList(event), 1041, 1043, 41, "lending-worker");
        String text = Json.write(result.toJson());
        assertEquals("{\"value\":{\"accepted\":true,\"ackMs\":38},\"events\":[{\"seq\":1042,\"ts\":5,"
                + "\"topic\":\"state.changed\",\"source\":\"lending\",\"correlationId\":\"c-17\",\"traceparent\":null,"
                + "\"payload\":{\"from\":\"signedOff\"}}],\"seq\":{\"before\":1041,\"after\":1043},"
                + "\"durationMs\":41,\"thread\":\"lending-worker\"}", text);
        RpcResult back = RpcResult.fromJson(Json.parseObject(text));
        assertEquals(result, back);
        assertEquals(result.hashCode(), back.hashCode());
        assertEquals(1041, back.seqBefore());
        assertEquals(1043, back.seqAfter());
        assertEquals(41, back.durationMs());
        assertEquals("lending-worker", back.thread());
        assertEquals(event, back.events().get(0));
    }

    @Test
    void rpcResultScalarAndNullValues() {
        RpcResult scalar = new RpcResult("ok", null, 0, 0, 0, null);
        assertEquals(scalar, RpcResult.fromJson(Json.parseObject(Json.write(scalar.toJson()))));
        assertTrue(scalar.events().isEmpty());
        RpcResult nothing = RpcResult.fromJson(Json.parseObject("{\"seq\":{\"before\":3,\"after\":4}}"));
        assertNull(nothing.value());
        assertTrue(nothing.events().isEmpty());
        assertEquals(0, nothing.durationMs());
        assertNull(nothing.thread());
    }

    @Test
    void rpcResultIsImmutable() {
        List<Object> value = new ArrayList<Object>(Arrays.<Object>asList(1, 2));
        List<Event> events = new ArrayList<Event>();
        events.add(new Event(1, 1, "t", null, null, null, null));
        RpcResult result = new RpcResult(value, events, 0, 1, 0, null);
        value.add(3);
        events.clear();
        assertEquals(Arrays.<Object>asList(1L, 2L), result.value());
        assertEquals(1, result.events().size());
        assertThrows(UnsupportedOperationException.class, () -> result.events().clear());
        assertThrows(NullPointerException.class,
                () -> new RpcResult(null, Collections.<Event>singletonList(null), 0, 0, 0, null));
    }

    @Test
    void rpcResultFromJsonRejectsWrongShapes() {
        assertMessage(assertThrows(JsonException.class, () -> RpcResult.fromJson(Json.parseObject("{\"value\":1}"))),
                "RpcResult: missing required field \"seq\"");
        assertMessage(assertThrows(JsonException.class,
                () -> RpcResult.fromJson(Json.parseObject("{\"seq\":{\"before\":1}}"))),
                "RpcResult.seq: missing required field \"after\"");
        assertMessage(assertThrows(JsonException.class,
                () -> RpcResult.fromJson(Json.parseObject("{\"seq\":{\"before\":1,\"after\":2},\"events\":[1]}"))),
                "must be an object");
    }

    // ---- Capability ----

    @Test
    void capabilityRoundTrips() {
        Capability full = new Capability("swing.tree", CapabilityState.DEGRADED, "slow", "paint");
        assertEquals("{\"state\":\"DEGRADED\",\"reason\":\"slow\",\"strategy\":\"paint\"}", Json.write(full.toJson()));
        assertEquals(full, Capability.fromJson("swing.tree", Json.parseObject(Json.write(full.toJson()))));
        Capability bare = new Capability("x.y", CapabilityState.OK, null, null);
        assertEquals("{\"state\":\"OK\",\"reason\":null,\"strategy\":null}", Json.write(bare.toJson()));
        assertEquals(bare, Capability.fromJson("x.y", bare.toJson()));
        assertNotEquals(bare, new Capability("x.z", CapabilityState.OK, null, null));
        assertEquals(bare.hashCode(), new Capability("x.y", CapabilityState.OK, null, null).hashCode());
        assertTrue(bare.toString().contains("x.y"));
    }

    @Test
    void capabilityFromJsonRejectsWrongShapes() {
        assertThrows(JsonException.class, () -> Capability.fromJson("a", Json.parseObject("{}")));
        assertMessage(assertThrows(JsonException.class,
                () -> Capability.fromJson("a", Json.parseObject("{\"state\":\"BROKEN\"}"))),
                "Capability: unknown state \"BROKEN\"");
        assertThrows(NullPointerException.class, () -> new Capability(null, CapabilityState.OK, null, null));
        assertThrows(NullPointerException.class, () -> new Capability("a", null, null, null));
    }

    // ---- PrimitiveDescriptor ----

    @Test
    void primitiveDescriptorRoundTrips() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("type", "object");
        params.put("properties", Collections.singletonMap("n", Collections.singletonMap("type", "integer")));
        params.put("required", Collections.emptyList());
        PrimitiveDescriptor descriptor = new PrimitiveDescriptor("agent.log", "Recent log lines", params,
                Collections.<String, Object>singletonMap("type", "object"), "CALLER", 10000, "core.log", false,
                false);
        String text = Json.write(descriptor.toJson());
        assertEquals("{\"name\":\"agent.log\",\"description\":\"Recent log lines\",\"paramsSchema\":{\"type\":"
                + "\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}},\"required\":[]},\"resultSchema\":"
                + "{\"type\":\"object\"},\"thread\":\"CALLER\",\"timeoutMs\":10000,\"capabilityId\":\"core.log\","
                + "\"mutating\":false,\"destructive\":false}", text);
        PrimitiveDescriptor back = PrimitiveDescriptor.fromJson(Json.parseObject(text));
        assertEquals(descriptor, back);
        assertEquals(descriptor.hashCode(), back.hashCode());
        assertThrows(UnsupportedOperationException.class, () -> back.paramsSchema().put("x", 1));
    }

    /** phase6-contracts.md 12.9, D156 (security fix S42): {@code script} is optional, false when absent. */
    @Test
    void primitiveDescriptorCarriesTheScriptFlagOnlyWhenSet() {
        PrimitiveDescriptor script = new PrimitiveDescriptor("web.waitFlag", "flag", null, null, "WORKER", 10000,
                "web.evaluate", false, false, true);
        assertTrue(script.script());
        String text = Json.write(script.toJson());
        assertTrue(text.endsWith("\"mutating\":false,\"destructive\":false,\"script\":true}"), text);
        PrimitiveDescriptor back = PrimitiveDescriptor.fromJson(Json.parseObject(text));
        assertEquals(script, back);
        assertTrue(back.script());
        PrimitiveDescriptor plain = new PrimitiveDescriptor("web.waitFlag", "flag", null, null, "WORKER", 10000,
                "web.evaluate", false, false);
        assertFalse(plain.script());
        assertFalse(Json.write(plain.toJson()).contains("\"script\""), "written only when set");
        assertFalse(plain.equals(script), "the flag is part of the descriptor's identity");
        assertFalse(PrimitiveDescriptor.fromJson(Json.parseObject("{\"name\":\"a.b\"}")).script(),
                "an older agent's entry without the key reads as false");
        assertFalse(PrimitiveDescriptor.fromJson(Json.parseObject("{\"name\":\"a.b\",\"script\":false}")).script());
        assertMessage(assertThrows(JsonException.class,
                () -> PrimitiveDescriptor.fromJson(Json.parseObject("{\"name\":\"a.b\",\"script\":\"yes\"}"))),
                "field \"script\" must be a boolean");
    }

    @Test
    void primitiveDescriptorReadsMinimalJsonWithDefaults() {
        PrimitiveDescriptor minimal = PrimitiveDescriptor.fromJson(Json.parseObject("{\"name\":\"a.b\"}"));
        assertEquals("a.b", minimal.name());
        assertNull(minimal.description());
        assertNull(minimal.paramsSchema());
        assertNull(minimal.resultSchema());
        assertNull(minimal.thread());
        assertEquals(0, minimal.timeoutMs());
        assertNull(minimal.capabilityId());
        assertFalse(minimal.mutating());
        assertFalse(minimal.destructive());
        assertEquals(minimal, PrimitiveDescriptor.fromJson(Json.parseObject(Json.write(minimal.toJson()))));
        assertMessage(assertThrows(JsonException.class,
                () -> PrimitiveDescriptor.fromJson(Json.parseObject("{\"description\":\"x\"}"))),
                "PrimitiveDescriptor: missing required field \"name\"");
        assertMessage(assertThrows(JsonException.class,
                () -> PrimitiveDescriptor.fromJson(Json.parseObject("{\"name\":\"a.b\",\"mutating\":1}"))),
                "field \"mutating\" must be a boolean");
    }

    private static void assertMessage(JsonException e, String fragment) {
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
        assertEquals(-1, e.offset());
    }
}
