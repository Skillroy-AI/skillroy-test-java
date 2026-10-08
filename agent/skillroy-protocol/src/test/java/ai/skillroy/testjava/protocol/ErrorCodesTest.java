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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorCodesTest {

    @Test
    void everyConstantMapsToItsOwnNameAndCodesAreUnique() throws Exception {
        Set<Integer> codes = new HashSet<Integer>();
        int constants = 0;
        for (Field field : ErrorCodes.class.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (field.getType() == int.class && Modifier.isPublic(mods) && Modifier.isStatic(mods)) {
                int code = field.getInt(null);
                assertEquals(field.getName(), ErrorCodes.name(code));
                assertTrue(codes.add(code), "duplicate code " + code);
                constants++;
            }
        }
        assertEquals(20, constants);
    }

    @Test
    void contractValues() {
        assertEquals(-32700, ErrorCodes.PARSE_ERROR);
        assertEquals(-32602, ErrorCodes.INVALID_PARAMS);
        assertEquals(-32001, ErrorCodes.TIMEOUT);
        assertEquals(-32008, ErrorCodes.SCRIPT_ERROR);
        assertEquals(-32011, ErrorCodes.CAPABILITY_UNAVAILABLE);
        assertEquals(-32032, ErrorCodes.NOT_READY);
        assertEquals(-32033, ErrorCodes.BUSY);
    }

    /** phase4-contracts.md section 8.1: a page script's error crosses the wire as an RpcError with its own code. */
    @Test
    void scriptErrorRoundTripsThroughTheCodecAsAnRpcError() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("browser", "b-1");
        data.put("message", "boom");
        data.put("stack", "Error: boom\n    at <anonymous>:1:7");
        RpcError error = new RpcError(ErrorCodes.SCRIPT_ERROR, "boom", data);
        String text = Json.write(error.toJson());
        assertEquals("{\"code\":-32008,\"message\":\"boom\",\"data\":{\"browser\":\"b-1\",\"message\":\"boom\","
                + "\"stack\":\"Error: boom\\n    at <anonymous>:1:7\"}}", text);
        RpcError back = RpcError.fromJson(Json.parseObject(text));
        assertEquals(error, back);
        assertEquals("SCRIPT_ERROR", ErrorCodes.name(back.code()));
        assertEquals("SCRIPT_ERROR", new RpcError(ErrorCodes.SCRIPT_ERROR, null, null).message());
    }

    @Test
    void unknownCodes() {
        assertEquals("UNKNOWN(42)", ErrorCodes.name(42));
        assertEquals("UNKNOWN(0)", ErrorCodes.name(0));
        assertEquals("UNKNOWN(-32000)", ErrorCodes.name(-32000));
    }
}
