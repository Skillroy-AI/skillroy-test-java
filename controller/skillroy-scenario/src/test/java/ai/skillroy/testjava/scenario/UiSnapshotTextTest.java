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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiSnapshotTextTest {

    @Test
    void rendersNodesWithRefsAndFlags() {
        Object snapshot = Json.parse("""
                {"seq": 4, "truncated": false, "windows": [
                  {"ref": "h1", "role": "window", "name": "Toy desk", "children": [
                    {"ref": "h2", "role": "panel", "name": "", "text": "", "children": [
                      {"ref": "h12", "role": "button", "name": "DUE", "enabled": false},
                      {"ref": "h13", "role": "textbox", "name": "entry", "text": "5000\\n17", "focused": true},
                      {"ref": "h14", "role": "checkbox", "name": "", "text": "Inducted", "selected": true,
                       "visible": false},
                      {"ref": "h15", "role": "combobox", "name": "site", "text": "MILL LANE", "value": 2}
                    ]}
                  ]}
                ]}
                """);
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("toy", Map.of("name", "LOAN"));
        String text = UiSnapshotText.render(snapshot, app);
        assertEquals("""
                app:
                  toy: {"name":"LOAN"}
                - window "Toy desk" [ref=h1]
                  - button "DUE" [ref=h12] (disabled)
                  - textbox "entry": "5000 17" [ref=h13] (focused)
                  - checkbox "Inducted" [ref=h14] (hidden, selected)
                  - combobox "site": "MILL LANE" [ref=h15] value=2
                """, text);
    }

    @Test
    void respectsTheBudgetAndMarksTruncation() {
        List<Object> children = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            children.add(Map.of("ref", "h" + i, "role", "label", "text", "line " + i));
        }
        Map<String, Object> snapshot = Map.of("truncated", true, "windows", List.of(Map.of("ref", "h0", "role",
                "window", "name", "W", "children", children)));
        String text = UiSnapshotText.render(snapshot, null, 500);
        assertTrue(text.length() <= 500 + 30, String.valueOf(text.length()));
        assertTrue(text.endsWith("more lines)"), text);
        String full = UiSnapshotText.render(snapshot, null, 1_000_000);
        assertTrue(full.endsWith("(snapshot truncated)\n"), full.substring(full.length() - 40));
        assertEquals("", UiSnapshotText.render(null, null));
        assertEquals("", UiSnapshotText.render("not a snapshot", List.of()));
    }

    /** phase4-contracts.md 6.2: the WEB surface after the Swing windows. */
    @Test
    void rendersTheWebSurfaceAfterTheWindows() {
        Map<String, Object> front = new LinkedHashMap<>();
        front.put("ref", "h7");
        front.put("adapter", "jcef");
        front.put("url", "data:text/html,front");
        front.put("title", "Front");
        front.put("visibleInSwing", true);
        front.put("rendering", "OFFSCREEN_HEAVYWEIGHT");
        Object aria = Json.parse("""
                {"truncated": false, "nodes": [
                  {"role": "heading", "name": "Borrow", "level": 1},
                  {"role": "form", "name": "loan", "children": [
                    {"role": "textbox", "name": "Item", "value": "hammer"},
                    {"role": "spinbutton", "name": "Quantity", "value": 2},
                    {"role": "button", "name": "Borrow", "disabled": true}
                  ]},
                  {"role": "text", "name": "borrowed hammer x 2"}
                ]}
                """);
        Map<String, Object> back = Map.of("ref", "h9", "title", "Back", "url", "data:text/html,back",
                "visibleInSwing", false, "adapter", "jcef", "rendering", "OFFSCREEN_HEAVYWEIGHT");
        Map<String, Object> bare = Map.of("ref", "h11", "url", "", "visibleInSwing", true);
        Object snapshot = Map.of("windows", List.of(Map.of("ref", "h1", "role", "window", "name", "Web host")));
        String text = UiSnapshotText.render(snapshot, null, List.of(new UiSnapshotText.WebView(front, aria, "cdp"),
                new UiSnapshotText.WebView(back, null, null), new UiSnapshotText.WebView(bare, null, null)),
                UiSnapshotText.DEFAULT_BUDGET);
        assertEquals("""
                - window "Web host" [ref=h1]
                [web h7] Front data:text/html,front (jcef, OFFSCREEN_HEAVYWEIGHT, cdp)
                  heading "Borrow"
                  form "loan"
                    textbox "Item" [hammer]
                    spinbutton "Quantity" [2]
                    button "Borrow"
                  text "borrowed hammer x 2"
                [web h9 hidden] Back data:text/html,back
                [web h11]
                """, text);
        assertEquals("", UiSnapshotText.render(null, null, List.of(), 4000), "no browser, nothing");
        assertEquals("", UiSnapshotText.render(null, null, null, 4000));
    }

    @Test
    void theWebSurfaceStaysWithinTheBudgetAndLongValuesAreCut() {
        List<Object> nodes = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            nodes.add(Map.of("role", "listitem", "name", "item " + i));
        }
        Map<String, Object> browser = Map.of("ref", "h7", "title", "T", "url", "data:text/html;base64,"
                + "A".repeat(500), "visibleInSwing", true);
        String text = UiSnapshotText.render(null, null, List.of(new UiSnapshotText.WebView(browser, Map.of("nodes",
                nodes, "truncated", true), "agent")), UiSnapshotText.DEFAULT_BUDGET);
        assertTrue(text.length() <= UiSnapshotText.DEFAULT_BUDGET + 30, String.valueOf(text.length()));
        assertTrue(text.startsWith("[web h7] T data:text/html;base64,AAAA"), text);
        assertTrue(text.lines().findFirst().orElseThrow().length() < 140, "the url is cut: " + text.lines()
                .findFirst().orElseThrow());
        assertTrue(text.endsWith("more lines)"), text.substring(text.length() - 40));
        String all = UiSnapshotText.render(null, null, List.of(new UiSnapshotText.WebView(browser, Map.of("nodes",
                nodes, "truncated", true), "agent")), 1_000_000);
        assertTrue(all.endsWith("  listitem \"item 299\"\n  (aria snapshot truncated)\n"), all.substring(
                all.length() - 60));
    }

    @Test
    void controlCharactersFromTheTargetAreMadeVisible() {
        // Security finding S26: ESC sequences, a bare CR, BEL, DEL, C1 and bidi overrides in any rendered value.
        String hostile = "OK\u001b]52;c;aGk=\u0007\u001b[2K\rPASS\u009b\u007f\u202e";
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("ref", "h1\u001b[1m");
        node.put("role", "button\u0007");
        node.put("name", hostile);
        node.put("text", "line\u2028two");
        node.put("value", hostile);
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("key\u001b[31m", Map.of("v", hostile));
        String text = UiSnapshotText.render(Map.of("windows", List.of(node)), app);
        for (char c : text.toCharArray()) {
            assertTrue(c == '\n' || (c >= 0x20 && c != 0x7f && !(c >= 0x80 && c <= 0x9f) && c != '\u2028'
                    && c != '\u202e'), "raw control character U+" + Integer.toHexString(c) + " in:\n" + text);
        }
        assertTrue(text.contains("button\\u0007 \"OK\\u001b]52;c;aGk=\\u0007\\u001b[2K PASS\\u009b"), text);
        assertTrue(text.contains("[ref=h1\\u001b[1m]"), text);
        assertTrue(text.contains("key\\u001b[31m: "), text);
    }
}
