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
 * <p><b>No bindings file yet.</b> Scenario D's bindings on {@code seed-a} and {@code seed-b} are written by
 * {@code approve} against the recorded gateway, from recordings that only a live call with the model key can make
 * (14.2, 14.5); the recording wave of the Phase 5 build, after wave 2, makes the recordings and commits the bindings.
 * Until then {@link #HAS_BINDINGS} is false and the checks that need a committed bindings file leave scenario D out
 * through it: the JUnit glue's replay of the committed directory ({@link #withoutScenarioDUntilItHasBindings}) and the
 * bindings files' own checks ({@code BindingsUpToDateTest}, which also fails as soon as a file appears while the
 * constant still says none, so that the wave that commits them flips it). The other end-to-end checks name scenarios A
 * and B explicitly and never run D.
 *
 * <p>Stateless; safe from any thread.
 */
final class ScenarioD {

    static final String ID = "supervisor-return-llm-sql";
    static final String YAML = ID + ".yaml";

    /** The slot a model resolves; the other query slots keep scenario A's strategies. */
    static final String MODEL_SLOT = "supervisor";

    /**
     * Whether scenario D's bindings files are committed: false until the recording wave of the Phase 5 build (after
     * wave 2) writes them through the recorded gateway.
     */
    static final boolean HAS_BINDINGS = false;

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
     * committed directory does not run a scenario whose bindings the recording wave has not written yet; the
     * scenario directory (and with it the glue's default bindings root) stays the source's.
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
