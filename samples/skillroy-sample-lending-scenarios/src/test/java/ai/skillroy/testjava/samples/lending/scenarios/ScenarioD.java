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

import ai.skillroy.testjava.junit.ScenarioSource;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindingsFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sample scenario D, {@code supervisor-return-llm-sql} (phase5-contracts.md 14.2): scenario A with its supervisor
 * found by a model (tier (b), strategy {@code llm-sql}). Where its files are, and which of them exist yet.
 *
 * <p><b>Its bindings files</b> on {@code seed-a} and {@code seed-b} are written by {@code approve} against the recorded
 * gateway (14.2, 14.5), with {@code approval.by} {@value ScriptedAnswers#APPROVED_BY}: the recordings they come from
 * are stand-ins made from correct scripted answers ({@link ScriptedAnswers}, provider {@code scripted}), because no
 * live call with the model key could be made when they were written; {@code ScenarioDRecordedTest} regenerates the
 * stand-ins and the files from them ({@code -Dskillroy.inference.fixtures.update=true}), and how they are re-recorded
 * live, with what that changes, is in the recordings' README (the profile {@code inference-record} is specified, not
 * built: phase5-contracts.md D110). {@link #HAS_BINDINGS} says that they are committed: the JUnit
 * glue's replay of the committed directory runs scenario D with A and B ({@link #withoutScenarioDUntilItHasBindings}
 * passes the directory through), and the bindings files' own checks ({@code BindingsUpToDateTest}) cover its files.
 *
 * <p>Stateless; safe from any thread.
 */
final class ScenarioD {

    static final String ID = "supervisor-return-llm-sql";
    static final String YAML = ID + ".yaml";

    /** The slot a model resolves; the other query slots keep scenario A's strategies. */
    static final String MODEL_SLOT = "supervisor";

    /**
     * Whether scenario D's bindings files are committed: true since the recording wave of the Phase 5 build
     * (2026-10-08) wrote them through the recorded gateway, from the scripted stand-in recordings.
     */
    static final boolean HAS_BINDINGS = true;

    private ScenarioD() {
    }

    /** {@code scenarios/<id>.yaml} (committed). */
    static Path yaml() {
        return ItEnv.scenario(YAML);
    }

    /** {@code plan/<id>.plan.json} (committed). */
    static Path plan() {
        return ItEnv.plan(ID);
    }

    /** {@code bindings/<env>/<id>.bindings.json}: committed once {@link #HAS_BINDINGS} is true. */
    static Path committedBindings(Env env) {
        return BindingsFile.locate(ScenarioA.bindingsRoot(), env.id, ID);
    }

    /**
     * {@code source} without scenario D's file while {@link #HAS_BINDINGS} is false, so that a replay of the
     * committed directory does not run a scenario whose bindings are not written; the scenario directory (and with it
     * the glue's default bindings root) stays the source's. With the files committed, {@code source} itself.
     */
    static ScenarioSource withoutScenarioDUntilItHasBindings(ScenarioSource source) {
        if (HAS_BINDINGS) {
            return source;
        }
        return new ScenarioSource() {
            @Override
            public List<Entry> entries() throws IOException {
                return source.entries().stream().filter(entry -> !entry.name().equals(YAML)).toList();
            }

            @Override
            public Path scenarioDirectory() {
                return source.scenarioDirectory();
            }
        };
    }

    /** True when either seed's bindings file of scenario D exists. */
    static boolean anyBindingsCommitted() {
        for (Env env : Env.values()) {
            if (Files.exists(committedBindings(env))) {
                return true;
            }
        }
        return false;
    }
}
