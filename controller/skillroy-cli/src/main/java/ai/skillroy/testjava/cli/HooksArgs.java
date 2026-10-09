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

import ai.skillroy.testjava.scenario.hooks.HooksMode;

/**
 * The run-level hooks switch on the command line (phase6-contracts.md 6.1, decision D127): {@code --hooks
 * auto|on|off} on {@code run}, {@code session} and {@code mcp}; read before the agent is contacted, so that a wrong
 * value is a usage error (64) before anything runs.
 */
final class HooksArgs {

    static final String HOOKS = "--hooks";

    static final OptionSpec OPTION = OptionSpec.value(HOOKS, "auto|on|off", "install the pack's hook catalogue on the\n"
            + "target first: auto (default) when the target's hooks module\nis loaded, on (refuse when it cannot be"
            + " installed), off");

    private HooksArgs() {
    }

    /**
     * The switch the arguments give; {@link HooksMode#AUTO} without {@code --hooks}.
     *
     * @throws UsageException for a value other than {@code auto}, {@code on} or {@code off}
     */
    static HooksMode mode(ParsedArgs args) throws UsageException {
        try {
            return HooksMode.parse(args.value(HOOKS));
        } catch (IllegalArgumentException e) {
            throw new UsageException(HOOKS + ": " + e.getMessage());
        }
    }
}
