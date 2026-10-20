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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.SessionOpenException;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepJournal;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 9.3 and 9.6: a read tool, a verb tool through the session entry, the arguments as a scenario
 * would give them (nullable optional keys dropped, no {@code confirm}, no {@code _meta}), a failed step as a tool
 * error, the 25000-character cut, an image as a resource link, {@code data_query} through {@code SqlGuard} on an
 * allow-listed source only (its list checked against the source's schema at the start), and the workflow tools: the
 * catalogue, the snapshot, facts, events, {@code skillroy_resolve}, the compile with a problem and with a plan.
 */
class ToolCallTest {

    @TempDir
    Path dir;

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void aReadToolRunsAsOneJournaledCallOfTheSession() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            McpSchema.CallToolResult result = fx.call("desk_state", Map.of());
            assertFalse(result.isError(), McpFixture.text(result));
            Map<String, Object> json = McpFixture.json(result);
            assertEquals("PASS", json.get("status"));
            assertEquals(Map.of("mode", "IDLE", "operator", "100"), json.get("value"));
            assertEquals(List.of("desk.state"), json.get("calls"));
            assertEquals(1, fx.desk.agent.callsTo("desk.state").size());
            assertEquals("sess-1/s1/1", fx.desk.agent.callsTo("desk.state").get(0).options().correlationId());
            StepJournal.StepEntry step = (StepJournal.StepEntry) fx.server.session().journal().entries().get(1);
            assertEquals("CLIENT", step.origin().kind().name(), "a client's origin, which confirms nothing (D158)");
            assertEquals("mcp", step.origin().by());
        }
    }

    @Test
    void aVerbToolRunsThroughTheSessionEntry() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            McpSchema.CallToolResult result = fx.call("desk_enter", Map.of("value", "HAM-1"));
            assertFalse(result.isError(), McpFixture.text(result));
            Map<String, Object> json = McpFixture.json(result);
            assertEquals("PASS", json.get("status"));
            assertEquals("enter", json.get("verb"));
            assertEquals(1L, json.get("executed"));
            FakeAgent.Call sent = fx.desk.agent.callsTo("desk.input").get(0);
            assertEquals(Map.of("source", "Entry", "value", "HAM-1"), sent.params());
            assertTrue(fx.desk.agent.methods().contains("evt.query"), "the prompt check of the step protocol ran");
            List<?> topics = ((List<?>) json.get("events")).stream().map(e -> ((Map<?, ?>) e).get("topic")).toList();
            assertTrue(topics.contains("input.acked") && topics.contains("input.requested"), topics.toString());
            StepJournal.StepEntry step = (StepJournal.StepEntry) fx.server.session().journal().entries().get(1);
            assertEquals("enter", step.verb());
            assertEquals(Map.of("value", "HAM-1"), step.args());
        }
    }

    @Test
    void theArgumentsAreAScenariosNullableOptionalsDroppedNoConfirmNoMeta() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            assertFalse(fx.call("desk_lend", args("item", "HAM-1", "borrower", null, "confirm", true)).isError());
            FakeAgent.Call lend = fx.desk.agent.callsTo("desk.lend").get(0);
            assertEquals(Map.of("item", "HAM-1"), lend.params(), "borrower: null is an absent key, confirm is gone");
            assertFalse(lend.options().confirm(), "the server never confirms on the client's behalf");
            McpSchema.CallToolResult meta = fx.call("desk_lend", args("item", "SAW-2", "_meta", Map.of("confirm",
                    true)));
            assertFalse(meta.isError(), McpFixture.text(meta));
            FakeAgent.Call forwarded = fx.desk.agent.callsTo("desk.lend").get(1);
            assertEquals(Map.of("item", "SAW-2"), forwarded.params(), "a verb that forwards its arguments forwards no"
                    + " _meta: a confirmation cannot ride in it");
            assertFalse(forwarded.options().confirm());
            assertFalse(fx.call("swing_click", args("target", "h12", "button", null, "confirm", true, "_meta",
                    Map.of("confirm", true))).isError());
            FakeAgent.Call click = fx.desk.agent.callsTo("swing.click").get(0);
            assertEquals(Map.of("target", "h12"), click.params());
            assertFalse(click.options().confirm());
            assertTrue(fx.desk.agent.methods().contains("swing.settle"), "a mutating Swing call settles after itself");
        }
    }

    /**
     * Phase 6 security fix S57 (D158), the sceptic's two probe verbs: each compiles to the destructive {@code desk.wipe}
     * with its own {@code confirm} only for some arguments, so the probes at the start offer both. A call that reaches
     * the destructive primitive is refused at call time as a tool error naming it, and nothing reaches the agent or the
     * journal; the same verbs with harmless arguments run. The server's origin is a client's: run past the check (the
     * session entry with the server's origin), the verb's own {@code confirm} is stripped and the agent answers
     * {@code CONFIRM_REQUIRED} (before the fix, the PERSON origin sent {@code confirm == true}).
     */
    @Test
    void aVerbIsCheckedOnTheCallsItCompilesToWithTheClientsArguments() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options(fx.desk.packWithProbeVerbs()).mutating(true).build());
            assertTrue(fx.toolNames().containsAll(List.of("desk_tidy", "desk_stamp")), "no probe finds them: "
                    + fx.toolNames());
            int entries = fx.server.session().journal().entries().size();
            for (Map<String, Object> call : List.of(Map.<String, Object>of("tool", "desk_tidy", "args",
                    Map.of("hard", true)), Map.<String, Object>of("tool", "desk_stamp", "args", Map.of("amount",
                    "1.00")))) {
                @SuppressWarnings("unchecked")
                McpSchema.CallToolResult refused = fx.call((String) call.get("tool"),
                        (Map<String, Object>) call.get("args"));
                String text = McpFixture.text(refused);
                assertTrue(refused.isError(), text);
                assertTrue(text.contains("the destructive primitive desk.wipe"), text);
                assertTrue(text.contains("nothing ran"), text);
            }
            assertTrue(fx.desk.agent.callsTo("desk.wipe").isEmpty(), "nothing reached the agent");
            assertEquals(entries, fx.server.session().journal().entries().size(), "nothing was journaled");
            McpSchema.CallToolResult soft = fx.call("desk_tidy", args("hard", false));
            assertFalse(soft.isError(), McpFixture.text(soft));
            assertEquals(1, fx.desk.agent.callsTo("desk.poke").size());
            McpSchema.CallToolResult bad = fx.call("desk_stamp", args("amount", "lots"));
            assertTrue(bad.isError(), "a step that does not compile is the session's refusal as before");
            assertTrue(McpFixture.text(bad).contains("does not compile"), McpFixture.text(bad));

            StepOutcome past = fx.server.session().execute("tidy", Map.of("hard", true), fx.server.origin());
            assertEquals(Status.ERROR, past.status(), past.message());
            assertTrue(past.message().contains("CONFIRM_REQUIRED"), past.message());
            FakeAgent.Call wipe = fx.desk.agent.callsTo("desk.wipe").get(0);
            assertFalse(wipe.options().confirm(), "a client's origin confirms nothing, the verb's own confirm included");
            assertEquals(Map.of(), wipe.params());
        }
    }

    @Test
    void aStepThatFailsIsAToolErrorWithItsOutcome() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            McpSchema.CallToolResult result = fx.call("desk_enter", Map.of());
            assertTrue(result.isError());
            Map<String, Object> json = McpFixture.json(result);
            assertEquals("ERROR", json.get("status"));
            assertTrue(String.valueOf(json.get("message")).contains("'value' is required"), json.toString());
            assertEquals(0L, json.get("executed"));
            assertFalse(fx.call("desk_poke", Map.of()).isError(), "the session goes on");
        }
    }

    /**
     * phase8-contracts.md 7.1 (ES): a step reply carries {@code warnings} only when the step's calls reported one, so
     * that a reply without warnings is as before; a session step accepts nothing its verb does not pre-accept.
     */
    @Test
    void aStepReplyCarriesWarningsOnlyWhenThereAreSome() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            Map<String, Object> plain = McpFixture.json(fx.call("desk_poke", Map.of()));
            assertFalse(plain.containsKey("warnings"), plain.toString());
            fx.desk.agent.warnings.put("desk.poke", List.of(new ai.skillroy.testjava.protocol.CallWarning(
                    "VALUE_DROPPED", "nothing to poke", null)));
            McpSchema.CallToolResult warned = fx.call("desk_poke", Map.of());
            assertTrue(warned.isError(), "an unaccepted warning fails the step");
            Map<String, Object> json = McpFixture.json(warned);
            assertEquals("FAIL", json.get("status"));
            assertEquals(List.of("s2: poke: desk.poke VALUE_DROPPED (nothing to poke)"), json.get("warnings"));
        }
    }

    @Test
    void aLongAnswerIsCutAt25000Characters() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            String text = McpFixture.text(fx.call("desk_dump", Map.of()));
            Matcher m = Pattern.compile("\n\\[truncated: (\\d+) more]$").matcher(text);
            assertTrue(m.find(), text.substring(text.length() - 100));
            assertEquals(Masking.MAX_RESULT, m.start(), "the text before the marker is exactly 25000 characters");
            assertTrue(Long.parseLong(m.group(1)) > 35_000, "the 60000-character value and its JSON, less the cut");
        }
    }

    /**
     * Phase 6 security fix S58 (D159): without the inference settings' {@code images: true} a capture is written to
     * {@code <out>/mcp/<n>.png} for the person and the link names a text resource that gives its path; no blob of it
     * exists (before the fix the PNG was served as a blob, unmasked and uncut).
     */
    @Test
    void aCaptureIsWrittenAndNamedButNotServedWithoutTheImagesOptIn() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            McpSchema.CallToolResult result = fx.call("swing_capture", args("target", null));
            assertFalse(result.isError(), McpFixture.text(result));
            assertEquals(2, result.content().size(), "the text and the link");
            McpSchema.ResourceLink link = assertInstanceOf(McpSchema.ResourceLink.class, result.content().get(1));
            assertEquals("skillroy://mcp/1.png.txt", link.uri());
            assertEquals("text/plain", link.mimeType());
            Path file = fx.out.resolve("mcp").resolve("1.png");
            byte[] png = Base64.getDecoder().decode(FakeAgent.PNG_1X1);
            assertArrayEquals(png, Files.readAllBytes(file), "written for the person");
            Map<?, ?> value = (Map<?, ?>) McpFixture.json(result).get("value");
            assertEquals("<image: skillroy://mcp/1.png.txt>", value.get("base64"), "no base64 in the text");
            McpSchema.ReadResourceResult read = fx.client.readResource(new McpSchema.ReadResourceRequest(link.uri()));
            McpSchema.TextResourceContents named = assertInstanceOf(McpSchema.TextResourceContents.class,
                    read.contents().get(0));
            assertTrue(named.text().contains(file.toString()), named.text());
            assertTrue(named.text().contains("images: true"), named.text());
            assertFalse(fx.client.listResources().resources().stream().anyMatch(r -> r.uri().endsWith(".png")),
                    "no PNG resource is listed");
            assertThrows(RuntimeException.class, () -> fx.client.readResource(new McpSchema.ReadResourceRequest(
                    "skillroy://mcp/1.png")), "the bytes are not served");
        }
    }

    /** Phase 6 security fix S58 (D159): under {@code images: true} the capture is served as a blob, as before. */
    @Test
    void aCaptureIsServedAsABlobUnderTheImagesOptIn() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options().inference(imagesOn()).build());
            McpSchema.CallToolResult result = fx.call("swing_capture", args("target", null));
            assertFalse(result.isError(), McpFixture.text(result));
            McpSchema.ResourceLink link = assertInstanceOf(McpSchema.ResourceLink.class, result.content().get(1));
            assertEquals("skillroy://mcp/1.png", link.uri());
            assertEquals("image/png", link.mimeType());
            assertEquals("an image returned by swing.capture (step s1)", link.description());
            byte[] png = Base64.getDecoder().decode(FakeAgent.PNG_1X1);
            Map<?, ?> value = (Map<?, ?>) McpFixture.json(result).get("value");
            assertEquals("<image: skillroy://mcp/1.png>", value.get("base64"), "no base64 in the text");
            McpSchema.ReadResourceResult read = fx.client.readResource(new McpSchema.ReadResourceRequest(link.uri()));
            McpSchema.BlobResourceContents blob = assertInstanceOf(McpSchema.BlobResourceContents.class,
                    read.contents().get(0));
            assertEquals("image/png", blob.mimeType());
            assertArrayEquals(png, Base64.getDecoder().decode(blob.blob()));
        }
    }

    /**
     * Phase 6 security fix S58 (D159): a run's {@code failure/*.png} is listed only under {@code images: true}; its text
     * files always.
     */
    @Test
    void aRunsFailureCaptureIsListedOnlyUnderTheImagesOptIn() throws Exception {
        Path run = dir.resolve("a-run");
        Files.createDirectories(run.resolve("failure"));
        Files.write(run.resolve("failure").resolve("capture.png"), Base64.getDecoder().decode(FakeAgent.PNG_1X1));
        Files.writeString(run.resolve("failure").resolve("events.json"), "[]");
        try (McpFixture fx = new McpFixture(dir.resolve("off")).startReadOnly()) {
            assertEquals(List.of("skillroy://run/run-x/failure/events.json"), fx.server.registerRun("run-x", run));
        }
        try (McpFixture fx = new McpFixture(dir.resolve("on"))) {
            fx.start(fx.options().inference(imagesOn()).build());
            assertEquals(List.of("skillroy://run/run-x/failure/capture.png",
                    "skillroy://run/run-x/failure/events.json"), fx.server.registerRun("run-x", run));
        }
    }

    /**
     * Phase 6 security fix S61: {@code skillroy_session_export} refuses an id whose files exist in {@code <out>}, and the
     * first export's files are byte-identical afterwards (before the fix the second export replaced them).
     */
    @Test
    void anExportNeverReplacesAnExistingScenario() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            assertFalse(fx.call("skillroy_resolve", args("slot", "item", "kind", "Item", "args",
                    Map.of("category", "tools"), "strategy", null)).isError());
            assertFalse(fx.call("desk_enter", Map.of("value", "$item.code")).isError());
            McpSchema.CallToolResult first = fx.call("skillroy_session_export", Map.of("scenarioId", "desk-once"));
            assertFalse(first.isError(), McpFixture.text(first));
            Path yaml = fx.out.resolve("desk-once.yaml");
            Path plan = fx.out.resolve("plan").resolve("desk-once.plan.json");
            Path bindings = fx.out.resolve("bindings").resolve("seed-a").resolve("desk-once.bindings.json");
            byte[] yamlBefore = Files.readAllBytes(yaml);
            byte[] planBefore = Files.readAllBytes(plan);
            byte[] bindingsBefore = Files.readAllBytes(bindings);
            assertFalse(fx.call("desk_poke", Map.of()).isError(), "the session goes on and would export otherwise");
            McpSchema.CallToolResult second = fx.call("skillroy_session_export", Map.of("scenarioId", "desk-once"));
            assertTrue(second.isError(), McpFixture.text(second));
            assertTrue(McpFixture.text(second).contains("exists; export under another id"), McpFixture.text(second));
            assertArrayEquals(yamlBefore, Files.readAllBytes(yaml));
            assertArrayEquals(planBefore, Files.readAllBytes(plan));
            assertArrayEquals(bindingsBefore, Files.readAllBytes(bindings));
            Files.delete(yaml);
            McpSchema.CallToolResult planLeft = fx.call("skillroy_session_export", Map.of("scenarioId", "desk-once"));
            assertTrue(planLeft.isError(), "the plan file alone refuses the id: " + McpFixture.text(planLeft));
            assertFalse(Files.exists(yaml), "nothing was written");
            assertFalse(fx.call("skillroy_session_export", Map.of("scenarioId", "desk-twice")).isError());
        }
    }

    /** Inference settings without a gateway but with the images opt-in (D84). */
    private static InferenceSettings imagesOn() {
        return new InferenceSettings(InferenceSettings.GATEWAY_NONE, null, false, null, null, null, null, null, null,
                false, true, null, false, null);
    }

    @Test
    void dataQueryRunsTheGuardedStatementAndKeepsTheAllowListedColumns() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            McpSchema.CallToolResult result = fx.call("data_query", args("source", "desk", "sql",
                    "SELECT code, title FROM item WHERE category = ?", "params", List.of("tools"), "maxRows", null));
            assertFalse(result.isError(), McpFixture.text(result));
            Map<String, Object> json = McpFixture.json(result);
            String sent = String.valueOf(fx.desk.queries().get(0).params().get("sql"));
            assertEquals(json.get("statement"), sent, "the guarded text runs, never the client's: the call is literal"
                    + " (PlannedCall.literalArgs), so nothing is substituted into it after the guard (S55)");
            assertTrue(sent.contains("ORDER BY") && sent.contains("LIMIT 200"), sent);
            Map<?, ?> rows = (Map<?, ?>) json.get("value");
            assertEquals(List.of("CODE", "TITLE"), ((List<?>) rows.get("columns")).stream()
                    .map(c -> ((Map<?, ?>) c).get("name")).toList(), "a column not on the allow-list is dropped");
            assertEquals(List.of(List.of("HAM-1", "Claw hammer")), rows.get("rows"));
            McpSchema.CallToolResult personal = fx.call("data_query", args("source", "desk", "sql",
                    "SELECT borrower_email FROM item"));
            assertTrue(personal.isError());
            assertTrue(McpFixture.text(personal).contains("COLUMN: borrower_email"), "a personal column is not even"
                    + " named here (D99): " + McpFixture.text(personal));
            McpSchema.CallToolResult dml = fx.call("data_query", args("source", "desk", "sql", "DELETE FROM item"));
            assertTrue(dml.isError());
            assertTrue(McpFixture.text(dml).contains("SqlGuard refused"), McpFixture.text(dml));
            McpSchema.CallToolResult other = fx.call("data_query", args("source", "ledger", "sql",
                    "SELECT code FROM item"));
            assertTrue(other.isError());
            assertTrue(McpFixture.text(other).contains("has no SQL allow-list"), McpFixture.text(other));
            assertEquals(1, fx.desk.queries().size(), "nothing refused reached the agent");
            assertEquals(1, fx.server.guardCount(), "one guard per source, closed with the server");
        }
    }

    /**
     * Phase 6 security fix S55 (phase5-contracts.md 9.3): {@code data_query}'s SQL and parameters are literal. A
     * {@code $slot.field} in the SQL, or in a parameter at any depth, is refused as a tool error naming the rule, before
     * the guard, and the agent receives nothing; a reference-shaped text is refused whether or not the slot exists.
     */
    @Test
    void dataQueryRefusesAReferenceInItsSqlOrItsParameters() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            McpSchema.CallToolResult resolved = fx.call("skillroy_resolve", args("slot", "it", "kind", "Item", "args",
                    Map.of("category", "tools"), "strategy", null));
            assertFalse(resolved.isError(), McpFixture.text(resolved));
            int before = fx.desk.queries().size();
            for (Map<String, Object> given : List.of(
                    args("source", "desk", "sql", "SELECT code FROM item WHERE title = '$it.title'"),
                    args("source", "desk", "sql", "SELECT code FROM item WHERE title = ?", "params",
                            List.of("$it.title")),
                    args("source", "desk", "sql", "SELECT code FROM item WHERE title = ?", "params",
                            List.of(Map.of("nested", List.of("a $it.code")))),
                    args("source", "desk", "sql", "SELECT code FROM item WHERE title = '$notaslot'"))) {
                McpSchema.CallToolResult refused = fx.call("data_query", given);
                assertTrue(refused.isError(), given + ": " + McpFixture.text(refused));
                assertTrue(McpFixture.text(refused).contains(ToolCalls.LITERAL_ONLY), McpFixture.text(refused));
                assertFalse(McpFixture.text(refused).contains("Claw hammer"), "never a value");
            }
            assertEquals(before, fx.desk.queries().size(), "nothing refused reached the agent");
        }
    }

    /**
     * Phase 6 security fix S55: a {@code $} that is not a reference ({@code $$}, a {@code $} before a space) runs, and
     * the statement the agent receives is byte-equal to the guarded text (before the fix the session's substitution
     * turned {@code $$} into {@code $} after the guard).
     */
    @Test
    void dataQuerySendsTheGuardedStatementByteForByte() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            for (String sql : List.of("SELECT code FROM item WHERE title = 'a$$b'",
                    "SELECT code FROM item WHERE title = 'US$ 5'")) {
                McpSchema.CallToolResult result = fx.call("data_query", args("source", "desk", "sql", sql, "params",
                        List.of()));
                assertFalse(result.isError(), sql + ": " + McpFixture.text(result));
                List<FakeAgent.Call> queries = fx.desk.queries();
                String sent = String.valueOf(queries.get(queries.size() - 1).params().get("sql"));
                assertEquals(McpFixture.json(result).get("statement"), sent, sql);
                assertTrue(sent.contains(sql.substring(sql.indexOf('\''))), sent);
            }
            StepJournal.StepEntry step = (StepJournal.StepEntry) fx.server.session().journal().entries().get(1);
            assertTrue(String.valueOf(((Map<?, ?>) step.args()).get("sql")).contains("'a$$b'"), "journaled as"
                    + " given: " + step.args());
        }
    }

    /**
     * Phase 6 security fix S55, the sceptic's probe ported: a resolved item whose title a user of the application wrote
     * holds a quote and a set operation over a hidden column; a {@code data_query} naming that field is refused, and the
     * set operation never reaches the agent. Written directly, the guard refuses it.
     */
    @Test
    void aPlantedValueNeverRebuildsTheGuardedStatement() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            McpSchema.CallToolResult resolved = fx.call("skillroy_resolve", args("slot", "it", "kind", "Item", "args",
                    Map.of("category", "planted"), "strategy", null));
            assertFalse(resolved.isError(), McpFixture.text(resolved));
            McpSchema.CallToolResult inSql = fx.call("data_query", args("source", "desk", "sql",
                    "SELECT code FROM item WHERE title = '$it.title'", "params", null, "maxRows", null));
            assertTrue(inSql.isError(), McpFixture.text(inSql));
            McpSchema.CallToolResult inParams = fx.call("data_query", args("source", "desk", "sql",
                    "SELECT code FROM item WHERE title = ?", "params", List.of("$it.title"), "maxRows", null));
            assertTrue(inParams.isError(), McpFixture.text(inParams));
            McpSchema.CallToolResult direct = fx.call("data_query", args("source", "desk", "sql",
                    "SELECT code FROM item UNION SELECT password AS code FROM staff"));
            assertTrue(direct.isError(), McpFixture.text(direct));
            assertTrue(McpFixture.text(direct).contains("SqlGuard refused"), McpFixture.text(direct));
            for (FakeAgent.Call query : fx.desk.queries()) {
                String sql = String.valueOf(query.params().get("sql"));
                assertFalse(sql.contains("UNION") || sql.contains("staff") || sql.contains("password"), sql);
                assertFalse(String.valueOf(query.params().get("params")).contains("UNION"), query.params().toString());
            }
        }
    }

    /**
     * Decision D99: {@code data_query} returns its rows to the client, so a personal column may not be named anywhere
     * (a filter, an order or a function of it would rebuild its values row by row); the other columns still read.
     */
    @Test
    void dataQueryNamesNoPersonalColumnAnywhere() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            for (String sql : List.of("SELECT code FROM item WHERE borrower_email LIKE 'a%'",
                    "SELECT code FROM item WHERE SUBSTRING(borrower_email, 1, 1) = 'a'",
                    "SELECT code FROM item WHERE LENGTH(borrower_email) > 3",
                    "SELECT code FROM item ORDER BY borrower_email",
                    "SELECT code FROM item WHERE borrower_email < 'm'")) {
                McpSchema.CallToolResult refused = fx.call("data_query", args("source", "desk", "sql", sql));
                assertTrue(refused.isError(), sql);
                assertTrue(McpFixture.text(refused).contains("COLUMN: borrower_email"), sql + ": "
                        + McpFixture.text(refused));
            }
            assertTrue(fx.desk.queries().isEmpty(), "nothing refused reached the agent");
            McpSchema.CallToolResult codes = fx.call("data_query", args("source", "desk", "sql",
                    "SELECT code FROM item"));
            assertFalse(codes.isError(), McpFixture.text(codes));
            assertEquals(1, fx.desk.queries().size());
        }
    }

    /**
     * Decisions D99 and D100: a column one of the pack's row masks alters is secret and may not be allow-listed (P2
     * 4's rule), so {@code data_query} never reads it: the server's allow-list check refuses the start, naming the
     * column and the mask. (The copy of the list {@code data_query}'s guard reads hides such a column as well, a second
     * wall for a mask the check's sentinels do not move: {@code PartsTest}.)
     */
    @Test
    void aColumnARowMaskAltersRefusesTheStart() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            SessionOpenException refused = assertThrows(SessionOpenException.class, () -> fx.start(fx.options(
                    fx.desk.packWithRowMasks(List.of((source, column, value) -> "title".equalsIgnoreCase(column)
                            ? "<masked>" : value))).build()));
            assertEquals(FailureKind.ENVIRONMENT, refused.kind());
            assertTrue(refused.getMessage().contains("column item.title, which row mask 1 of the pack alters"),
                    refused.getMessage());
            assertTrue(fx.desk.queries().isEmpty(), "nothing but the check reached the database");
            assertTrue(fx.desk.agent.callsTo("trace.mark").isEmpty(), "the session never opened");
        }
    }

    /**
     * Decision D100: at its start the server checks the allow-list against its source's schema once, before
     * {@code data_query} is offered (the guard's rule 4 trusts {@code otherColumns} to name every column of a table);
     * a list that names a column the table does not have, or leaves out one it has, refuses the start, naming the table
     * and the columns, never a value.
     */
    @Test
    void anAllowListThatDoesNotMatchItsSourcesSchemaRefusesTheStart() throws Exception {
        try (McpFixture fx = new McpFixture(dir.resolve("matching")).startReadOnly()) {
            assertEquals(List.of(Desk.ITEM_SCHEMA_CHECK), fx.desk.agent.callsTo("data.query").stream()
                    .map(c -> c.params().get("sql")).toList(), "the check ran once, at the start");
            assertTrue(fx.toolNames().contains("data_query"), fx.toolNames().toString());
        }
        try (McpFixture fx = new McpFixture(dir.resolve("drifted"))) {
            fx.desk.itemColumns.removeIf(c -> c.name().equals("CATEGORY"));
            fx.desk.itemColumns.add(new Rows.Column("SHELF", "VARCHAR"));
            SessionOpenException refused = assertThrows(SessionOpenException.class,
                    () -> fx.start(fx.options().build()));
            assertEquals(FailureKind.ENVIRONMENT, refused.kind());
            String message = refused.getMessage();
            assertTrue(message.contains("does not match table item"), message);
            assertTrue(message.contains("columns it names neither as readable nor as other columns: [SHELF]"),
                    message);
            assertTrue(message.contains("names the table does not have: [category]"), message);
            assertFalse(message.contains("Claw hammer") || message.contains("HAM-1") || message.contains(Desk.SECRET),
                    "never a value: " + message);
            assertTrue(fx.desk.queries().isEmpty(), "nothing but the check reached the database");
            assertTrue(fx.desk.agent.callsTo("trace.mark").isEmpty(), "the session never opened");
        }
    }

    /**
     * Decision D100: a source the check cannot reach at the start (here the agent does not list it) is not read by
     * {@code data_query}, which says why; the server still starts.
     */
    @Test
    void aSourceTheCheckCannotReachIsNotReadByDataQuery() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.desk.agent.value("data.sources", Map.of("sources", List.of()));
            fx.start(fx.options().build());
            McpSchema.CallToolResult result = fx.call("data_query", args("source", "desk", "sql",
                    "SELECT code FROM item"));
            assertTrue(result.isError(), McpFixture.text(result));
            assertTrue(McpFixture.text(result).contains("could not be checked against the source's schema"),
                    McpFixture.text(result));
            assertTrue(fx.desk.agent.callsTo("data.query").isEmpty(), "nothing reached the database");
            assertTrue(fx.logText().contains("data_query does not read source desk"), fx.logText());
        }
    }

    @Test
    void theCatalogueListsVerbsQueriesOraclesCapabilitiesAndSources() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            Map<String, Object> json = McpFixture.json(fx.call("skillroy_catalogue", Map.of()));
            assertEquals(Map.of("id", "desk", "version", "1.0.0"), json.get("pack"));
            List<?> verbs = (List<?>) json.get("verbs");
            assertEquals(List.of("deposit", "enter", "lend", "poke"), verbs.stream()
                    .map(v -> ((Map<?, ?>) v).get("verb")).toList());
            Map<?, ?> enter = (Map<?, ?>) verbs.get(1);
            assertEquals("desk_enter", enter.get("tool"));
            assertNotNull(enter.get("argsSchema"));
            assertEquals("desk_deposit", ((Map<?, ?>) verbs.get(0)).get("tool"));
            assertNull(((Map<?, ?>) verbs.get(3)).get("argsSchema"), "poke is free-form");
            Map<?, ?> item = (Map<?, ?>) ((List<?>) json.get("queries")).get(0);
            assertEquals("Item", item.get("kind"));
            assertTrue(((Map<?, ?>) ((Map<?, ?>) item.get("args")).get("properties")).containsKey("category"));
            assertEquals("Twice", ((Map<?, ?>) ((List<?>) json.get("oracles")).get(0)).get("fn"));
            assertTrue(((Map<?, ?>) json.get("capabilities")).containsKey("desk.input"));
            assertEquals("desk", ((Map<?, ?>) ((List<?>) json.get("dataQuerySources")).get(0)).get("source"));
            assertEquals(List.of(Map.of("tool", "desk_deposit_primitive", "primitive", "desk.deposit"),
                    Map.of("tool", "desk_lend_primitive", "primitive", "desk.lend"),
                    Map.of("tool", "desk_poke_primitive", "primitive", "desk.poke")), json.get("renamedPrimitives"),
                    "the primitives whose plain tool names verbs take (D92)");
            assertFalse(json.containsKey("hiddenPrimitives"));
        }
    }

    @Test
    void theSnapshotFactsAndEventsAreReads() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            McpSchema.CallToolResult snapshot = fx.call("skillroy_snapshot", args("maxChars", null));
            assertFalse(snapshot.isError());
            assertTrue(McpFixture.text(snapshot).contains("Lend"), McpFixture.text(snapshot));
            Map<String, Object> fact = McpFixture.json(fx.call("skillroy_facts", args("root", "desk", "path",
                    "mode")));
            assertEquals("IDLE", fact.get("value"));
            McpSchema.CallToolResult unknown = fx.call("skillroy_facts", args("root", "nowhere", "path", null));
            assertTrue(unknown.isError());
            assertTrue(McpFixture.text(unknown).contains("no fact root 'nowhere'"), McpFixture.text(unknown));
            Map<String, Object> recent = McpFixture.json(fx.call("skillroy_events", args("sinceSeq", null, "topics",
                    null, "max", 2L)));
            List<?> events = (List<?>) recent.get("events");
            assertEquals(2, events.size(), "the last two");
            long last = fx.desk.agent.lastSeq();
            assertEquals(last, ((Map<?, ?>) events.get(1)).get("seq"));
            Map<String, Object> since = McpFixture.json(fx.call("skillroy_events", args("sinceSeq", 0L, "topics",
                    "desk.*", "max", null)));
            assertEquals(List.of("desk.opened"), ((List<?>) since.get("events")).stream()
                    .map(e -> ((Map<?, ?>) e).get("topic")).toList());
            assertEquals(1, fx.server.session().journal().entries().size(), "reads are not journaled");
        }
    }

    @Test
    void resolveJoinsTheSessionsBindingsAndAVerbUsesThem() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startMutating()) {
            McpSchema.CallToolResult result = fx.call("skillroy_resolve", args("slot", "item", "kind", "Item", "args",
                    Map.of("category", "tools"), "strategy", null));
            assertFalse(result.isError(), McpFixture.text(result));
            Map<String, Object> json = McpFixture.json(result);
            assertEquals("$item", json.get("reference"));
            Map<?, ?> resolution = (Map<?, ?>) json.get("resolution");
            assertEquals(Map.of("code", "HAM-1", "title", "Claw hammer"), resolution.get("value"));
            assertFalse(fx.call("desk_enter", Map.of("value", "$item.code")).isError());
            assertEquals("HAM-1", fx.desk.agent.callsTo("desk.input").get(0).params().get("value"));
            McpSchema.CallToolResult none = fx.call("skillroy_resolve", args("slot", "other", "kind", "Item", "args",
                    Map.of("category", "boats"), "strategy", List.of("sql")));
            assertTrue(none.isError());
            Map<String, Object> failed = McpFixture.json(none);
            assertEquals("RESOLUTION_FAILED", failed.get("failureKind"), failed.toString());
        }
    }

    @Test
    void compileAnswersTheProblemOrThePlansSummary() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            String bad = "scenario: desk-bad\npack: desk\nsteps:\n  - enter: {value: HAM-1}\n  - teleport: {}\n";
            Map<String, Object> problem = McpFixture.json(fx.call("skillroy_scenario_compile", Map.of("yaml", bad)));
            assertEquals(false, problem.get("compiled"));
            Map<?, ?> first = (Map<?, ?>) ((List<?>) problem.get("problems")).get(0);
            assertEquals(5L, first.get("line"), first.toString());
            assertTrue(String.valueOf(first.get("message")).contains("teleport"), first.toString());
            String good = "scenario: desk-lend\npack: desk\nresolve:\n  item: {query: Item, args: {category: tools}}\n"
                    + "steps:\n  - enter: {value: $item.code}\n  - lend: {item: $item.code}\n";
            Map<String, Object> plan = McpFixture.json(fx.call("skillroy_scenario_compile", Map.of("yaml", good)));
            assertEquals(true, plan.get("compiled"), plan.toString());
            assertEquals("desk-lend", plan.get("scenarioId"));
            assertEquals("none", plan.get("inference"));
            assertEquals(List.of("enter", "lend"), ((List<?>) plan.get("steps")).stream()
                    .map(s -> ((Map<?, ?>) s).get("verb")).toList());
            assertEquals("item", ((Map<?, ?>) ((List<?>) plan.get("slots")).get(0)).get("name"));
            assertTrue(String.valueOf(plan.get("planHash")).startsWith("sha256:"));
            assertTrue(fx.desk.agent.callsTo("desk.input").isEmpty(), "a compile runs nothing");
        }
    }

    @Test
    void theRenamedPrimitiveAndTheVerbOfItsPlainNameEachRunTheirOwn() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            assertFalse(fx.call("desk_deposit_primitive", Map.of("code", "HAM-1")).isError());
            assertEquals(Map.of("code", "HAM-1"), fx.desk.agent.callsTo("desk.deposit").get(0).params());
            assertTrue(fx.desk.agent.callsTo("desk.input").isEmpty(), "read-only: the read, nothing else");
        }
        try (McpFixture fx = new McpFixture(dir.resolve("m")).startMutating()) {
            McpSchema.CallToolResult verb = fx.call("desk_deposit", Map.of("amount", "5.00"));
            assertFalse(verb.isError(), McpFixture.text(verb));
            assertEquals("deposit", McpFixture.json(verb).get("verb"));
            assertEquals(Map.of("source", "Key.DEPOSIT", "value", "5.00"), fx.desk.agent.callsTo("desk.input").get(0)
                    .params());
            assertTrue(fx.desk.agent.callsTo("desk.deposit").isEmpty(), "the verb, not the primitive");
            assertFalse(fx.call("desk_deposit_primitive", Map.of("code", "SAW-2")).isError());
            assertEquals(Map.of("code", "SAW-2"), fx.desk.agent.callsTo("desk.deposit").get(0).params());
        }
    }

    @Test
    void theAnswerIsPrettyJson() throws Exception {
        try (McpFixture fx = new McpFixture(dir).startReadOnly()) {
            String text = McpFixture.text(fx.call("desk_state", Map.of()));
            assertTrue(text.startsWith("{\n  \"step\": \"s1\",\n  \"verb\": null,\n  \"status\": \"PASS\""), text);
            assertEquals("s1", Json.parseObject(text).get("step"));
        }
    }
}
