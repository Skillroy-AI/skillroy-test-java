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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 9.2, 13.4, 15.1 item 15: {@code skillroy mcp} driven through {@link Main#run} in process, on
 * piped standard streams, with the MCP SDK's own client on their other ends against the CLI tests' fake agent: it
 * starts, answers {@code tools/list} (read-only by default; the pack's verbs and the mutating primitives with
 * {@code --mutating}; never a destructive one; {@code skillroy_session_ask} only with a gateway), runs a verb, and ends
 * with exit code 0 when the client closes the stream, and 3 when the agent's event stream drops while the client is
 * idle (the command's watch). Its usage errors are exit code 64 before anything is served.
 */
class McpCommandTest {

    static final McpJsonMapper MAPPER = new JacksonMcpJsonMapperSupplier().get();
    static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @BeforeEach
    void setUp() throws Exception {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        agent.close();
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "the command's thread ended");
    }

    /** The command serving in process on piped streams, and the SDK's client on their other ends. */
    private final class Served implements AutoCloseable {

        final TestPipe toServer = new TestPipe();
        final TestPipe fromServer = new TestPipe();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final Future<Integer> exit;
        final McpSyncClient client;

        Served(String... args) {
            List<String> all = new ArrayList<>(List.of("mcp"));
            all.addAll(List.of(args));
            all.addAll(connection);
            Cli cli = new Cli(toServer.in(), new PrintStream(fromServer.out(), true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8), CliHarness.class.getClassLoader(),
                    tmp.resolve("unused-discovery"), Terminal.NONE);
            exit = pool.submit(() -> Main.run(all, cli));
            client = McpClient.sync(new PipedClientTransport(MAPPER, toServer, fromServer)).requestTimeout(WAIT)
                    .initializationTimeout(WAIT).clientInfo(new McpSchema.Implementation("skillroy-cli-test", "1.0"))
                    .build();
            client.initialize();
        }

        List<String> tools() {
            return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
        }

        /** The client closes its stream; the command's exit code. */
        int clientCloses() throws Exception {
            client.closeGracefully();
            return exit.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        String log() {
            return err.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            try {
                client.close();
            } catch (RuntimeException e) {
                // Closed already, or the server is gone: nothing more to release.
            }
            toServer.closeWriter();
            fromServer.closeWriter();
        }
    }

    @Test
    void readOnlyByDefaultItListsTheReadsAndEndsWhenTheClientCloses() throws Exception {
        Path out = tmp.resolve("mcp-out");
        try (Served served = new Served("--out", out.toString())) {
            List<String> tools = served.tools();
            assertTrue(tools.containsAll(List.of("skillroy_catalogue", "skillroy_snapshot", "skillroy_facts",
                    "skillroy_events", "skillroy_resolve")), tools.toString());
            assertFalse(tools.contains("clitest_poke"), "a verb (mutating) is not listed read-only: " + tools);
            assertFalse(tools.contains("skillroy_scenario_run"), tools.toString());
            assertFalse(tools.contains("agent_disable"), "a destructive primitive never");
            assertFalse(tools.contains("skillroy_session_ask"), "no gateway (none without a key): no second model");
            McpSchema.CallToolResult counter = served.client.callTool(new McpSchema.CallToolRequest("skillroy_facts",
                    Map.of("root", "counter")));
            assertFalse(counter.isError(), String.valueOf(counter));
            assertTrue(((McpSchema.TextContent) counter.content().get(0)).text().contains("\"value\": 0"),
                    String.valueOf(counter));
            assertEquals(0, served.clientCloses(), served.log());
            assertTrue(served.log().contains("read-only"), "the server's log on stderr: " + served.log());
            assertFalse(served.fromServer.written().contains("serving"), "stdout carries the protocol only");
        }
        assertTrue(Files.isRegularFile(out.resolve("runs").resolve(sessionDir(out)).resolve("journal.jsonl")),
                "the session's journal under --out");
        assertTrue(agent.rpcRequests("test.poke").isEmpty(), "nothing was changed");
    }

    @Test
    void mutatingOffersTheVerbsAndRunsOneThroughTheSession() throws Exception {
        try (Served served = new Served("--mutating", "--out", tmp.resolve("out").toString())) {
            List<String> tools = served.tools();
            assertTrue(tools.containsAll(List.of("clitest_poke", "skillroy_scenario_run")), tools.toString());
            assertFalse(tools.contains("agent_disable"), "never a destructive primitive");
            McpSchema.CallToolResult poked = served.client.callTool(new McpSchema.CallToolRequest("clitest_poke",
                    Map.of()));
            assertFalse(poked.isError(), String.valueOf(poked));
            assertEquals(1, agent.counter.get(), "the verb ran on the target");
            assertEquals(0, served.clientCloses(), served.log());
        }
    }

    @Test
    void aGatewayOffersTheSessionsSecondModel() throws Exception {
        Path recordings = Files.createDirectories(tmp.resolve("recordings"));
        try (Served served = new Served("--inference", "recorded", "--recordings", recordings.toString(), "--out",
                tmp.resolve("out").toString())) {
            assertTrue(served.tools().contains("skillroy_session_ask"), served.tools().toString());
            assertEquals(0, served.clientCloses(), served.log());
        }
    }

    @Test
    void anAgentLostWhileTheClientIsIdleEndsTheServerWithExitCode3() throws Exception {
        try (Served served = new Served("--out", tmp.resolve("out").toString())) {
            assertFalse(served.tools().isEmpty());
            agent.close();
            int exit = served.exit.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            assertEquals(Exit.UNREACHABLE, exit, served.log());
            assertTrue(served.log().contains("the agent was lost"), served.log());
        }
    }

    @Test
    void usageErrorsAreExitCode64BeforeAnythingIsServed() throws Exception {
        CliHarness.Outcome extra = CliHarness.run(tmp, cli("mcp", "extra")).expectExit(Exit.USAGE);
        assertTrue(extra.err().contains("unexpected argument 'extra'"), extra.err());
        Path file = Files.writeString(tmp.resolve("a-file"), "x");
        CliHarness.run(tmp, cli("mcp", "--out", file.toString())).expectExit(Exit.USAGE);
        CliHarness.Outcome noKey = CliHarness.run(tmp, cli("mcp", "--inference", "claude")).expectExit(Exit.USAGE);
        assertTrue(noKey.err().contains("the model key is not set (ANTHROPIC_API_KEY)"), noKey.err());
        CliHarness.run(tmp, cli("mcp", "--inference", "recorded")).expectExit(Exit.USAGE);
        CliHarness.run(tmp, cli("mcp", "--json")).expectExit(Exit.USAGE);
        assertTrue(agent.requests.isEmpty(), "no call before a usage error");
    }

    private String[] cli(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(connection);
        return all.toArray(String[]::new);
    }

    /** The one session directory under {@code <out>/runs/}. */
    private static String sessionDir(Path out) throws Exception {
        try (var dirs = Files.list(out.resolve("runs"))) {
            List<Path> list = dirs.toList();
            assertEquals(1, list.size(), list.toString());
            return list.get(0).getFileName().toString();
        }
    }
}
