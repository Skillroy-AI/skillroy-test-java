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

/**
 * Why a run did not pass. Classification is deterministic and happens before anyone (or any model) reads the failure.
 * The status each kind produces is given in brackets.
 */
public enum FailureKind {
    /** A {@code requires:} capability is missing or UNAVAILABLE, or a launch property differs [ERROR]. */
    PRECONDITION_FAILED,
    /** Recorded bindings no longer fit this environment (later phases) [ERROR]. */
    REBIND_NEEDED,
    /** A {@code $slot} reference or a slot cannot be resolved [ERROR]. */
    RESOLUTION_FAILED,
    /**
     * A committed oracle and its cross-check (the application's own computation) disagree at bind time, in any mode
     * (reason {@code DISAGREED}, phase3-contracts.md 6.1, 8.1): a verdict on the test, not on the application; no
     * step has run [ERROR].
     */
    ORACLE_SUSPECT,
    /**
     * An ack, ready or settle wait expired, or a primitive timed out; carries edtState, dialogs and app state
     * [FAIL].
     */
    SYNC_TIMEOUT,
    /**
     * An expectation did not hold (a {@code db} check's row count, row values or aggregates included), a step's input
     * was rejected or its target not found or ambiguous, a modal dialog the scenario did not expect blocked a step's
     * input (MODAL_OPEN), a base UI verb's call did not fire, or an unexpected prompt was failed by the prompt policy
     * [FAIL].
     */
    ASSERTION,
    /** The agent was lost and the target restarted (new pid for the same main command) [ERROR]. */
    TARGET_RESTARTED,
    /** The connection to the agent dropped [ERROR]. */
    AGENT_LOST,
    /** Handshake, pre-flight, a pack failure, or a JSON-RPC error that is not a scenario failure [ERROR]. */
    ENVIRONMENT
}
