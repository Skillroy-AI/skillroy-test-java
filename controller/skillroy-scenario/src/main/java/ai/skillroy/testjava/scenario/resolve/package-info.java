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

/**
 * The resolution SPI (phase2-contracts.md 6): everything a pack implements or constructs to turn a scenario's typed
 * queries into reviewed, verified values: {@link ai.skillroy.testjava.scenario.resolve.Query},
 * {@link ai.skillroy.testjava.scenario.resolve.Resolver}, {@link ai.skillroy.testjava.scenario.resolve.Verifier},
 * {@link ai.skillroy.testjava.scenario.resolve.Oracle}, its
 * {@link ai.skillroy.testjava.scenario.resolve.CrossCheck} (phase3-contracts.md 5.1),
 * {@link ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue}, the fingerprint and stored-table types, and the
 * value types the engine builds ({@link ai.skillroy.testjava.scenario.resolve.Resolution},
 * {@link ai.skillroy.testjava.scenario.resolve.Provenance}). The engine that uses them ({@code Binder},
 * {@code ResolutionEngine}) lives in {@code ai.skillroy.testjava.scenario}.
 *
 * <p>Phase 5 (phase5-contracts.md 5.5, 8.3) adds the seams through which the controller's inference module brings a
 * model to a bind without this module depending on it: {@link ai.skillroy.testjava.scenario.resolve.ResolverContributor}
 * (found through {@code ServiceLoader}, never consulted in {@code replay}), the string-only
 * {@link ai.skillroy.testjava.scenario.resolve.InferenceSettings} it is configured with, and
 * {@link ai.skillroy.testjava.scenario.resolve.BatchableResolver}, whose model call {@code re-resolve} defers into a
 * batch.
 */
package ai.skillroy.testjava.scenario.resolve;
