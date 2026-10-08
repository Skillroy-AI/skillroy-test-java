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

package ai.skillroy.testjava.inference.spi;

import java.util.Objects;

/**
 * Where a batch stands (phase5-contracts.md 3.2). Immutable.
 *
 * @param state in progress, ended or being cancelled
 * @param succeeded requests answered
 * @param errored requests that failed
 * @param expired requests the provider did not process in time
 * @param canceled requests cancelled
 * @param processing requests still being processed
 */
public record BatchStatus(State state, int succeeded, int errored, int expired, int canceled, int processing) {

    /** The batch's state. */
    public enum State {
        /** Requests are being processed. */
        IN_PROGRESS,
        /** Every request has a result; collect them. */
        ENDED,
        /** A cancel was asked and is in progress. */
        CANCELING
    }

    /** @throws NullPointerException if the state is null */
    public BatchStatus {
        Objects.requireNonNull(state, "state");
    }
}
