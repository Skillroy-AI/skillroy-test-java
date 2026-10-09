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

import java.io.File;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.byteman.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module's whole route in this JVM (phase6-contracts.md 7.3-7.5), with the test agent's {@code Instrumentation}:
 * the first {@code rule.submit} loads Byteman in process, the rule fires into {@code rule.fired} with its bindings
 * encoded, {@code rule.list} shows it, {@code rule.check} now type-checks, a type error and a {@code killJVM} rule are
 * refused, {@code rule.unload} restores with {@code verified: OK}; the uninstall parks the {@code Retransformer} and a
 * second module instance reuses it; a third finds the rule a cut-short uninstall left and refuses, while the rule keeps
 * running its action with no {@code rule.fired}. The second instance's uninstall is cut short by a call that holds the
 * module past its detach budget of 1000 ms (fix wave, L2-S4): the uninstall still returns within the budget, and the
 * state it leaves is 7.5's (the seam of D206 is played by {@code BytemanIT}). A test class of its own (a fresh JVM):
 * Byteman stays loaded for the JVM's life.
 */
class InProcessRouteTest {

    static final String LIMIT = RuleLintTest.rule("limit", "ceiling", "fired($0, $!)").replace("AT ENTRY", "AT EXIT");

    private static FakeAgentContext context() {
        assertNotNull(TestAgent.instrumentation(), "the test agent's Instrumentation");
        FakeAgentContext ctx = new FakeAgentContext(TestAgent.instrumentation());
        ctx.options.put(Settings.BYTEMAN_JAR, System.getProperty("skillroy.test.bytemanJar"));
        return ctx;
    }

    private static BytemanModule module(FakeAgentContext ctx) throws Exception {
        BytemanModule module = new BytemanModule(new File(System.getProperty("skillroy.test.sysJar")), null);
        module.install(ctx);
        return module;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> submit(FakeAgentContext ctx, String rule) throws Exception {
        return map(ctx.call("rule.submit", args("rule", rule)));
    }

    @Test
    void theWholeRoute() throws Exception {
        FreshJvm.assume(false);
        DepositDesk desk = new DepositDesk();
        assertEquals(new BigDecimal("150.00"), desk.ceiling("STAFF-1"));

        // ---- the first instance: load on the first submit, fire, list, check, refuse, unload
        FakeAgentContext ctx = context();
        BytemanModule first = module(ctx);
        Capability engine = ctx.capabilities.probe(BytemanModule.CAPABILITY);
        assertEquals("in-process", engine.strategy());
        assertEquals(false, map(ctx.call("rule.check", args("rule", LIMIT))).get("typeChecked"));

        Map<String, Object> submitted = submit(ctx, LIMIT);
        assertEquals("limit", submitted.get("name"));
        assertEquals(true, submitted.get("installed"));
        assertEquals("[{class=" + RuleLintTest.TOY + ", status=injected}]", String.valueOf(submitted.get("classes")));
        assertTrue(first.engine().loaded());
        assertEquals("true", System.getProperty(Engine.LOADED_PROPERTY));
        assertTrue(ctx.log.all().contains("byteman: appended"), ctx.log.all());

        assertEquals(new BigDecimal("150.00"), desk.ceiling("STAFF-7"), "the rule observes, the result is unchanged");
        List<Map<String, Object>> fired = ctx.bus.payloads(Topics.RULE_FIRED);
        assertEquals(1, fired.size(), fired.toString());
        assertEquals("limit", fired.get(0).get("rule"));
        assertEquals(RuleLintTest.TOY, fired.get(0).get("class"));
        assertEquals("ceiling", fired.get(0).get("method"));
        Map<String, Object> bindings = map(fired.get(0).get("bindings"));
        assertEquals(RuleLintTest.TOY, map(bindings.get("0")).get("class"), "$0 is a handle");
        assertEquals(desk, ctx.handles.resolve((String) map(bindings.get("0")).get("ref")));
        assertEquals("150.00", bindings.get("1"), "$! is the limit");

        Map<String, Object> list = map(ctx.call("rule.list", args()));
        assertEquals(true, list.get("loaded"));
        assertEquals(false, list.get("reused"));
        List<?> rules = (List<?>) list.get("rules");
        assertEquals(1, rules.size());
        assertEquals("limit", map(rules.get(0)).get("name"));
        assertEquals("[{class=" + RuleLintTest.TOY + ", status=compiled}]",
                String.valueOf(map(rules.get(0)).get("classes")), "compiled at the first trigger");

        Map<String, Object> checked = map(ctx.call("rule.check", args("rule", LIMIT)));
        assertEquals(true, checked.get("typeChecked"));
        assertEquals(true, checked.get("ok"), checked.toString());
        String typeError = LIMIT.replace("fired($0, $!)", "fired($!.noSuchMethod())").replace("RULE limit",
                "RULE broken");
        Map<String, Object> badCheck = map(ctx.call("rule.check", args("rule", typeError)));
        assertEquals(false, badCheck.get("ok"));
        assertTrue(String.valueOf(badCheck.get("errors")).contains("noSuchMethod"), badCheck.toString());
        RpcException rejected = ctx.fails("rule.submit", args("rule", typeError));
        assertEquals(ErrorCodes.RULE_REJECTED, rejected.error().code());
        assertTrue(String.valueOf(rejected.error().data().get("errors")).contains("noSuchMethod"));
        RpcException kill = ctx.fails("rule.submit", args("rule", RuleLintTest.rule("kill", "ceiling", "killJVM()")));
        assertEquals(ErrorCodes.RULE_REJECTED, kill.error().code());
        assertTrue(String.valueOf(kill.error().data().get("errors")).contains("'killJVM' is not allowed"));
        RpcException entryReturn = ctx.fails("rule.submit", args("rule", RuleLintTest.rule("early", "ceiling",
                "fired($0, $!)")));
        assertEquals(ErrorCodes.RULE_REJECTED, entryReturn.error().code(), "a type warning refuses: AT ENTRY has no $!");

        Map<String, Object> unloaded = map(ctx.call("rule.unload", args("name", "limit")));
        assertEquals(true, unloaded.get("removed"));
        List<?> restored = (List<?>) unloaded.get("restored");
        assertEquals(1, restored.size(), restored.toString());
        assertEquals(RuleLintTest.TOY, map(restored.get(0)).get("class"));
        assertEquals("OK", map(restored.get(0)).get("verified"), restored.toString());
        assertEquals(0L, map(restored.get(0)).get("foreignEvents"));
        desk.ceiling("STAFF-8");
        assertEquals(1, ctx.bus.payloads(Topics.RULE_FIRED).size(), "no event after the unload");
        assertEquals(false, map(ctx.call("rule.unload", args("name", "limit"))).get("removed"));
        List<Map<String, Object>> changed = ctx.bus.payloads(Topics.RULES_CHANGED);
        assertEquals(Collections.singletonList("limit"), changed.get(0).get("installed"));
        assertEquals(Collections.singletonList("limit"), changed.get(changed.size() - 1).get("removed"));

        first.uninstall();
        assertTrue(ctx.log.all().contains("Retransformer parked"), ctx.log.all());
        assertFalse(RuleHelper.held(), "the helper's slot is released");

        // ---- a second instance reuses the parked Retransformer: no append, no premain
        FakeAgentContext ctx2 = context();
        ctx2.options.put(Settings.DETACH_BUDGET_MS, Long.toString(DetachBudgetTest.BUDGET_MS));
        BytemanModule second = module(ctx2);
        Capability reused = ctx2.capabilities.probe(BytemanModule.CAPABILITY);
        assertEquals("in-process, reused", reused.strategy(), reused.toString());
        submit(ctx2, LIMIT);
        assertEquals(true, map(ctx2.call("rule.list", args())).get("reused"));
        assertFalse(ctx2.log.all().contains("byteman: appended"), ctx2.log.all());
        desk.ceiling("SUP-3");
        List<Map<String, Object>> fired2 = ctx2.bus.payloads(Topics.RULE_FIRED);
        assertEquals(1, fired2.size());
        assertEquals("500.00", map(fired2.get(0).get("bindings")).get("1"));
        Map<String, Object> restored2 = map(ctx2.call("rule.unload", args("name", "limit")));
        assertEquals("OK", map(((List<?>) restored2.get("restored")).get(0)).get("verified"),
                "the reuse primes its reference: " + restored2);

        // ---- a cut-short uninstall leaves a rule: the next instance refuses, the rule keeps running its action
        submit(ctx2, LIMIT.replace("RULE limit", "RULE left behind"));
        Thread holder = DetachBudgetTest.holdLock(second.engine(), DetachBudgetTest.HOLD_MS);
        long ms = DetachBudgetTest.timedUninstall(second);
        assertTrue(ms < DetachBudgetTest.BUDGET_MS + DetachBudgetTest.MARGIN_MS, "uninstall took " + ms + " ms with a"
                + " detach budget of " + DetachBudgetTest.BUDGET_MS + " ms while a call held the module");
        holder.join(DetachBudgetTest.HOLD_MS + 10000);
        assertTrue(ctx2.log.all().contains("rules still installed after detach: [left behind]"), ctx2.log.all());
        assertTrue(RuleHelper.held(), "the disarmed handler keeps the slot while the rule exists");
        FakeAgentContext ctx3 = context();
        module(ctx3);
        Capability refused = ctx3.capabilities.probe(BytemanModule.CAPABILITY);
        assertEquals(CapabilityState.UNAVAILABLE, refused.state());
        assertEquals("Byteman rules of an earlier agent instance are still installed: left behind; restart the target",
                refused.reason());
        long before = RuleHelper.counters()[0];
        desk.ceiling("STAFF-9");
        assertEquals(before + 1, RuleHelper.counters()[0], "the rule's action still runs");
        assertEquals(1, ctx2.bus.payloads(Topics.RULE_FIRED).size(), "but no rule.fired arrives");
        assertTrue(ctx3.bus.payloads(Topics.RULE_FIRED).isEmpty());
        assertEquals(Arrays.asList(), ctx3.bus.payloads(Topics.RULES_CHANGED));
    }
}
