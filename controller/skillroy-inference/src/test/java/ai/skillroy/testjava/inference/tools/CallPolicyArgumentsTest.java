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

package ai.skillroy.testjava.inference.tools;

import ai.skillroy.testjava.inference.spi.ToolSpec;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 9.1 and 9.4 (D294, D341, D347): a person's arguments are refused for a client or a model, never
 * stripped: {@code redact} other than {@code true} and {@code mode: ROBOT} on {@code swing.capture}, {@code force} other
 * than {@code false} on the five acting primitives ({@code web.pointer} among them), {@code via: ROBOT} on
 * {@code swing.pointer} unless the host's robot flag is set; and the raw tools of the web host's two new primitives
 * follow the projection's rules: {@code web_hit_test} a read, {@code web_pointer} offered only under a mutating policy.
 */
class CallPolicyArgumentsTest {

    @Test
    void forceIsAPersonsArgumentOnTheFiveActingPrimitives() {
        assertEquals(List.of("swing.click", "swing.key", "swing.pointer", "swing.type", "web.pointer"),
                CallPolicy.FORCE_PRIMITIVES.stream().sorted().toList());
        for (String primitive : CallPolicy.FORCE_PRIMITIVES) {
            String why = CallPolicy.arguments(primitive, Map.of("force", true));
            assertNotNull(why, primitive);
            assertTrue(why.contains(primitive) && why.contains("force"), why);
            assertNull(CallPolicy.arguments(primitive, Map.of("force", false)), "false is the default: " + primitive);
            assertNull(CallPolicy.arguments(primitive, Map.of()), primitive);
        }
        assertNull(CallPolicy.arguments("web.hitTest", Map.of("force", true)), "a read has no such argument to refuse");
    }

    @Test
    void aCaptureIsRedactedAndNeverOfTheGlassAndRobotInputNeedsTheHostsFlag() {
        assertNotNull(CallPolicy.arguments("swing.capture", Map.of("redact", false)));
        assertNotNull(CallPolicy.arguments("swing.capture", Map.of("redact", "no")));
        assertNull(CallPolicy.arguments("swing.capture", Map.of("redact", true)));
        assertNotNull(CallPolicy.arguments("swing.capture", Map.of("mode", "ROBOT"), true), "whatever the flag");
        assertNotNull(CallPolicy.arguments("swing.pointer", Map.of("via", "ROBOT")));
        assertNull(CallPolicy.arguments("swing.pointer", Map.of("via", "ROBOT"), true), "the facade's V3 route");
        assertNull(CallPolicy.arguments("swing.pointer", Map.of("via", "ROUTED")));
    }

    /**
     * Phase 7 security fix S110 (phase7-contracts.md 11, the clipboard rule; 9.1): a paste, copy or cut chord on
     * {@code swing.key} and a middle button on a pointer are refused for a client or a model, read in every spelling the
     * agent reads ({@code Keys.code}: trimmed, upper-cased, an optional {@code VK_}; {@code CONTROL} for {@code CTRL});
     * the reason names the argument, never a value; ordinary chords pass.
     */
    @Test
    void aClipboardChordAndAMiddleButtonAreAPersonsOnly() {
        List<Map<String, Object>> refused = List.of(
                Map.of("key", "V", "modifiers", List.of("CTRL")),
                Map.of("key", " vk_v ", "modifiers", List.of("control")),
                Map.of("key", "v", "modifiers", List.of("META")),
                Map.of("key", "V", "modifiers", List.of("CTRL", "SHIFT")),
                Map.of("key", "INSERT", "modifiers", List.of("SHIFT")),
                Map.of("key", "PASTE"),
                Map.of("key", "VK_PASTE", "modifiers", List.of("ALT")),
                Map.of("key", "C", "modifiers", List.of("CTRL")),
                Map.of("key", "X", "modifiers", List.of("META")),
                Map.of("key", "INSERT", "modifiers", List.of("CTRL")),
                Map.of("key", "DELETE", "modifiers", List.of("SHIFT")),
                Map.of("key", "COPY"),
                Map.of("key", "CUT"),
                Map.of("key", "V", "modifiers", "CTRL"));
        for (Map<String, Object> args : refused) {
            String why = CallPolicy.arguments("swing.key", args);
            assertNotNull(why, String.valueOf(args));
            assertTrue(why.contains("swing.key") && why.contains("clipboard"), why);
        }
        for (Map<String, Object> args : List.of(
                Map.<String, Object>of("key", "S", "modifiers", List.of("CTRL")),
                Map.<String, Object>of("key", "TAB", "modifiers", List.of("SHIFT")),
                Map.<String, Object>of("key", "F2"),
                Map.<String, Object>of("key", "V"),
                Map.<String, Object>of("key", "INSERT"),
                Map.<String, Object>of("key", "DELETE"),
                Map.<String, Object>of("key", "V", "modifiers", List.of("SHIFT")))) {
            assertNull(CallPolicy.arguments("swing.key", args), String.valueOf(args));
        }
        for (String pointer : List.of("swing.pointer", "swing.click", "web.pointer")) {
            for (Object middle : List.of("MIDDLE", " middle ")) {
                String why = CallPolicy.arguments(pointer, Map.of("action", "CLICK", "button", middle));
                assertNotNull(why, pointer + " " + middle);
                assertTrue(why.contains("MIDDLE"), why);
            }
            assertNull(CallPolicy.arguments(pointer, Map.of("action", "CLICK", "button", "RIGHT")), pointer);
        }
        assertNotNull(CallPolicy.arguments("swing.pointer", Map.of("action", "PRESS", "button", "MIDDLE"), true),
                "whatever the robot flag");
    }

    /**
     * Phase 7 security fix S111 (phase7-contracts.md 3.6, 4.8, 9.1): {@code redact} other than {@code true} on the four
     * web reads of a masked browser is a person's argument.
     */
    @Test
    void aMaskedBrowsersPageIsReadUnmaskedByAPersonOnly() {
        for (String read : List.of("web.bitmap", "web.dom.text", "web.ariaSnapshot", "web.hitTest")) {
            String why = CallPolicy.arguments(read, Map.of("redact", false));
            assertNotNull(why, read);
            assertTrue(why.contains(read) && why.contains("redact: false"), why);
            assertNotNull(CallPolicy.arguments(read, Map.of("redact", "false")), read);
            assertNull(CallPolicy.arguments(read, Map.of("redact", true)), read);
            assertNull(CallPolicy.arguments(read, Map.of()), read);
        }
        assertEquals(java.util.Set.of("web.bitmap", "web.dom.text", "web.ariaSnapshot", "web.hitTest", "web.pointer",
                "web.dom.click", "web.dom.fill", "web.dispatchEvent"), CallPolicy.MASKED_WEB_READS);
    }

    /**
     * Phase 8 (phase8-contracts.md 5.2 (iv), D637): the answer of {@code web.pointer} of a masked browser carries no page
     * data unless {@code redact: false}, a person's argument; and (Phase 8 security fix S165, FE) {@code web.dom.click},
     * {@code web.dom.fill} and {@code web.dispatchEvent} with a selector target are refused on a masked browser unless
     * {@code redact: false}: refused for a model or a client, as on the four reads.
     */
    @Test
    void aMaskedBrowsersActionAnswersKeepTheirPageDataForAPersonOnly() {
        Map<String, Map<String, Object>> calls = Map.of(
                "web.pointer", Map.of("action", "CLICK", "point", Map.of("x", 1, "y", 1)),
                "web.dom.click", Map.of("selector", "#go"),
                "web.dom.fill", Map.of("selector", "#go", "text", "x"),
                "web.dispatchEvent", Map.of("name", "desk.reset", "target", "#go"));
        for (Map.Entry<String, Map<String, Object>> c : calls.entrySet()) {
            String primitive = c.getKey();
            Map<String, Object> unredacted = new java.util.LinkedHashMap<>(c.getValue());
            unredacted.put("redact", false);
            String why = CallPolicy.arguments(primitive, unredacted);
            assertNotNull(why, primitive);
            assertTrue(why.contains(primitive) && why.contains("redact: false"), why);
            unredacted.put("redact", "false");
            assertNotNull(CallPolicy.arguments(primitive, unredacted), primitive);
            assertNull(CallPolicy.arguments(primitive, c.getValue()), primitive + " without redact is allowed");
            Map<String, Object> redacted = new java.util.LinkedHashMap<>(c.getValue());
            redacted.put("redact", true);
            assertNull(CallPolicy.arguments(primitive, redacted), primitive);
        }
    }

    @Test
    void theWebHostsHitTestIsAReadAndItsPointerAMutatingTool() {
        List<PrimitiveDescriptor> primitives = List.of(
                new PrimitiveDescriptor("web.hitTest", "hit-tests a page", null, null, "WORKER", 10_000, "web.hittest",
                        false, false),
                new PrimitiveDescriptor("web.pointer", "points in a page", null, null, "WORKER", 10_000, "web.pointer",
                        true, false));
        List<String> readOnly = ToolProjection.project(primitives, ToolProjectionTest.pack(false, null),
                ToolProjection.Policy.readOnly()).stream().map(ToolSpec::name).toList();
        assertTrue(readOnly.contains("web_hit_test"), readOnly.toString());
        assertFalse(readOnly.contains("web_pointer"), "a mutating primitive needs --mutating: " + readOnly);
        List<String> mutating = ToolProjection.project(primitives, ToolProjectionTest.pack(false, null),
                ToolProjection.Policy.mutating()).stream().map(ToolSpec::name).toList();
        assertTrue(mutating.containsAll(List.of("web_hit_test", "web_pointer")), mutating.toString());
    }
}
