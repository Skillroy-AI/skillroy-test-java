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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.RedactionKeys;

import java.util.regex.Pattern;

/**
 * The lending desk's own secret keys (contract amendment A21): a system property or option named {@code lending.pin},
 * or any {@code lending.<…>.pin} such as {@code lending.supervisor.pin} (case-insensitive). Why a module rule: the
 * shared rule ({@code SecretKeys}) knows passwords, tokens and keys, but not a desk PIN, and only the module knows
 * which of its application's keys hold one; with it registered, {@code jvm.properties}, {@code jvm.info} and
 * {@code agent.info} show such a value as {@code <redacted>}. The desk itself reads no such property: the sample
 * registers the rule to show the extension point, and the end-to-end tests launch the desk with
 * {@code -Dlending.pin=…} to prove that the agent hides it.
 *
 * <p>Immutable; the core may call {@link #secretKeys()} from any thread, at every call that redacts.
 */
final class LendingSecrets implements RedactionKeys {

    /**
     * {@code lending.pin} and {@code lending.<segments>.pin}; a segment is letters, digits, {@code _} or {@code -}.
     * {@code lending.pinned} and {@code lending.spin} are not secret.
     */
    static final String KEYS = "^lending\\.(?:[A-Za-z0-9_-]+\\.)*pin$";

    private static final Pattern PATTERN = Pattern.compile(KEYS, Pattern.CASE_INSENSITIVE);

    @Override
    public Pattern secretKeys() {
        return PATTERN;
    }
}
