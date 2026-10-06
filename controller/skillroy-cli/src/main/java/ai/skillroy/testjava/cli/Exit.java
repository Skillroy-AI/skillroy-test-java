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
 * The CLI's exit codes. Distinct codes let a CI script tell a red scenario from a broken scenario file, an
 * unreachable agent and a typing mistake without parsing output.
 */
final class Exit {

    /** The command did what was asked; every scenario passed (or was DEGRADED and not mapped to a failure). */
    static final int OK = 0;
    /**
     * The command ran but its outcome is negative: a scenario FAIL or ERROR (or DEGRADED with
     * {@code --degraded-as failure}), a JSON-RPC error from {@code call}, a stale plan under {@code compile --check}.
     */
    static final int FAILED = 1;
    /** A scenario or plan file could not be loaded or compiled (a {@code ScenarioException}); nothing ran. */
    static final int SCENARIO = 2;
    /** The agent could not be found, reached, attached or kept: the reason is on stderr. */
    static final int UNREACHABLE = 3;
    /** The command line is wrong (unknown command or option, bad value, missing argument). */
    static final int USAGE = 64;
    /** A bug in the CLI or a pack: an unexpected exception, printed with its stack trace. */
    static final int INTERNAL = 70;

    private Exit() {
    }
}
