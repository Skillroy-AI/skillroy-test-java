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

package ai.skillroy.testjava.agent.byteman;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The options the module shares with the hooks module (phase6-contracts.md 3.7 as built, D176): one key, one range.
 * Fix wave (L1-F4, L2-F4): {@code hooks.maxClasses} is 1 to 1024 here as in the hooks module, not 1 to 4096.
 */
class SettingsTest {

    private static Settings read(String key, String value) {
        FakeAgentContext ctx = new FakeAgentContext(null);
        ctx.options.put(key, value);
        return Settings.read(ctx);
    }

    private static void refused(String key, String value, String message) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> read(key, value));
        assertEquals(message, e.getMessage());
    }

    @Test
    void theDefaultsAreThoseOfThreeSeven() {
        Settings s = Settings.read(new FakeAgentContext(null));
        assertEquals(64, s.maxClasses);
        assertEquals(262144L, s.maxClassBytes);
        assertEquals(2000, s.maxMethods);
        assertEquals(8000L, s.detachBudgetMs);
        assertEquals(16384, s.captureMaxBytes);
        assertEquals(null, s.bytemanJar);
    }

    @Test
    void theRangesAreTheHooksModulesRanges() {
        assertEquals(1024, read(Settings.MAX_CLASSES, "1024").maxClasses);
        assertEquals(1, read(Settings.MAX_CLASSES, "1").maxClasses);
        refused(Settings.MAX_CLASSES, "1025", "option hooks.maxClasses is 1025, outside 1 to 1024");
        refused(Settings.MAX_CLASSES, "0", "option hooks.maxClasses is 0, outside 1 to 1024");
        refused(Settings.MAX_CLASSES, "many", "option hooks.maxClasses is not a number: 'many'");
        refused(Settings.MAX_CLASS_BYTES, "1023", "option hooks.maxClassBytes is 1023, outside 1024 to 16777216");
        refused(Settings.MAX_CLASS_BYTES, "16777217",
                "option hooks.maxClassBytes is 16777217, outside 1024 to 16777216");
        refused(Settings.MAX_METHODS, "65536", "option hooks.maxMethods is 65536, outside 1 to 65535");
        refused(Settings.DETACH_BUDGET_MS, "999", "option hooks.detachBudgetMs is 999, outside 1000 to 9000");
        refused(Settings.DETACH_BUDGET_MS, "9001", "option hooks.detachBudgetMs is 9001, outside 1000 to 9000");
        refused(Settings.CAPTURE_MAX_BYTES, "1048577",
                "option hooks.capture.maxBytes is 1048577, outside 1024 to 1048576");
    }
}
