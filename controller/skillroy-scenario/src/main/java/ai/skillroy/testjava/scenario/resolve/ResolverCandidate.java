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
 * One candidate answer a {@link Resolver} offers. Built through {@link #of}; the canonical constructor is not API (a
 * later phase may add a component).
 *
 * <p>Immutable if {@code T} is; safe to share between threads then.
 *
 * @param value the typed value; never null (a null value is a pack defect, reported as ENVIRONMENT)
 * @param label a short identifying text for messages and provenance ({@code "staff 100"}); never a secret
 * @param confidence 1.0 for every tier (a) resolver
 * @param <T> the value type
 */
public record ResolverCandidate<T>(T value, String label, double confidence) {

    /** A candidate with confidence 1.0. */
    public static <T> ResolverCandidate<T> of(T value, String label) {
        return new ResolverCandidate<>(value, label, 1.0);
    }
}
