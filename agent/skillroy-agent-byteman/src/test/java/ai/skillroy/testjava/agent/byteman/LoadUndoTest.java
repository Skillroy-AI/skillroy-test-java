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

import ai.skillroy.testjava.agent.byteman.sys.RuleHelper;
import ai.skillroy.testjava.fixture.byteman.DepositDesk;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.Topics;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.skillroy.testjava.agent.byteman.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 6 security fix, pass 2: a load that cannot arm its handler is undone (P2-L2-F2), a removal whose
 * retransformation throws is answered (P2-L2-F1), and an unloaded rule's names are no longer recorded (P2-L2-F5).
 *
 * <ul>
 *   <li>Another handler (A12, a public static on the system class path) holds the helper's slot before the agent loads
 *       Byteman. Before the fix the first {@code rule.submit} was refused but the engine kept the {@code Retransformer},
 *       so the second installed a rule whose firings reached the squatter and never {@code rule.fired}, while
 *       {@code rule.engine} said {@code OK}. Now the load is undone on the fresh path and on the reuse path alike, the
 *       {@code Retransformer} is parked, the probe answers {@code UNAVAILABLE} with the reason, and once the slot is
 *       free the next submit works through the reuse path.</li>
 *   <li>Another agent hands the JVM a malformed class file during {@code rule.unload}'s retransformation: Byteman has
 *       already dropped the script, the class keeps the injected code. Before the fix the
 *       {@code InvocationTargetException} left the engine (an {@code INTERNAL_ERROR}) and the module kept a record of a
 *       rule Byteman no longer had. Now {@code HOOK_VERIFY_FAILED} names the class as {@code FAILED} ("cannot be
 *       restored"), the record is dropped, and its names are recorded no more.</li>
 * </ul>
 * A test class of its own (a fresh JVM): Byteman stays loaded for the JVM's life.
 */
class LoadUndoTest {

    private static ClassFileTransformer malformed(final Class<?> type) {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader l, String name, Class<?> redefined, ProtectionDomain pd, byte[] b) {
                return redefined == type ? new byte[] {1, 2, 3} : null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void aHeldHelperSlotUndoesTheLoadAndAThrowingRemovalIsAnswered() throws Exception {
        FreshJvm.assume(false);
        DepositDesk desk = new DepositDesk();
        desk.ceiling("STAFF-1");
        final AtomicInteger squatted = new AtomicInteger();
        RuleHelper.Handler squatter = new RuleHelper.Handler() {
            @Override
            public void fired(String rule, String className, String method, Object[] bindings) {
                squatted.incrementAndGet();
            }
        };
        assertTrue(RuleHelper.install(squatter), "the squatter takes the free slot");

        FakeAgentContext ctx = DetachBudgetTest.context(8000);
        BytemanModule module = DetachBudgetTest.module(ctx);
        Engine engine = module.engine();
        // The fresh path: premain runs, the handler cannot arm, everything is undone, the Retransformer is parked.
        RpcException first = ctx.fails("rule.submit", args("rule", InProcessRouteTest.LIMIT));
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, first.error().code());
        assertEquals(Engine.SLOT_HELD, first.error().message());
        assertFalse(engine.loaded(), "no half-loaded engine");
        assertEquals("true", System.getProperty(Engine.LOADED_PROPERTY), "Byteman itself stays loaded (attach C4)");
        // The reuse path refuses alike (the engine directly: the dispatcher's gate would answer from the matrix).
        try {
            engine.submit(InProcessRouteTest.LIMIT);
            fail("the reuse path must refuse while the slot is held");
        } catch (RpcException e) {
            assertEquals(Engine.SLOT_HELD, e.error().message());
        }
        assertFalse(engine.loaded());
        Capability refused = ctx.capabilities.probe(BytemanModule.CAPABILITY);
        assertEquals(CapabilityState.UNAVAILABLE, refused.state());
        assertEquals(Engine.SLOT_HELD, refused.reason());
        assertEquals(false, engine.describe().get("loaded"), "rule.list (its capability gate refuses it now)");
        desk.ceiling("STAFF-2");
        assertEquals(0, squatted.get(), "no rule was installed: nothing reaches the squatter");

        // The slot frees: the parked Retransformer is reused and the rule works.
        assertTrue(RuleHelper.uninstall(squatter));
        Capability free = ctx.capabilities.probe(BytemanModule.CAPABILITY);
        assertEquals(CapabilityState.OK, free.state(), String.valueOf(free));
        assertEquals("in-process, reused", free.strategy());
        assertEquals(true, map(ctx.call("rule.submit", args("rule", InProcessRouteTest.LIMIT))).get("installed"));
        desk.ceiling("STAFF-7");
        assertEquals(1, ctx.bus.payloads(Topics.RULE_FIRED).size());
        assertEquals(0, squatted.get());
        assertTrue(engine.aheadRecorder().watches(RuleLintTest.TOY));

        // A removal whose retransformation throws: HOOK_VERIFY_FAILED naming the class, the record dropped.
        Instrumentation inst = TestAgent.instrumentation();
        ClassFileTransformer broken = malformed(DepositDesk.class);
        inst.addTransformer(broken, true);
        RpcException failed;
        try {
            failed = ctx.fails("rule.unload", args("name", "limit"));
        } finally {
            inst.removeTransformer(broken);
        }
        assertEquals(ErrorCodes.HOOK_VERIFY_FAILED, failed.error().code(), failed.error().message());
        assertTrue(failed.error().message().startsWith("the rule limit was removed, but the classes it touched cannot"
                + " be restored: "), failed.error().message());
        assertEquals(true, failed.error().data().get("removed"));
        List<?> classes = (List<?>) failed.error().data().get("classes");
        assertEquals(1, classes.size(), String.valueOf(classes));
        assertEquals(RuleLintTest.TOY, map(classes.get(0)).get("class"));
        assertEquals("FAILED", map(classes.get(0)).get("verified"));
        assertTrue(String.valueOf(map(classes.get(0)).get("reason")).startsWith("cannot be restored"));
        assertTrue(((List<?>) map(ctx.call("rule.list", args())).get("rules")).isEmpty(),
                "Byteman dropped the rule, and so does the module");
        assertFalse(engine.aheadRecorder().watches(RuleLintTest.TOY), "its names are recorded no more (P2-L2-F5)");
        assertFalse(engine.behindRecorder().watches(RuleLintTest.TOY));

        // The same rule again: installed, and its unload restores and verifies.
        ctx.call("rule.submit", args("rule", InProcessRouteTest.LIMIT));
        Map<String, Object> unloaded = map(ctx.call("rule.unload", args("name", "limit")));
        assertEquals("OK", map(((List<?>) unloaded.get("restored")).get(0)).get("verified"), unloaded.toString());
        assertFalse(engine.aheadRecorder().watches(RuleLintTest.TOY));
        module.uninstall();
        assertFalse(RuleHelper.held());
    }
}
