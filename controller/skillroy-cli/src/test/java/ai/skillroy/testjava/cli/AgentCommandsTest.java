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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.Protocol;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** info, caps, schema, call and snapshot against the fake agent, with stdout and stderr captured. */
class AgentCommandsTest {

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;

    @BeforeEach
    void setUp() throws IOException {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CliHarness.Outcome cli(String... args) {
        return cliWithInput("", args);
    }

    private CliHarness.Outcome cliWithInput(String stdin, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(connection);
        return CliHarness.runWithInput(tmp.resolve("unused-default"), stdin, all.toArray(String[]::new))
                .never(agent.token);
    }

    private Map<String, Object> lastParams(String method) {
        List<FakeAgent.Request> calls = agent.rpcRequests(method);
        assertFalse(calls.isEmpty(), "no call of " + method);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) Json.parseObject(calls.get(calls.size() - 1).body())
                .get("params");
        return params;
    }

    // --- info -------------------------------------------------------------------------------------------------------

    @Test
    void infoShowsTheAgentAndDropsSecretOptions() {
        String out = cli("info").expectExit(Exit.OK).never(agent.agentNonce).out();
        assertTrue(out.contains("agent      9.9.9-fake, protocol " + Protocol.VERSION + ", load mode JAVAAGENT"), out);
        assertTrue(out.contains("Java 1.8.0_fake"), out);
        assertTrue(out.contains("ready, read-write, instrumentation available"), out);
        assertTrue(out.contains("modules    swing 0.1.0 INSTALLED"), out);
        assertTrue(out.contains("options    eventRing=10000"), out);
    }

    /** Phase 8 (phase8-contracts.md 3.2 rule 4, A68): a module's non-empty info follows its state. */
    @Test
    void infoPrintsAModulesInfoAfterItsState() {
        assertEquals("swing 0.2.0 INSTALLED {vetoWithheld=1}, data 0.2.0 INSTALLED", InfoCommand.modules(List.of(
                Map.of("id", "swing", "version", "0.2.0", "state", "INSTALLED", "info", Map.of("vetoWithheld", 1L)),
                Map.of("id", "data", "version", "0.2.0", "state", "INSTALLED", "info", Map.of()))));
    }

    @Test
    void infoJsonIsTheAgentInfoValueWithoutSecrets() {
        String out = cli("info", "--json").expectExit(Exit.OK).never(agent.agentNonce).out();
        JsonView info = JsonView.of(Json.parseObject(out));
        assertEquals("9.9.9-fake", info.str("agentVersion"));
        assertEquals(Map.of("eventRing", "10000"), info.obj("options").raw());
    }

    // --- caps -------------------------------------------------------------------------------------------------------

    @Test
    void capsPrintsTheMatrixSortedById() {
        String out = cli("caps").expectExit(Exit.OK).out();
        String[] lines = out.split("\n");
        assertTrue(lines[0].startsWith("CAPABILITY"), out);
        assertTrue(lines[1].startsWith("test.fancy") && lines[1].contains("UNAVAILABLE")
                && lines[1].contains("not in this fake"), out);
        assertTrue(lines[2].startsWith("test.poke") && lines[2].contains("OK") && lines[2].contains("fake"), out);
        assertTrue(agent.rpcRequests("agent.capabilities").isEmpty(), "plain caps reads GET /capabilities");
    }

    @Test
    void capsReprobeAsksTheAgentAndJsonKeepsTheShape() {
        String out = cli("caps", "--reprobe", "--json").expectExit(Exit.OK).out();
        assertEquals(Boolean.TRUE, lastParams("agent.capabilities").get("reprobe"));
        JsonView caps = JsonView.of(Json.parseObject(out)).obj("capabilities");
        assertEquals("UNAVAILABLE", caps.obj("test.fancy").str("state"));
        assertEquals("not in this fake", caps.obj("test.fancy").str("reason"));
        assertEquals("OK", caps.obj("test.poke").str("state"));
    }

    // --- schema -----------------------------------------------------------------------------------------------------

    @Test
    void schemaDescribesEveryPrimitive() {
        String out = cli("schema").expectExit(Exit.OK).out();
        assertTrue(out.contains("agent.disable  (destructive: needs --confirm; thread CALLER; timeout 10000 ms)"),
                out);
        assertTrue(out.contains("test.poke  (mutating; thread WORKER; timeout 5000 ms; needs test.poke)"), out);
        assertTrue(out.contains("    Increments the counter.\n    params: by?: integer\n"), out);
        assertTrue(out.indexOf("agent.disable") < out.indexOf("test.counter")
                && out.indexOf("test.counter") < out.indexOf("test.poke"), "sorted by name:\n" + out);
    }

    @Test
    void schemaFiltersByNameGlobs() {
        String out = cli("schema", "--name", "test.*").expectExit(Exit.OK).out();
        assertFalse(out.contains("agent.disable"), out);
        assertTrue(out.contains("test.poke") && out.contains("test.counter"), out);
        String json = cli("schema", "--name", "agent.*,test.counter", "--json").expectExit(Exit.OK).out();
        List<Object> primitives = JsonView.of(Json.parseObject(json)).list("primitives");
        assertEquals(2, primitives.size());
        CliHarness.Outcome none = cli("schema", "--name", "nothing.*").expectExit(Exit.OK);
        assertEquals("", none.out());
        assertTrue(none.err().contains("no primitive matches --name nothing.*"), none.err());
    }

    // --- call -------------------------------------------------------------------------------------------------------

    @Test
    void callPrintsTheValueAsJson() {
        String out = cli("call", "test.poke", "{\"by\": 3}").expectExit(Exit.OK).out();
        assertEquals(Map.of("count", 3L), Json.parseObject(out));
        assertEquals(3L, lastParams("test.poke").get("by"));
        assertEquals(Map.of("value", 3L), Json.parseObject(cli("call", "test.counter").expectExit(Exit.OK).out()));
        assertEquals(Set.of("_meta"), lastParams("test.counter").keySet(), "no params but _meta");
    }

    @Test
    void callReadsParamsFromAFileOrStandardInput() throws IOException {
        Path file = Files.writeString(tmp.resolve("params.json"), "{\"by\": 2}");
        cli("call", "test.poke", "@" + file).expectExit(Exit.OK);
        assertEquals(2L, lastParams("test.poke").get("by"));
        cliWithInput("{\"by\": 5}\n", "call", "test.poke", "-").expectExit(Exit.OK);
        assertEquals(5L, lastParams("test.poke").get("by"));
        assertEquals(7, agent.counter.get());
    }

    @Test
    void callRejectsParamsThatAreNotAJsonObjectBeforeConnecting() {
        assertTrue(cli("call", "test.poke", "{oops").expectExit(Exit.USAGE).err().contains("is not valid JSON"));
        assertTrue(cli("call", "test.poke", "[1,2]").expectExit(Exit.USAGE).err()
                .contains("must be a JSON object, found [1,2]"));
        assertTrue(cli("call", "test.poke", "@" + tmp.resolve("missing.json")).expectExit(Exit.USAGE).err()
                .contains("cannot be read"));
        assertTrue(cli("call", "test.poke", "--timeout", "later").expectExit(Exit.USAGE).err()
                .contains("--timeout"));
        assertTrue(agent.requests.isEmpty(), "nothing may be sent for a usage error");
    }

    @Test
    void callPassesTimeoutAndConfirmAsMeta() {
        cli("call", "agent.disable", "--confirm", "--timeout", "2s").expectExit(Exit.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) lastParams("agent.disable").get("_meta");
        assertEquals(Boolean.TRUE, meta.get("confirm"));
        assertEquals(2000L, meta.get("timeoutMs"));
    }

    @Test
    void aJsonRpcErrorIsExitCodeOneWithTheErrorOnStderr() {
        CliHarness.Outcome missing = cli("call", "no.such.method").expectExit(Exit.FAILED);
        assertEquals("", missing.out());
        assertTrue(missing.err().contains("no.such.method failed: METHOD_NOT_FOUND (-32601)"), missing.err());

        CliHarness.Outcome destructive = cli("call", "agent.disable").expectExit(Exit.FAILED);
        assertTrue(destructive.err().contains("CONFIRM_REQUIRED (-32030)"), destructive.err());
        assertTrue(destructive.err().contains("data: {\n  \"primitive\": \"agent.disable\"\n}"), destructive.err());
        assertTrue(destructive.err().contains("add --confirm"), destructive.err());

        agent.handlers.put("test.reject", p -> {
            throw new RpcException(ErrorCodes.INPUT_REJECTED, "not now", Map.of("state", "IDLE"));
        });
        String json = cli("call", "test.reject", "--json").expectExit(Exit.FAILED).out();
        JsonView error = JsonView.of(Json.parseObject(json)).obj("error");
        assertEquals("INPUT_REJECTED", error.str("name"));
        assertEquals(ErrorCodes.INPUT_REJECTED, error.num("code", 0));
        assertEquals("IDLE", error.obj("data").str("state"));
    }

    @Test
    void callJsonPrintsTheWholeResult() {
        String out = cli("call", "test.poke", "--json").expectExit(Exit.OK).out();
        JsonView result = JsonView.of(Json.parseObject(out));
        assertEquals(1L, result.obj("value").num("count", 0));
        assertEquals("fake-worker", result.str("thread"));
        assertTrue(result.obj("seq").has("before") && result.obj("seq").has("after"), out);
    }

    // --- snapshot ---------------------------------------------------------------------------------------------------

    @Test
    void snapshotRendersTheTreeAndTheApplicationState() {
        String out = cli("snapshot").expectExit(Exit.OK).out();
        assertTrue(out.contains("app:\n  test: {\"counter\":0}"), out);
        assertTrue(out.contains("- window \"Fake Desk\" [ref=h1]"), out);
        assertTrue(out.contains("  - button \"DUE\" [ref=h2] (disabled)"), out);
        assertTrue(out.contains("  - textbox \"entry\": \"42\" [ref=h3]"), out);
    }

    @Test
    void snapshotPassesItsOptionsAndJsonIsTheRawValue() {
        String out = cli("snapshot", "--json", "--root", "h1", "--max-depth", "3", "--include-hidden")
                .expectExit(Exit.OK).out();
        assertEquals("h1", JsonView.of(Json.parseObject(out)).list("windows").stream()
                .map(w -> ((Map<?, ?>) w).get("ref")).findFirst().orElse(null));
        Map<String, Object> params = lastParams("swing.snapshot");
        assertEquals("h1", params.get("root"));
        assertEquals(3L, params.get("maxDepth"));
        assertEquals(Boolean.TRUE, params.get("includeHidden"));
        assertTrue(agent.rpcRequests("app.state").isEmpty(), "--json reads the snapshot only");
    }

    @Test
    void snapshotFailureIsExitCodeOneAndMissingAppStateIsOnlyANote() {
        agent.handlers.remove("app.state");
        CliHarness.Outcome noState = cli("snapshot").expectExit(Exit.OK);
        assertTrue(noState.err().contains("no application state"), noState.err());
        agent.handlers.put("swing.snapshot", p -> {
            throw new RpcException(ErrorCodes.CAPABILITY_UNAVAILABLE, "swing.tree is UNAVAILABLE",
                    Map.of("reason", "headless"));
        });
        CliHarness.Outcome failed = cli("snapshot").expectExit(Exit.FAILED);
        assertTrue(failed.err().contains("CAPABILITY_UNAVAILABLE"), failed.err());
    }

    @Test
    void theTokenIsRedactedEvenWhenAnApplicationEchoesIt() {
        agent.handlers.put("test.echo", p -> Map.of("leak", "token=" + agent.token));
        String out = cli("call", "test.echo").expectExit(Exit.OK).out();
        assertEquals(Map.of("leak", "token=<redacted>"), Json.parseObject(out));
        agent.handlers.put("test.fail", p -> {
            throw new RpcException(ErrorCodes.INTERNAL_ERROR, "boom " + agent.token);
        });
        assertTrue(cli("call", "test.fail", "--json").expectExit(Exit.FAILED).err().contains("boom <redacted>"));
    }

    @Test
    void anUnexpectedAgentReplyIsAnInternalErrorNotACrash() {
        agent.handlers.put("agent.capabilities", p -> "not a matrix");
        CliHarness.Outcome outcome = cli("caps", "--reprobe").expectExit(Exit.FAILED);
        assertTrue(outcome.err().contains("returned no capability matrix"), outcome.err());
    }
}
