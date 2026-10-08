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

import ai.skillroy.testjava.controller.AgentLostException;

import java.util.Objects;

/**
 * Where a stepwise bind ({@link Binder#bindStepwise}) stands (phase5-contracts.md 8.2 step 2, 8.3): {@link Done},
 * with the outcome a one-shot bind returns, or {@link Parked} at a slot whose resolver deferred its model call into a
 * batch. The {@code re-resolve} job binds every scenario until it parks or finishes, submits the parked requests of the
 * round as one batch, and resumes each parked bind with its answer; a bind may park again at a later slot.
 */
public sealed interface BindStep permits BindStep.Done, BindStep.Parked {

    /**
     * The bind finished.
     *
     * @param outcome what {@link Binder#bind} would have returned
     */
    record Done(BindOutcome outcome) implements BindStep {

        /** @throws NullPointerException if the outcome is null */
        public Done {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /**
     * The bind stopped at a query slot: its ladder reached a {@code BatchableResolver}, which prepared the request
     * the job submits. The bind holds its engine (the contributors and the gateways they opened; its own data
     * channels are closed while it is parked and opened again on resumption) until it is resumed to the end, fails, or
     * is closed: a caller that will not resume it (the job's budget refused the round, the batch timed out) must
     * {@link #close()} it. The time a bind spends parked is not counted against its budget.
     *
     * <p>Not thread-safe: resumed or closed once, by one thread.
     */
    final class Parked implements BindStep, AutoCloseable {

        /** What resumes or abandons the bind; implemented by the binder. */
        interface Continuation {
            BindStep resume(Object answer) throws BindException, AgentLostException, InterruptedException;

            void abandon();
        }

        private final String scenarioId;
        private final String slot;
        private final String kind;
        private final String resolverId;
        private final Object request;
        private Continuation continuation;

        Parked(String scenarioId, String slot, String kind, String resolverId, Object request,
               Continuation continuation) {
            this.scenarioId = scenarioId;
            this.slot = slot;
            this.kind = kind;
            this.resolverId = resolverId;
            this.request = request;
            this.continuation = continuation;
        }

        /** The scenario being bound. */
        public String scenarioId() {
            return scenarioId;
        }

        /** The parked slot. */
        public String slot() {
            return slot;
        }

        /** The slot's query kind. */
        public String kind() {
            return kind;
        }

        /** The id of the resolver that prepared the request ({@code inference.sql}). */
        public String resolverId() {
            return resolverId;
        }

        /** The request the resolver prepared: opaque here, the job knows its type; never null. */
        public Object request() {
            return request;
        }

        /** True until the bind was resumed or closed. */
        public boolean isOpen() {
            return continuation != null;
        }

        /**
         * Hands the batch's answer to the resolver ({@code BatchableResolver.complete}) and goes on: the verifiers, the
         * next resolver if no candidate passes, the rest of the graph, until the next slot parks or the bind ends.
         *
         * @param answer what the job collected for {@link #request()}
         * @return the next step: parked again, or done
         * @throws BindException as a one-shot bind's failures (the bind is then over and its engine closed)
         * @throws AgentLostException if the agent is gone (the engine is closed)
         * @throws InterruptedException if the thread is interrupted (the engine is closed)
         * @throws IllegalStateException if this step was already resumed or closed
         */
        public BindStep resume(Object answer) throws BindException, AgentLostException, InterruptedException {
            Continuation next = continuation;
            if (next == null) {
                throw new IllegalStateException("the bind of " + scenarioId + " parked at slot " + slot
                        + " was already resumed or closed");
            }
            continuation = null;
            return next.resume(answer);
        }

        /** Abandons the bind: releases its engine. Idempotent; never throws. */
        @Override
        public void close() {
            Continuation next = continuation;
            continuation = null;
            if (next != null) {
                next.abandon();
            }
        }

        /** The scenario, the slot and the resolver; never the request. */
        @Override
        public String toString() {
            return "Parked[" + scenarioId + ", slot=" + slot + ", kind=" + kind + ", resolver=" + resolverId
                    + (isOpen() ? "" : ", closed") + "]";
        }
    }
}
