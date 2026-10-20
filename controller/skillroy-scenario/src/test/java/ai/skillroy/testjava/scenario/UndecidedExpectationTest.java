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

import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 8 security fixes of the fix wave's package FC on the side of a verdict (phase8-contracts.md 8.3-8.7, 8.10):
 * S149, a regex or a {@code [json]} that a bound stops decides nothing, under any number of negations, and ends
 * {@code ERROR / ENVIRONMENT} rather than a pass; S151, an event cut at publish fails the {@code events} root's read;
 * S154, a single filter that matches twice holds no {@code exists} form; S150, the window and a captured list are
 * bounded wherever a session or a run writes them. Every test here but the last two of S150's unit cases passes only
 * with the fixes (each was run against {@code f67402c}).
 */
class UndecidedExpectationTest {

    @TempDir
    Path dir;

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    private final Map<String, Object> state = new LinkedHashMap<>(Map.of("name", "IDLE"));
    private final List<Runnable> onPoke = new ArrayList<>();
    private RunOptions options;

    /** A regex that recurses once per character: it overflows the stack on a long text, every time (S24). */
    private static final String RECURSING = "(.|\\s)*manager";

    @BeforeEach
    void toyApplication() {
        options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper()).withBind(TestCredentials.bind())
                .withStepTimeout(Duration.ofMillis(300)).withOutputDir(dir.resolve("run"));
        agent.on("toy.poke", params -> {
            onPoke.forEach(Runnable::run);
            return Map.of();
        });
        agent.on("toy.state", params -> new LinkedHashMap<>(state));
        agent.value("toy.loan", Map.of("total", "7.00", "lines", List.of()));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()));
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
        agent.value("swing.snapshot", Map.of("windows", List.of()));
        agent.value("app.state", Map.of("toy", Map.of("name", "IDLE")));
    }

    private RunResult run(String body) throws ScenarioException {
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    private static void assertOutcome(RunResult result, Status status, FailureKind kind) {
        assertEquals(status, result.status(), result.message());
        assertEquals(kind, result.failureKind(), result.message());
    }

    private static String note(int chars, String tail) {
        StringBuilder text = new StringBuilder();
        while (text.length() < chars) {
            text.append("override ");
        }
        return text.append(tail).toString();
    }

    // ------------------------------------------------------------------------------------------------ S149

    @Test
    void aRegexThatCannotBeDecidedIsNeitherAPassNorAFailureUnderANegation() throws Exception {
        onPoke.add(() -> agent.publish("loan.posted", Map.of("note", note(50_000, " by manager"))));
        RunResult result = run("""
                steps:
                  - call: { primitive: toy.poke }
                  - expect:
                      events: { not: { containsWhere: { topic: loan.posted, payload.note: { matches: "%s" } } } }
                """.formatted(RECURSING.replace("\\", "\\\\")));
        assertOutcome(result, Status.ERROR, FailureKind.ENVIRONMENT);
        assertTrue(result.message().startsWith("expectation could not be decided: events "), result.message());
        assertTrue(result.message().contains("recurses too deeply"), result.message());
        RunResult twice = run("""
                steps:
                  - call: { primitive: toy.poke }
                  - expect:
                      "events[*topic=loan.posted][0].payload.note": { not: { not: { not: { matches: "%s" } } } }
                """.formatted(RECURSING.replace("\\", "\\\\")));
        assertOutcome(twice, Status.ERROR, FailureKind.ENVIRONMENT);
        RunResult anyOf = run("""
                steps:
                  - call: { primitive: toy.poke }
                  - expect:
                      "events[*topic=loan.posted][0].payload.note": { not: { anyOf: [ { matches: "%s" }, nope ] } }
                """.formatted(RECURSING.replace("\\", "\\\\")));
        assertOutcome(anyOf, Status.ERROR, FailureKind.ENVIRONMENT);
    }

    @Test
    void aFilterRegexOrAJsonTextThatABoundStopsLeavesThePathUndecidedNotAbsent() throws Exception {
        state.put("items", List.of(Map.of("note", note(50_000, " by manager"))));
        RunResult single = run("steps:\n  - expect: { \"state.items[note~" + RECURSING.replace("\\", "\\\\")
                + "]\": { exists: false } }\n");
        assertOutcome(single, Status.ERROR, FailureKind.ENVIRONMENT);
        RunResult notExists = run("steps:\n  - expect: { \"state.items[*note~" + RECURSING.replace("\\", "\\\\")
                + "][first]\": { not: { exists: true } } }\n");
        assertOutcome(notExists, Status.ERROR, FailureKind.ENVIRONMENT);
        RunResult none = run("steps:\n  - expect: { \"state.items[*note~" + RECURSING.replace("\\", "\\\\")
                + "]\": { size: 0 } }\n");
        assertOutcome(none, Status.ERROR, FailureKind.ENVIRONMENT);
        state.put("big", "\"" + "x".repeat(SafeRegex.MAX_SUBJECT) + "\"");
        assertOutcome(run("steps:\n  - expect: { \"state.big[json]\": { exists: false } }\n"), Status.ERROR,
                FailureKind.ENVIRONMENT);
        state.put("deep", "[".repeat(250) + "]".repeat(250));
        RunResult deep = run("steps:\n  - expect: { \"state.deep[json]\": { not: { exists: true } } }\n");
        assertOutcome(deep, Status.ERROR, FailureKind.ENVIRONMENT);
        assertTrue(deep.message().contains("nests deeper than 200"), deep.message());
        state.put("text", "qty: 2");
        assertOutcome(run("steps:\n  - expect: { \"state.text[json]\": { exists: false } }\n"), Status.PASS, null);
    }

    @Test
    void aSlotRegexThatDoesNotCompileDecidesNothing() throws Exception {
        state.put("re", "(");
        RunResult result = run("""
                steps:
                  - captureFacts: { slot: b, fields: { re: state.re } }
                  - expect: { state.name: { not: { matches: $b.re } } }
                """);
        assertOutcome(result, Status.ERROR, FailureKind.ENVIRONMENT);
        assertTrue(result.message().contains("bad regex"), result.message());
    }

    @Test
    void anUndecidedMatcherNeverHoldsWhateverTheNegations() throws Exception {
        SafeRegex.Budget spent = SafeRegex.Budget.ofMillis(1);
        Thread.sleep(5);
        Object not = Matchers.normalise(Map.of("not", Map.of("matches", "manager")));
        assertFalse(Matchers.evaluate(not, true, "the manager", null, spent).holds(), "a spent budget");
        Object notNot = Matchers.normalise(Map.of("not", Map.of("not", Map.of("not", Map.of("matches",
                "manager")))));
        assertFalse(Matchers.evaluate(notNot, true, "the manager", null, spent).holds(), "one more not flips nothing");
        String huge = "manager" + "x".repeat(SafeRegex.MAX_SUBJECT);
        assertFalse(Matchers.evaluate(not, true, huge, null).holds(), "a text over 1 Mi");
        Object notAnyOf = Matchers.normalise(Map.of("not", Map.of("anyOf", List.of(Map.of("matches", RECURSING),
                Map.of("matches", "manager")))));
        assertFalse(Matchers.evaluate(notAnyOf, true, note(50_000, " manager"), null).holds());
        Matchers.Outcome undecided = Matchers.evaluate(notAnyOf, true, note(50_000, " none"), null);
        assertFalse(undecided.holds(), "the first alternative undecided, the second not holding: " + undecided);
        Matchers.Outcome decided = Matchers.evaluate(Matchers.normalise(Map.of("anyOf", List.of(Map.of("matches",
                RECURSING), "the manager"))), true, "the manager", null);
        assertTrue(decided.holds(), "an alternative that holds decides an anyOf");
        Object where = Matchers.normalise(Map.of("not", Map.of("containsWhere", Map.of("topic", "x", "note",
                Map.of("matches", RECURSING)))));
        List<Object> elements = List.of(Map.of("topic", "y", "note", note(50_000, "")));
        assertTrue(Matchers.evaluate(where, true, elements, null).holds(), "a field that fails first decides the"
                + " element before its regex runs");
    }

    // ------------------------------------------------------------------------------------------------ S151

    @Test
    void anEventCutAtPublishFailsTheEventsRootsRead() throws Exception {
        onPoke.add(() -> {
            Map<String, Object> cut = new LinkedHashMap<>();
            cut.put("lines", List.of("a", "b"));
            cut.put("note", "<redacted>");
            cut.put("truncated", true);
            agent.publish("loan.posted", cut);
        });
        RunResult result = run("""
                steps:
                  - call: { primitive: toy.poke }
                verify:
                  - { "events[*topic=loan.posted][*payload.note~manager]": { size: 0 } }
                """);
        assertOutcome(result, Status.ERROR, FailureKind.ENVIRONMENT);
        assertTrue(result.message().contains("(loan.posted) was cut at publish (truncated: true)"),
                result.message());
        onPoke.clear();
        onPoke.add(() -> agent.publish("loan.posted", Map.of("note", "override by manager")));
        RunResult control = run("""
                steps:
                  - call: { primitive: toy.poke }
                verify:
                  - { "events[*topic=loan.posted][*payload.note~manager]": { size: 0 } }
                """);
        assertOutcome(control, Status.FAIL, FailureKind.ASSERTION);
    }

    // ------------------------------------------------------------------------------------------------ S154

    @Test
    void aSingleFilterThatMatchesTwiceHoldsNoExistsForm() throws Exception {
        onPoke.add(() -> {
            agent.publish("override.used", Map.of("by", "a"));
            agent.publish("override.used", Map.of("by", "b"));
        });
        for (String matcher : List.of("{ exists: false }", "{ not: { exists: true } }",
                "{ anyOf: [ { exists: false }, 3 ] }")) {
            RunResult result = run("steps:\n  - call: { primitive: toy.poke }\n  - expect: { \"events[topic="
                    + "override.used]\": " + matcher + " }\n");
            assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
            assertTrue(result.message().contains("the path is ambiguous: 2 elements of events match"),
                    result.message());
        }
        onPoke.clear();
        assertOutcome(run("steps:\n  - call: { primitive: toy.poke }\n  - expect: { \"events[topic=override.used]\":"
                + " { exists: false } }\n"), Status.PASS, null);
    }

    // ------------------------------------------------------------------------------------------------ S150

    @Test
    void aCapturedValueLargerThanASlotHoldsIsRefused() throws Exception {
        onPoke.add(() -> {
            for (int i = 0; i < 2000; i++) {
                agent.publish("toy.tick", Map.of("n", (long) i, "text", "tick number " + i));
            }
        });
        RunResult result = run("""
                steps:
                  - call: { primitive: toy.poke }
                  - captureFacts: { slot: b, fields: { all: events, n: { size: events } } }
                """);
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(result.message().startsWith("captureFacts b.all: the value is too large to capture (more than"
                + " 65536 characters of JSON, a list of "), result.message());
        assertTrue(result.message().endsWith("); capture its size or a field"), result.message());
        assertFalse(Files.readString(dir.resolve("run").resolve(RunLog.FILE)).contains("tick number 1999"),
                "no record holds the window");
    }

    @Test
    void aReasonAndAMessageQuoteASubstitutedListCut() throws Exception {
        List<Object> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ids.add("item-" + i);
        }
        state.put("ids", ids);
        state.put("one", "item-7");
        RunResult result = run("""
                steps:
                  - captureFacts: { slot: b, fields: { ids: state.ids } }
                  - expect: { state.one: { not: { in: $b.ids } } }
                """);
        assertOutcome(result, Status.FAIL, FailureKind.ASSERTION);
        assertTrue(result.message().length() < 1200, result.message().length() + ": " + result.message());
        Matchers.Outcome outcome = Matchers.evaluate(Map.of("not", Map.of("in", ids)), true, "item-7", null);
        assertFalse(outcome.holds());
        assertTrue(outcome.reason().length() < 600, outcome.reason());
    }

    @Test
    void aSessionJournalsAWindowCutAndTheExportWritesNoCutLiteral() throws Exception {
        Files.createDirectories(dir.resolve("session"));
        SessionFixture fx = new SessionFixture(dir.resolve("session"));
        try (ExecutorSession session = fx.open()) {
            for (int i = 0; i < 300; i++) {
                fx.agent.publish("toy.tick", Map.of("n", (long) i));
            }
            CheckOutcome check = session.check("events", Map.of("size", 0L), Origin.person("tester"));
            assertFalse(check.held());
            assertEquals(300, ((List<?>) check.actual()).size(), "the caller's outcome keeps the whole value");
            Object observed = session.observe("events[*topic=toy.tick]", Origin.person("tester"));
            assertEquals(300, ((List<?>) observed).size());
            StepOutcome expect = session.execute("expect", Map.of("events[*topic=toy.tick]", Map.of("size", 3L)),
                    Origin.person("tester"));
            assertEquals(Status.FAIL, expect.status(), expect.message());
            assertEquals(200, ((List<?>) expect.detail().get("actual")).size(), "the step's detail cut (D608)");
            assertEquals(true, expect.detail().get("actualTruncated"));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String line : Files.readAllLines(fx.dir.resolve(StepJournal.FILE), StandardCharsets.UTF_8)) {
            entries.add(Json.parseObject(line));
        }
        Map<String, Object> check = entries.stream().filter(e -> "check".equals(e.get("type"))).findFirst()
                .orElseThrow();
        assertEquals(200, ((List<?>) check.get("actual")).size());
        assertEquals(true, check.get("actualTruncated"));
        Map<String, Object> observe = entries.stream().filter(e -> "observe".equals(e.get("type"))).findFirst()
                .orElseThrow();
        assertEquals(200, ((List<?>) observe.get("value")).size());
        assertEquals(true, observe.get("valueTruncated"));
    }
}
