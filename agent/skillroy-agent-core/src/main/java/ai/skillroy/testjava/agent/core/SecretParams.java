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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.SecretKeys;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The secret parameters of one call (contract amendment A34). A controller names, in
 * {@code params._meta.secretParams}, the top-level parameters that carry a secret under a neutral key (a sign-on
 * password sent as an input's {@code value}); the key rule of the "Secret redaction" section cannot recognise them.
 * The primitive receives its arguments unchanged; this object removes the values from everything the core writes or
 * reports about the call:
 * <ul>
 *   <li>the error the call answers with (message and every string or number in {@code data}, however deep);</li>
 *   <li>the agent log lines written on a thread that runs the call (the dispatcher's own and a module's), the
 *       thread broker's line about a task of the call that failed after it was posted (an EDT_ASYNC call, a posted
 *       task), and the dispatcher's line about a call that timed out and still runs.</li>
 * </ul>
 * What the call does is not changed: the primitive's result value, and the payloads of the events published while
 * it runs (they reach the event ring, its subscribers and the result's {@code events} as published).
 *
 * <p>Which values: of each named parameter that is present, its text if it is a string, its decimal text if it is a
 * number, and every such leaf below it if it is an object or array; booleans, nulls and empty strings are not secrets.
 * How a value is hidden, in any text: a string or number that equals a secret becomes {@link SecretKeys#REDACTED};
 * inside a longer text, a secret of {@link #MIN_SUBSTRING} or more characters is replaced wherever it occurs, and a
 * shorter one wherever it stands as a token of its own (not preceded or followed by a letter or digit), so that a
 * four-digit code inside {@code "rejected 1111"} is hidden while {@code "11112"} is left alone. Map keys are never
 * changed.
 *
 * <p>Immutable; safe to share between threads.
 */
final class SecretParams {

    /** The shortest secret replaced wherever it occurs inside a longer text. */
    static final int MIN_SUBSTRING = 8;

    /** At most this many names are accepted in {@code _meta.secretParams}. */
    static final int MAX_NAMES = 100;

    private static final int MAX_DEPTH = 200;

    /** No secrets: every method returns its argument. */
    static final SecretParams NONE = new SecretParams(Collections.<String>emptySet());

    private final Set<String> exact;
    /** Longest first, so that a secret containing another is replaced whole. */
    private final List<String> ordered;

    private SecretParams(Set<String> values) {
        this.exact = values;
        List<String> sorted = new ArrayList<String>(values);
        Collections.sort(sorted, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.length() != b.length() ? b.length() - a.length() : a.compareTo(b);
            }
        });
        this.ordered = Collections.unmodifiableList(sorted);
    }

    /**
     * The secrets of one request's {@code params}. A request without {@code _meta.secretParams} (or whose params or
     * {@code _meta} are not objects, which the dispatcher reports itself) has none.
     *
     * @throws RpcException {@code INVALID_PARAMS} with {@code data.param = "_meta.secretParams"} when the entry is
     *     not an array of at most {@link #MAX_NAMES} strings
     */
    static SecretParams of(String method, Object rawParams) {
        if (!(rawParams instanceof Map)) {
            return NONE;
        }
        Map<?, ?> params = (Map<?, ?>) rawParams;
        Object meta = params.get("_meta");
        if (!(meta instanceof Map) || ((Map<?, ?>) meta).get(Protocol.META_SECRET_PARAMS) == null) {
            return NONE;
        }
        Object names = ((Map<?, ?>) meta).get(Protocol.META_SECRET_PARAMS);
        if (!(names instanceof List) || ((List<?>) names).size() > MAX_NAMES) {
            throw invalid(method);
        }
        Set<String> values = new LinkedHashSet<String>();
        for (Object name : (List<?>) names) {
            if (!(name instanceof String)) {
                throw invalid(method);
            }
            if (!"_meta".equals(name) && params.containsKey(name)) {
                collect(params.get(name), values, 0);
            }
        }
        return values.isEmpty() ? NONE : new SecretParams(Collections.unmodifiableSet(values));
    }

    /** {@link #of}, with a malformed list read as no secrets (the dispatcher refuses such a call before it runs). */
    static SecretParams lenient(String method, Object rawParams) {
        try {
            return of(method, rawParams);
        } catch (RpcException malformed) {
            return NONE;
        }
    }

    private static RpcException invalid(String method) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("primitive", method);
        data.put("param", "_meta." + Protocol.META_SECRET_PARAMS);
        return new RpcException(ErrorCodes.INVALID_PARAMS, "_meta." + Protocol.META_SECRET_PARAMS
                + " must be an array of at most " + MAX_NAMES + " parameter names", data);
    }

    private static void collect(Object value, Set<String> out, int depth) {
        if (depth > MAX_DEPTH || value == null || value instanceof Boolean) {
            return;
        }
        if (value instanceof Map) {
            for (Object v : ((Map<?, ?>) value).values()) {
                collect(v, out, depth + 1);
            }
        } else if (value instanceof List) {
            for (Object v : (List<?>) value) {
                collect(v, out, depth + 1);
            }
        } else {
            String text = value instanceof Number ? numberText((Number) value) : String.valueOf(value);
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
    }

    private static String numberText(Number n) {
        if (n instanceof BigDecimal) {
            return ((BigDecimal) n).toPlainString();
        }
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
        }
        return String.valueOf(n);
    }

    /** True when the call declared no secret, or none of the named parameters had a value. */
    boolean isEmpty() {
        return exact.isEmpty();
    }

    /** {@code text} with every secret hidden (see the class comment); null stays null. */
    String redact(String text) {
        if (text == null || exact.isEmpty()) {
            return text;
        }
        if (exact.contains(text)) {
            return SecretKeys.REDACTED;
        }
        String out = text;
        for (String secret : ordered) {
            out = secret.length() >= MIN_SUBSTRING ? out.replace(secret, SecretKeys.REDACTED) : replaceTokens(out,
                    secret);
        }
        return out;
    }

    /** Replaces the occurrences of {@code secret} that are not part of a longer run of letters or digits. */
    private static String replaceTokens(String text, String secret) {
        int from = text.indexOf(secret);
        if (from < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int copied = 0;
        while (from >= 0) {
            int end = from + secret.length();
            boolean before = from > 0 && Character.isLetterOrDigit(text.charAt(from - 1));
            boolean after = end < text.length() && Character.isLetterOrDigit(text.charAt(end));
            if (!before && !after) {
                sb.append(text, copied, from).append(SecretKeys.REDACTED);
                copied = end;
                from = text.indexOf(secret, end);
            } else {
                from = text.indexOf(secret, from + 1);
            }
        }
        return sb.append(text, copied, text.length()).toString();
    }

    /**
     * A copy of a JSON-like value (an error's {@code data}) with every string redacted and every number equal to a
     * secret replaced; map keys and everything else are kept. Values nested deeper than 200 are returned as they are
     * (the JSON codec refuses them anyway).
     */
    Object redact(Object value) {
        return exact.isEmpty() ? value : redact(value, 0);
    }

    private Object redact(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return value;
        }
        if (value instanceof String) {
            return redact((String) value);
        }
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                copy.put(e.getKey(), redact(e.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<Object>(((List<?>) value).size());
            for (Object item : (List<?>) value) {
                copy.add(redact(item, depth + 1));
            }
            return copy;
        }
        if (value instanceof Number && exact.contains(numberText((Number) value))) {
            return SecretKeys.REDACTED;
        }
        return value;
    }

    /** The error with its message and data redacted; the same object when there is nothing to hide. */
    @SuppressWarnings("unchecked")
    RpcError redact(RpcError error) {
        if (error == null || exact.isEmpty()) {
            return error;
        }
        Map<String, Object> data = error.data() == null ? null : (Map<String, Object>) redact((Object) error.data());
        try {
            return new RpcError(error.code(), redact(error.message()), data);
        } catch (RuntimeException unrepresentable) {
            return new RpcError(error.code(), redact(error.message()), null);
        }
    }
}
