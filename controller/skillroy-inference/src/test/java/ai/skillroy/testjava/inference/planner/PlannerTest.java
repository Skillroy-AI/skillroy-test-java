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

package ai.skillroy.testjava.inference.planner;

import ai.skillroy.testjava.inference.fakes.MutableClock;
import ai.skillroy.testjava.inference.resolve.DeskPack;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.ScenarioSchema;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 11: the planner's post-processing with a substitute {@code claude} (a shell script the
 * test writes, which records the arguments of each invocation, its working directory and its stdin, and prints the
 * envelope the test put beside it): the arguments, the YAML with its comment head, the plan, the authoring record, a
 * repair round through {@code --resume}, the repair limit, {@code is_error}, {@code mcp_server_errors}, a failed
 * {@code --resume} that prints plain text, a missing executable, the deadline, and the ledger entry with the envelope's
 * cost.
 */
@DisabledOnOs(OS.WINDOWS)
class PlannerTest {

    static final String SCRIPT = """
            #!/bin/sh
            dir="$(cd "$(dirname "$0")" && pwd)"
            if [ "$1" = "--version" ]; then echo "2.1.290 (Claude Code)"; exit 0; fi
            n=$(cat "$dir/count" 2>/dev/null || echo 0)
            n=$((n+1))
            echo "$n" > "$dir/count"
            for a in "$@"; do printf '%s\\0' "$a"; done > "$dir/args-$n"
            pwd > "$dir/cwd-$n"
            cat > "$dir/stdin-$n"
            echo "a warning on stderr: token=Zq7-Lantern-42x" >&2
            if [ -f "$dir/hang-$n" ]; then exec tail -f /dev/null; fi
            if [ -f "$dir/envelope-$n.json" ]; then cat "$dir/envelope-$n.json"; fi
            if [ -f "$dir/text-$n.txt" ]; then cat "$dir/text-$n.txt"; fi
            if [ -f "$dir/exit-$n" ]; then exit "$(cat "$dir/exit-$n")"; fi
            exit 0
            """;

    static final String BRIEF = "A borrower takes one power tool within the deposit bound; the desk signs on and"
            + " scans the item.";

    @TempDir
    Path tmp;

    private Path bin;
    private Path claude;
    private Path out;
    private Path mcp;
    private final DeskPack pack = new DeskPack();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T10:00:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        bin = Files.createDirectories(tmp.resolve("bin"));
        claude = bin.resolve("claude");
        Files.writeString(claude, SCRIPT);
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwx------"));
        out = tmp.resolve("scenarios");
        mcp = tmp.resolve("mcp.json");
        Files.writeString(mcp, "{\"mcpServers\":{\"skillroy\":{\"command\":\"skillroy\",\"args\":[\"mcp\","
                + "\"--mutating\"]}}}");
        Files.setPosixFilePermissions(mcp, PosixFilePermissions.fromString("rw-------"));
    }

    private PlannerRequest request(String id, int maxRepairs, Duration deadline) {
        return new PlannerRequest(BRIEF, id, out, pack, List.of(), mcp, "claude-opus-5-5", 40, maxRepairs,
                new BigDecimal("2.00"), false, claude, deadline, clock);
    }

    /** A good scenario document, its keys out of the grammar's order on purpose. */
    static Map<String, Object> document(String id) {
        return document(id, "60.00");
    }

    /**
     * A scenario document whose slot has this {@code maxDeposit}. {@link #CYCLE} adds a second slot whose bound is the
     * first's deposit while the first's is the second's: the schema takes it (references are strings), the compiler
     * refuses the cycle.
     */
    static Map<String, Object> document(String id, String maxDeposit) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("steps", List.of(Map.of("signOn", Map.of("staff", "S01")), Map.of("scanItem", Map.of("code",
                "$item.code"))));
        doc.put("scenario", id);
        doc.put("pack", "lending");
        Map<String, Object> resolve = new LinkedHashMap<>();
        resolve.put("item", slot(maxDeposit));
        if (CYCLE.equals(maxDeposit)) {
            resolve.put("other", slot("$item.deposit"));
        }
        doc.put("resolve", resolve);
        return doc;
    }

    /** The bound that makes {@link #document(String, String)} a cycle. */
    static final String CYCLE = "$other.deposit";

    private static Map<String, Object> slot(String maxDeposit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("category", "POWER");
        args.put("maxDeposit", maxDeposit);
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("query", "ItemByCategory");
        slot.put("args", args);
        return slot;
    }

    static Map<String, Object> envelope(Object document, boolean isError, String cost) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "result");
        e.put("subtype", "success");
        e.put("is_error", isError);
        e.put("result", isError ? "Not logged in · Please run /login" : "done");
        e.put("session_id", "sess-41");
        e.put("num_turns", 6L);
        e.put("total_cost_usd", Double.parseDouble(cost));
        e.put("usage", Map.of("input_tokens", 1200L, "output_tokens", 800L, "cache_read_input_tokens", 3000L,
                "cache_creation_input_tokens", 500L));
        e.put("modelUsage", Map.of("claude-opus-5-5", Map.of("costUSD", Double.parseDouble(cost))));
        if (document != null) {
            e.put("structured_output", document);
        }
        return e;
    }

    private void answer(int n, Map<String, Object> envelope) throws Exception {
        Files.writeString(bin.resolve("envelope-" + n + ".json"), Json.write(envelope));
    }

    private List<String> args(int n) throws Exception {
        String raw = Files.readString(bin.resolve("args-" + n), StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(List.of(raw.split("\0", -1)));
        out.remove(out.size() - 1);
        return out;
    }

    private static String after(List<String> args, String flag) {
        int i = args.indexOf(flag);
        return i < 0 || i + 1 >= args.size() ? null : args.get(i + 1);
    }

    @Test
    void theScenarioIsWrittenWithItsHeadPlanAndAuthoringRecord() throws Exception {
        answer(1, envelope(document("desk-lend"), false, "0.42"));
        AuthoringResult result = Planner.author(request("desk-lend", 2, Duration.ofSeconds(30)));

        List<String> args = args(1);
        assertEquals("-p", args.get(0));
        assertTrue(args.contains("--bare"));
        assertEquals("json", after(args, "--output-format"));
        assertEquals(mcp.toString(), after(args, "--mcp-config"));
        assertTrue(args.contains("--strict-mcp-config"));
        assertEquals("", after(args, "--tools"), "no built-in tools");
        assertEquals("mcp__skillroy__*", after(args, "--allowedTools"));
        assertEquals("dontAsk", after(args, "--permission-mode"));
        assertEquals("claude-opus-5-5", after(args, "--model"));
        assertEquals("40", after(args, "--max-turns"));
        assertEquals("2.00", after(args, "--max-budget-usd"));
        assertFalse(args.contains("--resume"));
        assertEquals(Json.write(ScenarioSchema.generate(pack, List.of())), after(args, "--json-schema"),
                "the scenario schema, as its JSON text");
        String prompt = args.get(args.size() - 1);
        assertTrue(prompt.contains("<brief>\n" + BRIEF + "\n</brief>"), prompt);
        assertTrue(prompt.contains("- ItemByCategory"), "the catalogue summary");
        assertTrue(prompt.contains("- signOn: {"), "the verbs with their schemas");
        assertTrue(prompt.contains("never the low-level primitives"));
        assertTrue(Files.readString(bin.resolve("stdin-1")).isEmpty(), "stdin closed");
        Path cwd = Path.of(Files.readString(bin.resolve("cwd-1")).strip());
        assertTrue(cwd.getFileName().toString().startsWith("skillroy-author-"), "a private working directory");
        assertFalse(Files.exists(cwd), "deleted afterwards");

        String yaml = Files.readString(out.resolve("desk-lend.yaml"));
        assertTrue(yaml.startsWith("# Authored by the planner (skillroy author) on 2026-10-08T10:00:00Z with"
                + " claude-opus-5-5; session sess-41; model cost $0.42.\n"), yaml);
        assertTrue(yaml.contains("#   " + BRIEF + "\n"), "the brief in the head");
        assertTrue(yaml.contains("\nscenario: desk-lend\npack: lending\nresolve:\n  item: { query: ItemByCategory,"),
                "the top-level keys in the grammar's order: " + yaml);
        assertTrue(yaml.contains("steps:\n  - { signOn: { staff: S01 } }\n  - { scanItem: { code: $item.code } }"),
                yaml);
        Plan plan = Plan.read(out.resolve("plan/desk-lend.plan.json"));
        assertEquals(PlanCompiler.compile(out.resolve("desk-lend.yaml"), pack).toJsonText(), plan.toJsonText(),
                "the plan as compile writes it");
        assertEquals(plan.toJsonText(), result.plan().toJsonText());
        Map<String, Object> record = Json.parseObject(Files.readString(out.resolve("desk-lend.authoring.json")));
        assertEquals(BRIEF, record.get("brief"));
        assertEquals("claude-opus-5-5", record.get("model"));
        assertEquals("sess-41", record.get("sessionId"));
        assertEquals(6L, record.get("turns"));
        assertEquals(0L, record.get("repairs"));
        assertEquals("0.42", record.get("costUsd"));
        assertEquals("2.1.290 (Claude Code)", record.get("claudeVersion"));
        assertEquals("2026-10-08T10:00:00Z", record.get("date"));
        Map<?, ?> checks = (Map<?, ?>) record.get("checks");
        assertEquals(true, checks.get("compiled"));
        assertTrue(checks.containsKey("resolved") && checks.get("resolved") == null, "the CLI's check to come");

        CostLedger.Entry entry = result.ledger().get(0);
        assertEquals("claude-code", entry.provider());
        assertEquals("plan", entry.purpose());
        assertEquals("0.42", entry.costUsd());
        assertEquals("claude-opus-5-5", entry.model());
        assertEquals("sess-41", entry.requestId());
        assertEquals(1200, entry.inputTokens());
        assertEquals(3000, entry.cacheReadTokens());
        assertEquals("desk-lend", entry.scenarioId());
        assertNotNull(entry.details());
        assertEquals(new BigDecimal("0.42"), result.costUsd());
        assertFalse(result.overBudget());
        assertTrue(result.log().stream().anyMatch(l -> l.contains("token=<redacted>")), "stderr captured, masked: "
                + result.log());
        assertTrue(result.instruction().contains("skillroy resolve --env <env> desk-lend.yaml"));

        result.recordChecks(true, null);
        Map<?, ?> after = (Map<?, ?>) Json.parseObject(Files.readString(out.resolve("desk-lend.authoring.json")))
                .get("checks");
        assertEquals(true, after.get("resolved"));
        assertFalse(after.containsKey("ran"), "no --run");

        AuthoringException exists = assertThrows(AuthoringException.class, () -> Planner.author(
                request("desk-lend", 2, Duration.ofSeconds(30))));
        assertEquals(64, exists.exitCode(), "an id that exists is a usage error");
    }

    @Test
    void aCompileProblemStartsARepairRoundThroughResume() throws Exception {
        answer(1, envelope(document("desk-fix", CYCLE), false, "0.30"));
        answer(2, envelope(document("desk-fix"), false, "0.10"));
        AuthoringResult result = Planner.author(request("desk-fix", 2, Duration.ofSeconds(30)));
        assertEquals(1, result.repairs());
        List<String> repair = args(2);
        assertEquals("sess-41", after(repair, "--resume"), "the planner's session resumed");
        assertTrue(repair.get(repair.size() - 1).contains("reference each other"), "the problems are the prompt: "
                + repair.get(repair.size() - 1));
        assertEquals("1.70", after(repair, "--max-budget-usd"), "what is left of the budget");
        assertEquals(2, result.ledger().size());
        assertEquals(0, new BigDecimal("0.40").compareTo(result.costUsd()), result.costUsd().toPlainString());
        assertEquals(12, result.turns());
        assertEquals(1L, Json.parseObject(Files.readString(out.resolve("desk-fix.authoring.json"))).get("repairs"));
    }

    @Test
    void theRepairsRunOut() throws Exception {
        answer(1, envelope(document("desk-bad", CYCLE), false, "0.30"));
        answer(2, envelope(document("desk-bad", CYCLE), false, "0.10"));
        AuthoringException e = assertThrows(AuthoringException.class, () -> Planner.author(request("desk-bad", 1,
                Duration.ofSeconds(30))));
        assertEquals(1, e.exitCode());
        assertTrue(e.getMessage().contains("after 1 repair round"), e.getMessage());
        assertTrue(e.lastYaml().contains("scenario: desk-bad"), "the last document for the person");
        assertEquals(0, new BigDecimal("0.40").compareTo(e.costUsd()), "what the attempts cost");
        assertFalse(Files.exists(out.resolve("desk-bad.yaml")), "nothing written");
        assertTrue(Files.exists(bin.resolve("args-2")) && !Files.exists(bin.resolve("args-3")), "one repair");
    }

    @Test
    void aSchemaProblemIsRepairedToo() throws Exception {
        Map<String, Object> wrong = document("desk-schema");
        wrong.put("colour", "blue");
        answer(1, envelope(wrong, false, "0.20"));
        answer(2, envelope(document("desk-schema"), false, "0.05"));
        AuthoringResult result = Planner.author(request("desk-schema", 2, Duration.ofSeconds(30)));
        assertEquals(1, result.repairs());
        assertTrue(args(2).get(args(2).size() - 1).contains("colour"), "the validator's problem with its path");
    }

    @Test
    void anErrorEnvelopeOrAnMcpServerErrorFailsTheCommand() throws Exception {
        answer(1, envelope(null, true, "0"));
        Files.writeString(bin.resolve("exit-1"), "1");
        AuthoringException isError = assertThrows(AuthoringException.class, () -> Planner.author(
                request("desk-err", 2, Duration.ofSeconds(30))));
        assertEquals(1, isError.exitCode());
        assertTrue(isError.getMessage().contains("Not logged in"), isError.getMessage());
        assertEquals(1, isError.ledger().size(), "its cost is recorded all the same");

        Map<String, Object> mcpError = envelope(document("desk-mcp"), false, "0.01");
        mcpError.put("mcp_server_errors", List.of(Map.of("server", "skillroy", "error", "spawn failed")));
        answer(2, mcpError);
        AuthoringException mcp = assertThrows(AuthoringException.class, () -> Planner.author(
                request("desk-mcp", 2, Duration.ofSeconds(30))));
        assertTrue(mcp.getMessage().contains("the MCP server failed"), mcp.getMessage());

        answer(3, envelope(null, false, "0.01"));
        AuthoringException none = assertThrows(AuthoringException.class, () -> Planner.author(
                request("desk-none", 2, Duration.ofSeconds(30))));
        assertTrue(none.getMessage().contains("no scenario document"), none.getMessage());

        answer(4, envelope(document("desk-zero"), true, "0.01"));
        AuthoringException zeroExit = assertThrows(AuthoringException.class, () -> Planner.author(
                request("desk-zero", 2, Duration.ofSeconds(30))));
        assertTrue(zeroExit.getMessage().contains("is_error true"), "exit 0 is not enough: "
                + zeroExit.getMessage());
        assertFalse(Files.exists(out.resolve("desk-zero.yaml")));

        Files.writeString(bin.resolve("exit-5"), "2");
        answer(5, envelope(document("desk-exit"), false, "0.01"));
        AuthoringException nonZero = assertThrows(AuthoringException.class, () -> Planner.author(
                request("desk-exit", 2, Duration.ofSeconds(30))));
        assertTrue(nonZero.getMessage().contains("exit 2"), "is_error false is not enough: "
                + nonZero.getMessage());
    }

    @Test
    void aFailedResumePrintsPlainTextAndIsReportedAsSuch() throws Exception {
        answer(1, envelope(document("desk-resume", CYCLE), false, "0.30"));
        Files.writeString(bin.resolve("text-2.txt"), "No conversation found with session ID: sess-41\n");
        Files.writeString(bin.resolve("exit-2"), "1");
        AuthoringException e = assertThrows(AuthoringException.class, () -> Planner.author(
                request("desk-resume", 2, Duration.ofSeconds(30))));
        assertTrue(e.getMessage().startsWith("the repair round's --resume printed no JSON envelope (exit 1): No"
                + " conversation found"), e.getMessage());
    }

    @Test
    void aMissingExecutableOrConfigurationIsAUsageError() {
        PlannerRequest missing = new PlannerRequest(BRIEF, "desk-x", out, pack, List.of(), mcp, null, 40, 2, null,
                false, tmp.resolve("no-claude-here"), null, clock);
        AuthoringException e = assertThrows(AuthoringException.class, () -> Planner.author(missing));
        assertEquals(64, e.exitCode());
        assertTrue(e.getMessage().startsWith("claude not found"), e.getMessage());
        PlannerRequest none = new PlannerRequest(BRIEF, "desk-x", out, pack, List.of(), mcp, null, 40, 2, null,
                false, null, null, clock);
        assertEquals("claude not found on PATH (--claude-command names it)", assertThrows(AuthoringException.class,
                () -> Planner.author(none)).getMessage());
        PlannerRequest noConfig = new PlannerRequest(BRIEF, "desk-x", out, pack, List.of(), tmp.resolve("nope.json"),
                null, 40, 2, null, false, claude, null, clock);
        assertEquals(64, assertThrows(AuthoringException.class, () -> Planner.author(noConfig)).exitCode());
        PlannerRequest blank = new PlannerRequest(" ", "desk-x", out, pack, List.of(), mcp, null, 40, 2, null, false,
                claude, null, clock);
        assertEquals(64, assertThrows(AuthoringException.class, () -> Planner.author(blank)).exitCode());
        assertEquals("claude-opus-5-5", none.model(), "the default model");
        assertEquals(new BigDecimal("2.00"), none.budgetUsd());
        assertNull(none.claudeCommand());
    }

    @Test
    void thePlannerIsStoppedAtItsDeadline() throws Exception {
        Files.writeString(bin.resolve("hang-1"), "");
        AuthoringException e = assertThrows(AuthoringException.class, () -> Planner.author(
                request("desk-slow", 2, Duration.ofMillis(1500))));
        assertEquals(1, e.exitCode());
        assertTrue(e.getMessage().contains("did not finish within its deadline"), e.getMessage());
    }
}
