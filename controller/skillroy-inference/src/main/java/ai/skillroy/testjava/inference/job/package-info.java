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
 * Batch re-resolution, the nightly job of phase5-contracts.md 8 (decisions D74, D75): {@link
 * ai.skillroy.testjava.inference.job.ReResolver} binds every scenario of an environment in {@code re-resolve} mode,
 * one batch per round of parked tier (b) slots, within a job budget, writes the bindings files {@code APPROVED} by the
 * job, and returns the {@link ai.skillroy.testjava.inference.job.ReResolveReport} a person reviews as a pull request.
 * The command that calls it is the CLI's; the pull request is the pipeline's ({@code ci/examples}).
 */
package ai.skillroy.testjava.inference.job;
