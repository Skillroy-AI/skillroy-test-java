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

package ai.skillroy.testjava.inference.claude;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two assumptions a live test of this module begins with (phase5-contracts.md 2.3, D111): the property the
 * profile {@code inference-live} sets for the test JVMs, then the model key in the environment. Without the profile,
 * or with it and without the key, the test is reported skipped with the reason, never failed.
 */
final class Live {

    /** The system property the profile {@code inference-live} sets ({@code -Pinference-live}). */
    static final String PROPERTY = "skillroy.inference.live";

    private Live() {
    }

    /**
     * Skips the calling test unless the profile is active ("the profile inference-live is not active") and the model
     * key is set and not blank ("no model key"), in that order. Of the key only its presence is looked at: its value
     * is never kept, printed or compared.
     */
    static void assumeLive() {
        assumeTrue(Boolean.getBoolean(PROPERTY), "the profile inference-live is not active");
        assumeTrue(keySet(), "no model key");
    }

    private static boolean keySet() {
        String key = System.getenv(ClaudeGateway.KEY_VARIABLE);
        return key != null && !key.isBlank();
    }
}
