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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.Scenario;
import ai.skillroy.testjava.scenario.ScenarioLoader;
import ai.skillroy.testjava.inference.tools.ToolProjection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 security fix, S101 and S128 (L3-F1, L3-S3; phase7-contracts.md 9.2, 9.4): the credential rule's plan form
 * ({@link PlanPolicy#credentialRefusal}) reads references with the executor's own tokenizer, so that every spelling the
 * substitution reads as the credential slot as a whole is one, in each of the three ways it judges a value; a field the
 * slot's fixture provides passes; a value nested deeper than the bound is refused. S107: the plan form needs the
 * scenario as written.
 */
class PlanPolicyCredentialTest {

    /** The credential slot {@code staff}, whose fixture provides {@code code}. */
    private static final Map<String, Set<String>> STAFF = Map.of("staff", Set.of("code"));

    private static String judge(String text, PlanPolicy.Judged judged) {
        return PlanPolicy.credentialRefusal(Map.of("value", text), STAFF, judged);
    }

    @Test
    void everySpellingOfTheWholeSlotIsOneInEveryJudgement() {
        for (PlanPolicy.Judged judged : PlanPolicy.Judged.values()) {
            for (String text : List.of("x$staff", "x$staff.", "x$staff..", "x$staff..password", "[$staff]")) {
                String why = judge(text, judged);
                assertNotNull(why, judged + " " + text);
                assertTrue(why.contains("the credential slot 'staff' as a whole"), why);
            }
        }
        assertNotNull(judge("$staff", PlanPolicy.Judged.CALL), "a call's argument");
        assertNotNull(judge("$staff", PlanPolicy.Judged.PACK_CALL), "a pack verb's compiled call passes it on");
        assertNull(judge("$staff", PlanPolicy.Judged.WRITTEN), "handed whole to a pack verb, which joins a field");
        assertNull(judge("$$staff and x$$staff", PlanPolicy.Judged.CALL), "a literal dollar is no reference");
    }

    @Test
    void aSecretFieldIsRefusedAndAProvidedFieldPasses() {
        for (PlanPolicy.Judged judged : List.of(PlanPolicy.Judged.CALL, PlanPolicy.Judged.WRITTEN)) {
            String why = judge("$staff.password", judged);
            assertNotNull(why, judged.name());
            assertTrue(why.contains("the field 'password' of the credential slot 'staff'"), why);
            assertNotNull(judge("pin $staff.password!", judged), "embedded");
            assertNull(judge("$staff.code", judged), "the fixture provides it");
            assertNull(judge("$other.password", judged), "not a credential slot");
        }
        assertNull(judge("$staff.password", PlanPolicy.Judged.PACK_CALL), "a pack verb's own join (D427)");
    }

    @Test
    void aValueNestedDeeperThanTheBoundIsRefused() {
        Object deep = "$staff.code";
        for (int i = 0; i < 66; i++) {
            deep = Map.of("a", deep);
        }
        for (PlanPolicy.Judged judged : PlanPolicy.Judged.values()) {
            String why = PlanPolicy.credentialRefusal(Map.of("deep", deep), STAFF, judged);
            assertNotNull(why, judged.name());
            assertTrue(why.contains("nested deeper than 64 levels"), why);
        }
        assertNull(PlanPolicy.credentialRefusal(Map.of("deep", deep), Map.of(), PlanPolicy.Judged.CALL),
                "a plan without a credential slot has nothing to protect");
    }

    @Test
    void thePlanFormNeedsTheScenarioAsWritten() throws Exception {
        Desk desk = new Desk();
        try {
            PlanPolicy policy = PlanPolicy.of(desk.agent.schema(), desk.pack, ToolProjection.Policy.mutating());
            byte[] yaml = ("scenario: s\npack: desk\nresolve:\n  staff: { fixture: { code: \"100\" }, credential:"
                    + " { realm: staff, key: code } }\nsteps:\n  - enter: {value: \"$staff.password\"}\n")
                    .getBytes(StandardCharsets.UTF_8);
            Scenario scenario = ScenarioLoader.load(yaml, "s.yaml");
            Plan plan = PlanCompiler.compile(scenario, desk.pack);
            assertThrows(NullPointerException.class, () -> policy.refusals(plan, null));
            assertTrue(policy.refusals(plan, scenario).get(0).startsWith("step s1 (enter, line 6): names the field"
                    + " 'password'"), String.valueOf(policy.refusals(plan, scenario)));
        } finally {
            desk.agent.close();
        }
    }
}
