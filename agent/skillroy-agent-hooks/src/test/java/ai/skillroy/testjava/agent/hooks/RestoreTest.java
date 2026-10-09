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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.fixture.hooks.DepositRule;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restore's outcomes (phase6-contracts.md section 3.5, D122; BS3 RESULT section 3): OK, DEGRADED for another
 * transformer still present (BC1) and for a foreign redefinition (BC3, BC4, where on Java 8 the event count is the only
 * witness), a removal that leaves other hooks of the class in place, an unknown id, and {@code hook.remove} and
 * {@code hook.health} served while {@code hook.engine} is UNAVAILABLE. The planted reference to Spy and the detach
 * budget leave the slot held by design and run in JVMs of their own.
 */
class RestoreTest {

    private Harness harness;
    private final DepositRule rule = new DepositRule();
    private byte[] file;

    @BeforeEach
    void start() throws Exception {
        harness = new Harness();
        InputStream in = DepositRule.class.getResourceAsStream("DepositRule.class");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            out.write(buffer, 0, n);
        }
        in.close();
        file = out.toByteArray();
    }

    @AfterEach
    void close() throws Exception {
        harness.ctx.call("hook.remove", map());
        Harness.inst().redefineClasses(new ClassDefinition(DepositRule.class, file));
        harness.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> removeAll() throws Exception {
        Map<String, Object> removed = harness.ctx.map("hook.remove", map());
        List<Object> restored = (List<Object>) removed.get("restored");
        assertEquals(1, restored.size(), String.valueOf(removed));
        return (Map<String, Object>) restored.get(0);
    }

    @Test
    void anotherTransformerStillPresentAtTheRestoreIsDegraded() throws Exception {
        harness.install("toys", spec("rate.named", HookMode.OBSERVE, DepositRule.class, "rateName"));
        ForeignPatch foreign = new ForeignPatch(DepositRule.class, "rate:standard", "rate:STANDARD");
        Harness.inst().addTransformer(foreign, true);
        try {
            assertEquals("rate:standard", rule.rateName(), "registered, not applied yet");
            Map<String, Object> line = removeAll();
            assertEquals("DEGRADED", line.get("verified"), String.valueOf(line));
            assertEquals("bytes differ: another transformer is present", line.get("reason"));
            assertEquals(0L, ((Number) line.get("foreignEvents")).longValue());
            assertEquals("rate:STANDARD", rule.rateName(), "our restore ran the other agent's transformer");
        } finally {
            Harness.inst().removeTransformer(foreign);
        }
    }

    @Test
    void aRedefinitionWhileHookedIsAForeignEventEvenWhenTheBytesCompareEqual() throws Exception {
        harness.install("toys", spec("rate.named", HookMode.OBSERVE, DepositRule.class, "rateName"));
        Harness.inst().redefineClasses(new ClassDefinition(DepositRule.class, file));
        rule.rateName();
        assertTrue(Harness.events(harness.ctx, "hook.rate.named").size() >= 1, "still hooked after the redefine");
        Map<String, Object> line = removeAll();
        assertEquals("DEGRADED", line.get("verified"), String.valueOf(line));
        assertEquals("foreign redefinition observed: 1 events", line.get("reason"));
        assertEquals(1L, ((Number) line.get("foreignEvents")).longValue());
    }

    @Test
    void aChangedRedefinitionIsReportedAsWell() throws Exception {
        harness.install("toys", spec("rate.named", HookMode.OBSERVE, DepositRule.class, "rateName"));
        Harness.inst().redefineClasses(new ClassDefinition(DepositRule.class,
                ForeignPatch.patch(file, "rate:standard", "rate:redefine")));
        assertEquals("rate:redefine", rule.rateName());
        Map<String, Object> line = removeAll();
        assertEquals("DEGRADED", line.get("verified"), String.valueOf(line));
        assertTrue(((Number) line.get("foreignEvents")).longValue() >= 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void removingOneHookOfAClassKeepsTheOthersAndAnUnknownIdIsReported() throws Exception {
        harness.install("toys", spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                spec("deposit.waived", HookMode.OBSERVE, DepositRule.class, "waives"));
        Map<String, Object> partial = harness.ctx.map("hook.remove", map("ids", Arrays.asList("deposit.computed",
                "no.such.hook")));
        assertTrue(((List<Object>) partial.get("restored")).isEmpty(), "the class still carries a hook");
        List<Object> statuses = (List<Object>) partial.get("hooks");
        assertEquals("UNAVAILABLE", ((Map<String, Object>) statuses.get(0)).get("state"));
        assertEquals("not installed", ((Map<String, Object>) statuses.get(0)).get("reason"));
        assertEquals("OK", ((Map<String, Object>) statuses.get(1)).get("state"));
        assertEquals("removed", ((Map<String, Object>) statuses.get(1)).get("reason"));
        int computed = Harness.events(harness.ctx, "hook.deposit.computed").size();
        rule.depositFor("T1");
        rule.waives("member");
        assertEquals(computed, Harness.events(harness.ctx, "hook.deposit.computed").size());
        assertEquals(1, Harness.events(harness.ctx, "hook.deposit.waived").size());
        assertTrue(Harness.slotHeld());
        assertEquals("OK", removeAll().get("verified"));
        assertFalse(Harness.slotHeld());
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeAndHealthAreServedWhileTheEngineIsUnavailable() throws Exception {
        harness.install("toys", spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"));
        harness.ctx.capabilities.set(HooksModule.ENGINE, CapabilityState.UNAVAILABLE, "a reprobe said so", null);
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, harness.ctx.error("hook.probe", map("specs",
                Harness.json(spec("x", HookMode.OBSERVE, DepositRule.class, "waives")))).error().code());
        Map<String, Object> health = harness.ctx.map("hook.health", map());
        assertTrue(((Map<String, Object>) health.get("hooks")).containsKey("deposit.computed"));
        assertEquals("OK", removeAll().get("verified"));
    }
}
