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

import java.io.Console;

/**
 * Where {@code approve} asks its question (phase2-contracts.md 12.1): a seam, so that tests can script it and the
 * terminal test does not depend on one JDK's behaviour of {@link System#console()}. The default reports
 * {@link #interactive()} only when a console exists and reports itself as a terminal; without one, {@code run --mode
 * approve} and {@code resolve} need {@code --yes} and otherwise fail with exit code 64 before anything runs, so an
 * unattended job never blocks on a question.
 *
 * <p>Used from the command's thread only.
 */
interface Terminal {

    /** True when a person can answer. */
    boolean interactive();

    /** Prints {@code prompt} and reads one line; null at end of input (which counts as no). */
    String readLine(String prompt);

    /** The process's console, when it is a terminal. */
    static Terminal system() {
        return new Terminal() {
            @Override
            public boolean interactive() {
                Console console = System.console();
                return console != null && console.isTerminal();
            }

            @Override
            public String readLine(String prompt) {
                Console console = System.console();
                return console == null ? null : console.readLine("%s", prompt);
            }
        };
    }

    /** A terminal nobody answers. */
    Terminal NONE = new Terminal() {
        @Override
        public boolean interactive() {
            return false;
        }

        @Override
        public String readLine(String prompt) {
            return null;
        }
    };
}
