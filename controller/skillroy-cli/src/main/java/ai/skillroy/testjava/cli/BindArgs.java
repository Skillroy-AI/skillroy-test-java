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
import ai.skillroy.testjava.inference.resolve.LlmSqlResolver;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindingsDiff;
import ai.skillroy.testjava.scenario.CrossCheckOutcome;
import ai.skillroy.testjava.scenario.OracleValue;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.LlmUsage;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The bind options of {@code run} and {@code resolve} (phase2-contracts.md 12.1): {@code --mode}, {@code --env},
 * {@code --bindings}, {@code --credentials}, {@code --yes}, {@code --approved-by}, {@code --write-bindings}; the
 * bindings root of the invocation (decision D31), the credentials loaded once before the agent is contacted, and the
 * approver (a terminal question, {@code --yes}, or exit 64 when nobody can answer).
 *
 * <p>Phase 5 (phase5-contracts.md 4.4, 7.1): where {@code inference.yaml} is looked for
 * ({@link #credentialsDir}), the bind options with the inference settings and {@code approve}'s reuse rule, and the
 * approval screen's lines of a model-written (tier (b)) value.
 *
 * <p>Stateless; used from the command's thread.
 */
final class BindArgs {

    static final String MODE = "--mode";
    static final String ENV = "--env";
    static final String BINDINGS = "--bindings";
    static final String CREDENTIALS = "--credentials";
    static final String YES = "--yes";
    static final String APPROVED_BY = "--approved-by";
    static final String WRITE_BINDINGS = "--write-bindings";

    private static final Pattern ENV_ID = Pattern.compile("[a-z0-9-]+");

    static final OptionSpec ENV_OPTION = OptionSpec.value(ENV, "id", "the environment id, [a-z0-9-]+ (default\n"
            + "'default'); bindings live in <bindings>/<id>/");
    static final OptionSpec BINDINGS_OPTION = OptionSpec.value(BINDINGS, "dir", "the bindings root (default: the\n"
            + "system property skillroy.bindings, else 'bindings' in\nthe parent of the first argument's directory)");
    static final OptionSpec CREDENTIALS_OPTION = OptionSpec.value(CREDENTIALS, "file", "credentials.yaml (default:\n"
            + "-Dskillroy.credentials, $SKILLROY_CREDENTIALS, or\n<bindings>/<env>/credentials.yaml when it exists)");
    static final OptionSpec YES_OPTION = OptionSpec.flag(YES, "approve everything without asking");
    static final OptionSpec APPROVED_BY_OPTION = OptionSpec.value(APPROVED_BY, "label", "written as approval.by in"
            + " the bindings file\n(default: the mode name)");

    /** The options of {@code run}. */
    static final List<OptionSpec> RUN_OPTIONS = List.of(
            OptionSpec.value(MODE, "replay|approve|live", "how slots are bound (default replay: the\n"
                    + "committed bindings file; approve: resolve, ask,\nwrite; live: resolve now)"),
            ENV_OPTION, BINDINGS_OPTION, CREDENTIALS_OPTION, YES_OPTION, APPROVED_BY_OPTION,
            OptionSpec.flag(WRITE_BINDINGS, "live only: write the bindings file (approval\nNOT_REQUIRED)"));

    /** What the options say. */
    record Settings(BindMode mode, String envId, Path root, Path credentialsFile, boolean yes, String approvedBy,
                    boolean writeBindings) {
    }

    private BindArgs() {
    }

    /**
     * Reads the options; {@code mode} is used when the command fixes it ({@code resolve}).
     *
     * @param files the expanded file arguments, for the default bindings root
     * @throws UsageException for {@code re-resolve} or another unknown mode, a bad {@code --env},
     *     {@code --write-bindings} outside {@code live}
     */
    static Settings parse(ParsedArgs args, List<Path> files, BindMode mode) throws UsageException {
        BindMode chosen = mode;
        if (chosen == null) {
            String text = args.value(MODE);
            try {
                chosen = text == null ? BindMode.REPLAY : BindMode.parse(text);
            } catch (IllegalArgumentException e) {
                throw new UsageException(MODE + ": " + e.getMessage());
            }
            if (chosen == BindMode.RE_RESOLVE) {
                throw new UsageException(MODE + " re-resolve: re-resolve is a command, not a run mode: skillroy re-resolve");
            }
        }
        String envId = args.value(ENV) == null ? BindOptions.DEFAULT_ENV : args.value(ENV).strip();
        if (!ENV_ID.matcher(envId).matches()) {
            throw new UsageException(ENV + " must match [a-z0-9-]+, found '" + envId + "'");
        }
        boolean write = args.spec().option(WRITE_BINDINGS) != null && args.flag(WRITE_BINDINGS);
        if (write && chosen != BindMode.LIVE) {
            throw new UsageException(WRITE_BINDINGS + " needs " + MODE + " live");
        }
        Path root = args.path(BINDINGS);
        if (root == null) {
            String property = System.getProperty(BindOptions.PROP_BINDINGS);
            root = property == null || property.isBlank() ? defaultRoot(files) : Path.of(property.strip());
        }
        boolean yes = args.spec().option(YES) != null && args.flag(YES);
        String approvedBy = args.spec().option(APPROVED_BY) == null ? null : args.value(APPROVED_BY);
        return new Settings(chosen, envId, root, args.path(CREDENTIALS), yes, approvedBy, write);
    }

    /**
     * Where {@code inference.yaml} is looked for (phase5-contracts.md 4.4): the directory of the credentials file the
     * invocation uses, else {@code <bindings>/<env>/}; null when neither is known. Called after the credentials loaded,
     * so that a named file exists.
     */
    static Path credentialsDir(Settings settings) {
        Path file = Credentials.locate(settings.credentialsFile(), settings.root(), settings.envId());
        if (file != null) {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return settings.root() == null ? null : settings.root().resolve(settings.envId());
    }

    /** {@code bindings} in the parent of the directory that holds the first file (D31); null for none. */
    static Path defaultRoot(List<Path> files) {
        if (files.isEmpty()) {
            return null;
        }
        Path dir = files.get(0).toAbsolutePath().normalize().getParent();
        Path parent = dir == null ? null : dir.getParent();
        return parent == null ? null : parent.resolve("bindings");
    }

    /**
     * The credentials of the invocation, located and loaded once (phase2-contracts.md 4).
     *
     * @throws CredentialsException if a file was named and is missing, or is unreadable, malformed or too open
     */
    static Credentials credentials(Settings settings) throws CredentialsException {
        Path file = Credentials.locate(settings.credentialsFile(), settings.root(), settings.envId());
        return file == null ? Credentials.empty() : Credentials.load(file);
    }

    /**
     * The approver: {@link Approver#APPROVE_ALL} with {@code --yes}, the terminal when a person can answer.
     *
     * @throws UsageException in {@code approve} mode without {@code --yes} and without an interactive terminal
     */
    static Approver approver(Settings settings, Cli cli) throws UsageException {
        if (settings.mode() != BindMode.APPROVE || settings.yes()) {
            return Approver.APPROVE_ALL;
        }
        if (!cli.terminal().interactive()) {
            throw new UsageException("approve needs a terminal to ask on, or " + YES + "; nothing was run");
        }
        return null;
    }

    /** The bind options for the executor. */
    static BindOptions options(Settings settings, Credentials credentials, Approver approver) {
        return BindOptions.defaults().withMode(settings.mode()).withEnvId(settings.envId())
                .withBindingsRoot(settings.root()).withCredentials(credentials).withApprover(approver)
                .withWriteBindings(settings.writeBindings()).withApprovedBy(settings.approvedBy());
    }

    /**
     * The bind options with the inference settings of the invocation and {@code approve}'s reuse rule
     * (phase5-contracts.md 4.4, 7.1: {@code --no-reuse-bindings} turns the reuse off).
     */
    static BindOptions options(Settings settings, Credentials credentials, Approver approver,
                               InferenceSettings inference, boolean reuseBindings) {
        return options(settings, credentials, approver).withInference(inference).withReuseBindings(reuseBindings);
    }

    /**
     * Asks on the terminal: prints the fingerprint, each resolution (slot, value, resolver, statements with their
     * parameters and masked row samples, verifier reasons), each oracle with its guard and its cross-check's outcome
     * (phase3-contracts.md 8.3) and the difference against the existing file, then reads y or n. End of input is n.
     * Every value is masked before it is written as JSON, and the whole text again (phase2-contracts.md 11.4).
     */
    static Approver terminalApprover(Cli cli, SecretMask mask) {
        return request -> {
            print(cli.out(), request, mask);
            String answer = cli.terminal().readLine("approve the bindings of " + request.scenarioId() + " in "
                    + request.envId() + "? [y/N] ");
            boolean yes = answer != null && List.of("y", "yes").contains(answer.strip().toLowerCase(Locale.ROOT));
            return new Approver.Decision(yes, null, yes ? null : answer == null ? "end of input" : "answered no");
        };
    }

    static void print(PrintStream out, Approver.ApprovalRequest request, SecretMask mask) {
        StringBuilder text = new StringBuilder();
        text.append("bindings of ").append(request.scenarioId()).append(" in environment ")
                .append(request.envId()).append('\n');
        if (request.env() != null) {
            text.append("  fingerprint ").append(request.env().hash()).append('\n');
            request.env().components().forEach((k, v) -> text.append("    ").append(k).append(": ").append(v)
                    .append('\n'));
        }
        for (Resolution r : request.resolutions()) {
            Provenance p = r.provenance();
            text.append("  slot ").append(r.slot()).append(" = ").append(json(r.value(), mask))
                    .append("  (").append(p == null ? "?" : p.resolverId()).append(", ").append(r.strategy())
                    .append(")\n");
            if (r.credential() != null) {
                text.append("    credential ").append(r.credential()).append('\n');
            }
            if (p != null) {
                for (Provenance.Statement s : p.statements()) {
                    text.append("    statement [").append(s.channel()).append(' ').append(s.source()).append("] ")
                            .append(s.sql()).append("\n      params ").append(json(s.params(), mask))
                            .append(", rows ").append(s.rowCount()).append(s.truncated() ? " (truncated)" : "")
                            .append('\n');
                    for (List<Object> row : s.rowSample()) {
                        text.append("      | ").append(json(row, mask)).append('\n');
                    }
                }
                for (Provenance.Call c : p.calls()) {
                    text.append("    call ").append(c.primitive()).append(' ').append(json(c.params(), mask))
                            .append('\n');
                }
                for (Provenance.Rejected rejected : p.rejected()) {
                    text.append("    rejected ").append(rejected.label()).append(": ").append(rejected.reason())
                            .append('\n');
                }
            }
            for (VerifierResult v : r.verified()) {
                text.append("    verifier ").append(v.verifierId()).append(v.passed() ? ": passed: " : ": failed: ")
                        .append(v.reason()).append('\n');
            }
            if (r.strategy().needsModel() || r.usage() != null) {
                modelWritten(text, r, p);
            }
        }
        for (OracleValue o : request.oracles()) {
            text.append("  oracle ").append(o.name()).append(" (").append(o.fn()).append(") = ")
                    .append(json(o.value(), mask));
            if (o.mustEqual() != null) {
                text.append("  must equal ").append(json(o.mustEqual(), mask));
            }
            text.append('\n');
            for (CrossCheckOutcome check : request.crossChecks()) {
                if (check.oracle().equals(o.name())) {
                    text.append("    ").append(check.skipped() ? "cross-check " + check.spec().check()
                            + " skipped: capability " + check.spec().capability() + " is " + check.capabilityState()
                            + (check.capabilityReason() == null ? "" : " (" + check.capabilityReason() + ")")
                            : check.summary()).append('\n');
                }
            }
        }
        BindingsDiff diff = request.againstExisting();
        if (diff == null) {
            text.append("  no bindings file exists yet\n");
        } else if (diff.isEmpty()) {
            text.append("  identical to the existing bindings file\n");
        } else {
            text.append("  differences against the existing bindings file:\n");
            for (BindingsDiff.Change change : diff.changes()) {
                text.append("    ").append(change.where()).append(": ").append(json(change.before(), mask))
                        .append(" -> ").append(json(change.after(), mask)).append('\n');
            }
        }
        out.print(mask.apply(text.toString()));
        out.flush();
    }

    /**
     * The approval screen's lines of a value a model found (tier (b), phase5-contracts.md 7.1), after the verifier
     * reasons: the strategy with the model, the request id and the cost ({@code Resolution.usage}), or, for a value
     * {@code approve} reused from the bindings file, "recorded, verified now; no model call"; the model's rationale
     * ({@code Provenance.rationale}); each clause the guard injected into the statement printed above, marked
     * {@code [injected]}; the query arguments the statement's parameters bind (their values are the statement's
     * {@code params} above); the confidence, the row bounds the model stated and the rows found. The guard's clauses,
     * the arguments and the bounds come from the resolver's provenance notes ({@code LlmSqlResolver.GUARD_NOTE},
     * {@code PARAMS_NOTE}, {@code ROWS_NOTE}). The caller masks the whole text.
     */
    private static void modelWritten(StringBuilder text, Resolution r, Provenance p) {
        List<String> notes = p == null ? List.of() : p.notes();
        String reused = notes.stream().filter(n -> n.startsWith(REUSED_NOTE)).findFirst().orElse(null);
        text.append("    strategy ").append(r.strategy().token());
        if (reused != null) {
            text.append(": recorded, verified now; no model call (").append(reused).append(")\n");
        } else {
            LlmUsage usage = r.usage();
            text.append(", model ").append(usage == null || usage.model() == null ? "?" : usage.model())
                    .append(", request ").append(usage == null || usage.requestId() == null ? "?"
                            : usage.requestId())
                    .append(", cost $").append(usage == null || usage.costUsd() == null ? "?" : usage.costUsd())
                    .append('\n');
        }
        if (p != null && p.rationale() != null && !p.rationale().isBlank()) {
            text.append("    rationale: ").append(Text.oneLine(p.rationale(), Provenance.MAX_RATIONALE)).append('\n');
        }
        String rows = null;
        String bound = null;
        for (String note : notes) {
            int guard = note.indexOf(": " + LlmSqlResolver.GUARD_NOTE);
            if (guard >= 0) {
                text.append("      [injected] ").append(note.substring(guard + 2 + LlmSqlResolver.GUARD_NOTE.length()))
                        .append('\n');
            }
            int stated = note.indexOf(": " + LlmSqlResolver.ROWS_NOTE);
            if (stated >= 0) {
                rows = note.substring(stated + 2 + LlmSqlResolver.ROWS_NOTE.length());
            }
            int params = note.indexOf(": " + LlmSqlResolver.PARAMS_NOTE);
            if (params >= 0) {
                bound = note.substring(params + 2 + LlmSqlResolver.PARAMS_NOTE.length());
            }
        }
        if (bound != null) {
            text.append("    the parameters bind the arguments ").append(bound).append(", in order\n");
        }
        text.append("    confidence ").append(BigDecimal.valueOf(r.confidence()).stripTrailingZeros().toPlainString());
        if (rows != null) {
            text.append("; rows stated ").append(rows);
        }
        text.append('\n');
    }

    /** How the note of a value {@code approve} reused from the bindings file starts (the engine writes it). */
    private static final String REUSED_NOTE = "reused from the bindings file";

    /**
     * A value as JSON text with the credentials masked in the value first (phase2-contracts.md 11.4): the JSON text
     * escapes a quote or a backslash, so the mask applied to the screen's text afterwards would not find such a
     * secret.
     */
    private static String json(Object value, SecretMask mask) {
        return Json.write(mask.apply(value));
    }

    /** The detail fields a binder failure adds, for the CLI's one-line summaries. */
    static String reason(Map<String, Object> detail) {
        Object reason = detail.get("reason");
        return reason == null ? "" : " (" + reason + ")";
    }
}
