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

package ai.skillroy.testjava.protocol;

import java.util.regex.Pattern;

/**
 * The rule that decides whether a key (a system property, an option, a fixture field) names a secret, shared by the
 * agent's redaction of JVM properties and arguments and the controller's masking of fixture secrets (contract
 * amendment A17), so that both sides hide the same things.
 *
 * <p>A key is secret when it contains, case-insensitively, a match of {@link #DEFAULT_KEYS}. Callers that add
 * keys of their own (the agent's {@code redact} option, module-supplied patterns) test those in addition.
 *
 * <p>Constants and a pure function only; usable from any thread.
 */
public final class SecretKeys {

    /** The keys that are always secret, as a regular expression matched case-insensitively anywhere in a key. */
    public static final String DEFAULT_KEYS =
            "password|passwd|pwd|secret|token|credential|licen[cs]e|api[-_.]?key|private[-_.]?key";

    /** What a secret value is replaced by. Never null, so that the presence of a secret can still be required. */
    public static final String REDACTED = "<redacted>";

    private static final Pattern DEFAULT = Pattern.compile(DEFAULT_KEYS, Pattern.CASE_INSENSITIVE);

    private SecretKeys() {
    }

    /** True if the key contains a match of {@link #DEFAULT_KEYS} (case-insensitive); false for null. */
    public static boolean looksSecret(String key) {
        return key != null && DEFAULT.matcher(key).find();
    }
}
