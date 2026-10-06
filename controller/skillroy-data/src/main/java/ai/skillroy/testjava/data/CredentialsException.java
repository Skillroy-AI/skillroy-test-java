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

package ai.skillroy.testjava.data;

/**
 * A {@code credentials.yaml} that cannot be used: missing where one was named, unreadable, malformed, against the
 * grammar, or accessible to other users (phase2-contracts.md 4). Found once per invocation, before the agent is
 * contacted.
 *
 * <p>The message names the file, the line where known and what is wrong, and <b>never contains a value</b> from the
 * file: not a secret, not a URL, not text the YAML reader quoted. No cause is attached that could carry one.
 * Immutable.
 */
public final class CredentialsException extends Exception {

    private static final long serialVersionUID = 1L;

    /** @param message what is wrong, without any value from the file */
    public CredentialsException(String message) {
        super(message);
    }
}
