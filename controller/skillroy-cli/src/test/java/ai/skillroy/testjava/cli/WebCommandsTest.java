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

import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.web.WebLib;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase4-contracts.md 12.1 item 6: {@code caps} lists the {@code web.*} capabilities as the agent declares them;
 * {@code run --json} carries the run's {@code web.path} record and the path of every web call; a {@code --pack} jar
 * that holds a {@code WebPath} service makes the CDP path available; {@code compile} prints the compiler's note.
 */
class WebCommandsTest {

    /** A web scenario of the {@code clitest} pack (the base web verbs exist in every pack). */
    static final String WEB = """
            scenario: web-borrow
            pack: clitest
            steps:
              - webFill: { selector: "#item", text: hammer }
              - webClick: { role: button, name: Borrow }
              - webDispatch: { name: webhost.reset }
            """;

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;

    @BeforeEach
    void setUp() throws IOException {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        put("web.browsers", CapabilityState.OK, null, null);
        put("web.evaluate", CapabilityState.OK, null, null);
        put("web.adapter.jcef", CapabilityState.OK, null, "jcef 152");
        put("web.adapter.jxbrowser", CapabilityState.UNAVAILABLE, "not present", null);
        put("web.cdp", CapabilityState.UNAVAILABLE, "no remote-debugging endpoint configured", null);
        put("web.hittest", CapabilityState.UNAVAILABLE, "reserved for the vision bolt-on", null);
        agent.handlers.put("web.dom.fill", p -> Map.of("matched", 1L, "typed", 6L));
        agent.handlers.put("web.dom.click", p -> Map.of("matched", 1L, "fidelity", "DOM"));
        agent.handlers.put("web.dispatchEvent", p -> Map.of("dispatched", true));
        CliCdpPath.SERVED.clear();
        CliCdpPath.LIFECYCLE.clear();
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private void put(String id, CapabilityState state, String reason, String strategy) {
        agent.capabilities.put(id, new Capability(id, state, reason, strategy));
    }

    private CliHarness.Outcome cli(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(connection);
        return CliHarness.run(tmp.resolve("unused-discovery"), all.toArray(String[]::new)).never(agent.token);
    }

    @Test
    void capsListsTheWebCapabilitiesAsTheAgentDeclaresThem() {
        String table = cli("caps").expectExit(Exit.OK).out();
        for (String line : List.of("web.adapter.jcef", "web.adapter.jxbrowser", "web.browsers", "web.cdp",
                "web.evaluate", "web.hittest")) {
            assertTrue(table.contains(line), line + " in\n" + table);
        }
        assertTrue(table.lines().anyMatch(l -> l.startsWith("web.adapter.jcef") && l.contains("OK")
                && l.endsWith("jcef 152")), table);
        assertTrue(table.lines().anyMatch(l -> l.startsWith("web.cdp") && l.contains("UNAVAILABLE")
                && l.contains("no remote-debugging endpoint configured")), table);
        Map<String, Object> json = Json.parseObject(cli("caps", "--json").expectExit(Exit.OK).out());
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) json.get("capabilities");
        assertEquals("jcef 152", ((Map<?, ?>) capabilities.get("web.adapter.jcef")).get("strategy"));
        assertEquals("UNAVAILABLE", ((Map<?, ?>) capabilities.get("web.cdp")).get("state"));
        assertEquals("reserved for the vision bolt-on", ((Map<?, ?>) capabilities.get("web.hittest")).get("reason"));
    }

    @Test
    void runJsonCarriesTheWebPathRecordAndThePathOfEveryWebCall() throws IOException {
        Path scenario = Scenarios.write(tmp.resolve("scenarios"), "web.yaml", WEB);
        CliHarness.Outcome outcome = cli("run", scenario.toString(), "--json").expectExit(Exit.OK);
        Map<String, Object> result = result(outcome);
        assertEquals("PASS", result.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> web = (Map<String, Object>) result.get("web");
        assertEquals("agent", web.get("path"));
        assertEquals("no web path with id cdp is on the class path", web.get("reason"));
        assertEquals(WebLib.hash(), web.get("libHash"));
        assertEquals("auto", web.get("policy"));
        assertEquals(List.of(call("s1", "web.dom.fill", "agent"), call("s2", "web.dom.click", "agent"),
                call("s3", "web.dispatchEvent", "agent")), web.get("calls"));
        assertEquals(List.of(), web.get("fallbacks"));
        assertEquals(List.of(), CliCdpPath.SERVED);

        Path plain = Scenarios.write(tmp.resolve("plain"), "pass.yaml", Scenarios.PASS);
        Map<String, Object> none = result(cli("run", plain.toString(), "--json").expectExit(Exit.OK));
        assertTrue(none.containsKey("web") && none.get("web") == null, "null for a plan without web calls: " + none);
    }

    @Test
    void aPackJarHoldingAWebPathMakesTheCdpPathAvailable() throws IOException {
        put("web.cdp", CapabilityState.OK, null, null);
        Path scenario = Scenarios.write(tmp.resolve("scenarios"), "web.yaml", WEB);
        Path jar = CliCdpPath.jar(tmp);
        Map<String, Object> result = result(cli("run", scenario.toString(), "--json", "--pack", jar.toString())
                .expectExit(Exit.OK));
        @SuppressWarnings("unchecked")
        Map<String, Object> web = (Map<String, Object>) result.get("web");
        assertEquals("cdp", web.get("path"));
        assertEquals(Map.of("endpoint", "127.0.0.1:9222", "connectMs", 7L), web.get("cdp"));
        assertEquals(List.of(call("s1", "web.dom.fill", "cdp"), call("s2", "web.dom.click", "cdp"),
                call("s3", "web.dispatchEvent", "agent")), web.get("calls"), "the toy path serves two primitives");
        assertEquals(List.of("web.dom.fill", "web.dom.click"), CliCdpPath.SERVED);
        assertEquals(List.of("open", "close"), CliCdpPath.LIFECYCLE, "opened once for the run, closed after it");
        assertTrue(agent.rpcRequests("web.dom.fill").isEmpty());
        assertEquals(1, agent.rpcRequests("web.dispatchEvent").size());

        CliCdpPath.SERVED.clear();
        Map<String, Object> without = result(cli("run", scenario.toString(), "--json").expectExit(Exit.OK));
        assertEquals("agent", ((Map<?, ?>) without.get("web")).get("path"), "the same command without the jar");
        assertEquals(List.of(), CliCdpPath.SERVED);

        Path out = tmp.resolve("out");
        cli("run", scenario.toString(), "--pack", jar.toString(), "--out", out.toString()).expectExit(Exit.OK);
        String log = Files.readString(out.resolve("web-borrow/run.jsonl"));
        assertTrue(log.contains("\"type\":\"web.path\",") && log.contains("\"path\":\"cdp\""), log);
        assertTrue(log.contains("\"primitive\":\"web.dom.fill\",\"path\":\"cdp\""), log);
    }

    @Test
    void compileAndRunPrintTheCompilersNote() throws IOException {
        String yaml = WEB.replace("pack: clitest\n", "pack: clitest\nweb: { path: cdp }\n");
        Path scenario = Scenarios.write(tmp.resolve("scenarios"), "web.yaml", yaml);
        CliHarness.Outcome compiled = CliHarness.run(tmp.resolve("unused"), "compile", scenario.toString())
                .expectExit(Exit.OK);
        String note = "web.path is cdp: the capability web.cdp was added to requires (list it there to say so in the"
                + " scenario)";
        assertTrue(compiled.err().contains(scenario + ": note: " + note), compiled.err());
        Map<String, Object> json = Json.parseObject(CliHarness.run(tmp.resolve("unused"), "compile",
                scenario.toString(), "--json").expectExit(Exit.OK).out());
        assertEquals(List.of(note), ((Map<?, ?>) ((List<?>) json.get("plans")).get(0)).get("notes"));
        assertTrue(Files.readString(tmp.resolve("scenarios/web-borrow.plan.json")).contains("\"web.cdp\""));

        CliHarness.Outcome run = cli("run", scenario.toString()).expectExit(Exit.FAILED);
        assertTrue(run.err().contains("skillroy run: note: " + scenario + ": " + note), run.err());
        assertTrue(run.out().contains("PRECONDITION_FAILED: unmet requirements: web.cdp: UNAVAILABLE (no"
                + " remote-debugging endpoint configured)"), run.out());
    }

    private static Map<String, Object> call(String step, String primitive, String path) {
        return Map.of("step", step, "index", 1L, "primitive", primitive, "path", path, "ok", true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(CliHarness.Outcome outcome) {
        Map<String, Object> document = Json.parseObject(outcome.out());
        return (Map<String, Object>) ((List<?>) document.get("results")).get(0);
    }
}
