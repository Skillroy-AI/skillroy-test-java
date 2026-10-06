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

package ai.skillroy.testjava.agent.api;

/**
 * Maps live host objects to short string handles ({@code "h12"}) so that the controller can refer to a component or
 * window across calls without the agent keeping it alive.
 *
 * <p>Implementations are thread-safe.
 */
public interface HandleRegistry {

    /** Same object -> same handle while it is alive (identity, weakly held). Handles look like "h12". */
    String handleFor(Object object, String kind);

    /** The object behind the handle; RpcException(STALE_HANDLE) if collected or unknown. */
    Object resolve(String handle);

    /** As {@link #resolve(String)}, and RpcException(INVALID_PARAMS) if the object is not of that type. */
    <T> T resolve(String handle, Class<T> type);

    /** Forgets the handle; no effect if unknown. */
    void release(String handle);
}
