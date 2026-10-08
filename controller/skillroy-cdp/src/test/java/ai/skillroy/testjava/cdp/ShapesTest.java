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

package ai.skillroy.testjava.cdp;

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The shape adapters of the CDP path (phase4-contracts.md section 12.1 item 5): the {@code web.evaluate} text, the
 * guarded results and their errors, Playwright's values in JSON normal form, PNG dimensions and scaling.
 */
class ShapesTest {

    @Test
    void theWrapperTextBecomesTheEvaluateValue() {
        assertEquals(Map.of("json", "2", "typeofResult", "number"),
                Shapes.evaluateValue("{\"json\":\"2\",\"typeofResult\":\"number\",\"truncated\":false}"));
        assertEquals(Map.of("json", "null", "typeofResult", "undefined"),
                Shapes.evaluateValue("{\"json\":\"null\",\"typeofResult\":\"undefined\"}"));
        Map<String, Object> cut = Shapes.evaluateValue("{\"json\":\"\\\"aaa\",\"typeofResult\":\"string\","
                + "\"truncated\":true}");
        assertEquals(List.of("json", "typeofResult", "truncated"), new ArrayList<>(cut.keySet()));
        assertEquals(true, cut.get("truncated"));
        assertThrows(IllegalArgumentException.class, () -> Shapes.evaluateValue("not json"));
        assertThrows(IllegalArgumentException.class, () -> Shapes.evaluateValue("{\"json\":2}"));
    }

    @Test
    void aGuardedResultYieldsItsValue() throws Exception {
        Map<String, Object> lib = Map.of("text", "hello", "truncated", false);
        assertEquals(lib, Shapes.unwrap("web.dom.text", FakeWorld.ok(lib), null));
        assertNull(Shapes.unwrap("web.dom.text", FakeWorld.ok(null), null));
    }

    @Test
    void aThrownScriptIsScriptErrorWithBrowserMessageAndStack() {
        Map<String, Object> raw = Map.of("__scriptError", Map.of("message", "Error: boom", "stack",
                "Error: boom\n    at <anonymous>:1:7"));
        AgentRpcException e = assertThrows(AgentRpcException.class, () -> Shapes.unwrap("web.evaluate", raw,
                "browser-3"));
        assertEquals("web.evaluate", e.method());
        assertEquals(ErrorCodes.SCRIPT_ERROR, e.error().code());
        assertEquals("Error: boom", e.error().message());
        assertEquals(Map.of("browser", "browser-3", "message", "Error: boom", "stack",
                "Error: boom\n    at <anonymous>:1:7"), e.error().data());
        AgentRpcException noRef = assertThrows(AgentRpcException.class, () -> Shapes.unwrap("web.evaluate", raw,
                null));
        assertEquals(List.of("message", "stack"), new ArrayList<>(noRef.error().data().keySet()));
    }

    @Test
    void libraryErrorObjectsKeepTheirCodeAndData() {
        AgentRpcException notFound = assertThrows(AgentRpcException.class, () -> Shapes.unwrap("web.dom.click",
                FakeWorld.ok(Map.of("__error", "NOT_FOUND", "data", Map.of("selector", "#nope"))), "b"));
        assertEquals(ErrorCodes.NOT_FOUND, notFound.error().code());
        assertEquals(Map.of("selector", "#nope"), notFound.error().data());
        assertEquals("no element in the page matches {\"selector\":\"#nope\"}", notFound.error().message());

        List<Object> candidates = List.of(Map.of("role", "button", "name", "Go", "tag", "BUTTON"),
                Map.of("role", "button", "name", "Go", "tag", "BUTTON"));
        AgentRpcException ambiguous = assertThrows(AgentRpcException.class, () -> Shapes.unwrap("web.dom.click",
                FakeWorld.ok(Map.of("__error", "AMBIGUOUS", "data", Map.of("candidates", candidates))), null));
        assertEquals(ErrorCodes.AMBIGUOUS, ambiguous.error().code());
        assertEquals("several elements in the page match (2); name one with index", ambiguous.error().message());
        assertEquals(Map.of("candidates", candidates), ambiguous.error().data());

        AgentRpcException hidden = assertThrows(AgentRpcException.class, () -> Shapes.unwrap("web.dom.fill",
                FakeWorld.ok(Map.of("__error", "INVALID_PARAMS", "data", Map.of("reason", "not visible"))), null));
        assertEquals(ErrorCodes.INVALID_PARAMS, hidden.error().code());
        assertEquals("invalid parameters: not visible", hidden.error().message());

        AgentRpcException unknown = assertThrows(AgentRpcException.class, () -> Shapes.unwrap("web.dom.text",
                FakeWorld.ok(Map.of("__error", "WHATEVER")), null));
        assertEquals(ErrorCodes.INTERNAL_ERROR, unknown.error().code());
        assertEquals(Map.of("error", "WHATEVER"), unknown.error().data());
    }

    @Test
    void somethingThatIsNotAGuardedResultIsAnInternalError() {
        for (Object raw : new Object[] {"text", null, Map.of("other", 1)}) {
            AgentRpcException e = assertThrows(AgentRpcException.class, () -> Shapes.unwrap("web.evaluate", raw,
                    null));
            assertEquals(ErrorCodes.INTERNAL_ERROR, e.error().code(), String.valueOf(raw));
            assertEquals(Map.of("path", "cdp"), e.error().data());
        }
    }

    @Test
    void playwrightValuesBecomeJsonNormalForm() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("int", 7);
        raw.put("double", 1.5);
        raw.put("integralDouble", 3.0);
        raw.put("big", 1.0e12);
        raw.put("huge", 1.0e300);
        raw.put("nan", Double.NaN);
        raw.put("inf", Double.NEGATIVE_INFINITY);
        raw.put("date", new Date(0));
        raw.put("url", new URL("http://example.test/"));
        raw.put("regex", Pattern.compile("a+"));
        raw.put("list", List.of(1, "x", true));
        raw.put("array", new int[] {1, 2});
        raw.put("error", new Exception("bad"));
        Object json = Values.json(raw);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("int", 7L);
        expected.put("double", 1.5);
        expected.put("integralDouble", 3L);
        expected.put("big", 1_000_000_000_000L);
        expected.put("huge", 1.0e300);
        expected.put("nan", null);
        expected.put("inf", null);
        expected.put("date", "1970-01-01T00:00:00Z");
        expected.put("url", "http://example.test/");
        expected.put("regex", "a+");
        expected.put("list", List.of(1L, "x", true));
        expected.put("array", List.of(1L, 2L));
        expected.put("error", "bad");
        assertEquals(expected, json);
        assertEquals(Json.write(expected), Json.write(json), "writable by the protocol codec");

        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        assertEquals(Map.of("self", "[cycle]"), Values.json(cyclic));
        List<Object> shared = List.of(1);
        assertEquals(List.of(List.of(1L), List.of(1L)), Values.json(List.of(shared, shared)),
                "a value seen twice is not a cycle");
    }

    @Test
    void pngDimensionsComeFromTheHeader() throws IOException {
        byte[] png = png(300, 200, 0xff336699);
        assertArrayEquals(new int[] {300, 200}, Png.size(png));
        assertThrows(IllegalArgumentException.class, () -> Png.size(new byte[] {1, 2, 3}));
        byte[] notPng = png.clone();
        notPng[1] = 'X';
        assertThrows(IllegalArgumentException.class, () -> Png.size(notPng));
    }

    @Test
    void aWidePngIsScaledToTheMaximumWidthKeepingItsAspectRatio() throws IOException {
        byte[] wide = png(4000, 1000, 0xff336699);
        byte[] scaled = Png.scaleToWidth(wide, 1000);
        assertArrayEquals(new int[] {1000, 250}, Png.size(scaled));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(scaled));
        assertEquals(1000, decoded.getWidth());
        assertEquals(0xff336699, decoded.getRGB(500, 125), "the colour survives the scaling");
        assertSame(wide, Png.scaleToWidth(wide, 4000), "not wider than the maximum: unchanged");
        assertArrayEquals(new int[] {3, 1}, Png.size(Png.scaleToWidth(png(10, 3, 0xff000000), 3)),
                "height rounded, at least 1");
        assertThrows(IllegalArgumentException.class, () -> Png.scaleToWidth(wide, 0));
    }

    @Test
    void theBitmapValueIsThePngWithItsSizeAndSource() throws IOException {
        Map<String, Object> v = Shapes.bitmapValue(png(800, 600, 0xffffffff), 400);
        assertEquals(List.of("format", "width", "height", "base64", "source"), new ArrayList<>(v.keySet()));
        assertEquals("png", v.get("format"));
        assertEquals(400L, v.get("width"));
        assertEquals(300L, v.get("height"));
        assertEquals("PROTOCOL", v.get("source"));
        byte[] decoded = Base64.getDecoder().decode((String) v.get("base64"));
        assertArrayEquals(new int[] {400, 300}, Png.size(decoded));
    }

    @Test
    void theSmallValueShapes() {
        assertEquals(Map.of("ready", true, "ms", 120L, "polls", 3L), Shapes.waitFlagValue(true, 120, 50, null));
        assertEquals(List.of("ready", "ms", "polls", "lastError"), new ArrayList<>(Shapes.waitFlagValue(false, 300, 50,
                "TypeError: x is null").keySet()), "A35: the agent's shape, lastError last");
        assertEquals(Map.of("ready", false, "ms", 300L, "polls", 7L, "lastError", "TypeError: x is null"),
                Shapes.waitFlagValue(false, 300, 50, "TypeError: x is null"));
        assertEquals(Map.of("ready", true, "ms", 120L, "polls", 3L), Shapes.waitFlagValue(true, 120, 50, "stale"),
                "no lastError when ready");
        assertEquals(Map.of("ready", false, "ms", 300L, "polls", 7L), Shapes.waitFlagValue(false, 300, 50, null));
        assertEquals(1L, Shapes.polls(0, 50), "one evaluation at the start");
        assertEquals(1L, Shapes.polls(49, 50));
        assertEquals(201L, Shapes.polls(10_000, 50));
        assertEquals(Map.of("applied", "OVERRIDE", "viewport", Map.of("w", 1024L, "h", 768L)),
                Shapes.resizeValue(1024, 768));
        assertEquals(Map.of("installed", 2L, "timing", "DOCUMENT_START"), Shapes.tapValue(2));
        assertEquals(Map.of("a", 1), Shapes.data("a", 1, "b", null), "null values are left out");
    }

    static byte[] png(int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
