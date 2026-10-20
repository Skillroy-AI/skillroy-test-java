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

import ai.skillroy.testjava.fixture.byteman.BranchDesk;
import ai.skillroy.testjava.fixture.byteman.DepositDesk;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.byteman.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code rule.check} and {@code rule.list} on a fresh agent load nothing (phase6-contracts.md 7.3, 7.6): no
 * {@code org.jboss.byteman} class is defined, nothing of the sys jar is loaded, Byteman's {@code loaded} property is
 * not set, and the answer says the type check was skipped. A confirmed {@code rule.submit} that the class bound refuses
 * loads nothing either: the bound runs before the load (fix wave, L2-F5), and so does one whose class or interface is
 * the JDK's (Phase 6 security fix, pass 2, P2-L2-F1), and one on a class the agent option {@code hooks.deny} denies
 * (phase8-contracts.md 6.7: the deny before the count and before the load). A test class of its own (a fresh JVM): the
 * Byteman jar is on the test class path, so anything that touched it would show.
 */
class CheckLoadsNothingTest {

    private static List<String> loaded(Instrumentation inst, String prefix) {
        List<String> names = new ArrayList<String>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().startsWith(prefix)) {
                names.add(c.getName());
            }
        }
        return names;
    }

    @Test
    void aCheckAndAListOnAFreshAgentLoadNothing() throws Exception {
        Instrumentation inst = TestAgent.instrumentation();
        assertNotNull(inst, "the test agent's Instrumentation");
        FreshJvm.assume(true);
        new DepositDesk().ceiling("STAFF-1");
        new BranchDesk().ceiling("STAFF-1");
        FakeAgentContext ctx = new FakeAgentContext(inst);
        ctx.options.put(Settings.BYTEMAN_JAR, System.getProperty("skillroy.test.bytemanJar"));
        ctx.options.put(Settings.MAX_CLASSES, "1");
        String branch = BranchDesk.class.getName();
        ctx.options.put(Settings.DENY, branch);
        BytemanModule module = new BytemanModule(new File(System.getProperty("skillroy.test.sysJar")), null);
        module.install(ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> check = (Map<String, Object>) ctx.call("rule.check", args("rule",
                RuleLintTest.rule("limit", "ceiling", "fired($0, $1)")));
        assertEquals(true, check.get("ok"), check.toString());
        assertEquals(false, check.get("typeChecked"));
        assertEquals("[type check skipped: Byteman is not loaded (the first rule.submit loads it)]",
                String.valueOf(check.get("warnings")));
        assertEquals("limit", check.get("name"));
        assertEquals(RuleLintTest.TOY, check.get("class"));
        @SuppressWarnings("unchecked")
        Map<String, Object> refused = (Map<String, Object>) ctx.call("rule.check", args("rule",
                RuleLintTest.rule("kill", "ceiling", "killJVM()")));
        assertEquals(false, refused.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> list = (Map<String, Object>) ctx.call("rule.list", args());
        assertEquals(false, list.get("loaded"));
        assertEquals(new ArrayList<Object>(), list.get("rules"));
        RpcException bound = ctx.fails("rule.submit", args("rule", RuleLintTest.rule("wide", "ceiling", "fired($0)")
                .replace("CLASS " + RuleLintTest.TOY, "CLASS ^" + RuleLintTest.TOY)));
        assertEquals(ErrorCodes.RULE_REJECTED, bound.error().code());
        assertTrue(String.valueOf(bound.error().data().get("errors")).contains("rule would retransform 2 classes"),
                String.valueOf(bound.error().data()));
        // Phase 6 security fix, pass 2, P2-L2-F1: a confirmed rule on a JDK class or interface outside the lint's
        // prefixes, loaded or not, is refused by name before anything is loaded (before the fix it loaded Byteman and
        // installed: every later load of the class threw NoClassDefFoundError into the host).
        for (String target : new String[] {"CLASS org.xml.sax.helpers.LocatorImpl", "INTERFACE org.xml.sax.ErrorHandler"}) {
            RpcException jdk = ctx.fails("rule.submit", args("rule", RuleLintTest.rule("jdk", "setPublicId",
                    "fired($0)").replace("CLASS " + RuleLintTest.TOY, target)));
            assertEquals(ErrorCodes.RULE_REJECTED, jdk.error().code(), target);
            assertTrue(String.valueOf(jdk.error().data().get("errors")).contains("class is the JDK's"),
                    String.valueOf(jdk.error().data()));
        }
        // Phase 8 (phase8-contracts.md 6.7): rule.check lists the deny in errors; a confirmed rule.submit refused by
        // the deny loads nothing, appends nothing and sets no property; rule.list shows the deny in force.
        String onBranch = RuleLintTest.rule("branch", "ceiling", "fired($0)").replace("CLASS " + RuleLintTest.TOY,
                "CLASS " + branch);
        @SuppressWarnings("unchecked")
        Map<String, Object> deniedCheck = (Map<String, Object>) ctx.call("rule.check", args("rule", onBranch));
        assertEquals(false, deniedCheck.get("ok"));
        assertEquals(java.util.Collections.singletonList(branch + ": denied by the agent option hooks.deny: "
                + branch), deniedCheck.get("errors"));
        RpcException denied = ctx.fails("rule.submit", args("rule", onBranch));
        assertEquals(ErrorCodes.RULE_REJECTED, denied.error().code());
        assertEquals("the rule was refused: " + branch + ": denied by the agent option hooks.deny: " + branch,
                denied.error().message());
        assertTrue(String.valueOf(denied.error().data().get("errors")).contains("denied by the agent option"),
                String.valueOf(denied.error().data()));
        @SuppressWarnings("unchecked")
        Map<String, Object> afterDeny = (Map<String, Object>) ctx.call("rule.list", args());
        assertEquals(false, afterDeny.get("loaded"), "nothing appended, no premain");
        @SuppressWarnings("unchecked")
        Map<String, Object> deny = (Map<String, Object>) afterDeny.get("deny");
        assertEquals(java.util.Collections.singletonList(branch), deny.get("option"));
        assertEquals(0L, deny.get("modules"));
        assertFalse(deny.containsKey("catalogue"), "no hooks module loaded");
        assertFalse(ctx.log.all().contains("byteman: appended"), ctx.log.all());
        assertEquals(new ArrayList<String>(), loaded(inst, "org.jboss.byteman."), "no Byteman class is defined");
        assertEquals(new ArrayList<String>(), loaded(inst, "ai.skillroy.testjava.agent.byteman.sys."),
                "nothing of the sys jar is loaded");
        assertFalse(loaded(inst, Engine.HANDLER).contains(Engine.HANDLER), "the handler class is not loaded");
        assertNull(System.getProperty(Engine.LOADED_PROPERTY));
        assertFalse(module.engine().loaded());
        assertTrue(ctx.bus.events.isEmpty(), "nothing published");
        module.uninstall();
    }
}
