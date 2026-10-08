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

package ai.skillroy.testjava.scenario.resolve;

/**
 * Inference settings that cannot be used (phase5-contracts.md 4.4, 5.5): an {@code inference.yaml} that is missing
 * where one was named, unreadable, malformed or against its grammar; a property or option with a value of the wrong
 * shape; or a combination that cannot work ({@code gateway: claude} without the model key in the environment,
 * {@code recorded} without a recordings directory). Found once per invocation, before any scenario runs (exit 64 in
 * the CLI, the {@code environment} test in the JUnit glue).
 *
 * <p>The message names the file and the line where known, and the key or property at fault; it <b>never contains a
 * value</b>: not a text from the file, not the value of a property, never anything of the model key. No cause is
 * attached that could carry one. Immutable.
 */
public final class SettingsException extends Exception {

    private static final long serialVersionUID = 1L;

    /** @param message what is wrong, without any value */
    public SettingsException(String message) {
        super(message);
    }
}
