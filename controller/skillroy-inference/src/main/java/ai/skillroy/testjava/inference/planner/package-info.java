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
 * The planner of phase5-contracts.md 10 (decision D77): {@link ai.skillroy.testjava.inference.planner.Planner} runs
 * the Claude Code CLI headless against the MCP server with the scenario JSON schema as its output constraint, checks
 * and compiles what comes back (repairing it through {@code --resume}), and writes the scenario's YAML, plan,
 * authoring record and the planner's masked envelopes (D112) for a person to review. The command that calls it, and
 * the checks after it, are the CLI's {@code author}.
 */
package ai.skillroy.testjava.inference.planner;
