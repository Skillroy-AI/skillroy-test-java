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
 * The command line is wrong: an unknown command or option, a missing or malformed value, a wrong number of
 * arguments, or options that do not go together. Reported with a pointer to {@code --help} and exit code
 * {@link Exit#USAGE}.
 *
 * <p>Immutable; safe to share between threads.
 */
final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    /** @param message what is wrong, in terms of the command line the user typed */
    UsageException(String message) {
        super(message);
    }
}
