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

import ai.skillroy.testjava.scenario.resolve.CrossCheckResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What became of one oracle's cross-check in a bind (phase3-contracts.md 6.1, 6.2, 8.2): it ran, and {@link #result()}
 * says whether the application agreed, or it was skipped because the capability that guards it was not OK on the
 * target. A bind that completes holds agreements and skips only, since a disagreement fails it as ORACLE_SUSPECT.
 * Kept by the bind's {@link Bindings#crossChecks()}, shown at approval ({@code Approver.ApprovalRequest}) and printed
 * by the CLI's {@code resolve}.
 *
 * <p>Immutable ({@code expected} is a deep unmodifiable copy); safe to share between threads.
 *
 * @param oracle the oracle's name
 * @param fn the oracle function
 * @param spec the plan's cross-check of the oracle
 * @param expected the oracle's output, encoded
 * @param result what the check answered; null when it was skipped
 * @param capabilityState when skipped: the guarding capability's state ({@code UNAVAILABLE} for a capability the
 *     target does not declare); null when the check ran
 * @param capabilityReason when skipped: the capability's reason, or null when it gives none; null when the check ran
 */
public record CrossCheckOutcome(String oracle, String fn, Plan.CrossCheckSpec spec, Object expected,
                                CrossCheckResult result, String capabilityState, String capabilityReason) {

    /**
     * Freezes {@code expected}.
     *
     * @throws IllegalArgumentException when the outcome is neither a result nor a skip with a state
     */
    public CrossCheckOutcome {
        Objects.requireNonNull(oracle, "oracle");
        Objects.requireNonNull(fn, "fn");
        Objects.requireNonNull(spec, "spec");
        expected = Plan.freeze(expected);
        if ((result == null) == (capabilityState == null)) {
            throw new IllegalArgumentException("a cross-check outcome is either a result or a skip with the"
                    + " capability's state");
        }
    }

    /** True when the check was skipped under its guard. */
    public boolean skipped() {
        return result == null;
    }

    /** True when the check ran and the application agreed. */
    public boolean agreed() {
        return result != null && result.agreed();
    }

    /**
     * The line {@code BindOutcome.skipped} and {@code RunResult.skippedExpectations} carry for a skipped check:
     * {@code "oracle waiver: cross-check C skipped: capability K is UNAVAILABLE (reason)"}; null when it ran.
     */
    public String skippedLine() {
        if (!skipped()) {
            return null;
        }
        return "oracle " + oracle + ": cross-check " + spec.check() + " skipped: capability " + spec.capability()
                + " is " + capabilityState + (capabilityReason == null || capabilityReason.isBlank() ? ""
                : " (" + capabilityReason + ")");
    }

    /**
     * One line for people: {@code "cross-check C: agrees (reason)"}, {@code "cross-check C: disagrees (reason)"},
     * or for a skipped check its {@link #skippedLine()}.
     */
    public String summary() {
        if (skipped()) {
            return skippedLine();
        }
        String reason = result.reason();
        return "cross-check " + spec.check() + ": " + (result.agreed() ? "agrees" : "disagrees")
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")");
    }

    /**
     * The fields of the {@code crosscheck} record of {@code run.jsonl} (phase3-contracts.md 8.2), unmasked:
     * {@code oracle, fn, check, tolerance}, then either {@code skipped: true, capability, state, reason} or
     * {@code agreed, reason, expected, actual, evidence}. A new map on each call.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("oracle", oracle);
        json.put("fn", fn);
        json.put("check", spec.check());
        json.put("tolerance", spec.tolerance());
        if (skipped()) {
            json.put("skipped", true);
            json.put("capability", spec.capability());
            json.put("state", capabilityState);
            json.put("reason", capabilityReason);
        } else {
            json.put("agreed", result.agreed());
            json.put("reason", result.reason());
            json.put("expected", expected);
            json.put("actual", result.actual());
            json.put("evidence", result.evidence());
        }
        return json;
    }

    /** The form of the run's {@code bindings.json} (8.3): {@code {check, agreed}} or {@code {check, skipped}}. */
    Map<String, Object> bindingsJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("check", spec.check());
        if (skipped()) {
            json.put("skipped", true);
        } else {
            json.put("agreed", result.agreed());
        }
        return json;
    }
}
