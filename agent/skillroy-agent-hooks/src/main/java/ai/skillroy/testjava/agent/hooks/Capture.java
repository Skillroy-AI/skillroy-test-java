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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.MaskPolicy;
import ai.skillroy.testjava.protocol.SecretKeys;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The encoding of a captured value (phase6-contracts.md section 4.3, D125), run on the application thread that called
 * the hooked method: scalars as JSON, an enum by name, big numbers as plain text, dates and {@code java.time} values as
 * ISO-8601 text, a JDK array, collection or map as at most 100 elements one level deep, and everything else as a weak
 * handle {@code {"class", "ref"}}. <b>No {@code toString()}, {@code hashCode()} or {@code equals()} of an application
 * object is called</b>, and a collection or map is iterated only when its class is the JDK's ({@code java.*}): an
 * application's own collection class is a handle, and so are the JDK collections whose iteration takes a lock (the
 * synchronized wrappers, {@code Vector}, {@code Stack}, {@code Hashtable}, {@code Properties}, the blocking queues),
 * so the encoding touches no lock of the application. (A JDK wrapper such as an unmodifiable view is iterated; its
 * iterator is the wrapped collection's, which an application's own code would call the same way.) Every encoded
 * scalar passes every registered {@link MaskPolicy} as {@code mask("hook.<id>", column, value)}; a map entry whose key
 * the agent calls secret is {@code <redacted>}.
 *
 * <p>The budget is counted on the input (the code units and elements read), so a call never reads more than
 * {@code hooks.capture.maxBytes} of the application's values. One {@link Run} per event; not thread-safe, never shared.
 */
final class Capture {

    static final int STRING_CUT = 2000;
    static final int NESTED_STRING_CUT = 256;
    static final int MAX_ELEMENTS = 100;
    static final int MESSAGE_CUT = 500;
    static final String REDACTED = SecretKeys.REDACTED;

    private final AgentContext ctx;
    private final int maxBytes;

    Capture(AgentContext ctx, int maxBytes) {
        this.ctx = ctx;
        this.maxBytes = maxBytes;
    }

    /** The budget of one event's encoding, also the bound of its output (section 4.3). */
    int maxBytes() {
        return maxBytes;
    }

    Run run(String sourceId) {
        return new Run(sourceId, maxBytes, ctx.spi().all(MaskPolicy.class));
    }

    /** One event's encoding: its budget, whether anything was cut, and whether a mask policy threw. */
    final class Run {
        private final String sourceId;
        private final List<MaskPolicy> policies;
        private long budget;
        boolean truncated;
        boolean maskFailed;

        Run(String sourceId, long budget, List<MaskPolicy> policies) {
            this.sourceId = sourceId;
            this.budget = budget;
            this.policies = policies;
        }

        /** The encoded value of one argument, the receiver or the return value ({@code column}: arg0, self, return). */
        Object top(Object value, String column) {
            return encode(value, column, false);
        }

        private Object encode(Object value, String column, boolean nested) {
            if (value == null) {
                return null;
            }
            if (budget <= 0) {
                truncated = true;
                return null;
            }
            Object scalar = scalar(value, nested);
            if (scalar != NOT_SCALAR) {
                return mask(column, scalar);
            }
            if (nested) {
                budget--;
                return handle(value);
            }
            if (value.getClass().isArray()) {
                return array(value, column);
            }
            if (value instanceof Collection && iterable(value)) {
                return collection((Collection<?>) value, column);
            }
            if (value instanceof Map && iterable(value)) {
                return map((Map<?, ?>) value, column);
            }
            budget--;
            return handle(value);
        }

        /** The JSON scalar for a JDK scalar type, or {@link #NOT_SCALAR}. */
        private Object scalar(Object value, boolean nested) {
            Class<?> type = value.getClass();
            if (type == String.class) {
                String s = (String) value;
                int cut = nested ? NESTED_STRING_CUT : STRING_CUT;
                int take = (int) Math.min(Math.min(s.length(), cut), Math.max(0, budget));
                budget -= Math.max(1, take);
                if (take < s.length()) {
                    truncated = true;
                    return s.substring(0, take);
                }
                return s;
            }
            if (type == Integer.class || type == Long.class || type == Short.class || type == Byte.class
                    || type == Boolean.class) {
                budget--;
                return value;
            }
            if (type == Double.class || type == Float.class) {
                budget--;
                double d = ((Number) value).doubleValue();
                return Double.isNaN(d) || Double.isInfinite(d) ? String.valueOf(d) : value;
            }
            if (type == Character.class) {
                budget--;
                return String.valueOf(((Character) value).charValue());
            }
            if (value instanceof Enum) {
                budget--;
                return ((Enum<?>) value).name();
            }
            if (type == BigDecimal.class) {
                BigDecimal d = (BigDecimal) value;
                String text = d.scale() >= -64 && d.scale() <= STRING_CUT && d.precision() <= STRING_CUT
                        ? d.toPlainString() : d.toString();
                return cutText(text, nested);
            }
            if (type == BigInteger.class) {
                BigInteger i = (BigInteger) value;
                if (i.bitLength() > 6000) {
                    truncated = true;
                    budget--;
                    return null;
                }
                return cutText(i.toString(), nested);
            }
            if (type.getName().startsWith("java.") && value instanceof Date) {
                budget--;
                return java.time.Instant.ofEpochMilli(((Date) value).getTime()).toString();
            }
            if (type.getName().startsWith("java.time.")) {
                return cutText(value.toString(), nested);
            }
            return NOT_SCALAR;
        }

        private String cutText(String text, boolean nested) {
            int cut = nested ? NESTED_STRING_CUT : STRING_CUT;
            int take = (int) Math.min(Math.min(text.length(), cut), Math.max(0, budget));
            budget -= Math.max(1, take);
            if (take < text.length()) {
                truncated = true;
                return text.substring(0, take);
            }
            return text;
        }

        private List<Object> array(Object array, String column) {
            int length = Array.getLength(array);
            List<Object> out = new ArrayList<Object>(Math.min(length, MAX_ELEMENTS));
            for (int i = 0; i < length; i++) {
                if (i >= MAX_ELEMENTS || budget <= 0) {
                    truncated = true;
                    break;
                }
                budget--;
                out.add(encode(Array.get(array, i), column, true));
            }
            return out;
        }

        private List<Object> collection(Collection<?> collection, String column) {
            List<Object> out = new ArrayList<Object>();
            Iterator<?> it = collection.iterator();
            while (it.hasNext()) {
                if (out.size() >= MAX_ELEMENTS || budget <= 0) {
                    truncated = true;
                    break;
                }
                budget--;
                out.add(encode(it.next(), column, true));
            }
            return out;
        }

        private Map<String, Object> map(Map<?, ?> map, String column) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                if (out.size() >= MAX_ELEMENTS || budget <= 0) {
                    truncated = true;
                    break;
                }
                Map.Entry<?, ?> entry = it.next();
                budget--;
                String key = key(entry.getKey());
                if (ctx.isSecretKey(key)) {
                    out.put(key, REDACTED);
                } else {
                    out.put(key, encode(entry.getValue(), column, true));
                }
            }
            return out;
        }

        /** A map key as text: a JDK scalar's text, else the key object's handle. */
        private String key(Object key) {
            if (key == null) {
                return "null";
            }
            Object scalar = scalar(key, true);
            if (scalar != NOT_SCALAR) {
                return String.valueOf(scalar);
            }
            return ctx.handles().handleFor(key, "hook");
        }

        private Map<String, Object> handle(Object value) {
            Map<String, Object> ref = new LinkedHashMap<String, Object>();
            ref.put("class", value.getClass().getName());
            ref.put("ref", ctx.handles().handleFor(value, "hook"));
            return ref;
        }

        /** Every registered policy, in order; a policy that throws redacts the value and marks the run. */
        private Object mask(String column, Object value) {
            Object current = value;
            for (MaskPolicy policy : policies) {
                try {
                    current = policy.mask(sourceId, column, current);
                } catch (Throwable t) {
                    maskFailed = true;
                    return REDACTED;
                }
                if (!(current == null || current instanceof String || current instanceof Number
                        || current instanceof Boolean)) {
                    return REDACTED;
                }
            }
            return current;
        }
    }

    /** True for a collection or map of the JDK whose iteration takes no lock of the application. */
    static boolean iterable(Object value) {
        String name = value.getClass().getName();
        return name.startsWith("java.") && !name.startsWith("java.util.Collections$Synchronized")
                && !"java.util.Vector".equals(name) && !"java.util.Stack".equals(name)
                && !"java.util.Hashtable".equals(name) && !"java.util.Properties".equals(name)
                && !name.contains("Blocking");
    }

    /** The redacted text of a throwable's message, cut at {@value #MESSAGE_CUT} characters. */
    String message(Throwable t) {
        String message;
        try {
            message = t.getMessage();
        } catch (Throwable ignored) {
            return null;
        }
        if (message == null) {
            return null;
        }
        if (message.length() > MESSAGE_CUT * 4) {
            message = message.substring(0, MESSAGE_CUT * 4);
        }
        String redacted = ctx.redactText(message);
        return redacted != null && redacted.length() > MESSAGE_CUT ? redacted.substring(0, MESSAGE_CUT) : redacted;
    }

    private static final Object NOT_SCALAR = new Object();
}
