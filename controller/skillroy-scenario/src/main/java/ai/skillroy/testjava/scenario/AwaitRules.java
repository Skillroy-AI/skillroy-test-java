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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.protocol.PayloadMatch;
import ai.skillroy.testjava.protocol.TopicFilter;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;

import java.util.List;
import java.util.Map;

/**
 * The rules for a call's {@link AwaitSpec} ({@code Sync.EVENT}) that the compiler enforces and {@code Plan.fromJson}
 * re-checks, and its resolution at run time. Stateless; safe from any thread.
 */
final class AwaitRules {

    /** The longest wait {@code sync.await} allows: the agent caps its {@code timeoutMs} at 600 s. */
    static final long MAX_WAIT_MS = 600_000L;

    private AwaitRules() {
    }

    /**
     * Null if {@code match} (in JSON normal form) is valid, else the reason: keys must not be blank, and each value is
     * a scalar (top-level payload equality by text) or {@code {has: scalar}} (a list member equal by text), the shared
     * rule of {@link PayloadMatch} (phase8-contracts.md 8.9, D606).
     */
    static String checkMatch(Map<String, Object> match) {
        for (Map.Entry<String, Object> e : match.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                return "'match' keys must be payload keys, found a blank key";
            }
        }
        return PayloadMatch.problem(match);
    }

    /** Null if {@code timeoutMs} is a positive wait the agent honours, else the reason. */
    static String checkTimeout(long timeoutMs) {
        if (timeoutMs <= 0) {
            return "'timeoutMs' must be positive, found " + timeoutMs;
        }
        if (timeoutMs > MAX_WAIT_MS) {
            return "'timeoutMs' must be at most " + MAX_WAIT_MS + " ms (the agent's limit for sync.await), found "
                    + timeoutMs + " ms";
        }
        return null;
    }

    /**
     * The spec with its references substituted and a 0 timeout replaced by {@code stepTimeoutMs} (at most
     * {@link #MAX_WAIT_MS}). A topics reference may resolve to a list of globs, which are joined with commas.
     *
     * @return null when {@code spec} is null
     * @throws ResolutionException if a reference does not resolve, the topics resolve to no glob, or the match holds a
     *     list or a map where a scalar belongs once substituted (reason {@code REFERENCE}, phase8-contracts.md 8.9)
     */
    static AwaitSpec resolve(AwaitSpec spec, Bindings bindings, long stepTimeoutMs) throws ResolutionException {
        if (spec == null) {
            return null;
        }
        String text = topicsText(bindings.substitutePublic(spec.topics()));
        if (TopicFilter.parse(text).globs().isEmpty()) {
            throw new ResolutionException(spec.topics(), "await topics " + spec.topics() + " resolved to no topic"
                    + " glob: \"" + text + "\"");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> match = spec.match() == null ? null : (Map<String, Object>) bindings.substitutePublic(
                spec.match());
        String problem = match == null ? null : PayloadMatch.problem(match);
        if (problem != null) {
            throw new ResolutionException(ResolutionException.REFERENCE, null, "await match " + resolvedMatch(spec,
                    match) + "; a match value is a scalar or {has: scalar}");
        }
        long timeoutMs = spec.timeoutMs() > 0 ? spec.timeoutMs() : Math.min(Math.max(1L, stepTimeoutMs),
                MAX_WAIT_MS);
        return new AwaitSpec(text, match, timeoutMs);
    }

    /** For a refusal: the first entry the substitution turned into a list or a map, by its key. */
    private static String resolvedMatch(AwaitSpec spec, Map<String, Object> match) {
        for (Map.Entry<String, Object> e : match.entrySet()) {
            Object value = e.getValue();
            Object written = spec.match().get(e.getKey());
            if (value instanceof Map<?, ?> operator && operator.size() == 1 && operator.containsKey(PayloadMatch.HAS)) {
                value = operator.get(PayloadMatch.HAS);
                written = written instanceof Map<?, ?> w ? w.get(PayloadMatch.HAS) : written;
            }
            if ((value instanceof Map<?, ?> || value instanceof List<?>) && written instanceof String) {
                return "'" + e.getKey() + "' resolved to " + ScenarioLoader.describe(value);
            }
        }
        return "is not valid: " + PayloadMatch.problem(match);
    }

    /** The text of substituted await topics: a list of globs joined with commas, else the value's text. */
    static String topicsText(Object topics) {
        if (topics instanceof List<?> list) {
            StringBuilder joined = new StringBuilder();
            for (Object item : list) {
                joined.append(joined.length() == 0 ? "" : ",").append(Values.stringOf(item));
            }
            return joined.toString();
        }
        return Values.stringOf(topics);
    }
}
