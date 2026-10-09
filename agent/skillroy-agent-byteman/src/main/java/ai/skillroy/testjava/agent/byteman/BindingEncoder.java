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

package ai.skillroy.testjava.agent.byteman;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.protocol.Json;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The encoding of a rule's bindings for {@code rule.fired} (phase6-contracts.md 7.3, the rule of 4.3, D125, D182),
 * applied on the application's thread that triggered the rule. Scalars as JSON, for the JDK's exact types only (a
 * string cut at 2000 UTF-16 code units, 256 inside a collection; the boxes {@code Integer}, {@code Long},
 * {@code Short}, {@code Byte}, {@code Float}, {@code Double}, {@code Boolean}, {@code Character}); an enum by
 * {@code name()}; {@code BigDecimal} as its plain string unless its scale is extreme (then its scientific text) and
 * {@code BigInteger} as its string, either cut ({@code null}, {@code truncated}) when its (unscaled) value is above
 * 6,000 bits, before any text is made; a {@code java.util.Date} and a {@code java.time} value as ISO-8601 text; an array
 * as a list, and a collection or map of the allow-list {@link #ITERABLE} as a list or object, of at most 100 elements
 * each encoded one level deep (a nested container becomes a handle); anything else as {@code {"class", "ref"}} with a
 * weak handle, so that nothing of an application object's state leaves the JVM and <b>no method of an application
 * object is ever called</b> (no {@code toString()}, no {@code longValue()} of an accumulator, no iteration of an
 * application's collection) <b>and no lock of the application is taken</b> (no iteration of a synchronized wrapper, a
 * {@code Vector}, a {@code Hashtable}, a blocking queue, or of any view, whose iterator is the wrapped collection's).
 * Fix wave (L2-F1, L2-S1, L2-F3): the allow-list and the exact types replace "a container or number whose class is the
 * JDK's".
 *
 * <p>Masking: every encoded scalar, inside containers too, passes every registered {@link MaskPolicy} as
 * {@code mask("rule.<name>", "binding<i>", value)} (P1 section 4's thread rule as amended: on the application thread);
 * a policy that throws is counted and the value becomes {@code <redacted>}; a map entry whose key the agent calls
 * secret ({@link AgentContext#isSecretKey}) is {@code <redacted>}. Bounds: the encoder counts the code units and
 * elements it reads and stops at {@code hooks.capture.maxBytes} (the budget is on the input), marking the payload
 * {@code truncated}; a payload still above the limit drops its bindings. It never throws and takes no lock of the
 * application.
 */
final class BindingEncoder {

    static final int MAX_STRING = 2000;
    static final int MAX_STRING_IN_COLLECTION = 256;
    static final int MAX_ELEMENTS = 100;
    /** A {@code BigInteger}, or a {@code BigDecimal}'s unscaled value, above this many bits is cut (D182). */
    static final int MAX_BITS = 6000;
    static final String REDACTED = "<redacted>";

    /**
     * The JDK collection and map classes the encoder iterates, by exact class: their iteration takes no lock and calls
     * no method of an element (no {@code compareTo}, {@code hashCode} or {@code equals}). Everything else, a view of
     * one of these included, is a handle. {@code TreeSet} and {@code ConcurrentSkipListSet} are not on the list: their
     * {@code subSet}, {@code headSet}, {@code tailSet} and {@code descendingSet} answer an instance of the same class
     * over a bounded sub-map, whose iteration compares the keys (the application's {@code compareTo}). The list is the
     * hooks module's ({@code Capture}).
     */
    static final Set<String> ITERABLE = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "java.util.ArrayList", "java.util.LinkedList", "java.util.ArrayDeque", "java.util.PriorityQueue",
            "java.util.HashSet", "java.util.LinkedHashSet", "java.util.RegularEnumSet",
            "java.util.JumboEnumSet", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.EnumMap", "java.util.IdentityHashMap", "java.util.Arrays$ArrayList",
            "java.util.Collections$EmptyList", "java.util.Collections$EmptySet", "java.util.Collections$EmptyMap",
            "java.util.Collections$SingletonList", "java.util.Collections$SingletonSet",
            "java.util.Collections$SingletonMap", "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ConcurrentSkipListMap", "java.util.concurrent.ConcurrentLinkedQueue", "java.util.concurrent.ConcurrentLinkedDeque",
            "java.util.concurrent.CopyOnWriteArrayList", "java.util.concurrent.CopyOnWriteArraySet")));
    /** The immutable collections of Java 9 and later ({@code List.of} and the like), by class-name prefix. */
    static final String IMMUTABLE_PREFIX = "java.util.ImmutableCollections$";

    private static final Object NOT_SCALAR = new Object();

    private final AgentContext ctx;
    private final int budget;
    private final AtomicLong maskFailures = new AtomicLong();

    BindingEncoder(AgentContext ctx, int budget) {
        this.ctx = ctx;
        this.budget = budget;
    }

    /** Mask policies that threw since the module installed. */
    long maskFailures() {
        return maskFailures.get();
    }

    /** One encoding's state: what is left of the budget, whether anything was cut. */
    private final class Pass {
        final String sourceId;
        final List<MaskPolicy> masks;
        long left = budget;
        boolean truncated;

        Pass(String sourceId, List<MaskPolicy> masks) {
            this.sourceId = sourceId;
            this.masks = masks;
        }
    }

    /**
     * {@code {bindings: {"0": v0, …}, truncated?: true}} for {@code rule.fired}; the rule name is the user's (without
     * the {@code skillroy:} prefix).
     */
    Map<String, Object> encode(String ruleName, Object[] bindings) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<MaskPolicy> masks;
        try {
            masks = ctx.spi() == null ? Collections.<MaskPolicy>emptyList() : ctx.spi().all(MaskPolicy.class);
        } catch (RuntimeException e) {
            masks = Collections.emptyList();
        }
        Pass pass = new Pass("rule." + ruleName, masks);
        Map<String, Object> encoded = new LinkedHashMap<String, Object>();
        for (int i = 0; bindings != null && i < bindings.length; i++) {
            String column = "binding" + i;
            if (pass.left <= 0) {
                pass.truncated = true;
                break;
            }
            encoded.put(Integer.toString(i), value(pass, column, bindings[i], 0));
        }
        out.put("bindings", encoded);
        if (pass.truncated) {
            out.put("truncated", Boolean.TRUE);
        }
        try {
            if (Json.write(out).length() > budget) {
                out.put("bindings", new LinkedHashMap<String, Object>());
                out.put("truncated", Boolean.TRUE);
            }
        } catch (RuntimeException e) {
            out.put("bindings", new LinkedHashMap<String, Object>());
            out.put("truncated", Boolean.TRUE);
        }
        return out;
    }

    private Object value(Pass pass, String column, Object v, int depth) {
        pass.left--;
        if (v == null) {
            return null;
        }
        Object scalar = scalar(pass, v, depth);
        if (scalar != NOT_SCALAR) {
            return scalar == null ? null : mask(pass, column, scalar);
        }
        Class<?> type = v.getClass();
        if (depth == 0) {
            if (type.isArray()) {
                List<Object> list = new ArrayList<Object>();
                int length = Array.getLength(v);
                for (int i = 0; i < length; i++) {
                    if (i >= MAX_ELEMENTS || pass.left <= 0) {
                        pass.truncated = true;
                        break;
                    }
                    list.add(value(pass, column, Array.get(v, i), 1));
                }
                return list;
            }
            if (v instanceof Collection && iterable(type)) {
                List<Object> list = new ArrayList<Object>();
                Iterator<?> it = ((Collection<?>) v).iterator();
                for (int i = 0; it.hasNext(); i++) {
                    if (i >= MAX_ELEMENTS || pass.left <= 0) {
                        pass.truncated = true;
                        break;
                    }
                    list.add(value(pass, column, it.next(), 1));
                }
                return list;
            }
            if (v instanceof Map && iterable(type)) {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                int i = 0;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    if (i++ >= MAX_ELEMENTS || pass.left <= 0) {
                        pass.truncated = true;
                        break;
                    }
                    String key = key(pass, e.getKey());
                    map.put(key, secret(key) ? REDACTED : value(pass, column, e.getValue(), 1));
                }
                return map;
            }
        }
        return handle(v);
    }

    /**
     * The JSON value of a JDK scalar of an exact type (unmasked; {@code null} for a big number that is cut), or
     * {@link #NOT_SCALAR}. Runs no method of an application class: every type tested is final or the JDK's own.
     */
    private Object scalar(Pass pass, Object v, int depth) {
        Class<?> type = v.getClass();
        int max = depth == 0 ? MAX_STRING : MAX_STRING_IN_COLLECTION;
        if (type == String.class) {
            return cut(pass, (String) v, max);
        }
        if (type == Boolean.class) {
            return v;
        }
        if (type == Character.class) {
            return String.valueOf(((Character) v).charValue());
        }
        if (v instanceof Enum) {
            return ((Enum<?>) v).name();
        }
        if (type == Integer.class || type == Long.class || type == Short.class || type == Byte.class) {
            return ((Number) v).longValue();
        }
        if (type == Double.class || type == Float.class) {
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? String.valueOf(d) : (Object) d;
        }
        if (type == BigDecimal.class) {
            BigDecimal d = (BigDecimal) v;
            if (d.unscaledValue().bitLength() > MAX_BITS) {
                pass.truncated = true;
                return null;
            }
            return cut(pass, d.scale() >= -64 && d.scale() <= MAX_STRING ? d.toPlainString() : d.toString(), max);
        }
        if (type == BigInteger.class) {
            BigInteger i = (BigInteger) v;
            if (i.bitLength() > MAX_BITS) {
                pass.truncated = true;
                return null;
            }
            return cut(pass, i.toString(), max);
        }
        String name = type.getName();
        if (name.startsWith("java.") && v instanceof Date) {
            return new Date(((Date) v).getTime()).toInstant().toString();
        }
        if (name.startsWith("java.time.")) {
            return cut(pass, v.toString(), max);
        }
        return NOT_SCALAR;
    }

    private static String cut(Pass pass, String s, int max) {
        int take = (int) Math.max(0, Math.min(Math.min(s.length(), max), pass.left));
        pass.left -= take;
        if (take < s.length()) {
            pass.truncated = true;
            return s.substring(0, take);
        }
        return s;
    }

    /** True for a collection or map class of {@link #ITERABLE} or of the JDK's immutable collections. */
    static boolean iterable(Class<?> type) {
        String name = type.getName();
        return ITERABLE.contains(name) || name.startsWith(IMMUTABLE_PREFIX);
    }

    private String key(Pass pass, Object k) {
        if (k == null) {
            return "null";
        }
        Object scalar = scalar(pass, k, 1);
        if (scalar != NOT_SCALAR) {
            return String.valueOf(scalar);
        }
        Object h = handle(k);
        return h instanceof Map ? String.valueOf(((Map<?, ?>) h).get("ref")) : String.valueOf(h);
    }

    private boolean secret(String key) {
        try {
            return ctx.isSecretKey(key);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private Object handle(Object v) {
        Map<String, Object> h = new LinkedHashMap<String, Object>();
        h.put("class", v.getClass().getName());
        try {
            h.put("ref", ctx.handles().handleFor(v, "rule.binding"));
        } catch (RuntimeException e) {
            h.put("ref", null);
        }
        return h;
    }

    private Object mask(Pass pass, String column, Object scalar) {
        Object value = scalar;
        for (MaskPolicy policy : pass.masks) {
            try {
                value = policy.mask(pass.sourceId, column, value);
            } catch (Throwable t) {
                maskFailures.incrementAndGet();
                return REDACTED;
            }
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Long
                || value instanceof Double) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float) {
            return ((Float) value).doubleValue();
        }
        return REDACTED;
    }
}
