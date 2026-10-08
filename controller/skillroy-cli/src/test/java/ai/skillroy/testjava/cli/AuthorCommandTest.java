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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 10.2, 13.4, 15.1 items 11 and 15: {@code skillroy author} through {@link Main#run} on the fake
 * agent with a substitute {@code claude} (a shell script, as the inference module's planner tests write it, that also
 * keeps a copy of the MCP configuration and its permissions while it runs, and prints the envelope the test put
 * beside it): the MCP configuration (owner-only, naming this CLI's server with {@code mcp --mutating}, the
 * connection, the environment and no second model; deleted when the command ends), the written files, the checks
 * ({@code resolve --dry-run} semantics and {@code --run}) recorded in the authoring record, the planner's cost in the
 * invocation's ledger and the cost lines, a planner failure (exit 1), a missing {@code claude} (exit 64), and the usage
 * errors.
 */
@DisabledOnOs(OS.WINDOWS)
class AuthorCommandTest {

    static final String SCRIPT = """
            #!/bin/sh
            dir="$(cd "$(dirname "$0")" && pwd)"
            if [ "$1" = "--version" ]; then echo "2.1.290 (Claude Code)"; exit 0; fi
            n=$(cat "$dir/count" 2>/dev/null || echo 0)
            n=$((n+1))
            echo "$n" > "$dir/count"
            for a in "$@"; do printf '%s\\0' "$a"; done > "$dir/args-$n"
            prev=""
            for a in "$@"; do
              if [ "$prev" = "--mcp-config" ]; then cp "$a" "$dir/mcp-$n.json"; ls -l "$a" > "$dir/mcp-perm-$n"; fi
              prev="$a"
            done
            cat > /dev/null
            if [ -f "$dir/envelope-$n.json" ]; then cat "$dir/envelope-$n.json"; fi
            if [ -f "$dir/exit-$n" ]; then exit "$(cat "$dir/exit-$n")"; fi
            exit 0
            """;

    static final String BRIEF = "The desk pokes its counter once; the counter then reads one.";

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private Path tokenFile;
    private Path bin;
    private Path claude;
    private Path out;

    @BeforeEach
    void setUp() throws Exception {
        agent = new FakeAgent();
        tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        bin = Files.createDirectories(tmp.resolve("bin"));
        claude = bin.resolve("claude");
        Files.writeString(claude, SCRIPT);
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwx------"));
        out = tmp.resolve("module/scenarios");
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome author(String... args) {
        List<String> all = new ArrayList<>(List.of("author"));
        all.addAll(List.of(args));
        all.addAll(List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString()));
        return CliHarness.run(tmp.resolve("discovery"), all.toArray(String[]::new)).never(agent.token);
    }

    private static Map<String, Object> envelope(Object document, boolean isError, String cost) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "result");
        e.put("subtype", "success");
        e.put("is_error", isError);
        e.put("result", isError ? "Not logged in" : "done");
        e.put("session_id", "sess-9");
        e.put("num_turns", 5L);
        e.put("total_cost_usd", Double.parseDouble(cost));
        if (document != null) {
            e.put("structured_output", document);
        }
        return e;
    }

    private static Map<String, Object> document(String id) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("scenario", id);
        doc.put("pack", "clitest");
        doc.put("steps", List.of(Map.of("poke", Map.of())));
        return doc;
    }

    private void answer(int n, Map<String, Object> envelope) throws Exception {
        Files.writeString(bin.resolve("envelope-" + n + ".json"), Json.write(envelope));
    }

    private List<String> args(int n) throws Exception {
        String raw = Files.readString(bin.resolve("args-" + n), StandardCharsets.UTF_8);
        List<String> list = new ArrayList<>(List.of(raw.split("\0", -1)));
        list.remove(list.size() - 1);
        return list;
    }

    @Test
    void aBriefBecomesAScenarioThatIsCheckedAndRunOnce() throws Exception {
        answer(1, envelope(document("poke-once"), false, "0.42"));
        CliHarness.Outcome outcome = author("--brief", BRIEF, "--id", "poke-once", "--out", out.toString(),
                "--claude-command", claude.toString(), "--run", "--env", "lab").expectExit(Exit.OK);
        String text = outcome.out();
        assertTrue(text.startsWith("authored poke-once with claude-opus-5-5 in 5 turns, 0 repairs (session sess-9,"
                + " claude 2.1.290 (Claude Code))"), text);
        assertTrue(text.contains("written: " + out.resolve("poke-once.yaml")), text);
        assertTrue(text.contains("check: it compiles\n  check: it needs no bindings\n"), text);
        assertTrue(text.contains("check: run once live: PASS      poke-once"), text);
        assertTrue(text.contains("Review the YAML and the plan, then run skillroy resolve --env <env> poke-once.yaml"
                + " to approve its bindings."), text);
        assertTrue(text.endsWith("model calls: 1\nmodel cost: $0.42 (budget $2.00)\n"), "the planner's call is in"
                + " the invocation's ledger: " + text);
        assertEquals(1, agent.counter.get(), "the scenario ran once");
        assertTrue(Files.isRegularFile(out.resolve("poke-once.yaml")));
        assertTrue(Files.isRegularFile(out.resolve("plan/poke-once.plan.json")));
        Map<String, Object> authoring = Json.parseObject(Files.readString(out.resolve("poke-once.authoring.json")));
        Map<?, ?> checks = (Map<?, ?>) authoring.get("checks");
        assertEquals(true, checks.get("compiled"));
        assertNull(checks.get("resolved"), "no bindings needed: no bind was made");
        assertEquals(true, checks.get("ran"));

        // The MCP configuration the planner was given: owner-only, this CLI's server, deleted afterwards.
        List<String> args = args(1);
        assertEquals("-p", args.get(0));
        int at = args.indexOf("--mcp-config");
        Path config = Path.of(args.get(at + 1));
        assertFalse(Files.exists(config), "deleted when the command ended");
        assertTrue(Files.readString(bin.resolve("mcp-perm-1")).startsWith("-rw-------"), "owner-only while the"
                + " planner ran: " + Files.readString(bin.resolve("mcp-perm-1")));
        Map<?, ?> server = (Map<?, ?>) ((Map<?, ?>) Json.parseObject(Files.readString(bin.resolve("mcp-1.json")))
                .get("mcpServers")).get("skillroy");
        String command = String.valueOf(server.get("command"));
        List<?> serverArgs = (List<?>) server.get("args");
        assertTrue(command.endsWith("/bin/java"), "in process this JVM's java (bin/skillroy names itself): "
                + command);
        int mcp = serverArgs.indexOf("mcp");
        assertTrue(mcp >= 0, serverArgs.toString());
        assertEquals(List.of("mcp", "--mutating", "--inference", "none"), serverArgs.subList(mcp, mcp + 4),
                "the planner may act; no second model");
        assertEquals("ai.skillroy.testjava.cli.Main", serverArgs.get(mcp - 1));
        assertEquals(Integer.toString(agent.port()), after(serverArgs, "--port"));
        assertEquals(tokenFile.toAbsolutePath().toString(), after(serverArgs, "--token-file"));
        assertEquals(tmp.resolve("discovery").toAbsolutePath().normalize().toString(),
                after(serverArgs, "--discovery-dir"));
        assertEquals("lab", after(serverArgs, "--env"));
        assertFalse(Files.readString(bin.resolve("mcp-1.json")).contains(agent.token), "the token file is named,"
                + " never the token");

        CliHarness.Outcome again = author("--brief", BRIEF, "--id", "poke-once", "--out", out.toString(),
                "--claude-command", claude.toString()).expectExit(Exit.USAGE);
        assertTrue(again.err().contains("scenario poke-once exists"), again.err());
    }

    private static String after(List<?> list, String flag) {
        int i = list.indexOf(flag);
        return i < 0 || i + 1 >= list.size() ? null : String.valueOf(list.get(i + 1));
    }

    @Test
    void aPlannerThatFailsIsExitCode1AndItsCostIsStillCounted() throws Exception {
        answer(1, envelope(null, true, "0.05"));
        CliHarness.Outcome outcome = author("--brief", BRIEF, "--id", "poke-fail", "--out", out.toString(),
                "--claude-command", claude.toString(), "--json").expectExit(Exit.FAILED);
        assertTrue(outcome.err().contains("skillroy author: "), outcome.err());
        assertTrue(outcome.err().contains("model calls: 1\nmodel cost: $0.05 (budget $2.00)"), outcome.err());
        assertFalse(Files.exists(out.resolve("poke-fail.yaml")));
        assertEquals("", outcome.out(), "nothing was written, so nothing is reported on stdout");
    }

    @Test
    void jsonReportsTheFilesTheChecksAndTheCost() throws Exception {
        answer(1, envelope(document("poke-json"), false, "0.30"));
        CliHarness.Outcome outcome = author("--brief-file", Files.writeString(tmp.resolve("brief.txt"), BRIEF)
                        .toString(), "--id", "poke-json", "--out", out.toString(), "--claude-command",
                claude.toString(), "--json", "--model", "sonnet", "--budget-usd", "1.50").expectExit(Exit.OK);
        Map<String, Object> json = Json.parseObject(outcome.out());
        assertEquals("poke-json", json.get("scenarioId"));
        assertEquals("claude-sonnet-5-5", json.get("model"));
        assertEquals(true, ((Map<?, ?>) json.get("checks")).get("compiled"));
        assertNull(((Map<?, ?>) json.get("checks")).get("ran"), "not run without --run");
        assertEquals("0.3", json.get("modelCost"), "the envelope's number, as the planner records it");
        assertEquals("1.50", json.get("budget"));
        assertEquals(1L, json.get("modelCalls"));
        assertEquals("claude-sonnet-5-5", after(args(1), "--model"));
        assertEquals("1.50", after(args(1), "--max-budget-usd"));
    }

    @Test
    void aMissingClaudeAndTheUsageErrorsAreExitCode64() throws Exception {
        CliHarness.Outcome missing = author("--brief", BRIEF, "--id", "x", "--out", out.toString(),
                "--claude-command", tmp.resolve("no-claude").toString()).expectExit(Exit.USAGE);
        assertTrue(missing.err().contains("claude not found"), missing.err());
        assertTrue(author("--id", "x", "--out", out.toString()).expectExit(Exit.USAGE).err()
                .contains("author needs the brief"));
        author("--brief", BRIEF, "--brief-file", "b.txt", "--id", "x", "--out", out.toString())
                .expectExit(Exit.USAGE);
        assertTrue(author("--brief", BRIEF, "--out", out.toString()).expectExit(Exit.USAGE).err()
                .contains("author needs --id"));
        assertTrue(author("--brief", BRIEF, "--id", "x").expectExit(Exit.USAGE).err().contains("author needs --out"));
        author("--brief", BRIEF, "--id", "x", "--out", out.toString(), "--model", "haiku").expectExit(Exit.USAGE);
        author("--brief", BRIEF, "--id", "x", "--out", out.toString(), "--budget-usd", "0").expectExit(Exit.USAGE);
        author("--brief", BRIEF, "--id", "x", "--out", out.toString(), "--max-turns", "0").expectExit(Exit.USAGE);
        author("--brief", BRIEF, "--id", "Not An Id", "--out", out.toString(), "--claude-command",
                claude.toString()).expectExit(Exit.USAGE);
        assertFalse(Files.exists(bin.resolve("count")), "the planner never ran");
    }
}
