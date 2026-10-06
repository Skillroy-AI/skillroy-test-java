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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class JsonParseTest {

    @Test
    void parsesScalars() {
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
        assertEquals("x", Json.parse("\"x\""));
        assertEquals("", Json.parse("\"\""));
        assertEquals(Long.valueOf(1), Json.parse(" 1 "));
    }

    @Test
    void objectsAreLinkedHashMapsInDocumentOrder() {
        Object parsed = Json.parse("{\"b\":1,\"a\":2,\"c\":{\"z\":null}}");
        assertInstanceOf(LinkedHashMap.class, parsed);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        assertEquals(Arrays.asList("b", "a", "c"), new ArrayList<String>(map.keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) map.get("c");
        assertTrue(inner.containsKey("z"));
        assertNull(inner.get("z"));
    }

    @Test
    void aRepeatedKeyTakesTheLastValueAndKeepsItsFirstPosition() {
        Map<String, Object> map = Json.parseObject("{\"a\":1,\"b\":2,\"a\":{\"x\":[3]},\"a\":4}");
        assertEquals(Arrays.asList("a", "b"), new ArrayList<String>(map.keySet()));
        assertEquals(Long.valueOf(4), map.get("a"));
        assertEquals(Long.valueOf(2), map.get("b"));
        assertNull(Json.parseObject("{\"a\":1,\"a\":null}").get("a"));
        assertEquals("{\"a\":4,\"b\":2}", Json.write(map));
    }

    @Test
    void arraysAreArrayListsOfTypedValues() {
        Object parsed = Json.parse("[1, \"a\", [], {}, null, true, 2.5]");
        assertInstanceOf(ArrayList.class, parsed);
        List<?> list = (List<?>) parsed;
        assertEquals(7, list.size());
        assertEquals(Long.valueOf(1), list.get(0));
        assertEquals("a", list.get(1));
        assertInstanceOf(ArrayList.class, list.get(2));
        assertInstanceOf(LinkedHashMap.class, list.get(3));
        assertNull(list.get(4));
        assertEquals(Boolean.TRUE, list.get(5));
        assertEquals(Double.valueOf(2.5), list.get(6));
    }

    @Test
    void acceptsTheFourWhitespaceCharactersBetweenTokens() {
        Map<String, Object> expected = new LinkedHashMap<String, Object>();
        expected.put("a", Arrays.<Object>asList(Long.valueOf(1), Long.valueOf(2)));
        assertEquals(expected, Json.parse(" \t\r\n{ \"a\" :\t[ 1 ,\n2 ] }\r\n "));
    }

    @Test
    void decodesEveryEscape() {
        assertEquals("\" \\ / \b \f \n \r \t", Json.parse("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\""));
        assertEquals("A\u00e9\u20ac", Json.parse("\"\\u0041\\u00E9\\u20aC\""));
        assertEquals("\u0000\u001f", Json.parse("\"\\u0000\\u001F\""));
        assertEquals("plain and \u00e9 and \u2603", Json.parse("\"plain and \u00e9 and \u2603\""));
    }

    @Test
    void handlesNonBmpCharactersAndToleratesLoneSurrogates() {
        assertEquals("\uD83D\uDE00", Json.parse("\"\\uD83D\\uDE00\""));
        assertEquals("\uD83D\uDE00", Json.parse("\"\uD83D\uDE00\""));
        assertEquals("\uD800", Json.parse("\"\\uD800\""));
        assertEquals("a\uDC00b", Json.parse("\"a\\udc00b\""));
        assertEquals("\uDE00\uD83D", Json.parse("\"\\uDE00\\uD83D\""));
    }

    @Test
    void acceptsRawLineSeparatorsAndDelInsideStrings() {
        assertEquals("\u2028\u2029\u007f", Json.parse("\"\u2028\u2029\u007f\""));
    }

    @Test
    void integralNumbersThatFitALongAreLongs() {
        assertEquals(Long.valueOf(0), Json.parse("0"));
        assertEquals(Long.valueOf(0), Json.parse("-0"));
        assertEquals(Long.valueOf(-17), Json.parse("-17"));
        assertEquals(Long.valueOf(Long.MAX_VALUE), Json.parse("9223372036854775807"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), Json.parse("-9223372036854775808"));
    }

    @Test
    void otherNumbersAreDoubles() {
        assertEquals(Double.valueOf(9.223372036854775808E18), Json.parse("9223372036854775808"));
        assertEquals(Double.valueOf(-9.223372036854775809E18), Json.parse("-9223372036854775809"));
        assertEquals(Double.valueOf(1.2345678901234568E29), Json.parse("123456789012345678901234567890"));
        assertEquals(Double.valueOf(1.0), Json.parse("1.0"));
        assertEquals(Double.valueOf(100.0), Json.parse("1e2"));
        assertEquals(Double.valueOf(100.0), Json.parse("1E+2"));
        assertEquals(Double.valueOf(0.01), Json.parse("1e-2"));
        assertEquals(Double.valueOf(-0.0015), Json.parse("-1.5e-3"));
        assertEquals(Double.valueOf(0.5), Json.parse("0.5"));
        assertEquals(Double.valueOf(0.0), Json.parse("1e-400"));
        assertEquals(Double.valueOf(-0.0), Json.parse("-0.0"));
        assertEquals(Double.valueOf(Double.MAX_VALUE), Json.parse("1.7976931348623157E308"));
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                arguments("", "unexpected end of input, expected a value", 0),
                arguments("   ", "unexpected end of input, expected a value", 3),
                arguments("[1,]", "trailing comma in array", 3),
                arguments("[1, ]", "trailing comma in array", 4),
                arguments("{\"a\":1,}", "trailing comma in object", 7),
                arguments("[1 2]", "unexpected '2', expected ',' or ']'", 3),
                arguments("[1,,2]", "unexpected ',', expected a value", 3),
                arguments("{\"a\" 1}", "unexpected '1', expected ':' after an object key", 5),
                arguments("{\"a\":1 \"b\":2}", "unexpected '\"', expected ',' or '}'", 7),
                arguments("{a:1}", "expected a double-quoted object key but found 'a'", 1),
                arguments("{'a':1}", "expected a double-quoted object key but found '''", 1),
                arguments("{,}", "expected a double-quoted object key but found ','", 1),
                arguments("['a']", "unexpected ''', expected a value", 1),
                arguments("// note\n1", "comments are not allowed", 0),
                arguments("/* note */ 1", "comments are not allowed", 0),
                arguments("[1, /* note */ 2]", "comments are not allowed", 4),
                arguments("{\"a\":1 // note\n}", "comments are not allowed", 7),
                arguments("{/* note */}", "comments are not allowed", 1),
                arguments("1 // note", "comments are not allowed", 2),
                arguments("01", "leading zeros are not allowed", 0),
                arguments("-01", "leading zeros are not allowed", 0),
                arguments("[00]", "leading zeros are not allowed", 1),
                arguments("1.", "expected a digit after the decimal point", 2),
                arguments("1.e5", "expected a digit after the decimal point", 2),
                arguments(".5", "unexpected '.', expected a value", 0),
                arguments("+1", "unexpected '+', expected a value", 0),
                arguments("-", "expected a digit after '-'", 1),
                arguments("-a", "expected a digit after '-'", 1),
                arguments("1e", "expected a digit in the exponent", 2),
                arguments("1e+", "expected a digit in the exponent", 3),
                arguments("0x10", "unexpected 'x' after the end of the JSON value", 1),
                arguments("1e400", "number outside the range of a double", 0),
                arguments("[-1e400]", "number outside the range of a double", 1),
                arguments("NaN", "unexpected 'N', expected a value", 0),
                arguments("Infinity", "unexpected 'I', expected a value", 0),
                arguments("-Infinity", "expected a digit after '-'", 1),
                arguments("tru", "invalid literal, expected 'true'", 0),
                arguments("[nul]", "invalid literal, expected 'null'", 1),
                arguments("fals", "invalid literal, expected 'false'", 0),
                arguments("True", "unexpected 'T', expected a value", 0),
                arguments("truex", "unexpected 'x' after the end of the JSON value", 4),
                arguments("\"abc", "unterminated string starting", 0),
                arguments("[\"abc]", "unterminated string starting", 1),
                arguments("\"a\nb\"", "unescaped control character U+000A in string", 2),
                arguments("\"a\tb\"", "unescaped control character U+0009 in string", 2),
                arguments("\"\u0000\"", "unescaped control character U+0000 in string", 1),
                arguments("\"\\x\"", "invalid escape sequence \\x", 1),
                arguments("\"\\'\"", "invalid escape sequence \\'", 1),
                arguments("\"\\u12G4\"", "invalid \\u escape, expected 4 hex digits", 1),
                arguments("\"\\u12\"", "invalid \\u escape, expected 4 hex digits", 1),
                arguments("\"\\u\uFF10\uFF10\uFF10\uFF10\"", "invalid \\u escape, expected 4 hex digits", 1),
                arguments("\"\\u12", "truncated \\u escape, expected 4 hex digits", 1),
                arguments("\"\\", "unterminated escape sequence", 1),
                arguments("[1", "unexpected end of input, expected ',' or ']'", 2),
                arguments("[", "unexpected end of input, expected a value", 1),
                arguments("{", "unexpected end of input, expected an object key", 1),
                arguments("{\"a\"", "unexpected end of input, expected ':'", 4),
                arguments("{\"a\":", "unexpected end of input, expected a value", 5),
                arguments("{\"a\":1", "unexpected end of input, expected ',' or '}'", 6),
                arguments("1 2", "unexpected '2' after the end of the JSON value", 2),
                arguments("{} x", "unexpected 'x' after the end of the JSON value", 3),
                arguments("\uFEFF1", "unexpected U+FEFF, expected a value", 0),
                arguments("\u00a01", "unexpected U+00A0, expected a value", 0),
                arguments("\f1", "unexpected U+000C, expected a value", 0));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("malformed")
    void rejectsMalformedInputWithMessageAndOffset(String input, String fragment, int offset) {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse(input));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
        assertEquals(offset, e.offset(), e.getMessage());
        assertTrue(e.getMessage().endsWith(" at offset " + offset), e.getMessage());
    }

    @Test
    void nullTextIsRejected() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse(null));
        assertEquals(-1, e.offset());
    }

    @Test
    void acceptsNestingUpToTwoHundredLevels() {
        Object parsed = Json.parse(nestedArrays(200));
        int depth = 0;
        while (parsed instanceof List && !((List<?>) parsed).isEmpty()) {
            parsed = ((List<?>) parsed).get(0);
            depth++;
        }
        assertEquals(199, depth);
        Json.parse(nestedObjects(200));
    }

    @Test
    void rejectsNestingBeyondTwoHundredLevels() {
        JsonException arrays = assertThrows(JsonException.class, () -> Json.parse(nestedArrays(201)));
        assertTrue(arrays.getMessage().startsWith("nesting depth exceeds 200"), arrays.getMessage());
        assertEquals(200, arrays.offset());
        JsonException objects = assertThrows(JsonException.class, () -> Json.parse(nestedObjects(201)));
        assertTrue(objects.getMessage().startsWith("nesting depth exceeds 200"), objects.getMessage());
        assertEquals(200 * 5, objects.offset());
    }

    @Test
    void parseObjectRequiresAnObjectAtTheTopLevel() {
        assertTrue(Json.parseObject(" {} ").isEmpty());
        assertEquals(Long.valueOf(1), Json.parseObject("{\"a\":1}").get("a"));
        JsonException e = assertThrows(JsonException.class, () -> Json.parseObject("  [1]"));
        assertTrue(e.getMessage().contains("expected a JSON object but found an array"), e.getMessage());
        assertEquals(2, e.offset());
        JsonException nul = assertThrows(JsonException.class, () -> Json.parseObject("null"));
        assertTrue(nul.getMessage().contains("found null"), nul.getMessage());
    }

    static String nestedArrays(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append('[');
        }
        for (int i = 0; i < depth; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    /** {"a":{"a":...{}...}}: each level adds the 5 characters {"a": before the next '{'. */
    static String nestedObjects(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth - 1; i++) {
            sb.append("{\"a\":");
        }
        sb.append("{}");
        for (int i = 0; i < depth - 1; i++) {
            sb.append('}');
        }
        return sb.toString();
    }
}
