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

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The SDK's stdio transport with its sends made one at a time. The SDK's stdio session transport hands each outgoing
 * message to a Reactor unicast sink with {@code tryEmitNext}, and that sink is serialised by refusal: a thread that
 * emits while another thread is still inside an emission gets {@code FAIL_NON_SERIALIZED}, which the transport turns
 * into the error "Failed to enqueue message", dropping the message. Its loop over the client's messages has no error
 * path either, so a dropped answer also ends that loop: the transport stops reading and stops writing, the call waits
 * for the client's timeout and every later call too, while the server process stays up.
 *
 * <p>The server sends from several threads: a tool call's answer and its notifications (a run's resources announce
 * {@code notifications/resources/list_changed}) from the call's thread, the answers to {@code initialize},
 * {@code ping}, {@code tools/list} and {@code resources/list} (which a client sends when that notification reaches it)
 * from the transport's reading thread. Two of them overlap whenever the client is quick, and even strictly alternating
 * calls overlap under load: the emitting thread releases the sink only after the message is already on its way, so the
 * client's next call can be answered on another thread before the first thread got the processor back.
 *
 * <p>Every session transport the SDK makes here is wrapped so that its sends subscribe under one lock. Once the
 * transport is started (it is before the client's first message is read) the SDK's send emits on the subscribing
 * thread, so emissions never overlap; the end of a send is signalled after the lock is released. Everything else
 * passes through unchanged. Thread-safe.
 */
final class SerialSends implements McpServerTransportProvider {

    private final McpServerTransportProvider delegate;

    /** @param delegate the SDK's provider, before its session factory is set */
    SerialSends(McpServerTransportProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        delegate.setSessionFactory(transport -> sessionFactory.create(new Transport(transport)));
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return delegate.notifyClients(method, params);
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return delegate.notifyClient(sessionId, method, params);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    /** One session's transport: its sends one at a time, the rest as the SDK's. */
    static final class Transport implements McpServerTransport {

        private final McpServerTransport delegate;
        private final Object sending = new Object();

        Transport(McpServerTransport delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.defer(() -> {
                CompletableFuture<Void> sent = new CompletableFuture<>();
                synchronized (sending) {
                    delegate.sendMessage(message).subscribe(null, sent::completeExceptionally,
                            () -> sent.complete(null));
                }
                return Mono.fromFuture(sent);
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public List<String> protocolVersions() {
            return delegate.protocolVersions();
        }
    }
}
