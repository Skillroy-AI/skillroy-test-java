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

package ai.skillroy.testjava.inference.spi;

import ai.skillroy.testjava.scenario.resolve.CodecException;
import ai.skillroy.testjava.scenario.resolve.RecordCodec;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decodes a structured answer through {@link RecordCodec#DEFAULT} (phase5-contracts.md 3.3), so that a model's answer
 * and a bindings file obey one set of rules: records by component name (an unknown key is an error, a missing one is
 * null), strings, booleans, whole numbers, decimals, enums by name, ISO dates, lists and maps with string keys.
 *
 * <p>One addition the codec lacks: a {@code double} or {@code float} component (boxed or not), which {@code Schemas}
 * writes as {@code number} and a structured answer such as {@code SqlProposal.confidence} uses, is read from any JSON
 * number. Every other type is handed to the codec, nested records included.
 *
 * <p>Stateless; safe from any thread.
 */
public final class Structured {

    private static final int MAX_DEPTH = 100;

    private Structured() {
    }

    /**
     * The typed value of a structured answer.
     *
     * @throws CodecException if the answer does not fit the type; the message names the field, never the value
     */
    public static <T> T decode(Class<T> type, Object jsonLike) throws CodecException {
        @SuppressWarnings("unchecked")
        T value = (T) decode((Type) type, jsonLike);
        return value;
    }

    /**
     * The typed value of a structured answer.
     *
     * @throws CodecException if the answer does not fit the type
     */
    public static Object decode(Type type, Object jsonLike) throws CodecException {
        return decode(type, jsonLike, "", 0);
    }

    /**
     * The JSON-like form of a typed value: the codec's, with {@code double} and {@code float} components as numbers.
     * What a scripted answer is built from.
     *
     * @throws IllegalArgumentException for a type neither the codec nor this class can encode
     */
    public static Object encode(Object typed) {
        return encode(typed, 0);
    }

    private static Object decode(Type type, Object value, String path, int depth) throws CodecException {
        if (depth > MAX_DEPTH) {
            throw new CodecException(at(path) + "nesting too deep");
        }
        Class<?> raw = raw(type);
        if (raw != null && isFloating(raw)) {
            if (value == null) {
                if (raw.isPrimitive()) {
                    throw new CodecException(at(path) + "a number is required");
                }
                return null;
            }
            double d = number(value, path);
            return raw == float.class || raw == Float.class ? (Object) (float) d : (Object) d;
        }
        if (raw == null || RecordCodec.DEFAULT.unsupported(type) == null) {
            try {
                return RecordCodec.DEFAULT.decode(type, value);
            } catch (CodecException e) {
                throw path.isEmpty() ? e : new CodecException(at(path) + e.getMessage());
            }
        }
        if (value == null) {
            if (raw.isPrimitive()) {
                throw new CodecException(at(path) + "a value is required");
            }
            return null;
        }
        if (raw.isRecord()) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new CodecException(at(path) + "expected a mapping");
            }
            RecordComponent[] components = raw.getRecordComponents();
            List<String> names = new ArrayList<>();
            for (RecordComponent c : components) {
                names.add(c.getName());
            }
            for (Object key : map.keySet()) {
                if (!names.contains(String.valueOf(key))) {
                    throw new CodecException(at(path) + "unknown key '" + key + "'; allowed: " + names);
                }
            }
            Object[] args = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                args[i] = decode(components[i].getGenericType(), map.get(components[i].getName()),
                        join(path, components[i].getName()), depth + 1);
            }
            try {
                Constructor<?> constructor = raw.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw new CodecException(at(path) + raw.getSimpleName() + " refused the value: "
                        + (cause == null || cause.getMessage() == null ? String.valueOf(cause) : cause.getMessage()));
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new CodecException(at(path) + "cannot construct " + raw.getSimpleName());
            }
        }
        if (raw == List.class) {
            if (!(value instanceof List<?> list)) {
                throw new CodecException(at(path) + "expected a list");
            }
            Type element = argument(type, 0);
            List<Object> out = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(decode(element, list.get(i), path + "[" + i + "]", depth + 1));
            }
            return Collections.unmodifiableList(out);
        }
        if (raw == Map.class && argument(type, 0) == String.class) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new CodecException(at(path) + "expected a mapping");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, decode(argument(type, 1), e.getValue(), join(path, key), depth + 1));
            }
            return Collections.unmodifiableMap(out);
        }
        return RecordCodec.DEFAULT.decode(type, value);
    }

    private static Object encode(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nesting too deep to encode");
        }
        if (value instanceof Double || value instanceof Float) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Record record && !encodable(record.getClass())) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    var accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    sorted.put(component.getName(), encode(accessor.invoke(record), depth + 1));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalArgumentException("cannot read " + component.getName(), e);
                }
            }
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof Collection<?> list && !list.isEmpty()) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(encode(item, depth + 1));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), encode(e.getValue(), depth + 1));
            }
            return new LinkedHashMap<>(sorted);
        }
        return RecordCodec.DEFAULT.encode(value);
    }

    /** True when the codec can encode the record class itself (no floating component anywhere inside). */
    private static boolean encodable(Class<?> raw) {
        return RecordCodec.DEFAULT.unsupported(raw) == null;
    }

    private static double number(Object value, String path) throws CodecException {
        if (value instanceof Long || value instanceof Double || value instanceof Integer || value instanceof BigDecimal
                || value instanceof BigInteger || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isFinite(d)) {
                return d;
            }
        }
        throw new CodecException(at(path) + "expected a number");
    }

    private static boolean isFloating(Class<?> raw) {
        return raw == double.class || raw == Double.class || raw == float.class || raw == Float.class;
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    private static Type argument(Type type, int index) {
        return type instanceof ParameterizedType p && p.getActualTypeArguments().length > index
                ? p.getActualTypeArguments()[index] : null;
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String at(String path) {
        return path.isEmpty() ? "" : path + ": ";
    }
}
