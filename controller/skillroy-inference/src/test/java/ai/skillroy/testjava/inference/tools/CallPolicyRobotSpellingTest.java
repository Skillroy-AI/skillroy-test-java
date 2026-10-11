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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fix wave of the Phase 7 review (FB, L1-F1 = L2-F3): {@code CallPolicy} reads {@code via} and {@code mode} with
 * the scenario module's one predicate ({@code Plan.robotValue}), a superset of what the agent reads as {@code ROBOT}
 * (the trimmed value upper-cased). {@link String#strip()} alone leaves a leading or trailing control character such as
 * U+0001 in place while the agent's {@link String#trim()} drops it, so a model's raw {@code swing_pointer} spelled that
 * way passed the policy (and, with the person's {@code --allow-robot} and the agent option, the session's origin-free
 * gate) and acted through Robot: the one guarantee "never a model's per-call choice, even with both flags" (phase7
 * 3.4, 11 rule 5) fell to a spelling.
 */
class CallPolicyRobotSpellingTest {

    @Test
    void aControlCharacterBeforeRobotIsStillRobot() {
        for (String spelling : List.of("\u0001ROBOT", "robot\u0000", "\u001b Robot", "\u0008ROBOT\u000e")) {
            assertNotNull(CallPolicy.arguments("swing.pointer", Map.of("via", spelling)), "via " + spelling.strip());
            assertNotNull(CallPolicy.arguments("swing.capture", Map.of("mode", spelling), true),
                    "mode " + spelling.strip() + " whatever the flag");
        }
        assertNull(CallPolicy.arguments("swing.pointer", Map.of("via", "\u0001ROBOT"), true),
                "the facade's V3 route under the host's flag, as for ROBOT");
        assertNull(CallPolicy.arguments("swing.pointer", Map.of("via", "\u0001ROUTED")));
    }
}
