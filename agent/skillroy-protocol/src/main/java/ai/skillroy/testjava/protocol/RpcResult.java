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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code result} member of every successful JSON-RPC response. Besides the primitive's value it carries the
 * events the call published and the bus sequence numbers around it, so that a caller can wait "since
 * {@code seqBefore}" and never miss a consequence of its own call.
 *
 * <p>Wire shape: {@code {"value":…,"events":[…],"seq":{"before":…,"after":…},"durationMs":…,"thread":…}}, plus
 * {@code "warnings":[…]} only when the call raised a warning (phase8-contracts.md 7.1, amendment A83): the envelope's
 * warning channel ({@link CallWarning}), never a key of the primitive's value. A result without warnings is written
 * exactly as before Phase 8, and {@link #fromJson} reads the member leniently (absent is empty), so that neither side
 * of a 0.1.0 pairing fails on it.
 *
 * <p>Immutable ({@code value} is deep-copied as described for {@link Event}); safe to share between threads.
 */
public final class RpcResult {

    private static final String TYPE = "RpcResult";

    private final Object value;
    private final List<Event> events;
    private final long seqBefore;
    private final long seqAfter;
    private final long durationMs;
    private final String thread;
    private final List<CallWarning> warnings;

    /**
     * @param value the primitive's JSON-able result, may be null
     * @param events events published on the calling thread during the call; null means none
     * @param seqBefore bus sequence number immediately before the primitive ran
     * @param seqAfter bus sequence number when the result was built
     * @param thread name of the thread that ran the primitive, may be null
     * @throws JsonException if {@code value} holds NaN or Infinity or nests deeper than 184 (a cycle, for instance)
     */
    public RpcResult(Object value, List<Event> events, long seqBefore, long seqAfter, long durationMs, String thread) {
        this(value, events, seqBefore, seqAfter, durationMs, thread, null);
    }

    /**
     * The result with the call's warnings (phase8-contracts.md 7.1).
     *
     * @param warnings the call's warnings in the order raised; null means none (at most
     *     {@link CallWarning#MAX_PER_RESULT} plus one {@link CallWarning#TOO_MANY_WARNINGS} are kept, the rest folded
     *     into that one)
     * @throws JsonException as the six-argument constructor
     */
    public RpcResult(Object value, List<Event> events, long seqBefore, long seqAfter, long durationMs, String thread,
                     List<CallWarning> warnings) {
        this.value = Coerce.freeze(value);
        List<Event> copy = new ArrayList<Event>();
        if (events != null) {
            for (Event event : events) {
                copy.add(Objects.requireNonNull(event, "events must not contain null"));
            }
        }
        this.events = Collections.unmodifiableList(copy);
        this.seqBefore = seqBefore;
        this.seqAfter = seqAfter;
        this.durationMs = durationMs;
        this.thread = thread;
        this.warnings = CallWarning.fold(warnings);
    }

    /** Unmodifiable when it is a map or list; may be null. */
    public Object value() {
        return value;
    }

    /** Unmodifiable, never null. */
    public List<Event> events() {
        return events;
    }

    public long seqBefore() {
        return seqBefore;
    }

    public long seqAfter() {
        return seqAfter;
    }

    public long durationMs() {
        return durationMs;
    }

    public String thread() {
        return thread;
    }

    /** The call's warnings in the order raised (phase8-contracts.md 7.1); unmodifiable, never null, usually empty. */
    public List<CallWarning> warnings() {
        return warnings;
    }

    /**
     * The wire shape described on the class: the six keys always present, {@code warnings} only when non-empty. A new
     * map on each call.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("value", value);
        List<Object> eventsJson = new ArrayList<Object>(events.size());
        for (Event event : events) {
            eventsJson.add(event.toJson());
        }
        json.put("events", eventsJson);
        Map<String, Object> seq = new LinkedHashMap<String, Object>();
        seq.put("before", Long.valueOf(seqBefore));
        seq.put("after", Long.valueOf(seqAfter));
        json.put("seq", seq);
        json.put("durationMs", Long.valueOf(durationMs));
        json.put("thread", thread);
        if (!warnings.isEmpty()) {
            List<Object> warningsJson = new ArrayList<Object>(warnings.size());
            for (CallWarning warning : warnings) {
                warningsJson.add(warning.toJson());
            }
            json.put("warnings", warningsJson);
        }
        return json;
    }

    /**
     * Reads the wire shape. {@code seq.before} and {@code seq.after} are required; a missing {@code value} is null,
     * missing {@code events} is empty, missing {@code durationMs} is 0, missing {@code warnings} is empty; the
     * {@code warnings} member is read leniently ({@link CallWarning#readAll}: a malformed element is
     * {@code INVALID_WARNING}, more than sixteen end in one {@code TOO_MANY_WARNINGS}, never an exception).
     *
     * @throws JsonException on a missing required field or a field of the wrong type
     */
    public static RpcResult fromJson(Map<String, Object> json) {
        Wire.requireObject(json, TYPE);
        List<Object> rawEvents = Wire.optList(json, "events", TYPE);
        List<Event> events = new ArrayList<Event>();
        if (rawEvents != null) {
            for (Object raw : rawEvents) {
                if (!(raw instanceof Map)) {
                    throw Wire.mismatch("events[]", TYPE, "an object", raw);
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> eventJson = (Map<String, Object>) raw;
                events.add(Event.fromJson(eventJson));
            }
        }
        Map<String, Object> seq = Wire.optObject(json, "seq", TYPE);
        if (seq == null) {
            throw new JsonException(TYPE + ": missing required field \"seq\"");
        }
        return new RpcResult(
                json.get("value"),
                events,
                Wire.requireLong(seq, "before", TYPE + ".seq"),
                Wire.requireLong(seq, "after", TYPE + ".seq"),
                Wire.optLong(json, "durationMs", 0L, TYPE),
                Wire.optString(json, "thread", TYPE),
                CallWarning.readAll(json.get("warnings")));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RpcResult)) {
            return false;
        }
        RpcResult other = (RpcResult) o;
        return Objects.equals(value, other.value) && events.equals(other.events) && seqBefore == other.seqBefore
                && seqAfter == other.seqAfter && durationMs == other.durationMs
                && Objects.equals(thread, other.thread) && warnings.equals(other.warnings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, events, Long.valueOf(seqBefore), Long.valueOf(seqAfter), Long.valueOf(durationMs),
                thread, warnings);
    }

    @Override
    public String toString() {
        return TYPE + Coerce.safeWrite(toJson());
    }
}
