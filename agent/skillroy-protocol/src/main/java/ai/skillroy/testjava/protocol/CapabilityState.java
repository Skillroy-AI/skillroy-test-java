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

package ai.skillroy.testjava.protocol;

/**
 * Probed state of one capability. Scenarios declare the capabilities they need, and the controller refuses to run a
 * scenario whose needs are UNAVAILABLE instead of letting it fail obscurely half-way.
 */
public enum CapabilityState {
    /** Works as designed. */
    OK,
    /** Works with a weaker strategy; calls still run. */
    DEGRADED,
    /** Does not work in this target; calls that need it fail fast with {@code CAPABILITY_UNAVAILABLE}. */
    UNAVAILABLE
}
