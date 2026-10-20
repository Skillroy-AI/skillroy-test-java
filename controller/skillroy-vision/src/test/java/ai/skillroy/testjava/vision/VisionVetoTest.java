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

package ai.skillroy.testjava.vision;

import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.vision.VisionHarness.done;
import static ai.skillroy.testjava.vision.VisionHarness.member;
import static ai.skillroy.testjava.vision.VisionHarness.tools;
import static ai.skillroy.testjava.vision.VisionHarness.use;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 (phase8-contracts.md 3.2, D507, 13.1 row 3): the recorder refuses a hit the agent flags {@code vetoed: true}
 * as it refuses a masked one, with {@link Recorder#VETOED}, before any step is built: the pointer on every gesture
 * ({@code click_at}, {@code drag}, {@code scroll}), {@code type} and {@code key} at a vetoed focus owner, and V2's raw
 * pointer halves; nothing is sent to the agent and nothing is journaled. A hit without the key (a 0.1.0 agent) is not
 * refused for that reason: the agent is the enforcer and the recorder's refusal a courtesy.
 */
class VisionVetoTest {

    @TempDir
    Path dir;

    private static final Map<String, String> V2_CUSTOM = Map.of(VisionOptions.TIER, "v2", VisionOptions.TOOLS,
            "custom");
    private static final Map<String, String> V2_TOOLSET = Map.of(VisionOptions.TIER, "v2");

    /** One response per call (a batch stops at its first refusal), then {@code done}. */
    private static List<ModelResponse> each(Content.ToolUse... uses) {
        List<ModelResponse> out = new ArrayList<>();
        int n = 0;
        for (Content.ToolUse u : uses) {
            out.add(tools("r" + (++n), u));
        }
        out.add(done("r" + (++n)));
        return out;
    }

    private static void nothingActed(VisionHarness h, String why) {
        for (String m : List.of("swing.click", "swing.key", "swing.pointer", "swing.type", "web.dom.click",
                "web.pointer")) {
            assertTrue(h.agent.bridge.callsTo(m).isEmpty(), why + ": " + m + " was sent");
        }
        assertTrue(h.steps().isEmpty(), why + ": nothing journaled");
    }

    /** The DUE key {@code h2} lies at image (20, 20) 60 x 30; the entry {@code h4} at (20, 70) 180 x 24. */
    @Test
    void everyPointerGestureAtAVetoedComponentIsRefused() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, V2_CUSTOM, each(
                use("t1", "click_at", Map.of("x", 50L, "y", 35L)),
                use("t2", "click_at", Map.of("x", 50L, "y", 35L, "count", 2L)),
                use("t3", "drag", Map.of("x1", 50L, "y1", 35L, "x2", 100L, "y2", 80L)),
                use("t4", "scroll", Map.of("x", 50L, "y", 35L, "direction", "down", "amount", 1L))))) {
            h.agent.vetoed.add("h2");
            Turn turn = h.service.ask("close the desk");
            assertEquals(4, turn.actions().size());
            for (int i = 0; i < 4; i++) {
                assertEquals(Recorder.VETOED, turn.actions().get(i).result(), turn.actions().get(i).tool());
            }
            nothingActed(h, "vetoed pointer");
        }
    }

    @Test
    void typeAndKeyAtAVetoedFocusOwnerAreRefused() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, V2_CUSTOM, each(
                use("t1", "type", Map.of("text", "drill")),
                use("t2", "key", Map.of("key", "ENTER"))))) {
            h.agent.focusOwner = "h4";
            h.agent.vetoed.add("h4");
            Turn turn = h.service.ask("type into the entry");
            assertEquals(Recorder.VETOED, turn.actions().get(0).result());
            assertEquals(Recorder.VETOED, turn.actions().get(1).result());
            nothingActed(h, "vetoed focus owner");
        }
    }

    @Test
    void theRawPointerHalvesRefuseAVetoedTarget() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, V2_TOOLSET, each(
                member("t1", "mouse_move", Map.of("coordinate", List.of(50L, 35L))),
                member("t2", "left_mouse_down", Map.of()),
                member("t3", "left_mouse_up", Map.of())))) {
            h.agent.vetoed.add("h2");
            Turn turn = h.service.ask("press there");
            for (int i = 0; i < 3; i++) {
                assertEquals(Recorder.VETOED, turn.actions().get(i).result(), turn.actions().get(i).tool());
            }
            nothingActed(h, "raw pointer");
        }
    }

    @Test
    void aHitWithoutTheKeyIsNotRefusedForIt() throws Exception {
        try (VisionHarness h = new VisionHarness(dir, V2_CUSTOM, each(use("t1", "click_at", Map.of("x", 50L,
                "y", 35L))))) {
            Turn turn = h.service.ask("press DUE");
            assertNotEquals(Recorder.VETOED, turn.actions().get(0).result());
            assertFalse(h.agent.bridge.callsTo("swing.click").isEmpty() && h.agent.bridge.callsTo("swing.pointer")
                    .isEmpty(), "a hit without vetoed acts as before (a 0.1.0 agent)");
        }
    }
}
