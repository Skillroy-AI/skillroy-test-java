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
 * How a session's journal is exported as a scenario (phase5-contracts.md 12.3).
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param scenarioId the exported scenario's id, {@code [a-z0-9-]+}
 * @param includeFailed true exports every step up to and including the first one that did not pass (and none after
 *     it); false (the default) exports the steps that passed and leaves the others out
 * @param approvedBy the approver of the exported bindings: a tier (b) slot is written {@code APPROVED} by this label;
 *     null writes it {@code REQUIRED}
 * @param description the scenario's description for its comment head; null means "exported from session &lt;id&gt; on
 *     &lt;date&gt;"
 */
public record ExportOptions(String scenarioId, boolean includeFailed, String approvedBy, String description) {

    /**
     * @throws IllegalArgumentException if the id is not {@code [a-z0-9-]+} or the approver label is blank
     */
    public ExportOptions {
        if (scenarioId == null || !ScenarioLoader.ID.matcher(scenarioId).matches()) {
            throw new IllegalArgumentException("a scenario id must match [a-z0-9-]+, found "
                    + (scenarioId == null ? "null" : "\"" + scenarioId + "\""));
        }
        if (approvedBy != null && approvedBy.isBlank()) {
            throw new IllegalArgumentException("the approver label must not be blank");
        }
    }

    /** Options exporting the passed steps as {@code scenarioId}, tier (b) slots {@code REQUIRED}. */
    public static ExportOptions of(String scenarioId) {
        return new ExportOptions(scenarioId, false, null, null);
    }

    /** A copy that does or does not include the steps up to the first failure. */
    public ExportOptions withIncludeFailed(boolean on) {
        return new ExportOptions(scenarioId, on, approvedBy, description);
    }

    /** A copy with this approver label (null: tier (b) slots {@code REQUIRED}). */
    public ExportOptions withApprovedBy(String label) {
        return new ExportOptions(scenarioId, includeFailed, label, description);
    }

    /** A copy with this description. */
    public ExportOptions withDescription(String text) {
        return new ExportOptions(scenarioId, includeFailed, approvedBy, text);
    }
}
