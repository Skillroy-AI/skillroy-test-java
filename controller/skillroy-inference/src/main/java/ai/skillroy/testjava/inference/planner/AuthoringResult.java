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

package ai.skillroy.testjava.inference.planner;

import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.resolve.CostLedger;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What the planner wrote (phase5-contracts.md 10.2 items 5-7): the scenario's YAML (with its comment head), its plan
 * (compiled as {@code compile} writes it), its authoring record and its envelope file (the planner's envelopes,
 * masked; D112), the plan itself for the CLI's checks (a {@code resolve --dry-run} bind and, with {@code --run}, one
 * {@code live} run), the session, the turns and repairs, the cost (the ledger entries of provider {@code claude-code},
 * purpose {@code plan}), the {@code claude} version, the masked tail of the planner's error output, and the
 * instruction for the reviewer.
 *
 * <p>Immutable (lists copied); safe to share between threads.
 *
 * @param scenarioId the scenario's id
 * @param yaml {@code <out>/<id>.yaml}
 * @param planFile {@code <out>/plan/<id>.plan.json}
 * @param authoring {@code <out>/<id>.authoring.json}
 * @param envelope {@code <out>/<id>.envelope.json}: the envelopes of the first invocation and of each repair, in
 *     order, through the mask of the planner's log; the reviewer deletes it before committing the scenario or keeps it
 *     as the fixture of the planner's replay
 * @param plan the compiled plan
 * @param yamlText the YAML as written
 * @param model the model id the planner ran with
 * @param sessionId the planner's session id
 * @param turns the planner's turns over all rounds
 * @param repairs how many repair rounds ran
 * @param costUsd the cost the envelopes reported, summed
 * @param overBudget true when that cost passed the budget (the CLI's own {@code --max-budget-usd} should prevent it)
 * @param claudeVersion what {@code claude --version} printed, or null
 * @param ledger the planner's ledger entries, one per invocation
 * @param log the masked tail of the planner's error output, one line each
 */
public record AuthoringResult(String scenarioId, Path yaml, Path planFile, Path authoring, Path envelope, Plan plan,
                              String yamlText, String model, String sessionId, int turns, int repairs,
                              BigDecimal costUsd, boolean overBudget, String claudeVersion,
                              List<CostLedger.Entry> ledger, List<String> log) {

    /** Copies the lists. */
    public AuthoringResult {
        Objects.requireNonNull(scenarioId, "scenarioId");
        ledger = ledger == null ? List.of() : List.copyOf(ledger);
        log = log == null ? List.of() : List.copyOf(log);
        costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
    }

    /** What the reviewer is told (10.2 item 7, D112). */
    public String instruction() {
        return "Review the YAML and the plan, then run skillroy resolve --env <env> " + yaml.getFileName()
                + " to approve its bindings." + (envelope == null ? "" : " " + envelope.getFileName() + " holds the"
                + " planner's envelopes: delete it before committing or keep it as the fixture of the planner's"
                + " replay.");
    }

    /**
     * Records the CLI's checks in the authoring record ({@code checks.resolved}, {@code checks.ran}).
     *
     * @param resolved whether the {@code resolve --dry-run} bind passed; null when it was not made
     * @param ran whether the {@code --run} passed; null without {@code --run}
     * @throws IOException if the record cannot be rewritten
     */
    public void recordChecks(Boolean resolved, Boolean ran) throws IOException {
        Planner.recordChecks(authoring, resolved, ran);
    }
}
