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
 * Service provider interfaces through which a domain module teaches the generic agent about one target application:
 * readiness, threads, state, widget names, locators, actions, dialogs, data sources, devices, production detection,
 * masking and secret keys for redaction. Implementations are registered with {@code AgentContext.spi()} during
 * {@code AgentModule.install}.
 */
package ai.skillroy.testjava.agent.api.spi;
