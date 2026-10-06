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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.CredentialsException;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.CrossCheckOutcome;
import ai.skillroy.testjava.scenario.PlanExecutor;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.resolve.BindMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * {@code skillroy resolve} (phase2-contracts.md 12.1): connects, checks {@code requires} and launch properties, runs
 * pre-flight, then binds each scenario in {@code approve} mode and stops: no step runs, no cleanup call is sent and
 * {@code recover} is not called. {@code --dry-run} binds in {@code live} mode and prints the bindings file it would
 * write, writing nothing; a file {@code approve} would refuse to write (a recorded value equals a credential) is
 * refused alike: ENVIRONMENT, exit 1, and nothing of it is printed ({@code --json} has no {@code wouldWrite}).
 * Scenarios that need no bindings are listed as such and skipped.
 *
 * <p>Phase 3 (phase3-contracts.md 9): each oracle's cross-check prints a line ({@code "<id>: cross-check C: agrees
 * (…)"}), and a guarded check that was skipped a {@code degraded:} line; a bind that is merely degraded still counts
 * as resolved. {@code --json} carries for each scenario that needs bindings its {@code crossChecks} (the
 * {@code crosscheck} records the bind emitted, also when it then failed) and its {@code skipped} list (empty when the
 * bind failed).
 *
 * <p>Exit codes: 0 every scenario that needs bindings was resolved (and, unless {@code --dry-run}, written or already
 * identical); 1 one could not be resolved, was not approved, or its oracle is suspect (ORACLE_SUSPECT), or the
 * credentials cannot be loaded; 2 a file could not be loaded or compiled; 3 the agent is unreachable; 64 a usage error
 * (including approve without a terminal and without {@code --yes}).
 */
final class ResolveCommand implements Command {

    private static final String DRY_RUN = "--dry-run";

    private static final CommandSpec SPEC = new CommandSpec("resolve", "resolve and approve the bindings of scenarios",
            "<scenario.yaml|plan.json|dir>...", """
            Binds each scenario that has query slots or oracles against the running target: resolves every slot,
            runs every verifier, computes the oracles and their guards, shows the result and asks whether to write
            <bindings>/<env>/<scenario-id>.bindings.json (--yes approves without asking). Nothing else runs: no
            step, no cleanup call, no recover. With --dry-run the bind is live and the file that would be written
            is printed instead. Scenarios without query slots or oracles need no bindings and are skipped.""",
            Connector.withConnection(BindArgs.ENV_OPTION, BindArgs.BINDINGS_OPTION, BindArgs.CREDENTIALS_OPTION,
                    BindArgs.YES_OPTION, BindArgs.APPROVED_BY_OPTION,
                    OptionSpec.flag(DRY_RUN, "resolve live and print what would be written; write\nnothing"),
                    Packs.OPTION_SPEC,
                    OptionSpec.flag("--json", "print {results:[...]} as JSON when done")),
            1, CommandSpec.UNLIMITED);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        boolean dryRun = args.flag(DRY_RUN);
        boolean json = args.flag("--json");
        List<Path> files = ScenarioFiles.expand(args.positionals());
        BindArgs.Settings settings = BindArgs.parse(args, files, dryRun ? BindMode.LIVE : BindMode.APPROVE);
        Approver approver = BindArgs.approver(settings, cli);
        if (!dryRun && settings.root() == null) {
            throw new UsageException("approve needs a bindings root: give " + BindArgs.BINDINGS);
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (Packs packs = Packs.load(args.values(Packs.OPTION), cli.classLoader())) {
            Thread.currentThread().setContextClassLoader(packs.loader());
            List<String> errors = new ArrayList<>();
            List<RunCommand.Loaded> loaded = new ArrayList<>();
            for (Path file : files) {
                try {
                    loaded.add(RunCommand.load(file, packs, cli.err()));
                } catch (ScenarioException e) {
                    errors.add(ScenarioFiles.describe(file, e));
                }
            }
            if (!errors.isEmpty()) {
                errors.forEach(cli.err()::println);
                throw new CliException(Exit.SCENARIO, errors.size() + " of " + files.size() + " scenario file"
                        + (files.size() == 1 ? "" : "s") + " could not be loaded or compiled; nothing was resolved");
            }
            Credentials credentials;
            try {
                credentials = BindArgs.credentials(settings);
            } catch (CredentialsException e) {
                throw new CliException(Exit.FAILED, "the credentials cannot be loaded: " + e.getMessage(), e);
            }
            SecretMask mask = credentials.mask();
            if (approver == null) {
                approver = BindArgs.terminalApprover(cli, mask);
            }
            Target target = Connector.connect(args, cli);
            UnaryOperator<String> scrub = text -> mask.apply(target.scrub(text));
            int failed = 0;
            List<Object> results = new ArrayList<>();
            try (target) {
                RunOptions options = RunOptions.defaults().withTargetRestarted(RunCommand.restartProbe(target))
                        .withBind(BindArgs.options(settings, credentials, approver));
                for (RunCommand.Loaded input : loaded) {
                    String id = input.plan().scenarioId();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("scenarioId", id);
                    if (!input.plan().needsBindings()) {
                        entry.put("status", "NO_BINDINGS_NEEDED");
                        results.add(entry);
                        cli.err().println(scrub.apply(id + ": needs no bindings; skipped"));
                        continue;
                    }
                    PlanExecutor.Resolved resolved = new PlanExecutor(input.pack(), target.client(), options)
                            .resolve(input.plan());
                    RunResult result = resolved.result();
                    BindOutcome outcome = resolved.outcome();
                    entry.put("status", result.status().name());
                    entry.put("failureKind", result.failureKind() == null ? null : result.failureKind().name());
                    entry.put("message", result.message());
                    entry.put("detail", result.detail());
                    boolean bound = outcome != null
                            && (result.status() == Status.PASS || result.status() == Status.DEGRADED);
                    if (outcome != null) {
                        for (CrossCheckOutcome check : outcome.bindings().crossChecks().values()) {
                            cli.out().println(scrub.apply(id + ": " + (check.skipped() ? "degraded: " : "")
                                    + check.summary()));
                        }
                    }
                    // phase3-contracts.md 9: both keys for every bound scenario, also when the bind failed (the
                    // records it emitted before it failed: an agreement before a GUARD, the ORACLE_SUSPECT one).
                    entry.put("crossChecks", new ArrayList<Object>(resolved.crossChecks()));
                    entry.put("skipped", outcome == null ? List.of() : outcome.skipped());
                    if (!bound) {
                        failed++;
                        cli.out().println(scrub.apply(id + ": " + result.failureKind() + BindArgs.reason(
                                result.detail()) + ": " + result.message()));
                    } else if (dryRun) {
                        // PlanExecutor.resolve refused a file that would hold a credential value (ENVIRONMENT, not
                        // bound), so what is printed here and put into --json passed approve's check.
                        String text = outcome.file().toJsonText();
                        entry.put("wouldWrite", Json.parse(text));
                        cli.out().println(scrub.apply(id + ": resolved (dry run); would write "
                                + settings.envId() + "/" + id + ".bindings.json:"));
                        cli.out().print(scrub.apply(text));
                    } else {
                        Path written = outcome.written();
                        entry.put("written", written == null ? null : written.toString());
                        cli.out().println(scrub.apply(id + ": resolved; " + (written == null
                                ? "the bindings file is already identical" : "wrote " + written)));
                    }
                    results.add(entry);
                }
            }
            if (json) {
                Map<String, Object> document = new LinkedHashMap<>();
                document.put("results", results);
                document.put("exitCode", (long) (failed > 0 ? Exit.FAILED : Exit.OK));
                cli.out().print(scrub.apply(Json.writePretty(document)));
            }
            return failed > 0 ? Exit.FAILED : Exit.OK;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
