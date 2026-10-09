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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.StepResult;
import ai.skillroy.testjava.scenario.observe.AwaitRecord;
import ai.skillroy.testjava.scenario.observe.CallEnd;
import ai.skillroy.testjava.scenario.observe.CallStart;
import ai.skillroy.testjava.scenario.observe.RunObserver;
import ai.skillroy.testjava.scenario.observe.RunStart;
import ai.skillroy.testjava.scenario.observe.ScenarioStart;
import ai.skillroy.testjava.scenario.observe.SessionStart;
import ai.skillroy.testjava.scenario.observe.StepStart;
import ai.skillroy.testjava.scenario.observe.ToolCallRecord;
import ai.skillroy.testjava.scenario.observe.TurnRecord;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP server's telemetry (phase6-contracts.md 10.2, 13.1 item 12): each {@code tools/call} is reported to the
 * session's observer under the session lock, with the client's {@code _meta.traceparent} when it is a valid W3C one
 * and none when it is malformed; the session's own start, steps and records reach the same observer.
 */
class McpTelemetryTest {

    @TempDir
    Path dir;

    /** What the server reported, in order. */
    private static final class Recording implements RunObserver {

        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<String> parents = Collections.synchronizedList(new ArrayList<>());
        final List<ToolCallRecord> ended = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void runStarted(RunStart run) {
        }

        @Override
        public void scenarioStarted(ScenarioStart scenario) {
            calls.add("scenarioStarted");
        }

        @Override
        public void scenarioEnded(RunResult result) {
            calls.add("scenarioEnded");
        }

        @Override
        public void bindEvent(String type, Map<String, Object> fields) {
        }

        @Override
        public void scenarioRecord(String type, Map<String, Object> fields) {
        }

        @Override
        public void stepStarted(StepStart step) {
        }

        @Override
        public void stepEnded(StepResult result) {
        }

        @Override
        public void callStarted(CallStart call) {
            calls.add("callStarted");
        }

        @Override
        public void callEnded(CallEnd call) {
            calls.add("callEnded");
        }

        @Override
        public void await(AwaitRecord sync) {
        }

        @Override
        public void modelCall(CostLedger.Entry entry) {
        }

        @Override
        public void sessionStep(StepOutcome outcome) {
            calls.add("sessionStep");
        }

        @Override
        public void sessionTurn(TurnRecord turn) {
        }

        @Override
        public void toolCall(ToolCallRecord record) {
            calls.add("toolCall");
            ended.add(record);
        }

        @Override
        public String currentTraceparent() {
            return null;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public void sessionStarted(SessionStart session) {
            calls.add("sessionStarted");
        }

        @Override
        public void sessionStepStarted(StepStart step) {
            calls.add("sessionStepStarted");
        }

        @Override
        public void toolCallStarted(String tool, String traceparent) {
            calls.add("toolCallStarted " + tool);
            parents.add(traceparent);
        }
    }

    @Test
    void eachToolCallIsReportedWithTheClientsTraceParent() throws Exception {
        Recording observer = new Recording();
        String parent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        try (McpFixture fx = new McpFixture(dir)) {
            ServerOptions base = fx.options().build();
            fx.start(ServerOptions.builder(base.agent(), base.pack()).session(base.session().withObserver(observer))
                    .outDir(base.outDir()).clock(base.clock()).build());
            McpSchema.CallToolResult catalogue = fx.client.callTool(new McpSchema.CallToolRequest(
                    "skillroy_catalogue", Map.of(), Map.of("traceparent", parent)));
            assertFalse(Boolean.TRUE.equals(catalogue.isError()));
            fx.client.callTool(new McpSchema.CallToolRequest("skillroy_catalogue", Map.of(),
                    Map.of("traceparent", "00-not-a-trace-01")));
            fx.call("skillroy_catalogue", Map.of());
        }
        assertEquals("sessionStarted", observer.calls.get(0), "the session opens with the server");
        assertEquals(List.of(parent), observer.parents.subList(0, 1), "the client's trace continues");
        assertNull(observer.parents.get(1), "a malformed traceparent is dropped");
        assertNull(observer.parents.get(2), "none sent, none passed");
        assertEquals(3, observer.ended.stream().filter(r -> r.tool().equals("skillroy_catalogue")).count());
        assertTrue(observer.ended.stream().allMatch(r -> r.sessionId().equals("sess-1") && r.durationMs() >= 0));
        assertFalse(observer.ended.get(0).error());
        assertEquals(parent, SkillroyMcpServer.inboundTraceparent(Map.of("traceparent", parent)));
        assertNull(SkillroyMcpServer.inboundTraceparent(Map.of("traceparent", 7L)));
        assertNull(SkillroyMcpServer.inboundTraceparent(null));
    }
}
