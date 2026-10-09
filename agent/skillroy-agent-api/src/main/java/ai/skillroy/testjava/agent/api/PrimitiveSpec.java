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

package ai.skillroy.testjava.agent.api;

import ai.skillroy.testjava.protocol.PrimitiveDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The declared contract of a {@link Primitive}: name, parameter and result schemas, thread policy, timeout, the
 * capability it depends on, and whether it changes state. The dispatcher enforces it and {@code GET /schema}
 * publishes it, so behaviour and documentation cannot drift apart.
 *
 * <p>Immutable (schemas are deep-copied and unmodifiable); safe to share between threads.
 */
public final class PrimitiveSpec {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+$");
    private static final Set<String> JSON_TYPES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "string", "number", "integer", "boolean", "object", "array", "null")));

    private final ThreadPolicy thread;
    private final PrimitiveDescriptor descriptor;

    private PrimitiveSpec(ThreadPolicy thread, PrimitiveDescriptor descriptor) {
        this.thread = thread;
        this.descriptor = descriptor;
    }

    /**
     * Starts a spec. {@code name} is {@code "<namespace>.<verb>[.<sub>]"}: two or more dot-separated segments, each a
     * lower-case letter followed by letters and digits ({@code ^[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*)+$}).
     *
     * @throws IllegalArgumentException if the name does not match
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return descriptor.name();
    }

    /** Never null; empty if none was given. */
    public String description() {
        return descriptor.description();
    }

    /** JSON Schema of {@code params} (without {@code _meta}); unmodifiable, never null. */
    public Map<String, Object> paramsSchema() {
        return descriptor.paramsSchema();
    }

    /** JSON Schema of the result value; unmodifiable, or null if none was given. */
    public Map<String, Object> resultSchema() {
        return descriptor.resultSchema();
    }

    public ThreadPolicy thread() {
        return thread;
    }

    /** Default timeout of a call; a caller may override it per call through {@code _meta.timeoutMs}. */
    public long timeoutMs() {
        return descriptor.timeoutMs();
    }

    /** Capability the call depends on (it fails fast while that capability is UNAVAILABLE), or null. */
    public String capabilityId() {
        return descriptor.capabilityId();
    }

    /** True if the call changes target state; rejected with READ_ONLY in read-only mode. */
    public boolean mutating() {
        return descriptor.mutating();
    }

    /** True if the call needs {@code _meta.confirm=true}; implies {@link #mutating()}. */
    public boolean destructive() {
        return descriptor.destructive();
    }

    /**
     * True if the call evaluates a caller-supplied expression or script in the target; rejected with READ_ONLY in
     * read-only mode, as a mutating call is (phase6-contracts.md 12.9, D156). Implies nothing else: a script primitive
     * that is not mutating stays a read for the step protocol.
     */
    public boolean script() {
        return descriptor.script();
    }

    /** The {@code GET /schema} entry for this spec ({@code thread} as the policy's name). */
    public PrimitiveDescriptor describe() {
        return descriptor;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PrimitiveSpec && descriptor.equals(((PrimitiveSpec) o).descriptor);
    }

    @Override
    public int hashCode() {
        return descriptor.hashCode();
    }

    @Override
    public String toString() {
        return "PrimitiveSpec" + descriptor.toJson();
    }

    /**
     * Collects the parts of a spec. Parameters are given either one by one with {@link #param} or as a whole schema
     * with {@link #paramsSchema}, not both. Not thread-safe; {@link #build()} may be called more than once.
     */
    public static final class Builder {

        private final String name;
        private String description = "";
        private final Map<String, Object> properties = new LinkedHashMap<String, Object>();
        private final List<String> required = new ArrayList<String>();
        private Map<String, Object> paramsSchema;
        private Map<String, Object> resultSchema;
        private ThreadPolicy thread = ThreadPolicy.CALLER;
        private long timeoutMs = 10000L;
        private String capabilityId;
        private boolean mutating;
        private boolean destructive;
        private boolean script;

        private Builder(String name) {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid primitive name '" + name
                        + "': expected <namespace>.<verb>[.<sub>] with lower-camel segments, e.g. swing.click");
            }
            this.name = name;
        }

        /** What the primitive does, for the schema catalogue; null means empty. */
        public Builder description(String text) {
            this.description = text == null ? "" : text;
            return this;
        }

        /**
         * Declares one parameter of the generated object schema.
         *
         * @param jsonType a JSON Schema type ({@code string, number, integer, boolean, object, array, null}), or
         *     several joined with '|' ({@code "string|array"}) for a value that may take either form
         * @param description may be null
         * @throws IllegalArgumentException on a blank or duplicate name, the reserved name {@code _meta}, or an
         *     unknown type
         * @throws IllegalStateException if {@link #paramsSchema} was used
         */
        public Builder param(String name, String jsonType, boolean required, String description) {
            if (paramsSchema != null) {
                throw new IllegalStateException("use either param(...) or paramsSchema(...), not both");
            }
            if (name == null || name.trim().isEmpty() || "_meta".equals(name)) {
                throw new IllegalArgumentException("invalid parameter name '" + name + "'");
            }
            if (properties.containsKey(name)) {
                throw new IllegalArgumentException("duplicate parameter '" + name + "'");
            }
            Map<String, Object> property = new LinkedHashMap<String, Object>();
            property.put("type", jsonType(name, jsonType));
            if (description != null) {
                property.put("description", description);
            }
            properties.put(name, property);
            if (required) {
                this.required.add(name);
            }
            return this;
        }

        /**
         * Alternative to {@link #param}: the complete JSON Schema of {@code params}, copied as given.
         *
         * @throws IllegalStateException if {@link #param} was used
         */
        public Builder paramsSchema(Map<String, Object> jsonSchema) {
            if (!properties.isEmpty()) {
                throw new IllegalStateException("use either param(...) or paramsSchema(...), not both");
            }
            this.paramsSchema = Objects.requireNonNull(jsonSchema, "jsonSchema");
            return this;
        }

        /** JSON Schema of the result value; null means none. */
        public Builder resultSchema(Map<String, Object> jsonSchema) {
            this.resultSchema = jsonSchema;
            return this;
        }

        /** Default CALLER. */
        public Builder thread(ThreadPolicy policy) {
            this.thread = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Default 10000.
         *
         * @throws IllegalArgumentException if not positive
         */
        public Builder timeoutMs(long ms) {
            if (ms <= 0) {
                throw new IllegalArgumentException("timeoutMs must be positive: " + ms);
            }
            this.timeoutMs = ms;
            return this;
        }

        /** Call fails fast when UNAVAILABLE; null means no capability. */
        public Builder capability(String capabilityId) {
            this.capabilityId = capabilityId;
            return this;
        }

        /** The call changes target state. */
        public Builder mutating() {
            this.mutating = true;
            return this;
        }

        /** Destructive implies mutating, needs confirm. */
        public Builder destructive() {
            this.destructive = true;
            this.mutating = true;
            return this;
        }

        /**
         * The call evaluates a caller-supplied expression or script in the target ({@code web.evaluate},
         * {@code web.waitFlag}): refused in read-only mode, as a mutating call is (D156). Implies nothing else.
         */
        public Builder script() {
            this.script = true;
            return this;
        }

        /** An immutable spec; later builder changes do not affect it. */
        public PrimitiveSpec build() {
            Map<String, Object> params;
            if (paramsSchema != null) {
                params = paramsSchema;
            } else {
                params = new LinkedHashMap<String, Object>();
                params.put("type", "object");
                params.put("properties", properties);
                params.put("required", required);
            }
            PrimitiveDescriptor descriptor = new PrimitiveDescriptor(name, description, params, resultSchema,
                    thread.name(), timeoutMs, capabilityId, mutating, destructive, script);
            return new PrimitiveSpec(thread, descriptor);
        }

        private static Object jsonType(String param, String jsonType) {
            if (jsonType == null) {
                throw new IllegalArgumentException("parameter '" + param + "' needs a JSON type");
            }
            List<String> types = new ArrayList<String>();
            for (String part : jsonType.split("\\|", -1)) {
                String type = part.trim();
                if (!JSON_TYPES.contains(type)) {
                    throw new IllegalArgumentException("parameter '" + param + "': unknown JSON type '" + part
                            + "', expected one of " + new TreeSet<String>(JSON_TYPES));
                }
                if (!types.contains(type)) {
                    types.add(type);
                }
            }
            return types.size() == 1 ? types.get(0) : types;
        }
    }
}
