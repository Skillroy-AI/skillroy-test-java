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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The connection selection rules: --pid, --port with --token-file, the only agent, --discovery-dir. */
class ConnectionTest {

    @TempDir
    Path tmp;

    private final List<FakeAgent> agents = new ArrayList<>();
    private Path discovery;

    @BeforeEach
    void setUp() throws IOException {
        discovery = FakeAgent.ownerOnlyDir(tmp.resolve("discovery"));
    }

    @AfterEach
    void tearDown() {
        agents.forEach(FakeAgent::close);
    }

    private FakeAgent agent() throws IOException {
        FakeAgent agent = new FakeAgent();
        agents.add(agent);
        return agent;
    }

    private CliHarness.Outcome info(String... connection) {
        List<String> args = new ArrayList<>(List.of("info"));
        args.addAll(List.of(connection));
        return CliHarness.run(tmp.resolve("no-default-dir"), args.toArray(String[]::new));
    }

    @Test
    void portAndTokenFileNeedNoDiscoveryFile() throws IOException {
        FakeAgent agent = agent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        CliHarness.Outcome outcome = info("--port", Integer.toString(agent.port()), "--token-file",
                tokenFile.toString()).expectExit(Exit.OK).never(agent.token);
        assertTrue(outcome.out().contains("9.9.9-fake"), outcome.out());
        // Hardening H2: the token never travels; every request carries a MAC made with it.
        assertFalse(agent.requests.isEmpty());
        for (FakeAgent.Request r : agent.requests) {
            assertTrue(r.authorization() != null && r.authorization().startsWith("Skillroy-HMAC "), r.toString());
            assertFalse(r.authorization().contains(agent.token) || r.pathAndQuery().contains(agent.token)
                    || r.body().contains(agent.token), "the token was sent: " + r);
            assertEquals(r.path().equals("/ping"), !r.authorization().contains("nonce="), r.toString());
        }
    }

    @Test
    void aPeerWhoseReplyMacIsWrongIsNotTheAgent() throws IOException {
        // A listener on the remembered port that answers /ping correctly formed, but without the key.
        FakeAgent impostor = agent();
        impostor.wrongReplyMac = true;
        Path tokenFile = impostor.writeTokenFile(tmp.resolve("agent.token"));
        info("--port", Integer.toString(impostor.port()), "--token-file", tokenFile.toString())
                .expectExit(Exit.UNREACHABLE).never(impostor.token);
        assertTrue(impostor.requests.stream().noneMatch(r -> r.path().equals("/rpc")),
                "nothing but the handshake reaches a peer that failed it");

        FakeAgent silent = agent();
        silent.omitReplyMac = true;
        Path silentToken = silent.writeTokenFile(tmp.resolve("silent.token"));
        info("--port", Integer.toString(silent.port()), "--token-file", silentToken.toString())
                .expectExit(Exit.UNREACHABLE).never(silent.token);
    }

    @Test
    void pidFindsTheAgentThroughItsDiscoveryFile() throws IOException {
        FakeAgent agent = agent();
        agent.writeDiscovery(discovery);
        CliHarness.Outcome outcome = info("--pid", Long.toString(agent.pid), "--discovery-dir", discovery.toString())
                .expectExit(Exit.OK).never(agent.token);
        assertTrue(outcome.out().contains("127.0.0.1:" + agent.port()), outcome.out());
    }

    /**
     * Findings S6/S28: discovery reads a token file only inside the directory it lists, so a discovery file that
     * points elsewhere is unreachable, and the message says how to reach such an agent instead.
     */
    @Test
    void aTokenFileOutsideTheDiscoveryDirectoryIsNotReadAndTheMessageSaysWhatToDo() throws IOException {
        FakeAgent agent = agent();
        Path file = agent.writeDiscovery(discovery);
        Path outside = agent.writeTokenFile(tmp.resolve("elsewhere.token"));
        Map<String, Object> json = new LinkedHashMap<>(Json.parseObject(Files.readString(file)));
        json.put("tokenFile", outside.toString());
        Files.writeString(file, Json.write(json));
        Files.delete(discovery.resolve("skillroy-" + agent.pid + "-" + agent.startTimeMs + ".token"));
        for (String[] connection : List.of(new String[] {"--pid", Long.toString(agent.pid), "--discovery-dir",
                discovery.toString()}, new String[] {"--discovery-dir", discovery.toString()})) {
            CliHarness.Outcome outcome = info(connection).expectExit(Exit.UNREACHABLE).never(agent.token);
            assertTrue(outcome.err().contains("names a token file that cannot be read"), outcome.err());
            assertTrue(outcome.err().contains("reached with --port and --token-file"), outcome.err());
        }
        assertTrue(agent.requests.isEmpty(), "nothing reaches the agent without its token");
    }

    @Test
    void withoutPidOrPortTheOnlyLiveAgentIsUsed() throws IOException {
        FakeAgent agent = agent();
        agent.writeDiscovery(discovery);
        info("--discovery-dir", discovery.toString()).expectExit(Exit.OK).never(agent.token);
    }

    @Test
    void theDefaultDiscoveryDirectoryComesFromTheContext() throws IOException {
        FakeAgent agent = agent();
        agent.writeDiscovery(discovery);
        CliHarness.run(discovery, "info").expectExit(Exit.OK).never(agent.token);
    }

    @Test
    void severalLiveAgentsAreAUsageErrorListingTheirPids() throws IOException {
        FakeAgent first = agent();
        FakeAgent second = agent();
        // Same live pid, a different start time within the liveness tolerance: two distinct discovery files.
        second.startTimeMs = first.startTimeMs + 1;
        first.writeDiscovery(discovery);
        second.writeDiscovery(discovery);
        CliHarness.Outcome outcome = info("--discovery-dir", discovery.toString()).expectExit(Exit.USAGE);
        assertTrue(outcome.err().contains("2 live agents in " + discovery.toAbsolutePath().normalize()),
                outcome.err());
        assertTrue(outcome.err().contains("choose one with --pid"), outcome.err());
    }

    @Test
    void noLiveAgentIsUnreachable() {
        CliHarness.Outcome outcome = info("--discovery-dir", discovery.toString()).expectExit(Exit.UNREACHABLE);
        assertTrue(outcome.err().contains("no live agent in " + discovery.toAbsolutePath().normalize()),
                outcome.err());
        CliHarness.Outcome missing = info("--discovery-dir", tmp.resolve("absent").toString())
                .expectExit(Exit.UNREACHABLE);
        assertTrue(missing.err().contains("(it does not exist)"), missing.err());
    }

    @Test
    void aGroupWritableDiscoveryDirectoryIsIgnoredAndSaysSo() throws IOException {
        FakeAgent agent = agent();
        agent.writeDiscovery(discovery);
        Files.setPosixFilePermissions(discovery, PosixFilePermissions.fromString("rwxrwx---"));
        CliHarness.Outcome outcome = info("--discovery-dir", discovery.toString()).expectExit(Exit.UNREACHABLE);
        assertTrue(outcome.err().contains("writable by group or others"), outcome.err());
        CliHarness.Outcome byPid = info("--pid", Long.toString(agent.pid), "--discovery-dir", discovery.toString())
                .expectExit(Exit.UNREACHABLE);
        assertTrue(byPid.err().contains("writable by group or others"), byPid.err());
    }

    @Test
    void aPidWithoutAnAgentIsUnreachableWithTheReason() throws IOException, InterruptedException {
        long self = ProcessHandle.current().pid();
        CliHarness.Outcome running = info("--pid", Long.toString(self), "--discovery-dir", discovery.toString())
                .expectExit(Exit.UNREACHABLE);
        assertTrue(running.err().contains("no live agent for pid " + self), running.err());
        assertTrue(running.err().contains("skillroy attach " + self), running.err());

        Process gone = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-version").redirectErrorStream(true).start();
        gone.getInputStream().readAllBytes();
        gone.waitFor();
        CliHarness.Outcome dead = info("--pid", Long.toString(gone.pid()), "--discovery-dir", discovery.toString())
                .expectExit(Exit.UNREACHABLE);
        assertTrue(dead.err().contains("no process " + gone.pid() + " is running"), dead.err());
    }

    @Test
    void contradictoryOrIncompleteOptionsAreUsageErrors() throws IOException {
        FakeAgent agent = agent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        String port = Integer.toString(agent.port());
        assertTrue(info("--port", port).expectExit(Exit.USAGE).err().contains("must be given together"));
        assertTrue(info("--token-file", tokenFile.toString()).expectExit(Exit.USAGE).err()
                .contains("must be given together"));
        assertTrue(info("--pid", "1", "--port", port, "--token-file", tokenFile.toString()).expectExit(Exit.USAGE)
                .err().contains("--pid cannot be combined"));
        assertTrue(info("--port", "70000", "--token-file", tokenFile.toString()).expectExit(Exit.USAGE).err()
                .contains("between 1 and 65535"));
        assertTrue(info("--pid", "abc").expectExit(Exit.USAGE).err().contains("--pid needs a whole number"));
        assertTrue(agent.requests.isEmpty(), "nothing may be sent for a usage error");
    }

    @Test
    void aBadTokenFileIsAUsageErrorThatNeverEchoesItsContent() throws IOException {
        FakeAgent agent = agent();
        String port = Integer.toString(agent.port());
        assertTrue(info("--port", port, "--token-file", tmp.resolve("none").toString()).expectExit(Exit.USAGE).err()
                .contains("no such file"));
        Path notAToken = Files.writeString(tmp.resolve("bad.token"), "secret value with spaces\n");
        CliHarness.Outcome outcome = info("--port", port, "--token-file", notAToken.toString())
                .expectExit(Exit.USAGE).never("secret value");
        assertTrue(outcome.err().contains("does not hold a token"), outcome.err());
    }

    @Test
    void aWrongTokenOrNoListenerIsUnreachable() throws IOException {
        FakeAgent agent = agent();
        Path wrong = Files.writeString(tmp.resolve("wrong.token"), "0123456789abcdef\n");
        CliHarness.Outcome rejected = info("--port", Integer.toString(agent.port()), "--token-file", wrong.toString())
                .expectExit(Exit.UNREACHABLE).never("0123456789abcdef");
        assertTrue(rejected.err().contains("401"), rejected.err());

        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        CliHarness.Outcome refused = info("--port", Integer.toString(freePort), "--token-file", wrong.toString())
                .expectExit(Exit.UNREACHABLE);
        assertTrue(refused.err().contains("agent unreachable"), refused.err());
    }

    @Test
    void aPortConnectionLearnsTheTargetIdentityFromPing() throws IOException, InterruptedException {
        FakeAgent agent = agent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        // The fake reports this JVM's pid; the CLI must accept it and keep verifying it on every ping.
        info("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString()).expectExit(Exit.OK);
        long pings = agent.requests.stream().filter(r -> r.path().equals("/ping")).count();
        assertTrue(pings >= 2, "a probe ping and a verified connect: " + pings);
        // A pid that is not a live process here (another PID namespace) is left unknown rather than failing.
        Process gone = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-version").redirectErrorStream(true).start();
        gone.getInputStream().readAllBytes();
        gone.waitFor();
        agent.pid = gone.pid();
        info("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString()).expectExit(Exit.OK);
    }

    @Test
    void connectorDirectoryProblemDescribesWhatItSees() throws IOException {
        assertEquals("it does not exist", Connector.directoryProblem(tmp.resolve("absent")));
        Path file = Files.writeString(tmp.resolve("file"), "x");
        assertEquals("it is not a directory", Connector.directoryProblem(file));
        assertEquals(null, Connector.directoryProblem(discovery));
        assertFalse(Connector.withConnection().isEmpty());
    }
}
