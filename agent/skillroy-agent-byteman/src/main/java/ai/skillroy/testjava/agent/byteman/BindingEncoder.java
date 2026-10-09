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
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The encoding of a rule's bindings for {@code rule.fired} (phase6-contracts.md 7.3, the rule of 4.3, D125), applied on
 * the application's thread that triggered the rule. Scalars as JSON (a string cut at 2000 UTF-16 code units, 256 inside
 * a collection); an enum by {@code name()}; {@code BigDecimal} and {@code BigInteger} as their plain string; a
 * {@code java.util.Date} and a {@code java.time} value as ISO-8601 text; an array, collection or map of the JDK as a
 * list or object of at most 100 elements, each encoded one level deep (a nested container becomes a handle); anything
 * else as {@code {"class", "ref"}} with a weak handle, so that nothing of an application object's state leaves the JVM
 * and <b>no {@code toString()} of an application object is ever called</b> (as built: a container is read only when
 * its class is the JDK's, since iterating an application's own collection would run its code).
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
    static final String REDACTED = "<redacted>";

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
        boolean jdk = v.getClass().getClassLoader() == null;
        if (v instanceof String) {
            String s = (String) v;
            int max = depth == 0 ? MAX_STRING : MAX_STRING_IN_COLLECTION;
            int take = (int) Math.max(0, Math.min(Math.min(s.length(), max), pass.left));
            pass.left -= take;
            if (take < s.length()) {
                pass.truncated = true;
                s = s.substring(0, take);
            }
            return mask(pass, column, s);
        }
        if (v instanceof Boolean) {
            return mask(pass, column, v);
        }
        if (v instanceof Character) {
            return mask(pass, column, String.valueOf(((Character) v).charValue()));
        }
        if (v instanceof Enum) {
            return mask(pass, column, ((Enum<?>) v).name());
        }
        if (jdk && (v instanceof BigDecimal)) {
            return mask(pass, column, ((BigDecimal) v).toPlainString());
        }
        if (jdk && (v instanceof BigInteger)) {
            return mask(pass, column, v.toString());
        }
        if (jdk && v instanceof Number) {
            Number n = (Number) v;
            if (v instanceof Double || v instanceof Float) {
                double d = n.doubleValue();
                return mask(pass, column, Double.isNaN(d) || Double.isInfinite(d) ? String.valueOf(d) : (Object) d);
            }
            return mask(pass, column, n.longValue());
        }
        if (jdk && v instanceof Date) {
            return mask(pass, column, new Date(((Date) v).getTime()).toInstant().toString());
        }
        if (jdk && v.getClass().getName().startsWith("java.time.")) {
            return mask(pass, column, v.toString());
        }
        if (depth == 0 && jdk) {
            if (v.getClass().isArray()) {
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
            if (v instanceof Collection) {
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
            if (v instanceof Map) {
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

    private String key(Pass pass, Object k) {
        if (k == null) {
            return "null";
        }
        if (k instanceof String) {
            String s = (String) k;
            pass.left -= Math.min(s.length(), MAX_STRING_IN_COLLECTION);
            return s.length() > MAX_STRING_IN_COLLECTION ? s.substring(0, MAX_STRING_IN_COLLECTION) : s;
        }
        if (k instanceof Enum) {
            return ((Enum<?>) k).name();
        }
        if (k.getClass().getClassLoader() == null && (k instanceof Number || k instanceof Boolean
                || k instanceof Character)) {
            return String.valueOf(k);
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
