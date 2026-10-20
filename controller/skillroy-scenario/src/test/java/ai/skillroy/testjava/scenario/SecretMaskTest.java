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

import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment A25 (fixture secrets masked in every run output), now for version 1 plans, which may still hold a fixture
 * secret, and phase2-contracts.md 11.4: a credential value joined into a slot appears in no run output.
 */
class SecretMaskTest {

    private static final String LONG_SECRET = "s3cr3t-Passw0rd";
    private static final String SHORT_SECRET = "1111";

    @Test
    void secretsAreCollectedByKeyAndMaskedByValue() {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("assistant", Map.of("code", "100", "password", SHORT_SECRET, "apiKey", 4242L));
        slots.put("vault", Map.of("credentials", Map.of("user", "kim", "pass", LONG_SECRET), "name", "main"));
        slots.put("pin", "9876");
        slots.put("plain", Map.of("token_count", List.of("x"), "flag", true));
        SecretMask mask = FixtureSecrets.mask(slots);

        assertEquals("<redacted>", mask.apply(SHORT_SECRET));
        assertEquals("pin " + SHORT_SECRET, mask.apply("pin " + SHORT_SECRET), "short secrets only as whole values");
        assertEquals("expected \"<redacted>\" but was \"2\"", mask.apply("expected \"" + SHORT_SECRET
                + "\" but was \"2\""), "or quoted, as JSON and messages write string values");
        assertEquals("{\"pin\":\"<redacted>\"}", mask.apply("{\"pin\":\"" + SHORT_SECRET + "\"}"));
        assertEquals("<redacted>", mask.apply("kim"), "every field below a secret key is secret ('credentials')");
        assertEquals("main", mask.apply("main"));
        assertEquals("<redacted>", mask.apply(LONG_SECRET));
        assertEquals("typed <redacted> into entry", mask.apply("typed " + LONG_SECRET + " into entry"));
        assertEquals("100", mask.apply("100"));
        assertEquals("9876", mask.apply("9876"), "the slot 'pin' does not look secret by name");
        assertEquals("<redacted>", mask.apply(4242L), "a number equal to a numeric secret");
        assertEquals(4243L, mask.apply(4243L));
        assertEquals("<redacted>", mask.apply("x"), "every value under a secret-looking key, lists included");

        Object masked = mask.apply(Map.of("params", List.of(Map.of("text", SHORT_SECRET, "n", 1L)), "msg",
                "the password is " + LONG_SECRET));
        assertEquals(Map.of("params", List.of(Map.of("text", "<redacted>", "n", 1L)), "msg",
                "the password is <redacted>"), masked);
        assertEquals(Map.of("user", "kim", "password", "<redacted>", "deep", Map.of("apiKey", "<redacted>")),
                FixtureSecrets.maskKeys(Map.of("user", "kim", "password", "x", "deep", Map.of("apiKey", 3L))));

        assertTrue(FixtureSecrets.mask(Map.of("item", Map.of("code", "A"))).isEmpty());
        Object value = Map.of("a", "b");
        assertSame(value, SecretMask.NONE.apply(value));
    }

    @Test
    void theFixturePasswordAppearsInNoRunOutput(@TempDir Path dir) throws Exception {
        ToyPack pack = new ToyPack();
        FakeAgentConnection agent = new FakeAgentConnection();
        agent.capabilities("toy.input", "toy.state", "toy.loan", "swing.capture.paint");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN", "echo", String.valueOf(params.get("value"))));
            // Phase 8 (phase8-contracts.md 7.5): a result that echoes a secret parameter fails its step ERROR /
            // ENVIRONMENT before anything else, so the echo for the mask to catch is in the event's payload only.
            return Map.of("accepted", true);
        });
        agent.on("swing.type", params -> {
            throw new AgentRpcException("swing.type", new RpcError(ErrorCodes.INPUT_REJECTED, "rejected input '"
                    + params.get("text") + "' (password " + LONG_SECRET + ")", Map.of("text", params.get("text"))));
        });
        // The application echoes the secrets in its state, its UI and its dialogs: the evidence must not.
        agent.value("toy.state", Map.of("name", "LOAN", "lastInput", SHORT_SECRET, "banner", "pw=" + LONG_SECRET));
        agent.value("app.state", Map.of("toy", Map.of("pin", SHORT_SECRET, "note", LONG_SECRET + "!")));
        agent.value("swing.snapshot", Json.parse("{\"seq\":3,\"truncated\":false,\"windows\":[{\"ref\":\"h1\","
                + "\"role\":\"window\",\"name\":\"" + LONG_SECRET + "\",\"children\":[{\"ref\":\"h2\",\"role\":"
                + "\"textbox\",\"name\":\"entry\",\"text\":\"" + SHORT_SECRET + "\"}]}]}"));
        agent.value("swing.dialogs", Map.of("dialogs", List.of(Map.of("title", "Sign on", "text", LONG_SECRET))));
        agent.value("swing.capture", Map.of("base64", ""));
        Path out = dir.resolve("t");
        TestClock clock = new TestClock();
        RunOptions options = RunOptions.defaults().withOutputDir(out).withClock(clock).withSleeper(clock.sleeper());
        // A version 2 plan cannot hold a fixture secret; a version 1 plan still can and is still read (5, 11.4).
        String yaml = """
                scenario: t
                pack: toy
                resolve:
                  assistant: { fixture: { code: "100", pin: "%s" } }
                  vault: { fixture: { name: main, hidden: "%s" } }
                steps:
                  - enter: { value: $assistant.code }
                  - enter: { value: $assistant.pin }
                  - enter: { value: "pw $vault.hidden" }
                  - expect: { "state.banner": { contains: $vault.hidden } }
                  - expect: { "state.lastInput": $assistant.pin }
                  - type: { target: h2, text: $vault.hidden }
                """.formatted(SHORT_SECRET, LONG_SECRET);
        Plan plan = version1(PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack),
                Map.of("pin", "password", "hidden", "secret"));
        assertEquals(Plan.VERSION, plan.planVersion(), "a version 1 plan is read as version 2");
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.FAIL, result.status(), result.message());
        assertEquals("s6", result.failedStepId());
        assertTrue(agent.callsTo("swing.type").get(0).params().containsValue(LONG_SECRET),
                "the secret is still used for the call");
        assertTrue(agent.callsTo("toy.input").stream().anyMatch(c -> SHORT_SECRET.equals(c.params().get("value"))));
        List<FakeAgentConnection.Call> inputs = agent.callsTo("toy.input");
        assertEquals(List.of(), inputs.get(0).options().secretParams(), "the code is no secret");
        assertEquals(List.of("value"), inputs.get(1).options().secretParams(),
                "A34: a version 1 fixture secret is announced to the agent");
        assertEquals(List.of("value"), inputs.get(2).options().secretParams(), "embedded in a longer text too");
        assertEquals(List.of("text"), agent.callsTo("swing.type").get(0).options().secretParams());

        List<String> outputs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(Files::isRegularFile).filter(f -> !f.toString().endsWith(".png"))
                    .toList()) {
                outputs.add(file.getFileName() + ":\n" + Files.readString(file));
            }
        }
        assertTrue(outputs.size() >= 6, outputs.toString());
        outputs.add("result:\n" + result);
        outputs.add("junit:\n" + new JunitXmlReporter().render("s", List.of(result), null));
        for (String output : outputs) {
            assertFalse(output.contains(LONG_SECRET), output);
            assertFalse(output.contains("\"" + SHORT_SECRET + "\""), output);
        }

        String bindings = Files.readString(out.resolve("bindings.json"));
        Map<String, Object> json = Json.parseObject(bindings);
        assertEquals(Map.of("strategy", "FIXTURE", "value", Map.of("code", "100", "password", "<redacted>")),
                ((Map<?, ?>) json.get("resolutions")).get("assistant"), "the code stays readable");
        String log = Files.readString(out.resolve("run.jsonl"));
        assertTrue(log.contains("\"value\":\"<redacted>\""), log);
        assertTrue(log.contains("\"value\":\"pw <redacted>\""), log);
        assertTrue(result.message().contains("<redacted>"), result.message());
        assertTrue(String.valueOf(result.detail().get("snapshot")).contains("window \"<redacted>\""),
                String.valueOf(result.detail().get("snapshot")));
    }

    @Test
    void secretParamsNameAFixtureSecretByProvenanceNeverByValue(@TempDir Path dir) throws Exception {
        ToyPack pack = new ToyPack();
        FakeAgentConnection agent = new FakeAgentConnection();
        agent.capabilities("toy.input");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of();
        });
        agent.value("toy.lock", Map.of());
        agent.value("toy.unlock", Map.of());
        // The probe of the review: a version 1 plan with fixture {code, password}; and literals equal to a secret.
        String yaml = """
                scenario: t
                pack: toy
                resolve:
                  assistant: { fixture: { code: "100", pw: "%s" } }
                steps:
                  - enter: { value: $assistant.pw }
                  - enter: { value: $assistant.code }
                  - enter: { value: $assistant }
                  - enter: { value: "%s" }
                  - enter: { value: "%s" }
                  - lock: { name: $assistant.pw }
                """.formatted(LONG_SECRET, LONG_SECRET, TestCredentials.PW_200);
        Plan plan = version1(PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack),
                Map.of("pw", "password"));
        TestClock clock = new TestClock();
        RunOptions options = RunOptions.defaults().withOutputDir(dir).withClock(clock).withSleeper(clock.sleeper())
                .withBind(TestCredentials.bind());
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.PASS, result.status(), result.message());
        List<FakeAgentConnection.Call> inputs = agent.callsTo("toy.input");
        assertEquals(LONG_SECRET, inputs.get(0).params().get("value"));
        assertEquals(List.of("value"), inputs.get(0).options().secretParams(), "$assistant.password is a secret");
        assertEquals(List.of(), inputs.get(1).options().secretParams(), "$assistant.code is not");
        assertEquals(List.of("value"), inputs.get(2).options().secretParams(),
                "a structure that holds a secret-looking field");
        assertEquals(LONG_SECRET, inputs.get(3).params().get("value"));
        assertEquals(List.of(), inputs.get(3).options().secretParams(),
                "a literal equal to the fixture secret is not named: by provenance, not by value");
        assertEquals(TestCredentials.PW_200, inputs.get(4).params().get("value"));
        assertEquals(List.of(), inputs.get(4).options().secretParams(),
                "nor is a literal equal to a credential value");
        assertEquals(List.of("which"), agent.callsTo("toy.lock").get(0).options().secretParams());
        assertEquals(List.of("which"), agent.callsTo("toy.unlock").get(0).options().secretParams(),
                "cleanup calls alike");
        List<Map<String, Object>> calls = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("run.jsonl"))) {
            Map<String, Object> record = Json.parseObject(line);
            if ("call".equals(record.get("type")) && "toy.input".equals(record.get("primitive"))) {
                calls.add(record);
            }
        }
        assertEquals(List.of("value"), calls.get(0).get("secretParams"), "the call record lists the names");
        assertFalse(calls.get(1).containsKey("secretParams"), "and omits an empty list");
    }

    /** Verifier evidence as a pack may build it. */
    record Evidence(String note) {
    }

    @Test
    void theRunLogMasksSetsArraysRecordsAndValuesNestedTooDeep(@TempDir Path dir) throws Exception {
        RunLog log = RunLog.open(dir, new TestClock());
        log.mask(SecretMask.of(List.of(LONG_SECRET, SHORT_SECRET)));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("asList", List.of(LONG_SECRET));
        evidence.put("asSet", java.util.Set.of(LONG_SECRET));
        evidence.put("asArray", new String[] {SHORT_SECRET});
        evidence.put("asRecord", new Evidence("typed " + LONG_SECRET));
        evidence.put("amount", new java.math.BigDecimal("20.00"));
        log.write("resolve", RunLog.fields("verified", List.of(RunLog.fields("evidence", evidence))));
        Object deep = LONG_SECRET;
        for (int i = 0; i < 250; i++) {
            deep = List.of(deep);
        }
        log.write("verifier", RunLog.fields("evidence", deep));
        log.close();
        List<String> lines = Files.readAllLines(dir.resolve(RunLog.FILE));
        for (String line : lines) {
            assertFalse(line.contains(LONG_SECRET) || line.contains(SHORT_SECRET), line);
        }
        Map<?, ?> written = (Map<?, ?>) ((Map<?, ?>) ((List<?>) Json.parseObject(lines.get(0)).get("verified"))
                .get(0)).get("evidence");
        assertEquals(List.of("<redacted>"), written.get("asSet"));
        assertEquals(List.of("<redacted>"), written.get("asArray"));
        assertEquals("Evidence[note=typed <redacted>]", written.get("asRecord"));
        assertTrue(lines.get(0).contains("\"amount\":20.00"), "numbers keep their form: " + lines.get(0));
        Map<String, Object> tooDeep = Json.parseObject(lines.get(1));
        assertTrue(String.valueOf(tooDeep.get("unwritable")).contains("200"), lines.get(1));
        assertEquals(List.of("evidence"), tooDeep.get("fields"), "only the field names of what cannot be masked");
    }

    @Test
    void aSlotWhoseNameLooksSecretIsMaskedWhole(@TempDir Path dir) throws Exception {
        ToyPack pack = new ToyPack();
        FakeAgentConnection agent = new FakeAgentConnection();
        agent.capabilities("toy.input");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN"));
            return Map.of();
        });
        String yaml = """
                scenario: t
                pack: toy
                resolve:
                  apiTkn: { fixture: "tok-0123456789" }
                  item: { fixture: { code: A1 } }
                steps:
                  - enter: { value: $apiTkn }
                  - enter: { value: $item.code }
                """;
        Plan plan = version1(PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack),
                Map.of("apiTkn", "apiToken"));
        RunResult result = new PlanExecutor(pack, agent, RunOptions.defaults().withOutputDir(dir)).run(plan);
        assertEquals(Status.PASS, result.status(), result.message());
        Map<?, ?> resolutions = (Map<?, ?>) Json.parseObject(Files.readString(dir.resolve("bindings.json")))
                .get("resolutions");
        assertEquals(Map.of("strategy", "FIXTURE", "value", "<redacted>"), resolutions.get("apiToken"));
        assertEquals(Map.of("strategy", "FIXTURE", "value", Map.of("code", "A1")), resolutions.get("item"));
        String log = Files.readString(dir.resolve("run.jsonl"));
        assertFalse(log.contains("tok-0123456789"), log);
        assertTrue(log.contains("\"value\":\"A1\""), log);
    }

    @Test
    void aVersion2PlanRefusesASecretLookingFixture() throws Exception {
        String yaml = """
                scenario: t
                pack: toy
                resolve:
                  assistant: { fixture: { code: "100", password: "%s" } }
                steps:
                  - enter: { value: $assistant.code }
                """.formatted(LONG_SECRET);
        ScenarioException compile = org.junit.jupiter.api.Assertions.assertThrows(ScenarioException.class,
                () -> PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", new ToyPack()));
        assertTrue(compile.getMessage().contains("password") && compile.getMessage().contains("credentials.yaml"),
                compile.getMessage());
        assertEquals(4, compile.line());
        assertFalse(compile.getMessage().contains(LONG_SECRET));
        Plan clean = PlanCompiler.compile(yaml.replace("password", "pw").getBytes(StandardCharsets.UTF_8), "t.yaml",
                new ToyPack());
        String edited = clean.toJsonText().replace("\"pw\"", "\"password\"");
        ScenarioException read = org.junit.jupiter.api.Assertions.assertThrows(ScenarioException.class,
                () -> Plan.parse(edited));
        assertTrue(read.getMessage().contains("looks secret"), read.getMessage());
        assertFalse(read.getMessage().contains(LONG_SECRET));
    }

    @Test
    void aJoinedCredentialAppearsInNoRunOutput(@TempDir Path dir) throws Exception {
        String password = TestCredentials.PW_100;
        ToyPack pack = new ToyPack();
        FakeAgentConnection agent = new FakeAgentConnection();
        agent.capabilities("toy.input", "toy.state", "toy.loan");
        agent.on("toy.input", params -> {
            agent.publish("input.acked", Map.of("source", params.get("source")));
            agent.publish("input.requested", Map.of("state", "LOAN", "echo", String.valueOf(params.get("value"))));
            if (password.equals(params.get("value"))) {
                throw new AgentRpcException("toy.input", new RpcError(ErrorCodes.INPUT_REJECTED, "wrong password "
                        + password, Map.of("value", password)));
            }
            return Map.of("accepted", true);
        });
        agent.value("toy.state", Map.of("name", "LOAN", "lastInput", password));
        agent.value("app.state", Map.of("toy", Map.of("note", "typed " + password)));
        String yaml = """
                scenario: t
                pack: toy
                resolve:
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                steps:
                  - signOn: { staff: $assistant }
                """;
        Plan plan = PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "t.yaml", pack);
        assertFalse(plan.toJsonText().contains(password));
        Path out = dir.resolve("t");
        TestClock clock = new TestClock();
        RunOptions options = RunOptions.defaults().withOutputDir(out).withClock(clock).withSleeper(clock.sleeper())
                .withBind(TestCredentials.bind());
        RunResult result = new PlanExecutor(pack, agent, options).run(plan);
        assertEquals(Status.FAIL, result.status(), result.message());
        FakeAgentConnection.Call signOn = agent.callsTo("toy.input").get(1);
        assertEquals(password, signOn.params().get("value"), "the secret is used for the call");
        assertEquals(List.of("value"), signOn.options().secretParams(),
                "A34: the agent is told which param is a secret");
        List<String> outputs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(Files::isRegularFile).filter(f -> !f.toString().endsWith(".png"))
                    .toList()) {
                outputs.add(file.getFileName() + ":\n" + Files.readString(file));
            }
        }
        outputs.add("result:\n" + result);
        outputs.add("junit:\n" + new JunitXmlReporter().render("s", List.of(result), null));
        for (String output : outputs) {
            assertFalse(output.contains(password), output);
        }
        String bindings = Files.readString(out.resolve("bindings.json"));
        Map<?, ?> assistant = (Map<?, ?>) ((Map<?, ?>) Json.parseObject(bindings).get("resolutions")).get("assistant");
        assertEquals(Map.of("code", "100"), assistant.get("value"), "joined fields are never written");
        assertEquals(Map.of("realm", "staff", "id", "100"), assistant.get("credential"),
                "the credential object is kept: the mask works by value, not by key");
    }

    /** The version 1 form of a compiled plan, with fixture keys and references renamed. */
    static Plan version1(Plan plan, Map<String, String> renames) throws Exception {
        Map<String, Object> json = plan.toJson();
        json.put("planVersion", 1L);
        json.remove("inference");
        json.remove("oracles");
        json.remove("resolutionGraph");
        json.remove("bindTimeoutMs");
        String text = Json.writePretty(json);
        for (Map.Entry<String, String> e : renames.entrySet()) {
            text = text.replace("\"" + e.getKey() + "\"", "\"" + e.getValue() + "\"")
                    .replace("." + e.getKey() + "\"", "." + e.getValue() + "\"")
                    .replace("$" + e.getKey() + "\"", "$" + e.getValue() + "\"")
                    .replace("." + e.getKey() + " ", "." + e.getValue() + " ")
                    .replace("." + e.getKey() + "}", "." + e.getValue() + "}");
        }
        return Plan.parse(text);
    }
}
