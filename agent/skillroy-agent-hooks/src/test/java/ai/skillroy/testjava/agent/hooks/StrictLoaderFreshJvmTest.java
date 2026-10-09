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
import ai.skillroy.testjava.fixture.hooks.StrictDelegationLoader;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class of a strict-delegation loader (one that delegates only {@code java.*}) cannot see {@code Spy} on the
 * bootstrap path and is refused (phase6-contracts.md section 3.3 item 6; BS3 RESULT section 9 fact 6). The check is
 * decided after the append, so it runs in a JVM of its own.
 */
class StrictLoaderFreshJvmTest {

    @Test
    @SuppressWarnings("unchecked")
    void theStrictLoadersClassIsRefusedWithTheReasonNamingTheLoader() throws Exception {
        new DepositRule();
        StrictDelegationLoader strict = new StrictDelegationLoader(new URL[] {Harness.testClasses()});
        Class<?> type = strict.loadClass("ai.skillroy.testjava.fixture.hooks.Capturing");
        assertEquals(strict, type.getClassLoader());
        String loader = Integer.toHexString(System.identityHashCode(strict));
        Harness harness = new Harness();
        Map<String, Object> probe = (Map<String, Object>) ((List<Object>) harness.ctx.map("hook.probe", map("specs",
                Harness.json(Harness.spec("label.read", HookMode.OBSERVE, type.getName(), "label",
                        "()Ljava/lang/String;", loader, null, null, false)))).get("hooks")).get(0);
        assertEquals("UNCHECKED", probe.get("spyVisibility"), "undecidable before the append: " + probe);
        assertEquals("OK", probe.get("state"));
        Map<String, Object> result = harness.install("toys",
                spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                Harness.spec("label.read", HookMode.OBSERVE, type.getName(), "label", "()Ljava/lang/String;", loader,
                        null, null, false));
        List<Object> hooks = (List<Object>) result.get("hooks");
        assertEquals("OK", ((Map<String, Object>) hooks.get(0)).get("state"));
        Map<String, Object> refused = (Map<String, Object>) hooks.get(1);
        assertEquals("UNAVAILABLE", refused.get("state"));
        assertTrue(String.valueOf(refused.get("reason")).startsWith("the class's loader cannot see Spy: "
                + StrictDelegationLoader.class.getName()), String.valueOf(refused));
        assertEquals(1, harness.engine().ledger.size(), "the strict loader's class was never retransformed");
        probe = (Map<String, Object>) ((List<Object>) harness.ctx.map("hook.probe", map("specs",
                Harness.json(Harness.spec("label.read", HookMode.OBSERVE, type.getName(), "label",
                        "()Ljava/lang/String;", loader, null, null, false)))).get("hooks")).get(0);
        assertEquals("NOT_VISIBLE", probe.get("spyVisibility"), "decided once Spy is on the bootstrap path");
        type.getMethod("label").invoke(type.newInstance());
        harness.ctx.call("hook.remove", map());
        harness.close();
        strict.close();
    }
}
