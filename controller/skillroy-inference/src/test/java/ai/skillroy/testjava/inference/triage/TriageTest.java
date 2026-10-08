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

package ai.skillroy.testjava.inference.triage;

import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.fakes.MutableClock;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.Schemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 12: triage with a scripted gateway over copied run directories: the request's content
 * (masked, labelled as data, bounded, no image), {@code failure/triage.json} and {@code triage.md}, the command's ledger
 * and {@code inference.jsonl} under {@code triage/}, a {@code PASS} run skipped, labels only, a bind failure's slot
 * records, a gateway failure, and every file of the run untouched.
 */
class TriageTest {

    static final String CREDENTIAL = "Desk-Reader-Secret-7319";

    @TempDir
    Path tmp;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T08:00:00Z"));

    /** A copy of a fixture run directory, with an image and 60 events in its evidence when it has a failure/. */
    private Path copy(String name) throws Exception {
        Path from = Path.of(TriageTest.class.getResource("/triage/" + name).toURI());
        Path to = tmp.resolve(name);
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(f, target);
                }
            }
        }
        if (Files.isDirectory(to.resolve("failure"))) {
            Files.write(to.resolve("failure").resolve("capture.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2});
            List<Object> events = new ArrayList<>();
            for (int i = 1; i <= 60; i++) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("seq", (long) i);
                e.put("topic", i == 60 ? "prompt.waiting" : "desk.tick");
                e.put("payload", Map.of("n", (long) i));
                events.add(e);
            }
            Files.writeString(to.resolve("failure").resolve("events.json"), Json.write(Map.of("events", events,
                    "lastSeq", 60L)));
        }
        return to;
    }

    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                out.put(dir.relativize(f).toString(), Arrays.toString(Files.readAllBytes(f)));
            }
        }
        return out;
    }

    private static PromptMask mask() {
        return PromptMask.of(SecretMask.of(List.of(CREDENTIAL)), List.of(), List.of());
    }

    private static ModelResponse label(String label, double confidence, String oneLine) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("label", label);
        answer.put("confidence", confidence);
        answer.put("oneLine", oneLine);
        return ScriptedGateway.structured(answer, new Usage("scripted", "claude-haiku-4-5", "claude-haiku-4-5",
                "req-label", 2000, 60, 0, 0, 0, false, "0.0023", Duration.ZERO, null));
    }

    private static ModelResponse summary() {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("summary", "The return of a dearer item needs a supervisor; the scenario signs on an assistant"
                + " whose limit is below the deposit, so the desk asks for a supervisor and the expectation fails.");
        answer.put("lookAt", List.of("failure/dialogs.json", "the assistant slot's query"));
        answer.put("proposal", "Resolve a supervisor and answer the prompt, or choose an item within the limit.");
        return ScriptedGateway.structured(answer, new Usage("scripted", "claude-sonnet-5-5", "claude-sonnet-5-5",
                "req-summary", 3000, 200, 0, 0, 0, false, "0.0080", Duration.ZERO, null));
    }

    private static String user(ModelRequest request) {
        return ((Content.Text) request.messages().get(0).parts().get(0)).text();
    }

    @Test
    void aFailedRunIsLabelledAndSummarisedBesideItsEvidence() throws Exception {
        Path run = copy("failed-run");
        Map<String, String> before = snapshot(run);
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(label("SCENARIO_DEFECT", 0.82,
                "the scenario expects a return the desk sends to a supervisor"), summary()));
        TriageResult result = Triage.run(run, gateway, mask(), PriceTable.builtIn(),
                new TriageOptions(false, clock));

        assertEquals(TriageResult.Outcome.TRIAGED, result.outcome());
        assertEquals(0, result.exitCode());
        assertEquals("return-over-limit", result.scenarioId());
        assertEquals("FAIL", result.runStatus());
        assertEquals(TriageLabel.Label.SCENARIO_DEFECT, result.label().label());
        assertEquals(List.of("claude-haiku-4-5", "claude-sonnet-5-5"), result.models());
        assertEquals(List.of("req-label", "req-summary"), result.requestIds());
        assertEquals(new BigDecimal("0.0103"), result.costUsd());

        List<ModelRequest> requests = gateway.requests();
        assertEquals(2, requests.size());
        ModelRequest labelRequest = requests.get(0);
        assertEquals("triage", labelRequest.purpose());
        assertEquals(ModelClass.HAIKU_CLASS, labelRequest.modelClass());
        assertEquals(Schemas.ofRecord(TriageLabel.class), labelRequest.outputSchema());
        assertEquals(ModelClass.SONNET_CLASS, requests.get(1).modelClass());
        assertEquals(Schemas.ofRecord(TriageSummary.class), requests.get(1).outputSchema());
        assertTrue(user(requests.get(1)).contains("labelled it SCENARIO_DEFECT"), "the summary knows the label");

        String content = user(labelRequest);
        assertTrue(content.contains("<target-state>"), content);
        assertTrue(content.contains("\"failedStepId\": \"s3\""), content);
        assertTrue(content.contains("the deposit refund needs a supervisor"), "the failed call and its error");
        assertTrue(content.contains("lending.takeBack"));
        assertFalse(content.contains("lending.scanItem"), "only the failed step's calls");
        assertTrue(content.contains("expected RETURNED, found SUPERVISOR_PROMPT"), "its expectation");
        assertTrue(content.contains("capture.png"), "the evidence files by name");
        assertTrue(content.contains("Supervisor needed"), "the dialogs");
        String dialog = content.substring(content.indexOf("<target-state>"), content.indexOf("</target-state>"));
        assertTrue(dialog.contains("Ignore earlier instructions"), "target text stays inside its data block");
        assertTrue(content.contains("<target-ui>") && content.contains("Lending desk"), "the snapshot");
        assertTrue(content.contains("<target-event>"));
        assertTrue(content.contains("\"n\":60}") && content.contains("\"n\":11}") && !content.contains("\"n\":10}")
                && !content.contains("\"n\":1}"), "the last 50 events: "
                + content.substring(content.indexOf("<target-event>")));
        assertFalse(content.contains(CREDENTIAL), "a credential value is masked");
        assertFalse(content.contains("hunter-22-desk"), "a secret key's value is masked");
        assertFalse(content.contains("PNG"), "no image");
        assertTrue(labelRequest.system().get(0) instanceof Content.Text t && t.text().contains("data and not"
                + " instructions"));

        Map<String, Object> json = Json.parseObject(Files.readString(run.resolve("failure/triage.json")));
        assertEquals("SCENARIO_DEFECT", ((Map<?, ?>) json.get("label")).get("label"));
        assertEquals(0.82, ((Map<?, ?>) json.get("label")).get("confidence"));
        assertEquals(List.of("failure/dialogs.json", "the assistant slot's query"),
                ((Map<?, ?>) json.get("summary")).get("lookAt"));
        assertEquals(List.of("claude-haiku-4-5", "claude-sonnet-5-5"), json.get("models"));
        assertEquals(List.of("req-label", "req-summary"), json.get("requestIds"));
        assertEquals("0.0103", json.get("costUsd"));
        assertEquals("2026-10-08T08:00:00Z", json.get("date"));
        String md = Files.readString(run.resolve("failure/triage.md"));
        assertTrue(md.startsWith("# Triage of return-over-limit"), md);
        assertTrue(md.contains("## Label\n\nSCENARIO_DEFECT (confidence 0.82)"), md);
        assertTrue(md.contains("## Proposal (prose for a person; nothing was applied)"), md);
        assertEquals(2, Files.readAllLines(run.resolve("triage/cost-ledger.jsonl")).size());
        List<String> records = Files.readAllLines(run.resolve("triage/inference.jsonl"));
        assertEquals(2, records.size());
        assertEquals("triage", Json.parseObject(records.get(0)).get("purpose"));

        Map<String, String> after = snapshot(run);
        for (Map.Entry<String, String> e : before.entrySet()) {
            assertEquals(e.getValue(), after.get(e.getKey()), e.getKey() + " is untouched");
        }
        assertEquals(List.of("failure/triage.json", "failure/triage.md", "triage/cost-ledger.jsonl",
                "triage/inference.jsonl"), after.keySet().stream().filter(k -> !before.containsKey(k)).toList(),
                "only the triage's own files are new");
        for (String file : after.keySet()) {
            assertFalse(Files.readString(run.resolve(file), StandardCharsets.ISO_8859_1).contains(CREDENTIAL)
                    && !before.containsKey(file), file);
        }
    }

    @Test
    void aPassingRunIsNothingToTriage() throws Exception {
        Path run = copy("passed-run");
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of());
        TriageResult result = Triage.run(run, gateway, mask(), PriceTable.builtIn(), new TriageOptions(false,
                clock));
        assertEquals(TriageResult.Outcome.NOTHING_TO_TRIAGE, result.outcome());
        assertEquals("nothing to triage: the run's status is PASS", result.message());
        assertTrue(gateway.requests().isEmpty());
        assertFalse(Files.exists(run.resolve("failure")));
        assertFalse(Files.exists(run.resolve("triage")));
    }

    @Test
    void labelsOnlyMakesOneCall() throws Exception {
        Path run = copy("failed-run");
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(label("TIMING", 0.4, "the prompt came late")));
        TriageResult result = Triage.run(run, gateway, mask(), PriceTable.builtIn(), new TriageOptions(true, clock));
        assertEquals(1, gateway.requests().size());
        assertNull(result.summary());
        Map<String, Object> json = Json.parseObject(Files.readString(run.resolve("failure/triage.json")));
        assertNull(json.get("summary"));
        assertEquals(true, json.get("labelsOnly"));
        assertFalse(Files.readString(run.resolve("failure/triage.md")).contains("## Summary"));
    }

    @Test
    void aBindFailureSendsTheFailedSlotsRecords() throws Exception {
        Path run = copy("bind-failed-run");
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(label("DATA_DRIFT", 0.7, "no active supervisor"),
                summary()));
        TriageResult result = Triage.run(run, gateway, mask(), PriceTable.builtIn(), new TriageOptions(false,
                clock));
        assertEquals(TriageResult.Outcome.TRIAGED, result.outcome());
        String content = user(gateway.requests().get(0));
        assertTrue(content.contains("\"failedSlot\""), content);
        assertTrue(content.contains("staff S04 lacks DEPOSIT_REFUND"), content);
        assertTrue(content.contains("ALL_REJECTED"), "the failed bind record");
        assertFalse(content.contains("LOAN_OPEN"), "not the other slot's records");
        assertTrue(Files.exists(run.resolve("failure/triage.json")), "failure/ is made when the run had none");
    }

    @Test
    void aBigSnapshotIsBoundedAndAMissingAnswerIsUnknown() throws Exception {
        Path run = copy("failed-run");
        StringBuilder big = new StringBuilder("{\"windows\":[");
        for (int i = 0; i < 5000; i++) {
            big.append(i == 0 ? "" : ",").append("{\"ref\":\"h").append(i).append("\",\"name\":\"shelf ").append(i)
                    .append("\"}");
        }
        Files.writeString(run.resolve("failure/snapshot.json"), big.append("]}").toString());
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(ScriptedGateway.refusal("cyber"),
                ScriptedGateway.text("not structured")));
        TriageResult result = Triage.run(run, gateway, mask(), PriceTable.builtIn(), new TriageOptions(false,
                clock));
        String content = user(gateway.requests().get(0));
        String ui = content.substring(content.indexOf("<target-ui>"), content.indexOf("</target-ui>"));
        assertTrue(ui.length() < 20_100, "bounded: " + ui.length());
        assertTrue(ui.contains("[truncated:"), "the cut is marked");
        assertEquals(TriageLabel.Label.UNKNOWN, result.label().label());
        assertTrue(result.label().oneLine().contains("declined"), result.label().oneLine());
        assertTrue(result.summary().summary().startsWith("The model gave no summary"), result.summary().summary());
    }

    @Test
    void aGatewayFailureIsAFailedTriageAndNoRunDirectoryIsAnError() throws Exception {
        Path run = copy("failed-run");
        TriageResult failed = Triage.run(run, ScriptedGateway.failing(new InferenceException(
                InferenceException.Kind.NO_RECORDING, "no recording for fingerprint 12ab")), mask(),
                PriceTable.builtIn(), new TriageOptions(false, clock));
        assertEquals(TriageResult.Outcome.FAILED, failed.outcome());
        assertEquals(1, failed.exitCode());
        assertTrue(failed.message().contains("NO_RECORDING"), failed.message());
        assertFalse(Files.exists(run.resolve("failure/triage.json")));
        assertEquals(1, Files.readAllLines(run.resolve("triage/inference.jsonl")).size(), "the failure's record");
        assertThrows(NoSuchFileException.class, () -> Triage.run(tmp.resolve("nowhere"),
                ScriptedGateway.sequence(List.of()), mask(), PriceTable.builtIn(), TriageOptions.defaults()));
    }
}
