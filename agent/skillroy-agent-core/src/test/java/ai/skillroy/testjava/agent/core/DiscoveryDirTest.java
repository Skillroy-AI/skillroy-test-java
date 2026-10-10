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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.Protocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discovery directory of contract amendment A3: per-user default, owner-only creation, and no token or discovery
 * file in a directory another user owns or others may write to.
 */
class DiscoveryDirTest {

    private static final String PROP_ERROR = Protocol.PROP_PREFIX + "error";

    @TempDir
    Path tmp;

    private AgentRuntime runtime;

    @BeforeEach
    void reset() {
        TestState.reset();
        System.clearProperty(PROP_ERROR);
    }

    @AfterEach
    void stop() {
        if (runtime != null) {
            runtime.close();
        }
        System.clearProperty(PROP_ERROR);
    }

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static AgentRuntime start(Map<String, String> options) throws Exception {
        options.put("enabled", "true");
        return AgentRuntime.start(options, null, DiscoveryDirTest.class.getClassLoader(), "EMBEDDED", null);
    }

    @Test
    void theDefaultIsPerUserWithTheNameReducedToSafeCharacters() {
        assertEquals("alice", SecureFiles.safeUserName("alice"));
        assertEquals("DOMAINjohn.doe_1-x", SecureFiles.safeUserName("DOMAIN\\john.doe_1-x"));
        assertEquals("johndoeexample.com", SecureFiles.safeUserName("john doe@example.com"));
        assertEquals("..", SecureFiles.safeUserName("../"), "a separator is dropped; the prefix keeps dots harmless");
        assertEquals("", SecureFiles.safeUserName(null));
        assertEquals(new File(System.getProperty("java.io.tmpdir"),
                "skillroy-" + SecureFiles.safeUserName(System.getProperty("user.name"))),
                SecureFiles.defaultDiscoveryDir());
        assertEquals(SecureFiles.defaultDiscoveryDir().getPath(),
                AgentRuntime.resolveOptions(null).get("discoveryDir"));
    }

    @Test
    void aMissingDirectoryIsCreatedOwnerOnly() throws Exception {
        Path dir = tmp.resolve("made/by/agent");
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("discoveryDir", dir.toString());
        runtime = start(options);
        assertTrue(new File(runtime.discoveryFile()).isFile());
        assertEquals(dir.toAbsolutePath().toString(), new File(runtime.discoveryFile()).getParent());
        if (posix(dir)) {
            assertEquals("rwx------", permissions(dir));
            assertEquals("rwx------", permissions(dir.getParent()), "parents the agent creates too");
        }
        assertNull(System.getProperty(PROP_ERROR));
    }

    @Test
    void aGeneratedTokenIsNeverWrittenIntoADirectoryOthersMayWriteTo() throws Exception {
        Assumptions.assumeTrue(posix(tmp), "POSIX permissions only");
        Path shared = Files.createDirectory(tmp.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        List<String> before = AgentHarness.skillroyThreads();
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("discoveryDir", shared.toString());
        IOException e = assertThrows(IOException.class, () -> start(options));
        assertTrue(e.getMessage().contains("cannot write the token file"), e.getMessage());
        assertTrue(e.getMessage().contains("writable by group or others (rwxrwxrwx)"), e.getMessage());
        assertEquals(0, shared.toFile().list().length, "nothing was written: " + String.join(",",
                shared.toFile().list()));
        assertNull(System.getProperty(Protocol.PROP_PREFIX + "port"), "no discovery properties");
        assertTrue(TestState.INSTALLED.isEmpty(), "refused before any module was installed: " + TestState.INSTALLED);
        AgentHarness.await("start threads gone", 30000, () -> {
            List<String> after = AgentHarness.skillroyThreads();
            after.removeAll(before);
            return after.isEmpty();
        });

        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class, () -> start(options), "group-writable is refused as well");
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"));
        runtime = start(options);
        assertTrue(new File(runtime.discoveryFile()).isFile(), "readable by others is fine: the files are 0600");
    }

    @Test
    void withASuppliedTokenTheAgentServesButWritesNothingThereAndReportsWhy() throws Exception {
        Assumptions.assumeTrue(posix(tmp), "POSIX permissions only");
        Path shared = Files.createDirectory(tmp.resolve("shared"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("discoveryDir", shared.toString());
        options.put("token", "supplied-token-0123456789");
        options.put("nonce", "n-1");
        runtime = start(options);
        assertNull(runtime.discoveryFile());
        assertEquals(0, shared.toFile().list().length);
        String error = System.getProperty(PROP_ERROR);
        assertTrue(error != null && error.contains("cannot write the discovery file")
                && error.contains("writable by group or others"), String.valueOf(error));
        assertEquals(String.valueOf(runtime.port()), System.getProperty(Protocol.PROP_PREFIX + "port"),
                "the controller that supplied the token reaches it through the system properties");
        assertTrue(String.valueOf(runtime.context().log().tail(100)).contains("cannot write the token file"));
    }

    @Test
    void aSymbolicLinkToTheUsersOwnDirectoryIsAccepted() throws Exception {
        Assumptions.assumeTrue(posix(tmp), "POSIX permissions only");
        Path real = Files.createDirectory(tmp.resolve("real"));
        Path link = Files.createSymbolicLink(tmp.resolve("link"), real);
        SecureFiles.checkSafe(link);
        SecureFiles.writeOwnerOnly(link.resolve("f.json"), "{}".getBytes(Charset.forName("UTF-8")));
        assertTrue(real.resolve("f.json").toFile().isFile());
        AgentHarness.assertOwnerOnly(real.resolve("f.json").toFile());
    }

    @Test
    void aDirectoryOwnedByAnotherUserIsRefused() throws Exception {
        Assumptions.assumeTrue(posix(tmp), "POSIX permissions only");
        Path foreign = Files.createDirectory(tmp.resolve("foreign"));
        try {
            Files.setAttribute(foreign, "unix:uid", Integer.valueOf(65534));
        } catch (IOException | RuntimeException e) {
            Assumptions.assumeTrue(false, "changing a file's owner needs root: " + e);
        }
        IOException e = assertThrows(IOException.class, () -> SecureFiles.checkSafe(foreign));
        assertTrue(e.getMessage().contains("not by the current user"), e.getMessage());
        Map<String, String> options = new LinkedHashMap<String, String>();
        options.put("discoveryDir", foreign.toString());
        assertThrows(IOException.class, () -> start(options));
        assertFalse(foreign.toFile().list().length > 0, "nothing was written");
    }
}
