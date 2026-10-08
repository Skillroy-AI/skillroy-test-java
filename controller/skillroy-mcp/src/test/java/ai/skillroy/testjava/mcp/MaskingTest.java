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

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 9.2, 9.3, 6.2: every output masked. The desk's application leaks its operator's credential into
 * its state, its events, its UI and its answers, and answers a {@code password} key; after a session that reads and
 * acts through every kind of tool and reads every resource, the credential appears nowhere the server wrote (the
 * protocol stream, its log, the journal, the run directory, the exports), and the {@code password} value nowhere on the
 * protocol stream.
 */
class MaskingTest {

    @TempDir
    Path dir;

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void aSecretAppearsNowhereTheServerWrote() throws Exception {
        McpFixture fx = new McpFixture(dir);
        List<String> texts = new ArrayList<>();
        try {
            fx.start(fx.options().mutating(true).actor((session, options) -> new FakeActor(session)).build());
            List<McpSchema.CallToolResult> results = new ArrayList<>();
            results.add(fx.call("desk_enter", Map.of("value", "HAM-1")));
            results.add(fx.call("desk_echo", args("text", "hello", "pad", null)));
            results.add(fx.call("desk_state", Map.of()));
            results.add(fx.call("app_state", Map.of()));
            results.add(fx.call("swing_snapshot", Map.of()));
            results.add(fx.call("skillroy_snapshot", args("maxChars", null)));
            results.add(fx.call("skillroy_facts", args("root", "app", "path", null)));
            results.add(fx.call("skillroy_events", args("sinceSeq", 0L, "topics", null, "max", null)));
            results.add(fx.call("skillroy_session_ask", Map.of("request", "lend the hammer")));
            results.add(fx.call("skillroy_session_observe", args("path", "app.operatorPin")));
            results.add(fx.call("skillroy_session_check", args("path", "app.operatorPin", "matcher", Desk.SECRET)));
            results.add(fx.call("skillroy_session_observe", args("path", "desk." + Desk.SECRET)));
            results.add(fx.call("skillroy_scenario_run", Map.of("yaml", "scenario: desk-masked\npack: desk\nsteps:\n"
                    + "  - enter: {value: SAW-2}\n  - expect: {desk.mode: CLOSED}\ntimeouts: {step: 200ms}\n",
                    "mode", "live")));
            results.add(fx.call("skillroy_session_export", args("scenarioId", "desk-masked-export")));
            for (McpSchema.CallToolResult result : results) {
                texts.add(McpFixture.text(result));
            }
            for (McpSchema.Resource resource : fx.client.listResources().resources()) {
                McpSchema.ResourceContents contents = fx.client.readResource(new McpSchema.ReadResourceRequest(
                        resource.uri())).contents().get(0);
                if (contents instanceof McpSchema.TextResourceContents text) {
                    texts.add(text.text());
                }
            }
            assertTrue(texts.stream().anyMatch(t -> t.contains("<redacted>")), "the masks were at work");
            assertTrue(texts.stream().anyMatch(t -> t.contains("\"operatorPin\": \"<redacted>\"")), texts.toString());
            assertTrue(texts.stream().anyMatch(t -> t.contains("\"password\": \"<redacted>\"")), "the key rule");
            assertTrue(texts.stream().anyMatch(t -> t.contains("token=<redacted>")), "the key=value rule");
        } finally {
            fx.close();
        }
        String stream = fx.fromServer.written();
        assertFalse(stream.isEmpty());
        assertFalse(stream.contains(Desk.SECRET), "the protocol stream");
        assertFalse(stream.contains("hunter22-plain"), "the protocol stream: a password key's value");
        for (String text : texts) {
            assertFalse(text.contains(Desk.SECRET), text);
        }
        assertFalse(fx.logText().contains(Desk.SECRET), "the log");
        assertTrue(fx.logText().contains("call skillroy_session_observe: error"), fx.logText());
        List<Path> files;
        try (Stream<Path> walk = Files.walk(fx.out)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("journal.jsonl")));
        assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("run.jsonl")));
        assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("desk-masked-export.yaml")));
        for (Path file : files) {
            String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            assertFalse(content.contains(Desk.SECRET), file.toString());
        }
    }
}
