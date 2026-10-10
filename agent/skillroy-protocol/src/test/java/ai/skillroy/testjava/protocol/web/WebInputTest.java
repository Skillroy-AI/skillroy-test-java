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

package ai.skillroy.testjava.protocol.web;

import ai.skillroy.testjava.protocol.Json;
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

/**
 * {@link WebInput}, what both web paths share for {@code web.hitTest} and {@code web.pointer} (phase7-contracts.md
 * sections 4.2 and 4.3): the parameters and their refusals, the event plan per action, the byte-identical protocol
 * parameters, the receiving side's bounds of a hit (S43), the child-frame rule and the result shapes.
 */
class WebInputTest {

    @Test
    void thePointAndItsSpace() {
        WebInput.Target t = WebInput.target(args("point", point(10.4, 20.6)));
        assertEquals(10, t.x());
        assertEquals(21, t.y(), "rounded to whole pixels");
        assertEquals(WebInput.VIEWPORT, t.space());
        assertFalse(t.component());
        assertTrue(WebInput.target(args("point", point(1, 2), "space", "COMPONENT")).component());
        assertEquals(-5, WebInput.target(args("point", point(-5, 0))).x(), "outside is the library's to answer");
        assertInvalid("point", args());
        assertInvalid("point", args("point", "10,20"));
        assertInvalid("point", args("point", args("x", 1)));
        assertInvalid("point", args("point", args("x", "1", "y", 2)));
        assertInvalid("point", args("point", point(Double.NaN, 1)));
        assertInvalid("point", args("point", point(16385, 1)));
        assertInvalid("space", args("point", point(1, 1), "space", "SCREEN"));
        assertInvalid("space", args("point", point(1, 1), "space", Boolean.TRUE));
    }

    @Test
    void thePointerParametersAndTheirDefaults() {
        WebInput.Pointer click = WebInput.pointer(args("action", "CLICK", "point", point(5, 6)));
        assertEquals("CLICK", click.action());
        assertEquals("LEFT", click.button());
        assertEquals(1, click.clickCount());
        assertEquals(Collections.emptyList(), click.modifiers());
        assertFalse(click.force());
        assertEquals(2, WebInput.pointer(args("action", "DOUBLE_CLICK", "point", point(5, 6))).clickCount());
        WebInput.Pointer full = WebInput.pointer(args("action", "PRESS", "point", point(5, 6), "button", "RIGHT",
                "clickCount", 3, "modifiers", Arrays.asList("SHIFT", "ALT", "SHIFT"), "force", Boolean.TRUE));
        assertEquals("RIGHT", full.button());
        assertEquals(3, full.clickCount());
        assertEquals(Arrays.asList("ALT", "SHIFT"), full.modifiers(), "each once, in the protocol's order");
        assertEquals(9, full.modifierBits());
        assertTrue(full.force());
        WebInput.Pointer wheel = WebInput.pointer(args("action", "WHEEL", "point", point(5, 6), "wheel",
                args("dx", 0, "dy", 120)));
        assertEquals(0, wheel.deltaX());
        assertEquals(120, wheel.deltaY());
    }

    @Test
    void aWrongPointerParameterNamesIt() {
        Map<String, Object> p = point(1, 1);
        assertInvalidPointer("action", args("point", p));
        assertInvalidPointer("action", args("action", "DRAG", "point", p));
        assertInvalidPointer("point", args("action", "MOVE"));
        assertInvalidPointer("button", args("action", "CLICK", "point", p, "button", "BACK"));
        assertInvalidPointer("clickCount", args("action", "CLICK", "point", p, "clickCount", 0));
        assertInvalidPointer("clickCount", args("action", "CLICK", "point", p, "clickCount", 4));
        assertInvalidPointer("clickCount", args("action", "CLICK", "point", p, "clickCount", 1.5));
        assertInvalidPointer("modifiers", args("action", "CLICK", "point", p, "modifiers", "CTRL"));
        assertInvalidPointer("modifiers", args("action", "CLICK", "point", p, "modifiers", Arrays.asList("HYPER")));
        assertInvalidPointer("wheel", args("action", "WHEEL", "point", p));
        assertInvalidPointer("wheel", args("action", "WHEEL", "point", p, "wheel", args("dx", 16385)));
        assertInvalidPointer("wheel", args("action", "CLICK", "point", p, "wheel", args("dy", 1)));
        assertInvalidPointer("force", args("action", "CLICK", "point", p, "force", "yes"));
    }

    @Test
    void thePlanStartsWithAMoveAndPairsEveryClick() {
        assertEquals("[mouseMoved(none,0)]", plan("MOVE", 1));
        assertEquals("[mouseMoved(none,0), mousePressed(left,1)]", plan("PRESS", 1));
        assertEquals("[mouseMoved(none,0), mouseReleased(left,2)]", plan("RELEASE", 2));
        assertEquals("[mouseMoved(none,0), mousePressed(left,1), mouseReleased(left,1)]", plan("CLICK", 1));
        assertEquals("[mouseMoved(none,0), mousePressed(left,1), mouseReleased(left,1), mousePressed(left,2),"
                + " mouseReleased(left,2)]", plan("DOUBLE_CLICK", 2));
        assertEquals("[mouseMoved(none,0), mouseWheel(none,0)]", String.valueOf(WebInput.plan(WebInput.pointer(
                args("action", "WHEEL", "point", point(1, 1), "wheel", args("dy", 3))))));
        assertEquals("[mouseMoved(none,0), mousePressed(middle,1), mouseReleased(middle,1)]",
                String.valueOf(WebInput.plan(WebInput.pointer(args("action", "CLICK", "point", point(1, 1),
                        "button", "MIDDLE")))));
    }

    @Test
    void theProtocolParametersHaveOneFormOnBothPaths() {
        assertEquals("{\"type\":\"mousePressed\",\"x\":12,\"y\":34,\"button\":\"left\",\"clickCount\":1,"
                + "\"modifiers\":2}", Json.write(WebInput.mouseEvent("mousePressed", 12, 34, "left", 1, 2, 0, 0)));
        assertEquals("{\"type\":\"mouseWheel\",\"x\":1,\"y\":2,\"button\":\"none\",\"clickCount\":0,"
                + "\"modifiers\":0,\"deltaX\":-5,\"deltaY\":120}",
                Json.write(WebInput.mouseEvent("mouseWheel", 1, 2, "none", 0, 0, -5, 120)));
        WebInput.Pointer p = WebInput.pointer(args("action", "CLICK", "point", point(7, 8), "modifiers",
                Arrays.asList("CTRL", "META")));
        List<String> sent = new ArrayList<String>();
        for (WebInput.Step step : WebInput.plan(p)) {
            sent.add(Json.write(WebInput.mouseEvent(step, 7, 8, p)));
        }
        assertEquals(Arrays.asList(
                "{\"type\":\"mouseMoved\",\"x\":7,\"y\":8,\"button\":\"none\",\"clickCount\":0,\"modifiers\":6}",
                "{\"type\":\"mousePressed\",\"x\":7,\"y\":8,\"button\":\"left\",\"clickCount\":1,\"modifiers\":6}",
                "{\"type\":\"mouseReleased\",\"x\":7,\"y\":8,\"button\":\"left\",\"clickCount\":1,\"modifiers\":6}"),
                sent);
        assertEquals(15, WebInput.modifierBits(WebInput.MODIFIERS));
        assertEquals(0, WebInput.modifierBits(Arrays.asList("HYPER")));
    }

    @Test
    void aHitIsBoundedOnTheReceivingSide() {
        Map<String, Object> element = new LinkedHashMap<String, Object>();
        element.put("role", repeat('r', 100));
        element.put("name", repeat('n', 5000));
        element.put("tag", "button");
        element.put("id", "go");
        element.put("rect", args("x", 1.4, "y", 1e12, "w", "wide", "h", Double.NaN));
        List<Object> locators = new ArrayList<Object>();
        locators.add(args("role", "button", "name", "Go"));
        locators.add(args("selector", repeat('s', 9000)));
        locators.add(args("nonsense", Boolean.TRUE));
        locators.add("text");
        for (int i = 0; i < 10; i++) {
            locators.add(args("selector", "#x" + i));
        }
        Map<String, Object> value = args("element", element, "locators", locators, "unique", Boolean.FALSE, "child",
                args("x", 3, "y", 4, "name", "inner", "url", repeat('u', 9000)));
        WebInput.Hit hit = WebInput.hit(value);
        Map<String, Object> e = hit.element();
        assertEquals(64, ((String) e.get("role")).length());
        assertEquals(1000, ((String) e.get("name")).length());
        assertEquals("go", e.get("id"));
        assertEquals(args("x", 1L, "y", 1L << 24, "w", 0L, "h", 0L), e.get("rect"));
        assertEquals(6, hit.locators().size(), "at most six well-formed locators");
        assertEquals(args("role", "button", "name", "Go"), hit.locators().get(0));
        assertEquals(4096, ((String) ((Map<?, ?>) hit.locators().get(1)).get("selector")).length());
        assertTrue(hit.unique(), "unique when a locator is read, whatever the page claimed");
        assertEquals(3, hit.child().x());
        assertEquals("inner", hit.child().name());
        assertEquals(WebLib.MAX_URL_CHARS, hit.child().url().length());
        assertFalse(hit.summary().containsKey("rect"));
        assertEquals(args("x", 11L, "y", (1L << 24) - 2, "w", 0L, "h", 0L),
                hit.translated(10, -2).element().get("rect"));
        assertThrows(WebInput.Malformed.class, () -> WebInput.hit("text"));
        assertThrows(WebInput.Malformed.class, () -> WebInput.hit(args("locators", locators)));
        WebInput.Hit bare = WebInput.hit(args("element", args("tag", "div")));
        assertEquals(args("role", "", "name", "", "tag", "div", "rect", args("x", 0L, "y", 0L, "w", 0L, "h", 0L)),
                bare.element());
        assertFalse(bare.unique());
        assertNull(bare.child());
    }

    @Test
    void theChildFrameIsFoundByNameThenUrlThenAlone() {
        List<WebInput.Frame> frames = Arrays.asList(new WebInput.Frame("M", null, "", "main"),
                new WebInput.Frame("A", "M", "terms", "data:a"), new WebInput.Frame("B", "M", "", "data:b"),
                new WebInput.Frame("C", "A", "", "data:c"));
        assertEquals("A", WebInput.childFrame(frames, "M", child("terms", "data:x")));
        assertEquals("B", WebInput.childFrame(frames, "M", child("", "data:b")));
        assertNull(WebInput.childFrame(frames, "M", child("other", "data:z")), "two children, neither matches");
        assertEquals("C", WebInput.childFrame(frames, "A", child("nameless", "")), "the only child");
        assertNull(WebInput.childFrame(frames, "C", child("", "")), "no child frame");
        assertNull(WebInput.childFrame(frames, null, child("terms", "")));
        List<WebInput.Frame> twins = Arrays.asList(new WebInput.Frame("X", "M", "same", "data:1"),
                new WebInput.Frame("Y", "M", "same", "data:2"));
        assertEquals("Y", WebInput.childFrame(twins, "M", child("same", "data:2")), "a shared name, then the URL");
    }

    @Test
    void theResultShapes() {
        WebInput.Hit hit = WebInput.hit(args("element", args("role", "button", "name", "Go", "tag", "button",
                "rect", args("x", 1, "y", 2, "w", 3, "h", 4)), "locators",
                Collections.singletonList(args("selector", "#go"))));
        assertEquals("{\"browser\":\"b-1\",\"frame\":\"F\",\"viewportPoint\":{\"x\":5,\"y\":6},\"element\":"
                + "{\"role\":\"button\",\"name\":\"Go\",\"tag\":\"button\",\"rect\":{\"x\":1,\"y\":2,\"w\":3,\"h\":4}},"
                + "\"locators\":[{\"selector\":\"#go\"}],\"unique\":true}",
                Json.write(WebInput.hitResult("b-1", "F", 5, 6, hit, null, null, null)));
        assertEquals("{\"browser\":\"b-1\",\"viewportPoint\":{\"x\":5,\"y\":6},\"element\":{\"role\":\"button\","
                + "\"name\":\"Go\",\"tag\":\"button\",\"rect\":{\"x\":1,\"y\":2,\"w\":3,\"h\":4}},\"locators\":"
                + "[{\"selector\":\"#go\"}],\"unique\":true,\"iframe\":{\"key\":\"K\",\"rect\":{\"x\":0,\"y\":0,"
                + "\"w\":9,\"h\":9}},\"scaleAssumed\":false}", Json.write(WebInput.hitResult("b-1", null, 5, 6, hit,
                "K", WebInput.rect(0, 0, 9, 9), Boolean.FALSE)));
        assertEquals("{\"via\":\"PROTOCOL\",\"viewportPoint\":{\"x\":5,\"y\":6},\"dispatched\":[\"mouseMoved\"],"
                + "\"hit\":{\"role\":\"button\",\"name\":\"Go\",\"tag\":\"button\"}}",
                Json.write(WebInput.pointerResult("PROTOCOL", 5, 6, Collections.singletonList("mouseMoved"),
                        hit.summary(), true)));
        assertEquals("{\"via\":\"VENDOR\",\"viewportPoint\":{\"x\":0,\"y\":0},\"dispatched\":[],"
                + "\"visibleInSwing\":false}", Json.write(WebInput.pointerResult("VENDOR", 0, 0,
                Collections.<String>emptyList(), null, false)));
    }

    private static String plan(String action, int clickCount) {
        return String.valueOf(WebInput.plan(WebInput.pointer(args("action", action, "point", point(1, 1),
                "clickCount", clickCount))));
    }

    private static WebInput.Child child(String name, String url) {
        return new WebInput.Child(0, 0, name, url);
    }

    private static void assertInvalid(String param, Map<String, Object> args) {
        WebInput.Invalid e = assertThrows(WebInput.Invalid.class, () -> WebInput.target(args), String.valueOf(args));
        assertEquals(param, e.param(), e.getMessage());
    }

    private static void assertInvalidPointer(String param, Map<String, Object> args) {
        WebInput.Invalid e = assertThrows(WebInput.Invalid.class, () -> WebInput.pointer(args), String.valueOf(args));
        assertEquals(param, e.param(), e.getMessage());
    }

    private static Map<String, Object> point(double x, double y) {
        return args("x", x, "y", y);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
