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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A controller-side module's capabilities, merged into the run's matrix beside the agent's (phase7-contracts.md 8.5,
 * decisions D326, D327, D348, D354; design 2.4, 3.10): found through {@code ServiceLoader} on the pack class loader; a
 * pure function of its context; never a model call.
 *
 * <p>Rules of the merge ({@code CapabilityMerge}): every contributed id starts with {@code inference.}; an agent that
 * declares an id with that prefix is refused; an id the agent also declares, or that two contributors answer, is a
 * conflict naming both; in {@code replay} every contributed id is forced to {@code UNAVAILABLE(reason="replay")}
 * whatever the contributor answered. The merge happens once per host: in {@code PlanExecutor} after step 0b (no host
 * features), in {@code ExecutorSession.open} (with {@link SessionOptions#host()}) and in the MCP server's start.
 */
public interface CapabilityContributor {

    /** The contributor's id ({@code "inference"}, {@code "vision"}): the matrix remembers {@code controller:<id>}. */
    String id();

    /**
     * The capabilities this module contributes under this context, keyed by id; every id starts with
     * {@code inference.}. Called once per host, on the host's thread; must not call a model or the agent.
     */
    Map<String, Capability> capabilities(CapabilityContext ctx);

    /**
     * What a contributor may know when it answers.
     *
     * @param settings the run's or the session's inference settings; null means {@link InferenceSettings#none()}
     * @param mode the bind mode of the run ({@code live} for a session)
     * @param agent the agent's matrix as read after step 0b (or after the session's hook install)
     * @param host the features the host declares ({@link SessionOptions#host()}: {@code "vision-session"},
     *     {@code "vision-tier:<v1|v2|v3>"}); empty for {@code PlanExecutor}
     * @param pack the running pack (the {@code inference.sql} reason needs its allow-lists)
     */
    record CapabilityContext(InferenceSettings settings, BindMode mode, CapabilityMatrix agent, Set<String> host,
                             DomainPack pack) {

        /** Fills defaults and copies the host features. */
        public CapabilityContext {
            settings = settings == null ? InferenceSettings.none() : settings;
            Objects.requireNonNull(mode, "mode");
            agent = agent == null ? new CapabilityMatrix(Map.of()) : agent;
            host = host == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(host));
        }
    }
}
