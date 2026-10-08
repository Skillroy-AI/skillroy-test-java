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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.Scenario;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.ScenarioLoader;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code skillroy compile}: scenario YAML to the committed {@code plan.json}, and {@code --check} for CI, which fails
 * when a committed plan no longer matches its YAML and pack (contract 8.2: compiling is deterministic, so a fresh
 * compile must give the same bytes).
 */
final class CompileCommand implements Command {

    private static final String OUT = "--out";
    private static final String CHECK = "--check";

    private static final CommandSpec SPEC = new CommandSpec("compile", "compile scenario YAML into plan.json",
            "<scenario.yaml|dir>...", """
            Compiles each scenario (a directory stands for its *.yaml and *.yml files) with the domain pack its
            'pack:' key names, or the only pack, into <scenario-id>.plan.json: in --out <dir>, or else next to the
            YAML file. A plan is rewritten only when its content changed. Load and compile errors are reported as
            file:line: reason, and give exit code 2; the other scenarios are still compiled.
            --check writes nothing: it compiles each scenario and compares the result with the plan file that is
            there; a missing or different plan is reported as stale and gives exit code 1. The compiler's notes
            (what it added that the scenario did not say, such as web.cdp in requires for web.path: cdp) are
            printed to stderr as file: note: text.""",
            List.of(OptionSpec.value(OUT, "dir", "write (or, with --check, look for) the plans in this directory"),
                    OptionSpec.flag(CHECK, "write nothing; fail if a plan is missing or stale"),
                    Packs.OPTION_SPEC,
                    OptionSpec.flag("--json", "print {\"plans\":[{source, scenarioId, plan, status, error?,\n"
                            + "notes?}]} as JSON; status is written, unchanged,\n"
                            + "up-to-date, stale, missing or error")),
            1, CommandSpec.UNLIMITED);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws UsageException, CliException {
        Path out = args.path(OUT);
        if (out != null && Files.exists(out) && !Files.isDirectory(out)) {
            throw new UsageException(OUT + " " + out + " is not a directory");
        }
        boolean check = args.flag(CHECK);
        boolean json = args.flag("--json");
        List<Path> files = ScenarioFiles.expand(args.positionals());
        List<Map<String, Object>> report = new ArrayList<>();
        Map<Path, Path> planSources = new HashMap<>();
        int errors = 0;
        int stale = 0;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (Packs packs = Packs.load(args.values(Packs.OPTION), cli.classLoader())) {
            Thread.currentThread().setContextClassLoader(packs.loader());
            for (Path yaml : files) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("source", yaml.toString());
                report.add(entry);
                Scenario scenario;
                Plan plan;
                List<String> notes;
                try {
                    scenario = ScenarioLoader.load(yaml);
                    DomainPack pack;
                    try {
                        pack = packs.select(scenario.pack());
                    } catch (ScenarioException e) {
                        throw e.locate(scenario.id(), 0);
                    }
                    PlanCompiler.Compiled compiled = PlanCompiler.compileWithNotes(scenario, pack);
                    plan = compiled.plan();
                    notes = compiled.notes();
                    for (String note : notes) {
                        cli.err().println(yaml + ": note: " + note);
                    }
                } catch (ScenarioException e) {
                    errors++;
                    String message = ScenarioFiles.describe(yaml, e);
                    entry.put("status", "error");
                    entry.put("error", message);
                    cli.err().println(message);
                    continue;
                }
                Path dir = out != null ? out : yaml.toAbsolutePath().getParent();
                Path planFile = dir.resolve(scenario.id() + ".plan.json");
                entry.put("scenarioId", scenario.id());
                entry.put("plan", planFile.toString());
                Path other = planSources.putIfAbsent(planFile.toAbsolutePath().normalize(), yaml);
                if (other != null) {
                    errors++;
                    String message = yaml + ": scenario id '" + scenario.id() + "' is also the id of " + other
                            + "; both would compile to " + planFile;
                    entry.put("status", "error");
                    entry.put("error", message);
                    cli.err().println(message);
                    continue;
                }
                String text = plan.toJsonText();
                String existing = readIfPresent(planFile);
                String status;
                if (check) {
                    status = existing == null ? "missing" : existing.equals(text) ? "up-to-date" : "stale";
                    if (existing == null || !existing.equals(text)) {
                        stale++;
                    }
                } else if (text.equals(existing)) {
                    status = "unchanged";
                } else {
                    try {
                        plan.write(planFile);
                    } catch (IOException e) {
                        throw new CliException(Exit.FAILED, "cannot write " + planFile + ": " + e, e);
                    }
                    status = "written";
                }
                entry.put("status", status);
                if (!notes.isEmpty()) {
                    entry.put("notes", notes);
                }
                if (!json) {
                    cli.out().println(String.format("%-10s %s  (%s, pack %s %s)", status, planFile, scenario.id(),
                            plan.pack(), plan.packVersion()));
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        if (json) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("plans", report);
            cli.out().print(Json.writePretty(result));
        }
        if (errors > 0) {
            cli.err().println("skillroy compile: " + errors + " of " + files.size() + " scenario"
                    + (files.size() == 1 ? "" : "s") + " could not be compiled");
            return Exit.SCENARIO;
        }
        if (stale > 0) {
            cli.err().println("skillroy compile: " + stale + " of " + files.size() + " plan"
                    + (files.size() == 1 ? " is" : "s are") + " missing or stale; run skillroy compile without "
                    + CHECK + " and commit the result");
            return Exit.FAILED;
        }
        return Exit.OK;
    }

    /** The plan file's text, null when there is no such file, and text that cannot match when it is unreadable. */
    private static String readIfPresent(Path planFile) {
        if (!Files.isRegularFile(planFile)) {
            return null;
        }
        try {
            return Files.readString(planFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
