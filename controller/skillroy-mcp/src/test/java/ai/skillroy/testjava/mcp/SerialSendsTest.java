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
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's transport ({@link SkillroyMcpServer#transport}) loses no message when two threads send at once. The
 * SDK's stdio transport alone refuses a send while another thread is inside its sink ("Failed to enqueue message"),
 * drops that message and stops handling the client: a tool call's answer lost that way left the client waiting for
 * its 20 s timeout, and the server for its 10 s drain (the Linux matrix's {@code ScenarioRunPolicyTest} failure).
 *
 * <p>Deterministic: Reactor's schedule hook holds the first send inside the SDK's sink (an emission that finds the
 * transport's writer idle schedules it from the sending thread, the sink still held), the second send starts while
 * it is held, and the hold ends once the second send waits for the first, or has already failed. The writer is idle
 * once its thread is parked after a first message. Should a later SDK or Reactor emit differently, the test fails at
 * its preconditions rather than passing without having held anything.
 */
class SerialSendsTest {

    private static final String HOOK = "skillroy-serial-sends-test";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void aSendWhileAnotherIsInsideTheSdksSinkWaitsForItAndNothingIsLost() throws Exception {
        TestPipe toServer = new TestPipe();
        TestPipe fromServer = new TestPipe();
        CountDownLatch reading = new CountDownLatch(1);
        InputStream in = new FilterInputStream(toServer.in()) {
            @Override
            public int read() throws IOException {
                reading.countDown();
                return super.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                reading.countDown();
                return super.read(b, off, len);
            }
        };
        AtomicReference<Thread> writer = new AtomicReference<>();
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                writer.set(Thread.currentThread());
                fromServer.out().write(b, off, len);
            }
        };
        McpServerTransportProvider provider = SkillroyMcpServer.transport(McpFixture.MAPPER, in, out);
        AtomicReference<McpServerTransport> transport = new AtomicReference<>();
        provider.setSessionFactory(t -> {
            transport.set(t);
            return new McpServerSession("serial-sends", WAIT, t, request -> Mono.empty(), Map.of(), Map.of());
        });
        AtomicReference<Thread> holder = new AtomicReference<>();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Schedulers.onScheduleHook(HOOK, task -> {
            if (Thread.currentThread() == holder.get() && held.getCount() > 0) {
                held.countDown();
                try {
                    release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return task;
        });
        try {
            assertTrue(reading.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the transport reads its input");
            assertEquals("sent", send(transport.get(), "notifications/before"));
            assertTrue(eventually(() -> lines(fromServer).size() == 1 && parked(writer.get())),
                    "the first message is written and the writer is idle again: " + fromServer.written());
            Map<String, String> outcomes = new ConcurrentHashMap<>();
            Thread first = new Thread(() -> outcomes.put("first", send(transport.get(), "notifications/first")),
                    "serial-sends-first");
            holder.set(first);
            first.start();
            assertTrue(held.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "the first send is held inside the SDK's"
                    + " sink, where it schedules the idle writer");
            Thread second = new Thread(() -> outcomes.put("second", send(transport.get(), "notifications/second")),
                    "serial-sends-second");
            second.start();
            assertTrue(eventually(() -> !second.isAlive() || waitsFor(second, first)), "the second send waits for the"
                    + " first, or has failed already");
            release.countDown();
            first.join(WAIT.toMillis());
            second.join(WAIT.toMillis());
            assertEquals("sent", outcomes.get("second"), "a send while another is inside the SDK's sink is not lost");
            assertEquals("sent", outcomes.get("first"));
            assertTrue(eventually(() -> lines(fromServer).size() == 3), fromServer.written());
            assertEquals(List.of("notifications/before", "notifications/first", "notifications/second"),
                    lines(fromServer), "each message once, in the order of the sends");
        } finally {
            Schedulers.resetOnScheduleHook(HOOK);
            release.countDown();
            toServer.closeWriter();
        }
    }

    private static String send(McpServerTransport transport, String method) {
        try {
            transport.sendMessage(new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, method, Map.of()))
                    .block(WAIT);
            return "sent";
        } catch (RuntimeException e) {
            return "failed: " + e.getMessage();
        }
    }

    /** The methods of the messages written so far, a line each. */
    private static List<String> lines(TestPipe fromServer) {
        return fromServer.written().lines().filter(line -> !line.isBlank())
                .map(line -> String.valueOf(Json.parseObject(line).get("method"))).toList();
    }

    /** The writer's thread waits for work: its run over the sink is over. */
    private static boolean parked(Thread thread) {
        Thread.State state = thread == null ? null : thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    /** {@code waiting} is blocked on a lock {@code owner} holds. */
    private static boolean waitsFor(Thread waiting, Thread owner) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(waiting.threadId());
        return info != null && info.getLockOwnerId() == owner.threadId();
    }

    private static boolean eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return true;
    }
}
