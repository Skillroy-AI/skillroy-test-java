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

import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.StepResult;
import ai.skillroy.testjava.scenario.resolve.CostLedger;

import java.util.Map;

/**
 * What a run, a session and the MCP server tell an exporter as they go (phase6-contracts.md 10.2, decision D140): the
 * seam OpenTelemetry export plugs into, naming no OpenTelemetry type and no inference type. Carried by
 * {@code RunOptions.observer} and {@code SessionOptions.observer}, so that the executor reads no system property;
 * fed from the places that write the matching {@code run.jsonl} and journal records, so that spans and records cannot
 * disagree. The bundled implementation is {@code skillroy-telemetry}'s, found through {@link RunObserverProvider}.
 *
 * <p>Every value handed over is already masked by the run's secret mask where it is text from a record, and carries
 * identifiers, enumerations, counts, durations and hashes only: never a call's parameters or values, an event's
 * payload, a snapshot, a resolved value, SQL, rows, a prompt or a credential (P2 11.4).
 *
 * <p>Hook points, each beside the record it mirrors: {@code PlanExecutor}'s run ({@code run-start} / {@code run-end}:
 * {@link #scenarioStarted} / {@link #scenarioEnded}), its steps ({@link #stepStarted} / {@link #stepEnded}), its
 * calls and cleanup calls ({@code CallRunner}: {@link #callStarted} / {@link #callEnded}, {@link #await} for every
 * {@code sync} record), the bind listener ({@link #bindEvent}, and the executor's own {@code bind} records), the seven
 * scenario records ({@link #scenarioRecord}), the cost ledger ({@link #modelCall}); {@code ExecutorSession}
 * ({@link #sessionStarted}, {@link #sessionStepStarted} / {@link #sessionStep}, {@link #sessionRecord} for every
 * journal record, {@link #sessionEnded}); {@code SessionService} ({@link #turnStarted} / {@link #sessionTurn}); the
 * MCP server ({@link #toolCallStarted} / {@link #toolCall}).
 *
 * <p>As built (Phase 6, TL): beside the methods the contract named, the start counterparts of the session's spans
 * ({@link #sessionStarted}, {@link #sessionStepStarted}, {@link #turnStarted}, {@link #toolCallStarted}),
 * {@link #sessionRecord}, {@link #sessionEnded} and {@link #traceId}, all default no-ops: a span is opened before its
 * children, so that a session step is a child of its turn and a tool call carries the client's trace into the work it
 * does (decision D161 of the build).
 *
 * <p>An implementation must be safe to call from any thread (the MCP server calls from its transport's threads, one
 * call at a time), must never block beyond its own bounded export, and should never throw: the executor wraps every
 * observer ({@code RunObservers.safe}) so that one that throws is logged once and replaced by {@link #NONE} for the
 * rest of the run. Telemetry never changes a verdict.
 */
public interface RunObserver extends AutoCloseable {

    /** The observer that does nothing: what a run without telemetry has. Every method is a no-op. */
    RunObserver NONE = new RunObserver() {
        @Override
        public void runStarted(RunStart run) {
        }

        @Override
        public void scenarioStarted(ScenarioStart scenario) {
        }

        @Override
        public void scenarioEnded(RunResult result) {
        }

        @Override
        public void bindEvent(String type, Map<String, Object> fields) {
        }

        @Override
        public void scenarioRecord(String type, Map<String, Object> fields) {
        }

        @Override
        public void stepStarted(StepStart step) {
        }

        @Override
        public void stepEnded(StepResult result) {
        }

        @Override
        public void callStarted(CallStart call) {
        }

        @Override
        public void callEnded(CallEnd call) {
        }

        @Override
        public void await(AwaitRecord sync) {
        }

        @Override
        public void modelCall(CostLedger.Entry entry) {
        }

        @Override
        public void sessionStep(StepOutcome outcome) {
        }

        @Override
        public void sessionTurn(TurnRecord turn) {
        }

        @Override
        public void toolCall(ToolCallRecord record) {
        }

        @Override
        public String currentTraceparent() {
            return null;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return "RunObserver.NONE";
        }
    };

    /** One per CLI invocation or JUnit stream: the root span. */
    void runStarted(RunStart run);

    /** A scenario's run starts ({@code run-start}). */
    void scenarioStarted(ScenarioStart scenario);

    /** A scenario's run ended ({@code run-end}); the result is masked. */
    void scenarioEnded(RunResult result);

    /**
     * A record of the bind (what {@code BindListener.event} receives, P2 11.3, and the executor's own {@code bind}
     * records), as {@code run.jsonl} holds it: masked, in JSON form. The {@code bind} span is opened by the first and
     * ended by the executor's {@code bind} record.
     */
    void bindEvent(String type, Map<String, Object> fields);

    /**
     * A {@code run.jsonl} record of the scenario that is not a bind record, as written (masked, JSON form):
     * {@code requires}, {@code launchProperties}, {@code preflight}, {@code hooks}, {@code capabilities},
     * {@code recover}, {@code cleanup}.
     */
    void scenarioRecord(String type, Map<String, Object> fields);

    /** A step (or the {@code verify} block) of a run starts. */
    void stepStarted(StepStart step);

    /** That step ended; its message is masked. */
    void stepEnded(StepResult result);

    /** A plan call (a step's or a cleanup call) starts. */
    void callStarted(CallStart call);

    /** That call ended, its sync and its prompt check included. */
    void callEnded(CallEnd call);

    /** A {@code sync} record of the open call: the wait it did. */
    void await(AwaitRecord sync);

    /** A model call of the run, after the fact, from the ledger ({@code writeLedger}). */
    void modelCall(CostLedger.Entry entry);

    /** A session step ended (the interactive profile); its message and detail are masked. */
    void sessionStep(StepOutcome outcome);

    /** A turn of the session service ended. */
    void sessionTurn(TurnRecord turn);

    /** One of the MCP server's {@code tools/call} ended. */
    void toolCall(ToolCallRecord record);

    /**
     * The W3C {@code traceparent} of the open call span (the innermost open span when no call is open), or null: what
     * {@code CallRunner} sends as {@code _meta.traceparent}.
     */
    String currentTraceparent();

    /** Exports what is finished; bounded by the exporter's timeout. Never throws. */
    void flush();

    /** Ends the open spans and the root span, flushes and releases the exporter; bounded; idempotent; never throws. */
    @Override
    void close();

    // -------------------------------------------------------------------------- as built (Phase 6, TL): see above

    /** A session opens (its span: id, pack, policy). */
    default void sessionStarted(SessionStart session) {
    }

    /** A session step starts; {@link StepStart#origin()} names who acted. */
    default void sessionStepStarted(StepStart step) {
    }

    /** A journal record of the session was written: its type only, never its content (the session span's events). */
    default void sessionRecord(String type) {
    }

    /** The session closed. */
    default void sessionEnded(String reason) {
    }

    /** A turn of the session service starts. */
    default void turnStarted(int index) {
    }

    /**
     * One of the MCP server's {@code tools/call} starts.
     *
     * @param traceparent the client's W3C {@code traceparent} ({@code _meta.traceparent}), validated, or null
     */
    default void toolCallStarted(String tool, String traceparent) {
    }

    /** The trace id (32 lower-case hex) of the invocation's trace, or null when nothing is exported. */
    default String traceId() {
        return null;
    }
}
