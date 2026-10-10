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

import ai.skillroy.testjava.protocol.Auth;
import ai.skillroy.testjava.protocol.LoadMode;
import ai.skillroy.testjava.protocol.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DiscoveryTest {

    private static final long PID = ProcessHandle.current().pid();
    private static final long START = ManagementFactory.getRuntimeMXBean().getStartTime();

    @TempDir
    Path dir;

    private FakeAgent agent;

    @BeforeEach
    void startAgent() throws IOException {
        agent = new FakeAgent();
    }

    @AfterEach
    void stopAgent() {
        agent.close();
    }

    private static long deadPid() throws Exception {
        Process process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-version")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return process.pid();
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    @Test
    void aMissingOrEmptyDirectoryListsNothing() {
        assertEquals(List.of(), Discovery.list(dir));
        assertEquals(List.of(), Discovery.list(dir.resolve("missing")));
        assertEquals(Optional.empty(), Discovery.forPid(PID, dir));
    }

    @Test
    void aLiveEntryIsListedWithItsToken() throws Exception {
        Path file = agent.writeDiscovery(dir);
        List<Endpoint> endpoints = Discovery.list(dir);
        assertEquals(List.of(new Endpoint("127.0.0.1", agent.port(), agent.token, agent.agentNonce, PID, START,
                LoadMode.JAVAAGENT, agent.sessionId)), endpoints);
        Discovery.Entry entry = Discovery.entries(dir).get(0);
        assertEquals("com.example.Main --flag", entry.mainCommand());
        assertEquals(Protocol.VERSION, entry.protocol());
        assertEquals(file, entry.file());
        assertEquals(endpoints.get(0), Discovery.forPid(PID, dir).orElseThrow());
        assertEquals(Optional.empty(), Discovery.forPid(PID + 1, dir));
    }

    @Test
    void aStalePidIsSkipped() throws Exception {
        agent.pid = deadPid();
        agent.writeDiscovery(dir);
        assertEquals(List.of(), Discovery.list(dir));
    }

    @Test
    void aWrongStartTimeIsSkipped() throws Exception {
        assumeTrue(ProcessHandle.current().info().startInstant().isPresent(), "OS reports no process start time");
        agent.startTimeMs = START - TimeUnit.HOURS.toMillis(1);
        agent.writeDiscovery(dir);
        assertEquals(List.of(), Discovery.list(dir));
        assertTrue(Discovery.isLive(PID, START));
        assertTrue(Discovery.isLive(PID, START + 30_000), "within the 60 s tolerance");
        assertTrue(Discovery.isLive(PID, 0), "an unknown start time is not checked");
    }

    @Test
    void garbageFilesAreSkipped() throws Exception {
        String prefix = "skillroy-" + PID + "-" + START;
        write("skillroy-abc.json", "{}");
        write("skillroy-" + PID + ".json", "{}");
        write(prefix + ".json.tmp", "{}");
        write(prefix + ".json", "{not json");
        Files.createDirectories(dir.resolve("skillroy-" + PID + "-" + (START + 1) + ".json"));
        assertEquals(List.of(), Discovery.list(dir));

        write(prefix + ".json", "{\"pid\":" + (PID + 1) + ",\"startTimeMs\":" + START + ",\"port\":1}");
        assertEquals(List.of(), Discovery.list(dir), "content contradicts the file name");
        write(prefix + ".json", "{\"pid\":" + PID + ",\"startTimeMs\":" + START + ",\"port\":70000}");
        assertEquals(List.of(), Discovery.list(dir), "port out of range");
        write(prefix + ".json", "{\"pid\":" + PID + ",\"startTimeMs\":" + START + "}");
        assertEquals(List.of(), Discovery.list(dir), "no port");
        write(prefix + ".json", "[1,2]");
        assertEquals(List.of(), Discovery.list(dir), "not an object");

        write(prefix + ".json", "{\"pid\":" + PID + ",\"startTimeMs\":" + START + ",\"port\":4321,"
                + "\"loadMode\":\"FUTURE_MODE\"}");
        Endpoint minimal = Discovery.list(dir).get(0);
        assertEquals(4321, minimal.port());
        assertNull(minimal.token());
        assertNull(minimal.nonce());
        assertNull(minimal.loadMode());
    }

    @Test
    void theTokenIsReadTrimmedAndValidated() throws Exception {
        agent.writeDiscovery(dir);
        Path tokenFile = dir.resolve("skillroy-" + PID + "-" + START + ".token");
        Files.writeString(tokenFile, "  abc123-0123456789  \n");
        assertEquals("abc123-0123456789", Discovery.list(dir).get(0).token());
        Files.writeString(tokenFile, "fifteen-chars-x");
        assertNull(Discovery.list(dir).get(0).token(), "shorter than 16 characters (P2-L1-F4)");
        Files.writeString(tokenFile, "two words");
        assertNull(Discovery.list(dir).get(0).token());
        Files.delete(tokenFile);
        assertNull(Discovery.list(dir).get(0).token());
    }

    @Test
    void awaitPidReturnsOnceTheFileAppearsAndThePingAnswers() throws Exception {
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(300);
                agent.writeDiscovery(dir);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Endpoint endpoint = Discovery.awaitPid(PID, dir, Duration.ofSeconds(30));
        writer.get(10, TimeUnit.SECONDS);
        assertEquals(agent.endpoint(), endpoint);
        assertTrue(agent.requests("/ping").size() >= 1, "a live agent was required");
    }

    @Test
    void awaitPidAcceptsAnExtremeTimeout() throws Exception {
        agent.writeDiscovery(dir);
        assertEquals(agent.endpoint(), Discovery.awaitPid(PID, dir, Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void awaitPidOnThisJvmReportsItsAgentError() {
        String key = "skillroy.agent.error";
        String before = System.getProperty(key);
        System.setProperty(key, "EMBEDDED start failed: simulated");
        try {
            AgentLostException lost = assertThrows(AgentLostException.class,
                    () -> Discovery.awaitPid(PID, dir, Duration.ofMillis(-1)));
            assertTrue(lost.getMessage().contains("skillroy.agent.error: EMBEDDED start failed: simulated"),
                    lost.getMessage());
        } finally {
            if (before == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, before);
            }
        }
    }

    @Test
    void aDiscoveryFileThatIsASymbolicLinkIsSkipped() throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path real = agent.writeDiscovery(elsewhere);
        Path link = dir.resolve(real.getFileName());
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here: " + e);
        }
        assertEquals(1, Discovery.list(elsewhere).size());
        assertEquals(List.of(), Discovery.list(dir));
    }

    @Test
    void filesAndDirectoriesWeCreateAreOurs() throws Exception {
        assertTrue(SecureFiles.ownedByCurrentUser(dir));
        Path options = SecureFiles.writeOptionsFile(dir.resolve("o"), java.util.Map.of("token", "t", "k", "v \u00e9"));
        assertTrue(SecureFiles.ownedByCurrentUser(options));
        java.util.Properties read = new java.util.Properties();
        try (java.io.InputStream in = Files.newInputStream(options)) {
            read.load(in);
        }
        assertEquals("v \u00e9", read.getProperty("k"));
        assertThrows(IOException.class, () -> SecureFiles.ensureOwnerOnlyDirectory(options), "a file, not a directory");
    }

    @Test
    void awaitPidFailsAtOnceForAProcessThatIsNotRunning() throws Exception {
        long gone = deadPid();
        long start = System.nanoTime();
        AgentLostException lost = assertThrows(AgentLostException.class,
                () -> Discovery.awaitPid(gone, dir, Duration.ofSeconds(30)));
        assertTrue(lost.getMessage().contains("not running"), lost.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);
    }

    @Test
    void awaitPidTimesOutNamingTheLastProblem() throws Exception {
        AgentLostException none = assertThrows(AgentLostException.class,
                () -> Discovery.awaitPid(PID, dir, Duration.ofMillis(300)));
        assertTrue(none.getMessage().contains("no live discovery file"), none.getMessage());

        agent.writeDiscovery(dir);
        agent.close();
        AgentLostException noPing = assertThrows(AgentLostException.class,
                () -> Discovery.awaitPid(PID, dir, Duration.ofMillis(300)));
        assertTrue(noPing.getMessage().contains("/ping failed"), noPing.getMessage());
    }

    @Test
    void awaitPidWaitsForAReadableToken() throws Exception {
        agent.writeDiscovery(dir);
        Files.delete(dir.resolve("skillroy-" + PID + "-" + START + ".token"));
        AgentLostException lost = assertThrows(AgentLostException.class,
                () -> Discovery.awaitPid(PID, dir, Duration.ofMillis(300)));
        assertTrue(lost.getMessage().contains("token file"), lost.getMessage());
    }

    // ---- findings S6/S28: the token file is read only inside the directory, by its own name ----

    private Path rewriteTokenFile(Path discoveryFile, Path tokenFile) throws IOException {
        String json = Files.readString(discoveryFile);
        String quoted = ai.skillroy.testjava.protocol.Json.write(tokenFile.toString());
        json = json.replaceFirst("\"tokenFile\":\"[^\"]*\"", java.util.regex.Matcher.quoteReplacement(
                "\"tokenFile\":" + quoted));
        FakeAgent.writeOwnerOnly(discoveryFile, json);
        return discoveryFile;
    }

    @Test
    void aTokenFileOutsideTheDirectoryGivesNoToken() throws Exception {
        Path discovery = dir.resolve("discovery");
        Path file = agent.writeDiscovery(discovery);
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path outside = elsewhere.resolve("skillroy-" + PID + "-" + START + ".token");
        FakeAgent.writeOwnerOnly(outside, "outside-secret");
        rewriteTokenFile(file, outside);
        assertNull(Discovery.list(discovery).get(0).token(), "same name, other directory");
        rewriteTokenFile(file, discovery.resolve("..").resolve("discovery")
                .resolve("skillroy-" + PID + "-" + START + ".token"));
        assertEquals(agent.token, Discovery.list(discovery).get(0).token(), "a path that normalises into it is fine");
    }

    @Test
    void aWronglyNamedTokenFileGivesNoToken() throws Exception {
        Path file = agent.writeDiscovery(dir);
        Path other = dir.resolve("vault-token");
        FakeAgent.writeOwnerOnly(other, "token-shaped-secret");
        rewriteTokenFile(file, other);
        assertNull(Discovery.list(dir).get(0).token());
        Path otherAgent = dir.resolve("skillroy-" + (PID + 1) + "-" + START + ".token");
        FakeAgent.writeOwnerOnly(otherAgent, "another-agents-token");
        rewriteTokenFile(file, otherAgent);
        assertNull(Discovery.list(dir).get(0).token(), "another entry's token file");
    }

    @Test
    void aSymbolicLinkAsTokenFileGivesNoToken() throws Exception {
        agent.writeDiscovery(dir);
        Path tokenFile = dir.resolve("skillroy-" + PID + "-" + START + ".token");
        Path target = dir.resolve("real.token");
        Files.move(tokenFile, target);
        try {
            Files.createSymbolicLink(tokenFile, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here: " + e);
        }
        assertNull(Discovery.list(dir).get(0).token());
    }

    @Test
    void aTokenFileOthersCanReadOrWithSeveralLinksGivesNoToken() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX permissions");
        agent.writeDiscovery(dir);
        Path tokenFile = dir.resolve("skillroy-" + PID + "-" + START + ".token");
        Files.setPosixFilePermissions(tokenFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        assertNull(Discovery.list(dir).get(0).token());
        Files.setPosixFilePermissions(tokenFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertEquals(agent.token, Discovery.list(dir).get(0).token());
        try {
            Files.createLink(dir.resolve("hard.link"), tokenFile);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no hard links here: " + e);
        }
        assertNull(Discovery.list(dir).get(0).token(), "a second hard link could lead anywhere");
    }

    @Test
    void anEntryWithStartTimeZeroIsSkippedAndNoRequestReachesItsPort() throws Exception {
        agent.writeDiscovery(dir);
        Path real = dir.resolve("skillroy-" + PID + "-" + START + ".json");
        String json = Files.readString(real).replace("\"startTimeMs\":" + START, "\"startTimeMs\":0");
        Files.delete(real);
        FakeAgent.writeOwnerOnly(dir.resolve("skillroy-" + PID + "-0.json"), json);
        assertEquals(List.of(), Discovery.list(dir));
        assertThrows(AgentLostException.class, () -> Discovery.awaitPid(PID, dir, Duration.ofMillis(300)));
        assertEquals(List.of(), agent.requests, "nothing was sent to the port the file names");
    }

    @Test
    void theSessionIdOfTheFileIsReadAndCheckedByThePing() throws Exception {
        agent.writeDiscovery(dir);
        assertEquals(agent.sessionId, Discovery.list(dir).get(0).sessionId());
        assertEquals(agent.endpoint(), Discovery.awaitPid(PID, dir, Duration.ofSeconds(30)));
        agent.sessionId = Auth.newSessionId();
        AgentLostException lost = assertThrows(AgentLostException.class,
                () -> Discovery.awaitPid(PID, dir, Duration.ofMillis(300)));
        assertTrue(lost.getMessage().contains("session"), lost.getMessage());
    }

    /** Findings S5/S6: a launcher connects with its own token and nonce; the token file is not read at all. */
    @Test
    void awaitPidWithTheLaunchersTokenIgnoresTheTokenFileAndChecksTheNonce() throws Exception {
        agent.writeDiscovery(dir);
        Files.delete(dir.resolve("skillroy-" + PID + "-" + START + ".token"));
        assertEquals(agent.endpoint(), Discovery.awaitPid(PID, dir, Duration.ofSeconds(30), agent.token,
                agent.agentNonce));
        AgentLostException wrongNonce = assertThrows(AgentLostException.class, () -> Discovery.awaitPid(PID, dir,
                Duration.ofMillis(300), agent.token, "another-launch-nonce"));
        assertTrue(wrongNonce.getMessage().contains("agentNonce"), wrongNonce.getMessage());
        AgentLostException wrongToken = assertThrows(AgentLostException.class, () -> Discovery.awaitPid(PID, dir,
                Duration.ofMillis(300), SecureFiles.newToken(), agent.agentNonce));
        assertTrue(wrongToken.getMessage().contains("401"), wrongToken.getMessage());
    }

    /** Finding S23: a peer that stalls its ping reply cannot hold awaitPid past its timeout plus one ping. */
    @Test
    void awaitPidAgainstAStallingPeerEndsWithinItsTimeout() throws Exception {
        agent.writeDiscovery(dir);
        agent.pingOverride = new FakeAgent.Stall(100, "{");
        long start = System.nanoTime();
        AgentLostException lost = assertThrows(AgentLostException.class,
                () -> Discovery.awaitPid(PID, dir, Duration.ofMillis(500)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(lost.getMessage().contains("no reply within"), lost.getMessage());
        assertTrue(elapsedMs < 500 + 10_000 + 2_000, "elapsed " + elapsedMs);
    }

    // ---- amendment A23: stale files are deleted when a trusted directory is listed ----

    @Test
    void listingDeletesTheFilesOfGoneProcessesOnly() throws Exception {
        agent.writeDiscovery(dir);
        long gone = deadPid();
        Path staleJson = dir.resolve("skillroy-" + gone + "-" + START + ".json");
        Path staleToken = dir.resolve("skillroy-" + gone + "-" + START + ".token");
        FakeAgent.writeOwnerOnly(staleJson, "{}");
        FakeAgent.writeOwnerOnly(staleToken, "t");
        Path reusedPid = dir.resolve("skillroy-" + PID + "-" + (START - TimeUnit.HOURS.toMillis(2)) + ".json");
        FakeAgent.writeOwnerOnly(reusedPid, "{}");
        Path zero = dir.resolve("skillroy-" + gone + "-0.json");
        FakeAgent.writeOwnerOnly(zero, "{}");
        Path options = dir.resolve("skillroy-options-0123.properties");
        FakeAgent.writeOwnerOnly(options, "x=y");
        Path staleDir = Files.createDirectories(dir.resolve("skillroy-" + gone + "-" + (START + 1) + ".json"));

        assertEquals(1, Discovery.list(dir, false).size());
        assertTrue(Files.exists(staleJson) && Files.exists(staleToken), "switched off: nothing deleted");
        assertEquals(1, Discovery.entries(dir).size());
        assertFalse(Files.exists(staleJson), "no process with that pid");
        assertFalse(Files.exists(staleToken));
        boolean osReportsStart = ProcessHandle.current().info().startInstant().isPresent();
        assertEquals(!osReportsStart, Files.exists(reusedPid), "a live pid with another start time is stale");
        assertTrue(Files.exists(zero), "a start time of 0 is never judged");
        assertTrue(Files.exists(options), "options files are not discovery files");
        assertTrue(Files.isDirectory(staleDir), "only files are deleted");
        assertTrue(Files.exists(dir.resolve("skillroy-" + PID + "-" + START + ".json")), "the live entry stays");
        assertTrue(Files.exists(dir.resolve("skillroy-" + PID + "-" + START + ".token")));
    }

    @Test
    void anUntrustedDirectoryIsNeverCleaned() throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX permissions");
        Path shared = Files.createDirectories(dir.resolve("shared"));
        Path stale = shared.resolve("skillroy-" + deadPid() + "-" + START + ".json");
        FakeAgent.writeOwnerOnly(stale, "{}");
        Files.setPosixFilePermissions(shared, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        assertEquals(List.of(), Discovery.list(shared));
        assertTrue(Files.exists(stale));
    }
}
