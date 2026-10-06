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

import java.util.Objects;

/**
 * One option a command accepts: a flag ({@code --json}) or an option with a value ({@code --pid <pid>}), possibly
 * repeatable ({@code --pack <jar>...}). Declared per command so that {@code --help} and the parser cannot disagree.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param name the option as typed, starting with {@code --}
 * @param valueName the placeholder shown in help ({@code <pid>}); null for a flag
 * @param repeatable whether the option may be given more than once
 * @param help one or more lines of help text
 */
record OptionSpec(String name, String valueName, boolean repeatable, String help) {

    /** Validates the name. */
    OptionSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(help, "help");
        if (!name.startsWith("--") || name.length() < 3 || name.indexOf('=') >= 0) {
            throw new IllegalArgumentException("bad option name: " + name);
        }
    }

    /** A flag: present or absent, no value. */
    static OptionSpec flag(String name, String help) {
        return new OptionSpec(name, null, false, help);
    }

    /** An option that takes one value and may be given once. */
    static OptionSpec value(String name, String valueName, String help) {
        return new OptionSpec(name, valueName, false, help);
    }

    /** An option that takes one value and may be given several times. */
    static OptionSpec repeatable(String name, String valueName, String help) {
        return new OptionSpec(name, valueName, true, help);
    }

    /** True unless this is a flag. */
    boolean takesValue() {
        return valueName != null;
    }

    /** The left column of the help: {@code --pid <pid>}, {@code --pack <jar>...}, {@code --json}. */
    String label() {
        if (valueName == null) {
            return name;
        }
        return name + " <" + valueName + ">" + (repeatable ? "..." : "");
    }
}
