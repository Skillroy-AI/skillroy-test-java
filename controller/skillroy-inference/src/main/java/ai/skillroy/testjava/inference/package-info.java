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
 * The AI layer of the controller (phase5-contracts.md): {@link ai.skillroy.testjava.inference.Gateways} builds the
 * gateway the settings name and shares it, reference-counted. The packages: {@code spi} (the gateway SPI),
 * {@code claude}, {@code recorded}, {@code scripted} (the three gateways), {@code prompt} (the mask and the
 * catalogue), {@code sql} (SqlGuard), {@code resolve} (the tier (b) resolver and the contributor), {@code budget}
 * (meters, prices, the ledger writer) and {@code tools} (primitives and verbs as tools). Nothing of it reaches a
 * target JVM.
 */
package ai.skillroy.testjava.inference;
