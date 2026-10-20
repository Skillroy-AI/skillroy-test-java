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

package ai.skillroy.testjava.protocol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The result conventions of semantic input, rungs and composites (phase8-contracts.md 7.2-7.6, 13.1 row 29). */
class ResultConventionsTest {

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    // ---- InputResult ----

    @Test
    void inputIdAndConsumedAreReadFromAValue() {
        Map<String, Object> value = map("accepted", true, "inputId", "lending-42", "consumed", "QUEUED");
        assertEquals("lending-42", InputResult.inputId(value));
        assertEquals("QUEUED", InputResult.consumed(value));
        assertTrue(InputResult.queued(value));
        assertFalse(InputResult.queued(map("consumed", "CONSUMED")));
        assertNull(InputResult.inputId(map("inputId", 42L)), "not a string");
        assertNull(InputResult.inputId(map("inputId", "has space")), "not an id");
        assertNull(InputResult.inputId(map("inputId", "")), "empty");
        assertNull(InputResult.inputId(map("inputId", new String(new char[65]).replace('\0', 'a'))), "too long");
        assertEquals("L12.1:a-b_c", InputResult.inputId(map("inputId", "L12.1:a-b_c")));
        assertNull(InputResult.inputId("lending-1"), "not an object");
        assertNull(InputResult.inputId(null));
        assertNull(InputResult.consumed(map("consumed", true)));
        assertNull(InputResult.consumed(null));
    }

    @Test
    void theStandardRejectionReasonsAreTokens() {
        assertEquals("reason", InputResult.REASON);
        assertEquals(Arrays.asList("notAccepted", "inboxFull", "vetoed", "not visible"), Arrays.asList(
                InputResult.NOT_ACCEPTED, InputResult.INBOX_FULL, InputResult.VETOED, InputResult.NOT_VISIBLE));
        assertEquals(Arrays.asList("CONSUMED", "QUEUED"), Arrays.asList(InputResult.STATE_CONSUMED,
                InputResult.STATE_QUEUED));
    }

    // ---- RungResult ----

    @Test
    void theRungBuilderWritesThePresentKeys() {
        assertEquals(map("rung", "SEMANTIC", "requestedRung", "PHYSICAL", "fidelity", "DEGRADED", "reason", "off"),
                RungResult.of(RungResult.SEMANTIC, RungResult.PHYSICAL, RungResult.DEGRADED, "off"));
        Map<String, Object> full = RungResult.of("PHYSICAL", null, RungResult.FULL, null);
        assertEquals(map("rung", "PHYSICAL", "fidelity", "FULL"), full);
        full.put(RungResult.MIN_RUNG, "PHYSICAL");
        assertEquals("PHYSICAL", RungResult.minRung(full), "the map is the primitive's to extend");
        assertEquals("PHYSICAL", RungResult.rung(full));
        assertNull(RungResult.rung(map("rung", 1L)));
        assertThrows(IllegalArgumentException.class, () -> RungResult.of("physical", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> RungResult.of(null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> RungResult.of("A", "b", null, null));
        assertTrue(RungResult.isName("A" + new String(new char[31]).replace('\0', 'B')));
        assertFalse(RungResult.isName("A" + new String(new char[32]).replace('\0', 'B')));
    }

    // ---- CompositeResult ----

    private static final List<String> PARAMS = Arrays.asList("staff", "password");
    private static final List<String> SECRETS = Collections.singletonList("password");

    @Test
    void aWellFormedCompositeHasNoProblem() {
        Map<String, Object> value = CompositeResult.builder().branch("password")
                .answered("input.requested", null, "Staff code", 411L, "staff", null, "lending-17")
                .answered("input.requested", null, "Password", 412L, "password", null, "lending-18")
                .build();
        value.put("inputId", "lending-18");
        value.put("inputIds", Arrays.asList("lending-17", "lending-18"));
        assertEquals(Collections.<String>emptyList(), CompositeResult.problems(value, PARAMS, SECRETS));
        assertEquals(Arrays.asList(411L, 412L), CompositeResult.answeredSeqs(value));
        Map<?, ?> composite = (Map<?, ?>) value.get("composite");
        assertEquals("password", composite.get("branch"));
        assertEquals(2, ((List<?>) composite.get("answered")).size());
        assertEquals(Collections.<String>emptyList(), CompositeResult.problems(map("accepted", true), PARAMS,
                SECRETS), "not a composite");
        assertTrue(CompositeResult.answeredSeqs(map("accepted", true)).isEmpty());
    }

    @Test
    void everyViolationIsNamed() {
        assertProblem(map("composite", "x"), "composite is not an object");
        assertProblem(map("composite", map("answered", new ArrayList<Object>())), "composite.branch is missing");
        assertProblem(map("composite", map("branch", "1bad")), "composite.branch is not a name");
        assertProblem(map("composite", map("branch", "b", "answered", "x")), "composite.answered is not a list");
        List<Object> many = new ArrayList<Object>();
        for (int i = 0; i < 33; i++) {
            many.add(map("question", map("topic", "t"), "by", "staff"));
        }
        assertProblem(map("composite", map("branch", "b", "answered", many)), "holds 33 entries; at most 32");
        assertProblem(entry(map("question", map("topic", "t"))), "composite.answered[0].by is missing");
        assertProblem(entry(map("question", map("topic", "t"), "by", "1234")),
                "composite.answered[0].by names no parameter of the call");
        assertProblem(entry(map("question", map("topic", "t"), "by", "password", "choice", "Yes")),
                "composite.answered[0].choice is given for the secret parameter 'password'");
        assertProblem(entry(map("question", map("topic", "t", "seq", 0L), "by", "staff")),
                "composite.answered[0].question.seq is not a positive integer");
        assertProblem(entry(map("question", map("topic", "t", "seq", "4"), "by", "staff")),
                "question.seq is not a positive integer");
        assertProblem(entry(map("question", "t", "by", "staff")), "composite.answered[0].question is not an object");
        assertProblem(entry("x"), "composite.answered[0] is not an object");
        assertEquals(Collections.<String>emptyList(), CompositeResult.problems(entry(map("question",
                map("topic", "t", "seq", 4.0), "by", "staff", "choice", "Yes")), PARAMS, SECRETS),
                "a choice on a public parameter, an integral seq");
    }

    @Test
    void answeredSeqsSkipsWhatIsNotAPositiveInteger() {
        Map<String, Object> value = map("composite", map("branch", "b", "answered", Arrays.asList(
                map("question", map("topic", "t", "seq", 7L), "by", "staff"),
                map("question", map("topic", "t"), "by", "staff"),
                map("question", map("topic", "t", "seq", -1L), "by", "staff"),
                map("question", map("topic", "t", "seq", 9L), "by", "staff"))));
        assertEquals(Arrays.asList(7L, 9L), CompositeResult.answeredSeqs(value));
        assertTrue(CompositeResult.answeredSeqs(map("composite", "x")).isEmpty());
    }

    private static Map<String, Object> entry(Object answered) {
        return map("composite", map("branch", "b", "answered", Collections.singletonList(answered)));
    }

    private static void assertProblem(Object value, String fragment) {
        List<String> problems = CompositeResult.problems(value, PARAMS, SECRETS);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains(fragment), problems.get(0));
    }
}
