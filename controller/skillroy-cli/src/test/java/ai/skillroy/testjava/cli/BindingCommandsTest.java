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
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.OracleValue;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.CredentialRef;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code run}'s bind options, {@code resolve} and {@code diff-bindings} against the fake agent (phase2-contracts.md
 * 12.1, 14.1 item 9).
 */
class BindingCommandsTest {

    static final String ORACLE = """
            scenario: with-oracle
            pack: clitest
            oracle:
              same: { fn: Echo, args: IDLE, must_equal: IDLE }
            steps:
              - expect: { "counter.value": { gte: 0 } }
            """;

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Path scenario;

    @BeforeEach
    void setUp() throws IOException {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        scenario = Scenarios.write(tmp.resolve("module/scenarios"), "with-oracle.yaml", ORACLE);
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome cli(Terminal terminal, String command, String... args) {
        List<String> all = new ArrayList<>(List.of(command));
        all.addAll(List.of(args));
        if (!command.equals("diff-bindings")) {
            all.addAll(connection);
        }
        return CliHarness.runWithTerminal(tmp.resolve("unused-discovery"), "", terminal, all.toArray(String[]::new))
                .never(agent.token);
    }

    private CliHarness.Outcome cli(String command, String... args) {
        return cli(Terminal.NONE, command, args);
    }

    private Path bindingsFile(String env) {
        return tmp.resolve("module/bindings").resolve(env).resolve("with-oracle.bindings.json");
    }

    @Test
    void resolveWritesTheFileRunReplaysItAndPrintsTheModelCalls() throws Exception {
        CliHarness.Outcome dry = cli("resolve", scenario.toString(), "--dry-run").expectExit(Exit.OK);
        assertTrue(dry.out().contains("would write default/with-oracle.bindings.json"), dry.out());
        assertTrue(dry.out().contains("\"bindingsVersion\": 1"), dry.out());
        assertFalse(Files.exists(bindingsFile("default")), "a dry run writes nothing");
        assertTrue(agent.rpcRequests("trace.mark").isEmpty(), "resolve sends no start mark and runs no step");

        CliHarness.Outcome resolved = cli("resolve", scenario.toString(), "--yes", "--approved-by", "ci")
                .expectExit(Exit.OK);
        assertTrue(resolved.out().contains("wrote " + bindingsFile("default")), resolved.out());
        BindingsFile file = BindingsFile.read(bindingsFile("default"));
        assertEquals("IDLE", file.oracles().get("same").value());
        assertTrue(cli("resolve", scenario.toString(), "--yes").expectExit(Exit.OK).out()
                .contains("already identical"));

        CliHarness.Outcome run = cli("run", scenario.toString()).expectExit(Exit.OK);
        assertTrue(run.out().endsWith("model calls: 0\n"), run.out());
        Path plan = tmp.resolve("module/plan/with-oracle.plan.json");
        CliHarness.run(tmp, "compile", scenario.toString(), "--out", plan.getParent().toString())
                .expectExit(Exit.OK);
        cli("run", plan.toString()).expectExit(Exit.OK);
        assertEquals(BindArgs.defaultRoot(List.of(scenario)), BindArgs.defaultRoot(List.of(plan)),
                "a YAML argument and a plan argument find the same bindings root (D31)");

        CliHarness.Outcome other = cli("run", scenario.toString(), "--env", "other").expectExit(Exit.FAILED);
        assertTrue(other.out().contains("REBIND_NEEDED"), other.out());
        assertTrue(other.out().contains("NO_BINDINGS"), other.out());
        cli("run", scenario.toString(), "--mode", "live").expectExit(Exit.OK);
        cli("run", scenario.toString(), "--mode", "live", "--write-bindings", "--env", "live-env")
                .expectExit(Exit.OK);
        assertTrue(Files.exists(bindingsFile("live-env")));
    }

    /** A credential of 17 characters with a quote and a backslash, which JSON text escapes (P2 11.4). */
    static final String SECRET = "pw\"in\\side-secret";

    /** The approve refusal, which a dry run that would print such a value ends with as well (P2 8.3, 11.4). */
    static final String REFUSED = "the bindings file was not written: a recorded value equals a credential";

    /** Every decoded string (key or value) of a JSON document that holds {@code secret}, with its path. */
    private static List<String> holding(Object json, String path, String secret) {
        List<String> found = new ArrayList<>();
        if (json instanceof String text) {
            if (text.contains(secret)) {
                found.add(path);
            }
        } else if (json instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (String.valueOf(e.getKey()).contains(secret)) {
                    found.add(path + " (key)");
                }
                found.addAll(holding(e.getValue(), path + "." + e.getKey(), secret));
            }
        } else if (json instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                found.addAll(holding(list.get(i), path + "[" + i + "]", secret));
            }
        }
        return found;
    }

    /**
     * phase2-contracts.md 12.1 and 11.4: {@code resolve --dry-run} prints the file it would write, so a file that
     * would hold a credential value is refused as {@code approve} refuses to write it (ENVIRONMENT, exit 1) and nothing
     * of it is printed, neither in the text nor as {@code wouldWrite} in {@code --json}. The secret has a quote and a
     * backslash, which JSON text escapes: the JSON is decoded, and the text is scanned for the secret as written and as
     * JSON escapes it.
     */
    @Test
    void aDryRunThatWouldPrintACredentialIsRefusedAsApproveRefusesToWriteIt() throws Exception {
        Path credentials = Files.writeString(tmp.resolve("credentials.yaml"), "version: 1\ncredentials:\n"
                + "  staff:\n    \"1\": { password: 'pw\"in\\side-secret' }\n");
        Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"));
        Path leaky = Scenarios.write(tmp.resolve("module/scenarios"), "leaky.yaml", """
                scenario: leaky
                pack: clitest
                oracle:
                  same: { fn: Echo, args: 'pw"in\\side-secret' }
                steps:
                  - expect: { "counter.value": { gte: 0 } }
                """);
        String escaped = Json.write(SECRET);
        escaped = escaped.substring(1, escaped.length() - 1);
        assertEquals("pw\\\"in\\\\side-secret", escaped, "how JSON text writes it");

        CliHarness.Outcome text = cli("resolve", leaky.toString(), "--dry-run", "--credentials",
                credentials.toString()).expectExit(Exit.FAILED);
        assertTrue(text.out().contains("leaky: ENVIRONMENT: " + REFUSED), text.out());
        assertFalse(text.out().contains("would write"), "nothing of the file is printed: " + text.out());
        CliHarness.Outcome json = cli("resolve", leaky.toString(), scenario.toString(), "--dry-run", "--json",
                "--credentials", credentials.toString()).expectExit(Exit.FAILED);
        for (CliHarness.Outcome outcome : List.of(text, json)) {
            for (String stream : List.of(outcome.out(), outcome.err())) {
                assertFalse(stream.contains(SECRET) || stream.contains(escaped), "the secret, as written or escaped,"
                        + " is printed:\n" + stream);
            }
        }
        Map<String, Object> document = Json.parseObject(json.out().substring(json.out().indexOf("{\n  \"results\"")));
        assertEquals(List.of(), holding(document, "$", SECRET), json.out());
        List<?> results = (List<?>) document.get("results");
        Map<?, ?> refused = (Map<?, ?>) results.get(0);
        assertEquals("ERROR", refused.get("status"));
        assertEquals("ENVIRONMENT", refused.get("failureKind"));
        assertEquals(REFUSED, refused.get("message"));
        assertFalse(refused.containsKey("wouldWrite"), "no wouldWrite for a refused file");
        assertEquals("live", ((Map<?, ?>) refused.get("detail")).get("mode"));
        Map<?, ?> plain = (Map<?, ?>) results.get(1);
        assertEquals("PASS", plain.get("status"), "a file without a credential value is still shown");
        assertEquals("IDLE", ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) plain.get("wouldWrite")).get("oracles"))
                .get("same")).get("value"));
        assertEquals(1L, document.get("exitCode"));
        assertFalse(Files.exists(tmp.resolve("module/bindings/default/leaky.bindings.json")), "nothing written");

        CliHarness.Outcome approve = cli("resolve", leaky.toString(), "--yes", "--credentials",
                credentials.toString()).expectExit(Exit.FAILED);
        assertTrue(approve.out().contains("leaky: ENVIRONMENT: " + REFUSED), "the same refusal: " + approve.out());

        // The approval screen, shown before the write is refused, masks each value before writing it as JSON.
        Terminal yes = new Terminal() {
            @Override
            public boolean interactive() {
                return true;
            }

            @Override
            public String readLine(String prompt) {
                return "y";
            }
        };
        CliHarness.Outcome screen = cli(yes, "resolve", leaky.toString(), "--credentials", credentials.toString())
                .expectExit(Exit.FAILED);
        assertTrue(screen.out().contains("  oracle same (Echo) = \"<redacted>\"\n"), screen.out());
        for (String stream : List.of(screen.out(), screen.err())) {
            assertFalse(stream.contains(SECRET) || stream.contains(escaped), "the screen shows the secret:\n"
                    + stream);
        }
    }

    @Test
    void usageErrorsAndApproveWithoutATerminal() {
        assertTrue(cli("run", scenario.toString(), "--mode", "re-resolve").expectExit(Exit.USAGE).err()
                .contains("re-resolve is a command"));
        cli("run", scenario.toString(), "--env", "Bad_Env").expectExit(Exit.USAGE);
        cli("run", scenario.toString(), "--write-bindings").expectExit(Exit.USAGE);
        CliHarness.Outcome noTerminal = cli("run", scenario.toString(), "--mode", "approve").expectExit(Exit.USAGE);
        assertTrue(noTerminal.err().contains("--yes"), noTerminal.err());
        cli("resolve", scenario.toString()).expectExit(Exit.USAGE);
        assertTrue(agent.requests.isEmpty(), "no call before a usage error");
    }

    @Test
    void theTerminalIsAskedAndEndOfInputIsNo() {
        List<String> prompts = new ArrayList<>();
        Terminal eof = scripted(prompts, (String) null);
        CliHarness.Outcome refused = cli(eof, "resolve", scenario.toString()).expectExit(Exit.FAILED);
        assertTrue(refused.out().contains("NOT_APPROVED"), refused.out());
        assertTrue(refused.out().contains("oracle same (Echo) = \"IDLE\"  must equal \"IDLE\""), refused.out());
        assertTrue(refused.out().contains("fingerprint sha256:"), refused.out());
        assertEquals(1, prompts.size());
        assertFalse(Files.exists(bindingsFile("default")));
        cli(scripted(prompts, "y"), "resolve", scenario.toString()).expectExit(Exit.OK);
        assertTrue(Files.exists(bindingsFile("default")));
    }

    private static Terminal scripted(List<String> prompts, String... answers) {
        List<String> left = new ArrayList<>(java.util.Arrays.asList(answers));
        return new Terminal() {
            @Override
            public boolean interactive() {
                return true;
            }

            @Override
            public String readLine(String prompt) {
                prompts.add(prompt);
                return left.isEmpty() ? null : left.remove(0);
            }
        };
    }

    @Test
    void aRefusedCredentialsFileFailsTheEnvironmentTestcaseAndMakesNoCall() throws Exception {
        Path junit = tmp.resolve("junit.xml");
        CliHarness.Outcome missing = cli("run", scenario.toString(), "--credentials", tmp.resolve("nope.yaml")
                .toString(), "--junit", junit.toString()).expectExit(Exit.FAILED);
        assertTrue(missing.err().contains("does not exist"), missing.err());
        String xml = Files.readString(junit);
        assertTrue(xml.contains("<testcase name=\"environment\"") && xml.contains("<error"), xml);
        assertTrue(agent.requests.isEmpty(), "the credentials are loaded before the agent is contacted");
        Path open = Files.writeString(tmp.resolve("open.yaml"), "version: 1\ncredentials:\n  staff:\n"
                + "    \"1\": { password: \"open-secret-1234\" }\n");
        try {
            Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException e) {
            return;
        }
        CliHarness.Outcome tooOpen = cli("resolve", scenario.toString(), "--yes", "--credentials", open.toString())
                .expectExit(Exit.FAILED).never("open-secret-1234");
        assertTrue(tooOpen.err().contains("accessible to other users"), tooOpen.err());
        assertTrue(agent.requests.isEmpty());
    }

    @Test
    void diffBindingsGroupsTheChangesBySlot() throws Exception {
        Path a = write("env-a", "A", "SITE ONE", "100", "B2");
        Path b = write("env-b", "B", "SITE TWO", "200", "C3");
        CliHarness.Outcome differ = cli("diff-bindings", a.toString(), b.toString()).expectExit(Exit.FAILED);
        String out = differ.out();
        assertTrue(out.contains("env.components.toy.seed: \"A\" -> \"B\""), out);
        assertTrue(out.contains("env.components.toy.site: \"SITE ONE\" -> \"SITE TWO\""), out);
        for (String slot : List.of("slot item:", "slot assistant:", "slot supervisor:", "slot keys:")) {
            assertTrue(out.contains(slot), slot + "\n" + out);
        }
        assertTrue(out.contains("  credential: \"staff/100\" -> \"staff/200\""), out);
        assertTrue(out.contains("in 4 slots or oracles"), out);
        CliHarness.Outcome json = cli("diff-bindings", "--json", "--bindings", tmp.resolve("b").toString(), "--env",
                "env-a", "--env", "env-b", "scenario-a").expectExit(Exit.FAILED);
        Map<String, Object> document = Json.parseObject(json.out());
        assertEquals(true, document.get("differ"));
        assertEquals(List.of("item", "assistant", "supervisor", "keys"), document.get("slots"));
        assertTrue(cli("diff-bindings", a.toString(), a.toString()).expectExit(Exit.OK).out()
                .contains("no differences"));
        cli("diff-bindings", a.toString(), tmp.resolve("missing.json").toString()).expectExit(Exit.SCENARIO);
        Files.writeString(tmp.resolve("not.json"), "{\"x\": 1}");
        cli("diff-bindings", a.toString(), tmp.resolve("not.json").toString()).expectExit(Exit.SCENARIO);
        cli("diff-bindings", "scenario-a").expectExit(Exit.USAGE);
        cli("diff-bindings", a.toString(), b.toString(), "--env", "x").expectExit(Exit.USAGE);
    }

    /** A bindings file of four slots and one oracle, varied by environment. */
    private Path write(String env, String seed, String site, String staff, String item) throws Exception {
        Map<String, String> components = new TreeMap<>(Map.of("skillroy.fingerprint", "1", "toy.seed", seed,
                "toy.site", site));
        EnvFingerprint fingerprint = new EnvFingerprint(1, "sha256:" + seed, components);
        Map<String, Resolution> resolutions = new LinkedHashMap<>();
        Provenance provenance = new Provenance("toy.sql.X", List.of(), List.of(), List.of(), 1, false, List.of());
        Approval approval = new Approval(Approval.State.APPROVED, "approve");
        resolutions.put("max", new Resolution("max", "Limit", Map.of("type", "RETURN"), "sha256:m", "100.00",
                Strategy.CANONICAL_SQL, provenance, List.of(), 1.0, null, approval, null));
        resolutions.put("item", new Resolution("item", "Item", Map.of(), "sha256:i", Map.of("code", item),
                Strategy.CANONICAL_SQL, provenance, List.of(), 1.0, null, approval, null));
        resolutions.put("assistant", new Resolution("assistant", "Staff", Map.of(), "sha256:a", Map.of("code",
                staff), Strategy.CANONICAL_SQL, provenance, List.of(), 1.0, new CredentialRef("staff", staff),
                approval, null));
        resolutions.put("supervisor", new Resolution("supervisor", "Staff", Map.of(), "sha256:s" + seed,
                Map.of("code", "9" + staff), Strategy.CANONICAL_SQL, provenance, List.of(), 1.0, null, approval,
                null));
        resolutions.put("keys", new Resolution("keys", "Keys", Map.of(), "sha256:k", Map.of("RETURN", seed),
                Strategy.CANONICAL_JAVA, provenance, List.of(), 1.0, null, approval, null));
        BindingsFile file = new BindingsFile(1, "scenario-a", env, "toy", "1.0.0", "sha256:p", "sha256:r",
                fingerprint, resolutions, Map.of("auth", new OracleValue("auth", "Needs", "sha256:o", "PROMPT",
                "PROMPT")));
        Path path = BindingsFile.locate(tmp.resolve("b"), env, "scenario-a");
        file.write(path);
        return path;
    }

    @Test
    void packAcceptsAJarWithoutADomainPack() throws Exception {
        // phase2-contracts.md 7.2 and 16.1: a JDBC driver arrives as a --pack jar; it need not hold a pack.
        Path jar = tmp.resolve("driver.jar");
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("META-INF/services/java.sql.Driver"));
            out.write("com.example.NoSuchDriver\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        cli("run", scenario.toString(), "--mode", "live", "--pack", jar.toString()).expectExit(Exit.OK);
        assertTrue(Main.overview().contains("resolve") && Main.overview().contains("diff-bindings"));
    }
}
