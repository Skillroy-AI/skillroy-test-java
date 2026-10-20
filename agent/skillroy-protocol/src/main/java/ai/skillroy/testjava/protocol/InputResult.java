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

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The semantic-input result convention (phase8-contracts.md 7.2 and 7.4, gaps 18 and 107, amendment A86): the keys a
 * module's input primitive ({@code <domain>.input}, a device action that injects an input, a composite's last input)
 * puts in its value, so that the executor can tell "taken but not yet consumed" from "consumed" and tie an
 * acknowledgement to its input. Every field is optional and additive:
 *
 * <pre>
 * { "accepted": true,
 *   "inputId": "lending-42",            // minted by the module: opaque, unique within the agent's life
 *   "consumed": "CONSUMED" | "QUEUED",  // consumed before the call returned, or taken and not yet consumed
 *   "taken": "…",                       // only with VALUE_COERCED; never when the sent value is a secret parameter
 *   "answeredBy": {"kind": "question" | "prompt", "id"?: "…", "text"?: "…"} }   // only with ANSWERED_NESTED
 * </pre>
 *
 * <p>The caveats travel as warnings ({@link CallWarning}): {@code VALUE_COERCED} with {@code taken},
 * {@code VALUE_DROPPED}, {@code ANSWERED_NESTED} with {@code answeredBy}. {@code INPUT_REJECTED} keeps meaning
 * "refused before it was handed over" (amendment A33); its {@code data.reason} takes the standard values below.
 * Refused is an error, handed over with a caveat is a warning; the two never overlap.
 *
 * <p>Constants and stateless readers only; safe from any thread.
 */
public final class InputResult {

    /** {@code true} when the application took the input. */
    public static final String ACCEPTED = "accepted";
    /** The input's id, minted by the module or echoed when the caller supplied one. */
    public static final String INPUT_ID = "inputId";
    /** A composite's ids of every input it posted, in order. */
    public static final String INPUT_IDS = "inputIds";
    /** {@link #STATE_CONSUMED} or {@link #STATE_QUEUED}. */
    public static final String CONSUMED = "consumed";
    /** The value the application consumed, when it differs from the one sent ({@code VALUE_COERCED}). */
    public static final String TAKEN = "taken";
    /** The question that consumed the input ({@code ANSWERED_NESTED}). */
    public static final String ANSWERED_BY = "answeredBy";

    /** {@link #CONSUMED}: consumed before the call returned. */
    public static final String STATE_CONSUMED = "CONSUMED";
    /** {@link #CONSUMED}: taken and not yet consumed when the call returned. */
    public static final String STATE_QUEUED = "QUEUED";

    /** The {@code INPUT_REJECTED} data key of the refusal's reason. */
    public static final String REASON = "reason";
    /** {@link #REASON}: the application's current state does not accept the input. */
    public static final String NOT_ACCEPTED = "notAccepted";
    /** {@link #REASON}: the application's input queue is full. */
    public static final String INBOX_FULL = "inboxFull";
    /** {@link #REASON}: a layer vetoes input to the component (phase8-contracts.md 3.2). */
    public static final String VETOED = "vetoed";
    /** {@link #REASON}: the point lies outside what the component shows (phase8-contracts.md 3.3). */
    public static final String NOT_VISIBLE = "not visible";

    /** The shape of an input id. */
    public static final Pattern INPUT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private InputResult() {
    }

    /**
     * The value's {@code inputId}: null when the value is not an object, the key is absent, its value is not a
     * string, or the string does not match {@link #INPUT_ID_PATTERN} (an id the executor cannot match on is no id).
     */
    public static String inputId(Object value) {
        Object id = member(value, INPUT_ID);
        return id instanceof String && INPUT_ID_PATTERN.matcher((String) id).matches() ? (String) id : null;
    }

    /** The value's {@code consumed}: null when the value is not an object, the key is absent or not a string. */
    public static String consumed(Object value) {
        Object consumed = member(value, CONSUMED);
        return consumed instanceof String ? (String) consumed : null;
    }

    /** True when the value says {@code consumed: QUEUED}. */
    public static boolean queued(Object value) {
        return STATE_QUEUED.equals(consumed(value));
    }

    private static Object member(Object value, String key) {
        return value instanceof Map ? ((Map<?, ?>) value).get(key) : null;
    }
}
