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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One entry of the agent's event bus, as stored in the ring buffer, streamed over SSE and returned with RPC results.
 * Scenarios synchronise on events instead of sleeping, so the sequence number is the ordering and resume key.
 *
 * <p>Immutable: the payload is deep-copied into JSON normal form (String-keyed maps, lists, Long for integral boxed
 * numbers, Double for Float, the {@code toString()} of any object that is not JSON-able) and is unmodifiable, so an
 * event equals its own JSON round trip and can always be written. Safe to share between threads.
 */
public final class Event {

    private static final String TYPE = "Event";

    private final long seq;
    private final long ts;
    private final String topic;
    private final String source;
    private final String correlationId;
    private final String traceparent;
    private final Map<String, Object> payload;

    /**
     * @param seq bus sequence number, strictly increasing per agent
     * @param ts wall-clock time in epoch milliseconds
     * @param topic dotted topic, not null
     * @param source publishing component (module id, "core", ...), may be null
     * @param correlationId the RPC call that caused the event, may be null
     * @param traceparent W3C trace context of that call, may be null
     * @param payload topic-specific data; null means empty
     * @throws JsonException if the payload holds NaN or Infinity or nests deeper than 184 (a cycle, for instance);
     *     publishers on application threads must not let this escape into the host
     */
    public Event(long seq, long ts, String topic, String source, String correlationId, String traceparent,
                 Map<String, Object> payload) {
        this.seq = seq;
        this.ts = ts;
        this.topic = Objects.requireNonNull(topic, "topic");
        this.source = source;
        this.correlationId = correlationId;
        this.traceparent = traceparent;
        this.payload = payload == null ? Collections.<String, Object>emptyMap() : Coerce.freezeMap(payload);
    }

    public long seq() {
        return seq;
    }

    public long ts() {
        return ts;
    }

    public String topic() {
        return topic;
    }

    public String source() {
        return source;
    }

    public String correlationId() {
        return correlationId;
    }

    public String traceparent() {
        return traceparent;
    }

    /** Unmodifiable, never null. */
    public Map<String, Object> payload() {
        return payload;
    }

    /**
     * {@code {"seq","ts","topic","source","correlationId","traceparent","payload"}}, all keys always present (null
     * values written as null). A new map on each call.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("seq", Long.valueOf(seq));
        json.put("ts", Long.valueOf(ts));
        json.put("topic", topic);
        json.put("source", source);
        json.put("correlationId", correlationId);
        json.put("traceparent", traceparent);
        json.put("payload", payload);
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}. {@code seq}, {@code ts} and {@code topic} are required.
     *
     * @throws JsonException on a missing required field or a field of the wrong type
     */
    public static Event fromJson(Map<String, Object> json) {
        Wire.requireObject(json, TYPE);
        return new Event(
                Wire.requireLong(json, "seq", TYPE),
                Wire.requireLong(json, "ts", TYPE),
                Wire.requireString(json, "topic", TYPE),
                Wire.optString(json, "source", TYPE),
                Wire.optString(json, "correlationId", TYPE),
                Wire.optString(json, "traceparent", TYPE),
                Wire.optObject(json, "payload", TYPE));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Event)) {
            return false;
        }
        Event other = (Event) o;
        return seq == other.seq && ts == other.ts && topic.equals(other.topic) && Objects.equals(source, other.source)
                && Objects.equals(correlationId, other.correlationId)
                && Objects.equals(traceparent, other.traceparent) && payload.equals(other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Long.valueOf(seq), Long.valueOf(ts), topic, source, correlationId, traceparent, payload);
    }

    @Override
    public String toString() {
        return TYPE + Coerce.safeWrite(toJson());
    }
}
