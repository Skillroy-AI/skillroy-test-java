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

/**
 * One batch result, matched to its request by index (the gateway keys the provider's custom ids itself; results are
 * never matched by position). Exactly one of {@code response} and {@code error} is set. Immutable.
 *
 * @param index the request's index in the submitted list
 * @param response the answer, or null when the request did not succeed
 * @param error why the request did not succeed, or null when it did
 */
public record BatchResult(int index, ModelResponse response, InferenceException error) {

    /** @throws IllegalArgumentException unless exactly one of response and error is set, or for a negative index */
    public BatchResult {
        if (index < 0) {
            throw new IllegalArgumentException("a batch result's index is not negative");
        }
        if ((response == null) == (error == null)) {
            throw new IllegalArgumentException("a batch result has a response or an error, not both");
        }
    }

    /** True when the request succeeded. */
    public boolean succeeded() {
        return response != null;
    }
}
