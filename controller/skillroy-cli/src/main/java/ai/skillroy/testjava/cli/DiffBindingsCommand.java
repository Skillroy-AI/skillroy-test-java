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
import ai.skillroy.testjava.scenario.BindingsDiff;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.BindingsFormatException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code skillroy diff-bindings} (phase2-contracts.md 12.1): offline (no agent), prints what differs between two
 * bindings files, one line per change grouped by slot: for reviewing a re-resolution, and for comparing two
 * environments. Exit 0 when the files do not differ, 1 when they differ, 2 when a file is missing or not a bindings
 * file, 64 for a usage error.
 */
final class DiffBindingsCommand implements Command {

    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");

    private static final CommandSpec SPEC = new CommandSpec("diff-bindings", "compare two bindings files",
            "<a.bindings.json> <b.bindings.json> | <scenario-id>", """
            Prints the differences between two bindings files: their ids, pack version, hashes and fingerprint
            components, then per slot the value, arguments, strategy, resolver, statements, calls, verifier outcome
            and credential, and per oracle the function and value. Give the two files, or --bindings <dir> with
            --env twice and a scenario id to compare one scenario in two environments. Needs no agent.""",
            List.of(OptionSpec.value(BindArgs.BINDINGS, "dir", "the bindings root (with two --env and a scenario id)"),
                    OptionSpec.repeatable(BindArgs.ENV, "id", "an environment id (give it twice)"),
                    OptionSpec.flag("--json", "print {differ, changes:[{where, before, after}]} as JSON")),
            1, 2);

    @Override
    public CommandSpec spec() {
        return SPEC;
    }

    @Override
    public int run(ParsedArgs args, Cli cli) throws Exception {
        Path a;
        Path b;
        List<String> envs = args.values(BindArgs.ENV);
        if (args.positionals().size() == 2) {
            if (!envs.isEmpty() || args.has(BindArgs.BINDINGS)) {
                throw new UsageException("give either two files, or --bindings, --env twice and a scenario id");
            }
            a = ParsedArgs.toPath("argument", args.positionals().get(0));
            b = ParsedArgs.toPath("argument", args.positionals().get(1));
        } else {
            Path root = args.path(BindArgs.BINDINGS);
            String id = args.positionals().get(0);
            if (root == null || envs.size() != 2) {
                throw new UsageException("with one argument (a scenario id) give --bindings <dir> and --env twice");
            }
            for (String text : List.of(id, envs.get(0), envs.get(1))) {
                if (!ID.matcher(text).matches()) {
                    throw new UsageException("'" + text + "' is not an id ([a-z0-9-]+)");
                }
            }
            a = BindingsFile.locate(root, envs.get(0), id);
            b = BindingsFile.locate(root, envs.get(1), id);
        }
        BindingsDiff diff = read(a).diff(read(b));
        if (args.flag("--json")) {
            List<Object> changes = new ArrayList<>();
            for (BindingsDiff.Change change : diff.changes()) {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("where", change.where());
                json.put("before", change.before());
                json.put("after", change.after());
                changes.add(json);
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("a", a.toString());
            document.put("b", b.toString());
            document.put("differ", !diff.isEmpty());
            document.put("slots", new ArrayList<>(diff.slots()));
            document.put("changes", changes);
            cli.out().print(Json.writePretty(document));
        } else {
            cli.out().print(text(diff, a, b));
        }
        return diff.isEmpty() ? Exit.OK : Exit.FAILED;
    }

    private static BindingsFile read(Path file) throws CliException {
        if (!Files.isRegularFile(file)) {
            throw new CliException(Exit.SCENARIO, file + ": no such bindings file");
        }
        try {
            return BindingsFile.read(file);
        } catch (IOException e) {
            throw new CliException(Exit.SCENARIO, file + ": cannot be read: " + e.getClass().getSimpleName(), e);
        } catch (BindingsFormatException e) {
            throw new CliException(Exit.SCENARIO, file + ": not a bindings file: " + e.getMessage(), e);
        }
    }

    /** One line per change, the slot and oracle changes grouped under their name. */
    static String text(BindingsDiff diff, Path a, Path b) {
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(a).append('\n').append("+++ ").append(b).append('\n');
        if (diff.isEmpty()) {
            return out.append("no differences\n").toString();
        }
        String group = null;
        for (BindingsDiff.Change change : diff.changes()) {
            String where = change.where();
            String newGroup = null;
            String field = where;
            for (String prefix : List.of("resolutions.", "oracles.")) {
                if (where.startsWith(prefix)) {
                    String rest = where.substring(prefix.length());
                    int dot = rest.indexOf('.');
                    newGroup = (prefix.equals("oracles.") ? "oracle " : "slot ") + (dot < 0 ? rest
                            : rest.substring(0, dot));
                    field = dot < 0 ? "(whole entry)" : rest.substring(dot + 1);
                }
            }
            if (newGroup != null && !newGroup.equals(group)) {
                out.append(newGroup).append(":\n");
            }
            group = newGroup;
            out.append(newGroup == null ? "" : "  ").append(field).append(": ").append(Json.write(change.before()))
                    .append(" -> ").append(Json.write(change.after())).append('\n');
        }
        out.append(diff.changes().size()).append(" change").append(diff.changes().size() == 1 ? "" : "s")
                .append(" in ").append(diff.slots().size()).append(" slot").append(diff.slots().size() == 1 ? ""
                : "s").append(" or oracle").append(diff.slots().size() == 1 ? "" : "s").append('\n');
        return out.toString();
    }
}
