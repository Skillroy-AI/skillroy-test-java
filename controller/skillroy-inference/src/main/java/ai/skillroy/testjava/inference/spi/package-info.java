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
 * The inference gateway SPI (phase5-contracts.md 3): {@link ai.skillroy.testjava.inference.spi.InferenceGateway},
 * the only way any code of this repository talks to a model (B7), with its request and response types, typed content
 * parts, usage, batch types and failure kinds, plus the helpers {@link ai.skillroy.testjava.inference.spi.Structured}
 * (structured answers through the scenario module's codec) and {@link ai.skillroy.testjava.inference.spi.Prompts}
 * (canonical JSON for fingerprints and recordings). Schemas of records come from the scenario module's
 * {@code Schemas.ofRecord}. Provider-neutral in its types, Claude-shaped in its defaults.
 */
package ai.skillroy.testjava.inference.spi;
