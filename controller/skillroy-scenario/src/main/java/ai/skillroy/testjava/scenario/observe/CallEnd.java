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


package ai.skillroy.testjava.scenario.observe;

import java.util.List;

/**
 * A plan call ended: the facts of its reply, never its value or the events' payloads.
 *
 * @param ok whether the call itself returned a result (its sync may still have failed)
 * @param path the path that served it ({@code agent} or a web path's id such as {@code cdp})
 * @param errorCode the error code's name ({@code TIMEOUT}, {@code INPUT_REJECTED}, …) of a call that failed, or null
 * @param seqBefore the agent's sequence number before the call, -1 when unknown
 * @param seqAfter the agent's sequence number after the call, -1 when unknown
 * @param rttMs the controller's round trip of the call itself (without its sync), -1 when unknown: what the agent's
 *     span is centred in
 * @param agentDurationMs the agent's own time for the call ({@code RpcResult.durationMs}), or null when the agent
 *     did not answer
 * @param agentThread the thread the agent ran it on, or null
 * @param events the events the reply carried: topic, sequence number, source and time only
 */
public record CallEnd(boolean ok, String path, String errorCode, long seqBefore, long seqAfter, long rttMs,
                      Long agentDurationMs, String agentThread, List<EventFact> events) {

    /** Copies the events. */
    public CallEnd {
        events = events == null ? List.of() : List.copyOf(events);
    }

    /**
     * One event of a reply, without its payload.
     *
     * @param topic the topic
     * @param seq the sequence number
     * @param source the source
     * @param ts the agent's time stamp, epoch milliseconds
     */
    public record EventFact(String topic, long seq, String source, long ts) {
    }
}
