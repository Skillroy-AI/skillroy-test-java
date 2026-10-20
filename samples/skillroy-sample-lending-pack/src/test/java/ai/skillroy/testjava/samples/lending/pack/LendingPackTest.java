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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.PlanCompiler;
import ai.skillroy.testjava.scenario.pack.AwaitSpec;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.PromptPolicy;
import ai.skillroy.testjava.scenario.pack.Sync;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.ColumnKind;
import ai.skillroy.testjava.scenario.resolve.StoredColumn;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pack as a whole: discovery, sync policy, facts, pre-flight, recovery, and a compiled plan. */
class LendingPackTest {

    private final LendingPack pack = new LendingPack();

    /** Phase 7 (phase7-contracts.md 12.3): the keypad legend for a model that looks at the screen. */
    @Test
    void theScreenLegendNamesTheKeypadKeysLeftToRightTheEntryFieldTheNumberPadAndThePrompt() {
        String legend = pack.screenLegend();
        assertEquals(LendingPack.SCREEN_LEGEND, legend);
        int at = -1;
        for (String key : List.of("QTY", "CANCEL", "RETURN", "DUE", "DEPOSIT", "SIGN_OFF")) {
            int i = legend.indexOf(key + " ");
            assertTrue(i > at, key + " in order in: " + legend);
            at = i;
        }
        assertTrue(legend.contains("entry field") && legend.contains("number pad") && legend.contains("prompt"));
        assertFalse(legend.contains("\n"), "one paragraph");
    }

    @Test
    void thePackIsFoundByServiceLoaderAndDeclaresTheLendingSyncPolicy() {
        List<DomainPack> packs = new ArrayList<>();
        ServiceLoader.load(DomainPack.class).forEach(packs::add);
        assertEquals(1, packs.size());
        assertTrue(packs.get(0) instanceof LendingPack);
        assertEquals("lending", pack.id());
        assertEquals("0.6.0", pack.version());
        assertEquals(new SyncPolicy("input.acked", List.of("input.requested", "prompt.waiting"),
                List.of("prompt.waiting"), null, null, true), pack.syncPolicy(),
                "acknowledgements correlated by inputId (phase8-contracts.md 7.4)");
        assertEquals(java.util.Set.of("lending.PASSWORD_REQUIRED"), pack.warningCodes(),
                "the pack's warning code (phase8-contracts.md 7.3)");
        assertSame(PromptPolicy.FAIL, pack.promptPolicy());
        assertEquals(List.of("loan", "state", "slip", "prompt"), pack.facts().stream().map(FactProvider::root)
                .toList());
        assertEquals(List.of("lending.database"), pack.preflight().stream().map(p -> p.id()).toList());
    }

    @Test
    void factsReadTheLendingPrimitivesAndListSlipsOldestFirst() throws Exception {
        FakeDesk desk = new FakeDesk();
        desk.slips = List.of(Map.of("loanId", 1002L, "text", "newest"), Map.of("loanId", 1001L));
        FactProvider slip = pack.facts().stream().filter(f -> f.root().equals("slip")).findFirst()
                .orElseThrow();
        assertEquals(List.of(Map.of("loanId", 1001L), Map.of("loanId", 1002L, "text", "newest")),
                slip.fetch(desk));
        assertEquals(List.of("lending.slip"), desk.calls);
        FactProvider state = pack.facts().stream().filter(f -> f.root().equals("state")).findFirst().orElseThrow();
        assertEquals("SIGNED_OFF", ((Map<?, ?>) state.fetch(desk)).get("name"));
        FactProvider prompt = pack.facts().stream().filter(f -> f.root().equals("prompt")).findFirst().orElseThrow();
        assertNull(prompt.fetch(desk));
    }

    /**
     * The pre-flight reads the staff count through a data channel: through the agent's {@code data.query} in its
     * Phase 1 form, and through the context's channel in the Phase 2 form the executor calls (phase2-contracts.md
     * 6.1, 13.3), so that it passes where source {@code lending} is served over JDBC.
     */
    @Test
    void thePreflightCheckAsksTheLendingDatabaseThroughTheDataChannel() throws Exception {
        FakeDesk desk = new FakeDesk();
        Map<String, Object> count = Map.of("columns", List.of(Map.of("name", "STAFF", "type", "BIGINT")), "rows",
                List.of(List.of(7L)), "rowCount", 1L);
        desk.dataQuery = count;
        PreflightCheck check = pack.preflight().get(0);
        assertNull(check.check(desk));
        desk.dataQuery = Map.of("columns", List.of(Map.of("name", "STAFF", "type", "BIGINT")), "rows",
                List.of(List.of(0L)), "rowCount", 1L);
        assertEquals("the lending database has no staff: 1 row(s)", check.check(desk));
        desk.dataQuery = new RpcError(ErrorCodes.NOT_FOUND, "unknown source lending", null);
        String failed = check.check(desk);
        assertTrue(failed.startsWith("the lending database could not be read (UNKNOWN_SOURCE, NOT_FOUND): "),
                failed);

        List<String> asked = new ArrayList<>();
        DataChannel jdbc = new DataChannel() {
            @Override
            public boolean serves(String source) {
                return "lending".equals(source);
            }

            @Override
            public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout) {
                asked.add(source + ": " + sql + " " + params + " " + maxRows);
                return Rows.of(List.of(new Rows.Column("STAFF", "BIGINT")), List.of(List.of(3L)), false);
            }

            @Override
            public String kind(String source) {
                return "jdbc";
            }
        };
        FakeDesk untouched = new FakeDesk();
        String viaContext = check.check(new PreflightCheck.PreflightContext() {
            @Override
            public AgentConnection agent() {
                return untouched;
            }

            @Override
            public DataChannel data() {
                return jdbc;
            }

            @Override
            public String envId() {
                return "seed-a";
            }
        });
        assertNull(viaContext);
        assertEquals(List.of("lending: SELECT COUNT(*) AS staff FROM staff [] 1"), asked);
        assertEquals(List.of(), untouched.calls, "the context's channel was used, not the agent");
    }

    /** The stored tables of 13.3 and the row mask, which repeats the agent module's MaskPolicy. */
    @Test
    void theStoredTablesAndTheRowMaskAreThePacksDeclaration() {
        assertEquals(List.of("loan", "loan_line", "loan_waiver", "loan_authorisation"),
                pack.storedTables().stream().map(StoredTable::name).toList());
        StoredTable line = pack.storedTables().get(1);
        assertEquals("lending", line.source());
        assertEquals(List.of("loan_id", "line_no", "item_code", "qty", "unit_deposit", "amount", "returned"),
                line.columns().stream().map(StoredColumn::name).toList());
        assertEquals(List.of(ColumnKind.INTEGER, ColumnKind.INTEGER, ColumnKind.TEXT, ColumnKind.DECIMAL,
                ColumnKind.DECIMAL, ColumnKind.DECIMAL, ColumnKind.BOOLEAN),
                line.columns().stream().map(StoredColumn::kind).toList());
        assertEquals("CAST(? AS DECIMAL(10,3))", line.column("qty").paramSql());
        assertEquals("CAST(? AS DECIMAL(10,2))", line.column("amount").paramSql());
        assertEquals("?", line.column("item_code").paramSql());
        assertEquals(List.of("loan_id", "line_no"), line.orderBy());
        assertEquals(List.of("id"), pack.storedTables().get(0).orderBy());
        assertEquals(List.of("loan_id", "kind", "staff_code"), pack.storedTables().get(3).orderBy());

        RowMask mask = pack.rowMasks().get(0);
        assertEquals("<redacted>", mask.mask("lending", "PASSWORD", "1111"));
        assertEquals("<redacted>", mask.mask("lending", "password", 1111L));
        assertNull(mask.mask("lending", "password", null), "null stays null");
        assertEquals("1111", mask.mask("lending", "P", "1111"), "a renamed column is not masked");
        assertEquals("1111", mask.mask("lending2", "password", "1111"), "only source lending");
        assertEquals("1111", mask.mask("LENDING", "password", "1111"), "the source id exactly");
        assertEquals("Alice", mask.mask("lending", "NAME", "Alice"));
    }

    @Test
    void recoverAnswersThePromptCancelsTheLoanAndSignsOff() throws Exception {
        FakeDesk desk = new FakeDesk();
        desk.state = "LOAN";
        desk.promptId = "unknown-item";
        desk.promptButtons = List.of("OK");
        pack.recover(desk);
        assertEquals("SIGNED_OFF", desk.state);
        assertEquals(List.of("lending.prompt", "lending.prompt.answer OK", "await input.requested,prompt.waiting",
                "lending.prompt", "lending.state", "lending.input Key.CANCEL", "await input.requested,prompt.waiting",
                "lending.prompt", "lending.state", "lending.input Key.SIGN_OFF", "await input.requested,prompt.waiting",
                "lending.prompt", "lending.state"), desk.calls);
    }

    @Test
    void recoverDoesNotWaitForAnEventAfterAnsweringAPromptTheModuleDoesNotListenTo() throws Exception {
        FakeDesk desk = new FakeDesk();
        desk.state = "LOAN";
        desk.promptId = "unknown-item";
        desk.promptButtons = List.of("OK");
        desk.listening = false;
        pack.recover(desk);
        assertEquals("SIGNED_OFF", desk.state);
        assertEquals(List.of("lending.prompt", "lending.prompt.answer OK",
                "lending.prompt", "lending.state", "lending.input Key.CANCEL", "await input.requested,prompt.waiting",
                "lending.prompt", "lending.state", "lending.input Key.SIGN_OFF", "await input.requested,prompt.waiting",
                "lending.prompt", "lending.state"), desk.calls, "no event wait after the answer: none can come");
    }

    @Test
    void recoverIsIdempotentAndHandlesLockedAndPasswordStates() throws Exception {
        FakeDesk idle = new FakeDesk();
        pack.recover(idle);
        assertEquals(List.of("lending.prompt", "lending.state"), idle.calls, "nothing to do when already signed off");
        for (String state : List.of("LOCKED", "PASSWORD", "DEPOSIT", "IDLE", "RETURN")) {
            FakeDesk desk = new FakeDesk();
            desk.state = state;
            pack.recover(desk);
            assertEquals("SIGNED_OFF", desk.state, state);
        }
    }

    @Test
    void recoverGivesUpAfterABoundedNumberOfAttempts() {
        FakeDesk stuck = new FakeDesk();
        stuck.state = "LOAN";
        stuck.rejectInputs = true;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> pack.recover(stuck));
        assertTrue(e.getMessage().contains("was not signed off"), e.getMessage());
        assertEquals(LendingRecovery.ROUNDS * 3, stuck.calls.size(), "prompt, state and input per round");
    }

    /**
     * Amendment A31: a wait that the agent answers with a JSON-RPC error is an {@code AgentRpcException}, no longer a
     * plain timeout. Recover does not take it for "nothing happened yet" and go round again: it stops at once and
     * reports the error, which the executor logs.
     */
    @Test
    void recoverStopsAtAWaitTheAgentAnswersWithAnError() {
        FakeDesk desk = new FakeDesk();
        desk.state = "LOAN";
        desk.awaitError = new RpcError(ErrorCodes.INVALID_PARAMS, "bad topic filter", null);
        AgentRpcException e = assertThrows(AgentRpcException.class, () -> pack.recover(desk));
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertEquals(List.of("lending.prompt", "lending.state", "lending.input Key.CANCEL",
                "await input.requested,prompt.waiting"), desk.calls, "no further action after the failed wait");
    }

    @Test
    void aScenarioCompilesToLendingInputsWithReadyEventWaitsAndTheSignOffCleanup() throws Exception {
        String yaml = """
                scenario: pack-check
                pack: lending
                resolve:
                  assistant: { fixture: { code: "100" }, credential: { realm: staff, key: code } }
                  item: { fixture: { code: YRD-001, barcode: "5000000000017" } }
                steps:
                  - signOn: { staff: $assistant }
                  - scan: { item: $item, times: 2 }
                  - scan: { barcode: "0000000000000" }
                  - expectPrompt: { text: Unknown item }
                  - answerPrompt: { choice: OK }
                  - key: { action: DUE }
                  - deposit: { amount: "7.00" }
                timeouts: { step: 8s }
                """;
        Plan plan = PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8), "pack-check.yaml", pack);
        assertEquals("lending", plan.pack());
        assertEquals("0.6.0", plan.packVersion());
        List<Plan.Call> calls = plan.steps().stream().flatMap(s -> s.calls().stream()).toList();
        assertEquals(List.of("lending.input", "lending.input", "lending.input", "lending.input", "lending.input",
                "sync.await", "lending.prompt.answer", "lending.input", "lending.input"),
                calls.stream().map(Plan.Call::primitive).toList());
        assertEquals(Map.of("source", "Entry", "value", "$assistant.code"), calls.get(0).args());
        assertEquals(Sync.READY, calls.get(0).sync());
        assertEquals(8000L, calls.get(0).timeoutMs());
        assertFalse(calls.get(0).handlesPrompt());
        assertTrue(calls.get(5).handlesPrompt(), "a wait on the prompt topic handles the prompt");
        Plan.Call answer = calls.get(6);
        assertEquals(Sync.EVENT, answer.sync());
        assertEquals(new AwaitSpec("input.requested,prompt.waiting", null, 8000L), answer.await());
        assertTrue(answer.handlesPrompt());
        assertEquals(List.of(new Plan.Call("lending.input", Map.of("source", "Key.SIGN_OFF"), Sync.READY, 8000L,
                false)), plan.cleanup());
        assertEquals(plan.toJsonText(), PlanCompiler.compile(yaml.getBytes(StandardCharsets.UTF_8),
                "pack-check.yaml", new LendingPack()).toJsonText(), "deterministic");
    }
}
