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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.PromptDecision;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Malformed input, boundary values and error paths of the loader, the compiler, the executor and the reporter. */
class RobustnessTest {

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper());

    private static ScenarioException loadFails(String yaml) {
        return assertThrows(ScenarioException.class,
                () -> ScenarioLoader.load(yaml.getBytes(StandardCharsets.UTF_8), "x.yaml"));
    }

    private Plan plan(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private RunResult run(String body) throws ScenarioException {
        return new PlanExecutor(pack, agent, options).run(plan(body));
    }

    private void toyInput() {
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of());
            agent.publish("input.requested", Map.of());
            return Map.of("accepted", true);
        });
    }

    // ------------------------------------------------------------------------------------------------ loader

    @ParameterizedTest
    @ValueSource(strings = {
        "~: 1\nscenario: a\nsteps: []\n",
        "scenario: a\nsteps:\n  - ~: {}\n",
        "scenario: a\nsteps:\n  - 1: {}\n",
        "scenario: a\nsteps:\n  - {}\n",
        "scenario: a\nsteps:\n  - [a]\n",
        "scenario: a\nsteps:\n  - just text\n",
        "scenario: a\nsteps:\n  - ? [a, b]\n    : c\n",
        "scenario: 123\nsteps: []\n",
        "scenario: a\nrequires: one\nsteps: []\n",
        "scenario: a\nrequires: [1]\nsteps: []\n",
        "scenario: a\nresolve: { 1x: { fixture: 1 } }\nsteps: []\n",
        "scenario: a\nresolve: { ~: { fixture: 1 } }\nsteps: []\n",
        "scenario: a\ntimeouts: { step: 0 }\nsteps: []\n",
        "scenario: a\ntimeouts: { step: -5 }\nsteps: []\n",
        "scenario: a\ntimeouts: { step: 1.5s }\nsteps: []\n",
        "scenario: a\ntimeouts: { step: 99999999999999999999s }\nsteps: []\n",
        "scenario: a\ntimeouts: { ~: 1 }\nsteps: []\n",
        "scenario: a\non_prompt: { ~: fail }\nsteps: []\n",
        "scenario: a\nsteps: []\nverify: [ [] ]\n",
        "scenario: a\nsteps: []\nverify: [ {} ]\n",
        "scenario: a\n\tsteps: []\n",
        "scenario: a\nsteps: [\n",
        "scenario: a\nsteps: !!binary aGVsbG8=\n",
        "base: &b { x: 1 }\nscenario: a\nsteps: []\n<<: *b\n",
        "[1, 2]\n",
        "just a string\n"})
    void malformedScenariosAreScenarioExceptions(String yaml) {
        ScenarioException e = loadFails(yaml);
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0100", "007", "00", "0x1F", "0b11", "1_000", "1_000.5", "0100.5",
        "12345678901234567890"})
    void numbersYamlWouldReinterpretAreErrors(String number) {
        ScenarioException e = loadFails("scenario: a\nresolve:\n  staff: { fixture: { code: " + number + " } }\n"
                + "steps: []\n");
        assertEquals(3, e.line());
        assertTrue(e.getMessage().contains(number) && e.getMessage().contains("quotes"), e.getMessage());
    }

    @Test
    void plainNumbersAndQuotedCodesAreKept() throws Exception {
        Scenario s = ScenarioLoader.load(("scenario: a\nresolve:\n  v: { fixture: { a: \"0100\", b: 100, c: -17,"
                + " d: 3.50, e: .5, f: 1e3, g: 0, h: +5, i: 9223372036854775807, j: '5000000000017', k: 0.0 } }\n"
                + "steps: []\n").getBytes(StandardCharsets.UTF_8), "a.yaml");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", "0100");
        expected.put("b", 100L);
        expected.put("c", -17L);
        expected.put("d", 3.5);
        expected.put("e", 0.5);
        expected.put("f", 1000.0);
        expected.put("g", 0L);
        expected.put("h", 5L);
        expected.put("i", Long.MAX_VALUE);
        expected.put("j", "5000000000017");
        expected.put("k", 0.0);
        assertEquals(expected, s.slots().get(0).value());
    }

    @Test
    void invalidBytesAndDeepNestingAreScenarioExceptions() {
        byte[] invalidUtf8 = {'s', 'c', 'e', 'n', 'a', 'r', 'i', 'o', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n'};
        assertThrows(ScenarioException.class, () -> ScenarioLoader.load(invalidUtf8, "x.yaml"));
        String deep = "scenario: a\nsteps: []\nverify: " + "[".repeat(150) + "]".repeat(150) + "\n";
        assertTrue(loadFails(deep).getMessage().contains("nesting"), loadFails(deep).getMessage());
        String veryDeep = "scenario: a\nsteps: " + "[".repeat(5000) + "]".repeat(5000) + "\n";
        loadFails(veryDeep);
    }

    // ------------------------------------------------------------------------------------------------ compiler

    /** A pack whose step library misbehaves in the ways a pack under development does. */
    private static final class OddPack implements DomainPack {

        Set<String> verbs = Set.of("odd");
        List<PlannedCall> calls = List.of();

        @Override
        public String id() {
            return "odd";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public StepLibrary steps() {
            return new StepLibrary() {
                @Override
                public Set<String> verbs() {
                    return verbs;
                }

                @Override
                public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) {
                    return calls;
                }
            };
        }

        @Override
        public SyncPolicy syncPolicy() {
            return new SyncPolicy(null, null, null, null, null);
        }

        @Override
        public List<FactProvider> facts() {
            return null;
        }
    }

    private static ScenarioException compileFails(DomainPack pack, String body) {
        return assertThrows(ScenarioException.class, () -> PlanCompiler.compile(
                ("scenario: t\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml", pack));
    }

    @Test
    void packsThatMisbehaveGiveScenarioExceptions() throws Exception {
        OddPack odd = new OddPack();
        odd.verbs = null;
        assertTrue(compileFails(odd, "steps:\n  - odd: {}\n").getMessage().contains("unknown verb 'odd'"));

        odd.verbs = Set.of("odd");
        odd.calls = List.of(new PlannedCall("odd.call", Map.of("x", Double.NaN), Sync.NONE, 0));
        ScenarioException nan = compileFails(odd, "steps:\n  - odd: {}\n");
        assertEquals(3, nan.line());
        assertTrue(nan.getMessage().contains("JSON"), nan.getMessage());

        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        odd.calls = List.of(new PlannedCall("odd.call", cyclic, Sync.NONE, 0));
        compileFails(odd, "steps:\n  - odd: {}\n");

        odd.calls = new ArrayList<>();
        odd.calls.add(null);
        assertTrue(compileFails(odd, "steps:\n  - odd: {}\n").getMessage().contains("null call"));

        odd.calls = null;
        Plan empty = PlanCompiler.compile("scenario: t\nsteps:\n  - odd: {}\n".getBytes(StandardCharsets.UTF_8),
                "t.yaml", odd);
        assertEquals(List.of(), empty.steps().get(0).calls());
    }

    @Test
    void waitsLongerThanTheAgentAllowsAreCompileErrors() throws Exception {
        ScenarioException e = assertThrows(ScenarioException.class,
                () -> plan("steps:\n  - waitFor: { topic: a.b, within: 5m }\n"));
        assertTrue(e.getMessage().contains("waitFor.within"), e.getMessage());
        assertEquals(4, e.line());
        Plan ok = plan("steps:\n  - waitFor: { topic: a.b, within: 119s }\n");
        assertEquals(120_000L, ok.steps().get(0).calls().get(0).timeoutMs());
    }

    @Test
    void handEditedPlansAreValidated() throws Exception {
        String good = plan("steps:\n  - enter: { value: 1 }\n  - expect: { \"state.name\": IDLE }\n").toJsonText();
        assertEquals(good, Plan.parse(good).toJsonText());
        for (String[] edit : new String[][] {
            {"\"scenarioId\": \"t\"", "\"scenarioId\": \"../../etc\""},
            {"\"scenarioId\": \"t\"", "\"scenarioId\": \"\""},
            {"\"stepTimeoutMs\": 10000", "\"stepTimeoutMs\": 0"},
            {"\"timeoutMs\": 10000", "\"timeoutMs\": -1"},
            {"\"line\": 4", "\"line\": 99999999999"},
            {"\"path\": \"state.name\"", "\"path\": \"state..name\""},
            {"\"equals\": \"IDLE\"", "\"equals\": \"IDLE\", \"contains\": \"I\""},
            {"\"equals\": \"IDLE\"", "\"matches\": \"(\""},
            {"\"sync\": \"READY\"", "\"sync\": \"ready\""},
            {"\"primitive\": \"toy.input\"", "\"primitive\": \"\""}}) {
            assertTrue(good.contains(edit[0]), edit[0]);
            String bad = good.replace(edit[0], edit[1]);
            ScenarioException e = assertThrows(ScenarioException.class, () -> Plan.parse(bad), edit[1]);
            assertTrue(e.getMessage().contains("invalid plan"), e.getMessage());
        }
        assertThrows(ScenarioException.class, () -> Plan.parse("[]"));
        assertThrows(ScenarioException.class, () -> Plan.parse("{"));
        assertThrows(ScenarioException.class, () -> Plan.parse("{\"planVersion\": 2}"));
    }

    // ------------------------------------------------------------------------------------------------ executor

    @Test
    void aPromptThatArrivedBeforeAnAnswerCallIsExpected() throws Exception {
        toyInput();
        AtomicInteger asked = new AtomicInteger();
        pack.promptPolicy = (event, a) -> {
            asked.incrementAndGet();
            return PromptDecision.fail("asked");
        };
        // The prompt appears after the first step's prompt check and before the answer: only the answer call's own
        // check can see it, and the answer is what handled it.
        agent.on("toy.prompt.answer", params -> {
            agent.publish("prompt.answered", Map.of());
            agent.publish("input.acked", Map.of());
            agent.publish("input.requested", Map.of());
            return Map.of("answered", true);
        });
        AtomicInteger pokes = new AtomicInteger();
        agent.on("toy.late", params -> Map.of());
        Plan plan = plan("steps:\n  - call: { primitive: toy.late }\n  - enter: { value: 1 }\n"
                + "  - answer: { choice: OK }\n");
        // Publish the prompt from the evt.query that follows s2's sync, after the query has been answered.
        FakeAgentConnection.Handler query = agent.handler("evt.query");
        agent.on("evt.query", params -> {
            Object value = query.handle(params);
            if (pokes.incrementAndGet() == 2) {
                agent.publish("prompt.waiting", Map.of("id", "late"));
            }
            return value;
        });
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(0, asked.get());
    }

    @Test
    void aPromptRaisedByAnAnswerCallIsNotExpected() throws Exception {
        toyInput();
        agent.on("toy.prompt.answer", params -> {
            agent.publish("input.acked", Map.of());
            agent.publish("prompt.waiting", Map.of("id", "second"));
            return Map.of("answered", true);
        });
        RunResult result = run("steps:\n  - answer: { choice: OK }\n  - enter: { value: 1 }\n");
        assertEquals(Status.FAIL, result.status(), result.message());
        assertEquals(FailureKind.ASSERTION, result.failureKind());
        assertEquals("s1", result.failedStepId());
    }

    @Test
    void aRunThatCannotMarkItsStartIsEnvironment() throws Exception {
        toyInput();
        agent.publish("prompt.waiting", Map.of("id", "left over from an earlier scenario"));
        agent.error("trace.mark", ErrorCodes.INTERNAL_ERROR, "bus down");
        RunResult result = run("steps:\n  - enter: { value: 1 }\n");
        assertEquals(Status.ERROR, result.status(), result.message());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertTrue(result.message().contains("trace.mark"), result.message());
        assertTrue(agent.callsTo("toy.input").isEmpty(), "no step runs without a starting sequence number");
    }

    @Test
    void promptsFromBeforeTheRunAreIgnored() throws Exception {
        toyInput();
        agent.publish("prompt.waiting", Map.of("id", "left over from an earlier scenario"));
        assertEquals(Status.PASS, run("steps:\n  - enter: { value: 1 }\n").status());
    }

    @Test
    void aPlanWithAnUnknownFactRootFailsAtOnce() throws Exception {
        Plan compiled = plan("steps:\n  - expect: { \"state.name\": IDLE }\n");
        Plan edited = Plan.parse(compiled.toJsonText().replace("state.name", "nowhere.name"));
        RunResult result = new PlanExecutor(pack, agent, options).run(edited);
        assertEquals(Status.ERROR, result.status(), result.message());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertTrue(result.message().contains("nowhere"), result.message());
        assertEquals(0, clock.sleeps, "nothing to wait for: the root can never appear");
    }

    @Test
    void aPackFailureInsideAStepIsEnvironmentWithAStepResult(@TempDir Path dir) throws Exception {
        agent.on("toy.state", params -> {
            throw new IllegalStateException("fake agent bug");
        });
        options = options.withOutputDir(dir).withStepTimeout(Duration.ofMillis(200));
        RunResult result = run("steps:\n  - mark: a\n  - expect: { \"state.name\": IDLE }\n");
        assertEquals(Status.ERROR, result.status(), result.message());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertEquals("s2", result.failedStepId());
        assertEquals(List.of("s1", "s2"), result.steps().stream().map(StepResult::id).toList());
        assertEquals(Status.ERROR, result.steps().get(1).status());
        assertEquals(1, pack.recovered.get());
    }

    @Test
    void runJsonlStaysJsonWhenAValueCannotBeWritten(@TempDir Path dir) throws Exception {
        pack.facts = List.of(new FactProvider() {
            @Override
            public String root() {
                return "state";
            }

            @Override
            public Object fetch(AgentConnection connection) {
                return Map.of("ratio", Double.NaN);
            }
        });
        options = options.withOutputDir(dir).withStepTimeout(Duration.ofMillis(100)).withCaptureOnFailure(false);
        RunResult result = run("steps:\n  - expect: { \"state.ratio\": 1 }\n");
        assertEquals(Status.FAIL, result.status(), result.message());
        List<String> lines = Files.readAllLines(dir.resolve("run.jsonl"));
        for (String line : lines) {
            Map<String, Object> record = Json.parseObject(line);
            assertNotNull(record.get("type"), line);
        }
        assertTrue(lines.get(lines.size() - 1).contains("run-end"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"type\":\"expect\"") && l.contains("unwritable")),
                String.join("\n", lines));
    }

    @Test
    void anInterruptedRunEndsAsEnvironmentAndKeepsTheInterrupt() throws Exception {
        agent.value("toy.state", Map.of("name", "BUSY"));
        agent.value("toy.unlock", Map.of());
        agent.value("toy.lock", Map.of());
        options = options.withSleeper(duration -> {
            throw new InterruptedException("stop");
        });
        try {
            RunResult result = run("steps:\n  - lock: { name: a }\n  - expect: { \"state.name\": IDLE }\n");
            assertEquals(Status.ERROR, result.status(), result.message());
            assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
            assertEquals("s2", result.failedStepId());
            assertEquals(List.of("s1", "s2"), result.steps().stream().map(StepResult::id).toList());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept for the caller");
            assertEquals(1, agent.callsTo("toy.unlock").size(), "cleanup still ran");
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void evidenceOfAnEarlierRunIsRemoved(@TempDir Path dir) throws Exception {
        toyInput();
        agent.value("toy.state", Map.of("name", "BUSY"));
        agent.value("swing.snapshot", Map.of("windows", List.of()));
        agent.value("app.state", Map.of("toy", Map.of("name", "BUSY")));
        Files.writeString(dir.resolve("notes.txt"), "not ours");
        options = options.withOutputDir(dir).withStepTimeout(Duration.ofMillis(100));
        String body = "resolve:\n  x: { fixture: 1 }\nsteps:\n  - expect: { \"state.name\": IDLE }\n";
        assertEquals(Status.FAIL, run(body).status());
        assertTrue(Files.exists(dir.resolve("failure/snapshot.json")));
        assertTrue(Files.exists(dir.resolve("bindings.json")));

        agent.value("toy.state", Map.of("name", "IDLE"));
        assertEquals(Status.PASS, run(body).status());
        assertFalse(Files.exists(dir.resolve("failure")), "a passing run leaves no failure evidence behind");
        assertTrue(Files.exists(dir.resolve("bindings.json")));

        assertEquals(Status.ERROR, run("requires: [missing]\n" + body).status());
        assertFalse(Files.exists(dir.resolve("bindings.json")), "this run never bound its slots");
        assertEquals("not ours", Files.readString(dir.resolve("notes.txt")));
        assertTrue(Files.readString(dir.resolve("run.jsonl")).contains("PRECONDITION_FAILED"));
    }

    @Test
    void onPromptFailIsCaseInsensitive() throws Exception {
        toyInput();
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of());
            agent.publish("prompt.waiting", Map.of("id", "p"));
            return Map.of();
        });
        pack.promptPolicy = (event, a) -> PromptDecision.ignore("the pack would let it pass");
        assertEquals(Status.PASS, run("steps:\n  - enter: { value: 1 }\n").status(), "absent: the pack's policy");
        // phase8-contracts.md 9.2 (gap 111): a value other than fail or pack no longer selects the pack's policy.
        ScenarioException lenient = assertThrows(ScenarioException.class,
                () -> plan("on_prompt: { unexpected: lenient }\nsteps:\n  - enter: { value: 1 }\n"));
        assertTrue(lenient.getMessage().contains("must be fail or pack"), lenient.getMessage());
        assertEquals(Status.PASS, run("on_prompt: { unexpected: pack }\nsteps:\n  - enter: { value: 1 }\n")
                .status(), "pack: the pack's policy, spelled out");
        assertEquals(Status.FAIL, run("on_prompt: { unexpected: FAIL }\nsteps:\n  - enter: { value: 1 }\n")
                .status());
    }

    @Test
    void hugeExponentsAreTextNotNumbers() {
        assertEquals(null, Values.decimal("1e999999999"));
        assertEquals(null, Values.decimal("1e-999999999"));
        assertFalse(Values.equal("1e999999999", "1e999999998"));
        assertTrue(Values.equal("1e999999999", "1e999999999"));
        Matchers.Outcome outcome = Matchers.evaluate(Map.of("gt", "1e999999999"), true, 5L, null);
        assertFalse(outcome.holds());
        assertTrue(outcome.reason().length() < 500, "the message must not expand the exponent");
        assertTrue(Values.equal("1e3", 1000L));
        assertTrue(Values.equal(2L, "2.00"));
    }

    @Test
    void identifiersWithLeadingZerosAreNotNumbers() {
        assertFalse(Values.equal("0100", "100"), "two staff codes");
        assertFalse(Values.equal("0100", 100L));
        assertFalse(Values.equal("05000000000017", "5000000000017"), "two barcodes");
        assertTrue(Values.equal("0100", "0100"));
        assertTrue(Values.equal("0.50", "0.5"));
        assertTrue(Values.equal("-0.5", -0.5));
        assertTrue(Values.equal("0", 0L));
        assertTrue(Values.equal("3.50", "3.5"));
        Object lines = Json.parse("[{\"code\":\"100\",\"n\":1},{\"code\":\"0100\",\"n\":2}]");
        ExpectationPath.Resolution hit = ExpectationPath.parse("staff[code=0100].n").resolve(lines);
        assertTrue(hit.resolved(), hit.problem());
        assertEquals(2L, hit.value());
        assertFalse(Matchers.evaluate(Map.of("gt", "007"), true, 8L, null).holds(), "\"007\" is not a number");
    }

    @Test
    void aPackThatCannotBeLoadedIsAScenarioException(@TempDir Path dir) throws Exception {
        Path services = Files.createDirectories(dir.resolve("META-INF/services"));
        Files.writeString(services.resolve(DomainPack.class.getName()), "no.such.PackClass\n");
        Scenario scenario = ScenarioLoader.load("scenario: a\nsteps: []\n".getBytes(StandardCharsets.UTF_8), "a.yaml");
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] {dir.toUri().toURL()},
                null)) {
            ScenarioException e = assertThrows(ScenarioException.class,
                    () -> DomainPacks.forScenario(scenario, loader));
            assertEquals("a", e.scenarioId());
            assertTrue(e.getMessage().contains("cannot be loaded"), e.getMessage());
        }
    }

    @Test
    void aCleanupSyncTimeoutIsLoggedAndNeverChangesTheVerdict(@TempDir Path dir) throws Exception {
        agent.value("toy.lock", Map.of());
        agent.value("toy.unlock", Map.of());
        String text = plan("steps:\n  - lock: { name: a }\n  - lock: { name: z }\n").toJsonText();
        int cleanup = text.indexOf("\"cleanup\"");
        // The first cleanup call (it runs last) now waits for an ack event that never comes.
        Plan plan = Plan.parse(text.substring(0, cleanup) + text.substring(cleanup)
                .replaceFirst("\"sync\": \"NONE\"", "\"sync\": \"READY\""));
        options = options.withOutputDir(dir);
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.PASS, result.status(), result.message());
        assertEquals(List.of("z", "a"), agent.callsTo("toy.unlock").stream().map(c -> c.params().get("which"))
                .toList(), "reverse registration order");
        List<Map<String, Object>> cleanups = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if ("cleanup".equals(record.get("type"))) {
                cleanups.add(record);
            }
        }
        assertEquals(List.of(true, false), cleanups.stream().map(r -> r.get("ok")).toList());
        assertTrue(String.valueOf(cleanups.get(1).get("error")).contains("no ack event"));
        assertEquals(0, pack.recovered.get());
    }

    @Test
    void anUnresolvableReferenceInACleanupCallFailsBeforeAnyStepAndSendsNothing() throws Exception {
        agent.value("toy.lock", Map.of());
        agent.value("toy.unlock", Map.of());
        String text = plan("resolve:\n  c: { fixture: { code: 1 } }\nsteps:\n  - lock: { name: a }\n"
                + "  - lock: { name: $c.code }\n").toJsonText();
        int cleanup = text.indexOf("\"cleanup\"");
        Plan plan = Plan.parse(text.substring(0, cleanup) + text.substring(cleanup).replace("$c.code", "$c.gone"));
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(FailureKind.RESOLUTION_FAILED, result.failureKind(), result.message());
        assertTrue(result.message().contains("$c.gone"), result.message());
        assertTrue(agent.callsTo("toy.lock").isEmpty(), "no step ran");
        assertEquals(List.of(), agent.callsTo("toy.unlock"), "Phase 2 (D30, Q11): a failure before the start mark"
                + " sends no cleanup call, not even the one that resolves");
        assertEquals(0, pack.recovered.get(), "and no recover");
    }

    @Test
    void cleanupStopsCallingALostAgent() throws Exception {
        agent.value("toy.lock", Map.of());
        agent.loseOn("toy.unlock");
        RunResult result = run("steps:\n  - lock: { name: a }\n  - lock: { name: b }\n  - lock: { name: c }\n");
        assertEquals(Status.PASS, result.status(), "the steps passed before the agent went away");
        assertEquals(1, agent.callsTo("toy.unlock").size(), "one attempt, then the remaining cleanup is skipped");
    }

    @Test
    void aFailingPromptQueryDoesNotFailTheRun() throws Exception {
        toyInput();
        agent.error("evt.query", ErrorCodes.INTERNAL_ERROR, "ring gone");
        assertEquals(Status.PASS, run("steps:\n  - enter: { value: 1 }\n  - enter: { value: 2 }\n").status());
    }

    @Test
    void aTargetWithoutSwingCannotSettle() throws Exception {
        agent.value("toy.poke", Map.of());
        agent.error("swing.settle", ErrorCodes.METHOD_NOT_FOUND, "no swing module");
        RunResult result = run("steps:\n  - poke: {}\n");
        assertEquals(Status.ERROR, result.status(), result.message());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertTrue(result.message().contains("swing.settle"), result.message());
    }

    @Test
    void expectationsOfOneStepMustHoldTogether() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        // name is IDLE only on odd reads, count is 2 only on even reads: never both in one attempt.
        agent.on("toy.state", params -> {
            int n = reads.incrementAndGet();
            return Map.of("name", n % 2 == 1 ? "IDLE" : "BUSY", "count", n % 2 == 0 ? 2L : 1L);
        });
        options = options.withStepTimeout(Duration.ofMillis(500));
        RunResult result = run("steps:\n  - expect: { \"state.name\": IDLE, \"state.count\": 2 }\n");
        assertEquals(Status.FAIL, result.status());
        assertEquals(FailureKind.ASSERTION, result.failureKind());
        assertEquals(6, reads.get(), "one read per attempt, 500 ms / 100 ms + 1 attempts");
    }

    @Test
    void anExecutorCanRunSeveralPlansInARow() throws Exception {
        toyInput();
        PlanExecutor executor = new PlanExecutor(pack, agent, options);
        Plan plan = plan("steps:\n  - enter: { value: 1 }\n");
        for (int i = 0; i < 3; i++) {
            RunResult result = executor.run(plan);
            assertEquals(Status.PASS, result.status(), result.message());
            assertEquals(1, result.steps().size());
        }
        assertEquals(3, agent.callsTo("toy.input").size());
    }

    // ------------------------------------------------------------------------------------------------ reporter

    @Test
    void reportStaysWellFormedForHostileText() throws Exception {
        String hostile = "a\u0000b\u0001\u001f\ud800 lone \udc00 ]]> <!-- --> &amp; ￾￿ \"'<>&\r\n\tend";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("snapshot", hostile);
        detail.put("actual", hostile);
        RunResult fail = new RunResult("s-" + "x".repeat(3), Status.FAIL, FailureKind.ASSERTION, "s1", hostile, detail,
                List.of(new StepResult("s1", "expect", 3, Status.FAIL, hostile, List.of(hostile), Duration.ZERO)),
                List.of(), Duration.ofMillis(1500));
        RunResult degraded = new RunResult("d", Status.DEGRADED, null, null, null, Map.of(), List.of(),
                List.of(hostile), Duration.ZERO);
        RunResult error = new RunResult("e", Status.ERROR, FailureKind.AGENT_LOST, null, null, Map.of(), List.of(),
                List.of(), null);
        String xml = new JunitXmlReporter().render(hostile, List.of(fail, degraded, error), hostile);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        assertEquals("testsuite", doc.getDocumentElement().getTagName());
        assertEquals("4", doc.getDocumentElement().getAttribute("tests"));
        assertEquals("1", doc.getDocumentElement().getAttribute("failures"));
        assertEquals("2", doc.getDocumentElement().getAttribute("errors"));
        assertEquals("1", doc.getDocumentElement().getAttribute("skipped"));
        assertEquals(4, doc.getElementsByTagName("testcase").getLength());
    }
}
