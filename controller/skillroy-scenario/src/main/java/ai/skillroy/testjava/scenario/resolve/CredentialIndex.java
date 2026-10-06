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
 * Whether {@code credentials.yaml} has an entry, without access to its fields: what a resolver may know about
 * credentials ("credentials early", phase2-contracts.md 6.4). Thread-safe.
 */
@FunctionalInterface
public interface CredentialIndex {

    /** True when the realm has an entry with this id (ids are compared as text). */
    boolean has(String realm, String id);
}
