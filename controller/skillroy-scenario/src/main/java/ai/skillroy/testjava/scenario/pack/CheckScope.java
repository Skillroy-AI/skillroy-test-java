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

package ai.skillroy.testjava.scenario.pack;

/**
 * How often a pack's pre-flight check ({@link PreflightCheck#scope()}) or capability ({@link PackCapability#scope()})
 * is evaluated (phase8-contracts.md 9.1, 9.3, decision D616).
 */
public enum CheckScope {

    /** Once per run: the behaviour of 0.1.0, and the default. */
    SCENARIO,

    /**
     * Once per suite, environment and agent start: the host's {@code SuiteCache} keeps the answer, ok or the failure
     * reason, for the rest of the suite (phase8-contracts.md 9.3.2). A connection that knows no agent session is
     * never cached for, so such an item runs per scenario.
     */
    SUITE
}
