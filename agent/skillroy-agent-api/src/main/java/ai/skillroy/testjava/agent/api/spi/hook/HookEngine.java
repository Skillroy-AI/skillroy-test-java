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

package ai.skillroy.testjava.agent.api.spi.hook;

import ai.skillroy.testjava.protocol.hook.HookHealth;
import ai.skillroy.testjava.protocol.hook.HookProbe;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.protocol.hook.HookStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The hook engine, for a domain module that wants hooks from Java rather than from a controller's catalogue
 * (phase6-contracts.md section 3.6, D118). Registered by the optional hooks module; found with
 * {@code ctx.spi().first(HookEngine.class)} or {@code AgentContext.hooks()}, both null when the module is not loaded.
 * Look it up in {@code onTargetReady} (after every module's {@code install}), never through {@code requires()}: the
 * framework must stay complete without the hooks module.
 *
 * <p>The methods do what the {@code hook.*} primitives do (section 4.2), with the same checks and the same events,
 * except that an install from a module is not gated by the dispatcher: a module is reviewed code. The engine still
 * refuses {@link #stub} while the agent is read-only, so that read-only mode disables STUB hooks whoever asks, and
 * records every stub it arms ({@code hooks.stubbed {id, armed, by: "spi"}}). Refusals are thrown as
 * {@code ai.skillroy.testjava.protocol.RpcException} with the primitives' codes ({@code INVALID_PARAMS},
 * {@code READ_ONLY}, {@code HOOK_CONFLICT}, {@code HOOK_VERIFY_FAILED}, {@code CAPABILITY_UNAVAILABLE}).
 *
 * <p>Thread-safe; every method is bounded. Call it from an agent thread (a primitive, a probe,
 * {@code onTargetReady}), never from the event-dispatch thread or an application thread: an install or a remove
 * retransforms classes, which stops the world for milliseconds per class.
 */
public interface HookEngine {

    /** The checks of section 3.3 for one spec; never installs, never appends, never loads the target class. */
    HookProbe probe(HookSpec spec);

    /**
     * Installs the specs that pass the checks of section 3.3 and reports every spec's state; idempotent by catalogue
     * hash (section 4.2): the same hash and spec set answers the current states and installs nothing.
     *
     * @param catalogueId the catalogue the specs belong to, not null
     * @param catalogueHash the catalogue's hash, not null
     * @param replace true: remove every installed hook first when another catalogue (or another spec set under the
     *     same hash) is installed; false: such an install is refused with {@code HOOK_CONFLICT}
     */
    List<HookStatus> install(List<HookSpec> specs, String catalogueId, String catalogueHash, boolean replace);

    /**
     * Arms a STUB hook with a value (converted once to the method's return type, section 3.4), or clears it.
     *
     * @return true when the hook now holds a value
     */
    boolean stub(String id, Object value, boolean clear);

    /** Removes the named hooks, or every hook for an empty list, with the restore and verification of section 3.5. */
    List<HookStatus> remove(List<String> ids);

    /** The health of the named hooks, or of every installed hook for an empty list (section 3.6). */
    Map<String, HookHealth> health(List<String> ids);

    /** {@code {id, hash, installedAt}} of the installed catalogue, or an empty map. */
    Map<String, Object> catalogue();

    /**
     * The catalogue deny patterns the engine holds (phase8-contracts.md section 6.6, D564, D575): every pattern a
     * {@code hook.install}'s {@code catalogue.deny} delivered, sorted, add-only for the engine instance's life. A
     * module's {@link #install} carries no catalogue and so no deny. Empty by default; the hooks module overrides it.
     *
     * @return unmodifiable, never null
     */
    default List<String> deny() {
        return Collections.emptyList();
    }
}
