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
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
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
import java.time.Duration;
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
 * connection, the environment and no second model, never {@code --allow-evaluate}; deleted when the command ends),
 * the written files, the checks ({@code resolve --dry-run} semantics and {@code --run}, refused when a call is one the
 * planner's server would not make, D96) recorded in the authoring record, the planner's schema limited to what its
 * server offers, its deadline from {@code timeouts.plannerMs}, the planner's cost in the invocation's ledger and the
 * cost lines, the envelope file (D112: the envelopes the substitute printed, in order and masked, named in the
 * {@code written:} lines, in {@code --json} and, after a failure that received one, on stderr), a planner failure
 * (exit 1), a missing {@code claude} (exit 64), and the usage errors.
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
            if [ -f "$dir/hang-$n" ]; then exec sleep 30; fi
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
        Map<String, Object> printed = envelope(document("poke-once"), false, "0.42");
        answer(1, printed);
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

        // D112: the envelope file, named in the written lines and the instruction, holds the one envelope printed.
        Path envelopeFile = out.resolve("poke-once.envelope.json");
        assertTrue(text.contains("\n           " + out.resolve("poke-once.authoring.json") + "\n           "
                + envelopeFile + "\n"), text);
        assertTrue(text.contains(" poke-once.envelope.json holds the planner's envelopes: delete it before committing"
                + " or keep it as the fixture of the planner's replay."), text);
        assertEquals(List.of(Json.parse(Json.write(printed))), Json.parse(Files.readString(envelopeFile)));

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
        assertFalse(serverArgs.contains(McpCommand.ALLOW_EVALUATE), "never web_evaluate for the planner (D98)");
        assertEquals("planner", after(serverArgs, McpCommand.CLIENT), "the planner's model acts as client planner,"
                + " never a person (phase6 security fix S59, D158)");
        Path serverOut = Path.of(after(serverArgs, "--out"));
        Path work = serverOut.getParent();
        assertEquals("mcp", serverOut.getFileName().toString(), serverOut.toString());
        assertTrue(work.getFileName().toString().startsWith("skillroy-author-"), "the server writes under the"
                + " planner's private working directory (phase6 security fix S60): " + serverOut);
        assertFalse(Files.exists(work), "deleted with the server's journal, run evidence and captures");
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

    /**
     * Phase 6 security fix S60: {@code --keep-work} keeps the planner's private working directory, where its server's
     * output went ({@code --out <work>/mcp}), and prints its path.
     */
    @Test
    void keepWorkKeepsThePlannersWorkingDirectoryAndNamesIt() throws Exception {
        answer(1, envelope(document("kept-work"), false, "0.10"));
        CliHarness.Outcome outcome = author("--brief", BRIEF, "--id", "kept-work", "--out", out.toString(),
                "--claude-command", claude.toString(), "--keep-work").expectExit(Exit.OK);
        Map<?, ?> server = (Map<?, ?>) ((Map<?, ?>) Json.parseObject(Files.readString(bin.resolve("mcp-1.json")))
                .get("mcpServers")).get("skillroy");
        Path work = Path.of(after((List<?>) server.get("args"), "--out")).getParent();
        try {
            assertTrue(Files.isDirectory(work), "kept: " + work);
            assertTrue(outcome.err().contains("the planner's working directory is kept: " + work), outcome.err());
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(work)) {
                for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static String after(List<?> list, String flag) {
        int i = list.indexOf(flag);
        return i < 0 || i + 1 >= list.size() ? null : String.valueOf(list.get(i + 1));
    }

    /** The {@code primitive} enum of the base verb {@code call} in the schema the planner was given. */
    private List<?> plannersCallEnum(int n) throws Exception {
        Map<String, Object> schema = Json.parseObject(after(args(n), "--json-schema"));
        Map<?, ?> defs = (Map<?, ?>) schema.get("$defs");
        for (Object alternative : (List<?>) ((Map<?, ?>) defs.get("step")).get("oneOf")) {
            Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) alternative).get("properties");
            if (properties.get("call") instanceof Map<?, ?> call) {
                return (List<?>) ((Map<?, ?>) ((Map<?, ?>) call.get("properties")).get("primitive")).get("enum");
            }
        }
        throw new AssertionError("no call verb in the planner's schema");
    }

    /**
     * Decision D96: the planner's scenario schema lets the base verb {@code call} name only what the planner's server
     * offers a scenario (never a destructive primitive, the agent's own, {@code web.evaluate} or {@code data.query}).
     */
    @Test
    void thePlannersSchemaLetsCallNameOnlyWhatItsServerOffers() throws Exception {
        agent.schema.add(new PrimitiveDescriptor("clitest.lamp", "Lights the lamp.", null, null, "WORKER", 5_000, null,
                true, false));
        agent.schema.add(new PrimitiveDescriptor("swing.click", "Clicks.", null, null, "EDT", 5_000, null, true,
                false));
        agent.schema.add(new PrimitiveDescriptor("web.evaluate", "Evaluates a script.", null, null, "WORKER", 5_000,
                null, true, false));
        agent.schema.add(new PrimitiveDescriptor("web.waitFlag", "Waits for an expression.", null, null, "WORKER",
                5_000, null, false, false));
        agent.schema.add(new PrimitiveDescriptor("data.query", "Reads rows.", null, null, "WORKER", 5_000, null, false,
                false));
        agent.schema.add(new PrimitiveDescriptor("jvm.info", "The JVM.", null, null, "CALLER", 5_000, null, false,
                false));
        answer(1, envelope(document("schema-check"), false, "0.10"));
        author("--brief", BRIEF, "--id", "schema-check", "--out", out.toString(), "--claude-command",
                claude.toString()).expectExit(Exit.OK);
        assertEquals(List.of("clitest.lamp", "jvm.info", "swing.click", "trace.mark"), plannersCallEnum(1),
                "the domain's and the base's primitives the mutating server offers, and mark's trace.mark; never"
                        + " agent.disable (destructive), test.* (another module), web.evaluate, web.waitFlag or"
                        + " data.query");
    }

    /**
     * Decision D96: {@code author --run} runs the planner's scenario only when every call of it is one the planner's
     * server offers; otherwise the check fails, nothing runs, and the scenario is still written for the reviewer.
     */
    @Test
    void aScenarioWithACallThePlannersServerWouldNotMakeIsNotRun() throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("scenario", "confirming");
        doc.put("pack", "clitest");
        doc.put("steps", List.of(Map.of("call", Map.of("primitive", "trace.mark", "args", Map.of("label", "x",
                "confirm", true)))));
        answer(1, envelope(doc, false, "0.20"));
        CliHarness.Outcome outcome = author("--brief", BRIEF, "--id", "confirming", "--out", out.toString(),
                "--claude-command", claude.toString(), "--run").expectExit(Exit.OK);
        assertTrue(outcome.out().contains("check: run once live: refused, nothing ran"), outcome.out());
        assertTrue(outcome.out().contains("step s1 (call") && outcome.out().contains("'confirm'"), outcome.out());
        assertTrue(agent.rpcRequests("trace.mark").isEmpty(), "nothing ran");
        Map<String, Object> authoring = Json.parseObject(Files.readString(out.resolve("confirming.authoring.json")));
        assertEquals(false, ((Map<?, ?>) authoring.get("checks")).get("ran"), "the --run check failed");
        assertTrue(Files.isRegularFile(out.resolve("confirming.yaml")), "written for the reviewer all the same");
    }

    /** phase5-contracts.md 10.2: the planner's deadline is {@code timeouts.plannerMs} of the inference settings. */
    @Test
    void thePlannersDeadlineIsTheSettingsPlannerMs() {
        InferenceSettings set = new InferenceSettings(InferenceSettings.GATEWAY_NONE, null, false, null, null, null,
                null, null, null, true, false, Map.of(AuthorCommand.TIMEOUT_PLANNER_MS, 1_500L), false, null);
        assertEquals(Duration.ofMillis(1_500), AuthorCommand.plannerDeadline(set));
        assertEquals(Duration.ofMinutes(15), AuthorCommand.plannerDeadline(InferenceSettings.none()),
                "the settings' default");
    }

    /** phase5-contracts.md 10.2: a planner that outruns {@code timeouts.plannerMs} is stopped there (exit 1). */
    @Test
    void aPlannerThatOutrunsTimeoutsPlannerMsIsStopped() throws Exception {
        Files.writeString(bin.resolve("hang-1"), "");
        Path settings = Files.writeString(tmp.resolve("inference.yaml"), "version: 1\ntimeouts: {plannerMs: 1500}\n");
        CliHarness.Outcome outcome = author("--brief", BRIEF, "--id", "slow", "--out", out.toString(),
                "--claude-command", claude.toString(), "--inference-file", settings.toString())
                .expectExit(Exit.FAILED);
        assertTrue(outcome.err().contains("deadline of PT1.5S"), outcome.err());
        assertFalse(Files.exists(out.resolve("slow.yaml")));
        assertFalse(Files.exists(out.resolve("slow.envelope.json")), "no envelope arrived, none is written");
        assertFalse(outcome.err().contains("the planner's envelopes"), outcome.err());
    }

    @Test
    void aPlannerThatFailsIsExitCode1AndItsCostIsStillCounted() throws Exception {
        Map<String, Object> printed = envelope(null, true, "0.05");
        answer(1, printed);
        CliHarness.Outcome outcome = author("--brief", BRIEF, "--id", "poke-fail", "--out", out.toString(),
                "--claude-command", claude.toString(), "--json").expectExit(Exit.FAILED);
        assertTrue(outcome.err().contains("skillroy author: "), outcome.err());
        assertTrue(outcome.err().contains("model calls: 1\nmodel cost: $0.05 (budget $2.00)"), outcome.err());
        assertFalse(Files.exists(out.resolve("poke-fail.yaml")));
        assertEquals("", outcome.out(), "nothing was written, so nothing is reported on stdout");
        // D112: the is_error envelope is kept so that a person can read why, and stderr names its file.
        Path envelopeFile = out.resolve("poke-fail.envelope.json");
        assertTrue(outcome.err().contains("the planner's envelopes: " + envelopeFile + "\n"), outcome.err());
        assertEquals(List.of(Json.parse(Json.write(printed))), Json.parse(Files.readString(envelopeFile)));
    }

    /**
     * D112: with a repair round the envelope file holds both envelopes, the first invocation's and the repair's, in
     * order, through the mask of the planner's log: a secret the mask knows (a {@code key=value} run in a text) is not
     * in it.
     */
    @Test
    void theEnvelopeFileHoldsEveryRoundMasked() throws Exception {
        Map<String, Object> wrong = document("poke-twice");
        wrong.put("colour", "blue");
        Map<String, Object> first = envelope(wrong, false, "0.20");
        first.put("result", "a draft; the desk's note reads password=Cli-Envelope-Pw-6620");
        Map<String, Object> second = envelope(document("poke-twice"), false, "0.25");
        answer(1, first);
        answer(2, second);
        CliHarness.Outcome outcome = author("--brief", BRIEF, "--id", "poke-twice", "--out", out.toString(),
                "--claude-command", claude.toString()).expectExit(Exit.OK);
        assertTrue(outcome.out().startsWith("authored poke-twice with claude-opus-5-5 in 10 turns, 1 repair (session"
                + " sess-9"), outcome.out());
        assertTrue(outcome.out().endsWith("model calls: 2\nmodel cost: $0.25 (budget $2.00)\n"), "the session's last"
                + " running total, not the sum of the totals: " + outcome.out());
        assertEquals("1.80", after(args(2), "--max-budget-usd"), "the repair is given what is left of the budget");
        Path envelopeFile = out.resolve("poke-twice.envelope.json");
        assertTrue(outcome.out().contains("\n           " + envelopeFile + "\n"), outcome.out());
        String text = Files.readString(envelopeFile);
        assertFalse(text.contains("Cli-Envelope-Pw-6620"), text);
        Map<String, Object> masked = new LinkedHashMap<>(first);
        masked.put("result", "a draft; the desk's note reads password=<redacted>");
        assertEquals(List.of(Json.parse(Json.write(masked)), Json.parse(Json.write(second))), Json.parse(text),
                "both rounds, in order, the first masked");
    }

    /**
     * D112 with the environment's credentials: the command gives the planner the credentials' mask, so a password of
     * the credentials file that the model repeats in its envelope reaches neither the envelope file nor the output.
     */
    @Test
    void theEnvelopeFileIsMaskedByTheCredentials() throws Exception {
        String password = "Cli-Desk-Pw-5531-Kx";
        Path credentials = Files.writeString(tmp.resolve("credentials.yaml"), "version: 1\ncredentials:\n"
                + "  staff:\n    S7: { password: '" + password + "' }\n");
        Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rw-------"));
        Map<String, Object> printed = envelope(document("poke-cred"), false, "0.10");
        printed.put("result", "done; the helper signed on with " + password + " at the desk");
        answer(1, printed);
        CliHarness.Outcome outcome = author("--brief", BRIEF, "--id", "poke-cred", "--out", out.toString(),
                "--claude-command", claude.toString(), "--credentials", credentials.toString()).expectExit(Exit.OK);
        String text = Files.readString(out.resolve("poke-cred.envelope.json"));
        assertFalse(text.contains(password), text);
        assertTrue(text.contains("done; the helper signed on with <redacted> at the desk"), text);
        assertFalse((outcome.out() + outcome.err()).contains(password));
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
        assertEquals(out.resolve("poke-json.envelope.json").toString(), json.get("envelope"),
                "the envelope file (D112)");
        assertTrue(Files.isRegularFile(out.resolve("poke-json.envelope.json")));
        assertTrue(String.valueOf(json.get("instruction")).contains("poke-json.envelope.json holds the planner's"
                + " envelopes"), String.valueOf(json.get("instruction")));
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

    /**
     * Phase 6 security fix, pass 2, P2-L6-F4: {@code author} takes the person's {@code --hooks} switch and hands it to
     * the planner's server (and to its own bind and run); without it the server is told {@code auto}, the default; a
     * wrong value is a usage error before the planner starts. Before the fix the option did not exist (64) and the
     * planner's server always ran under {@code auto}.
     */
    @Test
    void theHooksSwitchIsHandedToThePlannersServer() throws Exception {
        answer(1, envelope(document("hooks-off"), false, "0.10"));
        author("--brief", BRIEF, "--id", "hooks-off", "--out", out.toString(), "--claude-command", claude.toString(),
                "--hooks", "off", "--run").expectExit(Exit.OK);
        Map<?, ?> server = (Map<?, ?>) ((Map<?, ?>) Json.parseObject(Files.readString(bin.resolve("mcp-1.json")))
                .get("mcpServers")).get("skillroy");
        assertEquals("off", after((List<?>) server.get("args"), "--hooks"), String.valueOf(server.get("args")));
        // D168 (Phase 6 security fix, pass 2, P2-L8-S2): the planner's server exports nothing, whatever a
        // telemetry.yaml beside the credentials file names (an option wins over the file).
        assertEquals("none", after((List<?>) server.get("args"), "--telemetry"), String.valueOf(server.get("args")));

        answer(2, envelope(document("hooks-default"), false, "0.10"));
        author("--brief", BRIEF, "--id", "hooks-default", "--out", out.toString(), "--claude-command",
                claude.toString()).expectExit(Exit.OK);
        Map<?, ?> byDefault = (Map<?, ?>) ((Map<?, ?>) Json.parseObject(Files.readString(bin.resolve("mcp-2.json")))
                .get("mcpServers")).get("skillroy");
        assertEquals("auto", after((List<?>) byDefault.get("args"), "--hooks"));

        assertTrue(author("--brief", BRIEF, "--id", "x", "--out", out.toString(), "--claude-command",
                claude.toString(), "--hooks", "sometimes").expectExit(Exit.USAGE).err().contains("--hooks"));
    }
}
