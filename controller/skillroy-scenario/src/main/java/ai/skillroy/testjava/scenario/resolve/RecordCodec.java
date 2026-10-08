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

package ai.skillroy.testjava.scenario.resolve;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The default {@link Codec}: reflection over record components, no dependency (phase2-contracts.md 6.2).
 *
 * <table>
 *   <caption>Java type and JSON-like form</caption>
 *   <tr><td>a record</td><td>a map keyed by component name. Decoding: an unknown key is an error, a missing key
 *       decodes as null (a missing primitive component is an error). Encoding writes the keys sorted, so that a
 *       bindings file does not depend on component order</td></tr>
 *   <tr><td>{@code String}, {@code Boolean}/{@code boolean}, {@code Long}/{@code long}, {@code Integer}/{@code int}
 *       </td><td>the scalar; a whole number that does not fit is an error. No conversion between kinds: a code
 *       written as a number is not a string</td></tr>
 *   <tr><td>{@code BigDecimal}</td><td>encoded as its plain string with its scale ({@code "34.99"}); decoded from a
 *       string or a number</td></tr>
 *   <tr><td>{@code Double}/{@code double}, {@code Float}/{@code float}</td><td>a JSON number: decoded from any finite
 *       number (a whole one included; text is not a number), a {@code float} only when the number fits one; encoded
 *       as a {@code Double}. What {@code Schemas} writes as {@code number}, such as a model's stated
 *       confidence</td></tr>
 *   <tr><td>an {@code enum}</td><td>its {@code name()}</td></tr>
 *   <tr><td>{@code LocalDate}, {@code Instant}</td><td>ISO-8601 text</td></tr>
 *   <tr><td>{@code List<E>}, {@code Map<String,V>}</td><td>list (order kept); map with sorted keys (values may be
 *       null)</td></tr>
 * </table>
 *
 * <p>{@code Optional}, arrays and every other class are not supported ({@link #unsupported}). At compile time
 * ({@link #check}) a string holding a {@code $reference} stands for any value.
 *
 * <p>Stateless; safe from any thread.
 */
public final class RecordCodec implements Codec {

    /** The shared instance. */
    public static final RecordCodec DEFAULT = new RecordCodec();

    private static final Pattern REFERENCE = Pattern.compile("(?<!\\$)\\$[A-Za-z_]");
    private static final int MAX_DEPTH = 100;

    private RecordCodec() {
    }

    @Override
    public String unsupported(Type type) {
        return unsupported(type, new HashSet<>(), 0);
    }

    private String unsupported(Type type, Set<Type> seen, int depth) {
        if (depth > MAX_DEPTH) {
            return "nesting too deep";
        }
        Class<?> raw = GenericTypes.raw(type);
        if (raw == null) {
            return "type " + type + " is not a concrete type";
        }
        if (isScalar(raw)) {
            return null;
        }
        if (raw.isRecord()) {
            if (!seen.add(raw)) {
                return null;
            }
            for (RecordComponent component : raw.getRecordComponents()) {
                String problem = unsupported(component.getGenericType(), seen, depth + 1);
                if (problem != null) {
                    return raw.getSimpleName() + "." + component.getName() + ": " + problem;
                }
            }
            return null;
        }
        if (raw == List.class) {
            Type element = argument(type, 0);
            return element == null ? "a List needs its element type" : unsupported(element, seen, depth + 1);
        }
        if (raw == Map.class) {
            Type key = argument(type, 0);
            Type value = argument(type, 1);
            if (key != String.class) {
                return "a Map needs String keys";
            }
            return value == null ? "a Map needs its value type" : unsupported(value, seen, depth + 1);
        }
        return "type " + raw.getName() + " is not supported by RecordCodec (records, String, Boolean, Long,"
                + " Integer, Double, Float, BigDecimal, enums, LocalDate, Instant, List and Map<String,V>)";
    }

    @Override
    public List<String> check(Type type, Object template) {
        List<String> problems = new ArrayList<>();
        check(type, template, "", problems, 0);
        return problems;
    }

    private void check(Type type, Object value, String path, List<String> problems, int depth) {
        if (depth > MAX_DEPTH || problems.size() >= 20) {
            return;
        }
        Class<?> raw = GenericTypes.raw(type);
        if (raw == null) {
            problems.add(at(path) + "type " + type + " is not supported");
            return;
        }
        if (value instanceof String s && REFERENCE.matcher(s).find()) {
            return;
        }
        if (value == null) {
            if (raw.isPrimitive()) {
                problems.add(at(path) + "a value is required");
            }
            return;
        }
        if (raw.isRecord()) {
            if (!(value instanceof Map<?, ?> map)) {
                problems.add(at(path) + "expected a mapping with keys " + componentNames(raw) + ", found "
                        + describe(value));
                return;
            }
            Map<String, RecordComponent> components = components(raw);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                RecordComponent component = components.get(key);
                if (component == null) {
                    problems.add(at(path) + "unknown key '" + key + "'; allowed: " + components.keySet());
                    continue;
                }
                check(component.getGenericType(), e.getValue(), join(path, key), problems, depth + 1);
            }
            for (RecordComponent component : components.values()) {
                if (component.getType().isPrimitive() && !map.containsKey(component.getName())) {
                    problems.add(at(join(path, component.getName())) + "a value is required");
                }
            }
            return;
        }
        if (raw == List.class) {
            if (!(value instanceof List<?> list)) {
                problems.add(at(path) + "expected a list, found " + describe(value));
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                check(argument(type, 0), list.get(i), path + "[" + i + "]", problems, depth + 1);
            }
            return;
        }
        if (raw == Map.class) {
            if (!(value instanceof Map<?, ?> map)) {
                problems.add(at(path) + "expected a mapping, found " + describe(value));
                return;
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                check(argument(type, 1), e.getValue(), join(path, String.valueOf(e.getKey())), problems, depth + 1);
            }
            return;
        }
        try {
            scalar(raw, value, path);
        } catch (CodecException e) {
            problems.add(e.getMessage());
        }
    }

    @Override
    public Object decode(Type type, Object jsonLike) throws CodecException {
        return decode(type, jsonLike, "", 0);
    }

    private Object decode(Type type, Object value, String path, int depth) throws CodecException {
        if (depth > MAX_DEPTH) {
            throw new CodecException(at(path) + "nesting too deep");
        }
        Class<?> raw = GenericTypes.raw(type);
        if (raw == null) {
            throw new CodecException(at(path) + "type " + type + " is not supported");
        }
        if (value == null) {
            if (raw.isPrimitive()) {
                throw new CodecException(at(path) + "a value is required");
            }
            return null;
        }
        if (raw.isRecord()) {
            return record(raw, value, path, depth);
        }
        if (raw == List.class) {
            if (!(value instanceof List<?> list)) {
                throw new CodecException(at(path) + "expected a list, found " + describe(value));
            }
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(decode(argument(type, 0), list.get(i), path + "[" + i + "]", depth + 1));
            }
            return java.util.Collections.unmodifiableList(out);
        }
        if (raw == Map.class) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new CodecException(at(path) + "expected a mapping, found " + describe(value));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, decode(argument(type, 1), e.getValue(), join(path, key), depth + 1));
            }
            return java.util.Collections.unmodifiableMap(out);
        }
        return scalar(raw, value, path);
    }

    private Object record(Class<?> raw, Object value, String path, int depth) throws CodecException {
        if (!(value instanceof Map<?, ?> map)) {
            throw new CodecException(at(path) + "expected a mapping with keys " + componentNames(raw) + ", found "
                    + describe(value));
        }
        Map<String, RecordComponent> components = components(raw);
        for (Object key : map.keySet()) {
            if (!components.containsKey(String.valueOf(key))) {
                throw new CodecException(at(path) + "unknown key '" + key + "'; allowed: " + components.keySet());
            }
        }
        RecordComponent[] declared = raw.getRecordComponents();
        Object[] args = new Object[declared.length];
        Class<?>[] types = new Class<?>[declared.length];
        for (int i = 0; i < declared.length; i++) {
            RecordComponent component = declared[i];
            types[i] = component.getType();
            args[i] = decode(component.getGenericType(), map.get(component.getName()),
                    join(path, component.getName()), depth + 1);
        }
        try {
            Constructor<?> constructor = raw.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new CodecException(at(path) + raw.getSimpleName() + " refused the value: "
                    + (cause == null ? e : cause.getMessage() == null ? cause.getClass().getSimpleName()
                    : cause.getMessage()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new CodecException(at(path) + "cannot construct " + raw.getSimpleName() + ": " + e);
        }
    }

    private static Object scalar(Class<?> raw, Object value, String path) throws CodecException {
        if (raw == String.class) {
            if (value instanceof String s) {
                return s;
            }
            throw new CodecException(at(path) + "expected text, found " + describe(value)
                    + " (write codes in quotes)");
        }
        if (raw == Boolean.class || raw == boolean.class) {
            if (value instanceof Boolean b) {
                return b;
            }
            throw new CodecException(at(path) + "expected true or false, found " + describe(value));
        }
        if (raw == Long.class || raw == long.class || raw == Integer.class || raw == int.class) {
            BigInteger whole = whole(value);
            if (whole == null) {
                throw new CodecException(at(path) + "expected a whole number, found " + describe(value));
            }
            boolean isInt = raw == Integer.class || raw == int.class;
            if (whole.bitLength() > (isInt ? 31 : 63)) {
                throw new CodecException(at(path) + "the whole number does not fit " + (isInt ? "an int" : "a long"));
            }
            return isInt ? (Object) whole.intValue() : (Object) whole.longValue();
        }
        if (raw == Double.class || raw == double.class || raw == Float.class || raw == float.class) {
            Double number = floating(value);
            if (number == null) {
                throw new CodecException(at(path) + "expected a number, found " + describe(value));
            }
            if (raw == Float.class || raw == float.class) {
                float narrowed = number.floatValue();
                if (Float.isInfinite(narrowed)) {
                    throw new CodecException(at(path) + "the number does not fit a float");
                }
                return narrowed;
            }
            return number;
        }
        if (raw == BigDecimal.class) {
            BigDecimal decimal = decimal(value);
            if (decimal == null) {
                throw new CodecException(at(path) + "expected a decimal (a number or its text), found "
                        + describe(value));
            }
            return decimal;
        }
        if (raw.isEnum()) {
            if (value instanceof String s) {
                for (Object constant : raw.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals(s)) {
                        return constant;
                    }
                }
            }
            List<String> names = new ArrayList<>();
            for (Object constant : raw.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            throw new CodecException(at(path) + "expected one of " + names + ", found " + describe(value));
        }
        if (raw == LocalDate.class || raw == Instant.class) {
            if (value instanceof String s) {
                try {
                    return raw == LocalDate.class ? LocalDate.parse(s) : Instant.parse(s);
                } catch (DateTimeParseException e) {
                    // reported below
                }
            }
            throw new CodecException(at(path) + "expected ISO-8601 text ("
                    + (raw == LocalDate.class ? "2026-10-02" : "2026-10-02T09:30:00Z") + "), found "
                    + describe(value));
        }
        throw new CodecException(at(path) + "type " + raw.getName() + " is not supported");
    }

    private static BigInteger whole(Object value) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger i) {
            return i;
        }
        if (value instanceof BigDecimal d) {
            try {
                return d.toBigIntegerExact();
            } catch (ArithmeticException e) {
                return null;
            }
        }
        return null;
    }

    /** A finite JSON number as a double (a whole number too); null for anything else, text included. */
    private static Double floating(Object value) {
        if (value instanceof Double || value instanceof Float || value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte || value instanceof BigDecimal
                || value instanceof BigInteger) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof BigInteger i) {
            return new BigDecimal(i);
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : BigDecimal.valueOf(d);
        }
        if (value instanceof String s && s.length() <= 400 && s.matches("-?\\d+(\\.\\d+)?")) {
            return new BigDecimal(s);
        }
        return null;
    }

    @Override
    public Object encode(Object typed) {
        return encode(typed, 0);
    }

    private Object encode(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nesting too deep to encode");
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Long) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("a number that is not finite cannot be encoded");
            }
            return d;
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof LocalDate || value instanceof Instant) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), encode(e.getValue(), depth + 1));
            }
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof Collection<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(encode(item, depth + 1));
            }
            return out;
        }
        Class<?> raw = value.getClass();
        if (raw.isRecord()) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (RecordComponent component : raw.getRecordComponents()) {
                try {
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    sorted.put(component.getName(), encode(accessor.invoke(value), depth + 1));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new IllegalArgumentException("cannot read " + raw.getSimpleName() + "."
                            + component.getName() + ": " + e, e);
                }
            }
            return new LinkedHashMap<>(sorted);
        }
        throw new IllegalArgumentException("type " + raw.getName() + " is not supported by RecordCodec");
    }

    private static boolean isScalar(Class<?> raw) {
        return raw == String.class || raw == Boolean.class || raw == boolean.class || raw == Long.class
                || raw == long.class || raw == Integer.class || raw == int.class || raw == Double.class
                || raw == double.class || raw == Float.class || raw == float.class || raw == BigDecimal.class
                || raw.isEnum() || raw == LocalDate.class || raw == Instant.class;
    }

    private static Type argument(Type type, int index) {
        return type instanceof ParameterizedType p && p.getActualTypeArguments().length > index
                ? p.getActualTypeArguments()[index] : null;
    }

    private static Map<String, RecordComponent> components(Class<?> raw) {
        Map<String, RecordComponent> out = new LinkedHashMap<>();
        for (RecordComponent component : raw.getRecordComponents()) {
            out.put(component.getName(), component);
        }
        return out;
    }

    private static List<String> componentNames(Class<?> raw) {
        return new ArrayList<>(components(raw).keySet());
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String at(String path) {
        return path.isEmpty() ? "" : path + ": ";
    }

    /** The kind of a value for messages; never the value itself, which may be personal data. */
    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "text";
        }
        if (value instanceof Boolean) {
            return "a boolean";
        }
        if (value instanceof Number) {
            return "a number";
        }
        if (value instanceof Map) {
            return "a mapping";
        }
        if (value instanceof List) {
            return "a list";
        }
        return value.getClass().getSimpleName();
    }
}
