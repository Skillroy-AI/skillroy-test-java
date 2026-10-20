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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The warning channel's wire type and the envelope's member (phase8-contracts.md 7.1, 13.1 row 29). */
class CallWarningTest {

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void codesAreBaseOrPackShapes() {
        assertEquals(6, CallWarning.BASE_CODES.size());
        assertTrue(CallWarning.BASE_CODES.containsAll(Arrays.asList("INPUT_QUEUED", "VALUE_COERCED", "VALUE_DROPPED",
                "ANSWERED_NESTED", "INVALID_WARNING", "TOO_MANY_WARNINGS")));
        assertEquals("VALUE_COERCED", new CallWarning("VALUE_COERCED", null, null).code());
        assertTrue(new CallWarning("lending.PASSWORD_REQUIRED", null, null).isPackCode());
        assertFalse(new CallWarning("SOMETHING_ELSE", null, null).isPackCode(), "a base shape, not in the set");
        assertTrue(CallWarning.isValidCode("A" + repeat('B', 63)));
        for (String bad : Arrays.asList(null, "", "value_coerced", "1CODE", "A" + repeat('B', 64), "Lending.X",
                "lending.x", "lending.", ".X", "a_b.X", "lending.X.Y", "VALUE COERCED")) {
            assertThrows(IllegalArgumentException.class, () -> new CallWarning(bad, null, null), String.valueOf(bad));
            assertFalse(CallWarning.isValidCode(bad));
        }
        assertTrue(CallWarning.isValidCode("pack-2.NAME_1"));
    }

    @Test
    void theMessageIsOnePrintableLineOfAtMost500Characters() {
        CallWarning w = new CallWarning("VALUE_COERCED", "taken\nas\t\"4005\" x", null);
        assertEquals("taken as \"4005\" x", w.message());
        assertEquals(500, new CallWarning("VALUE_COERCED", repeat('m', 900), null).message().length());
        assertNull(new CallWarning("VALUE_COERCED", null, null).message());
        assertEquals(map("code", "VALUE_COERCED"), new CallWarning("VALUE_COERCED", null, null).toJson(),
                "message and data only when present");
    }

    @Test
    void dataIsFrozenAndCappedAt2048Characters() {
        Map<String, Object> data = map("taken", "4005", "n", 2);
        CallWarning w = new CallWarning("VALUE_COERCED", "m", data);
        data.put("taken", "changed");
        assertEquals("4005", w.data().get("taken"), "frozen copy");
        assertEquals(2L, w.data().get("n"));
        assertThrows(UnsupportedOperationException.class, () -> w.data().put("x", 1));
        CallWarning big = new CallWarning("VALUE_COERCED", "m", map("text", repeat('x', 3000)));
        assertEquals(map("truncated", Boolean.TRUE), big.data());
        CallWarning nan = new CallWarning("VALUE_COERCED", "m", map("n", Double.NaN));
        assertEquals(map("truncated", Boolean.TRUE), nan.data(), "unrepresentable data is truncated, not thrown");
        assertTrue(Json.write(new CallWarning("VALUE_COERCED", "m", map("text", repeat('x', 2000))).toJson())
                .contains(repeat('x', 2000)));
    }

    @Test
    void aBadElementReadsAsInvalidWarning() {
        for (Object bad : Arrays.<Object>asList("VALUE_COERCED", 3L, Arrays.asList("x"), map("message", "no code"),
                map("code", "lower"), map("code", 7L), map("code", "VALUE_COERCED", "message", 4L),
                map("code", "VALUE_COERCED", "data", "text"))) {
            List<CallWarning> read = CallWarning.readAll(Collections.singletonList(bad));
            assertEquals(1, read.size());
            assertEquals(CallWarning.INVALID_WARNING, read.get(0).code(), String.valueOf(bad));
            assertTrue(read.get(0).data().get("raw") instanceof String);
        }
        CallWarning one = CallWarning.fromJson(map("code", "lower", "message", repeat('z', 400)));
        assertEquals(CallWarning.INVALID_WARNING, one.code());
        assertTrue(((String) one.data().get("raw")).length() <= 200);
        assertEquals(CallWarning.INVALID_WARNING, CallWarning.readAll("not a list").get(0).code());
        assertTrue(CallWarning.readAll(null).isEmpty());
    }

    @Test
    void readAllKeepsSixteenAndFoldsTheRest() {
        List<Object> raw = new ArrayList<Object>();
        for (int i = 0; i < 20; i++) {
            raw.add(map("code", "VALUE_DROPPED", "message", "w" + i));
        }
        List<CallWarning> read = CallWarning.readAll(raw);
        assertEquals(17, read.size());
        assertEquals("w15", read.get(15).message());
        assertEquals(CallWarning.TOO_MANY_WARNINGS, read.get(16).code());
        assertEquals(4L, read.get(16).data().get("dropped"));
    }

    @Test
    void aWarningRoundTrips() {
        CallWarning w = new CallWarning("lending.PASSWORD_REQUIRED", "the staff member needs a password",
                map("staff", "102", "nested", map("a", Arrays.asList(1L, "b"))));
        CallWarning back = CallWarning.fromJson(Json.parseObject(Json.write(w.toJson())));
        assertEquals(w, back);
        assertEquals(w.hashCode(), back.hashCode());
        assertTrue(w.toString().startsWith("CallWarning{"));
    }

    // ---- RpcResult's member ----

    @Test
    void anRpcResultWritesWarningsOnlyWhenItHasSome() {
        RpcResult none = new RpcResult("ok", null, 1, 2, 3, "t");
        assertFalse(none.toJson().containsKey("warnings"));
        assertTrue(none.warnings().isEmpty());
        assertEquals(none, new RpcResult("ok", null, 1, 2, 3, "t", Collections.<CallWarning>emptyList()));
        assertEquals(Json.write(none.toJson()), Json.write(new RpcResult("ok", null, 1, 2, 3, "t", null).toJson()),
                "the six-argument JSON text is unchanged");

        RpcResult some = new RpcResult(map("accepted", true), null, 1, 2, 3, "t",
                Arrays.asList(new CallWarning("VALUE_COERCED", "taken as 4005", map("taken", "4005")),
                        new CallWarning("VALUE_DROPPED", null, null)));
        Map<String, Object> json = some.toJson();
        assertEquals(Arrays.asList("value", "events", "seq", "durationMs", "thread", "warnings"),
                new ArrayList<String>(json.keySet()));
        RpcResult back = RpcResult.fromJson(Json.parseObject(Json.write(json)));
        assertEquals(some, back);
        assertEquals("VALUE_COERCED", back.warnings().get(0).code());
        assertEquals(2, back.warnings().size());
        assertFalse(some.equals(new RpcResult(map("accepted", true), null, 1, 2, 3, "t")));
    }

    @Test
    void aPhase7ShapedResultReadsWithNoWarnings() {
        RpcResult old = RpcResult.fromJson(Json.parseObject("{\"value\":{\"accepted\":true},\"events\":[],"
                + "\"seq\":{\"before\":3,\"after\":4},\"durationMs\":5,\"thread\":\"w\"}"));
        assertTrue(old.warnings().isEmpty());
        RpcResult bad = RpcResult.fromJson(Json.parseObject("{\"seq\":{\"before\":3,\"after\":4},"
                + "\"warnings\":[{\"code\":\"x\"},\"y\"]}"));
        assertEquals(2, bad.warnings().size(), "read leniently, never thrown");
        assertEquals(CallWarning.INVALID_WARNING, bad.warnings().get(1).code());
    }

    @Test
    void theConstructorFoldsMoreThanSixteen() {
        List<CallWarning> many = new ArrayList<CallWarning>();
        for (int i = 0; i < 18; i++) {
            many.add(new CallWarning("VALUE_DROPPED", "w" + i, null));
        }
        RpcResult result = new RpcResult(null, null, 0, 0, 0, null, many);
        assertEquals(17, result.warnings().size());
        assertEquals(2L, result.warnings().get(16).data().get("dropped"));
        RpcResult again = RpcResult.fromJson(Json.parseObject(Json.write(result.toJson())));
        assertEquals(result, again, "sixteen plus the fold survive a round trip unchanged");
    }
}
