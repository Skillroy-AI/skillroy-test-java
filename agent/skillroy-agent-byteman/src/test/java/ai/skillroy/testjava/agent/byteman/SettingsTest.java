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

import ai.skillroy.testjava.agent.api.spi.hook.ClassDeny;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The options the module shares with the hooks module (phase6-contracts.md 3.7 as built, D176): one key, one range.
 * Fix wave (L1-F4, L2-F4): {@code hooks.maxClasses} is 1 to 1024 here as in the hooks module, not 1 to 4096.
 * Phase 8 (phase8-contracts.md 6.5, section 13.1 row 25): {@code hooks.deny} read and refused naming the key, and a
 * domain module's {@code ClassDeny} in the shared registry seen by the engine.
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

    @Test
    void theDenyOptionIsReadTrimmedAndCheckedNamingTheKey() {
        assertEquals(Collections.emptyList(), Settings.read(new FakeAgentContext(null)).deny);
        assertEquals(Arrays.asList("a.b.C", "x.y.**"), read(Settings.DENY, " a.b.C ,, x.y.** ,a.b.C").deny,
                "trimmed, empty items dropped, distinct");
        refused(Settings.DENY, "a.b.C,a..b", "option hooks.deny: pattern 1 an empty segment");
        refused(Settings.DENY, "a/b", "option hooks.deny: pattern 0 character '/' is not allowed");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            many.append(i == 0 ? "" : ",").append("a.b.C").append(i);
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> read(Settings.DENY, many.toString()));
        assertTrue(e.getMessage().startsWith("option hooks.deny: more than 64 patterns"), e.getMessage());
        assertEquals(64, read(Settings.DENY, many.substring(0, many.lastIndexOf(","))).deny.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aModulesClassDenyInTheSharedRegistryIsSeenByTheEngine() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext(null);
        ctx.options.put(Settings.DENY, "x.Other");
        ctx.spi.register(ClassDeny.class, new ClassDeny() {
            @Override
            public String refuse(String className) {
                return className.equals(RuleLintTest.TOY) ? "the lending module denies it" : null;
            }
        });
        Engine engine = new Engine(ctx, Settings.read(ctx), new File("none.jar"), getClass().getClassLoader(),
                ClassBound.chainOf(getClass().getClassLoader()));
        Map<String, Object> check = engine.check(RuleLintTest.rule("limit", "ceiling", "fired($0)"));
        assertEquals(false, check.get("ok"));
        assertEquals(Collections.singletonList(RuleLintTest.TOY + ": denied by a module: the lending module denies it"),
                check.get("errors"));
        assertEquals(false, check.get("typeChecked"));
        assertEquals("denied by the agent option hooks.deny: x.Other",
                engine.denyView().refuse("x.Other"), "the option first");
        Map<String, Object> deny = engine.denyInfo();
        assertEquals(Collections.singletonList("x.Other"), deny.get("option"));
        assertEquals(1L, deny.get("modules"));
        assertFalse(deny.containsKey("catalogue"), "no hooks module: no catalogue");
        ctx.spi.register(ClassDeny.class, new ClassDeny() {
            @Override
            public String refuse(String className) {
                throw new IllegalStateException("broken");
            }
        });
        String thrown = engine.denyView().refuse("a.b.C");
        assertTrue(thrown.startsWith("denied by a module: the deny policy ") && thrown.endsWith(
                " threw java.lang.IllegalStateException"), thrown);
        engine.denyView().refuse("a.b.D");
        int warned = 0;
        for (String line : ctx.log.lines) {
            if (line.contains("the deny policy") && line.contains("threw")) {
                warned++;
            }
        }
        assertEquals(1, warned, "logged once: " + ctx.log.all());
        List<String> none = Collections.emptyList();
        assertEquals(none, Settings.defaults().deny);
    }
}
