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

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentHome;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.controller.LaunchedTarget;
import ai.skillroy.testjava.controller.TargetLauncher;
import ai.skillroy.testjava.protocol.Auth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fix, pass 2, P2-L1-F1, end to end with the real agent home and the real {@link AgentClient}: a
 * process without the token tries to pin the agent's body budget (two connections each send a right-shaped
 * {@code Authorization} and body hash, declare 8 MB, send 6 MB and stall), and the controller's calls that go out on
 * <em>new</em> connections (concurrent calls; a call on a kept-alive connection never met the budget) must still be
 * served. Before the fix the budget was pinned and every such call failed with an unsigned 503. The target runs on
 * the Java named by {@code -Dskillroy.it.targetJava} (a Java 8 target in the Linux image), headless.
 */
class ControlChannelPinIT {

    private static final String CHILD_SOURCE = """
            package skillroyit;

            public final class SleepingTarget {
                public static void main(String[] args) throws Exception {
                    Thread.sleep(Long.MAX_VALUE);
                }
            }
            """;

    private static final int DECLARED = 8 * 1024 * 1024;
    private static final int SENT = 6 * 1024 * 1024;
    private static final int CONCURRENT_CALLS = 4;

    @TempDir
    Path tmp;

    private LaunchedTarget target;
    private final List<Socket> pins = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void tearDown() {
        for (Socket socket : pins) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closed by the agent already.
            }
        }
        if (target != null) {
            target.close();
        }
    }

    private static Path agentHome() {
        String configured = System.getProperty("skillroy.agent.home");
        Path home = Path.of(configured != null && !configured.isBlank() ? configured
                : System.getProperty("skillroy.cli.it.agentHome", "target/it-agent-home/skillroy-agent"));
        Assumptions.assumeTrue(Files.isRegularFile(home.resolve(AgentHome.BOOTSTRAP_JAR)), "no agent home at " + home);
        return home.toAbsolutePath();
    }

    private static Path targetJava() {
        String configured = System.getProperty("skillroy.it.targetJava");
        return configured != null && !configured.isBlank() ? Path.of(configured.strip())
                : Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private Path compileChild() throws IOException {
        Path src = Files.createDirectories(tmp.resolve("child-src/skillroyit"));
        Path source = Files.writeString(src.resolve("SleepingTarget.java"), CHILD_SOURCE);
        Path classes = Files.createDirectories(tmp.resolve("child-classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int status = javac.run(null, diagnostics, diagnostics, "--release", "8", "-Xlint:-options", "-d",
                classes.toString(), source.toString());
        assertEquals(0, status, diagnostics.toString(StandardCharsets.UTF_8));
        return classes;
    }

    @Test
    void aProcessWithoutTheTokenCannotMakeCallsOnNewConnectionsFail() throws Exception {
        Path home = agentHome();
        target = TargetLauncher.java(targetJava())
                .classPath(List.of(compileChild()))
                .mainClass("skillroyit.SleepingTarget")
                .jvmArg("-Djava.awt.headless=true")
                .javaAgent(AgentHome.of(home), Map.of())
                .discoveryDir(FakeAgent.ownerOnlyDir(tmp.resolve("discovery")))
                .inheritOutputTo(tmp.resolve("child.log"))
                .start();
        Endpoint endpoint = target.awaitAgent(Duration.ofSeconds(120));
        try (AgentClient client = AgentClient.connect(endpoint)) {
            client.call("agent.info", Map.of());
            List<Integer> pinReplies = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 2; i++) {
                pin(endpoint.port(), pinReplies);
            }
            // Before the fix the two bodies are buffered until they hold the whole budget, and a token-less probe is
            // then answered 503; with it both are refused on their heads at once.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (pinReplies.size() < 2 && probe(endpoint.port()) != 503) {
                assertTrue(System.nanoTime() < deadline, "the agent neither refused nor buffered the two bodies");
                Thread.sleep(50);
            }
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLS);
            try {
                List<Future<String>> calls = new ArrayList<>();
                for (int i = 0; i < CONCURRENT_CALLS; i++) {
                    calls.add(pool.submit(() -> {
                        try {
                            client.call("agent.info", Map.of());
                            return "OK";
                        } catch (Exception e) {
                            return e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                    }));
                }
                List<String> outcomes = new ArrayList<>();
                for (Future<String> call : calls) {
                    outcomes.add(call.get(120, TimeUnit.SECONDS));
                }
                assertEquals(Collections.nCopies(CONCURRENT_CALLS, "OK"), outcomes,
                        "concurrent calls (new connections) while a process without the token stalls two bodies");
            } finally {
                pool.shutdownNow();
            }
            assertTrue(!client.isLost(), "the client is not lost");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (pinReplies.size() < 2 && System.nanoTime() < until) {
                Thread.sleep(50);
            }
            assertEquals(List.of(401, 401), new ArrayList<>(pinReplies), "both refused on the head");
        }
    }

    /** One connection without the token that declares 8 MB, sends 6 MB on another thread and stalls. */
    private void pin(int port, List<Integer> replies) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        pins.add(socket);
        socket.setSoTimeout(120000);
        String zeros = "0".repeat(Auth.HEX_DIGEST_LENGTH);
        String head = "POST /rpc HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: "
                + Auth.formatAuthorization(Auth.newNonce(), zeros) + "\r\n" + Auth.HEADER_BODY_SHA256 + ": " + zeros
                + "\r\nContent-Length: " + DECLARED + "\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        Thread.ofPlatform().daemon().start(() -> {
            try {
                out.write(new byte[SENT]);
                out.flush();
            } catch (IOException refused) {
                // Answered and closed before the body went out.
            }
        });
        Thread.ofPlatform().daemon().start(() -> {
            try {
                replies.add(status(socket.getInputStream()));
            } catch (IOException noReply) {
                replies.add(-1);
            }
        });
    }

    /** A token-less {@code POST /rpc} with a small body: 401 normally, 503 while the body budget is pinned. */
    private static int probe(int port) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(30000);
            String zeros = "0".repeat(Auth.HEX_DIGEST_LENGTH);
            String head = "POST /rpc HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: "
                    + Auth.formatAuthorization(Auth.newNonce(), zeros) + "\r\n" + Auth.HEADER_BODY_SHA256 + ": "
                    + zeros + "\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return status(socket.getInputStream());
        }
    }

    /** The status code of the reply's status line. */
    private static int status(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            line.append((char) c);
        }
        return line.length() >= 12 ? Integer.parseInt(line.substring(9, 12)) : -1;
    }
}
