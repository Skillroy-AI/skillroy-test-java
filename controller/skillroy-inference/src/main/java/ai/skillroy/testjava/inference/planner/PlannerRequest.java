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

import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * What {@code skillroy author} asks the planner for (phase5-contracts.md 10.2).
 *
 * <p>Immutable; safe to share between threads if the pack and the clock are.
 *
 * @param brief the scenario's brief, one paragraph
 * @param scenarioId the new scenario's id, {@code [a-z0-9-]+}; refused when {@code <outDir>/<id>.yaml} exists
 * @param outDir the scenarios directory the files are written into
 * @param pack the running pack
 * @param primitives the target's primitive catalogue (for the scenario schema's {@code call} verb and
 *     {@code requires}); empty when not known
 * @param mcpConfig the MCP configuration file the CLI wrote (a private, owner-only file naming its own launcher with
 *     {@code mcp --mutating} and the connection options); the planner passes it to {@code claude} and does not delete it
 * @param model the model id ({@code claude-opus-5-5}); null means the settings' default Opus id
 * @param maxTurns {@code --max-turns}, at least 1 (40)
 * @param maxRepairs the most repair rounds through {@code --resume}, at least 0 (2)
 * @param budgetUsd the planner's budget (2.00): the first invocation's {@code --max-budget-usd}, what is left of it a
 *     repair's, and the bound the reported cost is checked against afterwards
 * @param run whether the CLI will run the scenario once after it is written ({@code --run}); recorded in the
 *     authoring record's checks
 * @param claudeCommand the {@code claude} executable (the CLI resolves {@code --claude-command} or the
 *     {@code PATH}); null or not executable is a usage error
 * @param deadline the most the planner may take over all its rounds ({@code timeouts.plannerMs}, 15 min); null means
 *     15 minutes
 * @param clock the authoring date's clock; null means the system UTC clock
 * @param secrets the secret values the planner's log and envelope file are masked by (the CLI passes its credentials'
 *     mask: a model's text that repeats a credential reaches neither); null knows none, leaving the key rule alone
 * @param workDirectory the planner's private working directory, made by the caller before it wrote the MCP
 *     configuration (whose server writes its output under it, {@code --out <work>/mcp}; phase6 security fix S60), and
 *     deleted by the caller; null means the planner makes one ({@link Planner#workDirectory()}) and deletes it
 */
public record PlannerRequest(String brief, String scenarioId, Path outDir, DomainPack pack,
                             List<PrimitiveDescriptor> primitives, Path mcpConfig, String model, int maxTurns,
                             int maxRepairs, BigDecimal budgetUsd, boolean run, Path claudeCommand, Duration deadline,
                             Clock clock, SecretMask secrets, Path workDirectory) {

    /** The default turn limit. */
    public static final int DEFAULT_MAX_TURNS = 40;
    /** The default repair limit. */
    public static final int DEFAULT_MAX_REPAIRS = 2;
    /** The default budget. */
    public static final BigDecimal DEFAULT_BUDGET_USD = new BigDecimal("2.00");
    /** The default deadline. */
    public static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(15);

    /** Copies the catalogue; fills the model, the budget, the deadline, the clock and the secrets. */
    public PlannerRequest {
        primitives = primitives == null ? List.of() : List.copyOf(primitives);
        model = model == null || model.isBlank() ? InferenceSettings.DEFAULT_MODELS.get(InferenceSettings.MODEL_OPUS)
                : model.strip();
        budgetUsd = budgetUsd == null ? DEFAULT_BUDGET_USD : budgetUsd;
        deadline = deadline == null ? DEFAULT_DEADLINE : deadline;
        clock = clock == null ? Clock.systemUTC() : clock;
        secrets = secrets == null ? SecretMask.NONE : secrets;
    }

    /** A request whose planner makes its own working directory (the shape before phase6 security fix S60). */
    public PlannerRequest(String brief, String scenarioId, Path outDir, DomainPack pack,
                          List<PrimitiveDescriptor> primitives, Path mcpConfig, String model, int maxTurns,
                          int maxRepairs, BigDecimal budgetUsd, boolean run, Path claudeCommand, Duration deadline,
                          Clock clock, SecretMask secrets) {
        this(brief, scenarioId, outDir, pack, primitives, mcpConfig, model, maxTurns, maxRepairs, budgetUsd, run,
                claudeCommand, deadline, clock, secrets, null);
    }

    /** A request whose log and envelope file know no secret value (the key rule alone). */
    public PlannerRequest(String brief, String scenarioId, Path outDir, DomainPack pack,
                          List<PrimitiveDescriptor> primitives, Path mcpConfig, String model, int maxTurns,
                          int maxRepairs, BigDecimal budgetUsd, boolean run, Path claudeCommand, Duration deadline,
                          Clock clock) {
        this(brief, scenarioId, outDir, pack, primitives, mcpConfig, model, maxTurns, maxRepairs, budgetUsd, run,
                claudeCommand, deadline, clock, null, null);
    }

    /** A request with the contract's defaults for the turns, repairs, budget and deadline. */
    public static PlannerRequest of(String brief, String scenarioId, Path outDir, DomainPack pack,
                                    List<PrimitiveDescriptor> primitives, Path mcpConfig, Path claudeCommand) {
        return new PlannerRequest(brief, scenarioId, outDir, pack, primitives, mcpConfig, null, DEFAULT_MAX_TURNS,
                DEFAULT_MAX_REPAIRS, null, false, claudeCommand, null, null, null, null);
    }
}
