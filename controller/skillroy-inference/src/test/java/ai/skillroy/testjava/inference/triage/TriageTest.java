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
        return copy(name, name);
    }

    /** {@link #copy(String)} into a directory of another name. */
    private Path copy(String name, String as) throws Exception {
        Path from = Path.of(TriageTest.class.getResource("/triage/" + name).toURI());
        Path to = tmp.resolve(as);
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

    /**
     * Phase 6 security fix S50: the model's label line and look-at items are inline code and its summary and proposal
     * sit in a fence longer than any backtick run they hold, so that their markup is inert in triage.md.
     */
    @Test
    void theModelsTextsAreRenderedAsCodeInTheMarkdown() throws Exception {
        Path run = copy("failed-run");
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("summary", "See ![x](https://e.example/x)\n# Approved\n```\nnot a fence end");
        answer.put("lookAt", List.of("[click](https://e.example/y)"));
        answer.put("proposal", "Open ![y](https://e.example/z)");
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(label("TIMING", 0.4,
                "late ![x](https://e.example/x)"), ScriptedGateway.structured(answer, new Usage("scripted",
                "claude-sonnet-5-5", "claude-sonnet-5-5", "req-summary", 3000, 200, 0, 0, 0, false, "0.0080",
                Duration.ZERO, null))));
        Triage.run(run, gateway, mask(), PriceTable.builtIn(), new TriageOptions(false, clock));
        String md = Files.readString(run.resolve("failure/triage.md"));
        assertTrue(md.contains("): `late ![x](https://e.example/x)`"), md);
        assertTrue(md.contains("- `[click](https://e.example/y)`"), md);
        assertTrue(md.contains("````text\nSee ![x](https://e.example/x)\n# Approved\n```\nnot a fence end\n````\n"),
                "a fence one backtick longer than the longest run: " + md);
        assertTrue(md.contains("```text\nOpen ![y](https://e.example/z)\n```\n"), md);
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

    /**
     * phase5-contracts.md 6.2, 11.2: no row of the run's data reaches a triage request. The slot that resolved last
     * before the bind failed carries a row sample with a person's name (a row sample has no column names, so the
     * by-column rule cannot be applied): its statement, parameters, row count and verifier outcome are sent, no cell.
     */
    @Test
    void aRowSampleOfTheLastResolvedSlotIsNeverSent() throws Exception {
        Path run = tmp.resolve("rows-run");
        Files.createDirectories(run);
        List<String> lines = List.of(
                "{\"type\":\"run-start\",\"t\":0,\"scenarioId\":\"supervisor-return\",\"pack\":\"lending\","
                        + "\"packVersion\":\"0.4.0\"}",
                "{\"type\":\"resolver\",\"t\":10,\"slot\":\"supervisor\",\"resolver\":\"lending.sql.Staff\","
                        + "\"candidates\":1}",
                "{\"type\":\"resolve\",\"t\":12,\"slot\":\"supervisor\",\"query\":\"Staff\",\"args\":{\"code\":\"S03\"},"
                        + "\"strategy\":\"CANONICAL_SQL\",\"resolver\":\"lending.sql.Staff\",\"statements\":[{"
                        + "\"channel\":\"jdbc\",\"source\":\"lending\",\"sql\":\"SELECT s.code, s.name FROM staff s"
                        + " WHERE s.code = ?\",\"params\":[\"S03\"],\"rowCount\":1,\"truncated\":false,"
                        + "\"rowSample\":[[\"S03\",\"Alice Example\"]]}],\"calls\":[{\"primitive\":\"data.query\","
                        + "\"params\":{\"sql\":\"SELECT 1\"},\"value\":{\"columns\":[\"code\",\"name\"],\"rows\":"
                        + "[[\"S03\",\"Bertha Sample\"]]}}],\"verified\":[{\"id\":\"lending.access.check\","
                        + "\"passed\":true,\"reason\":\"staff S03 may refund deposits\"}]}",
                "{\"type\":\"bind\",\"t\":30,\"ok\":false,\"mode\":\"live\",\"failureKind\":\"ENVIRONMENT\","
                        + "\"reason\":null,\"error\":\"the lending source could not be reached for slot keys\"}",
                "{\"type\":\"run-end\",\"t\":32,\"status\":\"ERROR\",\"failureKind\":\"ENVIRONMENT\","
                        + "\"failedStepId\":null,\"message\":\"the lending source could not be reached\"}");
        Files.write(run.resolve("run.jsonl"), lines, StandardCharsets.UTF_8);
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(label("ENVIRONMENT", 0.7, "the source"),
                summary()));
        Triage.run(run, gateway, mask(), PriceTable.builtIn(), new TriageOptions(false, clock));
        for (ModelRequest request : gateway.requests()) {
            String content = user(request);
            assertTrue(content.contains("\"failedSlot\""), content);
            assertTrue(content.contains("SELECT s.code, s.name FROM staff s WHERE s.code = ?"), "the statement");
            assertTrue(content.contains("\"rowCount\": 1"), "its row count");
            assertTrue(content.contains("staff S03 may refund deposits"), "the verifier's outcome");
            assertFalse(content.contains("Alice Example"), "no cell of the row sample: " + content);
            assertFalse(content.contains("Bertha Sample"), "no cell of a rows-shaped value: " + content);
            assertFalse(content.contains("rowSample"), content);
        }
    }

    /**
     * The settings reach the triage (L1 F7): {@code effort.triage} the requests, the model ids the estimate, and
     * {@code logPrompts} the triage's {@code inference.jsonl}.
     */
    @Test
    void theSettingsEffortModelsAndPromptLoggingReachTheTriage() throws Exception {
        Path plain = copy("failed-run");
        Triage.run(plain, ScriptedGateway.sequence(List.of(label("TIMING", 0.4, "late"), summary())), mask(),
                PriceTable.builtIn(), new TriageOptions(false, clock));
        String plainEstimate = (String) Json.parseObject(Files.readString(plain.resolve("failure/triage.json")))
                .get("estimatedUsd");
        assertFalse(Files.readString(plain.resolve("triage/inference.jsonl")).contains("\"messages\""),
                "no prompt without logPrompts");

        Path run = copy("failed-run", "configured");
        ai.skillroy.testjava.scenario.resolve.InferenceSettings settings =
                new ai.skillroy.testjava.scenario.resolve.InferenceSettings("recorded", tmp, false, null,
                        Map.of("opus", "claude-opus-5-5", "sonnet", "claude-opus-5-5", "haiku", "claude-opus-5-5"),
                        Map.of("resolve", "low", "triage", "high", "session", "medium", "summary", "low"), null,
                        null, null, true, false, null, true, null);
        TriageOptions options = TriageOptions.of(settings, false, clock);
        assertTrue(options.logPrompts());
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(label("TIMING", 0.4, "late"), summary()));
        Triage.run(run, gateway, mask(), PriceTable.builtIn(), options);
        for (ModelRequest request : gateway.requests()) {
            assertEquals(ai.skillroy.testjava.inference.spi.Effort.HIGH, request.effort(), "effort.triage");
        }
        String estimate = (String) Json.parseObject(Files.readString(run.resolve("failure/triage.json")))
                .get("estimatedUsd");
        assertTrue(new BigDecimal(estimate).compareTo(new BigDecimal(plainEstimate)) > 0, "the estimate is taken at"
                + " the configured (dearer) model ids: " + estimate + " against " + plainEstimate);
        List<String> records = Files.readAllLines(run.resolve("triage/inference.jsonl"));
        assertTrue(records.stream().allMatch(r -> r.contains("\"messages\"") && r.contains("\"system\"")),
                "logPrompts writes the masked prompts");
        assertTrue(records.stream().noneMatch(r -> r.contains(CREDENTIAL)), "masked");
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

    /** A 1 x 1 PNG, base64, as {@code swing.capture} answers it. */
    static final String PNG_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9aw"
            + "AAAABJRU5ErkJggg==";

    /**
     * Phase 6 security fix, pass 2, P2-L6-S1 (the sceptic's probe T, inverted): a failed step whose call returned a
     * capture, in a run log written before the run log elided images, sends the call's shape and size to the model and
     * never the capture's bytes; the bytes in a {@code fact}-like text field and in an expectation's actual are elided
     * too (P5 §11.2: "no image, and no image's bytes inside a record").
     */
    @Test
    void aFailedStepsCaptureReachesNoRequestAsText() throws Exception {
        Path run = tmp.resolve("capture-run");
        Files.createDirectories(run);
        String log = String.join("\n",
                "{\"type\":\"run-start\",\"t\":0,\"scenarioId\":\"cap\",\"pack\":\"lending\","
                        + "\"packVersion\":\"0.5.0\"}",
                "{\"type\":\"call\",\"t\":60,\"step\":\"s1\",\"index\":1,\"primitive\":\"swing.capture\","
                        + "\"params\":{},\"ok\":true,\"value\":{\"format\":\"png\",\"width\":1,\"height\":1,"
                        + "\"base64\":\"" + PNG_1X1 + "\"},\"events\":0,\"seq\":4,\"durationMs\":12,"
                        + "\"thread\":\"EDT\"}",
                "{\"type\":\"expect\",\"t\":70,\"step\":\"s1\",\"path\":\"app.state.mode\",\"matcher\":\"RETURNED\","
                        + "\"result\":\"failed\",\"resolved\":true,\"actual\":\"data:image/png;base64," + PNG_1X1
                        + "\",\"reason\":\"expected RETURNED\"}",
                "{\"type\":\"run-end\",\"t\":90,\"status\":\"FAIL\",\"failureKind\":\"ASSERTION\","
                        + "\"failedStepId\":\"s1\",\"message\":\"expectation failed\",\"skippedExpectations\":[],"
                        + "\"durationMs\":90}") + "\n";
        Files.writeString(run.resolve("run.jsonl"), log);
        ScriptedGateway gateway = ScriptedGateway.sequence(List.of(label("SCENARIO_DEFECT", 0.5, "x")));
        Triage.run(run, gateway, mask(), PriceTable.builtIn(), new TriageOptions(true, clock));
        String content = user(gateway.requests().get(0));
        assertFalse(content.contains("iVBORw0KGgo"), "no capture's bytes in the request:\n" + content);
        assertTrue(content.contains("\"base64\": \"<png: 70 bytes>\""), "the capture's shape and size stay: "
                + content);
        assertTrue(content.contains("\"width\": 1"), content);
        assertTrue(content.contains("data:image/png;base64,<png: 70 bytes>"), content);
    }

    /**
     * Phase 6 security fix, pass 2, P2-L5-F2: the answering model ids and the summary-failure line (which quotes the
     * provider's error text) are inline code in triage.md, so that a link or an image a provider (or whatever
     * ANTHROPIC_BASE_URL names) put in them is inert.
     */
    @Test
    void theProvidersModelIdsAndErrorTextAreRenderedAsCode() throws Exception {
        Path run = copy("failed-run");
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("label", "TIMING");
        answer.put("confidence", 0.4);
        answer.put("oneLine", "late");
        ModelResponse labelled = ScriptedGateway.structured(answer, new Usage("scripted",
                "claude-haiku-4-5", "claude-haiku-4-5 ![x](https://e.example/x)", "req-label", 2000, 60, 0, 0, 0,
                false, "0.0023", Duration.ZERO, null));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ScriptedGateway gateway = ScriptedGateway.of(request -> {
            if (calls.getAndIncrement() == 0) {
                return labelled;
            }
            throw new InferenceException(InferenceException.Kind.PROVIDER, "bad [click](https://e.example/y)");
        });
        TriageResult result = Triage.run(run, gateway, mask(), PriceTable.builtIn(), new TriageOptions(false,
                clock));
        assertEquals(TriageResult.Outcome.FAILED, result.outcome());
        String md = Files.readString(run.resolve("failure/triage.md"));
        assertTrue(md.contains(" by `claude-haiku-4-5 ![x](https://e.example/x)`; model cost"), md);
        assertTrue(md.contains("\n`the model provider could not be reached for the summary: PROVIDER (bad"
                + " [click](https://e.example/y))`\n"), md);
        String outsideCode = md.replaceAll("`[^`\n]*`", "");
        assertFalse(outsideCode.contains("](https://e.example"), "no live link or image: " + md);
    }
}
