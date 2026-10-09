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
 * The controller side of hooks (phase6-contracts.md 5, 6): a domain pack's hook catalogue ({@link
 * ai.skillroy.testjava.scenario.hooks.HookCatalogue}, read from YAML by {@link
 * ai.skillroy.testjava.scenario.hooks.HookCatalogues}), the run-level switch ({@link
 * ai.skillroy.testjava.scenario.hooks.HooksMode}) and the installer that puts a catalogue on a target once, idempotent
 * by its hash ({@link ai.skillroy.testjava.scenario.hooks.HookInstaller}). The agent never reads YAML: it receives the
 * enabled entries as the protocol's {@code HookSpec}s.
 */
package ai.skillroy.testjava.scenario.hooks;
