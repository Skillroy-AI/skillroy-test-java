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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision logic of {@link AttachSession} after {@code loadAgent}, without a target JVM: when the agent counts as
 * published, whether this load started it, and which token and nonce the endpoint gets. A re-attach keeps the running
 * agent's token and nonce (contract amendment A15), so they must be read back from the target, not taken from the new
 * options file.
 */
class AttachDecisionTest {

    private static final long PID = 4242;
    private static final String PORT = "skillroy.agent.port";
    private static final String NONCE = "skillroy.agent.nonce";
    private static final String TOKEN_FILE = "skillroy.agent.tokenFile";
    private static final String LOAD_MODE = "skillroy.agent.loadMode";
    private static final String ERROR = "skillroy.agent.error";

    private static final String OUR_NONCE = "0123456789abcdef0123456789abcdef";
    private static final String OUR_TOKEN = "our-token";

    @TempDir
    Path dir;

    private static Properties props(String... keysAndValues) {
        Properties p = new Properties();
        p.setProperty("java.specification.version", "1.8");
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            p.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    /** The system properties of a target whose agent runs on {@code port} with {@code nonce}. */
    private static Properties running(String port, String nonce, String tokenFile, String loadMode) {
        return props(PORT, port, NONCE, nonce, TOKEN_FILE, tokenFile, LOAD_MODE, loadMode);
    }

    private static AttachSession.Published decide(Properties before, Properties now, boolean expired)
            throws AttachFailedException {
        return AttachSession.decide(PID, before, now, OUR_NONCE, expired);
    }

    @Test
    void aFreshLoadIsRecognisedByOurNonce() throws Exception {
        AttachSession.Published p = decide(props(), running("5001", OUR_NONCE, "/d/x.token", "ATTACH"), false);
        assertEquals(new AttachSession.Published("5001", OUR_NONCE, "/d/x.token", "ATTACH", true), p);
    }

    @Test
    void nothingOrAPartialPublishMeansKeepPolling() throws Exception {
        assertNull(decide(props(), props(), false));
        assertNull(decide(props(), props(PORT, "5001"), false), "port without nonce");
        assertNull(decide(props(), props(NONCE, OUR_NONCE), false), "nonce without port");
    }

    @Test
    void aReattachReadsTheRunningAgentsNonceAndTokenFileBack() throws Exception {
        Properties before = running("5001", "their-nonce", "/d/skillroy-1-2.token", "JAVAAGENT");
        // The running agent's idempotent agentmain re-published its own values; ours are ignored.
        AttachSession.Published p = decide(before, running("5001", "their-nonce", "/d/skillroy-1-2.token",
                "JAVAAGENT"), false);
        assertFalse(p.fresh());
        assertEquals("their-nonce", p.nonce(), "the nonce is read back, not ours");
        assertEquals("/d/skillroy-1-2.token", p.tokenFile(), "the token file is read back");
        assertEquals("5001", p.port());
        assertEquals("JAVAAGENT", p.loadMode());
    }

    @Test
    void aReattachThatReusesTheRunningAgentsNonceIsStillAReattach() throws Exception {
        Properties before = running("5001", OUR_NONCE, "/d/t.token", "ATTACH");
        AttachSession.Published p = decide(before, running("5001", OUR_NONCE, "/d/t.token", "ATTACH"), false);
        assertFalse(p.fresh(), "the agent was running before this load: its token, not ours");
        assertEquals("/d/t.token", p.tokenFile());
    }

    @Test
    void aReattachWaitsForTheTokenFileProperty() throws Exception {
        Properties before = props(PORT, "5001", NONCE, "their-nonce");
        Properties now = props(PORT, "5001", NONCE, "their-nonce");
        assertNull(decide(before, now, false));
        AttachSession.Published atDeadline = decide(before, now, true);
        assertFalse(atDeadline.fresh());
        assertNull(atDeadline.tokenFile());
    }

    @Test
    void anAgentThatWasReplacedByThisLoadIsFresh() throws Exception {
        Properties before = running("5001", "old-nonce", "/d/old.token", "ATTACH");
        AttachSession.Published p = decide(before, running("6002", OUR_NONCE, "/d/new.token", "ATTACH"), false);
        assertTrue(p.fresh());
        assertEquals("6002", p.port());
    }

    @Test
    void aForeignNonceIsAcceptedOnlyAtTheDeadlineAndNotAsFresh() throws Exception {
        Properties now = running("5001", "someone-elses", "/d/x.token", "JAVAAGENT");
        assertNull(decide(props(), now, false), "our own agent may still be publishing");
        AttachSession.Published p = decide(props(), now, true);
        assertFalse(p.fresh());
        assertEquals("someone-elses", p.nonce());
    }

    @Test
    void aNewStartErrorFailsAtOnce() {
        AttachFailedException failed = assertThrows(AttachFailedException.class,
                () -> decide(props(), props(ERROR, "boom"), false));
        assertTrue(failed.getMessage().contains("pid 4242"), failed.getMessage());
        assertTrue(failed.getMessage().contains("skillroy.agent.error: boom"), failed.getMessage());
        assertThrows(AttachFailedException.class, () -> decide(props(ERROR, "old"), props(ERROR, "new"), false),
                "a changed error is a new one");
    }

    @Test
    void anOldErrorIsNotANewOneButIsReportedAtTheDeadline() throws Exception {
        assertNull(decide(props(ERROR, "old"), props(ERROR, "old"), false));
        AttachFailedException late = assertThrows(AttachFailedException.class,
                () -> decide(props(ERROR, "old"), props(ERROR, "old"), true));
        assertTrue(late.getMessage().contains("did not publish skillroy.agent.port"), late.getMessage());
        assertTrue(late.getMessage().contains("skillroy.agent.error: old"), late.getMessage());
        AttachFailedException none = assertThrows(AttachFailedException.class, () -> decide(props(), props(), true));
        assertFalse(none.getMessage().contains(ERROR), none.getMessage());
    }

    /** A token file as the agent writes it: owner-only. */
    private Path tokenFile(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        FakeAgent.writeOwnerOnly(file, content);
        return file;
    }

    @Test
    void aFreshLoadUsesTheTokenOfOurOptionsFile() throws Exception {
        Path other = tokenFile("skillroy-" + PID + "-2.token", "a-different-token");
        AttachSession.Published fresh = new AttachSession.Published("5001", OUR_NONCE, other.toString(), "ATTACH",
                true);
        assertEquals(OUR_TOKEN, AttachSession.tokenFor(PID, fresh, OUR_TOKEN, false, dir));
    }

    @Test
    void aReattachUsesTheTokenFromTheRunningAgentsTokenFile() throws Exception {
        Path tokenFile = tokenFile("skillroy-" + PID + "-1790000000000.token", "  their-token \n");
        AttachSession.Published existing = new AttachSession.Published("5001", "their-nonce", tokenFile.toString(),
                "JAVAAGENT", false);
        assertEquals("their-token", AttachSession.tokenFor(PID, existing, OUR_TOKEN, false, dir));
        assertEquals("their-token", AttachSession.tokenFor(PID, existing, OUR_TOKEN, true, dir),
                "the running agent's file wins over a token the caller passed");
    }

    @Test
    void aReattachWithoutAUsableTokenFileFailsClearlyUnlessATokenWasGiven() throws Exception {
        Path missing = dir.resolve("skillroy-" + PID + "-1.token");
        AttachSession.Published existing = new AttachSession.Published("5001", "their-nonce", missing.toString(),
                "JAVAAGENT", false);
        AttachFailedException failed = assertThrows(AttachFailedException.class,
                () -> AttachSession.tokenFor(PID, existing, OUR_TOKEN, false, dir));
        assertTrue(failed.getMessage().contains(missing.toString()), failed.getMessage());
        assertTrue(failed.getMessage().contains("keeps its own token"), failed.getMessage());
        assertEquals(OUR_TOKEN, AttachSession.tokenFor(PID, existing, OUR_TOKEN, true, dir),
                "a token the caller passed explicitly may be the running agent's: the handshake decides");

        Path garbage = tokenFile("skillroy-" + PID + "-3.token", "two words");
        assertThrows(AttachFailedException.class, () -> AttachSession.tokenFor(PID,
                new AttachSession.Published("5001", "n", garbage.toString(), null, false), OUR_TOKEN, false, dir));

        AttachFailedException noProperty = assertThrows(AttachFailedException.class, () -> AttachSession.tokenFor(PID,
                new AttachSession.Published("5001", "n", null, null, false), OUR_TOKEN, false, dir));
        assertTrue(noProperty.getMessage().contains("publishes no skillroy.agent.tokenFile"),
                noProperty.getMessage());
    }

    /**
     * Findings S6/S28: a re-attach reads the token file the target publishes only when it is this pid's token file
     * directly inside the session's discovery directory; a target cannot make the controller read anything else.
     */
    @Test
    void aReattachTokenFileOutsideTheDirectoryOrOtherwiseNamedIsRefused() throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        Path outside = elsewhere.resolve("skillroy-" + PID + "-1.token");
        FakeAgent.writeOwnerOnly(outside, "outside-token");
        Path otherPid = tokenFile("skillroy-" + (PID + 1) + "-1.token", "other-pid-token");
        Path otherName = tokenFile("vault-token", "vault-token-value");
        for (Path file : java.util.List.of(outside, otherPid, otherName)) {
            AttachSession.Published existing = new AttachSession.Published("5001", "n", file.toString(), null, false);
            AttachFailedException refused = assertThrows(AttachFailedException.class,
                    () -> AttachSession.tokenFor(PID, existing, OUR_TOKEN, false, dir), file.toString());
            assertTrue(refused.getMessage().contains("pass that agent's token as the token option"),
                    refused.getMessage());
            assertEquals(OUR_TOKEN, AttachSession.tokenFor(PID, existing, OUR_TOKEN, true, dir),
                    "with the token option the caller's token is used instead");
        }
        assertEquals("outside-token", AttachSession.tokenFor(PID, new AttachSession.Published("5001", "n",
                outside.toString(), null, false), OUR_TOKEN, false, elsewhere), "inside its own directory it is read");
    }

    /** Finding S18: an unlisted pid is attached to only when its executable is java or javaw, or by override. */
    @Test
    void anUnlistedPidIsRefusedUnlessItsExecutableIsJava() {
        java.util.Optional<String> none = java.util.Optional.empty();
        String unreadable = AttachSession.unlistedRefusal(7, none, false);
        assertTrue(unreadable != null && unreadable.contains("cannot be read")
                && unreadable.contains(AttachSession.ALLOW_UNLISTED), unreadable);
        assertTrue(AttachSession.unlistedRefusal(7, java.util.Optional.of("/usr/bin/javascript-helper"), false)
                .contains("/usr/bin/javascript-helper"));
        assertTrue(AttachSession.unlistedRefusal(7, java.util.Optional.of("/usr/bin/sleep"), false) != null);
        assertTrue(AttachSession.unlistedRefusal(7, java.util.Optional.of("/opt/java/bin/javac"), false) != null);
        assertNull(AttachSession.unlistedRefusal(7, java.util.Optional.of("/opt/jdk/bin/java"), false));
        assertNull(AttachSession.unlistedRefusal(7, java.util.Optional.of("C:\\jdk\\bin\\javaw.exe"), false));
        assertNull(AttachSession.unlistedRefusal(7, java.util.Optional.of("C:\\JDK\\BIN\\JAVA.EXE"), false));
        assertNull(AttachSession.unlistedRefusal(7, none, true), "the override allows");
        assertNull(AttachSession.unlistedRefusal(7, java.util.Optional.of("/usr/bin/sleep"), true));
    }
}
