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

package ai.skillroy.testjava.scenario.web;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;

import java.util.List;
import java.util.Map;

/**
 * One implementation of the web verbs (phase4-contracts.md section 6.1): the agent's, built in, or a controller-side
 * one found by {@code ServiceLoader<WebPath>} (the CDP path of {@code skillroy-cdp}). The executor opens the chosen
 * path once per run, asks it per call whether it serves the primitive, and falls back to the agent path for a call
 * the path reports as unsupported.
 */
public interface WebPath {

    /** {@code "agent"} or {@code "cdp"}. */
    String id();

    /**
     * Prepares the path for a run against this target: the CDP path probes and attaches here (once per run; the
     * attach is kept for the run and closed in {@link #close()}). Returns {@code null} when the path is usable, else
     * the reason it is not (recorded; not an error unless the scenario pinned the path, then {@code PRECONDITION_FAILED}).
     * Throws {@link WebPathOpenException} when the attach itself failed (unreachable, timed out, a protocol error,
     * frozen targets): {@code ENVIRONMENT} under a pin, the agent path with the reason recorded under {@code auto}.
     */
    String open(AgentConnection agent, WebSettings settings, WebRunContext ctx) throws Exception;

    /**
     * Details of a successful {@link #open} for the {@code web.path} record of {@code run.jsonl} (the CDP path:
     * {@code endpoint}, {@code browserVersion}, {@code connectMs}, {@code targets}, {@code unresponsive}); {@code null}
     * when the path has nothing to add (the agent path).
     */
    default Map<String, Object> openInfo() {
        return null;
    }

    boolean serves(String primitive);

    /**
     * The call's result in the shape the agent would give (value, events, seq, durationMs, thread = "controller"),
     * or throws {@link WebPathException} ({@code UNSUPPORTED}) when this call cannot be served, or the agent's own
     * exception type with the agent's error codes for the failures of phase4-contracts.md section 8.1.
     */
    RpcResult call(String primitive, Map<String, Object> args, CallOptions options) throws Exception;

    /** Events the path saw since {@code seq} on the given topics (its own events, which never reach the agent's bus). */
    List<Event> eventsSince(long seq, String topics);

    /**
     * Writes the path's trace (CDP tracing, when the scenario asked for it with {@code web.trace}) to the given file;
     * does nothing when there is no trace. Called by the executor before the failure evidence is complete; it records
     * the trace as saved only when the file is there afterwards and not empty. The executor opens the path with
     * {@link WebSettings#trace()} false when a call of the plan may carry a secret (phase4-contracts.md 6.4).
     */
    default void saveTrace(java.nio.file.Path target) throws Exception {
    }

    /** Idempotent; never throws; closes what the path opened and nothing that belongs to the application. */
    void close();
}
