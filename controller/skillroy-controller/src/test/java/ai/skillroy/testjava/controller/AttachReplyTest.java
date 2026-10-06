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

package ai.skillroy.testjava.controller;

import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.controller.AttachReply.INITIALIZATION_EXCEPTION;
import static ai.skillroy.testjava.controller.AttachReply.LOAD_EXCEPTION;
import static ai.skillroy.testjava.controller.AttachReply.interpret;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachReplyTest {

    private static void assertFailure(AttachReply.Outcome outcome, String codeName, Integer code) {
        assertFalse(outcome.success(), outcome.toString());
        assertEquals(codeName, outcome.codeName());
        assertEquals(code, outcome.code());
    }

    @Test
    void aNormalReturnIsSuccessOnAnyTarget() {
        assertTrue(interpret(null, null, null, "21").success());
        assertTrue(interpret(null, null, null, null).success());
    }

    /** The replies a JDK 21 attach client was observed to report (JDK 8 and JDK 21 targets, Linux x86-64). */
    @Test
    void observedRepliesAreInterpretedCorrectly() {
        // JDK 8 target: success, missing jar, agentmain class not found.
        assertTrue(interpret(LOAD_EXCEPTION, "Failed to load agent library: 0", null, "1.8").success());
        assertFailure(interpret(LOAD_EXCEPTION, "Failed to load agent library: 100", null, "1.8"), "BADJAR", 100);
        assertFailure(interpret(LOAD_EXCEPTION, "Failed to load agent library: 102", null, "1.8"), "STARTFAIL", 102);
        // JDK 21 target: success returns normally; missing jar; agentmain class not found.
        assertFailure(interpret(LOAD_EXCEPTION, "Agent JAR not found or no Agent-Class attribute", null, "21"),
                "BADJAR", 100);
        assertFailure(interpret(INITIALIZATION_EXCEPTION, "Agent JAR loaded but agent failed to initialize", 0,
                "21"), "STARTFAIL", 102);
        // The same "...: 0" text from a newer target is a failure.
        assertFailure(interpret(LOAD_EXCEPTION, "Failed to load agent library: 0", null, "21"), "AGENT_LOAD_FAILED",
                0);
    }

    @Test
    void theBareZeroReplyOfAJava8TargetIsSuccess() {
        assertTrue(interpret(LOAD_EXCEPTION, "0", null, "1.8").success());
        assertTrue(interpret(LOAD_EXCEPTION, " 0\n", null, "1.8").success());
        assertTrue(interpret(LOAD_EXCEPTION, "Failed to load agent - unknown reason: 0", null, "1.8").success());
        assertTrue(interpret(LOAD_EXCEPTION, "0", null, "1.7").success(), "older targets reply the same way");
    }

    @Test
    void zeroIsAFailureOnNewerOrUnknownTargets() {
        assertFailure(interpret(LOAD_EXCEPTION, "0", null, "9"), "AGENT_LOAD_FAILED", 0);
        assertFailure(interpret(LOAD_EXCEPTION, "0", null, "21"), "AGENT_LOAD_FAILED", 0);
        assertFailure(interpret(LOAD_EXCEPTION, "0", null, null), "AGENT_LOAD_FAILED", 0);
        assertFailure(interpret(LOAD_EXCEPTION, "0", null, "garbage"), "AGENT_LOAD_FAILED", 0);
    }

    @Test
    void java8TargetFailureCodesAreNamed() {
        assertFailure(interpret(LOAD_EXCEPTION, "100", null, "1.8"), "BADJAR", 100);
        assertFailure(interpret(LOAD_EXCEPTION, "101", null, "1.8"), "NOTONCP", 101);
        assertFailure(interpret(LOAD_EXCEPTION, "102", null, "1.8"), "STARTFAIL", 102);
        assertFailure(interpret(LOAD_EXCEPTION, "-4", null, "1.8"), "ENOMEM", -4);
        assertFailure(interpret(LOAD_EXCEPTION, "7", null, "1.8"), "AGENT_LOAD_FAILED", 7);
    }

    @Test
    void modernClientMessagesAreRecognised() {
        assertFailure(interpret(LOAD_EXCEPTION, "Agent JAR not found or no Agent-Class attribute", null, "21"),
                "BADJAR", 100);
        assertFailure(interpret(LOAD_EXCEPTION, "Unable to add JAR file to system class path", null, "21"),
                "NOTONCP", 101);
        assertFailure(interpret(LOAD_EXCEPTION, "Insuffient memory", null, "17"), "ENOMEM", -4);
        assertFailure(interpret(LOAD_EXCEPTION, "Failed to load agent - unknown reason: 33", null, "21"),
                "AGENT_LOAD_FAILED", 33);
        assertFailure(interpret(LOAD_EXCEPTION, "Target VM did not respond", null, "21"), "AGENT_LOAD_FAILED",
                null);
    }

    @Test
    void initializationFailuresAreStartFail() {
        assertFailure(interpret(INITIALIZATION_EXCEPTION, "Agent JAR loaded but agent failed to initialize", 0,
                "21"), "STARTFAIL", 102);
        assertFailure(interpret(INITIALIZATION_EXCEPTION, "Agent_OnAttach failed", 102, "11"), "STARTFAIL", 102);
        assertFailure(interpret(INITIALIZATION_EXCEPTION, null, null, "1.8"), "STARTFAIL", 102);
        assertFailure(interpret(INITIALIZATION_EXCEPTION, "Agent_OnAttach failed", 5, "21"), "AGENT_LOAD_FAILED", 5);
    }

    @Test
    void otherExceptionsAreFailures() {
        AttachReply.Outcome outcome = interpret("IOException", "broken pipe", null, "1.8");
        assertFalse(outcome.success());
        assertNull(outcome.code());
        assertTrue(outcome.describe().contains("broken pipe"), outcome.describe());
    }

    @Test
    void describeNamesCodeAndMessage() {
        assertEquals("STARTFAIL (102): boom: 102", interpret(LOAD_EXCEPTION, "boom: 102", null, "1.8").describe());
        assertEquals("agent loaded", interpret(null, null, null, "21").describe());
    }

    @Test
    void featureVersionsAreParsed() {
        assertEquals(8, AttachReply.featureVersion("1.8"));
        assertEquals(7, AttachReply.featureVersion("1.7"));
        assertEquals(9, AttachReply.featureVersion("9"));
        assertEquals(21, AttachReply.featureVersion("21"));
        assertEquals(25, AttachReply.featureVersion(" 25 "));
        assertEquals(-1, AttachReply.featureVersion(null));
        assertEquals(-1, AttachReply.featureVersion("x"));
        assertTrue(AttachReply.isJava8OrOlder("1.8"));
        assertFalse(AttachReply.isJava8OrOlder("11"));
        assertFalse(AttachReply.isJava8OrOlder(null));
    }
}
