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

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D159 as one rule on every route (phase6 security fix, pass 2, P2-L6-F2 and P2-L6-F3): a capture's bytes leave the
 * server only under the inference settings' {@code images: true}, and then only as the PNG blob behind a tool's
 * resource link; never as text, whatever the route: a tool's result at any depth ({@code skillroy_facts},
 * {@code skillroy_events}, {@code skillroy_session_observe}, {@code skillroy_session_check}, a step), a resource
 * ({@code state/current}, {@code events/recent}, the session journal), a run's file served as text
 * ({@code run.jsonl}, {@code failure/*.json}). Before the fix the run log served a {@code swing.capture} call's bytes
 * (the reviewer's probe 1) and every tool but a step's value, and a value deeper than eight levels, answered them as
 * text.
 */
class CapturesOverMcpTest {

    static final String SIGNATURE = "iVBORw0KGgo";

    @TempDir
    Path dir;

    private static Map<String, Object> capture() {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("format", "png");
        capture.put("width", 1L);
        capture.put("height", 1L);
        capture.put("base64", FakeAgent.PNG_1X1);
        return capture;
    }

    /** The value {@code levels} maps deep: {@code {l1: {l2: … value}}}. */
    private static Object nested(Object value, int levels) {
        Object v = value;
        for (int i = levels; i >= 1; i--) {
            v = Map.of("l" + i, v);
        }
        return v;
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static InferenceSettings imagesOn() {
        return new InferenceSettings(InferenceSettings.GATEWAY_NONE, null, false, null, null, null, null, null, null,
                false, true, null, false, null);
    }

    /** The desk with a capture nine levels deep in {@code app.state} and in an event. */
    private static McpFixture desk(Path dir) {
        McpFixture fx = new McpFixture(dir);
        FakeAgent agent = fx.desk.agent;
        agent.on("app.state", params -> Map.of("desk", Map.of("mode", "IDLE"), "view", nested(capture(), 9)));
        agent.publish("desk.viewed", Map.of("view", nested(capture(), 9)));
        return fx;
    }

    /** Every tool that answers target content, once; the results. */
    private static List<McpSchema.CallToolResult> callEverything(McpFixture fx) {
        List<McpSchema.CallToolResult> results = new ArrayList<>();
        results.add(fx.call("swing_capture", args("target", null)));
        results.add(fx.call("app_state", Map.of()));
        results.add(fx.call("skillroy_facts", args("root", "app", "path", null)));
        results.add(fx.call("skillroy_events", args("sinceSeq", 0L, "topics", null, "max", null)));
        results.add(fx.call("skillroy_session_observe", args("path", "app.view")));
        results.add(fx.call("skillroy_session_check", args("path", "app.view", "matcher", "x")));
        McpSchema.CallToolResult run = fx.call("skillroy_scenario_run", Map.of("yaml", "scenario: cap\npack: desk\n"
                + "steps:\n  - call: {primitive: swing.capture, args: {}}\n", "mode", "live"));
        assertFalse(run.isError(), McpFixture.text(run));
        results.add(run);
        return results;
    }

    /** The text of every resource served as text. */
    private static List<String> textResources(McpFixture fx) {
        List<String> texts = new ArrayList<>();
        for (McpSchema.Resource resource : fx.client.listResources().resources()) {
            McpSchema.ResourceContents contents = fx.client.readResource(new McpSchema.ReadResourceRequest(
                    resource.uri())).contents().get(0);
            if (contents instanceof McpSchema.TextResourceContents text) {
                texts.add(resource.uri() + "\n" + text.text());
            }
        }
        return texts;
    }

    @Test
    void aCapturesBytesReachTheClientOnNoRouteWithoutTheImagesOptIn() throws Exception {
        McpFixture fx = desk(dir);
        List<String> texts = new ArrayList<>();
        try {
            fx.startMutating();
            assertFalse(fx.server.servesImages());
            List<McpSchema.CallToolResult> results = callEverything(fx);
            for (McpSchema.CallToolResult result : results) {
                texts.add(McpFixture.text(result));
            }
            for (int i : new int[] {0, 1, 2, 3, 4}) {
                McpSchema.CallToolResult result = results.get(i);
                assertEquals(2, result.content().size(), "the text and a link, at any depth: " + texts.get(i));
                McpSchema.ResourceLink link = assertInstanceOf(McpSchema.ResourceLink.class, result.content().get(1));
                assertTrue(link.uri().endsWith(".png.txt"), "a text naming the file: " + link.uri());
            }
            Map<String, Object> facts = McpFixture.json(results.get(2));
            assertTrue(Json.write(facts).contains("\"base64\":\"<image: skillroy://mcp/"), Json.write(facts));
            List<String> resources = textResources(fx);
            assertTrue(resources.stream().anyMatch(t -> t.startsWith("skillroy://run/run-1/run.jsonl")), resources
                    .toString());
            assertTrue(resources.stream().anyMatch(t -> t.startsWith("skillroy://run/sess-1/journal.jsonl")),
                    resources.toString());
            texts.addAll(resources);
            assertTrue(texts.stream().anyMatch(t -> t.contains("<png: 70 bytes>")), "a capture keeps its size");
        } finally {
            fx.close();
        }
        for (String text : texts) {
            assertFalse(text.contains(SIGNATURE), text);
        }
        assertFalse(fx.fromServer.written().contains(SIGNATURE), "the protocol stream holds no capture's bytes");
        Path runLog = fx.out.resolve("runs").resolve("run-1").resolve("run.jsonl");
        assertFalse(Files.readString(runLog, StandardCharsets.UTF_8).contains(SIGNATURE), "nor the run log on disk");
        assertArrayEquals(Base64.getDecoder().decode(FakeAgent.PNG_1X1), Files.readAllBytes(fx.out.resolve("mcp")
                .resolve("1.png")), "the person's file holds the pixels");
    }

    @Test
    void underTheImagesOptInACaptureLeavesAsABlobOnly() throws Exception {
        McpFixture fx = desk(dir);
        List<String> texts = new ArrayList<>();
        try {
            fx.start(fx.options().inference(imagesOn()).mutating(true).build());
            List<McpSchema.CallToolResult> results = callEverything(fx);
            for (McpSchema.CallToolResult result : results) {
                texts.add(McpFixture.text(result));
            }
            McpSchema.ResourceLink link = assertInstanceOf(McpSchema.ResourceLink.class, results.get(2).content()
                    .get(1));
            assertTrue(link.uri().endsWith(".png"), link.uri());
            McpSchema.BlobResourceContents blob = assertInstanceOf(McpSchema.BlobResourceContents.class,
                    fx.client.readResource(new McpSchema.ReadResourceRequest(link.uri())).contents().get(0));
            assertArrayEquals(Base64.getDecoder().decode(FakeAgent.PNG_1X1), Base64.getDecoder().decode(blob.blob()));
            texts.addAll(textResources(fx));
        } finally {
            fx.close();
        }
        for (String text : texts) {
            assertFalse(text.contains(SIGNATURE), "never as text, under images: true too: " + text);
        }
    }

    @Test
    void aRunLogWrittenBeforeTheFixIsServedWithoutTheBytes() throws Exception {
        Path run = dir.resolve("an-old-run");
        Files.createDirectories(run.resolve("failure"));
        String other = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
        Files.writeString(run.resolve("run.jsonl"), "{\"type\":\"call\",\"t\":5,\"step\":\"s1\",\"primitive\":"
                + "\"swing.capture\",\"ok\":true,\"value\":" + Json.write(capture()) + "}\n{\"type\":\"cleanup\","
                + "\"value\":{\"format\":\"jpeg\",\"base64\":\"" + other + "\"}}\n");
        Files.writeString(run.resolve("failure").resolve("events.json"), Json.write(Map.of("events",
                List.of(Map.of("payload", capture())))));
        try (McpFixture fx = new McpFixture(dir.resolve("server")).startReadOnly()) {
            List<String> uris = fx.server.registerRun("run-x", run);
            assertEquals(List.of("skillroy://run/run-x/run.jsonl", "skillroy://run/run-x/failure/events.json"), uris);
            for (String uri : uris) {
                String text = ((McpSchema.TextResourceContents) fx.client.readResource(
                        new McpSchema.ReadResourceRequest(uri)).contents().get(0)).text();
                assertFalse(text.contains(SIGNATURE), text);
                assertFalse(text.contains(other), text);
                assertTrue(text.contains("<png: 70 bytes>"), text);
            }
        }
    }
}
