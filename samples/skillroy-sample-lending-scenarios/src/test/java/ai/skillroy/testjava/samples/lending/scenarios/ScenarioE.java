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

import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindingsFile;

import java.nio.file.Path;
import java.util.List;

/**
 * Scenario E, {@code return-within-limit-observed} (phase6-contracts.md 6.3, decision D131): a return within the
 * signed-on assistant's return limit, with three expectations on the base fact root {@code hooks}, each guarded by
 * {@code hook.limit.applied}. PASS with the pack's hooks installed, DEGRADED (reported as skipped) without, from one
 * YAML, one plan and one bindings file per seed (hooks never enter the fingerprint, D128).
 */
final class ScenarioE {

    static final String ID = "return-within-limit-observed";
    static final String HOOK = "hook.limit.applied";
    /** The query slots in file order. */
    static final List<String> QUERY_SLOTS = List.of("systemMax", "item", "assistant", "keys");
    /** The three guarded expectations' paths, as the skipped-expectation texts name them. */
    static final List<String> GUARDED = List.of("hooks[id=limit.applied].count", "hooks[id=limit.applied].last.args.0",
            "hooks[id=limit.applied].last.returned");

    private ScenarioE() {
    }

    static Path yaml() {
        return ItEnv.scenario(ID + ".yaml");
    }

    static Path plan() {
        return ItEnv.plan(ID);
    }

    static Path committedBindings(Env env) {
        return BindingsFile.locate(ScenarioA.bindingsRoot(), env.id, ID);
    }
}
