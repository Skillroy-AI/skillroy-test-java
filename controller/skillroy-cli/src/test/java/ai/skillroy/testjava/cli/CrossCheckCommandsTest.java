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

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle cross-checks at the command line (phase3-contracts.md 9, 11.1 item 4): {@code resolve} prints each check's
 * line and a {@code degraded:} line for a skipped guarded one, exits 1 for ORACLE_SUSPECT; {@code run} reports
 * ORACLE_SUSPECT as {@code <error type="ORACLE_SUSPECT">} with the 8.1 message; {@code --json} of both carries the
 * {@code crosscheck} records (also those of a bind that then failed) and the skipped list; the approval screen shows
 * each outcome with its oracle.
 */
class CrossCheckCommandsTest {

    static final String CHECKED = """
            scenario: with-check
            pack: clitest
            oracle:
              same: { fn: Echo, args: IDLE, must_equal: IDLE, crossCheck: test.echo.check }
            steps:
              - expect: { "counter.value": { gte: 0 } }
            """;

    static final String GUARDED = """
            scenario: guarded-check
            pack: clitest
            oracle:
              same: { fn: Echo, args: IDLE, crossCheck: { check: test.echo.check, capability: test.fancy } }
            steps:
              - expect: { "counter.value": { gte: 0 } }
            """;

    /** The check agrees, then the guard fails: a bind that fails after a {@code crosscheck} record. */
    static final String GUARD_FAILS = """
            scenario: guard-fails
            pack: clitest
            oracle:
              same: { fn: Echo, args: IDLE, must_equal: OTHER, crossCheck: test.echo.check }
            steps:
              - expect: { "counter.value": { gte: 0 } }
            """;

    static final String SKIP_LINE = "oracle same: cross-check test.echo.check skipped: capability test.fancy is"
            + " UNAVAILABLE (not in this fake)";

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Path checked;
    private Path guarded;

    @BeforeEach
    void setUp() throws IOException {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        checked = Scenarios.write(tmp.resolve("module/scenarios"), "with-check.yaml", CHECKED);
        guarded = Scenarios.write(tmp.resolve("module/scenarios"), "guarded-check.yaml", GUARDED);
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome cli(Terminal terminal, String command, String... args) {
        List<String> all = new ArrayList<>(List.of(command));
        all.addAll(List.of(args));
        all.addAll(connection);
        return CliHarness.runWithTerminal(tmp.resolve("unused-discovery"), "", terminal, all.toArray(String[]::new))
                .never(agent.token);
    }

    private CliHarness.Outcome cli(String command, String... args) {
        return cli(Terminal.NONE, command, args);
    }

    private Path bindingsFile(String id) {
        return tmp.resolve("module/bindings/default").resolve(id + ".bindings.json");
    }

    @Test
    void resolvePrintsTheCrossCheckLineAndItsJsonCarriesTheRecords() {
        CliHarness.Outcome resolved = cli("resolve", checked.toString(), "--yes").expectExit(Exit.OK);
        assertTrue(resolved.out().contains("with-check: cross-check test.echo.check: agrees (the fake echoes IDLE)"),
                resolved.out());
        assertTrue(Files.exists(bindingsFile("with-check")));
        CliHarness.Outcome json = cli("resolve", checked.toString(), "--yes", "--json").expectExit(Exit.OK);
        String document = json.out().substring(json.out().indexOf("{\n"));
        Map<?, ?> result = (Map<?, ?>) ((List<?>) Json.parseObject(document).get("results"))
                .get(0);
        List<?> records = (List<?>) result.get("crossChecks");
        assertEquals(1, records.size());
        Map<?, ?> record = (Map<?, ?>) records.get(0);
        assertEquals("same", record.get("oracle"));
        assertEquals("Echo", record.get("fn"));
        assertEquals("test.echo.check", record.get("check"));
        assertEquals(true, record.get("agreed"));
        assertEquals("IDLE", record.get("expected"));
        assertEquals("IDLE", record.get("actual"));
        assertEquals(Map.of("test.echo", "IDLE"), record.get("evidence"));
        assertEquals(List.of(), result.get("skipped"));
    }

    @Test
    void aSkippedGuardedCheckIsADegradedLineAndResolveStillExitsZero() {
        CliHarness.Outcome resolved = cli("resolve", guarded.toString(), "--yes", "--json").expectExit(Exit.OK);
        assertTrue(resolved.out().contains("guarded-check: degraded: " + SKIP_LINE), resolved.out());
        assertTrue(Files.exists(bindingsFile("guarded-check")), "a merely degraded bind is resolved and written");
        String document = resolved.out().substring(resolved.out().indexOf("{\n"));
        Map<?, ?> result = (Map<?, ?>) ((List<?>) Json.parseObject(document).get("results"))
                .get(0);
        assertEquals("DEGRADED", result.get("status"));
        assertEquals(List.of(SKIP_LINE), result.get("skipped"));
        assertEquals(true, ((Map<?, ?>) ((List<?>) result.get("crossChecks")).get(0)).get("skipped"));

        CliHarness.Outcome run = cli("run", guarded.toString()).expectExit(Exit.OK);
        assertTrue(run.out().contains("DEGRADED") && run.out().contains("degraded: " + SKIP_LINE), run.out());
        assertFalse(run.out().contains("skipped: " + SKIP_LINE), "a cross-check is not listed as an expectation");
        cli("run", guarded.toString(), "--degraded-as", "failure").expectExit(Exit.FAILED);
    }

    @Test
    void anOracleSuspectIsExitOneAndAnErrorInTheJunitReport() throws Exception {
        agent.echoAnswer = "BUSY";
        CliHarness.Outcome resolved = cli("resolve", checked.toString(), "--yes", "--json").expectExit(Exit.FAILED);
        assertTrue(resolved.out().contains("with-check: ORACLE_SUSPECT (DISAGREED): oracle same (Echo) disagrees"
                + " with test.echo.check: expected \"IDLE\", the application computes \"BUSY\" (the fake echoes"
                + " BUSY)"), resolved.out());
        assertFalse(Files.exists(bindingsFile("with-check")), "nothing written");
        assertTrue(agent.rpcRequests("trace.mark").isEmpty());
        String document = resolved.out().substring(resolved.out().indexOf("{\n"));
        Map<?, ?> result = (Map<?, ?>) ((List<?>) Json.parseObject(document).get("results"))
                .get(0);
        assertEquals("ORACLE_SUSPECT", result.get("failureKind"));
        Map<?, ?> detail = (Map<?, ?>) result.get("detail");
        assertEquals("DISAGREED", detail.get("reason"));
        assertEquals("BUSY", detail.get("actual"));
        assertEquals(List.of(record(false, "BUSY")), result.get("crossChecks"), "9: the disagreeing record, in the"
                + " form of 8.2, although the bind failed");
        assertEquals(List.of(), result.get("skipped"));

        Path junit = tmp.resolve("junit.xml");
        CliHarness.Outcome run = cli("run", checked.toString(), "--mode", "live", "--junit", junit.toString(),
                "--json").expectExit(Exit.FAILED);
        String xml = Files.readString(junit);
        assertTrue(xml.contains("<error message=\"ORACLE_SUSPECT: oracle same (Echo) disagrees with"
                + " test.echo.check: expected &quot;IDLE&quot;, the application computes &quot;BUSY&quot; (the fake"
                + " echoes BUSY)\" type=\"ORACLE_SUSPECT\">"), xml);
        Map<String, Object> runJson = Json.parseObject(run.out());
        Map<?, ?> runResult = (Map<?, ?>) ((List<?>) runJson.get("results")).get(0);
        assertEquals("ORACLE_SUSPECT", runResult.get("failureKind"));
        assertEquals(List.of(record(false, "BUSY")), runResult.get("crossChecks"), "the bind failed: the record it"
                + " emitted is kept (9)");
        assertEquals(List.of(), runResult.get("skipped"));
    }

    /** A {@code crosscheck} record of {@code test.echo.check} as 8.2 writes it, in its key order. */
    private static Map<String, Object> record(boolean agreed, String answer) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("oracle", "same");
        record.put("fn", "Echo");
        record.put("check", "test.echo.check");
        record.put("tolerance", null);
        record.put("agreed", agreed);
        record.put("reason", "the fake echoes " + answer);
        record.put("expected", "IDLE");
        record.put("actual", answer);
        record.put("evidence", Map.of("test.echo", answer));
        return record;
    }

    @Test
    void aBindThatFailsAfterAnAgreeingCheckStillReportsItsRecordInBothCommands() throws Exception {
        Path guardFails = Scenarios.write(tmp.resolve("module/scenarios"), "guard-fails.yaml", GUARD_FAILS);
        CliHarness.Outcome resolved = cli("resolve", guardFails.toString(), "--yes", "--json")
                .expectExit(Exit.FAILED);
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) Json.parseObject(resolved.out().substring(resolved.out()
                .indexOf("{\n"))).get("results")).get(0);
        assertEquals("RESOLUTION_FAILED", entry.get("failureKind"));
        assertEquals("GUARD", ((Map<?, ?>) entry.get("detail")).get("reason"));
        List<?> records = (List<?>) entry.get("crossChecks");
        assertEquals(List.of(record(true, "IDLE")), records, "the agreement before the guard failed");
        assertEquals(List.copyOf(record(true, "IDLE").keySet()), List.copyOf(((Map<?, ?>) records.get(0)).keySet()),
                "8.2's key order");
        assertEquals(List.of(), entry.get("skipped"));
        assertFalse(Files.exists(bindingsFile("guard-fails")), "nothing written");

        CliHarness.Outcome run = cli("run", guardFails.toString(), "--mode", "live", "--json")
                .expectExit(Exit.FAILED);
        Map<?, ?> result = (Map<?, ?>) ((List<?>) Json.parseObject(run.out()).get("results")).get(0);
        assertEquals("RESOLUTION_FAILED", result.get("failureKind"));
        assertEquals(List.of(record(true, "IDLE")), result.get("crossChecks"));
        assertEquals(List.of(), result.get("skipped"));
    }

    @Test
    void runJsonCarriesTheRecordsAndTheSkippedList() {
        cli("resolve", checked.toString(), guarded.toString(), "--yes").expectExit(Exit.OK);
        CliHarness.Outcome run = cli("run", checked.toString(), guarded.toString(), "--json").expectExit(Exit.OK);
        List<?> results = (List<?>) Json.parseObject(run.out()).get("results");
        Map<?, ?> first = (Map<?, ?>) results.get(0);
        assertEquals("PASS", first.get("status"));
        assertEquals(true, ((Map<?, ?>) ((List<?>) first.get("crossChecks")).get(0)).get("agreed"));
        assertEquals(List.of(), first.get("skipped"));
        Map<?, ?> second = (Map<?, ?>) results.get(1);
        assertEquals("DEGRADED", second.get("status"));
        assertEquals(List.of(SKIP_LINE), second.get("skipped"));
        assertEquals(List.of(SKIP_LINE), second.get("skippedExpectations"));
    }

    /** A credential of 17 characters with a quote and a backslash, which JSON text escapes (P2 11.4). */
    static final String SECRET = "pw\"in\\side-secret";

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
     * phase3-contracts.md 8.2, P2 11.4: a credential that reaches a cross-check's values (the target echoes it, the
     * oracle computes it, a capability's reason holds it) is masked as a value in the {@code --json} output of both
     * commands, whatever shape the pack gave it (a set, an array, a record), although JSON text escapes its quote
     * and backslash: the output is decoded, since a scan of the raw text cannot find such a secret.
     */
    @Test
    void noDecodedValueOfEitherCommandsJsonHoldsACredentialWhateverItsShape() throws Exception {
        Path credentials = Files.writeString(tmp.resolve("credentials.yaml"), "version: 1\ncredentials:\n"
                + "  staff:\n    \"1\": { password: 'pw\"in\\side-secret' }\n");
        Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"));
        Path shapes = Scenarios.write(tmp.resolve("module/scenarios"), "shapes.yaml", """
                scenario: shapes
                pack: clitest
                oracle:
                  same: { fn: Echo, args: 'pw"in\\side-secret', crossCheck: test.echo.shapes }
                steps:
                  - expect: { "counter.value": { gte: 0 } }
                """);
        CliHarness.Outcome run = cli("run", shapes.toString(), "--mode", "live", "--json", "--credentials",
                credentials.toString()).expectExit(Exit.OK);
        assertFalse(run.out().contains(SECRET), "the raw text does not show it either way");
        Map<String, Object> runJson = Json.parseObject(run.out());
        assertEquals(List.of(), holding(runJson, "$", SECRET), run.out());
        Map<?, ?> record = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) runJson.get("results")).get(0))
                .get("crossChecks")).get(0);
        assertEquals("<redacted>", record.get("expected"), "the oracle's value");
        Map<String, Object> masked = new LinkedHashMap<>();
        masked.put("array", List.of("<redacted>"));
        masked.put("record", "Seen[text=<redacted>]");
        masked.put("set", List.of("<redacted>"));
        assertEquals(masked, record.get("evidence"), "JSON-like and masked");
        assertEquals(masked, record.get("actual"));
        assertEquals("the fake echoes <redacted>", record.get("reason"));

        // approve refuses to write a file whose oracle value is a credential; --json still carries the record
        CliHarness.Outcome resolved = cli("resolve", shapes.toString(), "--yes", "--json", "--credentials",
                credentials.toString());
        Map<String, Object> resolveJson = Json.parseObject(resolved.out().substring(resolved.out().indexOf("{\n")));
        assertEquals(List.of(), holding(resolveJson, "$", SECRET), resolved.out());
        assertEquals(1, ((List<?>) ((Map<?, ?>) ((List<?>) resolveJson.get("results")).get(0)).get("crossChecks"))
                .size());

        agent.capabilities.put("test.fancy", new Capability("test.fancy", CapabilityState.UNAVAILABLE,
                "offline: " + SECRET, null));
        String line = "oracle same: cross-check test.echo.check skipped: capability test.fancy is UNAVAILABLE"
                + " (offline: <redacted>)";
        for (String command : List.of("run", "resolve")) {
            CliHarness.Outcome skipped = command.equals("run")
                    ? cli("run", guarded.toString(), "--mode", "live", "--json", "--credentials",
                    credentials.toString()).expectExit(Exit.OK)
                    : cli("resolve", guarded.toString(), "--yes", "--json", "--credentials", credentials.toString())
                    .expectExit(Exit.OK);
            Map<String, Object> json = Json.parseObject(skipped.out().substring(skipped.out().indexOf("{\n")));
            assertEquals(List.of(), holding(json, "$", SECRET), command + ": " + skipped.out());
            Map<?, ?> result = (Map<?, ?>) ((List<?>) json.get("results")).get(0);
            assertEquals(List.of(line), result.get("skipped"), command);
            assertEquals("offline: <redacted>", ((Map<?, ?>) ((List<?>) result.get("crossChecks")).get(0))
                    .get("reason"), command);
        }
    }

    @Test
    void theApprovalScreenShowsEachOutcomeWithItsOracle() {
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
        CliHarness.Outcome checkedOut = cli(yes, "resolve", checked.toString()).expectExit(Exit.OK);
        assertTrue(checkedOut.out().contains("  oracle same (Echo) = \"IDLE\"  must equal \"IDLE\"\n"
                + "    cross-check test.echo.check: agrees (the fake echoes IDLE)\n"), checkedOut.out());
        CliHarness.Outcome guardedOut = cli(yes, "resolve", guarded.toString()).expectExit(Exit.OK);
        assertTrue(guardedOut.out().contains("    cross-check test.echo.check skipped: capability test.fancy is"
                + " UNAVAILABLE (not in this fake)\n"), guardedOut.out());
    }
}
