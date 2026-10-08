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

import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The assumptions the sample's live tests begin with (phase5-contracts.md 2.3, decision D111): the system property
 * their Maven profile sets for the test JVMs, then the model key in the environment, so that a build without the
 * profile, or with the profile and no key, reports them skipped, never failed, and calls no model.
 * {@link InferenceLiveIT} makes both ({@link #assumeLive()}); {@link ScenarioDRecordIT} makes its own profile's and
 * then the key's ({@link #assumeKey()}).
 *
 * <p>Of the key only whether the variable is set and not blank is looked at: its value is never kept, printed or
 * compared, and it reaches a model only through the environment the gateway (or a launcher subprocess) inherits.
 *
 * <p>Stateless; safe from any thread.
 */
final class Live {

    /** The system property the profile {@code inference-live} sets for the test JVMs. */
    static final String LIVE_PROPERTY = "skillroy.inference.live";

    private Live() {
    }

    /** The two assumptions of the profile {@code inference-live}, in order: its property, then the key. */
    static void assumeLive() {
        assumeTrue(Boolean.getBoolean(LIVE_PROPERTY), "the profile inference-live is not active");
        assumeKey();
    }

    /** The model key ({@value InferenceSettings#KEY_VARIABLE}) is set in the environment and not blank. */
    static void assumeKey() {
        assumeTrue(keySet(), "no model key");
    }

    /** Whether the key variable is set and not blank; nothing of its value leaves this method. */
    private static boolean keySet() {
        String value = System.getenv(InferenceSettings.KEY_VARIABLE);
        return value != null && !value.isBlank();
    }
}
