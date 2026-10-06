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

import java.util.List;
import java.util.Objects;

/**
 * What a command accepts and how it is explained: its name, a one-line summary for the command list, the argument
 * synopsis, a description, its options and how many positional arguments it takes. The single source of both the
 * parser's rules and the {@code --help} text.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param name the command name ({@code call})
 * @param summary one line for {@code skillroy --help}
 * @param synopsis the positional arguments as shown in the usage line ({@code <method> [<json-params>]}), may be empty
 * @param description paragraphs of help text
 * @param options the options, in help order
 * @param minArgs the least number of positional arguments
 * @param maxArgs the greatest number of positional arguments ({@link #UNLIMITED} for no limit)
 */
record CommandSpec(String name, String summary, String synopsis, String description, List<OptionSpec> options,
                   int minArgs, int maxArgs) {

    /** No upper limit on positional arguments. */
    static final int UNLIMITED = Integer.MAX_VALUE;

    private static final int LABEL_WIDTH = 30;

    /** Copies the options and checks that no option is declared twice. */
    CommandSpec {
        Objects.requireNonNull(name, "name");
        options = List.copyOf(options);
        if (options.stream().map(OptionSpec::name).distinct().count() != options.size()) {
            throw new IllegalArgumentException("option declared twice in " + name);
        }
        if (minArgs < 0 || maxArgs < minArgs) {
            throw new IllegalArgumentException("bad argument counts for " + name);
        }
    }

    /** The option called {@code name}, or null. */
    OptionSpec option(String optionName) {
        for (OptionSpec option : options) {
            if (option.name().equals(optionName)) {
                return option;
            }
        }
        return null;
    }

    /** The usage line: {@code Usage: skillroy call <method> [<json-params>] [options]}. */
    String usageLine() {
        StringBuilder line = new StringBuilder("Usage: skillroy ").append(name);
        if (!synopsis.isEmpty()) {
            line.append(' ').append(synopsis);
        }
        return line.append(" [options]").toString();
    }

    /** The full {@code --help} text, ending with a newline. */
    String help() {
        StringBuilder text = new StringBuilder(usageLine()).append("\n\n");
        text.append(description.strip()).append("\n\nOptions:\n");
        for (OptionSpec option : options) {
            appendOption(text, option.label(), option.help());
        }
        appendOption(text, "-h, --help", "show this help");
        return text.toString();
    }

    private static void appendOption(StringBuilder text, String label, String help) {
        String[] lines = help.split("\n");
        text.append("  ").append(label);
        if (label.length() + 2 >= LABEL_WIDTH) {
            text.append('\n').append(" ".repeat(LABEL_WIDTH));
        } else {
            text.append(" ".repeat(LABEL_WIDTH - label.length() - 2));
        }
        text.append(lines[0]).append('\n');
        for (int i = 1; i < lines.length; i++) {
            text.append(" ".repeat(LABEL_WIDTH)).append(lines[i]).append('\n');
        }
    }
}
