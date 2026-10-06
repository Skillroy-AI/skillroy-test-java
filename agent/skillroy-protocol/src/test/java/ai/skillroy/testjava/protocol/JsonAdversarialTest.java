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
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Inputs chosen to break the codec: limits, odd numbers, surrogates, truncation, mutation and random round trips. */
class JsonAdversarialTest {

    private static final String DOCUMENT = "{\"a\":[1,-2.5e3,true,false,null,\"s\\n\\u00e9\\\\\",{\"b\":{}},[]],"
            + "\"c\":{\"d\":[[\"x\"],{\"e\":0}]},\"f\":\"\"}";

    // ---- nesting ----

    @Test
    void mixedNestingIsCountedPerContainerAtAndBeyondTheLimit() {
        assertEquals(nested(200), Json.parse(Json.write(nested(200))));
        assertEquals(nested(200), Json.parse(Json.writePretty(nested(200))));
        assertEquals(nested(200), Json.parse(Json.writeCanonical(nested(200))));
        String tooDeep = "[" + Json.write(nested(200)) + "]";
        assertTrue(assertThrows(JsonException.class, () -> Json.parse(tooDeep)).getMessage()
                .startsWith("nesting depth exceeds 200"));
        Object deeper = Collections.singletonList(nested(200));
        assertThrows(JsonException.class, () -> Json.write(deeper));
        assertThrows(JsonException.class, () -> Json.writePretty(deeper));
        assertThrows(JsonException.class, () -> Json.writeCanonical(deeper));
    }

    @Test
    void scalarsInsideTheDeepestContainerDoNotCountAsALevel() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append('[');
        }
        sb.append("1,\"x\",null");
        for (int i = 0; i < 200; i++) {
            sb.append(']');
        }
        Object parsed = Json.parse(sb.toString());
        assertEquals(sb.toString(), Json.write(parsed));
    }

    @Test
    void absurdNestingFailsWithAJsonExceptionNotAStackOverflow() {
        char[] open = new char[200000];
        Arrays.fill(open, '[');
        JsonException arrays = assertThrows(JsonException.class, () -> Json.parse(new String(open)));
        assertEquals(200, arrays.offset());
        StringBuilder objects = new StringBuilder();
        for (int i = 0; i < 50000; i++) {
            objects.append("{\"k\":");
        }
        assertThrows(JsonException.class, () -> Json.parse(objects.toString()));
    }

    @Test
    void depthIsReleasedWhenAContainerCloses() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1000; i++) {
            sb.append(i == 0 ? "" : ",").append("[[[{\"a\":[{}]}]]]");
        }
        sb.append(']');
        assertEquals(1000, ((List<?>) Json.parse(sb.toString())).size());
        assertEquals(sb.toString(), Json.write(Json.parse(sb.toString())));
    }

    // ---- numbers ----

    @Test
    void hugeAndTinyNumbers() {
        assertEquals(Double.valueOf(1e308), Json.parse("1" + zeros(308)));
        assertThrows(JsonException.class, () -> Json.parse("1" + zeros(309)));
        assertThrows(JsonException.class, () -> Json.parse("-1" + zeros(400)));
        assertThrows(JsonException.class, () -> Json.parse("1e999999999999999999999999"));
        assertThrows(JsonException.class, () -> Json.parse("[1E+309]"));
        assertEquals(Double.valueOf(0.0), Json.parse("1e-999999999999999999999999"));
        assertEquals(Double.valueOf(-0.0), Json.parse("-1e-999999"));
        assertEquals(Double.valueOf(Double.MIN_VALUE), Json.parse("4.9E-324"));
        assertEquals(Double.valueOf(1.0), Json.parse("0." + zeros(400) + "1e401"));
        assertEquals(Double.valueOf(0.1), Json.parse("0.1" + zeros(5000)));
        assertEquals(Double.valueOf(1.0), Json.parse("1" + zeros(400) + "e-400"));
        String digits = "1234567890123456789012345678901234567890";
        assertEquals(Double.valueOf(1.2345678901234568E39), Json.parse(digits));
        assertEquals(Double.valueOf(Double.parseDouble("0." + digits)), Json.parse("0." + digits));
    }

    /**
     * Java 8's {@code Double.parseDouble} reports these in-range numbers as infinite (it ignores the zeros after the
     * decimal point when it checks the exponent); the codec must read them as every later runtime does.
     */
    @Test
    void aLargeExponentOffsetByLeadingFractionZerosIsInRangeOnEveryRuntime() {
        assertEquals(Double.valueOf(1e308), Json.parse("0.000000000000000001e326"));
        assertEquals(Double.valueOf(-1e308), Json.parse("-0.000000000000000001E+326"));
        assertEquals(Double.valueOf(-1.0), Json.parse("-0." + zeros(400) + "1e401"));
        assertEquals(Double.valueOf(1.25), Json.parse("0." + zeros(350) + "125e351"));
        assertEquals(Arrays.asList(Double.valueOf(1.0)), Json.parse("[0." + zeros(400) + "1e401]"));
        assertEquals(Double.valueOf(Double.MAX_VALUE), Json.parse("0." + zeros(20) + "17976931348623157e329"));
        assertEquals(Double.valueOf(12.5), Json.parse("0.000" + zeros(400) + "125e405"));
        // Really out of range, however it is written.
        for (String tooBig : new String[] {"0.01e400", "0." + zeros(20) + "18e329", "-0.001e999999999999999999",
            "0." + zeros(10) + "123456789012345678901234567890e330", "1" + zeros(1000) + "e-500"}) {
            assertThrows(JsonException.class, () -> Json.parse(tooBig), tooBig);
        }
        // In range with a huge integer part and a negative exponent, or simply zero.
        assertEquals(Double.valueOf(1e100), Json.parse("1" + zeros(1000) + "e-900"));
        assertEquals(Double.valueOf(0.0), Json.parse("0." + zeros(400) + "e999"));
    }

    @Test
    void exponentForms() {
        assertEquals(Double.valueOf(0.0), Json.parse("0e0"));
        assertEquals(Double.valueOf(0.0), Json.parse("0E+0"));
        assertEquals(Double.valueOf(0.0), Json.parse("0.0e-0"));
        assertEquals(Double.valueOf(1000.0), Json.parse("1e3"));
        assertEquals(Double.valueOf(1000.0), Json.parse("1E3"));
        assertEquals(Double.valueOf(1000.0), Json.parse("1e+3"));
        assertEquals(Double.valueOf(1000.0), Json.parse("1.0e+003"));
        assertEquals(Double.valueOf(0.001), Json.parse("1e-3"));
        assertEquals(Double.valueOf(-12.5), Json.parse("-125e-1"));
        for (String bad : new String[] {"1e", "1e+", "1e-", "1e+-1", "1ee1", "1e1.5", "1.e1", "e1", "1e 1", "0e",
            "--1", "-+1", "1-", "1e1e1", "0.1.2", "1..2", "-.5", "00", "-00", "0123", "1_000", "1,000.5x"}) {
            assertThrows(JsonException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void negativeZeroKeepsItsSignOnlyAsADouble() {
        assertEquals(Long.valueOf(0), Json.parse("-0"));
        assertEquals("0", Json.write(Json.parse("-0")));
        for (String text : new String[] {"-0.0", "-0e0", "-0.000", "-0E-5"}) {
            Object parsed = Json.parse(text);
            assertInstanceOf(Double.class, parsed, text);
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(((Double) parsed).doubleValue()));
            assertEquals("-0.0", Json.write(parsed));
        }
        assertEquals("0.0", Json.write(Double.valueOf(0.0)));
        assertEquals("-0.0", Json.write(Float.valueOf(-0.0f)));
    }

    @Test
    void longBoundariesSurviveARoundTripAndOverflowBecomesDouble() {
        for (long value : new long[] {Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, 1L << 53, (1L << 53) + 1,
            -(1L << 53) - 1, 0, -1}) {
            assertEquals(Long.valueOf(value), Json.parse(Json.write(Long.valueOf(value))));
        }
        assertInstanceOf(Double.class, Json.parse("9223372036854775808"));
        assertInstanceOf(Double.class, Json.parse("-9223372036854775809"));
        assertInstanceOf(Double.class, Json.parse("9223372036854775807.0"));
        assertEquals("18446744073709551616", Json.write(BigInteger.ONE.shiftLeft(64)));
    }

    @Test
    void everyWrittenDoubleParsesBackBitForBit() {
        Random random = new Random(42);
        double[] fixed = {Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_NORMAL, 1e21, 1e20,
            999999999999999900000.0, 1e-7, 1e7, 123456789.125, 0.1 + 0.2, 5e-324, 1.0 / 3, -1e21, 9.999999999999999e22,
            (double) Long.MAX_VALUE, (double) Long.MIN_VALUE, 4.35, 100.0, 1e15, 1e16, 1e17};
        for (double d : fixed) {
            assertDoubleRoundTrip(d);
        }
        for (int i = 0; i < 20000; i++) {
            double d = Double.longBitsToDouble(random.nextLong());
            if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                assertDoubleRoundTrip(d);
            }
        }
    }

    private static void assertDoubleRoundTrip(double d) {
        String text = Json.write(Double.valueOf(d));
        assertTrue(Json.isJsonNumber(text), text);
        Object back = Json.parse(text);
        assertInstanceOf(Double.class, back, text);
        assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(((Double) back).doubleValue()), text);
    }

    @Test
    void unknownNumberTypesAreWrittenAsNumbers() {
        LongAdder adder = new LongAdder();
        adder.add(41);
        assertEquals("41", Json.write(adder));
        assertEquals("[1E+1000]", Json.write(Collections.singletonList(new BigDecimal("1E+1000"))));
        Number odd = new Number() {
            private static final long serialVersionUID = 1L;

            @Override
            public int intValue() {
                return 7;
            }

            @Override
            public long longValue() {
                return 7;
            }

            @Override
            public float floatValue() {
                return 7.5f;
            }

            @Override
            public double doubleValue() {
                return 7.5;
            }

            @Override
            public String toString() {
                return "seven and a half";
            }
        };
        assertEquals("7.5", Json.write(odd));
    }

    // ---- strings ----

    @Test
    void surrogatePairsAndLoneSurrogatesRoundTrip() {
        String[] samples = {"\uD83D\uDE00", "\uD800", "\uDC00", "\uDBFF\uDFFF", "\uDE00\uD83D", "a\uD83Db",
            "\uD83D\uD83D\uDE00", "\uD83D\uDE00\uDE00", "\uD800\uD800", "x\uDFFF", "\uD83D"};
        for (String sample : samples) {
            String written = Json.write(sample);
            assertEquals(sample, Json.parse(written), written);
            for (int i = 0; i < written.length(); i++) {
                char c = written.charAt(i);
                if (Character.isSurrogate(c)) {
                    boolean paired = Character.isHighSurrogate(c)
                            ? i + 1 < written.length() && Character.isLowSurrogate(written.charAt(i + 1))
                            : i > 0 && Character.isHighSurrogate(written.charAt(i - 1));
                    assertTrue(paired, "unpaired surrogate written raw in " + written);
                }
            }
        }
        assertEquals("\uD83D\uDE00", Json.parse("\"\\ud83d\\ude00\""));
        assertEquals("\uD83D\uDE00", Json.parse("\"\\uD83D\uDE00\""));
        assertEquals("\"\\ud800\"", Json.write("\uD800"));
        assertEquals("{\"\\udc00\":1}", Json.write(Collections.singletonMap("\uDC00", 1)));
    }

    @Test
    void embeddedNulAndControlCharacters() {
        assertEquals("a\u0000b", Json.parse("\"a\\u0000b\""));
        assertEquals("\"a\\u0000b\"", Json.write("a\u0000b"));
        assertThrows(JsonException.class, () -> Json.parse("\"a\u0000b\""));
        assertThrows(JsonException.class, () -> Json.parse("[1,\u00002]"));
        assertThrows(JsonException.class, () -> Json.parse("1\u0000"));
        assertThrows(JsonException.class, () -> Json.parse("\u00001"));
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("k\u0000", "\u0000");
        map.put("", "\u001f\u007f\u0080\u009f");
        assertEquals(map, Json.parse(Json.write(map)));
        for (char c = 0; c < 0x20; c++) {
            String raw = "\"" + c + "\"";
            assertThrows(JsonException.class, () -> Json.parse(raw));
            assertEquals(String.valueOf(c), Json.parse(Json.write(String.valueOf(c))));
        }
    }

    @Test
    void veryLongStrings() {
        StringBuilder plain = new StringBuilder();
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < 300000; i++) {
            plain.append((char) ('a' + i % 26));
            escaped.append(i % 3 == 0 ? '"' : i % 3 == 1 ? '\\' : '\n');
        }
        assertEquals(plain.toString(), Json.parse(Json.write(plain.toString())));
        assertEquals(escaped.toString(), Json.parse(Json.write(escaped.toString())));
        assertEquals(plain.length() + 2, Json.write(plain.toString()).length());
        StringBuilder unicode = new StringBuilder("\"");
        for (int i = 0; i < 100000; i++) {
            unicode.append("\\u0041");
        }
        unicode.append('"');
        assertEquals(100000, ((String) Json.parse(unicode.toString())).length());
        Map<String, Object> longKey = Collections.<String, Object>singletonMap(plain.toString(), plain.toString());
        assertEquals(longKey, Json.parse(Json.write(longKey)));
    }

    @Test
    void escapesAreCaseSensitiveAndComplete() {
        for (String bad : new String[] {"\"\\U0041\"", "\"\\a\"", "\"\\v\"", "\"\\0\"", "\"\\ \"", "\"\\N\"",
            "\"\\u004\"", "\"\\u 041\"", "\"\\u+041\"", "\"\\u-041\"", "\"\\u00x1\"", "\"\\\n\"", "\"\\"}) {
            assertThrows(JsonException.class, () -> Json.parse(bad), bad);
        }
        assertEquals("/", Json.parse("\"\\/\""));
        assertEquals("\"/\"", Json.write("/"));
        assertEquals("\\u0041", Json.parse("\"\\\\u0041\""));
    }

    // ---- whitespace, BOM ----

    @Test
    void whitespaceVariants() {
        String ws = " \t\r\n";
        String spaced = ws + "{" + ws + "\"a\"" + ws + ":" + ws + "[" + ws + "1" + ws + "," + ws + "\"x\"" + ws + "]"
                + ws + ",\r\n\"b\"\t:\t{\n}\r}" + ws;
        assertEquals("{\"a\":[1,\"x\"],\"b\":{}}", Json.write(Json.parse(spaced)));
        assertEquals(Collections.emptyList(), Json.parse("[ \t\r\n ]"));
        assertEquals(Collections.emptyMap(), Json.parse("{ \t\r\n }"));
        char[] notWhitespace = {'\u000b', '\f', '\u00a0', '\u0085', '\u1680', '\u2003', '\u2028', '\u2029', '\u3000',
            '\uFEFF', '\u0000', '\u001c'};
        for (char c : notWhitespace) {
            String hex = Integer.toHexString(c);
            assertThrows(JsonException.class, () -> Json.parse(c + "1"), hex);
            assertThrows(JsonException.class, () -> Json.parse("1" + c), hex);
            assertThrows(JsonException.class, () -> Json.parse("[1," + c + "2]"), hex);
            assertThrows(JsonException.class, () -> Json.parse("{" + c + "}"), hex);
            assertThrows(JsonException.class, () -> Json.parse("{\"a\"" + c + ":1}"), hex);
        }
        assertThrows(JsonException.class, () -> Json.parse("1 2"));
        assertThrows(JsonException.class, () -> Json.parse("- 1"));
        assertThrows(JsonException.class, () -> Json.parse("1 .5"));
        assertThrows(JsonException.class, () -> Json.parse("t rue"));
    }

    @Test
    void byteOrderMarkIsRejectedOutsideStringsAndKeptInside() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("\uFEFF{}"));
        assertEquals(0, e.offset());
        assertThrows(JsonException.class, () -> Json.parse("{}\uFEFF"));
        assertThrows(JsonException.class, () -> Json.parseObject("\uFEFF{\"a\":1}"));
        assertEquals("\uFEFFx", Json.parse("\"\uFEFFx\""));
        assertEquals("\"\uFEFFx\"", Json.write("\uFEFFx"));
    }

    // ---- truncation and mutation ----

    @Test
    void everyProperPrefixOfADocumentIsRejectedWithAnOffsetInsideIt() {
        String pretty = Json.writePretty(Json.parse(DOCUMENT));
        for (String document : new String[] {DOCUMENT, pretty.trim(), "[\"\\ud83d\\ude00\",-1.5E-3]", "\"abc\\n\"",
            "true", "null", "false", "{\"a\":{\"b\":{\"c\":[]}}}"}) {
            Json.parse(document);
            for (int cut = 0; cut < document.length(); cut++) {
                String prefix = document.substring(0, cut);
                try {
                    Object parsed = Json.parse(prefix);
                    fail("prefix of length " + cut + " parsed as " + parsed + ": " + prefix);
                } catch (JsonException expected) {
                    assertTrue(expected.offset() >= 0 && expected.offset() <= prefix.length(),
                            "offset " + expected.offset() + " for prefix " + prefix);
                }
            }
        }
    }

    @Test
    void truncatedNumbersEitherParseAsAShorterNumberOrFail() {
        String number = "-12.5e+10";
        for (int cut = 0; cut < number.length(); cut++) {
            String prefix = number.substring(0, cut);
            boolean valid = Json.isJsonNumber(prefix);
            if (valid) {
                assertInstanceOf(Number.class, Json.parse(prefix), prefix);
            } else {
                assertThrows(JsonException.class, () -> Json.parse(prefix), prefix);
            }
            assertThrows(JsonException.class, () -> Json.parse("[" + prefix), prefix);
        }
    }

    @Test
    void mutatedDocumentsNeverFailWithAnythingButJsonException() {
        Random random = new Random(7);
        String alphabet = "{}[]:,\"\\-+.eE0123456789 \n\ttruefalsn/u\u0000\u00e9\uD83D\uDE00'";
        int parsed = 0;
        for (int i = 0; i < 40000; i++) {
            StringBuilder sb = new StringBuilder(DOCUMENT);
            int edits = 1 + random.nextInt(3);
            for (int e = 0; e < edits && sb.length() > 0; e++) {
                int at = random.nextInt(sb.length());
                char c = alphabet.charAt(random.nextInt(alphabet.length()));
                switch (random.nextInt(4)) {
                    case 0:
                        sb.deleteCharAt(at);
                        break;
                    case 1:
                        sb.insert(at, c);
                        break;
                    case 2:
                        sb.setCharAt(at, c);
                        break;
                    default:
                        sb.setLength(at);
                }
            }
            String text = sb.toString();
            try {
                Object value = Json.parse(text);
                parsed++;
                assertEquals(value, Json.parse(Json.write(value)), text);
            } catch (JsonException expected) {
                assertTrue(expected.offset() >= 0 && expected.offset() <= text.length(), text);
            } catch (RuntimeException | StackOverflowError other) {
                fail("parse of " + text + " failed with " + other);
            }
        }
        assertTrue(parsed > 0, "some mutations should still be valid JSON");
    }

    @Test
    void randomGarbageNeverFailsWithAnythingButJsonException() {
        Random random = new Random(11);
        String alphabet = "{}[]:,\"\\-+.eE019 \ntrufalsn";
        for (int i = 0; i < 40000; i++) {
            int length = random.nextInt(24);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < length; j++) {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String text = sb.toString();
            try {
                Object value = Json.parse(text);
                assertEquals(value, Json.parse(Json.write(value)), text);
            } catch (JsonException expected) {
                assertTrue(expected.offset() >= 0 && expected.offset() <= text.length(), text);
            } catch (RuntimeException | StackOverflowError other) {
                fail("parse of " + text + " failed with " + other);
            }
        }
    }

    // ---- round trips of arbitrary structures ----

    @Test
    void randomStructuresRoundTripThroughEveryWriter() {
        Random random = new Random(20261001L);
        for (int i = 0; i < 3000; i++) {
            Object value = randomValue(random, 0);
            String compact = Json.write(value);
            assertEquals(value, Json.parse(compact), compact);
            assertEquals(compact, Json.write(Json.parse(compact)));
            assertEquals(value, Json.parse(Json.writePretty(value)), compact);
            assertEquals(value, Json.parse(Json.writeCanonical(value)), compact);
            assertEquals(Json.writeCanonical(value), Json.writeCanonical(shuffleKeys(value, random)), compact);
            assertEquals(Json.writeCanonical(value), Json.writeCanonical(Json.parse(Json.writeCanonical(value))));
            assertEquals(value, Coerce.freeze(value), compact);
            assertFalse(Json.writePretty(value).contains("\r"));
            assertFalse(Json.writePretty(value).contains("\t"));
        }
    }

    @Test
    void prettyOutputHasNoTrailingSpacesAndOneFinalNewline() {
        Random random = new Random(3);
        for (int i = 0; i < 300; i++) {
            Object value = randomContainer(random);
            String pretty = Json.writePretty(value);
            assertTrue(pretty.endsWith("\n") && !pretty.endsWith("\n\n"));
            for (String line : pretty.split("\n", -1)) {
                assertFalse(line.endsWith(" "), pretty);
            }
        }
        assertEquals("{}\n", Json.writePretty(Collections.emptyMap()));
        assertEquals("[\n  {},\n  [],\n  [\n    []\n  ]\n]\n",
                Json.writePretty(Arrays.<Object>asList(Collections.emptyMap(), Collections.emptyList(),
                        Collections.singletonList(Collections.emptyList()))));
    }

    // ---- values that iterate without end ----

    @Test
    void pathsAndThrowablesAreWrittenAsStringsNotIterated() {
        assertEquals("\"a\"", Json.write(Paths.get("a")));
        assertEquals("[\"x/y.txt\"]", Json.write(Collections.singletonList(Paths.get("x", "y.txt")))
                .replace("\\\\", "/"));
        SQLException sql = new SQLException("boom");
        assertEquals("\"java.sql.SQLException: boom\"", Json.write(sql));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("path", Paths.get("a"));
        payload.put("error", sql);
        Event event = new Event(1, 2, "t", null, null, null, payload);
        assertEquals("a", event.payload().get("path"));
        assertEquals("java.sql.SQLException: boom", event.payload().get("error"));
        assertEquals(event, Event.fromJson(Json.parseObject(Json.write(event.toJson()))));
    }

    // ---- generators ----

    /** {@code depth} containers nested alternately as array and object, with a scalar at the bottom. */
    private static Object nested(int depth) {
        Object value = "leaf";
        for (int i = depth; i >= 1; i--) {
            if (i % 2 == 0) {
                value = new ArrayList<Object>(Arrays.asList(Long.valueOf(i), value));
            } else {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                map.put("d" + i, value);
                value = map;
            }
        }
        return value;
    }

    private static String zeros(int n) {
        char[] zeros = new char[n];
        Arrays.fill(zeros, '0');
        return new String(zeros);
    }

    private static Object randomContainer(Random random) {
        Object value = randomValue(random, 0);
        return value instanceof Map || value instanceof List ? value : Collections.singletonList(value);
    }

    private static Object randomValue(Random random, int depth) {
        int kind = random.nextInt(depth > 5 ? 6 : 9);
        switch (kind) {
            case 0:
                return null;
            case 1:
                return Boolean.valueOf(random.nextBoolean());
            case 2:
                return Long.valueOf(random.nextBoolean() ? random.nextLong() : random.nextInt(2000) - 1000);
            case 3:
                double d = random.nextBoolean() ? Double.longBitsToDouble(random.nextLong())
                        : (random.nextInt(200000) - 100000) / 100.0;
                return Double.valueOf(Double.isNaN(d) || Double.isInfinite(d) ? 0.5 : d);
            case 4:
            case 5:
                return randomString(random);
            case 6:
            case 7:
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                int entries = random.nextInt(5);
                for (int i = 0; i < entries; i++) {
                    map.put(randomString(random), randomValue(random, depth + 1));
                }
                return map;
            default:
                List<Object> list = new ArrayList<Object>();
                int items = random.nextInt(5);
                for (int i = 0; i < items; i++) {
                    list.add(randomValue(random, depth + 1));
                }
                return list;
        }
    }

    private static String randomString(Random random) {
        int length = random.nextInt(12);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            switch (random.nextInt(8)) {
                case 0:
                    sb.append((char) random.nextInt(0x20));
                    break;
                case 1:
                    sb.append("\"\\/\b\f\n\r\t\u007f\u2028\u2029\uFEFF".charAt(random.nextInt(12)));
                    break;
                case 2:
                    sb.append((char) (0xD800 + random.nextInt(0x800)));
                    break;
                case 3:
                    sb.appendCodePoint(0x10000 + random.nextInt(0x100000));
                    break;
                case 4:
                    sb.append((char) random.nextInt(0x10000));
                    break;
                default:
                    sb.append((char) (' ' + random.nextInt(95)));
            }
        }
        return sb.toString();
    }

    /** A copy with every map's entries re-inserted in a random order. */
    private static Object shuffleKeys(Object value, Random random) {
        if (value instanceof Map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<Map.Entry<?, ?>>(((Map<?, ?>) value).entrySet());
            Collections.shuffle(entries, random);
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : entries) {
                copy.put((String) e.getKey(), shuffleKeys(e.getValue(), random));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                copy.add(shuffleKeys(item, random));
            }
            return copy;
        }
        return value;
    }
}
