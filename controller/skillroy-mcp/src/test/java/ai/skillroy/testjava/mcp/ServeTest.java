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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.SessionOpenException;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 9.2, 9.6 and 13.4: {@code serve} runs until the client closes the stream (exit 0) or the agent
 * is lost (exit 3, after the call in flight was answered with the loss as a tool error); a call in flight when the
 * client closes is still answered; start-up errors are exceptions, never a half-started server. Spoken in raw JSON-RPC
 * lines, as a client on stdio writes them.
 */
class ServeTest {

    @TempDir
    Path dir;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void stop() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "the test's threads ended");
    }

    /** A client that writes lines and reads the server's answers by id, waiting at most 20 s for each line. */
    private static final class LineClient {
        final TestPipe toServer = new TestPipe();
        final TestPipe fromServer = new TestPipe(McpFixture.WAIT);
        private final BufferedReader reader = new BufferedReader(new InputStreamReader(fromServer.in(),
                StandardCharsets.UTF_8));

        void send(String json) throws Exception {
            toServer.out().write((json + "\n").getBytes(StandardCharsets.UTF_8));
        }

        void initialize() throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"raw\",\"version\":\"1\"}}}");
            Map<String, Object> init = answer(1);
            assertTrue(init.containsKey("result"), init.toString());
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        void callTool(int id, String name, String argsJson) throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                    + "\",\"arguments\":" + argsJson + "}}");
        }

        /** The next message with this id (other messages, such as notifications, are skipped). */
        Map<String, Object> answer(long id) throws Exception {
            String line;
            while ((line = reader.readLine()) != null) {
                Map<String, Object> message = Json.parseObject(line);
                if (message.get("id") instanceof Number n && n.longValue() == id) {
                    return message;
                }
            }
            throw new AssertionError("no answer with id " + id + "; the server wrote: " + fromServer.written());
        }
    }

    private static String toolText(Map<String, Object> answer) {
        Map<?, ?> result = (Map<?, ?>) answer.get("result");
        return String.valueOf(((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text"));
    }

    private static boolean isError(Map<String, Object> answer) {
        return Boolean.TRUE.equals(((Map<?, ?>) answer.get("result")).get("isError"));
    }

    @Test
    void serveEndsWithZeroWhenTheClientClosesTheStream() throws Exception {
        McpFixture fx = new McpFixture(dir);
        LineClient client = new LineClient();
        Future<Integer> served = pool.submit(() -> SkillroyMcpServer.serve(fx.options().build(),
                client.toServer.in(), client.fromServer.out(), fx.log));
        client.initialize();
        client.callTool(2, "desk_state", "{}");
        assertTrue(toolText(client.answer(2)).contains("\"IDLE\""));
        client.toServer.closeWriter();
        assertEquals(SkillroyMcpServer.EXIT_CLIENT_CLOSED, served.get(20, TimeUnit.SECONDS));
        List<String> journal = Files.readAllLines(fx.out.resolve("runs").resolve("sess-1").resolve(StepJournal.FILE));
        assertTrue(journal.get(journal.size() - 1).contains("\"session-end\""), "the session was closed");
        assertTrue(fx.logText().contains("ended: the client closed the stream"), fx.logText());
        client.fromServer.closeWriter();
    }

    @Test
    void serveEndsWithThreeWhenTheAgentIsLostAfterAnsweringTheCallInFlight() throws Exception {
        McpFixture fx = new McpFixture(dir);
        fx.desk.agent.loseOn("desk.poke");
        LineClient client = new LineClient();
        Future<Integer> served = pool.submit(() -> SkillroyMcpServer.serve(fx.options().mutating(true).build(),
                client.toServer.in(), client.fromServer.out(), fx.log));
        client.initialize();
        client.callTool(2, "desk_poke", "{}");
        Map<String, Object> answer = client.answer(2);
        assertTrue(isError(answer), answer.toString());
        assertTrue(toolText(answer).contains("the agent was lost"), toolText(answer));
        assertEquals(SkillroyMcpServer.EXIT_AGENT_LOST, served.get(20, TimeUnit.SECONDS));
        assertTrue(fx.logText().contains("ending with exit code 3"), fx.logText());
        assertEquals(0, fx.desk.recovered.get(), "no recover on a lost agent");
        client.toServer.closeWriter();
        client.fromServer.closeWriter();
    }

    @Test
    void aCallInFlightWhenTheClientClosesIsStillAnswered() throws Exception {
        McpFixture fx = new McpFixture(dir);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fx.desk.agent.on("desk.item", params -> {
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("found", true, "active", true);
        });
        LineClient client = new LineClient();
        Future<Integer> served = pool.submit(() -> SkillroyMcpServer.serve(fx.options().build(),
                client.toServer.in(), client.fromServer.out(), fx.log));
        client.initialize();
        client.callTool(2, "desk_item", "{\"code\":\"HAM-1\"}");
        assertTrue(entered.await(20, TimeUnit.SECONDS));
        client.toServer.closeWriter();
        release.countDown();
        Map<String, Object> answer = client.answer(2);
        assertTrue(toolText(answer).contains("\"PASS\""), toolText(answer));
        assertEquals(SkillroyMcpServer.EXIT_CLIENT_CLOSED, served.get(20, TimeUnit.SECONDS));
        client.fromServer.closeWriter();
    }

    @Test
    void anAgentLostAtStartIsAnExceptionAndNothingIsLeftRunning() throws Exception {
        McpFixture fx = new McpFixture(dir);
        fx.desk.agent.loseOn("trace.mark");
        LineClient client = new LineClient();
        assertThrows(AgentLostException.class, () -> SkillroyMcpServer.serve(fx.options().build(),
                client.toServer.in(), client.fromServer.out(), fx.log));
    }

    @Test
    void aFailedPreflightOrACollisionWithAWorkflowToolRefusesTheStart() throws Exception {
        McpFixture fx = new McpFixture(dir);
        DomainPack refusing = new Desk().pack;
        DomainPack failing = new DomainPack() {
            @Override
            public String id() {
                return refusing.id();
            }

            @Override
            public String version() {
                return refusing.version();
            }

            @Override
            public StepLibrary steps() {
                return refusing.steps();
            }

            @Override
            public SyncPolicy syncPolicy() {
                return refusing.syncPolicy();
            }

            @Override
            public List<FactProvider> facts() {
                return refusing.facts();
            }

            @Override
            public List<PreflightCheck> preflight() {
                return List.of(new PreflightCheck() {
                    @Override
                    public String id() {
                        return "desk-open";
                    }

                    @Override
                    public String check(ai.skillroy.testjava.controller.AgentConnection agent) {
                        return "the desk is closed";
                    }
                });
            }
        };
        LineClient client = new LineClient();
        SessionOpenException closed = assertThrows(SessionOpenException.class, () -> SkillroyMcpServer.serve(
                ServerOptions.builder(fx.desk.agent, failing).session(fx.options().build().session()).outDir(fx.out)
                        .build(), client.toServer.in(), client.fromServer.out(), fx.log));
        assertEquals(FailureKind.ENVIRONMENT, closed.kind());
        assertTrue(closed.getMessage().contains("the desk is closed"), closed.getMessage());
        DomainPack clashing = new DomainPack() {
            @Override
            public String id() {
                return "skillroy";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public StepLibrary steps() {
                return new StepLibrary() {
                    @Override
                    public Set<String> verbs() {
                        return Set.of("catalogue");
                    }

                    @Override
                    public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) {
                        return List.of();
                    }
                };
            }

            @Override
            public SyncPolicy syncPolicy() {
                return new SyncPolicy(null, null, null, null, null);
            }

            @Override
            public List<FactProvider> facts() {
                return List.of();
            }
        };
        SessionOpenException collision = assertThrows(SessionOpenException.class, () -> SkillroyMcpServer.serve(
                ServerOptions.builder(fx.desk.agent, clashing).mutating(true).outDir(fx.out).build(),
                client.toServer.in(), client.fromServer.out(), fx.log));
        assertTrue(collision.getMessage().contains("tool name collision: two tools project to skillroy_catalogue"),
                collision.getMessage());
    }
}
