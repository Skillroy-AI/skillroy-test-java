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
        assertEquals(19, constants);
    }

    @Test
    void contractValues() {
        assertEquals(-32700, ErrorCodes.PARSE_ERROR);
        assertEquals(-32602, ErrorCodes.INVALID_PARAMS);
        assertEquals(-32001, ErrorCodes.TIMEOUT);
        assertEquals(-32011, ErrorCodes.CAPABILITY_UNAVAILABLE);
        assertEquals(-32032, ErrorCodes.NOT_READY);
        assertEquals(-32033, ErrorCodes.BUSY);
    }

    @Test
    void unknownCodes() {
        assertEquals("UNKNOWN(42)", ErrorCodes.name(42));
        assertEquals("UNKNOWN(0)", ErrorCodes.name(0));
        assertEquals("UNKNOWN(-32000)", ErrorCodes.name(-32000));
    }
}
