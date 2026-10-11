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

import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 security fix, S115 (phase5-contracts.md 9.3, phase7-contracts.md 9.4; the L7 sceptic's probe 1, inverted):
 * a client's {@code skillroy_scenario_run} never writes into the operator's bindings root. On a mutating server with a
 * bindings root and no approver (the CLI's server, {@code REJECT_ALL}), {@code mode: approve} of an images-only
 * scenario named like a committed one is refused before anything runs and leaves the committed file byte-identical;
 * an unused id plants no file; {@code mode: live} writes nothing either. Before the fix the approve run replaced the
 * committed file (its resolutions and oracles gone) and planted a file for an unused id.
 */
class ScenarioRunApproveTest {

    @TempDir
    Path dir;

    private static final String IMAGES_ONLY = "pack: desk\nsteps:\n  - expectImage: { label: pad }\n";

    private static List<Path> tree(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.sorted().toList();
        }
    }

    @Test
    void aClientsRunNeverWritesIntoTheOperatorsBindingsRoot() throws Exception {
        Path root = dir.resolve("bindings");
        Path committed = BindingsFile.locate(root, "seed-a", "desk-committed");
        Files.createDirectories(committed.getParent());
        String text = new BindingsFile(1, "desk-committed", "seed-a", "desk", "1.0.0", "sha256:plan", "sha256:resolve",
                new EnvFingerprint(EnvFingerprint.VERSION, "sha256:env", Map.of()), Map.of(), Map.of()).toJsonText();
        Files.writeString(committed, text);
        List<Path> before = tree(root);
        try (McpFixture fx = new McpFixture(dir)) {
            SessionOptions session = SessionOptions.defaults(null).withBind(BindOptions.defaults()
                    .withCredentials(Desk.credentials()).withEnvId("seed-a").withBindingsRoot(root)).withId("sess-1");
            fx.desk.agent.value("swing.rendering", Map.of("renderKey", "0123456789abcdef"));
            fx.start(fx.options().session(session).mutating(true).build());
            int captures = fx.desk.agent.callsTo("swing.capture").size();
            for (String id : List.of("desk-committed", "desk-unused")) {
                McpSchema.CallToolResult approve = fx.call("skillroy_scenario_run", Map.of("yaml", "scenario: " + id
                        + "\n" + IMAGES_ONLY, "mode", "approve"));
                assertTrue(approve.isError(), McpFixture.text(approve));
                assertTrue(McpFixture.text(approve).contains("mode approve is refused: this server has no approver"),
                        McpFixture.text(approve));
            }
            assertEquals(captures, fx.desk.agent.callsTo("swing.capture").size(), "nothing ran");
            McpSchema.CallToolResult live = fx.call("skillroy_scenario_run", Map.of("yaml", "scenario: desk-unused\n"
                    + IMAGES_ONLY, "mode", "live"));
            assertFalse(McpFixture.text(live).contains("mode approve is refused"), McpFixture.text(live));
        }
        assertEquals(text, Files.readString(committed), "the committed file is byte-identical");
        assertEquals(before, tree(root), "no file planted, none removed");
    }
}
