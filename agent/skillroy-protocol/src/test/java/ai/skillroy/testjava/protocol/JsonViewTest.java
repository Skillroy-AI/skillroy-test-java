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
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonViewTest {

    private final JsonView view = JsonView.of(Json.parseObject("{"
            + "\"s\":\"text\",\"n\":42,\"d\":2.5,\"di\":3.0,\"ns\":\" 17 \",\"ds\":\"2.25\",\"b\":true,"
            + "\"bs\":\"FALSE\",\"nul\":null,\"o\":{\"x\":{\"y\":\"deep\"}},\"l\":[1,2],\"big\":1e30,"
            + "\"empty\":\"\",\"neg\":-7}"));

    @Test
    void strCoercesScalarsToStrings() {
        assertEquals("text", view.str("s"));
        assertEquals("42", view.str("n"));
        assertEquals("-7", view.str("neg"));
        assertEquals("2.5", view.str("d"));
        assertEquals("3.0", view.str("di"));
        assertEquals("true", view.str("b"));
        assertEquals("", view.str("empty"));
        assertNull(view.str("missing"));
        assertNull(view.str("nul"));
        assertEquals("dflt", view.str("missing", "dflt"));
        assertEquals("dflt", view.str("nul", "dflt"));
        assertEquals("text", view.str("s", "dflt"));
        assertInvalid(() -> view.str("o"), "parameter 'o' must be a string but was an object", "o");
        assertInvalid(() -> view.str("l"), "parameter 'l' must be a string but was an array", "l");
    }

    @Test
    void requireStrNamesTheMissingKey() {
        assertEquals("", view.requireStr("empty"));
        assertInvalid(() -> view.requireStr("missing"), "missing required parameter 'missing'", "missing");
        assertInvalid(() -> view.requireStr("nul"), "missing required parameter 'nul'", "nul");
    }

    @Test
    void numAcceptsIntegralValuesAndNumericStrings() {
        assertEquals(42L, view.num("n", 0));
        assertEquals(-7L, view.num("neg", 0));
        assertEquals(3L, view.num("di", 0));
        assertEquals(17L, view.num("ns", 0));
        assertEquals(9L, view.num("missing", 9));
        assertEquals(9L, view.num("nul", 9));
        assertEquals(42L, view.requireNum("n"));
        assertInvalid(() -> view.num("d", 0), "parameter 'd' must be an integer but was a number", "d");
        assertInvalid(() -> view.num("ds", 0), "parameter 'ds' must be an integer but was a string", "ds");
        assertInvalid(() -> view.num("s", 0), "parameter 's' must be an integer but was a string", "s");
        assertInvalid(() -> view.num("b", 0), "parameter 'b' must be an integer but was a boolean", "b");
        assertInvalid(() -> view.num("big", 0), "parameter 'big' must be an integer but was a number", "big");
        assertInvalid(() -> view.requireNum("missing"), "missing required parameter 'missing'", "missing");
    }

    @Test
    void numHandlesLongLimits() {
        JsonView limits = JsonView.of(Json.parseObject(
                "{\"max\":9223372036854775807,\"min\":-9223372036854775808,\"over\":9223372036854775808,"
                        + "\"maxs\":\"9223372036854775807\",\"overs\":\"9223372036854775808\"}"));
        assertEquals(Long.MAX_VALUE, limits.requireNum("max"));
        assertEquals(Long.MIN_VALUE, limits.requireNum("min"));
        assertEquals(Long.MAX_VALUE, limits.requireNum("maxs"));
        assertInvalid(() -> limits.requireNum("over"), "must be an integer", "over");
        assertInvalid(() -> limits.requireNum("overs"), "must be an integer", "overs");
    }

    @Test
    void dblAcceptsNumbersAndNumericStrings() {
        assertEquals(2.5, view.dbl("d", 0), 0);
        assertEquals(42.0, view.dbl("n", 0), 0);
        assertEquals(2.25, view.dbl("ds", 0), 0);
        assertEquals(1e30, view.dbl("big", 0), 0);
        assertEquals(1.5, view.dbl("missing", 1.5), 0);
        assertInvalid(() -> view.dbl("s", 0), "parameter 's' must be a number but was a string", "s");
        assertInvalid(() -> view.dbl("b", 0), "parameter 'b' must be a number but was a boolean", "b");
        JsonView odd = JsonView.of(Json.parseObject("{\"nan\":\"NaN\",\"inf\":\"Infinity\",\"hex\":\"0x10\"}"));
        assertInvalid(() -> odd.dbl("nan", 0), "must be a number", "nan");
        assertInvalid(() -> odd.dbl("inf", 0), "must be a number", "inf");
        assertInvalid(() -> odd.dbl("hex", 0), "must be a number", "hex");
    }

    @Test
    void boolAcceptsBooleansAndTrueFalseStrings() {
        assertTrue(view.bool("b", false));
        assertFalse(view.bool("bs", true));
        assertTrue(view.bool("missing", true));
        assertFalse(view.bool("nul", false));
        assertInvalid(() -> view.bool("s", false), "parameter 's' must be a boolean but was a string", "s");
        assertInvalid(() -> view.bool("n", false), "parameter 'n' must be a boolean but was a number", "n");
    }

    @Test
    void objReturnsNestedViewsAndReportsNestedPaths() {
        assertEquals("deep", view.obj("o").obj("x").str("y"));
        assertFalse(view.obj("missing").has("a"));
        assertTrue(view.obj("missing").raw().isEmpty());
        assertTrue(view.obj("nul").raw().isEmpty());
        assertInvalid(() -> view.obj("s"), "parameter 's' must be an object but was a string", "s");
        assertInvalid(() -> view.obj("o").obj("x").num("y", 0), "parameter 'o.x.y' must be an integer", "o.x.y");
        assertInvalid(() -> view.obj("o").requireStr("q"), "missing required parameter 'o.q'", "o.q");
        assertInvalid(() -> view.obj("missing").requireStr("q"), "missing required parameter 'missing.q'",
                "missing.q");
    }

    @Test
    void listReturnsReadOnlyListsAndAcceptsJavaArrays() {
        assertEquals(Arrays.<Object>asList(1L, 2L), view.list("l"));
        assertTrue(view.list("missing").isEmpty());
        assertTrue(view.list("nul").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> view.list("l").add("x"));
        assertInvalid(() -> view.list("s"), "parameter 's' must be an array but was a string", "s");
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("arr", new Object[] {"a", "b"});
        raw.put("set", new LinkedHashSet<Object>(Arrays.<Object>asList("c")));
        JsonView javaValues = JsonView.of(raw);
        assertEquals(Arrays.<Object>asList("a", "b"), javaValues.list("arr"));
        assertEquals(Arrays.<Object>asList("c"), javaValues.list("set"));
    }

    @Test
    void hasGetAndRaw() {
        assertTrue(view.has("s"));
        assertFalse(view.has("nul"));
        assertFalse(view.has("missing"));
        assertEquals(Long.valueOf(42), view.get("n"));
        assertNull(view.get("nul"));
        assertTrue(view.raw().containsKey("nul"));
        assertThrows(UnsupportedOperationException.class, () -> view.raw().put("x", 1));
        assertTrue(JsonView.of(null).raw().isEmpty());
        assertFalse(JsonView.of(null).has("x"));
    }

    @Test
    void errorsNeverEchoTheValue() {
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("password", "hunter2-secret");
        RpcException e = assertThrows(RpcException.class, () -> JsonView.of(raw).num("password", 0));
        assertFalse(e.getMessage().contains("hunter2"), e.getMessage());
    }

    private static void assertInvalid(Executable call, String messageFragment, String param) {
        RpcException e = assertThrows(RpcException.class, call);
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertTrue(e.getMessage().contains(messageFragment), e.getMessage());
        assertTrue(e.error().message().contains(messageFragment), e.error().message());
        assertEquals(param, e.error().data().get("param"));
    }
}
