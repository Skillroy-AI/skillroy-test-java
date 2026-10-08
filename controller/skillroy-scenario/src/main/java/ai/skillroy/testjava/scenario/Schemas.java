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

package ai.skillroy.testjava.scenario;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * JSON Schemas of Java types, as JSON-like maps (phase5-contracts.md 3.3, 10.2), within the limits a model provider
 * sets on structured outputs and strict tools: no recursion, no numeric bounds, no string lengths, no {@code minItems}
 * other than 0 or 1 (none is written), no external references. Shared by the scenario schema
 * ({@link ScenarioSchema}) and the inference module, which builds structured outputs and tool inputs with
 * {@link #ofRecord}.
 *
 * <p>Types, as {@code RecordCodec} reads them: a record is an object keyed by component name; {@code String} a string;
 * {@code Boolean} a boolean; {@code Long}, {@code Integer}, {@code Short}, {@code Byte} and their primitives an
 * integer; {@code Double}, {@code Float} and their primitives a number; {@code BigDecimal} a string (a decimal written
 * as text) with a description; an enum a string with {@code enum}; {@code LocalDate} and {@code Instant} an ISO-8601
 * string with a description; {@code List<E>} an array of E; {@code Map<String, V>} an object whose values are V.
 * {@code Optional}, arrays, maps with other keys, a record that contains itself (directly or not) and every other class
 * are refused with an {@link IllegalArgumentException} naming the component. A component annotated {@link Doc} gets the
 * annotation's text as its {@code description}.
 *
 * <p>Stateless; safe from any thread. Every call returns new, mutable maps with a fixed key order.
 */
public final class Schemas {

    /** What a {@code $reference} looks like where the scenario schema accepts one: a string that starts with it. */
    public static final String REFERENCE_PATTERN = "^\\$[A-Za-z_]";

    private static final String DECIMAL = "a decimal number written as text, such as \"12.50\"";
    private static final String DATE = "an ISO-8601 date, such as \"2026-10-08\"";
    private static final String INSTANT = "an ISO-8601 instant, such as \"2026-10-08T10:00:00Z\"";

    private Schemas() {
    }

    /**
     * The description of a record component in a generated schema (phase5-contracts.md 3.3).
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    public @interface Doc {

        /** The description. */
        String value();
    }

    /**
     * The strict schema of a record (phase5-contracts.md 3.3): {@code {type: object, properties, required: every
     * component, additionalProperties: false}}, nested records likewise; a value must be given for every component.
     * What a structured output or a strict tool input is written against.
     *
     * @throws IllegalArgumentException if a component's type is not supported (see the class description)
     */
    public static Map<String, Object> ofRecord(Class<? extends Record> type) {
        Objects.requireNonNull(type, "type");
        return strict(type, new LinkedHashSet<>(), type.getSimpleName());
    }

    /**
     * The schema of a value of {@code type} as a scenario writes it (query arguments, an oracle's input): a record
     * requires only its primitive components (a missing one decodes as null) and has no other keys, and a
     * {@code $reference} (a string matching {@link #REFERENCE_PATTERN}) is accepted wherever a value of another type
     * than a string is (a reference stands for any value until the bind substitutes it).
     *
     * @throws IllegalArgumentException if the type is not supported (see the class description)
     */
    public static Map<String, Object> ofArguments(Type type) {
        Objects.requireNonNull(type, "type");
        return lenient(type, new LinkedHashSet<>(), name(type), true);
    }

    /**
     * {@link #ofArguments} without the reference alternative at the top: the schema of an object that must be written
     * out (a query slot's {@code args} is a mapping, never a reference).
     *
     * @throws IllegalArgumentException if the type is not supported
     */
    static Map<String, Object> ofArgumentObject(Type type) {
        Objects.requireNonNull(type, "type");
        return lenient(type, new LinkedHashSet<>(), name(type), false);
    }

    // ------------------------------------------------------------------------------------------------ strict

    private static Map<String, Object> strict(Type type, Set<Class<?>> path, String where) {
        Class<?> raw = raw(type, where);
        Map<String, Object> scalar = scalar(raw);
        if (scalar != null) {
            return scalar;
        }
        if (raw.isRecord()) {
            if (!path.add(raw)) {
                throw new IllegalArgumentException(where + ": record " + raw.getSimpleName() + " contains itself;"
                        + " a schema may not recurse");
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            List<Object> required = new ArrayList<>();
            for (RecordComponent component : raw.getRecordComponents()) {
                Map<String, Object> schema = strict(component.getGenericType(), path,
                        where + "." + component.getName());
                properties.put(component.getName(), described(schema, component));
                required.add(component.getName());
            }
            path.remove(raw);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", required);
            schema.put("additionalProperties", false);
            return schema;
        }
        if (raw == List.class) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "array");
            schema.put("items", strict(argument(type, 0, where + ": a List needs its element type"), path,
                    where + "[]"));
            return schema;
        }
        if (raw == Map.class) {
            mapKeys(type, where);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("additionalProperties", strict(argument(type, 1, where + ": a Map needs its value type"),
                    path, where + "{}"));
            return schema;
        }
        throw unsupported(raw, where);
    }

    // ------------------------------------------------------------------------------------------------ lenient

    private static Map<String, Object> lenient(Type type, Set<Class<?>> path, String where, boolean reference) {
        Class<?> raw = raw(type, where);
        Map<String, Object> scalar = scalar(raw);
        if (scalar != null) {
            if (raw == BigDecimal.class) {
                Map<String, Object> schema = anyOf(type("string"), type("number"));
                schema.put("description", DECIMAL + ", or a number, or a $reference");
                return schema;
            }
            boolean text = "string".equals(scalar.get("type")) && !scalar.containsKey("enum");
            return text || !reference ? scalar : anyOf(scalar, referenceSchema());
        }
        Map<String, Object> schema;
        if (raw.isRecord()) {
            if (!path.add(raw)) {
                throw new IllegalArgumentException(where + ": record " + raw.getSimpleName() + " contains itself;"
                        + " a schema may not recurse");
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            List<Object> required = new ArrayList<>();
            for (RecordComponent component : raw.getRecordComponents()) {
                Map<String, Object> value = lenient(component.getGenericType(), path,
                        where + "." + component.getName(), true);
                properties.put(component.getName(), described(value, component));
                if (component.getType().isPrimitive()) {
                    required.add(component.getName());
                }
            }
            path.remove(raw);
            schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            schema.put("additionalProperties", false);
        } else if (raw == List.class) {
            schema = new LinkedHashMap<>();
            schema.put("type", "array");
            schema.put("items", lenient(argument(type, 0, where + ": a List needs its element type"), path,
                    where + "[]", true));
        } else if (raw == Map.class) {
            mapKeys(type, where);
            schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("additionalProperties", lenient(argument(type, 1, where + ": a Map needs its value type"),
                    path, where + "{}", true));
        } else {
            throw unsupported(raw, where);
        }
        return reference ? anyOf(schema, referenceSchema()) : schema;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The schema of a scalar class, or null when the class is not a scalar. */
    private static Map<String, Object> scalar(Class<?> raw) {
        if (raw == String.class) {
            return type("string");
        }
        if (raw == Boolean.class || raw == boolean.class) {
            return type("boolean");
        }
        if (raw == Long.class || raw == long.class || raw == Integer.class || raw == int.class || raw == Short.class
                || raw == short.class || raw == Byte.class || raw == byte.class) {
            return type("integer");
        }
        if (raw == Double.class || raw == double.class || raw == Float.class || raw == float.class) {
            return type("number");
        }
        if (raw == BigDecimal.class) {
            Map<String, Object> schema = type("string");
            schema.put("description", DECIMAL);
            return schema;
        }
        if (raw == LocalDate.class) {
            Map<String, Object> schema = type("string");
            schema.put("description", DATE);
            return schema;
        }
        if (raw == Instant.class) {
            Map<String, Object> schema = type("string");
            schema.put("description", INSTANT);
            return schema;
        }
        if (raw.isEnum()) {
            Map<String, Object> schema = type("string");
            List<Object> names = new ArrayList<>();
            for (Object constant : raw.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            schema.put("enum", names);
            return schema;
        }
        return null;
    }

    /** The schema with the component's {@link Doc} as its description (before a type's own description). */
    private static Map<String, Object> described(Map<String, Object> schema, RecordComponent component) {
        Doc doc = component.getAnnotation(Doc.class);
        if (doc == null || doc.value().isBlank()) {
            return schema;
        }
        Object own = schema.get("description");
        Map<String, Object> out = new LinkedHashMap<>(schema);
        out.put("description", own == null ? doc.value() : doc.value() + " (" + own + ")");
        return out;
    }

    /** {@code {type: name}}. */
    static Map<String, Object> type(String name) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", name);
        return schema;
    }

    /** {@code {anyOf: [schemas…]}}. */
    @SafeVarargs
    static Map<String, Object> anyOf(Map<String, Object>... schemas) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("anyOf", new ArrayList<>(List.of(schemas)));
        return schema;
    }

    /** A {@code $reference}: a string that starts with one. */
    static Map<String, Object> referenceSchema() {
        Map<String, Object> schema = type("string");
        schema.put("pattern", REFERENCE_PATTERN);
        schema.put("description", "a $reference to a slot, an oracle or $env");
        return schema;
    }

    private static Class<?> raw(Type type, String where) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                throw new IllegalArgumentException(where + ": arrays are not supported; use a List");
            }
            if (c == Optional.class) {
                throw new IllegalArgumentException(where + ": Optional is not supported; use a nullable component");
            }
            return c;
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c) {
            if (c == Optional.class) {
                throw new IllegalArgumentException(where + ": Optional is not supported; use a nullable component");
            }
            return c;
        }
        if (type instanceof GenericArrayType) {
            throw new IllegalArgumentException(where + ": arrays are not supported; use a List");
        }
        throw new IllegalArgumentException(where + ": type " + type + " is not a concrete type");
    }

    private static Type argument(Type type, int index, String missing) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        throw new IllegalArgumentException(missing);
    }

    private static void mapKeys(Type type, String where) {
        if (argument(type, 0, where + ": a Map needs its key type") != String.class) {
            throw new IllegalArgumentException(where + ": a Map needs String keys");
        }
    }

    private static IllegalArgumentException unsupported(Class<?> raw, String where) {
        return new IllegalArgumentException(where + ": type " + raw.getName() + " is not supported (records, String,"
                + " Boolean, Long, Integer, Double, BigDecimal, enums, LocalDate, Instant, List and Map<String,V>)");
    }

    private static String name(Type type) {
        return type instanceof Class<?> c ? c.getSimpleName() : String.valueOf(type);
    }
}
