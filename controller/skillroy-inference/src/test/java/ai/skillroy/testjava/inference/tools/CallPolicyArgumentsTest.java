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
