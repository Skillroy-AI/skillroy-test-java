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

import java.time.Instant;
import java.util.Objects;

/**
 * A submitted batch (phase5-contracts.md 3.2, 8.2). Immutable.
 *
 * @param provider the gateway's provider
 * @param id the provider's batch id
 * @param submitted when it was submitted
 * @param requests how many requests it holds; results are matched to them by index
 */
public record BatchHandle(String provider, String id, Instant submitted, int requests) {

    /** @throws IllegalArgumentException if the request count is not positive */
    public BatchHandle {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(submitted, "submitted");
        if (requests < 1) {
            throw new IllegalArgumentException("a batch holds at least one request");
        }
    }
}
