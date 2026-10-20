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

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.protocol.CallWarning;
import ai.skillroy.testjava.protocol.CompositeResult;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composite primitives (phase8-contracts.md 7.5, gap 19; 13.1 row 32): the prompts a composite lists are expected,
 * the shape is checked, and no result carries a secret parameter's value.
 */
class CompositeRulesTest {

    /** A four-character password: equal only, never inside a longer text (the A25 rule). */
    private static final String SHORT = "4005";
    private static final String LONG = "pw-200-secret";

    @TempDir
    Path dir;

    private final ToyPack pack = new ToyPack();
    private final FakeAgentConnection agent = new FakeAgentConnection();
    private final TestClock clock = new TestClock();
    /** What toy.signOn answers, given its params and the seq of the prompt it published. */
    private Function<Map<String, Object>, Object> answer = params -> Map.of("accepted", true);

    @BeforeEach
    void toyApplication() {
        agent.capabilities("toy.input", "toy.state", "toy.loan", "toy.signOn");
        agent.on("toy.signOn", params -> {
            agent.publish("input.acked", Map.of("source", "Entry"));
            return answer.apply(params);
        });
        agent.value("toy.state", Map.of("name", "LOAN"));
        agent.value("toy.loan", Map.of("lines", List.of()));
        pack.custom = List.of(new PlannedCall("toy.signOn", Map.of("staff", "$assistant.code", "password",
                "$assistant.password"), Sync.NONE, 0));
    }

    private static Credentials credentials() {
        Map<String, Object> staff = new LinkedHashMap<>();
        staff.put("400", Map.of("password", SHORT));
        staff.put("200", Map.of("password", LONG));
        return TestCredentials.of(Map.of("version", 1L, "credentials", Map.of("staff", staff)));
    }

    private RunResult run(String code) throws ScenarioException {
        String body = """
                resolve:
                  assistant: { fixture: { code: "%s" }, credential: { realm: staff, key: code } }
                steps:
                  - custom: {}
                """.formatted(code);
        Plan plan = PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8),
                "t.yaml", pack);
        RunOptions options = RunOptions.defaults().withClock(clock).withSleeper(clock.sleeper())
                .withBind(BindOptions.defaults().withCredentials(credentials())).withOutputDir(dir);
        return new PlanExecutor(pack, agent, options).run(plan);
    }

    private List<Map<String, Object>> records(String type) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if (type.equals(record.get("type"))) {
                out.add(record);
            }
        }
        return out;
    }

    // ---- nested prompts

    @Test
    void aPromptTheCompositeListsInsideItsWindowIsExpected() throws Exception {
        answer = params -> {
            long seq = agent.publish("prompt.waiting", Map.of("id", "induction-check", "text", "Inducted?"));
            return CompositeResult.builder().branch("induction").answered("prompt.waiting", "induction-check",
                    null, seq, "staff", "Yes", null).build();
        };
        RunResult result = run("400");
        assertEquals(Status.PASS, result.status(), result.message());
        List<Map<String, Object>> prompts = records("prompt");
        assertEquals(1, prompts.size());
        assertEquals(Boolean.TRUE, prompts.get(0).get("expected"));
        assertEquals("toy.signOn", prompts.get(0).get("answeredBy"));
    }

    @Test
    void anUnlistedPromptReachesThePolicyAndAListedSeqOutsideTheWindowIsIgnored() throws Exception {
        answer = params -> {
            agent.publish("prompt.waiting", Map.of("id", "unknown-staff", "text", "Unknown"));
            return CompositeResult.builder().branch("refused").answered("prompt.waiting", null, null, 999L,
                    "staff", null, null).build();
        };
        RunResult result = run("400");
        assertEquals(Status.FAIL, result.status(), "the FAIL policy decides on the prompt the composite did not list");
        List<Map<String, Object>> prompts = records("prompt");
        assertEquals(List.of(999L), prompts.get(0).get("ignoredSeqs"));
        assertEquals(Boolean.FALSE, prompts.get(1).get("expected"));
        assertEquals("FAIL", prompts.get(1).get("decision"));
    }

    @Test
    void aCompositeThatBreaksTheConventionIsAnEnvironmentError() throws Exception {
        answer = params -> CompositeResult.builder().branch("password").answered("input.requested", null, null,
                null, "password", "Yes", null).build();
        RunResult result = run("200");
        assertEquals(Status.ERROR, result.status());
        assertEquals(FailureKind.ENVIRONMENT, result.failureKind());
        assertEquals("toy.signOn returned a composite result that breaks the convention: composite.answered[0]"
                + ".choice is given for the secret parameter 'password'", result.message());

        answer = params -> Map.of("composite", Map.of("answered", List.of()));
        assertTrue(run("200").message().endsWith("composite.branch is missing"));
        answer = params -> Map.of("accepted", true, "answered", true);
        assertEquals(Status.PASS, run("200").status(), "a result without composite is untouched");
    }

    // ---- the secret-echo check

    @Test
    void aShortSecretIsCheckedByEqualityOnly() throws Exception {
        answer = params -> Map.of("accepted", true, "barcode", "T" + SHORT + "9");
        assertEquals(Status.PASS, run("400").status(), "a four-digit password inside a barcode is not an echo");
        answer = params -> Map.of("accepted", true, "taken", SHORT);
        RunResult echoed = run("400");
        assertEquals(Status.ERROR, echoed.status());
        assertEquals(FailureKind.ENVIRONMENT, echoed.failureKind());
        assertEquals("toy.signOn returned the value of its secret parameter 'password'", echoed.message());
        assertEquals("password", echoed.detail().get("parameter"), "the detail names the parameter");
        assertFalse(Json.write(echoed.detail()).contains(SHORT), "and never the value");
        answer = params -> Map.of("accepted", true, "n", 4005L);
        assertEquals(Status.ERROR, run("400").status(), "a number equal to the secret's text");
    }

    @Test
    void aLongSecretIsFoundInsideALongerText() throws Exception {
        answer = params -> Map.of("accepted", true, "note", "typed " + LONG + " here");
        RunResult result = run("200");
        assertEquals(Status.ERROR, result.status());
        assertEquals("toy.signOn returned the value of its secret parameter 'password'", result.message());
        String log = Files.readString(dir.resolve("run.jsonl"));
        assertFalse(log.contains(LONG), "the call record is masked as always");
        assertFalse(String.valueOf(result).contains(LONG));
        assertTrue(agent.awaits.isEmpty() && records("sync").isEmpty(), "nothing runs after the check");
    }

    @Test
    void aWarningsTextIsScannedToo() throws Exception {
        answer = params -> FakeAgentConnection.Warned.of(Map.of("accepted", true), new CallWarning("VALUE_COERCED",
                "took " + LONG, null));
        assertEquals("toy.signOn returned the value of its secret parameter 'password'", run("200").message());
        answer = params -> FakeAgentConnection.Warned.of(Map.of("accepted", true), new CallWarning("VALUE_COERCED",
                "took", Map.of("taken", SHORT)));
        assertEquals("toy.signOn returned the value of its secret parameter 'password'", run("400").message(),
                "the check comes before the warnings, so an unaccepted warning cannot hide it");
    }

    @Test
    void aResultTooLargeToCheckFailsClosed() throws Exception {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < CallRunner.ECHO_MAX_SCALARS + 1; i++) {
            many.add("x" + i);
        }
        answer = params -> Map.of("accepted", true, "list", many);
        RunResult wide = run("200");
        assertEquals(Status.ERROR, wide.status());
        assertTrue(wide.message().startsWith("toy.signOn returned a result too large to check for secrets"),
                wide.message());
        Object deep = "leaf";
        for (int i = 0; i < CallRunner.ECHO_MAX_DEPTH + 1; i++) {
            deep = Map.of("d", deep);
        }
        Object nested = deep;
        answer = params -> Map.of("accepted", true, "deep", nested);
        assertTrue(run("200").message().contains("too large to check for secrets"));
        // Without secret parameters there is nothing to check.
        pack.custom = List.of(new PlannedCall("toy.signOn", Map.of("staff", "$assistant.code"), Sync.NONE, 0));
        answer = params -> Map.of("accepted", true, "list", many);
        assertEquals(Status.PASS, run("200").status());
    }
}
