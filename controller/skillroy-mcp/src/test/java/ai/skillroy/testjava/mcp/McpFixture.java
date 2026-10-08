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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.SessionOptions;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * The server on the desk's fake agent and pack, in process, with the SDK's own client over in-memory pipes: no
 * subprocess, no network. {@link #close} closes the client's stream (the server then ends with exit code 0) and the
 * server.
 */
final class McpFixture implements AutoCloseable {

    static final McpJsonMapper MAPPER = new JacksonMcpJsonMapperSupplier().get();
    static final Duration WAIT = Duration.ofSeconds(20);

    final Desk desk = new Desk();
    final Path out;
    final TestPipe toServer = new TestPipe();
    final TestPipe fromServer = new TestPipe();
    final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
    SkillroyMcpServer server;
    McpSyncClient client;

    McpFixture(Path dir) {
        this.out = dir.resolve("out");
    }

    /** The desk's options: credentials, environment seed-a, session sess-1, the output directory, read-only. */
    ServerOptions.Builder options() {
        return options(desk.pack);
    }

    /** {@link #options()} with another pack over the desk's agent. */
    ServerOptions.Builder options(ai.skillroy.testjava.scenario.pack.DomainPack pack) {
        SessionOptions session = SessionOptions.defaults(null)
                .withBind(BindOptions.defaults().withCredentials(Desk.credentials()).withEnvId("seed-a"))
                .withId("sess-1");
        return ServerOptions.builder(desk.agent, pack).session(session).outDir(out).clock(Clock.systemUTC());
    }

    /** Starts the server with these options and initialises a client. */
    McpFixture start(ServerOptions options) throws Exception {
        server = SkillroyMcpServer.start(options, toServer.in(), fromServer.out(), log);
        client = McpClient.sync(new PipedClientTransport(MAPPER, toServer, fromServer))
                .requestTimeout(WAIT)
                .initializationTimeout(WAIT)
                .clientInfo(new McpSchema.Implementation("skillroy-test", "1.0"))
                .build();
        client.initialize();
        return this;
    }

    McpFixture startReadOnly() throws Exception {
        return start(options().build());
    }

    McpFixture startMutating() throws Exception {
        return start(options().mutating(true).build());
    }

    McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
        return client.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    /** The first content's text. */
    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    /** The first content's text as a JSON object. */
    static Map<String, Object> json(McpSchema.CallToolResult result) {
        return Json.parseObject(text(result));
    }

    List<String> toolNames() {
        return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    McpSchema.Tool tool(String name) {
        return client.listTools().tools().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not listed: " + toolNames()));
    }

    String logText() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    /** Ends the client's stream; the server's exit code. */
    OptionalInt clientCloses() throws InterruptedException {
        client.closeGracefully();
        return server.awaitExit(WAIT);
    }

    @Override
    public void close() throws Exception {
        try {
            if (client != null) {
                client.closeGracefully();
            }
            if (server != null) {
                server.awaitExit(WAIT);
                server.close();
            }
        } finally {
            toServer.closeWriter();
            fromServer.closeWriter();
        }
    }
}
