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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule for a capture's bytes on a text route (phase6 security fix, pass 2, P2-L6-F2, P2-L6-F3, P2-L6-S1;
 * D159 completed): a capture keeps its shape and size, never its bytes, in a value or a text.
 */
class ImagesTest {

    /** A 1 x 1 PNG (70 bytes), base64. */
    static final String PNG_1X1 = FakeAgentConnection.PNG_1X1;

    private static Map<String, Object> capture() {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("format", "png");
        capture.put("width", 1L);
        capture.put("height", 1L);
        capture.put("base64", PNG_1X1);
        return capture;
    }

    @Test
    void aCapturesBytesAreElidedAndItsShapeKept() {
        assertEquals(70, Base64.getDecoder().decode(PNG_1X1).length);
        Map<?, ?> elided = (Map<?, ?>) Images.elided(capture());
        assertEquals(Map.of("format", "png", "width", 1L, "height", 1L, "base64", "<png: 70 bytes>"), elided);
        Object nested = Map.of("a", List.of(Map.of("b", Map.of("c", capture()))), "n", 7L);
        String json = Json.write(Images.elided(nested));
        assertFalse(json.contains("iVBORw0KGgo"), json);
        assertTrue(json.contains("\"base64\":\"<png: 70 bytes>\""), json);
        assertTrue(json.contains("\"n\":7"), json);
    }

    @Test
    void anyDepthAnyCollectionAndAnyFormat() {
        Object deep = capture();
        for (int i = 0; i < 150; i++) {
            deep = Map.of("l" + i, deep);
        }
        assertFalse(Json.write(Images.elided(deep)).contains("iVBORw0KGgo"));
        Object inSet = Set.of(capture());
        assertEquals(List.of(Images.elided(capture())), Images.elided(inSet));
        String jpeg = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4});
        assertEquals("<jpeg: 6 bytes>", ((Map<?, ?>) Images.elided(Map.of("format", "jpeg", "base64", jpeg)))
                .get("base64"));
        assertEquals("<base64: 6 bytes>", ((Map<?, ?>) Images.elided(Map.of("base64", jpeg))).get("base64"));
        Object tooDeep = "x";
        for (int i = 0; i < Images.MAX_DEPTH + 5; i++) {
            tooDeep = List.of(tooDeep);
        }
        assertTrue(String.valueOf(Images.elided(tooDeep)).contains("<too deep>"));
    }

    @Test
    void whatIsNotAnImagesBytesIsKept() {
        Map<String, Object> linked = new LinkedHashMap<>(capture());
        linked.put("base64", "<image: skillroy://mcp/1.png>");
        assertEquals(linked, Images.elided(linked), "a link is kept");
        assertEquals(Images.elided(capture()), Images.elided(Images.elided(capture())), "idempotent");
        assertEquals(Map.of("base64", ""), Images.elided(Map.of("base64", "")));
        assertEquals("base64:AAEC", Images.elided("base64:AAEC"), "a binary column's text without a PNG");
        String text = "the base64 of a hammer is not here; \"base64\" alone; {\"base64\": 7}";
        assertSame(text, Images.elidedText(text));
        assertNull(Images.elidedText(null));
        assertEquals(42L, Images.elided(42L));
    }

    @Test
    void aTextLosesEveryPngRunAndEveryBase64Member() {
        assertEquals("<img src=\"data:image/png;base64,<png: 70 bytes>\">",
                Images.elidedText("<img src=\"data:image/png;base64," + PNG_1X1 + "\">"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "call");
        record.put("value", capture());
        String line = Json.write(record);
        record.put("value", Images.elided(capture()));
        assertEquals(Json.write(record), Images.elidedText(line));
        String other = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5, 6});
        assertEquals("{\"base64\" : \"<base64: 6 bytes>\"}", Images.elidedText("{\"base64\" : \"" + other + "\"}"));
        assertEquals("{\\\"base64\\\":\\\"<base64: 6 bytes>\\\"}",
                Images.elidedText("{\\\"base64\\\":\\\"" + other + "\\\"}"), "inside another JSON string");
        assertEquals("\"x\":\"<png: 12 bytes>\"", Images.elidedText("\"x\":\"iVBORw0KGgoAB\\/CD\\/\""),
                "a JSON escape of / belongs to the run");
        String mime = PNG_1X1.substring(0, 76) + "\r\n" + PNG_1X1.substring(76);
        assertEquals("[<png: 70 bytes>] next", Images.elidedText("[" + mime + "] next"), "MIME line breaks");
        assertEquals("cut <png: 9 bytes>", Images.elidedText("cut " + PNG_1X1.substring(0, 12)), "a cut run");
        assertEquals("\"base64\":\"<base64: 3 bytes>", Images.elidedText("\"base64\":\"QUJD"), "a cut member");
        String twice = Images.elidedText("a " + PNG_1X1 + " b " + PNG_1X1);
        assertEquals("a <png: 70 bytes> b <png: 70 bytes>", twice);
        assertEquals(twice, Images.elidedText(twice), "idempotent");
        assertEquals("line1 <png: 70 bytes>\nHello", Images.elidedText("line1 " + PNG_1X1 + "\nHello"),
                "a line break after padding ends the run");
    }
}
