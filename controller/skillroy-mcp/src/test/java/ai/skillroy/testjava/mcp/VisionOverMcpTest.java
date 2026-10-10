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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 9.4 and 13.1 item 8 (the MCP half): a client's raw {@code swing_capture {redact: false}} and
 * {@code {mode: ROBOT}} and {@code swing_pointer {via: ROBOT}} (with {@code --allow-robot} set) are refused, never
 * stripped, with nothing sent; a client's scenario whose base verb or raw call names a credential slot's field its
 * fixture does not provide is refused by {@code PlanPolicy} with nothing run, a pass-through pack verb given it too,
 * while {@code signOn {staff: $staff}} (the pack verb's own join) runs; {@code skillroy_catalogue} shows each
 * capability's source; the session's host features follow {@code --vision}; the captures directory is listed only under
 * {@code images: true}; {@code v3} needs the robot flag.
 */
class VisionOverMcpTest {

    @TempDir
    Path dir;

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static InferenceSettings images(boolean on) {
        return new InferenceSettings(InferenceSettings.GATEWAY_NONE, null, false, null, null, null, null, null, null,
                false, on, null, false, null);
    }

    @Test
    void aClientsRawToolNeverCarriesAPersonsArgument() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.desk.agent.describe("swing.pointer", true, false, null);
            fx.desk.agent.value("swing.pointer", Map.of("fired", true));
            fx.start(fx.options().mutating(true).robot(true).build());
            List.of(
                    Map.entry("swing_capture", args("target", null, "redact", false)),
                    Map.entry("swing_capture", args("target", null, "mode", "ROBOT")),
                    Map.entry("swing_pointer", args("action", "CLICK", "via", "ROBOT")),
                    Map.entry("swing_pointer", args("action", "CLICK", "force", true)),
                    Map.entry("swing_click", args("target", "h12", "force", true))).forEach(call -> {
                        McpSchema.CallToolResult result = fx.call(call.getKey(), call.getValue());
                        String text = McpFixture.text(result);
                        assertTrue(result.isError(), call + ": " + text);
                        assertTrue(text.contains("refused, nothing was sent"), text);
                    });
            for (String method : List.of("swing.capture", "swing.pointer", "swing.click")) {
                assertTrue(fx.desk.agent.callsTo(method).isEmpty(), method + " reached the agent");
            }
            assertTrue(fx.server.session().options().robot(), "the person's flag is the session's (for the actor)");
            McpSchema.CallToolResult routed = fx.call("swing_pointer", args("action", "CLICK"));
            assertFalse(routed.isError(), McpFixture.text(routed));
            assertEquals(1, fx.desk.agent.callsTo("swing.pointer").size(), "a routed pointer runs");
        }
    }

    /** The desk's pack with a {@code signOn} verb that joins the staff slot's password itself, as the sample's does. */
    private static DomainPack withSignOn(Desk desk) {
        DomainPack base = desk.pack;
        StepLibrary baseSteps = base.steps();
        return new DomainPack() {
            @Override
            public String id() {
                return base.id();
            }

            @Override
            public String version() {
                return base.version();
            }

            @Override
            public StepLibrary steps() {
                return new StepLibrary() {
                    @Override
                    public Set<String> verbs() {
                        Set<String> verbs = new TreeSet<>(baseSteps.verbs());
                        verbs.add("signOn");
                        return verbs;
                    }

                    @Override
                    public List<PlannedCall> compile(String verb, Object args, CompileContext ctx)
                            throws ScenarioException {
                        if (!verb.equals("signOn")) {
                            return baseSteps.compile(verb, args, ctx);
                        }
                        Object staff = ((Map<?, ?>) args).get("staff");
                        return List.of(new PlannedCall("desk.input", Map.of("source", "Entry", "value", staff
                                + ".code"), Sync.NONE, 0), new PlannedCall("desk.input", Map.of("source", "Entry",
                                "value", staff + ".password"), Sync.NONE, 0));
                    }

                    @Override
                    public Map<String, Object> argsSchema(String verb) {
                        return verb.equals("signOn") ? null : baseSteps.argsSchema(verb);
                    }
                };
            }

            @Override
            public SyncPolicy syncPolicy() {
                return base.syncPolicy();
            }

            @Override
            public List<FactProvider> facts() {
                return base.facts();
            }

            @Override
            public void recover(AgentConnection connection) throws Exception {
                base.recover(connection);
            }

            @Override
            public ResolutionCatalogue resolution() {
                return base.resolution();
            }

            @Override
            public List<SqlAllowList> sqlAllowLists() {
                return base.sqlAllowLists();
            }
        };
    }

    private static String staffScenario(String id, String step) {
        return "scenario: " + id + "\npack: desk\nresolve:\n  staff: { fixture: { code: \"100\" }, credential:"
                + " { realm: staff, key: code } }\nsteps:\n" + step;
    }

    @Test
    void aClientsScenarioNeverUsesACredentialItsFixtureDoesNotProvide() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.start(fx.options(withSignOn(fx.desk)).mutating(true).build());
            for (String step : List.of(
                    "  - call: {primitive: desk.input, args: {source: Entry, value: $staff.password}}\n",
                    "  - enter: {value: $staff.password}\n")) {
                McpSchema.CallToolResult refused = fx.call("skillroy_scenario_run", Map.of("yaml",
                        staffScenario("typed-secret", step), "mode", "live"));
                String text = McpFixture.text(refused);
                assertTrue(refused.isError(), text);
                assertTrue(text.contains("credential slot 'staff'") && text.contains("'password'"), text);
                assertFalse(text.contains(Desk.SECRET), "never a value");
            }
            assertTrue(fx.desk.agent.callsTo("desk.input").isEmpty(), "nothing ran");
            McpSchema.CallToolResult code = fx.call("skillroy_scenario_run", Map.of("yaml", staffScenario(
                    "typed-code", "  - enter: {value: $staff.code}\n"), "mode", "live"));
            assertFalse(code.isError(), "the fixture provides the code: " + McpFixture.text(code));
            int before = fx.desk.agent.callsTo("desk.input").size();
            McpSchema.CallToolResult signOn = fx.call("skillroy_scenario_run", Map.of("yaml", staffScenario(
                    "signed-on", "  - signOn: {staff: $staff}\n"), "mode", "live"));
            assertFalse(signOn.isError(), "a pack verb's own join: " + McpFixture.text(signOn));
            assertEquals(before + 2, fx.desk.agent.callsTo("desk.input").size());
        }
    }

    @Test
    void theCatalogueShowsEachCapabilitysSource() throws Exception {
        try (McpFixture fx = new McpFixture(dir)) {
            fx.startReadOnly();
            Map<String, Object> catalogue = McpFixture.json(fx.call("skillroy_catalogue", Map.of()));
            Map<?, ?> caps = (Map<?, ?>) catalogue.get("capabilities");
            assertFalse(caps.isEmpty());
            for (Object entry : caps.values()) {
                assertTrue(((Map<?, ?>) entry).get("source") instanceof String s && (s.equals("agent")
                        || s.startsWith("controller:")), String.valueOf(entry));
            }
        }
    }

    @Test
    void theSessionsHostFeaturesFollowVisionAndV3NeedsTheRobotFlag() throws Exception {
        try (McpFixture fx = new McpFixture(dir.resolve("off"))) {
            fx.start(fx.options().inference(images(true)).build());
            assertTrue(fx.server.session().options().host().isEmpty(), "no --vision: no vision session");
            assertFalse(fx.server.session().options().robot());
        }
        try (McpFixture fx = new McpFixture(dir.resolve("none"))) {
            fx.start(fx.options().inference(images(true)).vision(Map.of("vision.tier", "v1")).build());
            assertTrue(fx.server.session().options().host().isEmpty(), "gateway none: no vision session");
        }
        try (McpFixture fx = new McpFixture(dir.resolve("on"))) {
            InferenceSettings scripted = new InferenceSettings("scripted", null, false, null, null, null, null, null,
                    null, false, true, null, false, null);
            fx.start(fx.options().inference(scripted).vision(Map.of("vision.tier", "v2")).build());
            assertEquals(Set.of("vision-session", "vision-tier:v2"), fx.server.session().options().host(),
                    "the module present (a SessionTools of id vision), a gateway, images on");
        }
        McpFixture fx = new McpFixture(dir.resolve("v3"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> fx.options()
                .vision(Map.of("vision.tier", "v3")).build());
        assertTrue(e.getMessage().contains("--allow-robot"), e.getMessage());
        fx.close();
    }

    @Test
    void theCapturesAreListedOnlyUnderImagesTrue() throws Exception {
        for (boolean on : new boolean[] {false, true}) {
            try (McpFixture fx = new McpFixture(dir.resolve("images-" + on))) {
                fx.start(fx.options().inference(images(on)).actor((session, options) -> new FakeActor()).build());
                Path captures = fx.server.session().options().outputDir().resolve("captures");
                Files.createDirectories(captures);
                String hex = "a".repeat(64);
                Files.write(captures.resolve(hex + ".png"), Base64Png.ONE);
                Files.writeString(captures.resolve("not-a-capture.txt"), "x");
                McpSchema.CallToolResult asked = fx.call("skillroy_session_ask", Map.of("request", "look"));
                assertFalse(asked.isError(), McpFixture.text(asked));
                List<String> uris = fx.client.listResources().resources().stream().map(McpSchema.Resource::uri)
                        .toList();
                assertEquals(on, uris.contains("skillroy://captures/" + hex + ".png"), uris.toString());
                assertFalse(uris.stream().anyMatch(u -> u.contains("not-a-capture")), uris.toString());
            }
        }
    }

    /** A 1 x 1 PNG's bytes. */
    private static final class Base64Png {
        static final byte[] ONE = java.util.Base64.getDecoder().decode(FakeAgent.PNG_1X1);
    }
}
