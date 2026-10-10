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
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restore that leaves a reference to {@code Spy} (phase6-contracts.md section 3.5): a transformer the test keeps
 * registered plants the advised bytes back, so the restore reports {@code HOOK_VERIFY_FAILED}, the class keeps its
 * ledger entry, the hook is disarmed and in ERROR, and the handler stays installed (D117). By design the slot stays
 * held for the JVM's life, so this runs in a JVM of its own.
 */
class PlantedReferenceFreshJvmTest {

    /** A JVM of its own (section 2.3 as built): skipped under a merging -Dtest, a failure otherwise. */
    @BeforeAll
    static void freshJvm() {
        Harness.assumeFreshJvm();
    }

    /** Keeps the advised bytes it sees and plants them back at the next retransformation. */
    static final class Planter implements ClassFileTransformer {
        volatile byte[] advised;
        volatile boolean plant;

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined != DepositRule.class) {
                return null;
            }
            if (!plant) {
                advised = classfileBuffer.clone();
                return null;
            }
            return advised;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPlantedReferenceIsHookVerifyFailedAndTheEntryAndTheHandlerStay() throws Exception {
        DepositRule rule = new DepositRule();
        Harness harness = new Harness();
        harness.install("toys", spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"));
        Planter planter = new Planter();
        Harness.inst().addTransformer(planter, true);
        Harness.inst().retransformClasses(DepositRule.class);
        assertNotNull(planter.advised, "the planter saw the advised bytes after the hook transformer");
        planter.plant = true;
        RpcException failed = harness.ctx.error("hook.remove", map());
        assertEquals(ErrorCodes.HOOK_VERIFY_FAILED, failed.error().code());
        List<Object> classes = (List<Object>) failed.error().data().get("classes");
        Map<String, Object> line = (Map<String, Object>) classes.get(0);
        assertEquals(DepositRule.class.getName(), line.get("class"));
        assertEquals("system", line.get("loader"));
        assertTrue(((Number) line.get("bytesAfter")).longValue() > 0);
        assertEquals(1L, ((Number) line.get("foreignEvents")).longValue(), "the test's own retransformation");
        assertEquals(1, harness.engine().ledger.size(), "a FAILED class keeps its ledger entry");
        assertTrue(Harness.slotHeld(), "and the handler stays installed");
        Map<String, Object> health = (Map<String, Object>) ((Map<String, Object>) harness.ctx.map("hook.health",
                map()).get("hooks")).get("deposit.computed");
        assertEquals("ERROR", health.get("state"));
        assertEquals("residue after remove", health.get("reason"));
        assertNull(harness.ctx.capabilities.get("hook.deposit.computed"), "withdrawn");
        assertEquals("FAILED", harness.ctx.events.last(Topics.HOOKS_REMOVED).payload().get("verified"));
        int events = harness.ctx.events.topic("hook.deposit.computed").size();
        rule.depositFor("T1");
        assertEquals(events, harness.ctx.events.topic("hook.deposit.computed").size(), "the hook is disarmed");
        // Fix wave (FA, D236): while the class may carry the residue, a hook of the same id is refused.
        Map<String, Object> again = (Map<String, Object>) ((List<Object>) harness.ctx.map("hook.install", map("specs",
                Harness.json(spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor")),
                "catalogue", map("id", "again", "hash", "sha256:a2"))).get("hooks")).get(0);
        assertEquals("UNAVAILABLE", again.get("state"));
        assertTrue(String.valueOf(again.get("reason")).startsWith("a residue of hook deposit.computed is left"),
                String.valueOf(again));
        assertEquals(1, harness.engine().ledger.size(), "nothing retransformed");
        Harness.inst().removeTransformer(planter);
        harness.module.uninstall();
    }
}
