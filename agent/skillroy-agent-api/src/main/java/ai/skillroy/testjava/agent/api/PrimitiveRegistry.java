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

import java.util.List;

/**
 * The agent's catalogue of primitives: the dispatcher resolves JSON-RPC methods here and {@code GET /schema} lists
 * it. Modules register during install and unregister during uninstall.
 *
 * <p>Implementations are thread-safe.
 */
public interface PrimitiveRegistry {

    /** Adds a primitive under its spec name; IllegalStateException on duplicate name. */
    void register(Primitive primitive);

    /** Removes the primitive with that name; no effect if absent. */
    void unregister(String name);

    /** The primitive with that name; null if absent. */
    Primitive find(String name);

    /** Specs of all registered primitives, sorted by name; a snapshot. */
    List<PrimitiveSpec> list();
}
