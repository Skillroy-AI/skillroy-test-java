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

package ai.skillroy.testjava.data;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** phase2-contracts.md 4 and 14.1 item 4: the credentials file. */
class CredentialsTest {

    private static final String STAFF_PW = "Stf-Secret-0100";
    private static final String JDBC_PW = "Jdbc-Secret-9876";
    private static final String PROP_VALUE = "Prop-Secret-5555";
    private static final String URL_PW = "Url-Secret-4242";
    /** Every value a message, an exception or a toString must never show. */
    private static final List<String> SECRETS = List.of(STAFF_PW, JDBC_PW, PROP_VALUE, URL_PW, "1234");

    private static final String FULL = """
            version: 1
            credentials:
              staff:
                "100": { password: "%s" }
                "200": { password: 1234, pin: "0042" }
                "300": {}
              ops-team:
                kim: { token: "Kim-Token-77777" }
            jdbc:
              reporting:
                url: "jdbc:skillroy-fake://reader:%s@db.test.example:5432/app"
                user: reader
                password: "%s"
                driver: org.example.FakeDriver
                statementTimeout: 30s
                loginTimeout: 500ms
                properties: { sslKey: "%s", trace: true }
              local:
                url: jdbc:hsqldb:mem:x
            """.formatted(STAFF_PW, URL_PW, JDBC_PW, PROP_VALUE);

    private static Path write(Path dir, String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        return file;
    }

    /** A load that failed: the exception, and its message with the file's temporary directory written {@code <dir>}. */
    private record Failure(CredentialsException exception, String message) {
    }

    private static CredentialsException fails(String yaml) {
        return failure(yaml).exception();
    }

    /** Loads {@code yaml} from a file in a new temporary directory; the load must fail. */
    private static Failure failure(String yaml) {
        Path dir;
        try {
            dir = Files.createTempDirectory("skillroy-cred-test");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        try {
            CredentialsException e = assertThrows(CredentialsException.class,
                    () -> Credentials.load(write(dir, "credentials.yaml", yaml)), yaml);
            return new Failure(e, withoutDir(e.getMessage(), dir));
        } finally {
            try {
                for (Path p : List.of(dir.resolve("credentials.yaml"), dir)) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException e) {
                // A leftover temporary directory is harmless.
            }
        }
    }

    /**
     * {@code text} with the path of {@code dir} (as given and as the file system resolves it) written {@code <dir>}:
     * a temporary directory's random name may hold any digits, a short numeric secret such as {@code 1234} among them
     * (seen once), and a path is where the file is, not a value read from it.
     */
    private static String withoutDir(String text, Path dir) {
        if (text == null) {
            return null;
        }
        String out = text;
        try {
            out = out.replace(dir.toRealPath().toString(), "<dir>");
        } catch (IOException e) {
            // Gone already: the path as given below is the one the message names.
        }
        return out.replace(dir.toAbsolutePath().toString(), "<dir>").replace(dir.toString(), "<dir>");
    }

    /** Fails when {@code text}, read with every path removed by the caller, holds a secret value. */
    private static void assertNoSecret(String text) {
        for (String secret : SECRETS) {
            assertFalse(text.contains(secret), "a secret in: " + text);
        }
    }

    @Test
    void theFullGrammar(@TempDir Path dir) throws Exception {
        Credentials c = Credentials.load(write(dir, "credentials.yaml", FULL));
        assertTrue(c.has("staff", "100"));
        assertTrue(c.has("staff", "300"), "an entry without fields exists");
        assertFalse(c.has("staff", "999"));
        assertFalse(c.has("nobody", "100"));
        assertFalse(c.has("staff", null));
        assertEquals(Map.of("password", STAFF_PW), c.secrets("staff", "100"));
        assertEquals(Map.of("password", "1234", "pin", "0042"), c.secrets("staff", "200"),
                "a whole number is read as its text");
        assertEquals(Map.of(), c.secrets("staff", "300"));
        assertNull(c.secrets("staff", "999"));
        assertEquals(Set.of("local", "reporting"), c.jdbcSources());
        assertEquals(List.of("local", "reporting"), List.copyOf(c.jdbcSources()), "sorted");

        Credentials.JdbcSource reporting = c.jdbc("reporting");
        assertEquals("reader", reporting.user());
        assertEquals(JDBC_PW, reporting.password());
        assertEquals("org.example.FakeDriver", reporting.driver());
        assertEquals(Duration.ofSeconds(30), reporting.statementTimeout());
        assertEquals(Duration.ofMillis(500), reporting.loginTimeout());
        assertEquals(Map.of("sslKey", PROP_VALUE, "trace", "true"), reporting.properties());
        Credentials.JdbcSource local = c.jdbc("local");
        assertEquals(Duration.ofSeconds(10), local.statementTimeout(), "default");
        assertEquals(Duration.ofSeconds(10), local.loginTimeout(), "default");
        assertNull(local.user());
        assertNull(local.password());
        assertNull(c.jdbc("missing"));

        // What the mask knows: every entry field, the JDBC password, property values, URL user-info.
        SecretMask mask = c.mask();
        for (String secret : List.of(STAFF_PW, "1234", "0042", "Kim-Token-77777", JDBC_PW, PROP_VALUE, URL_PW)) {
            assertEquals("<redacted>", mask.apply(secret), secret);
        }
        assertEquals("reader", mask.apply("reader"));
        assertEquals("true", mask.apply("true"), "a boolean property is passed as text but is never a secret");
    }

    @Test
    void textNeverHoldsAValue(@TempDir Path dir) throws Exception {
        Credentials c = Credentials.load(write(dir, "credentials.yaml", FULL));
        String text = c.toString();
        assertNoSecret(withoutDir(text, dir));
        assertTrue(text.contains("ops-team=1") && text.contains("staff=3"), text);
        assertTrue(text.contains("[local, reporting]"), text);
        assertNoSecret(c.jdbc("reporting").toString());
        assertFalse(c.jdbc("reporting").toString().contains("db.test.example"), "not even the url");
        assertTrue(Credentials.empty().toString().contains("realms={}"));
    }

    @Test
    void strictness() {
        assertTrue(fails("version: 1\nextra: x\n").getMessage().matches(".*credentials.yaml:2: a top-level key is"
                + " not known; allowed keys: version, credentials, jdbc, masking"));
        assertTrue(fails("credentials: {}\n").getMessage().contains("'version' is required"));
        assertTrue(fails("version: 2\n").getMessage().contains("'version' must be 1"));
        assertTrue(fails("version: 1.0\n").getMessage().contains("'version' must be 1"));
        assertTrue(fails("version: 1\ncredentials:\n  Staff: {}\n").getMessage().contains("must match [a-z0-9-]+"));
        assertTrue(fails("version: 1\ncredentials:\n  staff: []\n").getMessage().contains("must be a mapping"));
        assertTrue(fails("version: 1\ncredentials:\n  staff:\n    \"100\":\n").getMessage()
                .contains(":4: credentials.staff: an entry must be a mapping"), "S41: by its line, not its text");
        assertTrue(fails("version: 1\ncredentials:\n  staff:\n    \"100\": { pass-word: x }\n").getMessage()
                .contains("must be an identifier"));
        assertTrue(fails("version: 1\njdbc:\n  r: { user: x }\n").getMessage().contains("jdbc.r.url is required"));
        assertTrue(fails("version: 1\njdbc:\n  r: { url: \"http://x\" }\n").getMessage().contains("must start with"));
        assertTrue(fails("version: 1\njdbc:\n  r: { url: \"jdbc:x\", pool: 3 }\n").getMessage()
                .contains("jdbc.r: a key is not known; allowed keys: url,"));
        assertTrue(fails("version: 1\njdbc:\n  r: { url: \"jdbc:x\", driver: \"not a class\" }\n").getMessage()
                .contains("Java class name"));
        assertTrue(fails("version: 1\njdbc:\n  \"-r\": { url: \"jdbc:x\" }\n").getMessage().contains("source id"));
        assertTrue(fails("version: 1\njdbc: [1]\n").getMessage().contains("'jdbc' must be a mapping"));
        // Durations: a unit is required, 120s at most.
        assertTrue(fails("version: 1\njdbc:\n  r: { url: \"jdbc:x\", statementTimeout: 10 }\n").getMessage()
                .contains("must be a duration"));
        assertTrue(fails("version: 1\njdbc:\n  r: { url: \"jdbc:x\", loginTimeout: 3m }\n").getMessage()
                .contains("at most 120s"));
        assertTrue(fails("version: 1\njdbc:\n  r: { url: \"jdbc:x\", statementTimeout: 0s }\n").getMessage()
                .contains("must be a duration"));
        // YAML's own rules apply: duplicate keys, the reader's message withheld with its line (S35: a key's text may
        // be a value typed where a key belongs).
        CredentialsException duplicate = fails("version: 1\nversion: 1\n");
        assertTrue(duplicate.getMessage().contains(":2: not valid YAML (the reader's message is withheld"),
                duplicate.getMessage());
        assertFalse(duplicate.getMessage().contains("duplicate key 'version'"), duplicate.getMessage());
    }

    @Test
    void messagesNeverRepeatAValue() {
        String[] bad = {
            // a value of the wrong type
            "version: 1\ncredentials:\n  staff:\n    \"100\": { password: [\"%s\"] }\n",
            "version: 1\njdbc:\n  r: { url: \"jdbc:x\", password: { x: \"%s\" } }\n",
            "version: 1\njdbc:\n  r: { url: \"jdbc:x\", properties: { k: [\"%s\"] } }\n",
            // malformed YAML whose parser message would quote the text
            "version: 1\ncredentials:\n  staff:\n    \"100\": { password: \"%s }\n",
            // an empty secret, a boolean secret
            "version: 1\ncredentials:\n  staff:\n    \"100\": { password: \"\", note: \"%s\" }\n",
        };
        for (String template : bad) {
            Failure f = failure(template.formatted(STAFF_PW));
            assertNoSecret(f.message());
            assertTrue(f.message().startsWith("<dir>"), "the scan reads the message, not the random directory: "
                    + f.message());
            assertNull(f.exception().getCause());
        }
        Failure octal = failure("version: 1\ncredentials:\n  staff:\n    \"100\": { password: 0777 }\n");
        assertFalse(octal.message().contains("0777"), octal.message());
        assertTrue(octal.message().contains(":4: not valid YAML"), octal.message());
        CredentialsException bool = fails("version: 1\ncredentials:\n  staff:\n    \"100\": { password: true }\n");
        assertTrue(bool.getMessage().contains(":4: credentials.staff.100: the value of a field must be a text or a"
                + " whole number"), bool.getMessage());
    }

    @Test
    void aValueTypedWhereAKeyBelongsIsNeverEchoed() throws Exception {
        String secret = "s3cret-Value-9";
        String identifier = "S3cretValue9";
        Map<String, String> files = new LinkedHashMap<>();
        // The three probes of the review: a password without its field name, as a field name, as a source key.
        files.put("version: 1\ncredentials:\n  staff:\n    \"100\": { \"" + secret + "\" }\n",
                ":4: credentials.staff.100: a field name must be an identifier");
        files.put("version: 1\ncredentials:\n  staff:\n    \"100\": { " + identifier + " }\n",
                ":4: credentials.staff.100: the value of a field must be a text or a whole number");
        files.put("version: 1\njdbc:\n  rep:\n    url: jdbc:x://h/db\n    " + secret + ":\n",
                ":5: jdbc.rep: a key is not known; allowed keys:");
        // The other places a key the grammar does not fix is read.
        files.put("version: 1\njdbc:\n  rep: { url: \"jdbc:x\", properties: { \"" + secret + "\" } }\n",
                ":3: jdbc.rep.properties: the value of a property must be a text or a whole number");
        files.put("version: 1\ncredentials:\n  \"" + secret + "\": {}\n",
                ":3: credentials: a realm name must match [a-z0-9-]+");
        files.put("version: 1\njdbc:\n  \"-" + secret + "\": { url: \"jdbc:x\" }\n",
                ":3: jdbc: a source id must match");
        files.put("version: 1\n" + identifier + ": 1\n", ":2: a top-level key is not known");
        // Security fixes S35 and S41: the sceptics' seven probes.
        files.put("version: 1\ncredentials:\n  staff:\n    \"100\": { " + secret + ", " + secret + " }\n",
                ":4: not valid YAML (the reader's message is withheld");
        files.put("version: 1\ncredentials:\n  staff:\n    \"100\": { " + identifier + ", " + identifier + " }\n",
                ":4: not valid YAML (the reader's message is withheld");
        files.put("version: 1\njdbc:\n  rep: { url: \"jdbc:x\", \"" + secret + "\": 1, \"" + secret + "\": 2 }\n",
                ":3: not valid YAML (the reader's message is withheld");
        files.put("version: 1\ncredentials:\n  staff: { \"" + secret + "\" }\n",
                ":3: credentials.staff: an entry must be a mapping");
        files.put("version: 1\ncredentials:\n  " + secret.toLowerCase(java.util.Locale.ROOT) + ":\n",
                ":3: credentials: a realm must be a mapping");
        files.put("version: 1\njdbc:\n  " + secret + ": 7\n", ":3: jdbc: a source must be a mapping");
        files.put("version: 1\njdbc:\n  rep: { user: u }\n", ":3: jdbc.rep.url is required");
        for (Map.Entry<String, String> file : files.entrySet()) {
            String message = fails(file.getKey()).getMessage();
            assertFalse(message.contains(secret) || message.contains(identifier)
                    || message.contains(secret.toLowerCase(java.util.Locale.ROOT)), message);
            assertTrue(message.contains(file.getValue()), message);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("password", "x-123456789");
        entry.put(secret, null);
        String built = assertThrows(CredentialsException.class, () -> Credentials.of(Map.of("version", 1L,
                "credentials", Map.of("staff", Map.of("100", entry))), "built in code")).getMessage();
        assertEquals("built in code: credentials.staff.100: field name 2 must be an identifier"
                + " ([A-Za-z_][A-Za-z0-9_]*)", built, "without lines, the key's position");
    }

    @Test
    void ofReadsAParsedDocument() throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 1);
        doc.put("credentials", Map.of("staff", Map.of("100", Map.of("password", STAFF_PW))));
        Credentials c = Credentials.of(doc, "built in code");
        assertTrue(c.has("staff", "100"));
        assertEquals("built in code", c.origin());
        assertEquals(Set.of(), c.jdbcSources());
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("version", 1);
        unknown.put("x", 1);
        CredentialsException e = assertThrows(CredentialsException.class, () -> Credentials.of(unknown, "label"));
        assertEquals("label: top-level key 2 is not known; allowed keys: version, credentials, jdbc, masking", e.getMessage(),
                "without lines, the key's position");
        assertThrows(CredentialsException.class, () -> Credentials.of(null, "label"));
        assertFalse(Credentials.empty().has("staff", "100"));
        assertTrue(Credentials.empty().mask().isEmpty());
    }

    @Test
    void theMaskingThresholdIsPerFile(@TempDir Path dir) throws Exception {
        Credentials dflt = Credentials.load(write(dir, "credentials.yaml", FULL));
        assertEquals(SecretMask.MIN_SUBSTRING, dflt.minLengthInText());
        assertEquals("pin 1234 at 14:47:11", dflt.mask().apply("pin 1234 at 14:47:11"),
                "a four-character secret is left inside free text by default (A25)");
        Credentials low = Credentials.load(write(dir, "low.yaml", FULL + "masking:\n  minLengthInText: 4\n"));
        assertEquals(4, low.minLengthInText());
        assertEquals(4, low.mask().minLengthInText());
        assertEquals("pin <redacted> at 14:47:11", low.mask().apply("pin 1234 at 14:47:11"),
                "an environment whose test accounts have short credentials lowers it (B19 Q14, 2026-10-06)");
        assertEquals("<redacted>", low.mask().apply("1234"));
        assertEquals(4, low.mask().plus(List.of("Another-Secret-1")).minLengthInText(), "plus keeps it");
        assertEquals(SecretMask.MIN_SUBSTRING, Credentials.empty().minLengthInText());
        assertTrue(fails("version: 1\nmasking: 4\n").getMessage().contains("'masking' must be a mapping"));
        assertTrue(fails("version: 1\nmasking: { minLengthInText: 0 }\n").getMessage()
                .contains("masking.minLengthInText must be a whole number from 1 to 1000"));
        assertTrue(fails("version: 1\nmasking: { minLengthInText: 1001 }\n").getMessage().contains("from 1 to 1000"));
        assertTrue(fails("version: 1\nmasking: { minLengthInText: \"4\" }\n").getMessage().contains("whole number"));
        assertTrue(fails("version: 1\nmasking: { minLengthInText: 4.5 }\n").getMessage().contains("whole number"));
        assertTrue(fails("version: 1\nmasking: { threshold: 4 }\n").getMessage()
                .matches(".*masking: a key is not known; allowed keys: minLengthInText"));
    }

    @Test
    void theLocationRule(@TempDir Path dir) throws Exception {
        Path explicit = dir.resolve("given.yaml");
        Map<String, String> none = Map.of();
        assertEquals(explicit, Credentials.locate(explicit, dir, "seed-a", none::get, none::get));
        Path fromProperty = Credentials.locate(null, dir, "seed-a",
                Map.of(Credentials.PROPERTY, dir.resolve("p.yaml").toString())::get, none::get);
        assertEquals(dir.resolve("p.yaml"), fromProperty);
        Path fromEnv = Credentials.locate(null, dir, "seed-a", none::get,
                Map.of(Credentials.ENV, dir.resolve("e.yaml").toString())::get);
        assertEquals(dir.resolve("e.yaml"), fromEnv);
        assertEquals(dir.resolve("p.yaml"), Credentials.locate(null, dir, "seed-a",
                Map.of(Credentials.PROPERTY, dir.resolve("p.yaml").toString())::get,
                Map.of(Credentials.ENV, dir.resolve("e.yaml").toString())::get), "the property wins over the env");
        assertEquals(dir.resolve("e.yaml"), Credentials.locate(null, dir, "seed-a",
                Map.of(Credentials.PROPERTY, "  ")::get,
                Map.of(Credentials.ENV, dir.resolve("e.yaml").toString())::get), "a blank property is not set");
        assertNull(Credentials.locate(null, dir, "seed-a", none::get, none::get), "no file in the bindings root");
        Files.createDirectories(dir.resolve("seed-a"));
        Path inRoot = write(dir.resolve("seed-a"), "credentials.yaml", "version: 1\n");
        assertEquals(inRoot, Credentials.locate(null, dir, "seed-a", none::get, none::get));
        assertNull(Credentials.locate(null, dir, "seed-b", none::get, none::get));
        assertNull(Credentials.locate(null, dir, "../seed-a", none::get, none::get), "an invalid env id");
        assertNull(Credentials.locate(null, null, "seed-a", none::get, none::get));
    }

    /**
     * Security fix S38: {@code $SKILLROY_CREDENTIALS} (or {@code -Dskillroy.credentials}) holding a file's content,
     * or a path with a line break, is refused before it is used as a path, and a file it names is reported by the
     * variable's name, never by its value.
     */
    @Test
    void theLocationVariableIsAPathNeverContent(@TempDir Path dir) throws Exception {
        Map<String, String> none = Map.of();
        String content = "version: 1\ncredentials:\n  staff:\n    \"100\": { password: \"" + STAFF_PW + "\" }\n";
        CredentialsException e = assertThrows(CredentialsException.class, () -> Credentials.find(null, dir,
                "seed-a", none::get, Map.of(Credentials.ENV, content)::get));
        for (String line : content.split("\n")) {
            assertFalse(e.getMessage().contains(line.strip()), "no line of the content: " + e.getMessage());
        }
        assertTrue(e.getMessage().contains("$" + Credentials.ENV), e.getMessage());
        CredentialsException property = assertThrows(CredentialsException.class, () -> Credentials.find(null, dir,
                "seed-a", Map.of(Credentials.PROPERTY, dir + "/a\nb.yaml")::get, none::get));
        assertTrue(property.getMessage().contains("-D" + Credentials.PROPERTY), property.getMessage());
        assertFalse(property.getMessage().contains("b.yaml"), property.getMessage());
        assertThrows(CredentialsException.class, () -> Credentials.find(null, dir, "seed-a", none::get,
                Map.of(Credentials.ENV, "x".repeat(4097))::get), "longer than any file name");
        assertThrows(IllegalArgumentException.class, () -> Credentials.locate(null, dir, "seed-a", none::get,
                Map.of(Credentials.ENV, content)::get), "locate refuses it too, unchecked");

        String single = "{version: 1, credentials: {staff: {\"100\": {password: \"" + STAFF_PW + "\"}}}}";
        Credentials.Location location = Credentials.find(null, dir, "seed-a", none::get,
                Map.of(Credentials.ENV, single)::get);
        CredentialsException missing = assertThrows(CredentialsException.class,
                () -> Credentials.loadFound(location));
        assertFalse(missing.getMessage().contains(STAFF_PW), missing.getMessage());
        assertEquals("credentials file named by $" + Credentials.ENV + " does not exist", missing.getMessage());
        Credentials.Location explicit = Credentials.find(dir.resolve("given.yaml"), dir, "seed-a", none::get,
                none::get);
        assertEquals(dir.resolve("given.yaml").toString(), explicit.label(), "a path the person typed is named");
        assertEquals(Credentials.empty(), Credentials.loadFound(null));
    }

    @Test
    void missingAndNonRegularFilesAreRefused(@TempDir Path dir) {
        CredentialsException missing = assertThrows(CredentialsException.class,
                () -> Credentials.load(dir.resolve("nope.yaml")));
        assertTrue(missing.getMessage().contains("does not exist"));
        assertTrue(assertThrows(CredentialsException.class, () -> Credentials.load(dir)).getMessage()
                .contains("not a regular file"));
        assertThrows(CredentialsException.class, () -> Credentials.load(null));
    }

    @Test
    void posixGroupOrOtherBitsAreRefused(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX file system");
        Path file = write(dir, "credentials.yaml", FULL);
        Credentials.load(file);
        for (String mode : List.of("rw-r-----", "rw----r--", "rw--w----", "rw-rw-rw-")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
            CredentialsException e = assertThrows(CredentialsException.class, () -> Credentials.load(file), mode);
            assertTrue(e.getMessage().contains("credentials file is accessible to other users"), e.getMessage());
            assertTrue(e.getMessage().contains(mode), e.getMessage());
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        assertTrue(Credentials.load(file).has("staff", "100"), "owner read-only is fine");
    }

    // --- the ACL and no-permission branches, on built values (not verified on Windows) ---

    private record Principal(String name) implements UserPrincipal {
        @Override
        public String getName() {
            return name;
        }
    }

    private static final UserPrincipal ME = new Principal("me");
    private static final UserPrincipal OTHER = new Principal("other");
    private static final UserPrincipal SYSTEM = new Principal("SYSTEM");
    private static final UserPrincipal ADMINS = new Principal("Administrators");

    private static AclEntry allow(UserPrincipal who, AclEntryPermission... permissions) {
        return AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(who)
                .setPermissions(permissions.length == 0 ? EnumSet.noneOf(AclEntryPermission.class)
                        : EnumSet.of(permissions[0], permissions)).build();
    }

    private static CredentialFiles.Access fake(CredentialFiles.Kind kind, UserPrincipal owner, List<AclEntry> acl,
                                               UserPrincipal me) {
        return new CredentialFiles.Access() {
            @Override
            public CredentialFiles.Kind kind(Path file) {
                return kind;
            }

            @Override
            public Set<PosixFilePermission> posixPermissions(Path file) {
                throw new AssertionError("not POSIX");
            }

            @Override
            public UserPrincipal owner(Path file) {
                return owner;
            }

            @Override
            public List<AclEntry> acl(Path file) {
                return acl;
            }

            @Override
            public UserPrincipal me() {
                return me;
            }

            @Override
            public UserPrincipal system() {
                return SYSTEM;
            }

            @Override
            public UserPrincipal administrators() {
                return ADMINS;
            }
        };
    }

    @Test
    void theAclRule(@TempDir Path dir) throws Exception {
        Path file = write(dir, "credentials.yaml", FULL);
        List<AclEntry> ownerOnly = List.of(allow(ME, AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA),
                allow(SYSTEM, AclEntryPermission.READ_DATA), allow(ADMINS, AclEntryPermission.READ_DATA),
                AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(OTHER)
                        .setPermissions(AclEntryPermission.READ_DATA).build());
        assertTrue(Credentials.load(file, fake(CredentialFiles.Kind.ACL, ME, ownerOnly, ME)).has("staff", "100"));
        CredentialsException other = assertThrows(CredentialsException.class, () -> Credentials.load(file,
                fake(CredentialFiles.Kind.ACL, ME, List.of(allow(ME, AclEntryPermission.READ_DATA),
                        allow(OTHER, AclEntryPermission.READ_DATA)), ME)));
        assertTrue(other.getMessage().contains("credentials file is accessible to other users (its ACL allows other"),
                other.getMessage());
        CredentialsException owner = assertThrows(CredentialsException.class, () -> Credentials.load(file,
                fake(CredentialFiles.Kind.ACL, OTHER, ownerOnly, ME)));
        assertTrue(owner.getMessage().contains("owned by other"), owner.getMessage());
        assertThrows(CredentialsException.class, () -> Credentials.load(file,
                fake(CredentialFiles.Kind.ACL, ME, ownerOnly, null)), "the current user cannot be determined");
        assertThrows(CredentialsException.class, () -> Credentials.load(file,
                fake(CredentialFiles.Kind.ACL, ME, null, ME)), "an ACL that cannot be read");
        // An ALLOW entry without any right is harmless.
        assertTrue(Credentials.load(file, fake(CredentialFiles.Kind.ACL, ME, List.of(allow(ME,
                AclEntryPermission.READ_DATA), allow(OTHER)), ME)).has("staff", "100"));
    }

    @Test
    void withoutPermissionsTheFileIsAcceptedAndAWarningLoggedOnce(@TempDir Path dir) throws Exception {
        Path file = write(dir, "credentials.yaml", FULL);
        CredentialFiles.resetWarning();
        assertFalse(CredentialFiles.warned());
        assertTrue(Credentials.load(file, fake(CredentialFiles.Kind.NONE, null, null, null)).has("staff", "100"));
        assertTrue(CredentialFiles.warned());
        assertTrue(Credentials.load(file, fake(CredentialFiles.Kind.NONE, null, null, null)).has("staff", "200"));
    }

    @Test
    void aFileTooLargeIsRefused(@TempDir Path dir) throws Exception {
        String big = "version: 1\n" + "#".repeat(Credentials.MAX_FILE_BYTES) + "\n";
        Path file = write(dir, "credentials.yaml", big);
        assertTrue(assertThrows(CredentialsException.class, () -> Credentials.load(file)).getMessage()
                .contains("at most 1 MiB"));
        assertEquals(StandardCharsets.UTF_8.name(), "UTF-8");
    }

    @Test
    void urlSecrets() {
        assertEquals(List.of("pw-1"), Credentials.urlSecrets("jdbc:pg://u:pw-1@h:5/db"));
        assertEquals(List.of("tiger"), Credentials.urlSecrets("jdbc:oracle:thin:scott/tiger@h:1521:x"));
        assertEquals(List.of("s3"), Credentials.urlSecrets("jdbc:x://h/db?user=u&password=s3"));
        assertEquals(List.of(), Credentials.urlSecrets("jdbc:x://git@h/db"), "a user without a password");
        assertEquals(List.of(), Credentials.urlSecrets(null));
        assertSame(Credentials.empty(), Credentials.empty());
    }
}
