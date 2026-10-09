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
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hook installed, fired and removed on a toy class of the test's own loader (phase6-contracts.md 3.2, 3.5). */
class InstallFireRemoveTest {

    private Harness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void anObserveHookFiresOnTheCallersThreadAndIsRestoredByteForByte() throws Exception {
        DepositRule rule = new DepositRule();
        assertEquals(new BigDecimal("20.00"), rule.depositFor("T1"));
        harness = new Harness();
        assertEquals("OK", harness.ctx.capabilities.get(HooksModule.ENGINE).state().name(),
                String.valueOf(harness.ctx.capabilities.get(HooksModule.ENGINE)));
        Map<String, Object> result = harness.install("toys", spec("deposit.computed", HookMode.OBSERVE,
                DepositRule.class, "depositFor"));
        List<Object> hooks = (List<Object>) result.get("hooks");
        assertEquals("OK", ((Map<String, Object>) hooks.get(0)).get("state"), String.valueOf(result));
        assertEquals("hook.deposit.computed", ((Map<String, Object>) hooks.get(0)).get("capability"));
        assertEquals(1, ((List<Object>) result.get("touched")).size(), String.valueOf(result));
        assertTrue(Harness.slotHeld(), "the handler holds the slot");
        assertEquals("OK", harness.ctx.capabilities.get("hook.deposit.computed").state().name());

        assertEquals(new BigDecimal("40.00"), rule.depositFor("P7"), "behaviour unchanged");
        Event event = harness.ctx.events.last("hook.deposit.computed");
        assertNotNull(event, "the hook fired");
        Map<String, Object> payload = event.payload();
        assertEquals("deposit.computed", payload.get("hook"));
        assertEquals(DepositRule.class.getName(), payload.get("class"));
        assertEquals("depositFor", payload.get("method"));
        assertEquals("(Ljava/lang/String;)Ljava/math/BigDecimal;", payload.get("descriptor"));
        assertEquals(Thread.currentThread().getName(), payload.get("thread"));
        assertTrue(((Number) payload.get("durationNs")).longValue() >= 0);
        assertEquals(Boolean.FALSE, payload.get("stubbed"));
        assertFalse(payload.containsKey("args"), "OBSERVE carries no values");
        assertEquals("hooks", event.source());

        Map<String, Object> removed = harness.ctx.map("hook.remove", FakeHookContext.map());
        List<Object> restored = (List<Object>) removed.get("restored");
        assertEquals(1, restored.size(), String.valueOf(removed));
        Map<String, Object> line = (Map<String, Object>) restored.get(0);
        assertEquals("OK", line.get("verified"), String.valueOf(line));
        assertEquals(0L, ((Number) line.get("foreignEvents")).longValue());
        assertFalse(Harness.slotHeld(), "the slot is released once the ledger is empty");
        assertTrue(harness.ctx.capabilities.get("hook.deposit.computed") == null, "the capability is withdrawn");
        int before = Harness.events(harness.ctx, "hook.deposit.computed").size();
        rule.depositFor("T2");
        assertEquals(before, Harness.events(harness.ctx, "hook.deposit.computed").size(), "no event after remove");
        Event removedEvent = harness.ctx.events.last(Topics.HOOKS_REMOVED);
        assertEquals("OK", removedEvent.payload().get("verified"));
    }
}
