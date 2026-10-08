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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.Origin;
import ai.skillroy.testjava.scenario.SessionOptions;
import ai.skillroy.testjava.scenario.Status;
import ai.skillroy.testjava.scenario.StepOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The verbs of the end-to-end check of phase5-contracts.md 15.2 item 5 through the session entry
 * ({@code ExecutorSession.execute}, which the verb tools of a {@code skillroy mcp --mutating} server call), natively on
 * the desk's real engine ({@link HeadlessDesk}, seed A), so that the states that check asserts in the Linux image are
 * established here: entering the code of {@link InferenceChecks#AUTO_SIGN_ON_ASSISTANT} signs that assistant on (the
 * desk IDLE), {@code signOff} signs them off (SIGNED_OFF), each step PASS, the journal as
 * {@link InferenceChecks#assertJournalSignedOnAndOff} reads it. And why the check had to change: {@code signOff} on a
 * desk that is signed off already is refused by the desk, so a check that sends it alone and asserts only that a result
 * came back proves nothing.
 */
class SessionEntryVerbTest {

    /** The origin an MCP server's calls are journaled with. */
    private static final Origin MCP = Origin.person("mcp");

    @TempDir
    Path dir;

    private ExecutorSession open(HeadlessDesk desk, String id) throws Exception {
        return ExecutorSession.open(Runs.PACK, desk, SessionOptions.defaults(dir.resolve(id)).withBind(
                BindOptions.defaults().withEnvId(Env.SEED_A.id).withCredentials(desk.credentials(dir.resolve(id
                        + "-credentials")))));
    }

    @Test
    void enteringTheAutoSignOnAssistantsCodeSignsOnAndSignOffSignsOff() throws Exception {
        try (HeadlessDesk desk = HeadlessDesk.launch(Env.SEED_A)) {
            Path journal;
            try (ExecutorSession session = open(desk, "on-off")) {
                assertEquals("SIGNED_OFF", desk.status().state().name());
                StepOutcome on = session.execute("enter", Map.of("value", InferenceChecks.AUTO_SIGN_ON_ASSISTANT),
                        MCP);
                assertEquals(Status.PASS, on.status(), on.message());
                assertEquals("IDLE", desk.status().state().name(), "signed on by code alone");
                assertEquals(InferenceChecks.AUTO_SIGN_ON_ASSISTANT, desk.status().staff().code());
                StepOutcome off = session.execute("signOff", Map.of(), MCP);
                assertEquals(Status.PASS, off.status(), off.message());
                assertEquals("SIGNED_OFF", desk.status().state().name());
                journal = session.journal().file();
            }
            InferenceChecks.assertJournalSignedOnAndOff(journal);
        }
    }

    @Test
    void signOffOnADeskThatIsSignedOffAlreadyIsRefused() throws Exception {
        try (HeadlessDesk desk = HeadlessDesk.launch(Env.SEED_A); ExecutorSession session = open(desk, "off")) {
            StepOutcome off = session.execute("signOff", Map.of(), MCP);
            assertFalse(off.succeeded(), off.status() + ": " + off.message());
            assertEquals("SIGNED_OFF", desk.status().state().name());
        }
    }
}
