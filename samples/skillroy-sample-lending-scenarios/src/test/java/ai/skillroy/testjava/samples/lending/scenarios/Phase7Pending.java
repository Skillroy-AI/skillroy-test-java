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

package ai.skillroy.testjava.samples.lending.scenarios;

import java.util.Set;

/**
 * The one marker of the Phase 7 integration order (the session notes' contract-notes, "Integration order"): what VC's
 * step 1 committed but cannot run before the later steps land. Step 2 (VS: the desk's number pad, the agent module's
 * regions and masks) lets {@link #NEEDS_DESK} run; step 2b (the pack 0.6.0, every plan's {@code packVersion}, and the
 * {@code approve} run in the image on both Javas and both seeds) commits {@code keypad-image}'s bindings files and
 * baselines and empties {@link #NEEDS_BASELINES}. Delete this class with step 2b; every check that consults it then
 * runs in full.
 */
final class Phase7Pending {

    /** Seed-A scenarios that need VS's desk (the number pad): left out of LoadModeChecks and the glue until step 2. */
    static final Set<String> NEEDS_DESK = Set.of("sign-on-by-number-pad");

    /**
     * Scenarios whose bindings files and baselines come in step 2b: left out of ResolutionChecks, BindingsUpToDateTest
     * and the glue until then (their plans are committed and checked from step 1).
     */
    static final Set<String> NEEDS_BASELINES = Set.of("keypad-image");

    private Phase7Pending() {
    }

    /** Whether a scenario is held back until a later step of the integration. */
    static boolean pending(String scenarioId) {
        return NEEDS_DESK.contains(scenarioId) || NEEDS_BASELINES.contains(scenarioId);
    }
}
