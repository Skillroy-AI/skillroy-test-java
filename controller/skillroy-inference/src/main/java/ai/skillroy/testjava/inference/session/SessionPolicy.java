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

package ai.skillroy.testjava.inference.session;

/**
 * What the session service lets a model do (phase5-contracts.md 12.4, decision D87): which tools it is offered and
 * its bounds. The reads ({@code skillroy_snapshot}, {@code skillroy_facts}, {@code skillroy_events},
 * {@code skillroy_catalogue}), {@code skillroy_resolve}, {@code skillroy_check}, {@code skillroy_observe},
 * {@code skillroy_note}, {@code done} and {@code ask_person} are always offered; the pack's verbs with
 * {@code allowMutatingVerbs}; the base {@code swing_*} and {@code web_*} mutating primitives with
 * {@code allowBasePrimitives}; neither when {@code readOnly} (a read-only policy offers no action that changes the
 * target, whatever the other flags say). Never a destructive primitive, never {@code data_query}, never
 * {@code evaluate}.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param readOnly true offers no verb and no mutating primitive
 * @param allowMutatingVerbs the pack's verbs (every verb counts as mutating)
 * @param allowBasePrimitives the base {@code swing_*} and {@code web_*} mutating primitives (off by default: the verbs
 *     are the vocabulary a scenario exports)
 * @param maxActionsPerTurn the most actions one turn may take (10)
 * @param maxHistoryTurns how many earlier turns a request carries in full; older ones are summarised (20)
 * @param snapshotMaxChars the most characters a read's result may hold (20000)
 */
public record SessionPolicy(boolean readOnly, boolean allowMutatingVerbs, boolean allowBasePrimitives,
                            int maxActionsPerTurn, int maxHistoryTurns, int snapshotMaxChars) {

    /** The default action limit of a turn. */
    public static final int DEFAULT_MAX_ACTIONS = 10;
    /** The default number of earlier turns kept in full. */
    public static final int DEFAULT_MAX_HISTORY = 20;
    /** The default bound of a read's result. */
    public static final int DEFAULT_SNAPSHOT_MAX_CHARS = 20_000;
    /**
     * The least number of a tool set's reads one turn may take (phase7-contracts.md 9.1, 10; Phase 7 security fix
     * S138): {@link #maxReadsPerTurn()} is this or twice the action limit, whichever is larger.
     */
    public static final int DEFAULT_MAX_READS = 20;

    /**
     * Checks the bounds.
     *
     * @throws IllegalArgumentException if the action limit or the history is below 1, or the snapshot bound below 100
     */
    public SessionPolicy {
        if (maxActionsPerTurn < 1) {
            throw new IllegalArgumentException("maxActionsPerTurn is at least 1");
        }
        if (maxHistoryTurns < 1) {
            throw new IllegalArgumentException("maxHistoryTurns is at least 1");
        }
        if (snapshotMaxChars < 100) {
            throw new IllegalArgumentException("snapshotMaxChars is at least 100");
        }
    }

    /**
     * The reads only: no verb, no primitive that changes the target; the default bounds. (The contract's
     * {@code SessionPolicy.readOnly()}: a static method cannot share that name with the record's accessor.)
     */
    public static SessionPolicy readOnlyPolicy() {
        return new SessionPolicy(true, false, false, DEFAULT_MAX_ACTIONS, DEFAULT_MAX_HISTORY,
                DEFAULT_SNAPSHOT_MAX_CHARS);
    }

    /** The reads and the pack's verbs, no base primitive; the default bounds. */
    public static SessionPolicy acting() {
        return new SessionPolicy(false, true, false, DEFAULT_MAX_ACTIONS, DEFAULT_MAX_HISTORY,
                DEFAULT_SNAPSHOT_MAX_CHARS);
    }

    /** True when the pack's verbs are offered. */
    public boolean offersVerbs() {
        return !readOnly && allowMutatingVerbs;
    }

    /**
     * The most reads of a tool set (a call whose outcome ran no step: a capture, a {@code wait}, a {@code zoom}, a
     * visual question, a refusal) one turn may take, which do not count against {@link #maxActionsPerTurn}
     * (phase7-contracts.md 9.1, 10; Phase 7 security fix S138): {@link #DEFAULT_MAX_READS}, or twice
     * {@code maxActionsPerTurn} when that is larger. At the bound the remaining calls are not run and the turn ends
     * {@code LIMIT}, as at the action limit.
     */
    public int maxReadsPerTurn() {
        return Math.max(DEFAULT_MAX_READS, 2 * maxActionsPerTurn);
    }

    /** True when the base {@code swing_*} and {@code web_*} mutating primitives are offered. */
    public boolean offersBasePrimitives() {
        return !readOnly && allowBasePrimitives;
    }

    /** A copy with another action limit. */
    public SessionPolicy withMaxActionsPerTurn(int max) {
        return new SessionPolicy(readOnly, allowMutatingVerbs, allowBasePrimitives, max, maxHistoryTurns,
                snapshotMaxChars);
    }

    /** A copy that keeps another number of earlier turns in full. */
    public SessionPolicy withMaxHistoryTurns(int max) {
        return new SessionPolicy(readOnly, allowMutatingVerbs, allowBasePrimitives, maxActionsPerTurn, max,
                snapshotMaxChars);
    }

    /** A copy with the base primitives offered or not. */
    public SessionPolicy withBasePrimitives(boolean on) {
        return new SessionPolicy(readOnly, allowMutatingVerbs, on, maxActionsPerTurn, maxHistoryTurns,
                snapshotMaxChars);
    }
}
