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
 * Ends a command with an exit code and a one-line reason for stderr. Lets a command stop from deep inside a helper
 * (connection selection, pack loading) without each caller mapping errors to exit codes.
 *
 * <p>Immutable; safe to share between threads.
 */
final class CliException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int exitCode;

    /**
     * @param exitCode one of {@link Exit}
     * @param message the reason, printed after the command name; must never contain the token
     */
    CliException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    /** As {@link #CliException(int, String)}, keeping the underlying failure as the cause. */
    CliException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /** The process exit code. */
    int exitCode() {
        return exitCode;
    }
}
