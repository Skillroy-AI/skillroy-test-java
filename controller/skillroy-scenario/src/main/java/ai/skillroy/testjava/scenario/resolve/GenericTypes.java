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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the actual type arguments a class gives a generic interface ({@code implements Query<BigDecimal>},
 * {@code implements Oracle<Input, Outcome>}), following superclasses and super-interfaces and substituting type
 * variables on the way. Stateless; safe from any thread.
 */
final class GenericTypes {

    private GenericTypes() {
    }

    /**
     * The type argument {@code index} that {@code cls} gives {@code target}.
     *
     * @throws IllegalArgumentException if {@code cls} does not implement {@code target} with a concrete argument
     */
    static Type argument(Class<?> cls, Class<?> target, int index) {
        Type found = search(cls, target, index, new HashMap<>(), 0);
        if (found == null || found instanceof TypeVariable<?>) {
            throw new IllegalArgumentException(cls.getName() + " must implement " + target.getSimpleName()
                    + " with a concrete type argument " + (index + 1) + " (as in 'implements "
                    + target.getSimpleName() + "<…>')");
        }
        return found;
    }

    private static Type search(Type type, Class<?> target, int index, Map<TypeVariable<?>, Type> bound, int depth) {
        if (type == null || depth > 20) {
            return null;
        }
        Class<?> raw;
        Map<TypeVariable<?>, Type> here = new HashMap<>(bound);
        if (type instanceof ParameterizedType p) {
            raw = (Class<?>) p.getRawType();
            TypeVariable<?>[] vars = raw.getTypeParameters();
            Type[] args = p.getActualTypeArguments();
            for (int i = 0; i < vars.length && i < args.length; i++) {
                here.put(vars[i], substitute(args[i], bound));
            }
            if (raw == target) {
                return substitute(args[index], bound);
            }
        } else if (type instanceof Class<?> c) {
            raw = c;
            if (raw == target) {
                return null;
            }
        } else {
            return null;
        }
        for (Type parent : raw.getGenericInterfaces()) {
            Type found = search(parent, target, index, here, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return search(raw.getGenericSuperclass(), target, index, here, depth + 1);
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> bound) {
        if (type instanceof TypeVariable<?> v && bound.containsKey(v)) {
            return bound.get(v);
        }
        return type;
    }

    /** The class of a type ({@code List<String>} gives {@code List}); null for a type variable or wildcard. */
    static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        return null;
    }
}
