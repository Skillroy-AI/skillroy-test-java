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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonWriteTest {

    @Test
    void writesScalars() {
        assertEquals("null", Json.write(null));
        assertEquals("true", Json.write(Boolean.TRUE));
        assertEquals("false", Json.write(Boolean.FALSE));
        assertEquals("\"abc\"", Json.write("abc"));
        assertEquals("\"sb\"", Json.write(new StringBuilder("sb")));
        assertEquals("\"x\"", Json.write(Character.valueOf('x')));
        assertEquals("\"ATTACH\"", Json.write(LoadMode.ATTACH));
    }

    @Test
    void writesIntegralNumbersExactly() {
        assertEquals("9223372036854775807", Json.write(Long.MAX_VALUE));
        assertEquals("-9223372036854775808", Json.write(Long.MIN_VALUE));
        assertEquals("5", Json.write(Integer.valueOf(5)));
        assertEquals("-3", Json.write(Short.valueOf((short) -3)));
        assertEquals("7", Json.write(Byte.valueOf((byte) 7)));
        assertEquals("11", Json.write(new AtomicLong(11)));
        assertEquals("123456789012345678901234567890",
                Json.write(new BigInteger("123456789012345678901234567890")));
        assertEquals("1.50", Json.write(new BigDecimal("1.50")));
        assertEquals("1E+3", Json.write(new BigDecimal("1E+3")));
        LongAdder adder = new LongAdder();
        adder.add(5);
        assertEquals("5", Json.write(adder));
    }

    @Test
    void writesDoublesWithoutExponentNoiseWhenIntegral() {
        assertEquals("1.0", Json.write(1.0));
        assertEquals("-1.0", Json.write(-1.0));
        assertEquals("0.0", Json.write(0.0));
        assertEquals("-0.0", Json.write(-0.0));
        assertEquals("1.5", Json.write(1.5));
        assertEquals("0.1", Json.write(0.1));
        assertEquals("10000000000.0", Json.write(1e10));
        assertEquals("123456789.0", Json.write(123456789.0));
        assertEquals("100000000000000000000.0", Json.write(1e20));
        assertEquals("123456789012345670000.0", Json.write(1.2345678901234567e20));
        assertEquals("9223372036854776000.0", Json.write(9.223372036854775807E18));
        assertEquals("1.0E21", Json.write(1e21));
        assertEquals("1.0E-5", Json.write(1e-5));
        assertEquals("1.7976931348623157E308", Json.write(Double.MAX_VALUE));
        assertEquals("4.9E-324", Json.write(Double.MIN_VALUE));
    }

    @Test
    void writesFloatsByTheirShortestDecimalForm() {
        assertEquals("0.1", Json.write(0.1f));
        assertEquals("1.0", Json.write(1.0f));
        assertEquals("10000000000.0", Json.write(1e10f));
    }

    @Test
    void writtenDoublesParseBackToTheSameValue() {
        double[] samples = {0.1, 1.0 / 3, -2.5e-300, 6.02214076e23, 1e20, 9007199254740993.0, 1234.5678};
        for (double d : samples) {
            assertEquals(Double.valueOf(d), Json.parse(Json.write(d)), "round trip of " + d);
        }
    }

    @Test
    void rejectsNanAndInfinity() {
        assertThrows(JsonException.class, () -> Json.write(Double.NaN));
        assertThrows(JsonException.class, () -> Json.write(Double.POSITIVE_INFINITY));
        assertThrows(JsonException.class, () -> Json.write(Double.NEGATIVE_INFINITY));
        assertThrows(JsonException.class, () -> Json.write(Float.NaN));
        assertThrows(JsonException.class, () -> Json.write(Float.POSITIVE_INFINITY));
        assertThrows(JsonException.class, () -> Json.write(Collections.singletonList(Double.NaN)));
        assertThrows(JsonException.class, () -> Json.write(new double[] {1, Double.NEGATIVE_INFINITY}));
    }

    @Test
    void escapesEveryControlCharacter() {
        StringBuilder input = new StringBuilder();
        StringBuilder expected = new StringBuilder("\"");
        for (char c = 0; c < 0x20; c++) {
            input.append(c);
            switch (c) {
                case '\b':
                    expected.append("\\b");
                    break;
                case '\f':
                    expected.append("\\f");
                    break;
                case '\n':
                    expected.append("\\n");
                    break;
                case '\r':
                    expected.append("\\r");
                    break;
                case '\t':
                    expected.append("\\t");
                    break;
                default:
                    expected.append(String.format("\\u%04x", (int) c));
            }
        }
        expected.append('"');
        assertEquals(expected.toString(), Json.write(input.toString()));
        assertEquals(input.toString(), Json.parse(Json.write(input.toString())));
    }

    @Test
    void escapesQuotesBackslashesAndLineSeparatorsButNotSlashOrDel() {
        assertEquals("\"a\\\"b\\\\c/d\u007f\"", Json.write("a\"b\\c/d\u007f"));
        assertEquals("\"\\u2028\\u2029\"", Json.write("\u2028\u2029"));
        assertEquals("\"\u00e9\u20ac\u2603\"", Json.write("\u00e9\u20ac\u2603"));
    }

    @Test
    void writesSurrogatePairsRawAndLoneSurrogatesEscaped() {
        assertEquals("\"\uD83D\uDE00\"", Json.write("\uD83D\uDE00"));
        assertEquals("\"\\ud83dx\"", Json.write("\uD83Dx"));
        assertEquals("\"x\\ud83d\"", Json.write("x\uD83D"));
        assertEquals("\"\\ude00\"", Json.write("\uDE00"));
        assertEquals("\"\\ude00\\ud83d\"", Json.write("\uDE00\uD83D"));
        assertEquals("\"\\ud83d\uD83D\uDE00\"", Json.write("\uD83D\uD83D\uDE00"));
        String lone = "a\uD800b\uDFFFc";
        assertEquals(lone, Json.parse(Json.write(lone)));
    }

    @Test
    void writesMapsWithStringifiedKeysInIterationOrder() {
        Map<Object, Object> map = new LinkedHashMap<Object, Object>();
        map.put("z", 1);
        map.put(Integer.valueOf(1), "one");
        map.put(Boolean.TRUE, "yes");
        map.put(null, "nothing");
        assertEquals("{\"z\":1,\"1\":\"one\",\"true\":\"yes\",\"null\":\"nothing\"}", Json.write(map));
    }

    @Test
    void rejectsMapKeysThatCollideOnceStringified() {
        Map<Object, Object> map = new LinkedHashMap<Object, Object>();
        map.put(Integer.valueOf(1), "a");
        map.put("1", "b");
        assertThrows(JsonException.class, () -> Json.write(map));
    }

    @Test
    void writesIterablesAndArrays() {
        assertEquals("[\"b\",\"a\"]", Json.write(new LinkedHashSet<String>(Arrays.asList("b", "a"))));
        assertEquals("[1,\"a\",null]", Json.write(new Object[] {1, "a", null}));
        assertEquals("[1,2]", Json.write(new int[] {1, 2}));
        assertEquals("[3]", Json.write(new long[] {3}));
        assertEquals("[4,-5]", Json.write(new short[] {4, -5}));
        assertEquals("[6]", Json.write(new byte[] {6}));
        assertEquals("[1.5]", Json.write(new double[] {1.5}));
        assertEquals("[0.1]", Json.write(new float[] {0.1f}));
        assertEquals("[true,false]", Json.write(new boolean[] {true, false}));
        assertEquals("[\"a\",\"b\"]", Json.write(new char[] {'a', 'b'}));
        assertEquals("[]", Json.write(new int[0]));
        assertEquals("[[1],[]]", Json.write(new int[][] {{1}, {}}));
        assertEquals("{}", Json.write(new LinkedHashMap<String, Object>()));
        assertEquals("[]", Json.write(Collections.emptyList()));
    }

    @Test
    void writesOtherObjectsAsTheirToString() {
        Object custom = new Object() {
            @Override
            public String toString() {
                return "custom \"value\"";
            }
        };
        assertEquals("\"custom \\\"value\\\"\"", Json.write(custom));
        Object nullString = new Object() {
            @Override
            public String toString() {
                return null;
            }
        };
        assertEquals("null", Json.write(nullString));
    }

    @Test
    void writesNestingUpToTwoHundredLevelsAndRejectsDeeperOrCyclicValues() {
        assertEquals(JsonParseTest.nestedArrays(200), Json.write(Json.parse(JsonParseTest.nestedArrays(200))));
        List<Object> deep = new ArrayList<Object>();
        List<Object> current = deep;
        for (int i = 1; i < 201; i++) {
            List<Object> next = new ArrayList<Object>();
            current.add(next);
            current = next;
        }
        JsonException tooDeep = assertThrows(JsonException.class, () -> Json.write(deep));
        assertTrue(tooDeep.getMessage().contains("deeper than 200"), tooDeep.getMessage());

        List<Object> cyclic = new ArrayList<Object>();
        cyclic.add(cyclic);
        assertThrows(JsonException.class, () -> Json.write(cyclic));
        Map<String, Object> cyclicMap = new LinkedHashMap<String, Object>();
        cyclicMap.put("self", cyclicMap);
        assertThrows(JsonException.class, () -> Json.writeCanonical(cyclicMap));
    }

    @Test
    void canonicalSortsKeysRecursivelyAndKeepsArrayOrder() {
        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        inner.put("z", 1);
        inner.put("y", 2);
        Map<String, Object> a = new LinkedHashMap<String, Object>();
        a.put("d", 1);
        a.put("c", Arrays.asList(inner, 3, "x"));
        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("b", 1);
        doc.put("a", a);
        String canonical = Json.writeCanonical(doc);
        assertEquals("{\"a\":{\"c\":[{\"y\":2,\"z\":1},3,\"x\"],\"d\":1},\"b\":1}", canonical);

        Map<String, Object> reordered = new TreeMap<String, Object>(Collections.reverseOrder());
        reordered.putAll(doc);
        assertEquals(canonical, Json.writeCanonical(reordered));
        assertEquals(canonical, Json.writeCanonical(Json.parse(canonical)));
        assertEquals("{\"b\":1,\"a\":{\"d\":1,\"c\":[{\"z\":1,\"y\":2},3,\"x\"]}}", Json.write(doc));
    }

    @Test
    void canonicalOrderIsUtf16CodeUnitOrder() {
        Map<Object, Object> map = new LinkedHashMap<Object, Object>();
        map.put("\u00e9", 1);
        map.put("b", 2);
        map.put("B", 3);
        map.put("a", 4);
        map.put(Integer.valueOf(10), 5);
        map.put(Integer.valueOf(9), 6);
        map.put("\uD83D\uDE00", 7);
        map.put("\uFFFF", 8);
        assertEquals("{\"10\":5,\"9\":6,\"B\":3,\"a\":4,\"b\":2,\"\u00e9\":1,\"\uD83D\uDE00\":7,\"\uFFFF\":8}",
                Json.writeCanonical(map));
    }

    @Test
    void prettyUsesTwoSpaceIndentAndNewlineLineEnds() {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("f", null);
        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("a", 1L);
        doc.put("b", Arrays.<Object>asList(1L, "two"));
        doc.put("c", new LinkedHashMap<String, Object>());
        doc.put("d", new ArrayList<Object>());
        doc.put("e", e);
        String expected = "{\n"
                + "  \"a\": 1,\n"
                + "  \"b\": [\n"
                + "    1,\n"
                + "    \"two\"\n"
                + "  ],\n"
                + "  \"c\": {},\n"
                + "  \"d\": [],\n"
                + "  \"e\": {\n"
                + "    \"f\": null\n"
                + "  }\n"
                + "}\n";
        assertEquals(expected, Json.writePretty(doc));
        assertEquals(doc, Json.parse(Json.writePretty(doc)));
        assertEquals("1\n", Json.writePretty(1));
        assertEquals("{}\n", Json.writePretty(Collections.emptyMap()));
        assertEquals("[\n  [\n    []\n  ]\n]\n", Json.writePretty(Json.parse("[[[]]]")));
    }

    @Test
    void parseOfWriteReturnsAnEqualValue() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("text", "line\nbreak \"quoted\" \u2028 \uD83D\uDE00 \\");
        nested.put("max", Long.MAX_VALUE);
        nested.put("min", Long.MIN_VALUE);
        nested.put("pi", 3.141592653589793);
        nested.put("big", 1e300);
        nested.put("tiny", -4.9E-324);
        nested.put("flags", Arrays.<Object>asList(true, false, null));
        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("nested", nested);
        doc.put("empty", new LinkedHashMap<String, Object>());
        doc.put("list", Arrays.<Object>asList(1L, 2.5, "x", new ArrayList<Object>()));
        assertEquals(doc, Json.parse(Json.write(doc)));
        assertEquals(doc, Json.parse(Json.writePretty(doc)));
        assertEquals(Json.writeCanonical(doc), Json.writeCanonical(Json.parse(Json.writeCanonical(doc))));
    }

    @Test
    void writeOfParseReturnsTheSameCompactText() {
        String[] texts = {
            "{\"a\":[1,2.5,-0.0,\"x\\n\",true,false,null,{}],\"b\":{\"c\":[]}}",
            "[\"\\u0000\\u001f\\\\\\\"/\",\"\\ud800\",\"\\u2028\"]",
            "9223372036854775807",
            "-9223372036854775808",
            "\"\u00e9\uD83D\uDE00\""
        };
        for (String text : texts) {
            assertEquals(text, Json.write(Json.parse(text)));
        }
    }
}
