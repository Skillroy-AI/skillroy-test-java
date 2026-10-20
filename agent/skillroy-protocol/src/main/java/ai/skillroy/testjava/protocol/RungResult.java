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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The rung convention of a ladder primitive (phase8-contracts.md 7.6, gap 22, amendment A86): a call whose plan
 * declares a ladder of rungs (best first) reports the rung that acted. The primitive chooses at the moment of acting,
 * since only the agent side knows whether the physical path is there:
 *
 * <pre>
 * { "rung": "SEMANTIC",            // required for a ladder call
 *   "requestedRung": "PHYSICAL",   // the ladder's first rung, optional
 *   "fidelity": "FULL" | "DEGRADED",
 *   "reason": "…",                 // why a lower rung acted, optional
 *   "minRung": "PHYSICAL" }        // the echo of a minRung parameter the primitive received and applied
 * </pre>
 *
 * <p>A ladder primitive that receives {@code minRung} refuses to act below it ({@code CAPABILITY_UNAVAILABLE} with
 * {@code data {rung, minRung, capability?}}) and echoes it in its result: the controller fails closed without the
 * echo, since a 0.1.0 agent ignores a parameter it does not know (phase8-contracts.md 2.1). It does not retry below
 * the minimum on a transient failure of a permitted rung.
 *
 * <p>Constants and a builder; safe from any thread.
 */
public final class RungResult {

    /** The rung that acted. */
    public static final String RUNG = "rung";
    /** The rung the call asked for first. */
    public static final String REQUESTED_RUNG = "requestedRung";
    /** {@link #FULL} or {@link #DEGRADED}. */
    public static final String FIDELITY = "fidelity";
    /** Why a lower rung acted. */
    public static final String REASON = "reason";
    /** The echo of the {@code minRung} parameter (and the parameter's own name). */
    public static final String MIN_RUNG = "minRung";
    /** The capability of a rung, in a refusal's data. */
    public static final String CAPABILITY = "capability";

    /** {@link #FIDELITY}: the rung asked for acted. */
    public static final String FULL = "FULL";
    /** {@link #FIDELITY}: a lower rung acted. */
    public static final String DEGRADED = "DEGRADED";

    /** A device's own path: a simulator or the application's driver code, where the real device hands data over. */
    public static final String PHYSICAL = "PHYSICAL";
    /** A shortcut into the application (design 3.7's name). */
    public static final String SEMANTIC = "SEMANTIC";

    /** The shape of a rung's name. */
    public static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    private RungResult() {
    }

    /**
     * A new, modifiable result map holding the given keys (null ones left out), for a primitive to add its own keys
     * to.
     *
     * @param rung the rung that acted (required)
     * @param requestedRung the rung asked for first, or null
     * @param fidelity {@link #FULL}, {@link #DEGRADED} or null
     * @param reason why a lower rung acted, or null
     * @throws IllegalArgumentException if {@code rung} or {@code requestedRung} is not a valid name
     */
    public static Map<String, Object> of(String rung, String requestedRung, String fidelity, String reason) {
        if (!isName(rung)) {
            throw new IllegalArgumentException("rung must match " + NAME.pattern() + ": " + rung);
        }
        if (requestedRung != null && !isName(requestedRung)) {
            throw new IllegalArgumentException("requestedRung must match " + NAME.pattern() + ": " + requestedRung);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put(RUNG, rung);
        if (requestedRung != null) {
            out.put(REQUESTED_RUNG, requestedRung);
        }
        if (fidelity != null) {
            out.put(FIDELITY, fidelity);
        }
        if (reason != null) {
            out.put(REASON, reason);
        }
        return out;
    }

    /** True for a valid rung name. */
    public static boolean isName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The value's {@code rung} when it is a string, else null. */
    public static String rung(Object value) {
        return string(value, RUNG);
    }

    /** The value's {@code minRung} echo when it is a string, else null. */
    public static String minRung(Object value) {
        return string(value, MIN_RUNG);
    }

    private static String string(Object value, String key) {
        Object member = value instanceof Map ? ((Map<?, ?>) value).get(key) : null;
        return member instanceof String ? (String) member : null;
    }
}
