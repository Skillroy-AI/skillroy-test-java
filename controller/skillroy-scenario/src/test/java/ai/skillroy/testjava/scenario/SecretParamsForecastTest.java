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
import ai.skillroy.testjava.scenario.pack.DomainPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase4-contracts.md 6.4 (the review's L2 F1): the forecast that decides, before the web path opens and before the
 * bind, whether a step or cleanup call of the plan may announce a secret parameter (A34). It must never say no where
 * the run's own rule later names one (the last tests compare the two call by call on real runs), and it says no for
 * what the run will not name: a literal, a plain field of a credential's fixture, a value with no secret-looking key.
 */
class SecretParamsForecastTest {

    private static final String LONG_SECRET = "s3cr3t-Passw0rd";

    /** The toy credential {@code staff}: a fixture with its code, joined with a password at bind time. */
    private static final String STAFF =
            "  staff: { fixture: { code: \"100\" }, credential: { realm: staff, key: code } }\n";

    private final ToyPack pack = new ToyPack();

    private static Plan compile(DomainPack pack, String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    /** The forecast for each step call of the plan, in order. */
    private static List<Boolean> stepCalls(Plan plan, Bindings provided, String envId) {
        SecretParamsForecast forecast = SecretParamsForecast.of(plan, provided, envId);
        List<Boolean> out = new ArrayList<>();
        for (Plan.Step step : plan.steps()) {
            for (Plan.Call call : step.calls()) {
                out.add(forecast.mayAnnounce(call));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the bind's own

    @Test
    void aLiteralIsNeverASecretNotEvenOneEqualToACredential() throws Exception {
        Plan plan = compile(pack, "resolve:\n" + STAFF + "steps:\n"
                + "  - webFill: { selector: \"#user\", text: \"" + TestCredentials.PW_100 + "\" }\n"
                + "  - webFill: { selector: \"#user\", text: \"$$staff.password\" }\n"
                + "  - expect: { \"state.name\": $staff.code }\n");
        assertEquals(List.of(false, false), stepCalls(plan, null, null), "by provenance, never by value");
        assertFalse(SecretParamsForecast.mayAnnounce(plan, null, null));
    }

    @Test
    void aJoinedCredentialFieldOrTheWholeCredentialSlotMayBeASecretAFieldOfItsFixtureIsNot() throws Exception {
        Plan plan = compile(pack, "resolve:\n" + STAFF + "steps:\n"
                + "  - webFill: { selector: \"#pw\", text: $staff.password }\n"
                + "  - webFill: { selector: \"#user\", text: $staff.code }\n"
                + "  - webFill: { selector: \"#all\", text: $staff }\n"
                + "  - webDispatch: { name: signed, detail: { who: $staff.code, note: \"pw $staff.password\" } }\n");
        assertEquals(List.of(true, false, true, true), stepCalls(plan, null, null),
                "a joined field (also embedded and nested), the whole slot; its own fixture field is not secret");
        assertTrue(SecretParamsForecast.mayAnnounce(plan, null, null));
    }

    @Test
    void aCleanupCallCountsAsAStepCallDoes() throws Exception {
        Plan secret = compile(pack, "resolve:\n" + STAFF + "steps:\n  - lock: { name: $staff.password }\n");
        SecretParamsForecast forecast = SecretParamsForecast.of(secret, null, null);
        assertEquals(1, secret.cleanup().size());
        assertTrue(forecast.mayAnnounce(secret.cleanup().get(0)), "toy.unlock {which: $staff.password}");

        Plan plain = compile(pack, "resolve:\n" + STAFF + "steps:\n  - lock: { name: $staff.code }\n");
        assertFalse(SecretParamsForecast.mayAnnounce(plain, null, null));
    }

    @Test
    void whatOnlyTheBindProducesIsUnknownAndCountsWhereAReferenceReachesIt() throws Exception {
        ToyPack resolving = new ToyResolution().pack();
        Plan plan = compile(resolving, """
                resolve:
                  max: { query: Limit, args: { type: RETURN } }
                  item: { query: Item, args: { deposit: { between: ["2.00", "50.00"], lte: $max } } }
                  assistant:
                    query: Staff
                    args: { limitBelow: $item.deposit }
                    credential: { realm: staff, key: code }
                  loan: { fixture: { item: $item.code, note: plain } }
                steps:
                  - webFill: { selector: "#user", text: $assistant.code }
                  - webFill: { selector: "#item", text: $item.code }
                  - webFill: { selector: "#item", text: $loan.item }
                  - webFill: { selector: "#note", text: $loan.note }
                  - webFill: { selector: "#loan", text: $loan }
                """);
        assertEquals(List.of(true, false, true, false, true), stepCalls(plan, null, null),
                "a credential query slot: any field (its value's fields are known only after the bind); a query slot"
                        + " without a credential is not covered by the key rule; a fixture's copy of a query value is"
                        + " unknown, its literal field is not");
    }

    @Test
    void aVersion1FixtureSecretByTheKeyRule() throws Exception {
        Plan plan = SecretMaskTest.version1(compile(pack, """
                resolve:
                  assistant: { fixture: { code: "100", pw: "%s" } }
                  blank: { fixture: { code: "200", pw: "" } }
                  copy: { fixture: { inner: $assistant, code: $assistant.code } }
                steps:
                  - enter: { value: $assistant.pw }
                  - enter: { value: $assistant.code }
                  - enter: { value: $assistant }
                  - enter: { value: "%s" }
                  - enter: { value: $blank.pw }
                  - enter: { value: $copy }
                  - enter: { value: $copy.code }
                """.formatted(LONG_SECRET, LONG_SECRET)), Map.of("pw", "password"));
        assertEquals(List.of(true, false, true, false, false, true, false), stepCalls(plan, null, null),
                "a secret-looking field and a structure that holds one (also through another fixture) are secrets;"
                        + " the code, a literal and an empty text are not");
    }

    @Test
    void fixturesThatReferenceFixturesAndTheEnvironmentAreKnownBeforeTheBind() throws Exception {
        Plan plan = compile(pack, """
                resolve:
                  desk: { fixture: { site: north } }
                  where: { fixture: { site: $desk.site, env: $env.id, label: "desk $desk.site" } }
                steps:
                  - webFill: { selector: "#w", text: $where }
                  - webFill: { selector: "#s", text: $where.site }
                """);
        assertEquals(List.of(false, false), stepCalls(plan, null, "seed-a"));
        assertEquals(List.of(true, false), stepCalls(plan, null, null),
                "without an environment id $env.id does not resolve (the bind refuses it): unknown");
    }

    // ------------------------------------------------------------------------------------------------ caller bindings

    @Test
    void theCallersJoinedFieldsDecideTheCredentialHalf() throws Exception {
        Plan plan = compile(pack, "resolve:\n" + STAFF + "steps:\n"
                + "  - webFill: { selector: \"#pw\", text: $staff.password }\n"
                + "  - webFill: { selector: \"#user\", text: $staff.code }\n"
                + "  - webFill: { selector: \"#all\", text: $staff }\n");
        Bindings joined = Bindings.of(Map.of("staff", Map.of("code", "100"))).withCredential("staff",
                Map.of("password", TestCredentials.PW_100));
        assertEquals(List.of(true, false, true), stepCalls(plan, joined, null));
    }

    @Test
    void theKeyRuleJudgesTheCallersValues() throws Exception {
        Plan plan = compile(pack, """
                resolve:
                  login: { fixture: { user: u0 } }
                  desk: { fixture: { site: south } }
                steps:
                  - webFill: { selector: "#l", text: $login }
                  - webFill: { selector: "#u", text: $login.user }
                  - webFill: { selector: "#d", text: $desk }
                """);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("login", Map.of("user", "u1", "secret", LONG_SECRET));
        values.put("desk", Map.of("site", "north"));
        assertEquals(List.of(true, false, false), stepCalls(plan, Bindings.of(values), null),
                "the caller's value, not the plan's fixture: it holds a key that looks secret");
        assertEquals(List.of(false, false, false), stepCalls(plan, null, null), "the plan's own fixtures hold none");
    }

    @Test
    void aSlotThePlanCapturesHoldsAValueOnlyTheRunKnows() throws Exception {
        Plan plan = compile(pack, """
                steps:
                  - waitFor: { topic: web.ready, capture: { slot: ready, fields: { name: name } } }
                  - webFill: { selector: "#e", text: $ready.name }
                """);
        assertEquals(List.of(false, true), stepCalls(plan, Bindings.of(Map.of("ready", Map.of("name", "x"))), null),
                "a caller's slot that the run replaces when it captures");
        assertEquals(List.of(false, false), stepCalls(plan, Bindings.empty(), null), "the key rule covers only the"
                + " caller's slots");
        assertEquals(List.of(false, false), stepCalls(plan, null, null), "nor a captured slot of a bound plan");
    }

    // ------------------------------------------------------------------------------------------------ against runs

    /** Per step call, in plan order, whether the run's {@code call} record named secret parameters. */
    private static List<Boolean> namedByTheRun(Plan plan, Path dir) throws Exception {
        Map<String, Boolean> named = new LinkedHashMap<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if ("call".equals(record.get("type"))) {
                named.put(record.get("step") + "/" + record.get("index"), record.containsKey("secretParams"));
            }
        }
        List<Boolean> out = new ArrayList<>();
        for (Plan.Step step : plan.steps()) {
            for (int i = 0; i < step.calls().size(); i++) {
                Boolean value = named.get(step.id() + "/" + (i + 1));
                assertTrue(value != null, "every step call ran: " + step.id() + "/" + (i + 1) + " in " + named);
                out.add(value);
            }
        }
        return out;
    }

    @Test
    void theForecastAgreesWithTheRunCallByCallOnWebVerbsAndACredential(@TempDir Path dir) throws Exception {
        FakeAgentConnection agent = new FakeAgentConnection().withWeb();
        agent.value("toy.state", Map.of("name", "IDLE"));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true));
        agent.value("toy.lock", Map.of());
        agent.value("toy.unlock", Map.of());
        Plan plan = compile(pack, "resolve:\n" + STAFF + "steps:\n"
                + "  - webFill: { selector: \"#pw\", text: $staff.password }\n"
                + "  - webFill: { selector: \"#user\", text: $staff.code }\n"
                + "  - webFill: { selector: \"#lit\", text: \"" + TestCredentials.PW_100 + "\" }\n"
                + "  - webDispatch: { name: signed, detail: { who: $staff.code, note: \"pw $staff.password\" } }\n"
                + "  - webClick: { role: button, name: $staff.code }\n"
                + "  - lock: { name: $staff.password }\n");
        TestClock clock = new TestClock();
        RunOptions options = RunOptions.defaults().withOutputDir(dir).withClock(clock).withSleeper(clock.sleeper())
                .withBind(TestCredentials.bind());
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.PASS, result.status(), result.message());
        List<Boolean> run = namedByTheRun(plan, dir);
        assertEquals(List.of(true, false, false, true, false, true), run);
        assertEquals(run, stepCalls(plan, null, options.bind().envId()));
        assertEquals(List.of("which"), agent.callsTo("toy.unlock").get(0).options().secretParams());
        assertTrue(SecretParamsForecast.of(plan, null, null).mayAnnounce(plan.cleanup().get(0)));
    }

    @Test
    void theForecastAgreesWithTheRunCallByCallOnVersion1FixtureSecrets(@TempDir Path dir) throws Exception {
        FakeAgentConnection agent = new FakeAgentConnection();
        agent.capabilities("toy.input");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of();
        });
        Plan plan = SecretMaskTest.version1(compile(pack, """
                resolve:
                  assistant: { fixture: { code: "100", pw: "%s" } }
                  blank: { fixture: { code: "200", pw: "" } }
                  copy: { fixture: { inner: $assistant, code: $assistant.code } }
                steps:
                  - enter: { value: $assistant.pw }
                  - enter: { value: $assistant.code }
                  - enter: { value: $assistant }
                  - enter: { value: "%s" }
                  - enter: { value: $blank.pw }
                  - enter: { value: $copy }
                  - enter: { value: "x $copy.inner.pw" }
                """.formatted(LONG_SECRET, LONG_SECRET)), Map.of("pw", "password"));
        TestClock clock = new TestClock();
        RunOptions options = RunOptions.defaults().withOutputDir(dir).withClock(clock).withSleeper(clock.sleeper());
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.PASS, result.status(), result.message());
        List<Boolean> run = namedByTheRun(plan, dir);
        assertEquals(List.of(true, false, true, false, false, true, true), run);
        assertEquals(run, stepCalls(plan, null, options.bind().envId()));
    }

    @Test
    void theForecastAgreesWithTheRunCallByCallWithTheCallersBindings(@TempDir Path dir) throws Exception {
        FakeAgentConnection agent = new FakeAgentConnection().withWeb();
        agent.value("toy.state", Map.of("name", "IDLE"));
        agent.value("swing.edtState", Map.of("dispatchThreadAlive", true));
        Plan plan = compile(pack, "resolve:\n" + STAFF + "  login: { fixture: { user: u0 } }\nsteps:\n"
                + "  - webFill: { selector: \"#pw\", text: $staff.password }\n"
                + "  - webFill: { selector: \"#user\", text: $staff.code }\n"
                + "  - webFill: { selector: \"#login\", text: $login }\n"
                + "  - webFill: { selector: \"#u\", text: $login.user }\n");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("staff", Map.of("code", "100"));
        values.put("login", Map.of("user", "u1", "token", LONG_SECRET));
        Bindings provided = Bindings.of(values).withCredential("staff", Map.of("password", TestCredentials.PW_100));
        TestClock clock = new TestClock();
        RunOptions options = RunOptions.defaults().withOutputDir(dir).withClock(clock).withSleeper(clock.sleeper());
        RunResult result = new PlanExecutor(pack, agent, options).run(plan, provided);
        assertEquals(Status.PASS, result.status(), result.message());
        List<Boolean> run = namedByTheRun(plan, dir);
        assertEquals(List.of(true, false, true, false), run);
        assertEquals(run, stepCalls(plan, provided, options.bind().envId()));
    }
}
