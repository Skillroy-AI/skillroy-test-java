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

import ai.skillroy.testjava.scenario.Durations;
import ai.skillroy.testjava.scenario.ScenarioException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A command line parsed against a {@link CommandSpec}: positional arguments, option values in the order given, and
 * whether help was asked for. The typed getters turn a malformed value into a {@link UsageException} that names the
 * option, so commands never see a raw {@code NumberFormatException}.
 *
 * <p>Immutable; safe to share between threads.
 */
final class ParsedArgs {

    private final CommandSpec spec;
    private final List<String> positionals;
    private final Map<String, List<String>> values;
    private final boolean help;

    ParsedArgs(CommandSpec spec, List<String> positionals, Map<String, List<String>> values, boolean help) {
        this.spec = spec;
        this.positionals = List.copyOf(positionals);
        this.values = Map.copyOf(values);
        this.help = help;
    }

    /** The command these arguments belong to. */
    CommandSpec spec() {
        return spec;
    }

    /** True when {@code --help} or {@code -h} appeared before {@code --}; nothing else was validated then. */
    boolean help() {
        return help;
    }

    /** The positional arguments, in order. */
    List<String> positionals() {
        return positionals;
    }

    /** True when the option (flag or valued) was given. */
    boolean has(String name) {
        declared(name);
        return values.containsKey(name);
    }

    /** True when the flag was given. */
    boolean flag(String name) {
        return has(name);
    }

    /** The value of a single-valued option, or null when absent. */
    String value(String name) {
        declared(name);
        List<String> given = values.get(name);
        return given == null ? null : given.get(given.size() - 1);
    }

    /** Every value of a repeatable option in the order given; empty when absent. */
    List<String> values(String name) {
        declared(name);
        return values.getOrDefault(name, List.of());
    }

    /**
     * The option as a whole number in {@code [min, max]}, or null when absent.
     *
     * @throws UsageException if the value is not such a number
     */
    Long longValue(String name, long min, long max) throws UsageException {
        String text = value(name);
        if (text == null) {
            return null;
        }
        long parsed;
        try {
            parsed = Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            throw new UsageException(name + " needs a whole number, found '" + text + "'");
        }
        if (parsed < min || parsed > max) {
            throw new UsageException(name + " must be between " + min + " and " + max + ", found " + parsed);
        }
        return parsed;
    }

    /**
     * The option as a duration in milliseconds ({@code 10s}, {@code 500ms}, {@code 2m} or a number of milliseconds,
     * as in scenario files), or null when absent.
     *
     * @throws UsageException if the value is not a positive duration
     */
    Long durationMs(String name) throws UsageException {
        String text = value(name);
        if (text == null) {
            return null;
        }
        try {
            return Durations.toMillis(text, name);
        } catch (ScenarioException e) {
            throw new UsageException(e.getMessage());
        }
    }

    /**
     * The option as a path, or null when absent.
     *
     * @throws UsageException if the value is not a valid path on this platform
     */
    Path path(String name) throws UsageException {
        String text = value(name);
        return text == null ? null : toPath(name, text);
    }

    /**
     * {@code text} as a path.
     *
     * @param what the option or argument the text came from, for the message
     * @throws UsageException if it is empty or not a valid path
     */
    static Path toPath(String what, String text) throws UsageException {
        if (text.isBlank()) {
            throw new UsageException(what + " needs a path, found an empty value");
        }
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new UsageException(what + ": not a valid path: " + text);
        }
    }

    private void declared(String name) {
        if (spec.option(name) == null) {
            throw new IllegalArgumentException("command " + spec.name() + " declares no option " + name);
        }
    }
}
