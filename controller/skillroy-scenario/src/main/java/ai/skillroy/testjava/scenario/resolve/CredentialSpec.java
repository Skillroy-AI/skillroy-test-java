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

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a slot declares with {@code credential: {realm, key}}: the slot's value is joined to the entry of
 * {@code credentials.yaml} whose id is the text of the value's field {@code key} (phase2-contracts.md 8.4).
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param realm the realm in {@code credentials.yaml}, {@code [a-z0-9-]+}
 * @param key the field of the slot value that holds the entry id
 */
public record CredentialSpec(String realm, String key) {

    private static final Pattern REALM = Pattern.compile("[a-z0-9-]+");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * @throws IllegalArgumentException if the realm or key is not a name
     */
    public CredentialSpec {
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(key, "key");
        if (!REALM.matcher(realm).matches()) {
            throw new IllegalArgumentException("a credential realm must match [a-z0-9-]+, found \"" + realm + "\"");
        }
        if (!FIELD.matcher(key).matches()) {
            throw new IllegalArgumentException("a credential key must match [A-Za-z_][A-Za-z0-9_]*, found \"" + key
                    + "\"");
        }
    }
}
