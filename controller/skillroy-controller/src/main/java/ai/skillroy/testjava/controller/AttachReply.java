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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets how {@code VirtualMachine.loadAgent} ended. A pure function, kept apart from the attach API so that it is
 * unit-tested without a target and loads without {@code jdk.attach}.
 *
 * <p>Why it is needed: a JDK 10+ attach client does not understand the load reply of a Java 8 target, so a
 * <em>successful</em> load into a Java 8 target surfaces as {@code AgentLoadException} with the message {@code "0"},
 * or one ending in {@code ": 0"} (a JDK 21 client reports {@code "Failed to load agent library: 0"}). On such targets
 * that message means success; against a newer target it keeps its normal meaning. Failure codes are the instrument
 * agent's: 100 BADJAR (jar not found or no {@code Agent-Class}), 101 NOTONCP (cannot add the jar to the class path),
 * 102 STARTFAIL ({@code agentmain} missing or threw), -4 ENOMEM. From a Java 8 target they arrive the same way
 * ({@code "Failed to load agent library: 102"}); against a newer target the client turns them into fixed messages or
 * an {@code AgentInitializationException}, which are recognised as well.
 *
 * <p>Stateless; safe to call from any thread.
 */
final class AttachReply {

    /** Simple name of {@code com.sun.tools.attach.AgentLoadException}. */
    static final String LOAD_EXCEPTION = "AgentLoadException";

    /** Simple name of {@code com.sun.tools.attach.AgentInitializationException}. */
    static final String INITIALIZATION_EXCEPTION = "AgentInitializationException";

    static final int ENOMEM = -4;
    static final int BADJAR = 100;
    static final int NOTONCP = 101;
    static final int STARTFAIL = 102;

    private static final Pattern TRAILING_CODE = Pattern.compile("(?:^|:\\s*)(-?\\d{1,10})\\s*$");

    /**
     * The interpretation.
     *
     * @param success true when the load reply counts as success (the handshake still has to confirm it)
     * @param codeName BADJAR, NOTONCP, STARTFAIL, ENOMEM or AGENT_LOAD_FAILED; null on success
     * @param code the numeric code when one is known, else null
     * @param message the exception message as the client reported it, may be null
     */
    record Outcome(boolean success, String codeName, Integer code, String message) {

        /** One line for an {@link AttachFailedException}. */
        String describe() {
            if (success) {
                return "agent loaded";
            }
            return codeName + (code == null ? "" : " (" + code + ")")
                    + (message == null || message.isBlank() ? "" : ": " + message);
        }
    }

    private AttachReply() {
    }

    /**
     * @param exceptionType simple class name of what {@code loadAgent} threw ({@link #LOAD_EXCEPTION},
     *     {@link #INITIALIZATION_EXCEPTION}, or another), or null when it returned normally
     * @param message the exception's message, may be null
     * @param returnValue {@code AgentInitializationException.returnValue()}, or null
     * @param targetSpecVersion the target's {@code java.specification.version}, read before loading; null if unknown
     */
    static Outcome interpret(String exceptionType, String message, Integer returnValue, String targetSpecVersion) {
        if (exceptionType == null) {
            return new Outcome(true, null, null, null);
        }
        String text = message == null ? "" : message.strip();
        if (LOAD_EXCEPTION.equals(exceptionType)) {
            boolean java8OrOlder = isJava8OrOlder(targetSpecVersion);
            if (java8OrOlder && (text.equals("0") || text.endsWith(": 0"))) {
                return new Outcome(true, null, null, message);
            }
            Integer code = codeOf(text);
            return failure(code, message);
        }
        if (INITIALIZATION_EXCEPTION.equals(exceptionType)) {
            Integer code = returnValue != null && returnValue != 0 ? returnValue : codeOf(text);
            return failure(code == null ? Integer.valueOf(STARTFAIL) : code, message);
        }
        return new Outcome(false, "AGENT_LOAD_FAILED", null, exceptionType + (text.isEmpty() ? "" : ": " + text));
    }

    /** True when {@code specVersion} is {@code "1.8"} or older; false for 9+ and for unknown values. */
    static boolean isJava8OrOlder(String specVersion) {
        int feature = featureVersion(specVersion);
        return feature > 0 && feature <= 8;
    }

    /**
     * The feature version of a {@code java.specification.version}: {@code "1.8"} -> 8, {@code "11"} -> 11; -1 when
     * it cannot be read.
     */
    static int featureVersion(String specVersion) {
        if (specVersion == null) {
            return -1;
        }
        String text = specVersion.strip();
        if (text.startsWith("1.")) {
            text = text.substring(2);
        }
        int dot = text.indexOf('.');
        if (dot >= 0) {
            text = text.substring(0, dot);
        }
        try {
            int value = Integer.parseInt(text);
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** BADJAR, NOTONCP, STARTFAIL, ENOMEM, else AGENT_LOAD_FAILED. */
    static String codeName(Integer code) {
        if (code == null) {
            return "AGENT_LOAD_FAILED";
        }
        return switch (code) {
            case BADJAR -> "BADJAR";
            case NOTONCP -> "NOTONCP";
            case STARTFAIL -> "STARTFAIL";
            case ENOMEM -> "ENOMEM";
            default -> "AGENT_LOAD_FAILED";
        };
    }

    private static Outcome failure(Integer code, String message) {
        return new Outcome(false, codeName(code), code, message);
    }

    /** The code a client message stands for: a bare or trailing number, or one of the JDK client's fixed texts. */
    private static Integer codeOf(String text) {
        Matcher m = TRAILING_CODE.matcher(text);
        if (m.find()) {
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (text.startsWith("Agent JAR not found or no Agent-Class attribute")) {
            return BADJAR;
        }
        if (text.startsWith("Unable to add JAR file to system class path")) {
            return NOTONCP;
        }
        if (text.startsWith("Agent JAR loaded but agent failed to initialize")) {
            return STARTFAIL;
        }
        if (text.startsWith("Insuffient memory") || text.startsWith("Insufficient memory")) {
            return ENOMEM;
        }
        return null;
    }
}
