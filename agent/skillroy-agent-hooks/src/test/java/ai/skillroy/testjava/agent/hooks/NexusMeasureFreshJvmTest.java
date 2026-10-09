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

import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.fixture.hooks.ChildFirstLoader;
import ai.skillroy.testjava.fixture.hooks.DepositRule;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Nexus} measure (phase6-contracts.md section 3.2, D155; BS3 RESULT section 6, section 9 facts 2 and 3):
 * with the module in a loader of its own, after its first {@code AgentBuilder}, an install and a remove, the system
 * loader has gained no class of the relocated prefix (Byte Buddy's default would inject {@code Nexus} there) and the
 * bootstrap loader nothing of the agent's but {@code Spy} and its two nested types (no {@code ByteBuddyAccessor}).
 */
class NexusMeasureFreshJvmTest {

    /** What a shared loader defines of the agent's: the relocated prefix, Byte Buddy's accessor, Nexus, Spy. */
    private static Set<String> agentsIn(ClassLoader loader) {
        Set<String> out = new TreeSet<String>();
        for (Class<?> c : Harness.inst().getInitiatedClasses(loader)) {
            String n = c.getName();
            boolean ours = n.startsWith("ai.skillroy.testjava.agent.hooks.shaded.") || n.contains("ByteBuddy")
                    || n.contains("Nexus") || n.startsWith("ai.skillroy.testjava.agent.spy.");
            if (c.getClassLoader() == loader && ours) {
                out.add(n);
            }
        }
        return out;
    }

    @Test
    public void theSharedLoadersGainNothingButSpy() throws Exception {
        new DepositRule().depositFor("T0");
        ClassLoader system = ClassLoader.getSystemClassLoader();
        Set<String> systemBefore = agentsIn(system);
        Set<String> bootBefore = agentsIn(null);
        ChildFirstLoader loader = ModuleLoaderFixture.loader();
        AgentModule module = ModuleLoaderFixture.module(loader);
        FakeHookContext ctx = new FakeHookContext();
        module.install(ctx);
        ctx.ready();
        ctx.hooks().install(Collections.singletonList(spec("deposit.computed", HookMode.OBSERVE, DepositRule.class,
                "depositFor")), "toys", "sha256:03", false);
        new DepositRule().depositFor("T1");
        assertEquals(1, ctx.events.topic("hook.deposit.computed").size());
        ctx.hooks().remove(Collections.<String>emptyList());
        module.uninstall();
        ctx.rollback();
        List<String> systemAdded = new ArrayList<String>(agentsIn(system));
        systemAdded.removeAll(systemBefore);
        assertTrue(systemAdded.isEmpty(), "the system loader gained " + systemAdded);
        List<String> bootAdded = new ArrayList<String>(agentsIn(null));
        bootAdded.removeAll(bootBefore);
        Collections.sort(bootAdded);
        assertTrue(bootAdded.contains("ai.skillroy.testjava.agent.spy.Spy"), String.valueOf(bootAdded));
        assertTrue(java.util.Arrays.asList("ai.skillroy.testjava.agent.spy.Spy",
                "ai.skillroy.testjava.agent.spy.Spy$Handler", "ai.skillroy.testjava.agent.spy.Spy$Stub")
                .containsAll(bootAdded), "nothing but Spy and its nested types: " + bootAdded);
        assertTrue(System.getProperty(Transformers.nexusProperty()) == null, "the property is cleared again");
        loader.close();
    }
}
