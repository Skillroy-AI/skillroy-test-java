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

import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.recorded.RecordingGateway;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.inference.triage.Triage;
import ai.skillroy.testjava.inference.triage.TriageOptions;
import ai.skillroy.testjava.inference.triage.TriageResult;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 11.2, 13.4, 15.1 item 15: {@code skillroy triage} through {@link Main#run} over run directories
 * that {@code skillroy run --out} wrote on the fake agent, with the recorded gateway and answers the test records
 * first: a failed run labelled and summarised beside its evidence (the run's own files untouched),
 * {@code --labels-only}, a passed run "nothing to triage", a missing recording (the provider failed for that directory:
 * exit 1), {@code --json}, and the usage errors (exit 64).
 */
class TriageCommandTest {

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private Path runs;
    private Path recordings;

    @BeforeEach
    void setUp() throws Exception {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        Path scenarios = tmp.resolve("scenarios");
        Scenarios.write(scenarios, "wrong.yaml", Scenarios.WRONG);
        Scenarios.write(scenarios, "pass.yaml", Scenarios.PASS);
        runs = tmp.resolve("runs");
        CliHarness.run(tmp, "run", scenarios.toString(), "--out", runs.toString(), "--port",
                Integer.toString(agent.port()), "--token-file", tokenFile.toString()).expectExit(Exit.FAILED);
        assertTrue(Files.isRegularFile(runs.resolve("wrong-count/run.jsonl")));
        recordings = Files.createDirectories(tmp.resolve("recordings"));
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome triage(String... args) {
        List<String> all = new ArrayList<>(List.of("triage"));
        all.addAll(List.of(args));
        return CliHarness.run(tmp, all.toArray(String[]::new));
    }

    /** Records a label and a summary for the failed run's requests, as a triage through a recording gateway does. */
    private void record() throws Exception {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("label", "SCENARIO_DEFECT");
        label.put("confidence", 0.7);
        label.put("oneLine", "the scenario expects a count the desk never reaches");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("summary", "One poke makes the counter 1; the expectation asks for 99.");
        summary.put("lookAt", List.of("run.jsonl", "the expect step"));
        summary.put("proposal", "Expect 1, or poke 99 times.");
        Usage usage = new Usage("scripted", "claude-haiku-4-5", "claude-haiku-4-5", "req-1", 1000, 40, 0, 0, 0,
                false, "0.0012", Duration.ZERO, null);
        List<ModelResponse> answers = List.of(ScriptedGateway.structured(label, usage),
                ScriptedGateway.structured(summary, usage));
        try (RecordingGateway gateway = new RecordingGateway(ScriptedGateway.sequence(answers), recordings,
                PromptMask.keysOnly(), false, Clock.systemUTC())) {
            TriageResult recorded = Triage.run(runs.resolve("wrong-count"), gateway, PromptMask.keysOnly(),
                    PriceTable.builtIn(), new TriageOptions(false, null));
            assertEquals(TriageResult.Outcome.TRIAGED, recorded.outcome(), recorded.message());
        }
        Files.delete(runs.resolve("wrong-count/failure/triage.json"));
        Files.delete(runs.resolve("wrong-count/failure/triage.md"));
    }

    /** Every file of a run directory but triage's own, with its bytes. */
    private static Map<String, String> runFiles(Path dir) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String name = dir.relativize(f).toString();
                if (!name.startsWith("triage") && !name.contains("triage.")) {
                    files.put(name, new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1));
                }
            }
        }
        return files;
    }

    @Test
    void aFailedRunIsLabelledAndSummarisedBesideItsEvidence() throws Exception {
        record();
        Path failed = runs.resolve("wrong-count");
        Map<String, String> before = runFiles(failed);
        CliHarness.Outcome outcome = triage(failed.toString(), runs.resolve("poke-twice").toString(), "--inference",
                "recorded", "--recordings", recordings.toString()).expectExit(Exit.OK);
        String out = outcome.out();
        assertTrue(out.contains(failed + ": wrong-count FAIL ASSERTION -> SCENARIO_DEFECT (confidence 0.7): the"
                + " scenario expects a count the desk never reaches"), out);
        assertTrue(out.contains("One poke makes the counter 1"), "the summary: " + out);
        assertTrue(out.contains(runs.resolve("poke-twice") + ": nothing to triage"), out);
        assertTrue(out.contains("model calls: 2\nmodel cost: $0 (no budget)\n"), out);
        assertTrue(Files.isRegularFile(failed.resolve("failure/triage.json")));
        assertTrue(Files.isRegularFile(failed.resolve("failure/triage.md")));
        assertEquals(2, Files.readAllLines(failed.resolve("triage/inference.jsonl")).size());
        assertEquals(2, Files.readAllLines(failed.resolve("triage/cost-ledger.jsonl")).size());
        assertEquals(before, runFiles(failed), "the run's own files are untouched");
        assertFalse(Files.exists(runs.resolve("poke-twice/failure/triage.json")), "a passed run is not triaged");

        CliHarness.Outcome labels = triage(failed.toString(), "--labels-only", "--inference", "recorded",
                "--recordings", recordings.toString(), "--json").expectExit(Exit.OK);
        Map<String, Object> document = Json.parseObject(labels.out());
        Map<?, ?> result = (Map<?, ?>) ((List<?>) document.get("results")).get(0);
        assertEquals("TRIAGED", result.get("outcome"));
        assertEquals("SCENARIO_DEFECT", ((Map<?, ?>) result.get("label")).get("label"));
        assertEquals(null, result.get("summary"), "labels only");
        assertEquals(1L, document.get("modelCalls"));
        assertEquals("0", document.get("modelCost"));
        assertEquals("recorded", ((Map<?, ?>) document.get("inference")).get("gateway"));
        assertTrue(labels.err().contains("model cost: $0 (no budget)"), labels.err());
    }

    @Test
    void aMissingRecordingIsTheProviderFailingForThatDirectory() {
        CliHarness.Outcome outcome = triage(runs.resolve("wrong-count").toString(), "--inference", "recorded",
                "--recordings", recordings.toString()).expectExit(Exit.FAILED);
        assertTrue(outcome.out().contains("triage failed: the model provider could not be reached for the label"),
                outcome.out());
        assertFalse(Files.exists(runs.resolve("wrong-count/failure/triage.json")));
    }

    @Test
    void usageErrorsAreExitCode64() throws Exception {
        triage().expectExit(Exit.USAGE);
        CliHarness.Outcome none = triage(runs.resolve("wrong-count").toString(), "--inference", "none")
                .expectExit(Exit.USAGE);
        assertTrue(none.err().contains("triage needs a model gateway"), none.err());
        CliHarness.Outcome noKey = triage(runs.resolve("wrong-count").toString(), "--inference", "claude")
                .expectExit(Exit.USAGE);
        assertTrue(noKey.err().contains("the model key is not set (ANTHROPIC_API_KEY)"), noKey.err());
        assertTrue(triage(tmp.resolve("nowhere").toString(), "--inference", "recorded", "--recordings",
                recordings.toString()).expectExit(Exit.USAGE).err().contains("no such directory"));
        assertTrue(triage(tmp.toString(), "--inference", "recorded", "--recordings", recordings.toString())
                .expectExit(Exit.USAGE).err().contains("not a run output directory (no run.jsonl)"));
        assertTrue(triage(runs.resolve("wrong-count").toString()).expectExit(Exit.USAGE).err()
                .contains("triage needs a model gateway"), "no key and no --inference: gateway none");
    }
}
