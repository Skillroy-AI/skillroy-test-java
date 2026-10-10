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

package ai.skillroy.testjava.agent.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Option parsing, precedence and the enable rule (phase1-contracts.md 2.4). */
class BootOptionsTest {

    @TempDir
    Path tmp;

    private static Map<String, String> env(String value) {
        return value == null ? Collections.<String, String>emptyMap()
                : Collections.singletonMap(BootOptions.ENV_ENABLED, value);
    }

    @Test
    void parsesTheArgumentString() {
        Map<String, String> args = BootOptions.parseArgs(" a=1 ; b = two=2;;flag; =x;c=;a=3");
        assertEquals("3", args.get("a"), "later keys win");
        assertEquals("two=2", args.get("b"), "only the first '=' splits");
        assertEquals("true", args.get("flag"));
        assertEquals("", args.get("c"));
        assertFalse(args.containsKey(""));
        assertEquals(4, args.size());
        assertTrue(BootOptions.parseArgs(null).isEmpty());
        assertTrue(BootOptions.parseArgs("").isEmpty());
    }

    @Test
    void systemPropertiesUnderThePrefixAreOptionsExceptTheOnesTheAgentPublishes() {
        Properties sys = new Properties();
        sys.setProperty("skillroy.agent.port", "4000");
        sys.setProperty("skillroy.agent.modules", "/m.jar");
        sys.setProperty("skillroy.agent.enabled", "true");
        sys.setProperty("skillroy.agent.error", "old");
        sys.setProperty("skillroy.agent.loadMode", "JAVAAGENT");
        sys.setProperty("skillroy.agent.protocol", "0.2");
        sys.setProperty("skillroy.agent.discoveryFile", "/d.json");
        sys.setProperty("skillroy.agent.session", "00112233445566778899aabbccddeeff");
        sys.setProperty("skillroy.agent.", "empty key");
        sys.setProperty("other.prop", "x");
        Map<String, String> options = BootOptions.fromSystemProperties(sys);
        assertEquals("4000", options.get("port"));
        assertEquals("/m.jar", options.get("modules"));
        assertEquals(2, options.size(), String.valueOf(options));
    }

    @Test
    void precedenceIsSystemPropertiesThenFileThenArguments() throws IOException {
        File file = tmp.resolve("opts.properties").toFile();
        write(file, "port=2000\nreadOnly=true\nnonce=from-file\n# comment\nhome=/hé\n");
        Properties sys = new Properties();
        sys.setProperty("skillroy.agent.port", "1000");
        sys.setProperty("skillroy.agent.readOnly", "false");
        sys.setProperty("skillroy.agent.eventRing", "500");
        Map<String, String> args = BootOptions.parseArgs("t=" + file + ";port=3000");
        Map<String, String> merged = BootOptions.resolve(BootOptions.ATTACH, args, sys, env(null));
        assertEquals("3000", merged.get("port"), "arguments beat the file");
        assertEquals("true", merged.get("readOnly"), "the file beats system properties");
        assertEquals("500", merged.get("eventRing"), "system properties beat defaults");
        assertEquals("from-file", merged.get("nonce"));
        assertEquals("/hé", merged.get("home"), "UTF-8 file");
        assertEquals("false", merged.get("enabled"));
    }

    @Test
    void theOptionsFileCanBeNamedByConfigOrBySystemProperty() throws IOException {
        File file = tmp.resolve("c.properties").toFile();
        writeOwnerOnly(file, "token=abc\n");
        Map<String, String> viaConfig = BootOptions.resolve(BootOptions.ATTACH,
                BootOptions.parseArgs("config=" + file), new Properties(), env(null));
        assertEquals("abc", viaConfig.get("token"));
        Properties sys = new Properties();
        sys.setProperty("skillroy.agent.t", file.getPath());
        assertEquals("abc", BootOptions.resolve(BootOptions.JAVAAGENT, new LinkedHashMap<String, String>(), sys,
                env(null)).get("token"));
        IOException missing = assertThrows(IOException.class, () -> BootOptions.resolve(BootOptions.ATTACH,
                BootOptions.parseArgs("t=" + tmp.resolve("nope")), new Properties(), env(null)));
        assertTrue(missing.getMessage().contains("nope"));
    }

    @Test
    void javaAgentIsEnabledOnlyBySystemPropertyOrEnvironment() throws IOException {
        File file = tmp.resolve("e.properties").toFile();
        write(file, "enabled=true\n");
        Map<String, String> args = BootOptions.parseArgs("enabled=true;t=" + file);
        assertEquals("false", BootOptions.resolve(BootOptions.JAVAAGENT, args, new Properties(), env(null))
                .get("enabled"), "neither the argument string nor its file may enable JAVAAGENT");
        Properties sys = new Properties();
        sys.setProperty("skillroy.agent.enabled", "TRUE");
        assertEquals("true", BootOptions.resolve(BootOptions.JAVAAGENT, args, sys, env(null)).get("enabled"));
        assertEquals("true", BootOptions.resolve(BootOptions.JAVAAGENT, args, new Properties(), env("true"))
                .get("enabled"));
        assertEquals("false", BootOptions.resolve(BootOptions.JAVAAGENT, args, new Properties(), env("1"))
                .get("enabled"), "only the word true counts");
        Properties disabled = new Properties();
        disabled.setProperty("skillroy.agent.enabled", "false");
        assertEquals("false", BootOptions.resolve(BootOptions.JAVAAGENT,
                BootOptions.parseArgs("enabled=true"), disabled, env(null)).get("enabled"));
    }

    @Test
    void attachAcceptsEnabledFromItsArgumentsOrFile() throws IOException {
        File file = tmp.resolve("a.properties").toFile();
        write(file, "enabled=true\n");
        assertEquals("true", BootOptions.resolve(BootOptions.ATTACH, BootOptions.parseArgs("t=" + file),
                new Properties(), env(null)).get("enabled"));
        assertEquals("true", BootOptions.resolve(BootOptions.ATTACH, BootOptions.parseArgs("enabled=true"),
                new Properties(), env(null)).get("enabled"));
        assertEquals("false", BootOptions.resolve(BootOptions.ATTACH, BootOptions.parseArgs("port=0"),
                new Properties(), env(null)).get("enabled"));
    }

    @Test
    void embeddedAcceptsEnabledFromTheHostsPropertiesButNotFromTheirFile() throws IOException {
        File file = tmp.resolve("x.properties").toFile();
        write(file, "enabled=true\n");
        Properties host = new Properties();
        host.setProperty("enabled", " true ");
        assertEquals("true", BootOptions.resolve(BootOptions.EMBEDDED, BootOptions.fromProperties(host),
                new Properties(), env(null)).get("enabled"));
        Properties viaFile = new Properties();
        viaFile.setProperty("t", file.getPath());
        assertEquals("false", BootOptions.resolve(BootOptions.EMBEDDED, BootOptions.fromProperties(viaFile),
                new Properties(), env(null)).get("enabled"));
        assertTrue(BootOptions.fromProperties(null).isEmpty());
    }

    @Test
    void helpers() {
        assertTrue(BootOptions.isTrue(" True "));
        assertFalse(BootOptions.isTrue("yes"));
        assertFalse(BootOptions.isTrue(null));
        assertEquals("b", BootOptions.firstNonEmpty(null, " ", " b ", "c"));
        assertNull(BootOptions.firstNonEmpty(null, ""));
    }

    /**
     * Phase 6 security fix, pass 2, P2-L1-F3: an options file another user could have written or replaced is refused
     * before anything in it is used, and one that holds a token must be owner-only, as the controller writes it.
     */
    @Test
    void anOptionsFileOthersCouldHaveWrittenOrReadIsRefused() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        File shared = tmp.resolve("shared.properties").toFile();
        write(shared, "enabled=true\n");
        Files.setPosixFilePermissions(shared.toPath(), PosixFilePermissions.fromString("rw-rw-r--"));
        IOException writable = assertThrows(IOException.class, () -> BootOptions.resolve(BootOptions.ATTACH,
                BootOptions.parseArgs("t=" + shared), new Properties(), env(null)));
        assertTrue(writable.getMessage().contains("is refused: it is writable by group or others"),
                writable.getMessage());

        File readable = tmp.resolve("readable.properties").toFile();
        write(readable, "token=0123456789abcdef0123\nenabled=true\n");
        Files.setPosixFilePermissions(readable.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
        IOException exposed = assertThrows(IOException.class, () -> BootOptions.resolve(BootOptions.ATTACH,
                BootOptions.parseArgs("t=" + readable), new Properties(), env(null)));
        assertTrue(exposed.getMessage().contains("holds a token and is readable by group or others"),
                exposed.getMessage());
        assertFalse(exposed.getMessage().contains("0123456789abcdef0123"), "the token is never echoed");

        File config = tmp.resolve("config.properties").toFile();
        write(config, "readOnly=true\n");
        Files.setPosixFilePermissions(config.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals("true", BootOptions.resolve(BootOptions.ATTACH, BootOptions.parseArgs("t=" + config),
                new Properties(), env(null)).get("readOnly"), "an operator's file without a token may be readable");

        File real = tmp.resolve("real.properties").toFile();
        writeOwnerOnly(real, "token=0123456789abcdef0123\nenabled=true\n");
        assertEquals("true", BootOptions.resolve(BootOptions.ATTACH, BootOptions.parseArgs("t=" + real),
                new Properties(), env(null)).get("enabled"), "owner-only, as the controller writes it");
        Path link = tmp.resolve("link.properties");
        Files.createSymbolicLink(link, real.toPath());
        IOException linked = assertThrows(IOException.class, () -> BootOptions.resolve(BootOptions.ATTACH,
                BootOptions.parseArgs("t=" + link), new Properties(), env(null)));
        assertTrue(linked.getMessage().contains("is refused: it is a symbolic link"), linked.getMessage());
    }

    /** P2-L1-F3: the owner rule, by uid when both are known (root accepted), else by name. */
    @Test
    void theOwnerOfAnOptionsFileMustBeTheCurrentUserOrRoot() {
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        assertNull(OptionsFileCheck.posixProblem("alice", 501L, ownerOnly, 501L, "alice", true));
        assertNull(OptionsFileCheck.posixProblem("root", 0L, ownerOnly, 501L, "alice", true));
        assertEquals("it is owned by mallory, not by the current user",
                OptionsFileCheck.posixProblem("mallory", 502L, ownerOnly, 501L, "alice", false));
        assertEquals("it is owned by alice, not by the current user",
                OptionsFileCheck.posixProblem("alice", 502L, ownerOnly, 501L, "alice", false),
                "the uid decides when both are known, not the name");
        long unknown = OptionsFileCheck.UNKNOWN_UID;
        assertNull(OptionsFileCheck.posixProblem("alice", unknown, ownerOnly, unknown, "alice", true));
        assertTrue(OptionsFileCheck.posixProblem("mallory", unknown, ownerOnly, unknown, "alice", true)
                .contains("owned by mallory"));
        assertTrue(OptionsFileCheck.posixProblem("alice", 501L, PosixFilePermissions.fromString("rw-----w-"), 501L,
                "alice", false).contains("writable by group or others"));
        assertNull(OptionsFileCheck.posixProblem("alice", 501L, PosixFilePermissions.fromString("rw-r-----"), 501L,
                "alice", false), "readable, but no token");
    }

    /** P2-L1-F3: the ACL judgement on built values (the Windows branch is not run on a Windows host here). */
    @Test
    void theAclRuleOfAnOptionsFile() {
        UserPrincipal me = new Principal("HOST\\alice");
        UserPrincipal admins = new Principal("BUILTIN\\Administrators");
        UserPrincipal users = new Principal("BUILTIN\\Users");
        List<UserPrincipal> builtIns = Collections.singletonList(admins);
        List<AclEntry> ownerOnly = Collections.singletonList(allow(me, AclEntryPermission.values()));
        assertNull(OptionsFileCheck.aclProblem(me, ownerOnly, me, builtIns, OptionsFileCheck.ANY_RIGHT));
        assertNull(OptionsFileCheck.aclProblem(admins, ownerOnly, me, builtIns, OptionsFileCheck.ANY_RIGHT),
                "an elevated administrator's file");
        assertTrue(OptionsFileCheck.aclProblem(users, ownerOnly, me, builtIns, OptionsFileCheck.WRITE_RIGHTS)
                .contains("owned by BUILTIN\\Users"));
        List<AclEntry> readable = Arrays.asList(allow(me, AclEntryPermission.values()),
                allow(users, AclEntryPermission.READ_DATA));
        assertNull(OptionsFileCheck.aclProblem(me, readable, me, builtIns, OptionsFileCheck.WRITE_RIGHTS),
                "readable by others, no token");
        assertEquals("its ACL allows BUILTIN\\Users READ_DATA",
                OptionsFileCheck.aclProblem(me, readable, me, builtIns, OptionsFileCheck.ANY_RIGHT));
        List<AclEntry> writable = Arrays.asList(allow(me, AclEntryPermission.values()),
                allow(users, AclEntryPermission.APPEND_DATA));
        assertEquals("its ACL allows BUILTIN\\Users APPEND_DATA",
                OptionsFileCheck.aclProblem(me, writable, me, builtIns, OptionsFileCheck.WRITE_RIGHTS));
        List<AclEntry> denied = Arrays.asList(allow(me, AclEntryPermission.values()), AclEntry.newBuilder()
                .setType(AclEntryType.DENY).setPrincipal(users).setPermissions(AclEntryPermission.WRITE_DATA).build());
        assertNull(OptionsFileCheck.aclProblem(me, denied, me, builtIns, OptionsFileCheck.ANY_RIGHT));
        assertEquals("the current user cannot be determined",
                OptionsFileCheck.aclProblem(me, ownerOnly, null, builtIns, OptionsFileCheck.WRITE_RIGHTS));
    }

    private static AclEntry allow(UserPrincipal who, AclEntryPermission... rights) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who).setPermissions(rights).build();
    }

    /** A principal for the built ACL values, equal by name. */
    private static final class Principal implements UserPrincipal {
        private final String name;

        Principal(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Principal && ((Principal) other).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(Charset.forName("UTF-8")));
    }

    /** As the controller writes an options file that holds a token: readable and writable by its owner only. */
    static void writeOwnerOnly(File file, String text) throws IOException {
        write(file, text);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
        }
    }
}
