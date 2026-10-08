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

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * The SDK's client side of stdio, over in-memory pipes instead of a subprocess (the SDK's own stdio client transport
 * starts a process): one JSON-RPC message per line each way, the server's lines read on a daemon thread that ends with
 * the server's stream. Closing gracefully ends the server's input, as a client closing stdin does.
 */
final class PipedClientTransport implements McpClientTransport {

    private final McpJsonMapper mapper;
    private final TestPipe toServer;
    private final TestPipe fromServer;
    private Thread reader;

    PipedClientTransport(McpJsonMapper mapper, TestPipe toServer, TestPipe fromServer) {
        this.mapper = mapper;
        this.toServer = toServer;
        this.fromServer = fromServer;
    }

    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.fromRunnable(() -> {
            reader = new Thread(() -> read(handler), "test-mcp-client-reader");
            reader.setDaemon(true);
            reader.start();
        });
    }

    private void read(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(fromServer.in(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(mapper, line);
                handler.apply(Mono.just(message)).subscribe();
            }
        } catch (IOException | RuntimeException e) {
            // The server's stream ended or carried something that is not a message: the reader ends.
        }
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            try {
                byte[] line = (mapper.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
                OutputStream out = toServer.out();
                synchronized (this) {
                    out.write(line);
                    out.flush();
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot send to the server: " + e, e);
            }
        });
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(toServer::closeWriter);
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return mapper.convertValue(data, typeRef);
    }
}
