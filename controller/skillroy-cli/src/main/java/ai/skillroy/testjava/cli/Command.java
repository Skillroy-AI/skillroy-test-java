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

/**
 * One CLI command. {@link Main} parses the words after the command name against {@link #spec()}, shows the help
 * when asked, and maps exceptions to exit codes: {@link UsageException} to 64, {@link CliException} to its own code,
 * a lost agent to 3, a JSON-RPC error to 1, anything else to 70.
 *
 * <p>Implementations are stateless; {@link #run} runs on the caller's thread.
 */
interface Command {

    /** The command's name, options and help. */
    CommandSpec spec();

    /**
     * Runs the command.
     *
     * @return the exit code (see {@link Exit})
     * @throws Exception any failure; see the class comment for how it is reported
     */
    int run(ParsedArgs args, Cli cli) throws Exception;
}
