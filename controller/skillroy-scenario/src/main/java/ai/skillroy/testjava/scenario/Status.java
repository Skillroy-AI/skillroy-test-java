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
 * The verdict of a scenario run (and of each step). Five values rather than pass/fail because "a check could not be
 * made on this target", "the run could not be carried out" and "the plan was not executed by design" must look
 * different from both a pass and a failure of the target application in CI.
 */
public enum Status {
    /** Every expectation held. */
    PASS,
    /**
     * Every evaluated expectation held, but at least one guarded expectation was skipped because its capability was
     * not OK on this target, at least one UI call ran at a lower fidelity than requested (a COMPONENT click that
     * fell back to ACTION), or at least one guarded oracle cross-check was skipped (phase3-contracts.md 6.2). Neither
     * green nor red; see {@code RunOptions.degradedAs}.
     */
    DEGRADED,
    /** The application did not behave as the scenario expects (ASSERTION, SYNC_TIMEOUT). */
    FAIL,
    /**
     * The scenario could not be run properly (PRECONDITION_FAILED, RESOLUTION_FAILED, ENVIRONMENT, AGENT_LOST,
     * TARGET_RESTARTED).
     */
    ERROR,
    /**
     * The plan was not executed (phase7-contracts.md 8.6, decision D328): its {@code inference} is {@code run} (a step
     * needs a model while steps run) and the mode is {@code replay}. Decided right after {@code run-start}, before the
     * agent is contacted; neither a failure nor a pass. A session never produces it.
     */
    SKIPPED
}
