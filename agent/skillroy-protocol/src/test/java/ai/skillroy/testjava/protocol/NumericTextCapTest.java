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
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Security finding S13: a numeric field sent as a huge digit string must be refused at once, not parsed by
 * {@link BigDecimal} (quadratic in the digit count) on an HTTP or controller thread.
 */
class NumericTextCapTest {

    private static final Duration FAST = Duration.ofSeconds(2);
    private static final String HUGE = digits(1 << 20);

    @Test
    void hugeDigitStringsAreNotNumbers() {
        assertTimeoutPreemptively(FAST, () -> {
            assertNull(Coerce.toLong(HUGE));
            assertNull(Coerce.toDouble(HUGE));
            assertNull(Coerce.toLong(new StringBuilder(HUGE)));
            assertNull(Coerce.toDouble("  " + HUGE + "  "));
        });
    }

    @Test
    void jsonViewReportsTheNormalTypeError() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("timeoutMs", HUGE);
        assertTimeoutPreemptively(FAST, () -> {
            RpcException e = assertThrows(RpcException.class, () -> JsonView.of(map).num("timeoutMs", 1));
            assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
            RpcException d = assertThrows(RpcException.class, () -> JsonView.of(map).dbl("timeoutMs", 1));
            assertEquals(ErrorCodes.INVALID_PARAMS, d.error().code());
        });
    }

    @Test
    void wireTypesRejectAHugeSequenceNumber() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("seq", HUGE);
        json.put("ts", Long.valueOf(1));
        json.put("topic", "t");
        assertTimeoutPreemptively(FAST, () -> {
            assertThrows(JsonException.class, () -> Event.fromJson(json));
        });
    }

    @Test
    void theCapIsAtTheBoundary() {
        String atCap = "1" + zeros(Coerce.MAX_NUMERIC_TEXT - 3) + ".0"; // 64 characters, too large for a long
        assertEquals(Coerce.MAX_NUMERIC_TEXT, atCap.length());
        assertNull(Coerce.toLong(atCap));
        assertEquals(Double.valueOf(1e61), Coerce.toDouble(atCap));
        String padded = "0000000000" + zeros(Coerce.MAX_NUMERIC_TEXT - 12) + "42";
        assertEquals(Coerce.MAX_NUMERIC_TEXT, padded.length());
        assertEquals(Long.valueOf(42), Coerce.toLong(padded));
        assertNull(Coerce.toLong("0" + padded));
        // surrounding blanks do not count against the cap, within the cheap pre-check's slack
        assertEquals(Long.valueOf(42), Coerce.toLong("        " + padded + "        "));
        assertNull(Coerce.toLong(blanks(9) + padded + blanks(8)));
    }

    @Test
    void ordinaryNumericTextStillCoerces() {
        assertEquals(Long.valueOf(42), Coerce.toLong("42"));
        assertEquals(Long.valueOf(42), Coerce.toLong(" 42 "));
        assertEquals(Long.valueOf(42), Coerce.toLong("4.2e1"));
        assertEquals(Long.valueOf(1000), Coerce.toLong("1e3"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), Coerce.toLong("-9223372036854775808"));
        assertEquals(Long.valueOf(1000000000000000000L), Coerce.toLong("1000000000000000000.000000"));
        assertNull(Coerce.toLong("4.5"));
        assertNull(Coerce.toLong("9223372036854775808"));
        assertNull(Coerce.toLong("x"));
        assertEquals(Double.valueOf(4.5), Coerce.toDouble("4.5"));
        assertEquals(Double.valueOf(1e3), Coerce.toDouble(" 1e3 "));
        assertEquals(Double.valueOf(Double.MAX_VALUE), Coerce.toDouble(Double.toString(Double.MAX_VALUE)));
        assertNull(Coerce.toDouble("1e400"));
    }

    @Test
    void bigNumbersAreConvertedWithoutAStringRoundTrip() {
        assertEquals(Long.valueOf(Long.MAX_VALUE), Coerce.toLong(BigInteger.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.valueOf(Long.MIN_VALUE), Coerce.toLong(BigInteger.valueOf(Long.MIN_VALUE)));
        assertNull(Coerce.toLong(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
        assertEquals(Long.valueOf(7), Coerce.toLong(new BigDecimal("7.000")));
        assertNull(Coerce.toLong(new BigDecimal("7.5")));
        assertNull(Coerce.toLong(new BigDecimal("1e40")));
        assertTimeoutPreemptively(FAST, () -> {
            // far more digits than the text cap; these are already numbers, so no parse is involved
            assertNull(Coerce.toLong(new BigInteger(HUGE.substring(0, 20000))));
            assertNull(Coerce.toLong(new BigDecimal("1e1000000")));
            assertEquals(Long.valueOf(1), Coerce.toLong(new BigDecimal(BigInteger.ONE.shiftLeft(100))
                    .divide(new BigDecimal(BigInteger.ONE.shiftLeft(100)))));
        });
    }

    private static String digits(int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, '9');
        return new String(chars);
    }

    private static String zeros(int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, '0');
        return new String(chars);
    }

    private static String blanks(int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, ' ');
        return new String(chars);
    }
}
