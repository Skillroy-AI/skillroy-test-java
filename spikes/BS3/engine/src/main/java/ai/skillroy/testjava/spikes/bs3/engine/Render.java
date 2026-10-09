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

package ai.skillroy.testjava.spikes.bs3.engine;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Renders values for events without calling host code: strings, boxed primitives and the JDK's immutable numbers as
 * themselves, arrays as type and length, anything else as its class name and identity hash. A handler that called
 * {@code toString()} on a host object could block or throw inside the host's own call.
 */
final class Render {

    private Render() {
    }

    static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            String s = (String) v;
            return s.length() > 80 ? '"' + s.substring(0, 80) + "...\"" : '"' + s + '"';
        }
        if (v instanceof Number && (v instanceof Integer || v instanceof Long || v instanceof Short
                || v instanceof Byte || v instanceof Double || v instanceof Float || v instanceof BigDecimal
                || v instanceof BigInteger)) {
            return v instanceof BigDecimal ? ((BigDecimal) v).toPlainString() : String.valueOf(v);
        }
        if (v instanceof Boolean || v instanceof Character) {
            return String.valueOf(v);
        }
        Class<?> c = v.getClass();
        if (c.isArray()) {
            return c.getComponentType().getSimpleName() + "[" + java.lang.reflect.Array.getLength(v) + "]";
        }
        return c.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(v));
    }

    static String args(Object[] args) {
        if (args == null) {
            return "()";
        }
        StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(value(args[i]));
        }
        return b.append(')').toString();
    }

    /** A copy that cannot change under the host: immutables as they are, arrays cloned, others kept by reference. */
    static Object copy(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean
                || v instanceof Character || v instanceof Enum) {
            return v;
        }
        if (v instanceof Object[]) {
            return ((Object[]) v).clone();
        }
        if (v instanceof long[]) {
            return ((long[]) v).clone();
        }
        if (v instanceof int[]) {
            return ((int[]) v).clone();
        }
        if (v instanceof byte[]) {
            return ((byte[]) v).clone();
        }
        return v;
    }
}
