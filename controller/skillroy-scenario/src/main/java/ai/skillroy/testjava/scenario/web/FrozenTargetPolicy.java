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

package ai.skillroy.testjava.scenario.web;

/**
 * What the CDP path does with a page target that does not answer the liveness probe (phase4-contracts.md sections 5.3
 * and 7.2). {@code FAIL} is the default; {@code RELOAD} asks the agent to reload the browser once before a second
 * probe; {@code CLOSE} is parsed and refused in Phase 4 (D52).
 */
public enum FrozenTargetPolicy {
    FAIL, RELOAD, CLOSE
}
