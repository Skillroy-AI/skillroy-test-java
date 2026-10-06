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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveRegistry;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link PrimitiveRegistry}: a concurrent map from primitive name to primitive. The spec is read once at
 * registration, so a primitive cannot change its name afterwards.
 *
 * <p>Thread-safe.
 */
final class CorePrimitiveRegistry implements PrimitiveRegistry {

    private final ConcurrentHashMap<String, Registered> primitives = new ConcurrentHashMap<String, Registered>();

    @Override
    public void register(Primitive primitive) {
        if (primitive == null) {
            throw new IllegalArgumentException("primitive must not be null");
        }
        PrimitiveSpec spec = primitive.spec();
        if (spec == null) {
            throw new IllegalArgumentException("primitive " + primitive + " has no spec");
        }
        if (primitives.putIfAbsent(spec.name(), new Registered(primitive, spec)) != null) {
            throw new IllegalStateException("primitive already registered: " + spec.name());
        }
    }

    @Override
    public void unregister(String name) {
        if (name != null) {
            primitives.remove(name);
        }
    }

    @Override
    public Primitive find(String name) {
        Registered registered = name == null ? null : primitives.get(name);
        return registered == null ? null : registered.primitive;
    }

    /** The spec captured at registration, or null. */
    PrimitiveSpec specOf(String name) {
        Registered registered = name == null ? null : primitives.get(name);
        return registered == null ? null : registered.spec;
    }

    @Override
    public List<PrimitiveSpec> list() {
        Map<String, PrimitiveSpec> sorted = new TreeMap<String, PrimitiveSpec>();
        for (Registered registered : primitives.values()) {
            sorted.put(registered.spec.name(), registered.spec);
        }
        return new ArrayList<PrimitiveSpec>(sorted.values());
    }

    private static final class Registered {
        final Primitive primitive;
        final PrimitiveSpec spec;

        Registered(Primitive primitive, PrimitiveSpec spec) {
            this.primitive = primitive;
            this.spec = spec;
        }
    }
}
