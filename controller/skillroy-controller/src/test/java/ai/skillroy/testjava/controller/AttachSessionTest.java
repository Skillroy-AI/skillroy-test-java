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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.LoadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Attaches the fake agent to a real child JVM. With {@code -Dskillroy.it.targetJava} naming a Java 8 JVM this goes
 * through the Java 8 "0" reply that a current attach client reports as {@code AgentLoadException}.
 */
class AttachSessionTest {

    @TempDir
    Path dir;

    private LaunchedTarget target;
    private long targetPid;
    private String targetVersion;

    /** Starts the child JVM the attach tests load the fake agent into. */
    private void launchTarget() throws Exception {
        assumeTrue(AttachSession.attachModulePresent(), "jdk.attach is not available");
        Fixtures f = Fixtures.get();
        Path report = dir.resolve("report.properties");
        TargetLauncher.Builder builder = TargetLauncher.java(Fixtures.targetJava())
                .classPath(List.of(f.childClasses))
                .mainClass(Fixtures.CHILD_MAIN)
                .arg("report=" + report)
                .inheritOutputTo(dir.resolve("target.log"));
        Fixtures.dynamicAgentOptions().forEach(builder::jvmArg);
        target = builder.start();
        targetPid = target.pid();
        // The report is written from main(), so the JVM is fully started before anything attaches to it.
        Properties seen = Fixtures.report(report);
        assertEquals(Long.toString(targetPid), seen.getProperty("pid"));
        targetVersion = seen.getProperty("java.specification.version");
    }

    @AfterEach
    void stopTarget() {
        if (target != null) {
            target.close();
        }
    }

    private Map<String, String> options(String... extra) {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("discoveryDir", dir.resolve("discovery").toString());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            options.put(extra[i], extra[i + 1]);
        }
        return options;
    }

    private long optionFilesLeft() throws IOException {
        Path discovery = dir.resolve("discovery");
        if (!Files.isDirectory(discovery)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(discovery)) {
            return files.filter(p -> p.getFileName().toString().startsWith("skillroy-options-")).count();
        }
    }

    @Test
    void attachLoadsTheAgentAndOnlyAVerifiedPingCounts() throws Exception {
        launchTarget();
        Endpoint endpoint = AttachSession.attach(targetPid, Fixtures.get().home, options());
        // Evidence in the test output of which target the Java 8 "0" reply path was exercised against.
        System.out.println("[attach test] attached to a target with java.specification.version=" + targetVersion);
        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(targetPid, endpoint.pid());
        assertEquals(LoadMode.ATTACH, endpoint.loadMode());
        assertTrue(endpoint.startTimeMs() > 0);
        assertTrue(SecureFiles.isValidToken(endpoint.token()));
        try (AgentClient client = AgentClient.connect(endpoint)) {
            Map<String, Object> ping = client.ping();
            assertEquals(endpoint.nonce(), ping.get("agentNonce"));
            assertEquals("ATTACH", ping.get("loadMode"));
        }
        assertEquals(0, optionFilesLeft(), "the options file is deleted after the handshake");
        assertEquals(List.of(endpoint), Discovery.list(dir.resolve("discovery")),
                "the agent's discovery file describes the same endpoint");
    }

    @Test
    void attachingRightAfterLaunchDoesNotEndTheStartingJvm() throws Exception {
        assumeTrue(AttachSession.attachModulePresent(), "jdk.attach is not available");
        Fixtures f = Fixtures.get();
        TargetLauncher.Builder builder = TargetLauncher.java(Fixtures.targetJava())
                .classPath(List.of(f.childClasses))
                .mainClass(Fixtures.CHILD_MAIN)
                .inheritOutputTo(dir.resolve("target.log"));
        Fixtures.dynamicAgentOptions().forEach(builder::jvmArg);
        target = builder.start();
        // No wait for the child here: a JVM that is still starting is ended by the attach signal, so attach itself
        // must give it time.
        Endpoint endpoint = AttachSession.attach(target.pid(), f.home, options());
        assertEquals(target.pid(), endpoint.pid());
        assertTrue(target.process().isAlive());
        // One second of slack for the OS's coarse process start time.
        long ageMs = System.currentTimeMillis() - target.process().info().startInstant()
                .map(java.time.Instant::toEpochMilli).orElse(0L);
        assertTrue(ageMs >= AttachSession.MIN_TARGET_AGE.toMillis() - 1000, "attached at age " + ageMs + " ms");
    }

    @Test
    void pathsTheJvmOrTheAgentWouldSplitAreRefused() {
        assertEquals(null, AttachSession.agentArgumentProblem("/a/skillroy-agent-bootstrap.jar", "t=/tmp/x y/o.p"));
        assertTrue(AttachSession.agentArgumentProblem("/a=b/skillroy-agent-bootstrap.jar", "t=/tmp/o.p")
                .contains("'='"));
        assertTrue(AttachSession.agentArgumentProblem("/a/skillroy-agent-bootstrap.jar", "t=/tmp/a;b/o.p")
                .contains("';'"));
        assertThrows(AttachFailedException.class,
                () -> AttachSession.checkAgentArgument("/a/skillroy-agent-bootstrap.jar", "t=/tmp/a;b/o.p"));
    }

    @Test
    void attachingAgainFindsTheRunningAgent() throws Exception {
        launchTarget();
        Endpoint first = AttachSession.attach(targetPid, Fixtures.get().home, options());
        Endpoint second = AttachSession.attach(targetPid, Fixtures.get().home, options());
        assertEquals(first.port(), second.port());
        assertEquals(first.nonce(), second.nonce(), "the agent keeps its nonce; the attach channel tells us which");
        assertEquals(first.token(), second.token(), "the token comes from the agent's token file");
        // Contract amendment A15: even an explicit new token and nonce are ignored by the running agent, and the
        // endpoint carries the values read back from it.
        Endpoint third = AttachSession.attach(targetPid, Fixtures.get().home,
                options("token", SecureFiles.newToken(), "nonce", SecureFiles.newNonce()));
        assertEquals(first, third);
        try (AgentClient client = AgentClient.connect(second)) {
            assertEquals(first.nonce(), client.ping().get("agentNonce"));
        }
        assertEquals(0, optionFilesLeft());
    }

    @Test
    void anAgentThatFailsToStartIsReportedWithItsError() throws Exception {
        launchTarget();
        AttachFailedException failed = assertThrows(AttachFailedException.class,
                () -> AttachSession.attach(targetPid, Fixtures.get().home, options("fakeFail", "attach boom")));
        assertTrue(failed.getMessage().contains("attach boom"), failed.getMessage());
        assertTrue(failed.getMessage().contains("skillroy.agent.error"), failed.getMessage());
        assertTrue(target.process().isAlive(), "a failed attach leaves the target running");
        assertEquals(0, optionFilesLeft());
    }

    @Test
    void listJvmsIncludesTheTarget() throws Exception {
        launchTarget();
        List<JvmInfo> jvms = AttachSession.listJvms();
        JvmInfo info = jvms.stream().filter(j -> j.pid() == targetPid).findFirst().orElseThrow(
                () -> new AssertionError("pid " + targetPid + " not in " + jvms));
        assertTrue(info.displayName().contains(Fixtures.CHILD_MAIN), info.displayName());
    }

    @Test
    void impossibleTargetsFailClearly() throws Exception {
        assumeTrue(AttachSession.attachModulePresent(), "jdk.attach is not available");
        AgentHome home = Fixtures.get().home;
        assertTrue(assertThrows(AttachFailedException.class, () -> AttachSession.attach(0, home, null))
                .getMessage().contains("invalid pid"));
        long self = ProcessHandle.current().pid();
        assertTrue(assertThrows(AttachFailedException.class, () -> AttachSession.attach(self, home, null))
                .getMessage().contains("own JVM"));

        Process process = new ProcessBuilder(Fixtures.currentJava().toString(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        process.waitFor();
        AttachFailedException gone = assertThrows(AttachFailedException.class,
                () -> AttachSession.attach(process.pid(), home, options()));
        assertTrue(gone.getMessage().contains("no running process"), gone.getMessage());
        assertTrue(assertThrows(AttachFailedException.class,
                () -> AttachSession.attach(process.pid(), home, options("token", "two words")))
                .getMessage().contains("usable token"));
        // P2-L1-F4: a supplied token shorter than 16 characters is refused before anything is written.
        assertTrue(assertThrows(AttachFailedException.class,
                () -> AttachSession.attach(process.pid(), home, options("token", "fifteen-chars-x")))
                .getMessage().contains("16 to 1024"));
        assertEquals(0, optionFilesLeft());
    }

    /**
     * Finding S18: a process the attach API does not list, whose executable is not java, is refused before any
     * signal is sent, so it survives.
     */
    @Test
    void aProcessThatIsNotAJvmIsRefusedBeforeAnySignal() throws Exception {
        assumeTrue(AttachSession.attachModulePresent(), "jdk.attach is not available");
        Path sleep = Path.of("/bin/sleep");
        assumeTrue(Files.isExecutable(sleep), "no /bin/sleep here");
        Process child = new ProcessBuilder(sleep.toString(), "60").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            AttachFailedException refused = assertThrows(AttachFailedException.class,
                    () -> AttachSession.attach(child.pid(), Fixtures.get().home, options()));
            assertTrue(refused.getMessage().contains("is not a JVM the attach API lists"), refused.getMessage());
            assertTrue(refused.getMessage().contains(AttachSession.ALLOW_UNLISTED), refused.getMessage());
            assertTrue(child.isAlive(), "no attach signal reached the process");
            assertEquals(0, optionFilesLeft());
        } finally {
            child.destroyForcibly();
        }
    }

    /** H2: the attached endpoint carries the session id the ping verified, the one in the discovery file. */
    @Test
    void theEndpointCarriesTheSessionOfTheDiscoveryFile() throws Exception {
        launchTarget();
        Endpoint endpoint = AttachSession.attach(targetPid, Fixtures.get().home, options());
        assertTrue(endpoint.sessionId() != null && endpoint.sessionId().length() == 32, endpoint.toString());
        Discovery.Entry entry = Discovery.entries(dir.resolve("discovery")).get(0);
        assertEquals(endpoint.sessionId(), entry.endpoint().sessionId());
        assertEquals(ai.skillroy.testjava.protocol.Protocol.VERSION, entry.protocol());
    }

    @Test
    void theAgentArgumentMustStayUnder1024Bytes() throws Exception {
        AttachSession.checkAgentArgument("/a/skillroy-agent-bootstrap.jar", "t=/tmp/x.properties");
        String longPath = "/" + "d".repeat(1000) + "/skillroy-options-1.properties";
        AttachFailedException tooLong = assertThrows(AttachFailedException.class,
                () -> AttachSession.checkAgentArgument("/a/skillroy-agent-bootstrap.jar", "t=" + longPath));
        assertTrue(tooLong.getMessage().contains("1024"), tooLong.getMessage());
        String exact = "x".repeat(1023 - "/j=".length());
        AttachSession.checkAgentArgument("/j", exact);
        assertThrows(AttachFailedException.class, () -> AttachSession.checkAgentArgument("/j", exact + "x"));
    }
}
