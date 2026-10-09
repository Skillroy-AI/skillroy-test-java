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


package ai.skillroy.testjava.scenario.observe;

/**
 * The start of a CLI invocation or a JUnit stream: the root span's facts (phase6-contracts.md 10.4).
 *
 * @param command the CLI command ({@code run}, {@code session}, {@code mcp}, {@code re-resolve}, {@code triage},
 *     {@code author}) or {@code junit}; the root span is named {@code skillroy <command>}
 * @param suite the suite name (the JUnit report's), or null
 * @param bindMode the bind mode's label, or null
 * @param envId the environment id, or null
 * @param cliVersion the CLI's (or the JUnit glue's) version; {@code (development build)} when unknown
 * @param target what is known of the target, or null when no agent was reached
 * @param environment a one-line, masked message about the environment (why it could not be used), or null
 */
public record RunStart(String command, String suite, String bindMode, String envId, String cliVersion,
                       Target target, String environment) {

    /**
     * The target's facts, from the connection and {@code agent.info}; every field may be unknown.
     *
     * @param pid the target's pid, 0 when unknown
     * @param javaVersion the target's {@code java.version}
     * @param loadMode {@code JAVAAGENT}, {@code ATTACH} or {@code EMBEDDED}
     * @param agentVersion the agent's version
     * @param protocol the agent's protocol version
     * @param session the agent's session id (an identifier of the connection, not a secret)
     */
    public record Target(long pid, String javaVersion, String loadMode, String agentVersion, String protocol,
                         String session) {
    }
}
