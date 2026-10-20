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

package ai.skillroy.testjava.protocol;

import ai.skillroy.testjava.protocol.text.TextBounds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One warning of a successful call (phase8-contracts.md 7.1, gap 18, amendment A83): the call did what it was asked,
 * with a caveat a scenario step must accept ({@code accept: [CODE]}) or fail on. A warning travels in the call's
 * envelope ({@link RpcResult#warnings()}), never as a key of the primitive's value; a module raises one through
 * {@code Invocation.warn} and the agent's dispatcher builds the object.
 *
 * <p>Wire shape: {@code {"code": …, "message"?: …, "data"?: {…}}}. {@code code} is a base code ({@link #BASE_CODE},
 * the meanings of {@link #BASE_CODES}) or a pack code {@code <packId>.<NAME>} ({@link #PACK_CODE}) the pack declares.
 * {@code message} is one printable line, control characters replaced by spaces, cut at {@link #MAX_MESSAGE}
 * characters; {@code data} is a frozen JSON object whose serialised text is at most {@link #MAX_DATA_CHARS}
 * characters (a larger one, or one the JSON codec cannot represent, becomes {@code {"truncated": true}}).
 *
 * <p>The bounds apply after redaction; a caller that builds one from text holding a secret redacts first (the agent's
 * dispatcher removes the call's secret parameters from the raw message and data before it builds the warning, so that
 * no cut leaves a secret's prefix behind).
 *
 * <p>Immutable; safe to share between threads.
 */
public final class CallWarning {

    /** The application took the input and had not consumed it when the call returned; derived by the executor. */
    public static final String INPUT_QUEUED = "INPUT_QUEUED";
    /** The application consumed a different value than the one sent. */
    public static final String VALUE_COERCED = "VALUE_COERCED";
    /** The application consumed the input and ignored its value. */
    public static final String VALUE_DROPPED = "VALUE_DROPPED";
    /** The input was consumed by another question than the one the step addressed. */
    public static final String ANSWERED_NESTED = "ANSWERED_NESTED";
    /** A malformed element of the channel (never acceptable). */
    public static final String INVALID_WARNING = "INVALID_WARNING";
    /** The channel held more than {@link #MAX_PER_RESULT} warnings (never acceptable). */
    public static final String TOO_MANY_WARNINGS = "TOO_MANY_WARNINGS";

    /** The six base codes. */
    public static final Set<String> BASE_CODES = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            INPUT_QUEUED, VALUE_COERCED, VALUE_DROPPED, ANSWERED_NESTED, INVALID_WARNING, TOO_MANY_WARNINGS)));

    /** The two base codes no step can accept: a malformed or overfull channel fails closed. */
    public static final Set<String> NEVER_ACCEPTABLE = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(INVALID_WARNING, TOO_MANY_WARNINGS)));

    /** The shape of a base code. */
    public static final Pattern BASE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    /** The shape of a pack code: the pack's id, a dot, a name. */
    public static final Pattern PACK_CODE = Pattern.compile("[a-z][a-z0-9-]{0,31}\\.[A-Z][A-Z0-9_]{0,63}");

    /** At most this many warnings per result; the rest becomes one {@link #TOO_MANY_WARNINGS}. */
    public static final int MAX_PER_RESULT = 16;
    /** The longest message, in characters. */
    public static final int MAX_MESSAGE = 500;
    /** The longest serialised {@code data}, in characters. */
    public static final int MAX_DATA_CHARS = 2048;

    /** The most characters of a malformed element {@link #fromJson} keeps in {@code data.raw}. */
    static final int MAX_RAW = 200;

    private static final String TYPE = "CallWarning";

    private final String code;
    private final String message;
    private final Map<String, Object> data;

    /**
     * @param code a base or pack code (see the class comment)
     * @param message the message, or null; sanitised and cut (see the class comment)
     * @param data a JSON-able object, or null; frozen and bounded (see the class comment)
     * @throws IllegalArgumentException if {@code code} is null or matches neither shape
     */
    public CallWarning(String code, String message, Map<String, Object> data) {
        if (!isValidCode(code)) {
            throw new IllegalArgumentException("warning code must match " + BASE_CODE.pattern() + " or "
                    + PACK_CODE.pattern() + ": " + quoted(code));
        }
        this.code = code;
        this.message = message == null ? null : line(message);
        this.data = data == null ? null : bounded(data);
    }

    /** True when {@code code} is a base or a pack code by shape. */
    public static boolean isValidCode(String code) {
        return code != null && (BASE_CODE.matcher(code).matches() || PACK_CODE.matcher(code).matches());
    }

    /** True when {@code code} is a pack code by shape ({@code <packId>.<NAME>}). */
    public static boolean isPackCodeShape(String code) {
        return code != null && PACK_CODE.matcher(code).matches();
    }

    public String code() {
        return code;
    }

    /** The sanitised message, or null. */
    public String message() {
        return message;
    }

    /** The frozen data, or null. */
    public Map<String, Object> data() {
        return data;
    }

    /** True for a {@code <packId>.<NAME>} code. */
    public boolean isPackCode() {
        return isPackCodeShape(code);
    }

    /** The wire shape; {@code message} and {@code data} only when present. A new map on each call. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("code", code);
        if (message != null) {
            json.put("message", message);
        }
        if (data != null) {
            json.put("data", data);
        }
        return json;
    }

    /**
     * Reads one element of a result's {@code warnings}, leniently: an element that is not an object, whose
     * {@code code} is absent or malformed, whose {@code message} is not a string or whose {@code data} is not an
     * object reads as {@link #INVALID_WARNING} with {@code data {raw: <the element's text, at most 200 chars>}}.
     * Never throws.
     */
    public static CallWarning fromJson(Map<String, Object> json) {
        return read(json);
    }

    /**
     * Reads a result's {@code warnings} member (phase8-contracts.md 7.1): null is empty; a value that is not a list is
     * one {@link #INVALID_WARNING}; at most {@link #MAX_PER_RESULT} elements are read and the rest is replaced by one
     * {@link #TOO_MANY_WARNINGS} {@code {dropped: n}}. Never throws.
     */
    public static List<CallWarning> readAll(Object rawList) {
        if (rawList == null) {
            return Collections.emptyList();
        }
        List<CallWarning> out = new ArrayList<CallWarning>();
        if (!(rawList instanceof List)) {
            out.add(invalid(rawList));
            return Collections.unmodifiableList(out);
        }
        long dropped = 0;
        for (Object element : (List<?>) rawList) {
            if (out.size() < MAX_PER_RESULT) {
                out.add(read(element));
            } else if (element instanceof Map && TOO_MANY_WARNINGS.equals(((Map<?, ?>) element).get("code"))
                    && ((Map<?, ?>) element).get("data") instanceof Map
                    && ((Map<?, ?>) ((Map<?, ?>) element).get("data")).get("dropped") instanceof Long) {
                // A list folded before (by the agent) folds to itself.
                dropped += ((Long) ((Map<?, ?>) ((Map<?, ?>) element).get("data")).get("dropped")).longValue();
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            out.add(tooMany(dropped));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The first {@link #MAX_PER_RESULT} warnings, and the rest folded into one {@link #TOO_MANY_WARNINGS}
     * {@code {dropped: n}} (a {@code TOO_MANY_WARNINGS} among the rest adds its own count, so that a folded list folds
     * to itself). Unmodifiable.
     */
    static List<CallWarning> fold(List<CallWarning> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return Collections.emptyList();
        }
        List<CallWarning> out = new ArrayList<CallWarning>();
        long dropped = 0;
        for (CallWarning warning : warnings) {
            if (warning == null) {
                throw new NullPointerException("warnings must not contain null");
            }
            if (out.size() < MAX_PER_RESULT) {
                out.add(warning);
            } else if (TOO_MANY_WARNINGS.equals(warning.code()) && warning.data() != null
                    && warning.data().get("dropped") instanceof Long) {
                dropped += ((Long) warning.data().get("dropped")).longValue();
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            out.add(tooMany(dropped));
        }
        return Collections.unmodifiableList(out);
    }

    /** One {@link #TOO_MANY_WARNINGS} {@code {dropped: n}}. */
    public static CallWarning tooMany(long dropped) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("dropped", Long.valueOf(dropped));
        return new CallWarning(TOO_MANY_WARNINGS, "more than " + MAX_PER_RESULT + " warnings; " + dropped
                + " dropped", data);
    }

    private static CallWarning read(Object element) {
        if (!(element instanceof Map)) {
            return invalid(element);
        }
        Map<?, ?> json = (Map<?, ?>) element;
        Object code = json.get("code");
        Object message = json.get("message");
        Object data = json.get("data");
        if (!(code instanceof String) || !isValidCode((String) code)
                || (message != null && !(message instanceof String))
                || (data != null && !(data instanceof Map))) {
            return invalid(element);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = (Map<String, Object>) data;
        return new CallWarning((String) code, (String) message, dataMap);
    }

    private static CallWarning invalid(Object element) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("raw", TextBounds.cut(line(Coerce.safeWrite(element)), MAX_RAW));
        return new CallWarning(INVALID_WARNING, "a warning that is not {code, message?, data?} with a valid code",
                data);
    }

    /** One printable line: control characters (and line and paragraph separators) become spaces; cut at 500. */
    static String line(String text) {
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_MESSAGE));
        for (int i = 0; i < text.length() && sb.length() < MAX_MESSAGE; i++) {
            char c = text.charAt(i);
            sb.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? ' ' : c);
        }
        return TextBounds.cut(sb.toString(), MAX_MESSAGE);
    }

    private static Map<String, Object> bounded(Map<String, Object> data) {
        try {
            if (TextBounds.exceeds(data, 4L * MAX_DATA_CHARS)) {
                return truncated();
            }
            Map<String, Object> frozen = Coerce.freezeMap(data);
            if (Json.write(frozen).length() > MAX_DATA_CHARS) {
                return truncated();
            }
            return frozen;
        } catch (RuntimeException unrepresentable) {
            return truncated();
        }
    }

    private static Map<String, Object> truncated() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("truncated", Boolean.TRUE);
        return Collections.unmodifiableMap(out);
    }

    private static String quoted(String code) {
        return code == null ? "null" : "\"" + TextBounds.cut(line(code), 80) + "\"";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CallWarning)) {
            return false;
        }
        CallWarning other = (CallWarning) o;
        return code.equals(other.code) && Objects.equals(message, other.message) && Objects.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message, data);
    }

    @Override
    public String toString() {
        return TYPE + Coerce.safeWrite(toJson());
    }
}
