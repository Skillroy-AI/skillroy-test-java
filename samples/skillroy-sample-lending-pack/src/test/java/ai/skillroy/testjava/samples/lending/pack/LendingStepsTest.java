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

package ai.skillroy.testjava.samples.lending.pack;

import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each lending verb compiles to exactly the planned calls of contract section 9.3 (and amendment A1), with the Phase 2
 * changes of phase2-contracts.md 13.3: {@code signOn} and the new {@code authorise} take only a slot with a credential,
 * and {@code key} accepts a reference into an {@code ActionKey} slot.
 */
class LendingStepsTest {

    /** A compile context that records cleanups and prompt handlers and declares a few slots. */
    static final class Ctx implements CompileContext {
        final List<PlannedCall> cleanups = new ArrayList<>();
        final List<PlannedCall> handlers = new ArrayList<>();
        long stepTimeoutMs = 10_000L;
        final Map<String, SlotInfo> slots = new LinkedHashMap<>(Map.of(
                "assistant", new SlotInfo("assistant", "query", "StaffWithPermission",
                        new CredentialSpec("staff", "code"), Set.of()),
                "supervisor", new SlotInfo("supervisor", "fixture", null, new CredentialSpec("staff", "code"),
                        Set.of()),
                "clerk", new SlotInfo("clerk", "fixture", null, null, Set.of()),
                "keys", new SlotInfo("keys", "query", "ActionKey", null, Set.of()),
                "item", new SlotInfo("item", "query", "LendableItem", null, Set.of()),
                "loan", new SlotInfo("loan", "capture", null, null, Set.of("id"))));

        @Override
        public SlotInfo slot(String name) {
            return slots.get(name);
        }

        @Override
        public void addCleanup(PlannedCall call) {
            cleanups.add(call);
        }

        @Override
        public int sourceLine() {
            return 7;
        }

        @Override
        public DomainPack pack() {
            return new LendingPack();
        }

        @Override
        public long stepTimeoutMs() {
            return stepTimeoutMs;
        }

        @Override
        public void markHandlesPrompt(PlannedCall call) {
            handlers.add(call);
        }

        /** The step's {@code minRung} modifier (phase8-contracts.md 7.3, ES). */
        String minRung;
        /** The scenario's {@code ui} settings (ES). */
        ai.skillroy.testjava.scenario.UiSettings ui = ai.skillroy.testjava.scenario.UiSettings.DEFAULT;

        @Override
        public String minRung() {
            return minRung;
        }

        @Override
        public ai.skillroy.testjava.scenario.UiSettings ui() {
            return ui;
        }
    }

    private final Ctx ctx = new Ctx();

    private List<PlannedCall> compile(String verb, Object args) throws ScenarioException {
        return LendingSteps.INSTANCE.compile(verb, args, ctx);
    }

    private static PlannedCall input(String source, String value) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("source", source);
        if (value != null) {
            args.put("value", value);
        }
        return new PlannedCall("lending.input", args, Sync.READY, 0);
    }

    @Test
    void theVerbsAreTheContractsList() {
        assertEquals(List.of("signOn", "signOff", "scan", "key", "enter", "quantity", "deposit", "expectPrompt",
                "answerPrompt", "authorise", "signOnAny", "scanChecked", "deviceScan"),
                new ArrayList<>(LendingSteps.INSTANCE.verbs()), "Phase 8 adds the last three (ES)");
    }

    @Test
    void signOnSendsCodeThenPasswordAndRegistersTheSignOffCleanup() throws Exception {
        assertEquals(List.of(input("Entry", "$assistant.code"), input("Entry", "$assistant.password")),
                compile("signOn", Map.of("staff", "$assistant")));
        assertEquals(List.of(input("Key.SIGN_OFF", null)), ctx.cleanups);
        assertEquals(List.of(input("Entry", "$supervisor.code"), input("Entry", "$supervisor.password")),
                compile("signOn", Map.of("staff", "$supervisor")), "a fixture slot with a credential also works");
        assertEquals(2, ctx.cleanups.size());
        assertTrue(ctx.handlers.isEmpty());
    }

    /**
     * Phase 2 (3.1 rule 5, 13.3): the mapping form with a password is gone, and a slot without a credential is a
     * compile error; the message says how to fix it.
     */
    @Test
    void signOnAndAuthoriseNeedASlotWithACredential() {
        Map<String, Object> literal = new LinkedHashMap<>();
        literal.put("code", "200");
        literal.put("password", "3333");
        for (String verb : List.of("signOn", "authorise")) {
            ScenarioException mapping = assertThrows(ScenarioException.class,
                    () -> compile(verb, Map.of("staff", literal)));
            assertTrue(mapping.getMessage().contains("credential"), mapping.getMessage());
            ScenarioException noCredential = assertThrows(ScenarioException.class,
                    () -> compile(verb, Map.of("staff", "$clerk")));
            assertTrue(noCredential.getMessage().contains("slot 'clerk' has no credential"),
                    noCredential.getMessage());
            ScenarioException unknown = assertThrows(ScenarioException.class,
                    () -> compile(verb, Map.of("staff", "$nobody")));
            assertTrue(unknown.getMessage().contains("not a slot"), unknown.getMessage());
            assertThrows(ScenarioException.class, () -> compile(verb, Map.of("staff", "$assistant.code")));
            assertThrows(ScenarioException.class, () -> compile(verb, Map.of("staff", "100")));
        }
        assertTrue(ctx.cleanups.isEmpty(), "nothing registered by a verb that failed");
    }

    /**
     * {@code authorise}: answers the supervisor prompt with OK (EVENT on the ready topics, marked as handling the
     * prompt), then enters the supervisor's code and password, each READY. It registers no cleanup.
     */
    @Test
    void authoriseAnswersTheSupervisorPromptThenEntersCodeAndPassword() throws Exception {
        List<PlannedCall> calls = compile("authorise", Map.of("staff", "$supervisor"));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("choice", "OK");
        answer.put("id", "supervisor-required");
        assertEquals(List.of(PlannedCall.event("lending.prompt.answer", answer,
                        new AwaitSpec("input.requested,prompt.waiting", null, 0)),
                input("Entry", "$supervisor.code"), input("Entry", "$supervisor.password")), calls);
        assertEquals(1, ctx.handlers.size());
        assertSame(calls.get(0), ctx.handlers.get(0));
        assertTrue(ctx.cleanups.isEmpty());
    }

    /** {@code key} with a reference into an {@code ActionKey} slot; any other reference is a compile error. */
    @Test
    void aKeyActionMayBeAReferenceIntoAnActionKeySlot() throws Exception {
        assertEquals(List.of(input("Key.$keys.RETURN", null)), compile("key", Map.of("action", "$keys.RETURN")));
        assertEquals(List.of(input("Key.$keys.DUE", "2")), compile("key", Map.of("action", "$keys.DUE",
                "value", 2L)));
        for (String bad : List.of("$item.code", "$loan.id", "$nobody.RETURN", "$keys", "$keys.RETURN.x",
                "$assistant.code")) {
            assertThrows(ScenarioException.class, () -> compile("key", Map.of("action", bad)), bad);
        }
    }

    @Test
    void scanRepeatsTheBarcodeInput() throws Exception {
        assertEquals(List.of(input("Barcode", "$item.barcode"), input("Barcode", "$item.barcode")),
                compile("scan", Map.of("item", "$item", "times", 2L)));
        assertEquals(List.of(input("Barcode", "5000000000017")), compile("scan", Map.of("barcode", "5000000000017")));
        assertEquals(List.of(input("Barcode", "4006000000011")),
                compile("scan", Map.of("item", Map.of("barcode", "4006000000011", "code", "T1001"))));
    }

    @Test
    void keysEntriesQuantitiesAndDepositsAreSingleReadyInputs() throws Exception {
        assertEquals(List.of(input("Key.DUE", null)), compile("key", Map.of("action", "DUE")));
        assertEquals(List.of(input("Key.QTY", "3")), compile("key", Map.of("action", "QTY", "value", 3L)));
        assertEquals(List.of(input("Entry", "YRD-001")), compile("enter", Map.of("value", "YRD-001")));
        assertEquals(List.of(input("Entry", "$item.code")), compile("enter", Map.of("value", "$item.code")));
        assertEquals(List.of(input("Key.QTY", "2")), compile("quantity", Map.of("n", 2L)));
        assertEquals(List.of(input("Key.QTY", "0.25")), compile("quantity", Map.of("n", 0.25)));
        assertEquals(List.of(input("Key.DEPOSIT", "7.00")), compile("deposit", Map.of("amount", "7.00")));
        assertEquals(List.of(input("Key.DEPOSIT", "7.5")), compile("deposit", Map.of("amount", 7.5)));
        assertEquals(List.of(input("Key.DEPOSIT", null)), compile("deposit", Map.of()));
        assertEquals(List.of(input("Key.DEPOSIT", null)), compile("deposit", null));
        assertEquals(List.of(input("Key.SIGN_OFF", null)), compile("signOff", null));
        assertEquals(List.of(input("Key.SIGN_OFF", null)), compile("signOff", Map.of()));
        assertTrue(ctx.cleanups.isEmpty());
    }

    @Test
    void expectPromptAwaitsAPromptEventAndAnswerPromptIsCompletedByTheReadyTopics() throws Exception {
        Map<String, Object> await = new LinkedHashMap<>();
        await.put("topics", "prompt.waiting");
        await.put("match", Map.of("text", "Unknown item"));
        await.put("timeoutMs", 5000L);
        assertEquals(List.of(new PlannedCall("sync.await", await, Sync.NONE, 6000)),
                compile("expectPrompt", Map.of("text", "Unknown item", "within", "5s")));

        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("topics", "prompt.waiting");
        plain.put("timeoutMs", 10_000L);
        assertEquals(List.of(new PlannedCall("sync.await", plain, Sync.NONE, 11_000)), compile("expectPrompt", null));
        ctx.stepTimeoutMs = 500_000L;
        assertEquals(119_000L, compile("expectPrompt", Map.of()).get(0).args().get("timeoutMs"),
                "capped at the agent's call limit");

        List<PlannedCall> answer = compile("answerPrompt", Map.of("choice", "OK"));
        assertEquals(List.of(PlannedCall.event("lending.prompt.answer", Map.of("choice", "OK"),
                new AwaitSpec("input.requested,prompt.waiting", null, 0))), answer);
        assertEquals(1, ctx.handlers.size());
        assertSame(answer.get(0), ctx.handlers.get(0), "the answering call is marked as handling the prompt");
        assertEquals(Map.of("choice", "Yes", "id", "induction-check"),
                compile("answerPrompt", Map.of("choice", "Yes", "id", "induction-check")).get(0).args());
    }

    @Test
    void wrongArgumentsAreCompileErrors() {
        for (Object[] bad : new Object[][] {
            {"signOn", Map.of("staff", 100L)},
            {"signOn", Map.of("staff", "$assistant", "extra", 1L)},
            {"signOn", Map.of("staff", Map.of("code", "100"))},
            {"signOn", Map.of("staff", Map.of("code", "100", "password", "1111"))},
            {"authorise", Map.of()},
            {"authorise", Map.of("staff", "$supervisor", "choice", "OK")},
            {"signOn", "$assistant"},
            {"scan", Map.of("item", "$item", "barcode", "1")},
            {"scan", Map.of()},
            {"scan", Map.of("barcode", "1", "times", 0L)},
            {"scan", Map.of("barcode", "1", "times", "2")},
            {"scan", Map.of("barcode", "1", "times", 100L)},
            {"key", Map.of("action", "PRINT")},
            {"key", Map.of()},
            {"enter", Map.of()},
            {"enter", Map.of("value", "")},
            {"enter", Map.of("value", List.of("a"))},
            {"quantity", Map.of()},
            {"deposit", Map.of("amount", "1.00", "note", "x")},
            {"deposit", Map.of("amount", List.of("1.00"))},
            {"deposit", "10.00"},
            {"signOff", Map.of("staff", "$assistant")},
            {"expectPrompt", Map.of("within", "200s")},
            {"expectPrompt", Map.of("button", "OK")},
            {"answerPrompt", Map.of()},
            {"teleport", Map.of()},
        }) {
            assertThrows(ScenarioException.class, () -> compile((String) bad[0], bad[1]),
                    bad[0] + " " + bad[1]);
        }
    }

    @Test
    void unknownVerbsAreNotPackVerbs() {
        Set<String> verbs = LendingSteps.INSTANCE.verbs();
        assertTrue(!verbs.contains("click") && !verbs.contains("expect"), "base verbs stay with the base library");
    }

    // ------------------------------------------------------------------ phase8-contracts.md 7.5, 7.6, 12.3 (ES)

    @Test
    void signOnAnySendsThePasswordOnlyForASlotWithACredential() throws Exception {
        assertEquals(List.of(new PlannedCall("lending.signOn", Map.of("staff", "$assistant.code", "password",
                "$assistant.password"), Sync.READY, 0)), compile("signOnAny", Map.of("staff", "$assistant")));
        assertEquals(List.of(new PlannedCall("lending.signOn", Map.of("staff", "$clerk.code"), Sync.READY, 0)),
                compile("signOnAny", Map.of("staff", "$clerk")), "a slot without a credential: no password");
        assertEquals(List.of(input("Key.SIGN_OFF", null), input("Key.SIGN_OFF", null)), ctx.cleanups,
                "signOn's cleanup");
        assertTrue(ctx.handlers.isEmpty(), "the composite's own prompts are reconciled by the executor");
        assertTrue(compile("signOnAny", Map.of("staff", "$clerk")).get(0).accept().isEmpty(),
                "the verb pre-accepts nothing");
        for (Object bad : List.of("100", "$nobody", "$assistant.code")) {
            assertThrows(ScenarioException.class, () -> compile("signOnAny", Map.of("staff", bad)), "" + bad);
        }
    }

    @Test
    void scanCheckedSendsTheBarcodeAndTheAnswer() throws Exception {
        assertEquals(List.of(new PlannedCall("lending.scanChecked", Map.of("barcode", "$item.barcode", "inducted",
                true), Sync.READY, 0)), compile("scanChecked", Map.of("item", "$item", "inducted", true)));
        assertEquals(List.of(new PlannedCall("lending.scanChecked", Map.of("barcode", "5000000000086", "inducted",
                false), Sync.READY, 0)), compile("scanChecked", Map.of("barcode", "5000000000086", "inducted",
                false)));
        assertThrows(ScenarioException.class, () -> compile("scanChecked", Map.of("barcode", "1")));
        assertThrows(ScenarioException.class, () -> compile("scanChecked", Map.of("barcode", "1", "inducted",
                "yes")));
        assertThrows(ScenarioException.class, () -> compile("scanChecked", Map.of("inducted", true)));
    }

    @Test
    void deviceScanActsThroughTheScannerWithItsLadderAndForwardsMinRung() throws Exception {
        PlannedCall plain = compile("deviceScan", Map.of("barcode", "5000000000017")).get(0);
        assertEquals("device.act", plain.primitive());
        assertEquals(Map.of("id", "scanner", "action", "scan", "args", Map.of("barcode", "5000000000017")),
                plain.args());
        assertEquals(Sync.READY, plain.sync());
        assertEquals(List.of("PHYSICAL", "SEMANTIC"), plain.rungs().stream().map(r -> r.name()).toList());
        assertEquals("lending.scanner.physical", plain.rungs().get(0).capability());
        assertEquals(null, plain.rungs().get(1).capability());
        ctx.minRung = "PHYSICAL";
        assertEquals(Map.of("barcode", "$item.barcode", "minRung", "PHYSICAL"), compile("deviceScan",
                Map.of("item", "$item")).get(0).args().get("args"), "the step's own minRung is forwarded");
        ctx.minRung = null;
        ctx.ui = new ai.skillroy.testjava.scenario.UiSettings("ACTION", true);
        assertEquals("PHYSICAL", ((Map<?, ?>) compile("deviceScan", Map.of("barcode", "1")).get(0).args()
                .get("args")).get("minRung"), "under strict the first rung");
        assertThrows(ScenarioException.class, () -> compile("deviceScan", Map.of()));
    }
}
