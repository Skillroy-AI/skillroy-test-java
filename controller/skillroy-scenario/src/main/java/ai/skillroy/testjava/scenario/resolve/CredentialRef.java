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

/**
 * Which entry of {@code credentials.yaml} a bound slot was joined to. Written to bindings files and run outputs in
 * place of the secret fields, which never leave memory.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param realm the realm
 * @param id the entry id (the text of the slot value's key field)
 */
public record CredentialRef(String realm, String id) {

    /** @throws NullPointerException if a component is null */
    public CredentialRef {
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(id, "id");
    }

    /** {@code realm/id}, as messages name an entry. */
    @Override
    public String toString() {
        return realm + "/" + id;
    }
}
