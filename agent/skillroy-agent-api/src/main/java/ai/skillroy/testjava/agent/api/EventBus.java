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

package ai.skillroy.testjava.agent.api;

import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.TopicFilter;

import java.util.List;
import java.util.Map;

/**
 * The agent's ordered event log: a bounded ring buffer with a strictly increasing sequence number, read by SSE,
 * {@code evt.query} and {@code sync.await}. Lets scenarios wait on what the application did instead of sleeping.
 *
 * <p>Implementations are thread-safe; {@link #publish} may be called from any thread, including the EDT and
 * application threads, and never blocks on subscribers.
 */
public interface EventBus {

    /**
     * Publishes with the calling thread's current correlation id (set by the dispatcher), or none.
     *
     * @return the new event's sequence number
     */
    long publish(String topic, String source, Map<String, Object> payload);

    /** Sequence number of the newest event, or 0 if none has been published. */
    long lastSeq();

    /** Up to {@code limit} events with seq > seqExclusive that match the filter, oldest first. */
    List<Event> since(long seqExclusive, TopicFilter filter, int limit);

    /**
     * First event with seq > seqExclusive matching filter (and, if non-null, every payloadMatch entry by
     * String.valueOf equality); null on timeout. Blocks the calling thread; never call it on the EDT.
     */
    Event await(long seqExclusive, TopicFilter filter, Map<String, Object> payloadMatch, long timeoutMs)
            throws InterruptedException;

    /** Delivers every future matching event to the sink until the returned handle is closed. */
    AutoCloseable subscribe(TopicFilter filter, EventSink sink);

    /** Oldest seq still in the ring. */
    long oldestSeq();
}
