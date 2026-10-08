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
 * Triage of a finished run (phase5-contracts.md 11, decision D78): {@link ai.skillroy.testjava.inference.triage.Triage}
 * reads a failed run's evidence after its deterministic classification and writes a label and a summary beside it,
 * never changing the outcome. The command that calls it is the CLI's {@code triage}.
 */
package ai.skillroy.testjava.inference.triage;
