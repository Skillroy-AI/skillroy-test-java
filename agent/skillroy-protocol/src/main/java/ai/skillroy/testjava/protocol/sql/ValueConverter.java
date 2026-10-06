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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Calendar;

/**
 * Turns one cell value, as a JDBC driver or a data source provider returns it, into a JSON-able value before it is
 * masked and leaves the JVM. Keeps the wire form of {@code data.query} independent of the driver, and makes a value
 * read through the controller's JDBC channel take exactly the form it takes through the agent (phase2-contracts.md
 * 2.2).
 *
 * <ul>
 *   <li>null, Boolean and Long stay as they are; Integer, Short and Byte become Long; Float becomes the Double with
 *       the same decimal form; other numbers stay numbers, except that BigDecimal becomes its plain string
 *       ({@code toPlainString}; its {@code toString} with an exponent if the plain form would be longer than the
 *       text cap) and NaN and the infinities, which JSON cannot hold, become "NaN", "Infinity" and
 *       "-Infinity".</li>
 *   <li>Dates and times become ISO-8601 strings: {@code java.sql.Date} {@code 2026-10-01}, {@code java.sql.Time}
 *       {@code 10:15:30}, {@code java.sql.Timestamp} and {@code LocalDateTime} {@code 2026-10-01T10:15:30.123}
 *       (no zone, as stored), any other {@code java.util.Date} the UTC instant {@code 2026-10-01T08:15:30Z};
 *       {@code java.time} values and {@code Calendar} their ISO form, with the offset when they carry one.</li>
 *   <li>{@code byte[]} and {@code Blob} become {@code "base64:<first 1024 bytes>"}, followed by
 *       {@code "...[truncated: <total> bytes]"} when longer.</li>
 *   <li>Text ({@code String}, {@code Clob}, {@code SQLXML}, {@code char[]}) and every other value's
 *       {@code toString()} are capped at 10000 characters, followed by {@code "...[truncated: <total> chars]"} when
 *       longer.</li>
 * </ul>
 *
 * <p>Large objects are read while their result set is open and freed afterwards. Stateless; safe from any thread.
 */
public final class ValueConverter {

    /** Characters of text kept per value. */
    public static final int MAX_TEXT_CHARS = 10000;
    /** Bytes of binary data kept per value. */
    public static final int MAX_BINARY_BYTES = 1024;
    /** Prefix of an encoded binary value. */
    public static final String BINARY_PREFIX = "base64:";

    private ValueConverter() {
    }

    /**
     * The JSON-able form of {@code value}.
     *
     * @throws SQLException if a large object cannot be read
     */
    public static Object convert(Object value) throws SQLException {
        if (value == null || value instanceof Boolean || value instanceof Long) {
            return value;
        }
        if (value instanceof String) {
            return text((String) value);
        }
        if (value instanceof Number) {
            return number((Number) value);
        }
        if (value instanceof java.util.Date) {
            return date((java.util.Date) value);
        }
        String temporal = temporal(value);
        if (temporal != null) {
            return temporal;
        }
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            return binary(bytes, bytes.length);
        }
        if (value instanceof Blob) {
            return blob((Blob) value);
        }
        if (value instanceof Clob) {
            return clob((Clob) value);
        }
        if (value instanceof SQLXML) {
            return sqlxml((SQLXML) value);
        }
        if (value instanceof char[]) {
            return text(new String((char[]) value));
        }
        return text(safeToString(value));
    }

    /** True for the values {@link #convert} returns unchanged: null, String, Boolean, Long and finite Double. */
    public static boolean isNormal(Object value) {
        return value == null || value instanceof String || value instanceof Boolean || value instanceof Long
                || value instanceof Double && !((Double) value).isNaN() && !((Double) value).isInfinite();
    }

    private static Object number(Number n) {
        if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return Long.valueOf(n.longValue());
        }
        if (n instanceof BigDecimal) {
            BigDecimal d = (BigDecimal) n;
            // The plain form of 1E+1000000000 would fill the heap; a value that long keeps its exponent.
            boolean plainFits = (long) d.precision() + Math.abs((long) d.scale()) <= MAX_TEXT_CHARS;
            return plainFits ? d.toPlainString() : text(d.toString());
        }
        if (n instanceof BigInteger) {
            return n;
        }
        if (n instanceof Double) {
            double d = n.doubleValue();
            return isFinite(d) ? n : String.valueOf(d);
        }
        if (n instanceof Float) {
            String s = n.toString();
            return isFinite(n.floatValue()) ? (Object) Double.valueOf(s) : s;
        }
        // Atomic and accumulating numbers, driver-specific types: take the value now.
        double d = n.doubleValue();
        if (!isFinite(d)) {
            return String.valueOf(d);
        }
        if (d == Math.rint(d) && Math.abs(d) < 0x1p53) {
            return Long.valueOf(n.longValue());
        }
        return Double.valueOf(d);
    }

    private static boolean isFinite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static String date(java.util.Date date) {
        if (date instanceof Timestamp) {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(((Timestamp) date).toLocalDateTime());
        }
        if (date instanceof java.sql.Date) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(((java.sql.Date) date).toLocalDate());
        }
        if (date instanceof Time) {
            Time time = (Time) date;
            int millis = (int) Math.floorMod(time.getTime(), 1000L);
            return DateTimeFormatter.ISO_LOCAL_TIME.format(time.toLocalTime().withNano(millis * 1000000));
        }
        return DateTimeFormatter.ISO_INSTANT.format(date.toInstant());
    }

    /** ISO-8601 form of a java.time value or a Calendar; null for anything else. */
    private static String temporal(Object value) {
        if (value instanceof LocalDateTime) {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format((LocalDateTime) value);
        }
        if (value instanceof LocalDate) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format((LocalDate) value);
        }
        if (value instanceof LocalTime) {
            return DateTimeFormatter.ISO_LOCAL_TIME.format((LocalTime) value);
        }
        if (value instanceof OffsetDateTime) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format((OffsetDateTime) value);
        }
        if (value instanceof OffsetTime) {
            return DateTimeFormatter.ISO_OFFSET_TIME.format((OffsetTime) value);
        }
        if (value instanceof ZonedDateTime) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format((ZonedDateTime) value);
        }
        if (value instanceof Instant) {
            return DateTimeFormatter.ISO_INSTANT.format((Instant) value);
        }
        if (value instanceof Calendar) {
            Calendar calendar = (Calendar) value;
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                    ZonedDateTime.ofInstant(calendar.toInstant(), calendar.getTimeZone().toZoneId()));
        }
        return null;
    }

    private static String blob(Blob blob) throws SQLException {
        try {
            long length = blob.length();
            byte[] head = length == 0 ? new byte[0] : blob.getBytes(1, (int) Math.min(length, MAX_BINARY_BYTES));
            return binary(head, length);
        } finally {
            free(blob);
        }
    }

    private static String clob(Clob clob) throws SQLException {
        try {
            long length = clob.length();
            String head = length == 0 ? "" : clob.getSubString(1, (int) Math.min(length, MAX_TEXT_CHARS));
            return length > MAX_TEXT_CHARS ? cut(head) + marker(length, "chars") : text(head);
        } finally {
            free(clob);
        }
    }

    private static String sqlxml(SQLXML xml) throws SQLException {
        try {
            return text(xml.getString());
        } finally {
            try {
                xml.free();
            } catch (SQLException | RuntimeException | AbstractMethodError ignored) {
                // Freeing is a courtesy to the driver; the value has been read.
            }
        }
    }

    private static void free(Blob blob) {
        try {
            blob.free();
        } catch (SQLException | RuntimeException | AbstractMethodError ignored) {
            // Freeing is a courtesy to the driver; the value has been read.
        }
    }

    private static void free(Clob clob) {
        try {
            clob.free();
        } catch (SQLException | RuntimeException | AbstractMethodError ignored) {
            // Freeing is a courtesy to the driver; the value has been read.
        }
    }

    static String binary(byte[] head, long totalBytes) {
        byte[] kept = head.length > MAX_BINARY_BYTES ? Arrays.copyOf(head, MAX_BINARY_BYTES) : head;
        String encoded = BINARY_PREFIX + Base64.getEncoder().encodeToString(kept);
        return totalBytes > MAX_BINARY_BYTES ? encoded + marker(totalBytes, "bytes") : encoded;
    }

    static String text(String s) {
        return s.length() <= MAX_TEXT_CHARS ? s : cut(s) + marker(s.length(), "chars");
    }

    /** The head of a text being truncated: MAX_TEXT_CHARS characters, one fewer if that would split a pair. */
    private static String cut(String s) {
        int end = Math.min(s.length(), MAX_TEXT_CHARS);
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    static String marker(long total, String unit) {
        return "...[truncated: " + total + " " + unit + "]";
    }

    private static String safeToString(Object value) {
        try {
            return String.valueOf(value.toString());
        } catch (RuntimeException failed) {
            return "<" + value.getClass().getName() + ": toString failed>";
        }
    }
}
