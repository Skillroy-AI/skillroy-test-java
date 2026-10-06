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

package ai.skillroy.testjava.protocol.sql;

import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

import static ai.skillroy.testjava.protocol.sql.ValueConverter.convert;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueConverterTest {

    @Test
    void jsonScalarsStayAsTheyAre() throws Exception {
        assertNull(convert(null));
        assertSame(Boolean.TRUE, convert(Boolean.TRUE));
        Long l = Long.valueOf(9007199254740993L);
        assertSame(l, convert(l));
        Double d = Double.valueOf(1.5);
        assertSame(d, convert(d));
        assertEquals("text", convert("text"));
    }

    @Test
    void numbersStayNumbers() throws Exception {
        assertEquals(Long.valueOf(7), convert(Integer.valueOf(7)));
        assertEquals(Long.valueOf(-3), convert(Short.valueOf((short) -3)));
        assertEquals(Long.valueOf(4), convert(Byte.valueOf((byte) 4)));
        assertEquals(Double.valueOf(0.1), convert(Float.valueOf(0.1f)));
        BigInteger huge = new BigInteger("123456789012345678901234567890");
        assertSame(huge, convert(huge));
        assertEquals(Long.valueOf(42), convert(new AtomicLong(42)));
        assertEquals(Long.valueOf(5), convert(new AtomicInteger(5)));
        DoubleAdder adder = new DoubleAdder();
        adder.add(2.25);
        assertEquals(Double.valueOf(2.25), convert(adder));
    }

    @Test
    void bigDecimalBecomesItsPlainString() throws Exception {
        assertEquals("12.50", convert(new BigDecimal("12.50")));
        assertEquals("1000", convert(new BigDecimal("1E+3")));
        assertEquals("0.00000012", convert(new BigDecimal("1.2E-7")));
        assertEquals("-12345678901234567890.12300", convert(new BigDecimal("-12345678901234567890.12300")));
    }

    @Test
    void aBigDecimalWhosePlainFormWouldBeHugeKeepsItsExponent() throws Exception {
        // toPlainString() of these would need gigabytes inside the target JVM.
        assertEquals("1E+1000000000", convert(new BigDecimal("1E+1000000000")));
        assertEquals("-1.5E-999999999", convert(new BigDecimal("-1.5E-999999999")));
        assertEquals("1E+10000", convert(new BigDecimal("1E+10000")));
        // Up to the text cap the plain form is kept.
        assertEquals("1" + TestDb.repeat('0', 9998), convert(new BigDecimal("1E+9998")));
        assertEquals("0." + TestDb.repeat('0', 9998) + "1", convert(new BigDecimal("1E-9999")));
        // A value with more digits than the cap is cut like any other text.
        String digits = TestDb.repeat('7', 12000);
        assertEquals(TestDb.repeat('7', 10000) + "...[truncated: 12000 chars]", convert(new BigDecimal(digits)));
    }

    @Test
    void nanAndInfinitiesBecomeStrings() throws Exception {
        assertEquals("NaN", convert(Double.NaN));
        assertEquals("Infinity", convert(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", convert(Double.NEGATIVE_INFINITY));
        assertEquals("NaN", convert(Float.NaN));
        assertEquals("-Infinity", convert(Float.NEGATIVE_INFINITY));
        DoubleAdder adder = new DoubleAdder();
        adder.add(Double.POSITIVE_INFINITY);
        assertEquals("Infinity", convert(adder));
    }

    @Test
    void sqlDatesAndTimesBecomeIsoStringsWithoutZoneShift() throws Exception {
        assertEquals("2026-10-01", convert(java.sql.Date.valueOf("2026-10-01")));
        assertEquals("10:15:30", convert(Time.valueOf("10:15:30")));
        assertEquals("10:15:00", convert(Time.valueOf("10:15:00")));
        assertEquals("10:15:30.25", convert(new Time(Time.valueOf("10:15:30").getTime() + 250)));
        assertEquals("2026-10-01T10:15:30.123", convert(Timestamp.valueOf("2026-10-01 10:15:30.123")));
        assertEquals("2026-10-01T10:15:30", convert(Timestamp.valueOf("2026-10-01 10:15:30")));
        assertEquals("2026-10-01T10:15:00", convert(Timestamp.valueOf("2026-10-01 10:15:00")));
        assertEquals("2026-10-01T10:15:30.123456789", convert(Timestamp.valueOf("2026-10-01 10:15:30.123456789")));
    }

    @Test
    void utilDateBecomesTheUtcInstant() throws Exception {
        assertEquals("1970-01-01T00:00:00Z", convert(new java.util.Date(0)));
        assertEquals("2026-10-01T08:15:30.500Z", convert(java.util.Date.from(
                Instant.parse("2026-10-01T08:15:30.500Z"))));
    }

    @Test
    void javaTimeValuesBecomeIsoStrings() throws Exception {
        assertEquals("2026-10-01", convert(LocalDate.of(2026, 10, 1)));
        assertEquals("10:15:00", convert(LocalTime.of(10, 15)));
        assertEquals("2026-10-01T10:15:00", convert(LocalDateTime.of(2026, 10, 1, 10, 15)));
        assertEquals("2026-10-01T10:15:30+02:00",
                convert(OffsetDateTime.of(2026, 10, 1, 10, 15, 30, 0, ZoneOffset.ofHours(2))));
        assertEquals("10:15:00+02:00", convert(OffsetTime.of(10, 15, 0, 0, ZoneOffset.ofHours(2))));
        assertEquals("2026-10-01T10:15:30+02:00",
                convert(ZonedDateTime.of(2026, 10, 1, 10, 15, 30, 0, ZoneId.of("Europe/Paris"))));
        assertEquals("2026-10-01T08:15:30Z", convert(Instant.parse("2026-10-01T08:15:30Z")));
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(0);
        assertEquals("1970-01-01T00:00:00Z", convert(calendar));
    }

    @Test
    void bytesBecomeBase64CappedAtOneKilobyte() throws Exception {
        assertEquals("base64:", convert(new byte[0]));
        assertEquals("base64:AQL/", convert(new byte[] {1, 2, (byte) 0xFF}));
        byte[] exact = filled(ValueConverter.MAX_BINARY_BYTES);
        assertEquals("base64:" + Base64.getEncoder().encodeToString(exact), convert(exact));
        byte[] long1 = filled(ValueConverter.MAX_BINARY_BYTES + 1);
        String encoded = (String) convert(long1);
        assertTrue(encoded.endsWith("...[truncated: 1025 bytes]"), encoded);
        String base64 = encoded.substring("base64:".length(), encoded.indexOf("..."));
        assertArrayEquals(exact, Base64.getDecoder().decode(base64));
    }

    @Test
    void blobIsReadUpToOneKilobyteAndFreed() throws Exception {
        SerialBlob blob = new SerialBlob(filled(5000));
        String encoded = (String) convert(blob);
        assertTrue(encoded.startsWith("base64:AAECAwQF"), encoded);
        assertTrue(encoded.endsWith("...[truncated: 5000 bytes]"), encoded);
        assertEquals(1024, Base64.getDecoder().decode(
                encoded.substring("base64:".length(), encoded.indexOf("..."))).length);
        assertThrows(SQLException.class, blob::length, "freed after reading");
        assertEquals("base64:", convert(new SerialBlob(new byte[0])));
    }

    @Test
    void textIsCappedAtTenThousandCharacters() throws Exception {
        String exact = TestDb.repeat('x', ValueConverter.MAX_TEXT_CHARS);
        assertSame(exact, convert(exact));
        String longer = exact + "yz";
        assertEquals(exact + "...[truncated: 10002 chars]", convert(longer));
    }

    @Test
    void cappingNeverSplitsASurrogatePair() throws Exception {
        String head = TestDb.repeat('x', ValueConverter.MAX_TEXT_CHARS - 1);
        String text = head + "😀" + "tail";
        assertEquals(head + "...[truncated: " + text.length() + " chars]", convert(text));
    }

    @Test
    void clobIsReadAsTextCappedAndFreed() throws Exception {
        assertEquals("short", convert(new SerialClob("short".toCharArray())));
        assertEquals("", convert(new SerialClob(new char[0])));
        SerialClob big = new SerialClob(TestDb.repeat('c', 12000).toCharArray());
        assertEquals(TestDb.repeat('c', 10000) + "...[truncated: 12000 chars]", convert(big));
        assertThrows(SQLException.class, big::length, "freed after reading");
    }

    @Test
    void clobReadFailureIsReported() {
        Clob broken = (Clob) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Clob.class},
                (proxy, method, args) -> {
                    if ("free".equals(method.getName())) {
                        return null;
                    }
                    throw new SQLException("gone", "HY000");
                });
        SQLException e = assertThrows(SQLException.class, () -> convert(broken));
        assertEquals("gone", e.getMessage());
    }

    @Test
    void sqlxmlIsReadAsText() throws Exception {
        final AtomicBoolean freed = new AtomicBoolean();
        SQLXML xml = (SQLXML) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SQLXML.class},
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) {
                        return "<a>1</a>";
                    }
                    if ("free".equals(method.getName())) {
                        freed.set(true);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        assertEquals("<a>1</a>", convert(xml));
        assertTrue(freed.get());
    }

    @Test
    void otherValuesUseToStringCapped() throws Exception {
        UUID uuid = UUID.fromString("6f1e8f7a-0000-4000-8000-000000000001");
        assertEquals(uuid.toString(), convert(uuid));
        assertEquals("[1, 2]", convert(Arrays.asList(1, 2)));
        assertEquals("x", convert('x'));
        assertEquals("abc", convert(new char[] {'a', 'b', 'c'}));
        Object huge = new Object() {
            @Override
            public String toString() {
                return TestDb.repeat('h', 20000);
            }
        };
        assertEquals(TestDb.repeat('h', 10000) + "...[truncated: 20000 chars]", convert(huge));
        Object broken = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("no");
            }
        };
        String text = (String) convert(broken);
        assertTrue(text.startsWith("<") && text.endsWith(": toString failed>"), text);
    }

    @Test
    void isNormalNamesExactlyTheValuesConvertKeeps() {
        assertTrue(ValueConverter.isNormal(null));
        assertTrue(ValueConverter.isNormal("s"));
        assertTrue(ValueConverter.isNormal(Boolean.FALSE));
        assertTrue(ValueConverter.isNormal(1L));
        assertTrue(ValueConverter.isNormal(1.5d));
        assertFalse(ValueConverter.isNormal(Double.NaN));
        assertFalse(ValueConverter.isNormal(Double.POSITIVE_INFINITY));
        assertFalse(ValueConverter.isNormal(1));
        assertFalse(ValueConverter.isNormal(new BigDecimal("1")));
        assertFalse(ValueConverter.isNormal(new java.util.Date()));
    }

    private static byte[] filled(int n) {
        byte[] bytes = new byte[n];
        for (int i = 0; i < n; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }
}
