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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.inference.planner.AuthoringResult;
import ai.skillroy.testjava.inference.planner.Planner;
import ai.skillroy.testjava.inference.planner.PlannerRequest;
import ai.skillroy.testjava.inference.tools.ToolProjection;
import ai.skillroy.testjava.mcp.PlanPolicy;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.ScenarioSchema;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 11 on the planner's own output (14.3, D112): the post-processing of
 * {@code skillroy author} replayed on scenario C's fixture, the envelopes the planner printed when it authored the
 * scenario live, through a substitute {@code claude} (a shell script, as the inference module's planner tests write
 * one, that answers {@code --version} as the live CLI did and prints the fixture's envelopes in turn: the first
 * invocation's, then each repair's on its {@code --resume}). The planner is given what {@code author} gave it: the
 * brief of 14.3, the lending pack, the catalogue of the planner's server ({@code mcp --mutating} without
 * {@code --allow-evaluate}, D98: {@link PlanPolicy#callableCatalogue()} over the desk's primitives, as the headless
 * desk describes them), an owner-only MCP configuration, and a clock at the date of the authoring that the fixture's
 * head names. No key and no CLI are needed, and nothing is skipped.
 *
 * <p>The replay is compared with the fixture, never with the committed scenario, so that a later edit of the
 * scenario by a reviewer does not break it: the YAML the planner renders is the fixture's
 * ({@code author/return-refunds-deposit.authored.yaml}, the planner's YAML before the review) byte for byte, comment
 * head included, and its plan compiles to what that YAML compiles to; the authoring record and the ledger hold the
 * envelopes' session, turns and cost (an envelope reports its session's running total), and the committed authoring
 * record names the same; and the envelope file the planner writes is the fixture, masked the same way (D112).
 */
@DisabledOnOs(OS.WINDOWS)
class AuthorEnvelopeTest {

    /** The substitute {@code claude}: its version from a file, then the n-th envelope on the n-th invocation. */
    static final String SCRIPT = """
            #!/bin/sh
            dir="$(cd "$(dirname "$0")" && pwd)"
            if [ "$1" = "--version" ]; then cat "$dir/version"; exit 0; fi
            n=$(cat "$dir/count" 2>/dev/null || echo 0)
            n=$((n+1))
            echo "$n" > "$dir/count"
            for a in "$@"; do printf '%s\\0' "$a"; done > "$dir/args-$n"
            cat > /dev/null
            if [ -f "$dir/envelope-$n.json" ]; then cat "$dir/envelope-$n.json"; exit 0; fi
            echo "no envelope for invocation $n" >&2
            exit 1
            """;

    /** The head of a rendered scenario, which names the date of the authoring (10.2 item 5). */
    private static final Pattern HEAD = Pattern.compile("^# Authored by the planner \\(skillroy author\\) on (\\S+)"
            + " with ");

    @TempDir
    Path tmp;

    /** The fixture's envelopes, in the order the planner printed them. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> envelopes() throws Exception {
        List<Map<String, Object>> envelopes = new ArrayList<>();
        for (Object envelope : (List<Object>) Json.parse(Files.readString(ScenarioC.envelopeFixture()))) {
            envelopes.add((Map<String, Object>) envelope);
        }
        return envelopes;
    }

    /** The catalogue of the primitives the planner's server offers a scenario on the desk (10.2 step 2, D96). */
    static List<PrimitiveDescriptor> plannersCatalogue() throws Exception {
        List<PrimitiveDescriptor> primitives;
        try (HeadlessDesk desk = HeadlessDesk.launch(Env.SEED_A)) {
            primitives = desk.schema();
        }
        return PlanPolicy.of(primitives, Runs.PACK, ToolProjection.Policy.mutating(false)).callableCatalogue();
    }

    @Test
    void thePlannersEnvelopeReplaysToTheYamlItRenderedAndItsRecords() throws Exception {
        List<Map<String, Object>> envelopes = envelopes();
        Map<String, Object> last = envelopes.get(envelopes.size() - 1);
        String sessionId = String.valueOf(last.get("session_id"));
        long turns = envelopes.stream().mapToLong(e -> ((Number) e.get("num_turns")).longValue()).sum();
        String cost = new BigDecimal(String.valueOf(last.get("total_cost_usd"))).toPlainString();
        String model = String.valueOf(((Map<?, ?>) last.get("modelUsage")).keySet().iterator().next());
        Map<String, Object> committed = Json.parseObject(Files.readString(ScenarioC.authoring()));
        String authored = Files.readString(ScenarioC.authoredYaml(), StandardCharsets.UTF_8);
        Matcher head = HEAD.matcher(authored);
        assertTrue(head.find(), "the fixture's head names the date of the authoring: " + authored);

        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path claude = bin.resolve("claude");
        Files.writeString(claude, SCRIPT);
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(bin.resolve("version"), committed.get("claudeVersion") + "\n");
        for (int i = 0; i < envelopes.size(); i++) {
            Files.writeString(bin.resolve("envelope-" + (i + 1) + ".json"), Json.write(envelopes.get(i)));
        }
        Path mcp = tmp.resolve("mcp.json");
        Files.writeString(mcp, "{\"mcpServers\":{\"skillroy\":{\"command\":\"skillroy\",\"args\":[\"mcp\","
                + "\"--mutating\"]}}}");
        Files.setPosixFilePermissions(mcp, PosixFilePermissions.fromString("rw-------"));
        Path out = tmp.resolve("scenarios");
        List<PrimitiveDescriptor> catalogue = plannersCatalogue();

        AuthoringResult result = Planner.author(new PlannerRequest(ScenarioC.BRIEF, ScenarioC.ID, out, Runs.PACK,
                catalogue, mcp, null, PlannerRequest.DEFAULT_MAX_TURNS, PlannerRequest.DEFAULT_MAX_REPAIRS, null, true,
                claude, Duration.ofMinutes(1), Clock.fixed(Instant.parse(head.group(1)), ZoneOffset.UTC)));

        // What the planner's CLI was given: the first invocation and, for a repair, its --resume.
        assertEquals(String.valueOf(envelopes.size()), Files.readString(bin.resolve("count")).strip(),
                "one invocation per envelope of the fixture");
        List<String> first = args(bin, 1);
        assertEquals("-p", first.get(0));
        assertTrue(first.contains("--bare"), String.valueOf(first));
        assertEquals("json", after(first, "--output-format"));
        assertEquals(mcp.toString(), after(first, "--mcp-config"));
        assertEquals(Planner.ALLOWED_TOOLS, after(first, "--allowedTools"));
        assertEquals("dontAsk", after(first, "--permission-mode"));
        assertEquals(model, after(first, "--model"), "the default model is the one that answered");
        assertNull(after(first, "--resume"));
        assertEquals(withoutSchemaKey(ScenarioSchema.generate(Runs.PACK, catalogue)),
                withoutSchemaKey(Json.parseObject(after(first, "--json-schema"))),
                "the scenario schema over the planner's server's catalogue");
        String prompt = first.get(first.size() - 1);
        assertTrue(prompt.contains("<brief>\n" + ScenarioC.BRIEF + "\n</brief>"), prompt);
        for (int n = 2; n <= envelopes.size(); n++) {
            assertEquals(sessionId, after(args(bin, n), "--resume"), "repair " + (n - 1) + " resumes the session");
        }

        // The YAML is the fixture's, byte for byte; its plan compiles to what the fixture compiles to.
        assertEquals(authored, result.yamlText(), "the YAML the planner's post-processing rendered");
        assertEquals(authored, Files.readString(out.resolve(ScenarioC.YAML), StandardCharsets.UTF_8));
        Plan compiled = PlanCompiler.compile(authored.getBytes(StandardCharsets.UTF_8), ScenarioC.YAML, Runs.PACK);
        Plan written = Plan.read(out.resolve("plan").resolve(ScenarioC.ID + ".plan.json"));
        assertEquals(compiled.toJsonText(), written.toJsonText(), "the plan as compile writes it");
        assertEquals(written.toJsonText(), result.plan().toJsonText());
        assertEquals(ScenarioC.ID, written.scenarioId());

        // The records: the envelopes' session, turns and cost, in the authoring record and in the ledger.
        assertEquals(sessionId, result.sessionId());
        assertEquals(turns, result.turns());
        assertEquals(envelopes.size() - 1, result.repairs());
        assertEquals(0, new BigDecimal(cost).compareTo(result.costUsd()), "the envelopes' running total: "
                + result.costUsd());
        assertFalse(result.overBudget());
        Map<String, Object> record = Json.parseObject(Files.readString(out.resolve(ScenarioC.ID + ".authoring.json")));
        assertEquals(ScenarioC.BRIEF, record.get("brief"));
        assertEquals(model, record.get("model"));
        assertEquals(sessionId, record.get("sessionId"));
        assertEquals(turns, record.get("turns"));
        assertEquals((long) (envelopes.size() - 1), record.get("repairs"));
        assertEquals(cost, record.get("costUsd"));
        assertEquals(committed.get("claudeVersion"), record.get("claudeVersion"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) record.get("checks")).get("compiled"));
        for (String key : List.of("brief", "model", "sessionId", "turns", "repairs", "costUsd", "claudeVersion")) {
            assertEquals(committed.get(key), record.get(key), "the committed authoring record's " + key);
        }
        assertEquals(envelopes.size(), result.ledger().size(), "one ledger entry per invocation");
        CostLedger.Entry entry = result.ledger().get(result.ledger().size() - 1);
        assertEquals(Planner.PROVIDER, entry.provider());
        assertEquals(Planner.PURPOSE, entry.purpose());
        assertEquals(model, entry.model());
        assertEquals(sessionId, entry.requestId());
        assertEquals(ScenarioC.ID, entry.scenarioId());
        Map<?, ?> usage = (Map<?, ?>) last.get("usage");
        assertEquals(((Number) usage.get("input_tokens")).longValue(), entry.inputTokens());
        assertEquals(((Number) usage.get("output_tokens")).longValue(), entry.outputTokens());
        assertEquals(((Number) usage.get("cache_read_input_tokens")).longValue(), entry.cacheReadTokens());
        BigDecimal ledgerCost = BigDecimal.ZERO;
        for (CostLedger.Entry e : result.ledger()) {
            ledgerCost = ledgerCost.add(new BigDecimal(e.costUsd()));
        }
        assertEquals(0, new BigDecimal(cost).compareTo(ledgerCost), "the entries add up to the session's total: "
                + ledgerCost);

        // The envelope file (D112): the fixture itself, masked the same way.
        Path envelopeFile = out.resolve(ScenarioC.ID + ".envelope.json");
        assertEquals(envelopeFile, result.envelope(), "the result names the envelope file");
        assertEquals(Files.readString(ScenarioC.envelopeFixture(), StandardCharsets.UTF_8),
                Files.readString(envelopeFile, StandardCharsets.UTF_8), "the planner's envelopes, masked");
    }

    /**
     * The fixture is what the contract says it is (14.3, D112): the envelopes of one session that ended well, each with
     * a scenario document of the requested id, the last one the authored scenario; and the committed authoring record
     * belongs to it (the brief of 14.3, the session, the turns and the cost, no repair round beyond the envelopes').
     */
    @Test
    void theFixtureIsTheEnvelopeOfTheCommittedAuthoring() throws Exception {
        List<Map<String, Object>> envelopes = envelopes();
        assertFalse(envelopes.isEmpty());
        Set<Object> sessions = new HashSet<>();
        for (Map<String, Object> envelope : envelopes) {
            assertEquals("result", envelope.get("type"));
            assertEquals(Boolean.FALSE, envelope.get("is_error"));
            Map<?, ?> document = (Map<?, ?>) envelope.get("structured_output");
            assertEquals(ScenarioC.ID, document.get("scenario"));
            assertEquals("lending", document.get("pack"));
            sessions.add(envelope.get("session_id"));
        }
        assertEquals(1, sessions.size(), "one planner session");
        Map<String, Object> committed = Json.parseObject(Files.readString(ScenarioC.authoring()));
        Map<String, Object> last = envelopes.get(envelopes.size() - 1);
        assertEquals(ScenarioC.BRIEF, committed.get("brief"), "the brief of 14.3");
        assertEquals(last.get("session_id"), committed.get("sessionId"));
        assertEquals(new BigDecimal(String.valueOf(last.get("total_cost_usd"))).toPlainString(),
                committed.get("costUsd"));
        assertEquals((long) (envelopes.size() - 1), committed.get("repairs"));
        Map<?, ?> checks = (Map<?, ?>) committed.get("checks");
        assertEquals(Map.of("compiled", true, "resolved", true, "ran", true), checks, "compiled, resolved and ran");
        assertTrue(committed.get("review") instanceof Map<?, ?> review && review.containsKey("by")
                && review.containsKey("notes"), "the review section");
    }

    /** No file of the planner's that is committed holds a staff password of either seed as a value. */
    @Test
    void thePlannersCommittedFilesHoldNoPassword() throws Exception {
        Set<String> passwords = new HashSet<>(SampleCredentials.SEED_A.values());
        passwords.addAll(SampleCredentials.SEED_B.values());
        for (Path file : List.of(ScenarioC.envelopeFixture(), ScenarioC.authoredYaml(), ScenarioC.authoring(),
                ScenarioC.yaml(), ScenarioC.plan())) {
            assertTrue(Files.isRegularFile(file), file.toString());
            assertEquals(List.of(), SecretScan.leaks(file, passwords), file + " holds a password as a value");
        }
    }

    /** A JSON Schema without its {@code $schema} key, which the planner's CLI copy drops (10.2 step 2). */
    private static Map<String, Object> withoutSchemaKey(Map<String, Object> schema) {
        Map<String, Object> copy = Json.parseObject(Json.write(schema));
        copy.remove("$schema");
        return new LinkedHashMap<>(copy);
    }

    private static List<String> args(Path bin, int n) throws Exception {
        String raw = Files.readString(bin.resolve("args-" + n), StandardCharsets.UTF_8);
        List<String> list = new ArrayList<>(List.of(raw.split("\0", -1)));
        list.remove(list.size() - 1);
        return list;
    }

    private static String after(List<String> args, String flag) {
        int i = args.indexOf(flag);
        return i < 0 || i + 1 >= args.size() ? null : args.get(i + 1);
    }
}
