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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 security fix S111 (phase7-contracts.md 3.6, 4.8; package SI's test in the scenario module, the L5 sceptic's
 * probe B): the session's text snapshot, which every session model and an MCP client read, shows a browser a pack's
 * {@code CaptureMask} names as one line {@code [web <ref> masked]}, never its URL, title or accessibility tree, and its
 * tree is never read; an older agent's browser counts as masked when its component is masked in the Swing snapshot.
 */
class MaskedBrowserSnapshotTest {

    @TempDir
    Path dir;

    private static final String PERSONAL = "Ada Example, 12 Lending Lane, born 1990-04-01";

    private SessionFixture fixture(boolean agentSaysMasked) {
        SessionFixture fx = new SessionFixture(dir);
        fx.agent.withWeb();
        Map<String, Object> front = new java.util.LinkedHashMap<>(FakeAgentConnection.browser("h7", "member", true));
        front.put("title", "Member record");
        if (agentSaysMasked) {
            front.put("masked", true);
            front.put("url", "");
            front.remove("title");
        } else {
            fx.agent.value("swing.snapshot", Json.parse("{\"seq\":9,\"truncated\":false,\"windows\":[{\"ref\":\"h1\","
                    + "\"role\":\"window\",\"name\":\"Toy\",\"children\":[{\"ref\":\"h3\",\"role\":\"panel\","
                    + "\"masked\":true,\"children\":[{\"ref\":\"h2\",\"role\":\"panel\"}]}]}]}"));
        }
        fx.agent.value("web.browsers", Map.of("browsers", List.of(front)));
        fx.agent.on("web.ariaSnapshot", params -> Map.of("nodes", List.of(Map.of("role", "heading", "name",
                "Member record", "level", 1L), Map.of("role", "cell", "name", PERSONAL)), "truncated", false));
        return fx;
    }

    @Test
    void aMaskedBrowserIsOneLineInTheSnapshotText() throws Exception {
        for (boolean agentSaysMasked : new boolean[] {true, false}) {
            SessionFixture fx = fixture(agentSaysMasked);
            try (ExecutorSession session = fx.open()) {
                String text = session.snapshotText(4000);
                assertTrue(text.contains("[web h7 masked]"), agentSaysMasked + ": " + text);
                assertFalse(text.contains(PERSONAL), text);
                assertFalse(text.contains("Member record"), text);
                assertFalse(text.contains("data:text/html,member"), text);
                assertEquals(0, fx.agent.callsTo("web.ariaSnapshot").size(), "the page's tree is never read");
            }
        }
    }

    @Test
    void anUnmaskedBrowserStillShowsItsPage() throws Exception {
        SessionFixture fx = new SessionFixture(dir);
        fx.agent.withWeb();
        try (ExecutorSession session = fx.open()) {
            String text = session.snapshotText(4000);
            assertTrue(text.contains("[web h7] front page"), text);
            assertTrue(text.contains("heading \"Borrow\""), text);
        }
    }
}
