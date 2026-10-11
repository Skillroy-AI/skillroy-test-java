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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one Robot predicate of the controller (phase7-contracts.md 3.4, 9.2, 9.3; the Phase 7 review's fix wave, FB,
 * L1-F1 = L2-F3): {@link Plan#robotValue} accepts every spelling the agent reads as {@code ROBOT} after the wire (the
 * agent upper-cases the trimmed value), so that no controller gate can be passed by spelling; a raw {@code call:} step
 * spelled {@code via: robot} or {@code mode: Robot} makes the plan a Robot plan whose {@code requires} names
 * {@code swing.pointer.robot}; and {@link Plan#unredacted} refuses a {@code redact} that is present and not the boolean
 * {@code true}.
 */
class RobotValueTest {

    private final ToyPack pack = new ToyPack();

    /** How the agent reads {@code via} and {@code mode}: the value after the JSON wire, trimmed and upper-cased. */
    private static boolean agentReadsRobot(String value) {
        Map<String, Object> wire = Json.parseObject(Json.write(Map.of("via", value)));
        return "ROBOT".equals(String.valueOf(wire.get("via")).trim().toUpperCase(Locale.ROOT));
    }

    @Test
    void everySpellingTheAgentReadsAsRobotIsRobot() {
        for (String spelling : List.of("ROBOT", "robot", "Robot", " ROBOT", "ROBOT ", "\tRobot\n", "\u0001ROBOT",
                "\u0000robot\u001b", "\u000bROBOT\u0008", "\u2003ROBOT\u3000")) {
            assertTrue(Plan.robotValue(spelling), escaped(spelling));
        }
        for (Object other : List.of("ROUTED", "ROBOTS", "RO BOT", "", " ", "\u00a0ROBOT", "R0BOT", 1L, true,
                Map.of("via", "ROBOT"), List.of("ROBOT"))) {
            assertFalse(Plan.robotValue(other), String.valueOf(other));
        }
        assertFalse(Plan.robotValue(null), "absent");
        // Every single character before and after the word, in both cases: whatever the agent reads as ROBOT, the
        // controller's gates read as ROBOT too (the superset rule).
        int checked = 0;
        for (char c = 0; c < 0xFFFF; c++) {
            if (Character.isSurrogate(c)) {
                continue;
            }
            for (String spelling : List.of(c + "robot", "ROBOT" + c, c + "Robot" + c)) {
                if (agentReadsRobot(spelling)) {
                    assertTrue(Plan.robotValue(spelling), escaped(spelling));
                    checked++;
                }
            }
        }
        assertTrue(checked >= 3 * 33, "at least U+0000 to U+0020 on each side: " + checked);
    }

    @Test
    void aStringFalseOrAnyRedactButTrueIsUnredacted() {
        assertTrue(Plan.unredacted(Map.of("redact", false)));
        assertTrue(Plan.unredacted(Map.of("redact", "false")), "the agent coerces the string");
        assertTrue(Plan.unredacted(Map.of("redact", "true")), "strict: only the boolean true is redacted");
        Map<String, Object> nullRedact = new HashMap<>();
        nullRedact.put("redact", null);
        assertTrue(Plan.unredacted(nullRedact));
        assertFalse(Plan.unredacted(Map.of("redact", true)));
        assertFalse(Plan.unredacted(Map.of()), "absent: the agent's default is true");
        assertFalse(Plan.unredacted(null));
    }

    @Test
    void aRawCallSpellingRobotLooselyIsARobotPlanThatRequiresTheRobotId() throws Exception {
        for (String via : List.of("robot", "\" Robot\"", "\"\\u0001ROBOT\"")) {
            Plan plan = compile("steps:\n  - call: { primitive: swing.pointer, args: { action: CLICK, point: { x: 1, y:"
                    + " 1 }, via: " + via + " } }\n");
            assertTrue(plan.usesRobot(), via);
            assertTrue(plan.requires().contains("swing.pointer.robot"), via + ": " + plan.requires());
        }
        Plan glass = compile("steps:\n  - call: { primitive: swing.capture, args: { mode: robot } }\n");
        assertTrue(glass.usesRobot(), "a glass capture spelled in lower case");
        Plan padded = compile("steps:\n  - poke: {}\n  - call: { primitive: swing.capture, args: { mode: \" ROBOT \" }"
                + " }\n");
        assertTrue(padded.usesRobot(), "a glass capture padded with blanks");
        Plan routed = compile("steps:\n  - call: { primitive: swing.pointer, args: { action: CLICK, point: { x: 1, y:"
                + " 1 }, via: routed } }\n");
        assertFalse(routed.usesRobot());
        assertFalse(routed.requires().contains("swing.pointer.robot"));
    }

    private Plan compile(String body) throws ScenarioException {
        return PlanCompiler.compile(("scenario: t\npack: toy\n" + body).getBytes(StandardCharsets.UTF_8), "t.yaml",
                pack);
    }

    private static String escaped(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            out.append(c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        }
        return out.toString();
    }
}
