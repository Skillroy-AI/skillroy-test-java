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

import ai.skillroy.testjava.data.SecretMask;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 8 security fixes of the fix wave's package FC on the side of a secret (phase5-contracts.md 12.2,
 * phase8-contracts.md 8.3, 8.5, 8.7): S173, where the masked-value rule applies (a session always, a run with
 * {@code expectationsOnMaskedValues}) a path is followed on the fact as the mask leaves it, so that a filter, a regex,
 * an index or a count answers the same for a right guess at a secret and a wrong one, in the same time; S174 and S175,
 * a key-named secret inside a text (a JSON text, escaped JSON inside it) never reaches a read or a check; S176, a
 * path's or a size's reason names the kind of the value it stopped at, never a short numeric credential; S177, a
 * {@code size} captured on a secret fails before it is measured; S153, a session's check normalises its matcher before
 * it substitutes. Each test here passes only with the fixes (each was run against {@code f67402c}).
 */
class MaskedFactViewTest {

    private static final Origin MODEL = Origin.model("a-model", "the next action", "req-1");
    private static final String PW = TestCredentials.PW_100;
    private static final String RIGHT = "^pw\\-1";
    private static final String WRONG = "^zz";

    @TempDir
    Path dir;

    private SessionFixture fx;

    @BeforeEach
    void fixture() {
        fx = new SessionFixture(dir);
    }

    // -------------------------------------------------------------------------------- S173, the session's reads

    @Test
    void aFilterAnIndexAndACountAnswerTheSameForARightAndAWrongGuess() throws Exception {
        try (ExecutorSession session = fx.open()) {
            fx.agent.publish("input.received", Map.of("source", "Keyboard", "text", PW));
            Object whole = session.fact("events", null);
            assertFalse(Json.write(whole).contains(PW), "read whole, the root is masked: " + whole);
            assertEquals(session.fact("events", "[payload.text~" + WRONG + "].topic"),
                    session.fact("events", "[payload.text~" + RIGHT + "].topic"), "the projection form");
            assertNull(session.fact("events", "[payload.text~" + RIGHT + "].topic"));
            assertEquals(session.fact("events", "[*payload.text~" + WRONG + "]"),
                    session.fact("events", "[*payload.text~" + RIGHT + "]"), "the list form");
            for (Object matcher : List.of(Map.of("size", 0L), Map.of("size", 1L), Map.of("exists", true),
                    Map.of("exists", false))) {
                CheckOutcome right = session.check("events[*topic=input.received][*payload.text~" + RIGHT + "]",
                        matcher, MODEL);
                CheckOutcome wrong = session.check("events[*topic=input.received][*payload.text~" + WRONG + "]",
                        matcher, MODEL);
                assertEquals(wrong.held(), right.held(), matcher + ": " + right.reason() + " / " + wrong.reason());
                assertEquals(wrong.reason().replace(WRONG, RIGHT), right.reason(), String.valueOf(matcher));
                assertFalse(right.held(), "a filter over a secret holds no matcher: " + right.reason());
            }
            CheckOutcome index = session.check("events[*topic=input.received][*payload.text~" + RIGHT + "][0].seq",
                    Map.of("exists", true), MODEL);
            assertFalse(index.held());
            assertTrue(index.reason().contains("holds a secret"), index.reason());
            CheckOutcome direct = session.check("events[*topic=input.received][0].payload.text",
                    Map.of("matches", "^<red"), MODEL);
            assertFalse(direct.held(), "the mask's marker counts as a secret: " + direct.reason());
            assertFalse(session.check("events[*topic=input.received][*payload.text=<redacted>]", Map.of("size", 1L),
                    MODEL).held(), "a filter literal equal to the marker matches nothing");
        }
    }

    @Test
    void aRegexWrittenToBacktrackOnARightPrefixRunsOnNoSecret() throws Exception {
        try (ExecutorSession session = fx.open()) {
            fx.agent.publish("audit.line", Map.of("text", PW + " ok"));
            String slow = "(?:.*".repeat(30) + ")".repeat(30) + "z";
            long t0 = System.nanoTime();
            CheckOutcome right = session.check("events[*payload.text~" + RIGHT + slow + "]", Map.of("size", 0L),
                    MODEL);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            CheckOutcome wrong = session.check("events[*payload.text~" + WRONG + slow + "]", Map.of("size", 0L),
                    MODEL);
            assertEquals(wrong.held(), right.held());
            assertFalse(right.reason().contains("ran longer"), "no budget spent on the secret: " + right.reason());
            assertTrue(ms < 500, ms + " ms: the regex never ran over the secret");
        }
    }

    @Test
    void aPackRootsSingleFilterIsNoOracleEither() throws Exception {
        fx.state.put("users", List.of(Map.of("code", PW, "name", "a")));
        try (ExecutorSession session = fx.open()) {
            assertNull(session.fact("state", "users[code~" + RIGHT + "].name"));
            assertEquals(session.check("state.users[code~" + WRONG + "].name", Map.of("exists", false), MODEL)
                    .held(), session.check("state.users[code~" + RIGHT + "].name", Map.of("exists", false), MODEL)
                    .held());
        }
    }

    // ------------------------------------------------------------------------------- S173, S177, a client's run

    @Test
    void aClientsRunGivesTheSameVerdictAndCaptureForBothGuesses() throws Exception {
        ToyPack pack = new ToyPack();
        FakeAgentConnection agent = new FakeAgentConnection();
        TestClock clock = new TestClock();
        Map<String, Object> state = new LinkedHashMap<>(Map.of("name", "IDLE", "pin", PW));
        agent.on("toy.poke", params -> {
            agent.publish("audit.line", Map.of("text", "stored " + PW));
            return Map.of();
        });
        agent.on("toy.state", params -> new LinkedHashMap<>(state));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()));
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
        agent.value("swing.snapshot", Map.of("windows", List.of()));
        agent.value("app.state", Map.of("toy", Map.of("name", "IDLE")));
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withBind(TestCredentials.bind()).withStepTimeout(Duration.ofMillis(300))
                .withExpectationsOnMaskedValues(true);
        List<String> outcomes = new ArrayList<>();
        for (String guess : List.of(RIGHT, WRONG)) {
            String body = """
                    steps:
                      - call: { primitive: toy.poke }
                      - captureFacts: { slot: o, fields: { n: { size: "events[*topic=audit.line][*payload.text~%s]" } } }
                    """.formatted(guess.replace("\\", "\\\\"));
            Path out = dir.resolve("run-" + outcomes.size());
            RunResult result = new PlanExecutor(pack, agent, options.withOutputDir(out)).run(PlanCompiler.compile((
                    "scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml", pack));
            assertEquals(Status.FAIL, result.status(), result.message());
            outcomes.add(result.message().replace(guess, "<guess>"));
            assertFalse(Files.readString(out.resolve(RunLog.FILE)).contains("\"n\":"), "no count captured");
        }
        assertEquals(outcomes.get(1), outcomes.get(0), "the same verdict and message for both guesses");
        assertTrue(outcomes.get(0).contains(Matchers.HOLDS_A_SECRET), outcomes.get(0));
        RunResult size = new PlanExecutor(pack, agent, options.withOutputDir(dir.resolve("run-size"))).run(
                PlanCompiler.compile(("scenario: t\npack: toy\nsteps:\n  - captureFacts: { slot: o, fields: { len:"
                        + " { size: state.pin } } }\n").getBytes(StandardCharsets.UTF_8), "t.yaml", pack));
        assertEquals(Status.FAIL, size.status(), "S177: a size of a secret is never captured: " + size.message());
        assertEquals("captureFacts o.len: " + Matchers.HOLDS_A_SECRET, size.message());
        assertEquals(SecretMask.REDACTED, size.detail().get("actual"));
    }

    // ------------------------------------------------------------------------------------------- S174, S175

    @Test
    void aKeyNamedSecretInsideAJsonTextNeverReachesARead() throws Exception {
        String unknown = "opensesame-77";
        fx.state.put("operator", Map.of("password", unknown, "name", "desk"));
        try (ExecutorSession session = fx.open()) {
            fx.agent.publish("api.response", Map.of("status", 200L, "body", "{\"user\":\"desk\",\"password\":\""
                    + unknown + "\"}"));
            fx.agent.publish("web.tap", Map.of("detail", "{\"body\":\"{\\\"token\\\":\\\"" + unknown + "\\\"}\"}"));
            assertEquals(SecretMask.REDACTED, session.fact("events", "[topic=api.response].payload.body[json]"
                    + ".password"));
            CheckOutcome check = session.check("events[topic=api.response].payload.body[json].password",
                    Map.of("matches", "^open"), MODEL);
            assertFalse(check.held(), check.reason());
            assertEquals(Matchers.HOLDS_A_SECRET, check.reason());
            assertFalse(Json.write(session.fact("events", null)).contains(unknown), "S175: escaped JSON, read whole");
            assertEquals(SecretMask.REDACTED, session.fact("events", "[topic=web.tap].payload.detail[json].body[json]"
                    + ".token"));
            assertFalse(session.check("events[topic=api.response].payload.body", Map.of("matches",
                    "\"password\":\"o"), MODEL).held(), "no regex oracle on the containing text");
            assertEquals(SecretMask.REDACTED, session.fact("state", "operator.password"), "a pack root's key");
            assertEquals("desk", session.fact("state", "operator.name"));
        }
    }

    // ---------------------------------------------------------------------------------------------------- S176

    @Test
    void aStopReasonNamesTheKindNeverAShortNumericCredential() throws Exception {
        Map<String, Object> staff = new LinkedHashMap<>();
        staff.put("400", Map.of("password", "4711"));
        Map<String, Object> document = Map.of("version", 1L, "credentials", Map.of("staff", staff));
        fx.state.put("staff", List.of(Map.of("code", "400", "pin", "4711", "pinNo", 4711L)));
        try (ExecutorSession session = ExecutorSession.open(fx.pack, fx.agent, fx.options().withBind(
                BindOptions.defaults().withCredentials(TestCredentials.of(document)).withEnvId("seed-a")))) {
            for (String path : List.of("state.staff[0].pin[json].x", "state.staff[0].pinNo.x",
                    "state.staff[0].pinNo[0]", "state.staff[0].pinNo[json]")) {
                CheckOutcome check = session.check(path, Map.of("exists", true), MODEL);
                assertFalse(check.held());
                assertFalse(check.reason().matches("(?s).*(^|[^0-9])4711([^0-9]|$).*"), path + ": " + check.reason());
            }
        }
        ToyPack pack = new ToyPack();
        FakeAgentConnection agent = new FakeAgentConnection();
        TestClock clock = new TestClock();
        agent.on("toy.state", params -> Map.of("staff", List.of(Map.of("pinNo", 4711L, "pin", "4711"))));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true, "modalDialogs", List.of()));
        agent.value("swing.dialogs", Map.of("dialogs", List.of()));
        agent.value("swing.snapshot", Map.of("windows", List.of()));
        agent.value("app.state", Map.of("toy", Map.of("name", "IDLE")));
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withBind(BindOptions.defaults().withCredentials(TestCredentials.of(document)))
                .withStepTimeout(Duration.ofMillis(300)).withOutputDir(dir.resolve("run"));
        for (String step : List.of("  - expect: { \"state.staff[0].pin[json].x\": { exists: true } }\n",
                "  - captureFacts: { slot: o, fields: { n: { size: \"state.staff[0].pinNo\" } } }\n")) {
            RunResult result = new PlanExecutor(pack, agent, options).run(PlanCompiler.compile(("scenario: t\npack:"
                    + " toy\nsteps:\n" + step).getBytes(StandardCharsets.UTF_8), "t.yaml", pack));
            assertEquals(Status.FAIL, result.status(), result.message());
            assertFalse(result.message().matches("(?s).*(^|[^0-9])4711([^0-9]|$).*"), "a person's run too: "
                    + result.message());
            assertFalse(Files.readString(dir.resolve("run").resolve(RunLog.FILE)).matches(
                    "(?s).*[^0-9\"]4711[^0-9\"].*"), "run.jsonl holds no bare 4711");
        }
    }

    // ---------------------------------------------------------------------------------------------------- S153

    @Test
    void aSessionCheckNormalisesItsMatcherBeforeItSubstitutes() throws Exception {
        fx.state.put("mode", Map.of("not", Map.of("equals", "LOCKED")));
        try (ExecutorSession session = fx.open()) {
            StepOutcome capture = session.execute("captureFacts", Map.of("slot", "before", "fields", Map.of("mode",
                    "state.mode")), Origin.person("tester"));
            assertEquals(Status.PASS, capture.status(), capture.message());
            fx.state.put("mode", "OPEN");
            CheckOutcome bare = session.check("state.mode", "$before.mode", MODEL);
            assertFalse(bare.held(), "a captured map is a value, compared for equality: " + bare.reason());
            assertEquals(session.check("state.mode", Map.of("equals", "$before.mode"), MODEL).held(), bare.held());
            assertEquals(Status.FAIL, session.execute("expect", Map.of("state.mode", "$before.mode"),
                    Origin.person("tester")).status(), "as the compiled expect step");
        }
    }
}
