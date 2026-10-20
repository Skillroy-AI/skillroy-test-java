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

package ai.skillroy.testjava.scenario.pack;

import ai.skillroy.testjava.protocol.Capability;

/**
 * An environment fact the running pack contributes to the run's capability matrix (phase8-contracts.md 9.1, gap 106,
 * decision D609): a setting, a table or a condition only the pack knows, which a scenario names in {@code requires:}
 * or in a guard like any other capability id. Declared through {@link DomainPack#capabilities()}.
 *
 * <p>Evaluated by every host that merges the matrix (a run, a session's open, {@code skillroy caps --pack},
 * {@code skillroy re-resolve}), after the controller-side contributors' merge and before {@code capabilities.json} is
 * archived and {@code requires} is checked; every declared id, always, not only those a plan names. The context
 * carries no bind mode, no inference settings and no gateway, so the answer cannot depend on the mode: a pack
 * capability is honoured in {@code replay} (decision D610). In the merged matrix its source is
 * {@code pack:<packId>}.
 *
 * <p>Instances are shared by every run in the JVM, so they must be stateless or thread-safe.
 */
public interface PackCapability {

    /**
     * {@code <packId>.<name>}: the pack's id, a dot, then {@code [a-z0-9]+([.-][a-z0-9]+)*}; at most 128 characters
     * (phase8-contracts.md 9.1.2). An id the agent's matrix or a controller-side contributor also holds refuses the
     * run (equality only: the pack's agent module shares the prefix).
     */
    String id();

    /** One line for the scenario schema, {@code skillroy caps} and the MCP catalogue; null (the default) = none. */
    default String description() {
        return null;
    }

    /**
     * {@link CheckScope#SCENARIO} (the default): evaluated in every run; {@link CheckScope#SUITE}: once per suite,
     * environment and agent start, the host's suite cache keeping the answer.
     */
    default CheckScope scope() {
        return CheckScope.SCENARIO;
    }

    /**
     * The state in this environment. Called on the host's thread, once per host (a run, a session open, a command);
     * read-only; bounded by its own reads (every {@code ctx.data()} query carries a timeout; the host adds none, as
     * for a {@link PreflightCheck}); never a model call, never a mutating primitive, never anything the step protocol
     * would wait on. The answer's {@code id()} must equal {@link #id()}; null means {@code UNAVAILABLE("no answer")}.
     * A reason is evidence every run archives and a model may read through the {@code caps} root: name the setting,
     * the table or the condition, never a row value.
     *
     * @param ctx the host's pre-flight context: the run's data channel, environment id and the running pack's
     *     settings (so that an overlay changes what an evaluation reads without replacing it), and the agent
     * @return {@code OK}, {@code DEGRADED(reason)} or {@code UNAVAILABLE(reason)}, with this id
     * @throws Exception an environmental failure: the id becomes {@code UNAVAILABLE("failed: <exception class>")},
     *     the exception's text going to the log and the run's record only (phase8-contracts.md 9.1.3)
     */
    Capability evaluate(PreflightCheck.PreflightContext ctx) throws Exception;
}
