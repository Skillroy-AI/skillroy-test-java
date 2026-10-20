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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The composite-primitive result convention (phase8-contracts.md 7.5, gap 19, amendment A86). Logic that depends on
 * which question the application asks next lives in one agent-side composite primitive that owns its nested
 * questions; it reports what it did in a nested object, so that it never collides with a flat key of the value:
 *
 * <pre>
 * { "composite": {
 *     "branch": "password",               // [A-Za-z][A-Za-z0-9_.-]{0,63}, from the set the primitive documents
 *     "answered": [                       // every question the composite answered, in order, at most 32
 *       { "question": {"topic": "input.requested", "id"?: "…", "text"?: "…", "seq"?: 412},
 *         "by": "password",               // the parameter that answered it: its name, never its value
 *         "choice"?: "Yes",               // a button label; only when "by" is not a secret parameter
 *         "inputId"?: "lending-18" } ] },
 *   "inputId"?: "lending-18", "inputIds"?: ["lending-17", "lending-18"], "consumed"?: "CONSUMED" }
 * </pre>
 *
 * <p>{@code question.seq} is required when the module published an event for that question: the executor expects a
 * prompt event whose {@code seq} the composite lists and that lies inside the call's window, and only such a one. The
 * executor checks every result that has a {@code composite} key with {@link #problems}; a layer calls it from its
 * module tests too.
 *
 * <p>Stateless readers and a builder (not thread-safe; one per result).
 */
public final class CompositeResult {

    /** The result key of the nested object. */
    public static final String COMPOSITE = "composite";
    /** The branch the composite took. */
    public static final String BRANCH = "branch";
    /** The questions it answered. */
    public static final String ANSWERED = "answered";
    /** An answered entry's question. */
    public static final String QUESTION = "question";
    /** A question's topic. */
    public static final String TOPIC = "topic";
    /** A question's id. */
    public static final String ID = "id";
    /** A question's text. */
    public static final String TEXT = "text";
    /** A question's event sequence number. */
    public static final String SEQ = "seq";
    /** The parameter that answered a question. */
    public static final String BY = "by";
    /** The button a question was answered with. */
    public static final String CHOICE = "choice";

    /** At most this many answered questions. */
    public static final int MAX_ANSWERED = 32;

    /** The shape of a branch. */
    public static final Pattern BRANCH_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    private static final int MAX_QUOTED = 64;

    private final Map<String, Object> composite = new LinkedHashMap<String, Object>();
    private final List<Object> answered = new ArrayList<Object>();

    private CompositeResult() {
    }

    /** A new builder. */
    public static CompositeResult builder() {
        return new CompositeResult();
    }

    /** Sets the branch. */
    public CompositeResult branch(String branch) {
        composite.put(BRANCH, branch);
        return this;
    }

    /**
     * Adds one answered question; every argument but {@code topic} and {@code by} may be null.
     *
     * @param seq the question's event sequence number, or null when no event was published for it
     */
    public CompositeResult answered(String topic, String id, String text, Long seq, String by, String choice,
                                    String inputId) {
        Map<String, Object> question = new LinkedHashMap<String, Object>();
        question.put(TOPIC, topic);
        putIf(question, ID, id);
        putIf(question, TEXT, text);
        putIf(question, SEQ, seq);
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put(QUESTION, question);
        entry.put(BY, by);
        putIf(entry, CHOICE, choice);
        putIf(entry, InputResult.INPUT_ID, inputId);
        answered.add(entry);
        return this;
    }

    /** A new, modifiable result map {@code {composite: {branch, answered}}}, for the primitive to add its keys to. */
    public Map<String, Object> build() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>(composite);
        nested.put(ANSWERED, new ArrayList<Object>(answered));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put(COMPOSITE, nested);
        return out;
    }

    private static void putIf(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * The violations of the convention in a result value: empty when the value has no {@code composite} key (not a
     * composite) or follows the convention. Each is one line naming what is wrong (never a value of a parameter).
     *
     * @param value the primitive's result value
     * @param parameterNames the names of the call's parameters
     * @param secretParameterNames the names of the call's secret parameters ({@code _meta.secretParams})
     */
    public static List<String> problems(Object value, Collection<String> parameterNames,
                                        Collection<String> secretParameterNames) {
        if (!(value instanceof Map) || !((Map<?, ?>) value).containsKey(COMPOSITE)) {
            return Collections.emptyList();
        }
        Collection<String> params = parameterNames == null ? Collections.<String>emptyList() : parameterNames;
        Collection<String> secrets = secretParameterNames == null ? Collections.<String>emptyList()
                : secretParameterNames;
        List<String> out = new ArrayList<String>();
        Object composite = ((Map<?, ?>) value).get(COMPOSITE);
        if (!(composite instanceof Map)) {
            out.add("composite is not an object");
            return out;
        }
        Map<?, ?> nested = (Map<?, ?>) composite;
        Object branch = nested.get(BRANCH);
        if (branch == null) {
            out.add("composite.branch is missing");
        } else if (!(branch instanceof String) || !BRANCH_NAME.matcher((String) branch).matches()) {
            out.add("composite.branch is not a name matching " + BRANCH_NAME.pattern());
        }
        Object answered = nested.get(ANSWERED);
        if (answered == null) {
            return out;
        }
        if (!(answered instanceof List)) {
            out.add("composite.answered is not a list");
            return out;
        }
        List<?> entries = (List<?>) answered;
        if (entries.size() > MAX_ANSWERED) {
            out.add("composite.answered holds " + entries.size() + " entries; at most " + MAX_ANSWERED);
            return out;
        }
        for (int i = 0; i < entries.size(); i++) {
            String at = "composite.answered[" + i + "]";
            Object item = entries.get(i);
            if (!(item instanceof Map)) {
                out.add(at + " is not an object");
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            Object question = entry.get(QUESTION);
            if (!(question instanceof Map)) {
                out.add(at + ".question is not an object");
            } else {
                Object seq = ((Map<?, ?>) question).get(SEQ);
                if (seq != null && positive(seq) == null) {
                    out.add(at + ".question.seq is not a positive integer");
                }
            }
            Object by = entry.get(BY);
            if (by == null) {
                out.add(at + ".by is missing");
            } else if (!(by instanceof String) || !params.contains(by)) {
                // Never quoted: a "by" that names no parameter may hold a value.
                out.add(at + ".by names no parameter of the call");
            } else if (entry.get(CHOICE) != null && secrets.contains(by)) {
                out.add(at + ".choice is given for the secret parameter " + quoted(by));
            }
        }
        return out;
    }

    /**
     * The sequence numbers of the answered questions that carry a positive integer {@code question.seq}, in order;
     * empty for a value that is not a composite or does not follow the convention there.
     */
    public static List<Long> answeredSeqs(Object value) {
        if (!(value instanceof Map) || !(((Map<?, ?>) value).get(COMPOSITE) instanceof Map)) {
            return Collections.emptyList();
        }
        Object answered = ((Map<?, ?>) ((Map<?, ?>) value).get(COMPOSITE)).get(ANSWERED);
        if (!(answered instanceof List)) {
            return Collections.emptyList();
        }
        List<Long> out = new ArrayList<Long>();
        for (Object item : (List<?>) answered) {
            if (out.size() >= MAX_ANSWERED) {
                break;
            }
            if (item instanceof Map && ((Map<?, ?>) item).get(QUESTION) instanceof Map) {
                Long seq = positive(((Map<?, ?>) ((Map<?, ?>) item).get(QUESTION)).get(SEQ));
                if (seq != null) {
                    out.add(seq);
                }
            }
        }
        return out;
    }

    /** The value as a positive long when it is an integral number, else null. */
    private static Long positive(Object seq) {
        if (seq instanceof Long || seq instanceof Integer || seq instanceof Short || seq instanceof Byte) {
            long l = ((Number) seq).longValue();
            return l > 0 ? Long.valueOf(l) : null;
        }
        if (seq instanceof Number && !(seq instanceof Double) && !(seq instanceof Float)) {
            Long l = Coerce.toLong(seq);
            return l != null && l.longValue() > 0 ? l : null;
        }
        if (seq instanceof Double || seq instanceof Float) {
            double d = ((Number) seq).doubleValue();
            return d == Math.rint(d) && d >= 1 && d < 9.007199254740992E15 ? Long.valueOf((long) d) : null;
        }
        return null;
    }

    private static String quoted(Object name) {
        String text = Coerce.toStr(name);
        if (text == null) {
            return Coerce.typeName(name);
        }
        return "'" + CallWarning.line(text.length() > MAX_QUOTED ? text.substring(0, MAX_QUOTED) + "..." : text) + "'";
    }
}
