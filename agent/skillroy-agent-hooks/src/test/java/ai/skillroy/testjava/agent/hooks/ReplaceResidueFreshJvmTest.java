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
import ai.skillroy.testjava.fixture.hooks.SlipFormat;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 security fix, pass 2, P2-L2-F3: {@code hook.install {replace: true}} runs {@code hook.remove} of everything
 * first, and that remove's verdict travels in the answer ({@code replaced: {restored, verified}}), so that a restore
 * that left a reference to {@code Spy} (a transformer the test keeps registered plants the advised bytes back) is
 * {@code FAILED} in the install's answer, which the controller's {@code HookInstaller} maps to ERROR / ENVIRONMENT.
 * The sceptic's case (B): the new version of the same catalogue drops the residue's id, which before the fix was a
 * plain success. By design the slot stays held for the JVM's life, so this runs in a JVM of its own.
 */
class ReplaceResidueFreshJvmTest {

    /** A JVM of its own (section 2.3 as built): skipped under a merging -Dtest, a failure otherwise. */
    @BeforeAll
    static void freshJvm() {
        Harness.assumeFreshJvm();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReplacesRemoveVerdictIsInTheAnswer() throws Exception {
        new DepositRule();
        new SlipFormat().header("drill");
        Harness harness = new Harness();
        harness.ctx.map("hook.install", map("specs", Harness.json(
                spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                spec("slip.header", HookMode.OBSERVE, SlipFormat.class, "header")),
                "catalogue", map("id", "toys", "hash", "sha256:v1")));
        PlantedReferenceFreshJvmTest.Planter planter = new PlantedReferenceFreshJvmTest.Planter();
        Harness.inst().addTransformer(planter, true);
        Harness.inst().retransformClasses(DepositRule.class);
        assertNotNull(planter.advised, "the planter saw the advised bytes");
        planter.plant = true;

        Map<String, Object> answer = harness.ctx.map("hook.install", map("specs", Harness.json(
                spec("slip.header", HookMode.OBSERVE, SlipFormat.class, "header")),
                "catalogue", map("id", "toys", "hash", "sha256:v2"), "replace", true));
        Map<String, Object> replaced = (Map<String, Object>) answer.get("replaced");
        assertNotNull(replaced, "the answer carries the implicit remove's verdict: " + answer);
        assertEquals("FAILED", replaced.get("verified"), String.valueOf(replaced));
        boolean depositFailed = false;
        for (Object line : (List<Object>) replaced.get("restored")) {
            Map<String, Object> l = (Map<String, Object>) line;
            if (DepositRule.class.getName().equals(l.get("class"))) {
                depositFailed = "FAILED".equals(l.get("verified"));
            }
        }
        assertTrue(depositFailed, "the class that kept a reference to Spy is named: " + replaced);
        assertEquals("OK", ((Map<String, Object>) ((List<Object>) answer.get("hooks")).get(0)).get("state"),
                "the new version itself installed");
        Map<String, Object> health = (Map<String, Object>) ((Map<String, Object>) harness.ctx.map("hook.health",
                map()).get("hooks")).get("deposit.computed");
        assertEquals("ERROR", health.get("state"));

        Map<String, Object> same = harness.ctx.map("hook.install", map("specs", Harness.json(
                spec("slip.header", HookMode.OBSERVE, SlipFormat.class, "header")),
                "catalogue", map("id", "toys", "hash", "sha256:v2")));
        assertEquals(null, same.get("replaced"), "an idempotent install removes nothing and says nothing of it");
        Harness.inst().removeTransformer(planter);
        harness.module.uninstall();
    }
}
