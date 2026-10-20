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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.protocol.CompositeResult;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.TopicFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.agent.FakeAgentContext.args;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The desk's composite primitives (phase8-contracts.md 7.5, 12.2; 13.1 row 34). */
class LendingCompositesTest {

    private static final List<String> SIGN_ON_PARAMS = Arrays.asList("staff", "password");
    private static final List<String> SECRET = Collections.singletonList("password");

    private LendingFixture lending;

    @BeforeEach
    void start() throws Exception {
        lending = LendingFixture.start();
    }

    @AfterEach
    void stop() {
        lending.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> composite(Map<String, Object> result) {
        return map(result.get("composite"));
    }

    private Map<String, Object> signOn(Map<String, Object> args) throws Exception {
        Map<String, Object> result = map(lending.ctx.call("lending.signOn", args, SECRET));
        assertEquals(Collections.<String>emptyList(), CompositeResult.problems(result, SIGN_ON_PARAMS, SECRET),
                "the convention holds");
        return result;
    }

    private String state() throws Exception {
        return (String) map(lending.ctx.call("lending.state", args())).get("name");
    }

    @Test
    void anAutoSignOnStaffMemberIsTheAutoBranch() throws Exception {
        Map<String, Object> result = signOn(args("staff", "103"));
        assertEquals("auto", composite(result).get("branch"));
        List<Object> answered = list(composite(result).get("answered"));
        assertEquals(1, answered.size());
        Map<String, Object> staff = map(answered.get(0));
        assertEquals("staff", staff.get("by"));
        assertEquals("Enter staff code", map(staff.get("question")).get("text"));
        assertEquals("input.requested", map(staff.get("question")).get("topic"));
        assertEquals(result.get("inputId"), staff.get("inputId"));
        assertEquals(Collections.singletonList(result.get("inputId")), result.get("inputIds"));
        assertEquals("CONSUMED", result.get("consumed"));
        assertEquals("IDLE", state());
    }

    @Test
    void aPasswordMemberIsThePasswordBranchAndThePasswordIsNowhere() throws Exception {
        long before = lending.ctx.events.lastSeq();
        Map<String, Object> result = signOn(args("staff", "100", "password", "1111"));
        assertEquals("password", composite(result).get("branch"));
        List<Object> answered = list(composite(result).get("answered"));
        assertEquals(2, answered.size());
        assertEquals("staff", map(answered.get(0)).get("by"));
        assertEquals("password", map(answered.get(1)).get("by"));
        assertEquals("Enter password", map(map(answered.get(1)).get("question")).get("text"));
        assertNull(map(answered.get(1)).get("choice"));
        assertEquals(2, list(result.get("inputIds")).size());
        assertFalse(Json.write(result).contains("1111"), Json.write(result));
        assertEquals("IDLE", state());
        List<Event> acks = lending.ctx.events.since(before, TopicFilter.parse("input.acked"), 10);
        assertEquals(2, acks.size(), "one acknowledgement per input, inside the call");
        assertFalse(Json.write(acks.get(1).payload()).contains("1111"));
    }

    @Test
    void aMemberWhoNeedsAPasswordTheCallDoesNotHaveWarnsAndLeavesTheDeskWaiting() throws Exception {
        lending.ctx.warnings.clear();
        Map<String, Object> result = signOn(args("staff", "100"));
        assertEquals("passwordRequired", composite(result).get("branch"));
        assertEquals(1, lending.ctx.warnings.size());
        assertEquals("lending.PASSWORD_REQUIRED", lending.ctx.warnings.get(0).get("code"));
        assertEquals("PASSWORD", state(), "the desk is left waiting for the password");
    }

    @Test
    void aRefusalIsTheRefusedBranchWithThePromptLeftOpen() throws Exception {
        Map<String, Object> unknown = signOn(args("staff", "999"));
        assertEquals("refused", composite(unknown).get("branch"));
        assertEquals("unknown-staff", lending.app.prompts().current().id(), "left open for the prompt check");
        assertTrue(lending.app.prompts().answer("OK"));
        lending.ctx.events.next("input.requested", lending.ctx.events.lastSeq() - 1);
        Map<String, Object> wrong = signOn(args("staff", "100", "password", "0000"));
        assertEquals("refused", composite(wrong).get("branch"));
        assertEquals("wrong-password", lending.app.prompts().current().id());
        assertTrue(lending.app.prompts().answer("OK"));
    }

    @Test
    void aSignOnOutsideTheSignedOffStateIsRejected() throws Exception {
        lending.signOn();
        RpcException e = lending.ctx.fails("lending.signOn", args("staff", "100"));
        assertEquals(ErrorCodes.INPUT_REJECTED, e.error().code());
        assertEquals("notAccepted", e.error().data().get("reason"));
    }

    @Test
    void theInductionCheckIsAnsweredAndListedWithItsSeq() throws Exception {
        lending.signOn();
        long before = lending.ctx.events.lastSeq();
        Map<String, Object> result = map(lending.ctx.call("lending.scanChecked", args("barcode", "5000000000086",
                "inducted", Boolean.TRUE)));
        assertEquals(Collections.<String>emptyList(), CompositeResult.problems(result, Arrays.asList("barcode",
                "inducted"), Collections.<String>emptyList()));
        assertEquals("induction", composite(result).get("branch"));
        Map<String, Object> entry = map(list(composite(result).get("answered")).get(0));
        assertEquals("inducted", entry.get("by"));
        assertEquals("Yes", entry.get("choice"));
        Map<String, Object> question = map(entry.get("question"));
        assertEquals("induction-check", question.get("id"));
        Event prompt = lending.ctx.events.next("prompt.waiting", before);
        assertEquals(Long.valueOf(prompt.seq()), question.get("seq"), "the seq of the prompt's own event");
        assertEquals(Collections.singletonList(Long.valueOf(prompt.seq())), CompositeResult.answeredSeqs(result));
        Map<String, Object> loan = map(lending.ctx.call("lending.loan", args()));
        assertEquals("LAD-001", map(list(loan.get("lines")).get(0)).get("code"));
        assertNull(lending.app.prompts().current());
    }

    @Test
    void aPlainItemIsThePlainBranchAndAnUnknownOneRefused() throws Exception {
        lending.signOn();
        Map<String, Object> plain = map(lending.ctx.call("lending.scanChecked", args("barcode", "5000000000017",
                "inducted", Boolean.FALSE)));
        assertEquals("plain", composite(plain).get("branch"));
        assertTrue(list(composite(plain).get("answered")).isEmpty());
        Map<String, Object> unknown = map(lending.ctx.call("lending.scanChecked", args("barcode", "999",
                "inducted", Boolean.TRUE)));
        assertEquals("refused", composite(unknown).get("branch"));
        assertNotNull(lending.app.prompts().current(), "unknown-item left open");
        assertEquals("unknown-item", lending.app.prompts().current().id());
        assertTrue(lending.app.prompts().answer("OK"));
    }
}
