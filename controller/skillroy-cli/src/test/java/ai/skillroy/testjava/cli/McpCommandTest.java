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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
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
import java.math.BigDecimal;
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
 * {@code --mutating}; never a destructive one; {@code web_evaluate} and {@code web_wait_flag} only with
 * {@code --allow-evaluate};
 * {@code skillroy_session_ask} only with a gateway), runs a verb, writes the session's ledger and model-call records
 * when it ends (an actor or not), and ends with exit code 0 when the client closes the stream, and 3 when the agent's
 * event stream drops while the client is idle (the command's watch). Its usage errors are exit code 64 before anything
 * is served.
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
            this(CliHarness.class.getClassLoader(), args);
        }

        /** The command with the packs of {@code loader}. */
        Served(ClassLoader loader, String... args) {
            List<String> all = new ArrayList<>(List.of("mcp"));
            all.addAll(List.of(args));
            all.addAll(connection);
            Cli cli = new Cli(toServer.in(), new PrintStream(fromServer.out(), true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8), loader, tmp.resolve("unused-discovery"),
                    Terminal.NONE);
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

    /**
     * Decision D98: {@code web_evaluate} and {@code web_wait_flag} (each runs a script in the target's pages) only with
     * {@code --allow-evaluate}, a person's opt-in; {@code web_evaluate} with {@code --mutating} as well.
     */
    @Test
    void theScriptPrimitivesAreOfferedOnlyWhenAPersonAllowsThem() throws Exception {
        agent.schema.add(new PrimitiveDescriptor("web.evaluate", "Evaluates a script.", null, null, "WORKER", 5_000,
                null, true, false));
        agent.schema.add(new PrimitiveDescriptor("web.waitFlag", "Waits for an expression.", null, null, "WORKER",
                5_000, null, false, false));
        agent.schema.add(new PrimitiveDescriptor("swing.click", "Clicks.", null, null, "EDT", 5_000, null, true,
                false));
        try (Served served = new Served("--mutating", "--out", tmp.resolve("default").toString())) {
            assertFalse(served.tools().contains("web_evaluate") || served.tools().contains("web_wait_flag"),
                    served.tools().toString());
            assertTrue(served.tools().contains("swing_click"), served.tools().toString());
            assertEquals(0, served.clientCloses(), served.log());
        }
        try (Served served = new Served("--mutating", "--allow-evaluate", "--out", tmp.resolve("opted").toString())) {
            assertTrue(served.tools().containsAll(List.of("web_evaluate", "web_wait_flag")), served.tools()
                    .toString());
            assertEquals(0, served.clientCloses(), served.log());
        }
        try (Served served = new Served("--allow-evaluate", "--out", tmp.resolve("read-only").toString())) {
            assertTrue(served.tools().contains("web_wait_flag"), served.tools().toString());
            assertFalse(served.tools().contains("web_evaluate"), "read-only: " + served.tools());
            assertEquals(0, served.clientCloses(), served.log());
        }
    }

    /**
     * phase5-contracts.md 13.4: the server writes the session's {@code cost-ledger.jsonl} when it ends, whether or
     * not its client ever made the session's actor (here, no gateway: no actor at all).
     */
    @Test
    void theSessionsLedgerIsWrittenWhenTheServerEndsWithoutAnActor() throws Exception {
        Path out = tmp.resolve("no-actor");
        try (Served served = new Served("--out", out.toString())) {
            assertFalse(served.tools().contains("skillroy_session_ask"));
            assertEquals(0, served.clientCloses(), served.log());
        }
        Path session = out.resolve("runs").resolve(sessionDir(out));
        assertEquals(List.of(), Files.readAllLines(session.resolve("cost-ledger.jsonl")), "written, and empty");
        assertFalse(Files.exists(session.resolve("inference.jsonl")), "no model call, no record");
    }

    /**
     * phase5-contracts.md 7.4, 13.4: a model-written resolution through {@code skillroy_resolve} goes through the
     * session's actor; its call is in the session's {@code cost-ledger.jsonl} and {@code inference.jsonl} when the
     * server ends, and (through the service's metered resolve) counts on the session's budget record.
     */
    @Test
    void aModelWrittenResolutionIsRecordedWhenTheServerEnds() throws Exception {
        Path out = tmp.resolve("tier-b");
        try (CliTierB tierB = new CliTierB(tmp, agent)) {
            List<String> args = new ArrayList<>(tierB.options());
            args.addAll(List.of("--out", out.toString()));
            try (Served served = new Served(tierB.loader, args.toArray(String[]::new))) {
                McpSchema.CallToolResult resolved = served.client.callTool(new McpSchema.CallToolRequest(
                        "skillroy_resolve", Map.of("slot", "tool", "kind", "Tool", "args", Map.of("kind", "hammer"),
                                "strategy", List.of("llm-sql"))));
                String text = ((McpSchema.TextContent) resolved.content().get(0)).text();
                assertFalse(resolved.isError(), text);
                assertTrue(text.contains("\"LLM_SQL\""), text);
                assertEquals(0, served.clientCloses(), served.log());
            }
            assertEquals(1, tierB.calls());
        }
        Path session = out.resolve("runs").resolve(sessionDir(out));
        List<String> ledger = Files.readAllLines(session.resolve("cost-ledger.jsonl"));
        assertEquals(1, ledger.size(), ledger.toString());
        List<String> inference = Files.readAllLines(session.resolve("inference.jsonl"));
        assertEquals(1, inference.size(), inference.toString());
        assertEquals("resolve", Json.parseObject(inference.get(0)).get("purpose"));
        List<Map<String, Object>> budgets = CliTierB.budgets(session.resolve("journal.jsonl"));
        assertFalse(budgets.isEmpty(), "through the session service's metered resolve: a budget record");
        assertEquals(0, new BigDecimal(CliTierB.COST).compareTo(new BigDecimal(String.valueOf(
                budgets.get(budgets.size() - 1).get("spentUsd")))), budgets.toString());
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
