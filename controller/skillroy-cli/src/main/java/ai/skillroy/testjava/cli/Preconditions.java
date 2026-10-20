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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataChannels;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.Preflight;
import ai.skillroy.testjava.scenario.SuiteCache;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PackSettings;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a command checks on the target before a job of its own (phase5-contracts.md 8.2 step 1, 10.2 step 1), as a run
 * checks it before its bind (phase2-contracts.md 8.2): a plan's {@code requires} against the capability matrix, its
 * launch properties against {@code jvm.properties}, and the pack's pre-flight checks.
 *
 * <p>Stateless; runs on the caller's thread with bounded calls.
 */
final class Preconditions {

    private static final long READ_TIMEOUT_MS = 10_000L;

    private Preconditions() {
    }

    /**
     * Why a plan cannot run on this target.
     *
     * @param kind PRECONDITION_FAILED for an unmet requirement or a launch property that differs, ENVIRONMENT when
     *     the properties cannot be read
     * @param code {@code REQUIRES}, {@code LAUNCH_PROPERTY} or {@code LAUNCH_PROPERTIES} (they cannot be read)
     * @param message one line
     */
    record Unmet(FailureKind kind, String code, String message) {
    }

    /**
     * The first unmet precondition of {@code plan}, or null when its requirements are met and its launch properties
     * are as written.
     *
     * @param caps the target's capability matrix, read once by the caller
     * @throws AgentLostException if the agent is gone
     */
    static Unmet check(Plan plan, CapabilityMatrix caps, AgentConnection agent) throws AgentLostException {
        List<String> unmet = caps.unmet(plan.requires());
        if (!unmet.isEmpty()) {
            return new Unmet(FailureKind.PRECONDITION_FAILED, "REQUIRES", "unmet requirements: "
                    + String.join("; ", unmet));
        }
        if (plan.launchProperties().isEmpty()) {
            return null;
        }
        RpcResult result;
        try {
            result = agent.call("jvm.properties", Map.of("names", new ArrayList<>(plan.launchProperties().keySet())),
                    CallOptions.defaults().withTimeoutMs(READ_TIMEOUT_MS));
        } catch (AgentRpcException e) {
            return new Unmet(FailureKind.ENVIRONMENT, "LAUNCH_PROPERTIES", "cannot read launch properties: "
                    + e.getMessage());
        }
        Map<?, ?> actual = result.value() instanceof Map<?, ?> v && v.get("properties") instanceof Map<?, ?> p
                ? p : Map.of();
        for (Map.Entry<String, String> e : plan.launchProperties().entrySet()) {
            Object value = actual.get(e.getKey());
            String text = value == null ? null : value instanceof String s ? s : Json.write(value);
            if (!e.getValue().equals(text)) {
                return new Unmet(FailureKind.PRECONDITION_FAILED, "LAUNCH_PROPERTY", "launch property " + e.getKey()
                        + ": expected '" + e.getValue() + "', actual " + (text == null ? "<not set>" : "'" + text
                        + "'"));
            }
        }
        return null;
    }

    /**
     * Runs the pack's pre-flight checks as a run does (phase8-contracts.md 9.3.3, through {@link Preflight}); the
     * failure of the first that fails, or null.
     *
     * @param suite the command's suite cache, shared with its executors; null = none
     * @param agentSession the connection's agent session ({@code AgentConnection.agentSession()}); null = nothing is
     *     cached
     * @throws AgentLostException if the agent is gone
     */
    static String preflight(DomainPack pack, AgentConnection agent, Credentials credentials, String envId,
                            SuiteCache suite, String agentSession) throws AgentLostException {
        List<RowMask> masks;
        try {
            masks = pack.rowMasks();
        } catch (RuntimeException e) {
            return "pack " + pack.id() + ": its pre-flight checks cannot be read: " + e;
        }
        try (DataChannels data = DataChannels.of(agent, credentials, masks == null ? List.of() : masks)) {
            return Preflight.run(pack, context(pack, agent, data, envId), suite, agentSession).failure();
        }
    }

    /**
     * What a command's pre-flight checks and the pack's capabilities read (phase2-contracts.md 6.1;
     * phase8-contracts.md 9.1.3): the command's connection and data channels, the environment id and the pack's
     * settings; no bind mode, no inference settings, no gateway.
     */
    static PreflightCheck.PreflightContext context(DomainPack pack, AgentConnection agent, DataChannel data,
                                                  String envId) {
        return new PreflightCheck.PreflightContext() {
            @Override
            public AgentConnection agent() {
                return agent;
            }

            @Override
            public DataChannel data() {
                return data;
            }

            @Override
            public String envId() {
                return envId;
            }

            @Override
            public PackSettings settings() {
                PackSettings settings = pack.settings();
                return settings == null ? PackSettings.EMPTY : settings;
            }
        };
    }
}
