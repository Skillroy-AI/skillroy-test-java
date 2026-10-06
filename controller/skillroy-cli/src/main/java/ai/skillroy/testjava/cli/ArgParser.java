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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CLI's hand-written argument parser (no third-party CLI library). Rules:
 *
 * <ul>
 *   <li>{@code --help} or {@code -h} anywhere before {@code --} asks for help; nothing else is checked then.</li>
 *   <li>Options are long only: {@code --name value} or {@code --name=value}; a flag takes no value. The token after
 *       a valued option is always its value, even when it starts with {@code -} ({@code --since -1}).</li>
 *   <li>Options and positional arguments may be mixed; {@code --} ends the options, and a lone {@code -} is a
 *       positional argument (standard input).</li>
 *   <li>Unknown options, a missing value, a value on a flag, a non-repeatable option given twice, and too few or too
 *       many positional arguments are {@link UsageException}s.</li>
 * </ul>
 *
 * <p>Stateless; safe from any thread.
 */
final class ArgParser {

    private ArgParser() {
    }

    /**
     * Parses {@code args} (the words after the command name) against {@code spec}.
     *
     * @throws UsageException if the words do not fit the spec
     */
    static ParsedArgs parse(CommandSpec spec, List<String> args) throws UsageException {
        for (String arg : args) {
            if (arg.equals("--")) {
                break;
            }
            if (arg.equals("--help") || arg.equals("-h")) {
                return new ParsedArgs(spec, List.of(), Map.of(), true);
            }
        }
        Map<String, List<String>> values = new LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        boolean optionsEnded = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (!optionsEnded && arg.equals("--")) {
                optionsEnded = true;
                continue;
            }
            if (optionsEnded || !arg.startsWith("-") || arg.equals("-")) {
                positionals.add(arg);
                continue;
            }
            if (!arg.startsWith("--")) {
                throw new UsageException("unknown option '" + arg + "' (options are written --name; put -- before an"
                        + " argument that starts with -)");
            }
            int eq = arg.indexOf('=');
            String name = eq < 0 ? arg : arg.substring(0, eq);
            String inline = eq < 0 ? null : arg.substring(eq + 1);
            OptionSpec option = spec.option(name);
            if (option == null) {
                throw new UsageException("unknown option '" + name + "'");
            }
            String value;
            if (option.takesValue()) {
                if (inline != null) {
                    value = inline;
                } else if (i + 1 < args.size()) {
                    value = args.get(++i);
                } else {
                    throw new UsageException("option " + name + " needs a value <" + option.valueName() + ">");
                }
            } else {
                if (inline != null) {
                    throw new UsageException("option " + name + " takes no value");
                }
                value = "";
            }
            List<String> given = values.computeIfAbsent(name, k -> new ArrayList<>());
            if (!given.isEmpty() && !option.repeatable()) {
                throw new UsageException("option " + name + " given more than once");
            }
            given.add(value);
        }
        if (positionals.size() < spec.minArgs()) {
            throw new UsageException(spec.minArgs() == 1 && spec.maxArgs() == 1
                    ? "missing argument " + spec.synopsis()
                    : "missing argument: expected " + spec.synopsis());
        }
        if (positionals.size() > spec.maxArgs()) {
            throw new UsageException("unexpected argument '" + positionals.get(spec.maxArgs()) + "'"
                    + (spec.maxArgs() == 0 ? " (this command takes options only)" : ""));
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        values.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new ParsedArgs(spec, positionals, frozen, false);
    }
}
