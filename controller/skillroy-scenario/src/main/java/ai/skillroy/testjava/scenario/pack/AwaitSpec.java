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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.protocol.TopicFilter;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The event that completes a planned call with {@link Sync#EVENT}: the executor awaits the first event published
 * since the call's {@code seq.before} whose topic matches {@code topics} and whose payload matches {@code match}.
 * In {@code plan.json} it is the call's {@code "await": {"topics":…, "match":…, "timeoutMs":…}}.
 *
 * <p>{@code topics} and the values of {@code match} may hold {@code $slot} references; they are resolved when the
 * scenario runs, never at compile time. The compiler checks the rest: {@code match} must be JSON-compatible with
 * scalar values (strings, numbers, booleans, null), and a wait may last at most 600 s, the agent's limit for
 * {@code sync.await}. A timeout of 0 becomes the scenario's step timeout (at most 600 s) in the plan.
 *
 * <p>Immutable apart from the values inside {@code match}, which the compiler deep-copies into the plan. Safe to
 * share between threads.
 *
 * @param topics comma-separated topic globs, as for {@code sync.await}; at least one glob
 * @param match top-level payload equality: every entry must equal the event's payload value of the same key (compared
 *     by text, as {@code sync.await} does); null means any payload
 * @param timeoutMs how long to wait in milliseconds; 0 means the scenario's step timeout
 */
public record AwaitSpec(String topics, Map<String, Object> match, long timeoutMs) {

    /**
     * @throws IllegalArgumentException if {@code topics} names no glob or {@code timeoutMs} is negative
     */
    public AwaitSpec {
        if (topics == null || TopicFilter.parse(topics).globs().isEmpty()) {
            throw new IllegalArgumentException("an await spec needs at least one topic glob, found "
                    + (topics == null ? "null" : "\"" + topics + "\""));
        }
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("an await spec's timeoutMs must be 0 (the step timeout) or positive: "
                    + timeoutMs);
        }
        match = match == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(match));
    }

    /** An await on {@code topics} (comma-separated globs) with any payload and the scenario's step timeout. */
    public static AwaitSpec of(String topics) {
        return new AwaitSpec(topics, null, 0);
    }

    /**
     * An await on any of {@code topics} (each a glob, or several separated by commas) with any payload and the
     * scenario's step timeout; for example the pack's ready topics, {@code AwaitSpec.anyOf(policy.readyTopics())}.
     *
     * @throws IllegalArgumentException if the collection names no glob
     */
    public static AwaitSpec anyOf(Collection<String> topics) {
        if (topics == null) {
            throw new IllegalArgumentException("an await spec needs at least one topic glob, found null");
        }
        return new AwaitSpec(String.join(",", topics), null, 0);
    }

    /** This spec with the given payload match (null for any payload). */
    public AwaitSpec withMatch(Map<String, Object> match) {
        return new AwaitSpec(topics, match, timeoutMs);
    }

    /** This spec with the given timeout (0 for the scenario's step timeout). */
    public AwaitSpec withTimeoutMs(long timeoutMs) {
        return new AwaitSpec(topics, match, timeoutMs);
    }
}
