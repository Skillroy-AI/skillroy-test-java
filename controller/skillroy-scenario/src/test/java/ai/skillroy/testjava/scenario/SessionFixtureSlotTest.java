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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase7-contracts.md 9.2 (D425): {@link ExecutorSession#fixture} binds a fixture slot as a plan binds one. A
 * credential fixture is a person's only: for a model or a client it is refused with nothing joined or journaled; for a
 * person the secret is joined and in flight until the person's {@code signOn} consumes it, the {@code fixture} record
 * holds the value as given and never the secret. A fixture without a credential binds for any origin, its references
 * substituted; the failures are a bind's. The export writes the slot back as a fixture, the journal read back exports
 * the same, and a later {@code resolve} of the slot replaces it.
 */
class SessionFixtureSlotTest {

    private static final Origin PERSON = Origin.person("tester");
    private static final Origin MODEL = Origin.model("a-model", "the next action", "req-1");
    private static final Origin CLIENT = Origin.client("a-client");
    private static final CredentialSpec STAFF = new CredentialSpec("staff", "code");

    @TempDir
    Path dir;

    private static List<StepJournal.FixtureEntry> fixtures(ExecutorSession session) {
        return session.journal().entries().stream().filter(e -> e instanceof StepJournal.FixtureEntry)
                .map(e -> (StepJournal.FixtureEntry) e).toList();
    }

    @Test
    void aCredentialFixtureIsAPersonsAndItsSecretIsInFlightUntilThePersonSignsOn() throws Exception {
        SessionFixture fx = new SessionFixture(dir);
        try (ExecutorSession session = fx.open()) {
            for (Origin origin : List.of(MODEL, CLIENT)) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> session.fixture(
                        "assistant", Map.of("code", "100"), STAFF, origin));
                assertTrue(e.getMessage().contains(ExecutorSession.FIXTURE_CREDENTIAL_REFUSED), e.getMessage());
            }
            assertTrue(fixtures(session).isEmpty(), "nothing journaled");
            assertFalse(session.secretsInFlight(), "nothing joined");

            Object value = session.fixture("assistant", Map.of("code", "100"), STAFF, PERSON);
            assertEquals(Map.of("code", "100"), value);
            assertTrue(session.secretsInFlight(), "the person's fixture joined a secret");
            StepJournal.FixtureEntry entry = fixtures(session).get(0);
            assertEquals("assistant", entry.slot());
            assertEquals(Map.of("code", "100"), entry.value());
            assertEquals(STAFF, entry.credential());
            assertEquals(Origin.Kind.PERSON, entry.origin().kind());

            StepOutcome typed = session.execute("type", Map.of("by", Map.of("id", "entry"), "text", "1234"), MODEL);
            assertEquals(Status.ERROR, typed.status(), "a model's typing waits for the person's sign-on");
            StepOutcome signOn = session.execute("signOn", Map.of("staff", "$assistant"), PERSON);
            assertEquals(Status.PASS, signOn.status(), signOn.message());
            assertFalse(session.secretsInFlight(), "the person's sign-on consumed it");
            assertEquals(Status.ERROR, session.execute("signOn", Map.of("staff", "$assistant"), MODEL).status(),
                    "a model's sign-on stays refused");
            List<FakeAgentConnection.Call> inputs = fx.agent.callsTo("toy.input");
            assertEquals(TestCredentials.PW_100, inputs.get(inputs.size() - 1).params().get("value"),
                    "the joined password reached the agent on the person's step");
            String journal = Files.readString(session.journal().file());
            assertFalse(journal.contains(TestCredentials.PW_100), "the journal holds no secret");
            assertTrue(journal.contains("\"type\":\"fixture\""), journal);
        }
    }

    @Test
    void aFixtureWithoutACredentialBindsForAnyOriginWithItsReferencesSubstituted() throws Exception {
        SessionFixture fx = new SessionFixture(dir);
        try (ExecutorSession session = fx.open()) {
            session.fixture("item", Map.of("code", "B2", "barcode", "5000"), null, PERSON);
            Object derived = session.fixture("again", Map.of("same", "$item.code"), null, MODEL);
            assertEquals(Map.of("same", "B2"), derived);
            assertFalse(session.secretsInFlight());
            assertEquals(Map.of("same", "$item.code"), fixtures(session).get(1).value(), "journaled as given");
            assertEquals(Status.PASS, session.execute("enter", Map.of("value", "$item.code"), MODEL).status());
            assertEquals("B2", fx.agent.callsTo("toy.input").get(0).params().get("value"));
        }
    }

    @Test
    void theFailuresAreABindsAndLeaveTheSlotUnbound() throws Exception {
        SessionFixture fx = new SessionFixture(dir);
        try (ExecutorSession session = fx.open()) {
            BindException noKey = assertThrows(BindException.class, () -> session.fixture("assistant",
                    Map.of("name", "x"), STAFF, PERSON));
            assertEquals(FailureKind.ENVIRONMENT, noKey.kind());
            BindException noEntry = assertThrows(BindException.class, () -> session.fixture("assistant",
                    Map.of("code", "999"), STAFF, PERSON));
            assertEquals(FailureKind.ENVIRONMENT, noEntry.kind());
            assertTrue(noEntry.getMessage().contains("999"), noEntry.getMessage());
            BindException clash = assertThrows(BindException.class, () -> session.fixture("assistant",
                    Map.of("code", "100", "password", "x"), STAFF, PERSON));
            assertEquals(FailureKind.ENVIRONMENT, clash.kind());
            assertFalse(clash.getMessage().contains(TestCredentials.PW_100), "never a value");
            BindException reference = assertThrows(BindException.class, () -> session.fixture("item",
                    Map.of("code", "$nowhere.code"), null, PERSON));
            assertEquals(FailureKind.RESOLUTION_FAILED, reference.kind());
            assertTrue(fixtures(session).isEmpty(), "nothing journaled");
            assertFalse(session.secretsInFlight());
            assertThrows(IllegalArgumentException.class, () -> session.fixture("env", Map.of(), null, PERSON),
                    "a reserved name");
        }
    }

    @Test
    void theExportWritesTheSlotBackAsAFixtureAndALaterResolveReplacesIt() throws Exception {
        SessionFixture fx = new SessionFixture(dir.resolve("a"));
        try (ExecutorSession session = fx.open()) {
            session.fixture("assistant", Map.of("code", "100"), STAFF, PERSON);
            assertEquals(Status.PASS, session.execute("signOn", Map.of("staff", "$assistant"), PERSON).status());
            ExportResult export = session.journal().export(ExportOptions.of("fixture-sign-on"));
            assertTrue(export.yaml().contains("assistant: { fixture: { code: \"100\" }, credential: { realm: staff,"
                    + " key: code } }"), export.yaml());
            assertTrue(export.yaml().contains("- signOn: { staff: $assistant }"), export.yaml());
            assertNull(export.bindings(), "a fixture needs no bindings file");
            assertFalse(export.yaml().contains(TestCredentials.PW_100));
            ExportResult fromFile = StepJournal.read(session.journal().file(), fx.pack)
                    .export(ExportOptions.of("fixture-sign-on"));
            assertEquals(export.yaml(), fromFile.yaml(), "the journal read back exports the same");
        }
        SessionFixture fy = new SessionFixture(dir.resolve("b"));
        try (ExecutorSession session = fy.open()) {
            session.fixture("item", Map.of("code", "B2"), null, PERSON);
            session.resolve("item", "Item", Map.of("deposit", Map.of("between", List.of("2.00", "50.00"))),
                    List.of(), List.of(), null, PERSON);
            ExportResult export = session.journal().export(ExportOptions.of("replaced"));
            assertTrue(export.yaml().contains("item: { query: Item"), export.yaml());
            assertFalse(export.yaml().contains("fixture:"), export.yaml());
            assertTrue(export.report().notes().stream().anyMatch(n -> n.contains("bound more than once")),
                    String.valueOf(export.report().notes()));
        }
    }
}
