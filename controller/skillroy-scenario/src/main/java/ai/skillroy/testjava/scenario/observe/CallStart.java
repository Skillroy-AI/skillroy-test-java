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


package ai.skillroy.testjava.scenario.observe;

import java.util.List;

/**
 * A plan call starts (a step's call, or a cleanup call).
 *
 * @param stepId the step id, or {@code cleanup}
 * @param index the call's number in its step, from 1
 * @param primitive the primitive's name
 * @param correlationId the call's correlation id ({@code <scenarioId>/<stepId>/<n>}), or null
 * @param secretParams the names (never the values) of the parameters announced as secret (A34)
 * @param confirm whether the call is confirmed
 */
public record CallStart(String stepId, int index, String primitive, String correlationId, List<String> secretParams,
                        boolean confirm) {

    /** Copies the names. */
    public CallStart {
        secretParams = secretParams == null ? List.of() : List.copyOf(secretParams);
    }
}
