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

package ai.skillroy.testjava.agent.api.spi;

/**
 * "The application is initialised enough that domain singletons may be touched." Polled every 250 ms.
 * Lets the agent start before the application and still know when domain primitives become safe.
 *
 * <p>Polled on an agent worker thread until it first reports ready; must be cheap and must not block.
 */
public interface ReadinessSource {

    /** Stable id, reported while the target is not ready. */
    String id();

    /** True once domain singletons may be touched. Once true, it should stay true. */
    boolean isReady();

    /** What this source is waiting for, for logs and {@code target.ready} reasons. */
    String describe();
}
