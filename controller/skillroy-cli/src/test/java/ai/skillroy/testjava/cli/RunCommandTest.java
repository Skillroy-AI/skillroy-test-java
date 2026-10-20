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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.scenario.RunResult;
import ai.skillroy.testjava.scenario.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code skillroy run} with the in-test {@code clitest} pack against the fake agent. */
class RunCommandTest {

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Path scenarios;

    @BeforeEach
    void setUp() throws IOException {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        scenarios = tmp.resolve("scenarios");
        Scenarios.write(scenarios, "pass.yaml", Scenarios.PASS);
        Scenarios.write(scenarios, "guarded.yaml", Scenarios.GUARDED);
        Scenarios.write(scenarios, "wrong.yaml", Scenarios.WRONG);
        Scenarios.write(scenarios, "needs-fancy.yaml", Scenarios.NEEDS_FANCY);
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome run(String... args) {
        List<String> all = new ArrayList<>(List.of("run"));
        all.addAll(List.of(args));
        all.addAll(connection);
        return CliHarness.run(tmp.resolve("unused-discovery"), all.toArray(String[]::new)).never(agent.token);
    }

    private String file(String name) {
        return scenarios.resolve(name).toString();
    }

    @Test
    void runsEveryScenarioReportsEachVerdictAndWritesEvidenceAndJunit() throws IOException {
        Path out = tmp.resolve("out");
        Path junit = tmp.resolve("reports/TEST-skillroy.xml");
        CliHarness.Outcome outcome = run(file("pass.yaml"), file("guarded.yaml"), file("wrong.yaml"),
                file("needs-fancy.yaml"), "--out", out.toString(), "--junit", junit.toString())
                .expectExit(Exit.FAILED);
        String stdout = outcome.out();
        assertTrue(stdout.startsWith("PASS      poke-twice  ("), stdout);
        assertTrue(stdout.contains("\nDEGRADED  guarded  ("), stdout);
        assertTrue(stdout.contains("\n  skipped: "), stdout);
        assertTrue(stdout.contains("\nFAIL      wrong-count  ("), stdout);
        assertTrue(stdout.contains("\n  ASSERTION at s2 (line 6): "), stdout);
        assertTrue(stdout.contains("\n  detail:\n    {"), stdout);
        assertTrue(stdout.contains("\n  UI at failure:\n"), stdout);
        assertTrue(stdout.contains("- window \"Fake Desk\" [ref=h1]"), stdout);
        assertTrue(stdout.contains("\n  evidence: " + out.resolve("wrong-count")), stdout);
        assertTrue(stdout.contains("\nERROR     needs-fancy  ("), stdout);
        assertTrue(stdout.contains("\n  PRECONDITION_FAILED: "), stdout);
        assertTrue(stdout.endsWith("4 scenarios: 1 passed, 1 degraded, 1 failed, 1 errors, 0 skipped\nmodel calls: 0\n"
                        + "model cost: $0 (budget $0.50)\n"),
                "Phase 2 (12.1): the summary ends with the model calls, and Phase 5 (13.4) with their cost: "
                        + stdout);
        assertFalse(stdout.contains("evidence: " + out.resolve("poke-twice")), "no evidence line for a pass");

        for (String id : List.of("poke-twice", "guarded", "wrong-count", "needs-fancy")) {
            assertTrue(Files.isRegularFile(out.resolve(id).resolve("run.jsonl")), id);
        }
        assertTrue(Files.isRegularFile(out.resolve("wrong-count/failure/snapshot.json")));
        assertFalse(Files.exists(out.resolve("poke-twice/failure")));

        String xml = Files.readString(junit);
        assertTrue(xml.contains("<testsuite name=\"skillroy\" tests=\"5\" failures=\"1\" errors=\"1\" skipped=\"1\""),
                xml);
        assertTrue(xml.contains("<testcase name=\"environment\" classname=\"skillroy\" time=\"0.000\"/>"), xml);
        assertTrue(xml.contains("<testcase name=\"poke-twice\""), xml);
        assertTrue(xml.contains("<failure message=\"ASSERTION at s2"), xml);
        assertTrue(xml.contains("<error message=\"PRECONDITION_FAILED"), xml);
        assertTrue(xml.contains("<skipped message="), xml);
        assertTrue(outcome.err().contains("JUnit report written to " + junit), outcome.err());
        // The start mark follows the precondition check, so the scenario that fails its preconditions has none.
        assertEquals(3, agent.rpcRequests("trace.mark").size(), "one start mark per scenario that started");
    }

    @Test
    void degradedPassesUnlessMappedToFailure() throws IOException {
        Path junit = tmp.resolve("junit.xml");
        run(file("pass.yaml"), file("guarded.yaml"), "--junit", junit.toString()).expectExit(Exit.OK);
        assertTrue(Files.readString(junit).contains("<skipped"));

        CliHarness.Outcome failure = run(file("guarded.yaml"), "--degraded-as", "failure", "--junit",
                junit.toString()).expectExit(Exit.FAILED);
        assertTrue(failure.out().contains("1 scenario: 0 passed, 1 degraded, 0 failed, 0 errors"), failure.out());
        assertTrue(Files.readString(junit).contains("<failure message="));

        run(file("guarded.yaml"), "--degraded-as", "PASS", "--junit", junit.toString(), "--suite", "nightly")
                .expectExit(Exit.OK);
        String xml = Files.readString(junit);
        assertTrue(xml.contains("<testsuite name=\"nightly\"") && !xml.contains("<skipped")
                && !xml.contains("<failure"), xml);
    }

    @Test
    void aDirectoryRunsItsScenariosInNameOrder() {
        String stdout = run(scenarios.toString()).expectExit(Exit.FAILED).out();
        List<String> verdicts = stdout.lines().filter(l -> !l.startsWith(" ") && !l.contains("scenarios:")
                        && !l.startsWith("model calls:") && !l.startsWith("model cost:"))
                .map(l -> l.split("\\s+")[1]).toList();
        assertEquals(List.of("guarded", "needs-fancy", "poke-twice", "wrong-count"), verdicts);
    }

    @Test
    void aCommittedPlanRunsAsItIsAndAVersionMismatchIsAWarning() throws IOException {
        Path plans = tmp.resolve("plans");
        CliHarness.run(tmp, "compile", file("pass.yaml"), "--out", plans.toString()).expectExit(Exit.OK);
        Path plan = plans.resolve("poke-twice.plan.json");
        CliHarness.Outcome outcome = run(plan.toString()).expectExit(Exit.OK);
        assertTrue(outcome.out().startsWith("PASS      poke-twice"), outcome.out());
        assertEquals("", outcome.err());

        Files.writeString(plan, Files.readString(plan).replace("\"packVersion\": \"1.0.0\"",
                "\"packVersion\": \"0.9.0\""));
        agent.counter.set(0);
        CliHarness.Outcome mismatch = run(plan.toString()).expectExit(Exit.OK);
        assertTrue(mismatch.err().contains("was compiled with pack clitest 0.9.0 and runs with version 1.0.0"),
                mismatch.err());
    }

    @Test
    void aScenarioErrorRunsNothingAndIsReportedInTheJunitEnvironmentCase() throws IOException {
        Path broken = Scenarios.write(scenarios, "broken.yaml", Scenarios.BROKEN);
        Path junit = tmp.resolve("junit.xml");
        CliHarness.Outcome outcome = run(file("pass.yaml"), broken.toString(), "--junit", junit.toString())
                .expectExit(Exit.SCENARIO);
        assertTrue(outcome.err().contains(broken + ":5: "), outcome.err());
        assertTrue(outcome.err().contains("nothing was run"), outcome.err());
        assertEquals("", outcome.out());
        assertTrue(agent.requests.isEmpty(), "the agent must not be contacted");
        String xml = Files.readString(junit);
        assertTrue(xml.contains("<testcase name=\"environment\"") && xml.contains("<error message=")
                && xml.contains("broken.yaml:5:"), xml);
    }

    @Test
    void anUnreachableAgentIsExitCodeThreeAndAnEnvironmentError() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        Path junit = tmp.resolve("junit.xml");
        CliHarness.Outcome outcome = CliHarness.run(tmp, "run", file("pass.yaml"), "--junit", junit.toString(),
                "--port", Integer.toString(freePort), "--token-file", tmp.resolve("agent.token").toString())
                .expectExit(Exit.UNREACHABLE).never(agent.token);
        assertTrue(outcome.err().contains("agent unreachable"), outcome.err());
        String xml = Files.readString(junit);
        assertTrue(xml.contains("tests=\"1\" failures=\"0\" errors=\"1\"") && xml.contains("agent unreachable"),
                xml);
    }

    @Test
    void theTokenNeverReachesTheOutputOrTheReport() throws Exception {
        agent.handlers.put("app.state", p -> Map.of("test", Map.of("echo", agent.token)));
        Path junit = tmp.resolve("junit.xml");
        String out = run(file("wrong.yaml"), "--junit", junit.toString()).expectExit(Exit.FAILED).out();
        assertTrue(out.contains("<redacted>"), out);
        String xml = Files.readString(junit);
        assertFalse(xml.contains(agent.token), xml);
        // Security finding S27: the token is scrubbed before escaping, so the report is still well-formed XML and
        // reads back as the same literal the console shows.
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(junit.toFile());
        Element failure = (Element) doc.getElementsByTagName("failure").item(0);
        assertTrue(failure.getTextContent().contains("\"echo\":\"<redacted>\""), failure.getTextContent());
        assertFalse(failure.getTextContent().contains(agent.token));
    }

    @Test
    void aClickThatFellBackIsDegradedAndListedEverywhere() throws Exception {
        Path fallback = Scenarios.write(scenarios, "fallback.yaml", Scenarios.FALLBACK);
        Path junit = tmp.resolve("junit.xml");
        CliHarness.Outcome outcome = run(fallback.toString(), "--junit", junit.toString()).expectExit(Exit.OK);
        String entry = "s1: click fell back from COMPONENT to ACTION (FOCUS_FAILED: no focus within 500 ms)";
        assertTrue(outcome.out().startsWith("DEGRADED  click-fallback  ("), outcome.out());
        assertTrue(outcome.out().contains("\n  degraded: " + entry), outcome.out());
        assertFalse(outcome.out().contains("skipped:"), outcome.out());
        assertTrue(outcome.out().contains("1 scenario: 0 passed, 1 degraded, 0 failed, 0 errors"), outcome.out());
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(junit.toFile());
        assertEquals("DEGRADED: degraded fidelity: " + entry, ((Element) doc.getElementsByTagName("skipped")
                .item(0)).getAttribute("message"));
        Map<String, Object> params = new LinkedHashMap<>(JsonView.of(Json.parseObject(
                agent.rpcRequests("swing.click").get(0).body())).obj("params").raw());
        params.remove("_meta");
        assertEquals(Map.of("by", Map.of("name", "DUE"), "rung", "COMPONENT", "activateWindow", true, "fallback",
                "ACTION"), params);

        run(fallback.toString(), "--degraded-as", "failure").expectExit(Exit.FAILED);
        JsonView result = JsonView.of(cast(JsonView.of(Json.parseObject(run(fallback.toString(), "--json")
                .expectExit(Exit.OK).out())).list("results").get(0)));
        assertEquals(List.of(entry), result.list("degradedFidelity"));
        assertEquals(List.of(entry), JsonView.of(cast(result.list("steps").get(0))).list("degradedFidelity"));

        Path strict = Scenarios.write(scenarios, "strict.yaml", Scenarios.STRICT);
        String failed = run(strict.toString()).expectExit(Exit.FAILED).out();
        assertTrue(failed.contains("ASSERTION at s1 (line 5): click did not fire: FOCUS_FAILED: FOCUS_FAILED: no"
                + " focus within 500 ms"), failed);
    }

    @Test
    void controlCharactersFromTheTargetNeverReachTheTerminal() {
        // Security finding S26: an error message and UI text with OSC 52, erase-line, a bare CR, CSI (C1) and DEL.
        String hostile = "bad \u001b]52;c;aGk=\u0007\u001b[2K\rPASS \u009b31m \u007f \u202e";
        agent.handlers.put("test.poke", p -> {
            throw new RpcException(ErrorCodes.INPUT_REJECTED, hostile);
        });
        agent.handlers.put("app.state", p -> Map.of("test", Map.of("text", hostile)));
        CliHarness.Outcome outcome = run(file("pass.yaml")).expectExit(Exit.FAILED);
        assertNoRawControls(outcome.out());
        assertNoRawControls(outcome.err());
        assertTrue(outcome.out().contains("bad \\u001b]52;c;aGk=\\u0007\\u001b[2K\\u000dPASS \\u009b31m"),
                outcome.out());

        CliHarness.Outcome json = run(file("pass.yaml"), "--json").expectExit(Exit.FAILED);
        assertNoRawControls(json.out());
        JsonView result = JsonView.of(cast(JsonView.of(Json.parseObject(json.out())).list("results").get(0)));
        assertTrue(result.str("message").endsWith(hostile), "the JSON still round-trips: " + result.str("message"));
    }

    @Test
    void aRunThatBreaksStillWritesTheJunitReport() throws IOException {
        Path crash = Scenarios.write(scenarios, "crash.yaml", Scenarios.CRASH);
        Path junit = tmp.resolve("junit.xml");
        List<String> args = new ArrayList<>(List.of("run", file("pass.yaml"), crash.toString(), "--junit",
                junit.toString()));
        args.addAll(connection);
        assertThrows(InternalError.class, () -> CliHarness.run(tmp, args.toArray(String[]::new)));
        String xml = Files.readString(junit);
        assertTrue(xml.contains("<testcase name=\"poke-twice\""), "the results so far: " + xml);
        assertTrue(xml.contains("the run stopped after 1 of 2 scenarios: internal error: java.lang.InternalError:"
                + " pack bug"), xml);
    }

    @Test
    void describeListsSkippedExpectationsAndDegradedFidelity() {
        RunResult both = new RunResult("both", Status.DEGRADED, null, null, null, Map.of(), List.of(),
                List.of("s2: x skipped"), Duration.ofMillis(5), List.of("s1: click fell back from COMPONENT to"
                + " ACTION (r)"));
        assertEquals("DEGRADED  both  (0.005 s)\n  skipped: s2: x skipped\n  degraded: s1: click fell back from"
                + " COMPONENT to ACTION (r)", RunCommand.describe(both, null));
    }

    /** phase8-contracts.md 7.1 (ES): a warning fails a step that does not accept it and is listed either way. */
    @Test
    void warningsArePrintedAndCarriedInTheJson() throws Exception {
        agent.warnings.put("test.poke", List.of(new ai.skillroy.testjava.protocol.CallWarning("VALUE_DROPPED",
                "nothing to poke", null)));
        String line = "s1: poke: test.poke VALUE_DROPPED (nothing to poke)";
        Path unaccepted = Scenarios.write(scenarios, "warned.yaml", """
                scenario: warned
                pack: clitest
                steps:
                  - poke: {}
                """);
        String failed = run(unaccepted.toString()).expectExit(Exit.FAILED).out();
        assertTrue(failed.contains("ASSERTION at s1 (line 4): poke: test.poke reported VALUE_DROPPED: nothing to poke"
                + " (a step accepts it with accept: [VALUE_DROPPED])"), failed);
        assertTrue(failed.contains("\n  warning: " + line), failed);
        Path accepted = Scenarios.write(scenarios, "accepted.yaml", """
                scenario: accepted
                pack: clitest
                steps:
                  - poke: {}
                    accept: [VALUE_DROPPED]
                """);
        String passed = run(accepted.toString()).expectExit(Exit.OK).out();
        assertTrue(passed.startsWith("PASS      accepted  ("), passed);
        assertTrue(passed.contains("\n  warning: " + line), passed);
        JsonView result = JsonView.of(cast(JsonView.of(Json.parseObject(run(accepted.toString(), "--json")
                .expectExit(Exit.OK).out())).list("results").get(0)));
        assertEquals(List.of(line), result.list("warnings"));
        assertEquals(List.of(line), JsonView.of(cast(result.list("steps").get(0))).list("warnings"));
        agent.warnings.clear();
        JsonView none = JsonView.of(cast(JsonView.of(Json.parseObject(run(accepted.toString(), "--json")
                .expectExit(Exit.OK).out())).list("results").get(0)));
        assertEquals(List.of(), none.list("warnings"), "the key is always there, as degradedFidelity is");
    }

    @Test
    void jsonOutputSummarisesTheRun() {
        CliHarness.Outcome outcome = run(file("pass.yaml"), file("wrong.yaml"), "--json", "--out",
                tmp.resolve("out").toString()).expectExit(Exit.FAILED);
        JsonView document = JsonView.of(Json.parseObject(outcome.out()));
        List<Object> results = document.list("results");
        assertEquals(2, results.size());
        JsonView pass = JsonView.of(cast(results.get(0)));
        assertEquals("PASS", pass.str("status"));
        assertEquals(List.of("s1", "s2", "s3", "verify"), pass.list("steps").stream()
                .map(step -> ((Map<?, ?>) step).get("id")).toList());
        JsonView fail = JsonView.of(cast(results.get(1)));
        assertEquals("FAIL", fail.str("status"));
        assertEquals("ASSERTION", fail.str("failureKind"));
        assertEquals("s2", fail.str("failedStepId"));
        assertEquals(6L, fail.num("failedStepLine", 0));
        assertEquals(tmp.resolve("out/wrong-count").toString(), fail.str("outputDir"));
        assertEquals("counter.value", fail.obj("detail").str("path"));
        JsonView summary = document.obj("summary");
        assertEquals(2L, summary.num("total", 0));
        assertEquals(1L, summary.num("pass", 0));
        assertEquals(1L, summary.num("fail", 0));
        assertEquals(1L, document.num("exitCode", 0));
        assertTrue(outcome.err().contains("PASS      poke-twice"), "progress goes to stderr: " + outcome.err());
    }

    @Test
    void stepTimeoutOverridesTheScenarioDefault() throws IOException {
        // No timeouts key: the default step timeout is 10 s; the override makes the failing expectation give up
        // after 200 ms of retries.
        Path slow = Scenarios.write(scenarios, "slow.yaml", Scenarios.WRONG.replace("timeouts: { step: 300ms }\n",
                "").replace("wrong-count", "slow-wrong"));
        String out = run(slow.toString(), "--step-timeout", "200ms", "--json").expectExit(Exit.FAILED).out();
        JsonView result = JsonView.of(cast(JsonView.of(Json.parseObject(out)).list("results").get(0)));
        assertEquals("ASSERTION", result.str("failureKind"));
        assertTrue(result.num("durationMs", Long.MAX_VALUE) < 8_000, out);
        assertTrue(run(slow.toString(), "--step-timeout", "0").expectExit(Exit.USAGE).err()
                .contains("--step-timeout"));
    }

    @Test
    void usageErrorsAreCaughtBeforeAnythingRuns() throws IOException {
        Scenarios.write(tmp.resolve("dup"), "again.yaml", Scenarios.PASS);
        CliHarness.Outcome duplicate = run(file("pass.yaml"), tmp.resolve("dup/again.yaml").toString())
                .expectExit(Exit.USAGE);
        assertTrue(duplicate.err().contains("are both scenario 'poke-twice'"), duplicate.err());
        assertTrue(run(file("pass.yaml"), "--degraded-as", "maybe").expectExit(Exit.USAGE).err()
                .contains("--degraded-as must be skipped, failure or pass"));
        Path notADir = Files.writeString(tmp.resolve("out-file"), "x");
        assertTrue(run(file("pass.yaml"), "--out", notADir.toString()).expectExit(Exit.USAGE).err()
                .contains("is not a directory"));
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            // Phase 6 security fix, pass 2, P2-L8-F3, P2-L8-S1: an output directory others may write to (as one
            // another user created first under /tmp is) is a usage error; nothing is written into it.
            Path shared = Files.createDirectories(tmp.resolve("shared-out"));
            Files.setPosixFilePermissions(shared, java.nio.file.attribute.PosixFilePermissions.fromString(
                    "rwxrwxrwx"));
            assertTrue(run(file("pass.yaml"), "--out", shared.toString()).expectExit(Exit.USAGE).err()
                    .contains("--out " + shared + " cannot be used: others may write to it without the sticky bit"));
            try (java.util.stream.Stream<Path> entries = Files.list(shared)) {
                assertEquals(0L, entries.count());
            }
        }
        assertTrue(run().expectExit(Exit.USAGE).err().contains("missing argument"));
        assertTrue(agent.requests.isEmpty(), "the agent must not be contacted");
    }

    /** No C0 control but newline and tab, no DEL, no C1 and no bidi override. */
    static void assertNoRawControls(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7f && !(c >= 0x80 && c <= 0x9f)
                    && !(c >= '\u202a' && c <= '\u202e'));
            assertTrue(ok, "raw U+" + Integer.toHexString(c) + " at " + i + " in:\n" + text);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
