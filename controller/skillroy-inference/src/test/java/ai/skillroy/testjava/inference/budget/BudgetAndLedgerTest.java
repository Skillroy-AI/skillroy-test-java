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

package ai.skillroy.testjava.inference.budget;

import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelClass;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.SettingsException;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 item 8: the meter's arithmetic (reserve, settle, exhaustion, unlimited, zero), the price
 * table (built-in values, a file override, an unknown model, the batch rate and the cache-write split), the extended
 * ledger entry beside the old constructor, {@code inference.jsonl} with and without {@code logPrompts}, and the
 * cache-hit ratio.
 */
class BudgetAndLedgerTest {

    @TempDir
    Path dir;

    private static Usage usage(String model, long in, long out, long read, long w5, long w1, boolean batch,
                               String cost) {
        return new Usage("claude", model, model, "msg_1", in, out, read, w5, w1, batch, cost, Duration.ofMillis(12),
                null);
    }

    @Test
    void theBuiltInTableHasTheContractsRates() {
        PriceTable t = PriceTable.builtIn();
        assertEquals(List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-4-5", "claude-sonnet-5",
                "claude-opus-4-8"), t.models());
        PriceTable.Price sonnet = t.price("claude-sonnet-5-5");
        assertEquals(new BigDecimal("2.00"), sonnet.inputPerMTok());
        assertEquals(new BigDecimal("10.00"), sonnet.outputPerMTok());
        assertEquals(new BigDecimal("0.20"), sonnet.cacheReadPerMTok());
        assertEquals(new BigDecimal("1.25"), sonnet.cacheWrite5mFactor());
        assertEquals(new BigDecimal("2"), sonnet.cacheWrite1hFactor());
        assertEquals(new BigDecimal("0.5"), sonnet.batchFactor());
        assertEquals(new BigDecimal("4.00"), t.price("claude-opus-5-5").inputPerMTok());
        assertEquals(new BigDecimal("1.00"), t.price("claude-haiku-4-5").inputPerMTok());
        assertEquals(new BigDecimal("5.00"), t.price("claude-opus-4-8").inputPerMTok());
    }

    @Test
    void theCostComesFromTheUsagesOwnSplitAtSixDecimals() {
        PriceTable t = PriceTable.builtIn();
        // 1000 x 2 + 500 x 10 + 2000 x 0.2 + 400 x 2 x 1.25 + 100 x 2 x 2 = 8800 per million.
        assertEquals(new BigDecimal("0.008800"), t.cost(usage("claude-sonnet-5-5", 1000, 500, 2000, 400, 100, false,
                "0")));
        assertEquals(new BigDecimal("0.004400"), t.cost(usage("claude-sonnet-5-5", 1000, 500, 2000, 400, 100, true,
                "0")), "the batch rate stacks with cache reads");
        assertEquals(new BigDecimal("0.000003"), t.cost(usage("claude-haiku-4-5", 3, 0, 0, 0, 0, false, "0")));
        assertEquals(new BigDecimal("0.000001"), t.cost(usage("claude-haiku-4-5", 1, 0, 0, 0, 0, false, "0")),
                "0.0000010 rounds half up");
        assertEquals(new BigDecimal("0.000002"), t.cost(usage("claude-haiku-4-5", 0, 0, 15, 0, 0, false, "0")),
                "15 x 0.10 = 1.5 per million rounds up");
        assertEquals(t.cost(usage("claude-opus-5-5", 100, 10, 0, 0, 0, false, "0")),
                t.cost(usage("claude-fable-x", 100, 10, 0, 0, 0, false, "0")), "an unknown model at Opus's price");
        assertEquals("priced as claude-opus-5-5 (unknown model claude-fable-x)", t.note("claude-fable-x"));
        assertNull(t.note("claude-sonnet-5-5"));
        assertEquals(new BigDecimal("0.026480"), t.estimate("claude-sonnet-5-5", 3000, 2048, false));
        assertEquals(new BigDecimal("0.013240"), t.estimate("claude-sonnet-5-5", 3000, 2048, true));
    }

    @Test
    void aFileOverridesAndAdds() throws Exception {
        Path file = dir.resolve("prices.yaml");
        Files.writeString(file, """
                version: 1
                models:
                  claude-sonnet-5-5: {inputPerMTok: "3.00"}
                  a-new-model: {inputPerMTok: "1.50", outputPerMTok: 6}
                """);
        PriceTable t = PriceTable.load(file);
        assertEquals(new BigDecimal("3.00"), t.price("claude-sonnet-5-5").inputPerMTok());
        assertEquals(new BigDecimal("10.00"), t.price("claude-sonnet-5-5").outputPerMTok(), "the rest kept");
        PriceTable.Price added = t.price("a-new-model");
        assertEquals(new BigDecimal("6"), added.outputPerMTok());
        assertEquals(0, new BigDecimal("0.15").compareTo(added.cacheReadPerMTok()), "a tenth of the input price");
        assertEquals(new BigDecimal("4.00"), t.price("claude-opus-5-5").inputPerMTok(), "built-in rows stay");
        Files.writeString(file, "version: 1\nmodels:\n  m: {outputPerMTok: \"1\"}\n");
        assertTrue(assertThrows(SettingsException.class, () -> PriceTable.load(file)).getMessage()
                .contains("needs inputPerMTok and outputPerMTok"));
        Files.writeString(file, "version: 1\nmodels:\n  claude-haiku-4-5: {inputPerMTok: \"-1\"}\n");
        assertThrows(SettingsException.class, () -> PriceTable.load(file));
        Files.writeString(file, "version: 1\nmodels:\n  claude-haiku-4-5: {price: \"sekrit-9999\"}\n");
        SettingsException e = assertThrows(SettingsException.class, () -> PriceTable.load(file));
        assertTrue(e.getMessage().contains(":3:"), "names the line: " + e.getMessage());
        assertFalse(e.getMessage().contains("sekrit-9999"), "never a value");
        Files.writeString(file, "version: 2\n");
        assertThrows(SettingsException.class, () -> PriceTable.load(file));
        assertThrows(SettingsException.class, () -> PriceTable.load(dir.resolve("missing.yaml")));
    }

    @Test
    void theMeterReservesSettlesAndRefuses() throws Exception {
        BudgetMeter meter = new BudgetMeter(BudgetMeter.Scope.SCENARIO, null, new BigDecimal("0.50"),
                new CostLedger());
        meter.identify("scenario-a");
        meter.identify("ignored");
        assertEquals("scenario-a", meter.id());
        meter.reserve(new BigDecimal("0.30"));
        assertEquals(new BigDecimal("0.20"), meter.remaining());
        BudgetException tooMuch = assertThrows(BudgetException.class, () -> meter.reserve(new BigDecimal("0.25")),
                "the open reservation counts");
        assertEquals(new BigDecimal("0.30"), tooMuch.spentUsd());
        meter.settle(usage("claude-sonnet-5-5", 0, 0, 0, 0, 0, false, "0.10"));
        assertEquals(new BigDecimal("0.10"), meter.spent());
        assertEquals(new BigDecimal("0.40"), meter.remaining(), "the reservation was released");
        meter.reserve(new BigDecimal("0.40"));
        meter.release();
        BudgetException e = assertThrows(BudgetException.class, () -> meter.reserve(new BigDecimal("0.41")));
        assertEquals(BudgetMeter.Scope.SCENARIO, e.scope());
        assertEquals("the scenario budget of $0.50 has $0.10 spent and no room for a model call estimated at"
                + " $0.41", e.getMessage());
        ResolutionException r = e.toResolutionException();
        assertEquals(ResolutionException.BUDGET, r.reason());
        assertEquals(Map.of("scope", "SCENARIO", "id", "scenario-a", "limitUsd", "0.50", "spentUsd", "0.10",
                "estimateUsd", "0.41"), r.detail());
        meter.reserve(new BigDecimal("0.40"));
    }

    @Test
    void anUnlimitedMeterAndAZeroLimit() throws Exception {
        BudgetMeter unlimited = BudgetMeter.of(BudgetMeter.Scope.JOB, "job-1", null, null);
        assertTrue(unlimited.unlimited());
        unlimited.reserve(new BigDecimal("1000000"));
        assertNull(unlimited.remaining());
        BudgetMeter zero = BudgetMeter.of(BudgetMeter.Scope.SESSION, "s", "0", null);
        assertThrows(BudgetException.class, () -> zero.reserve(BigDecimal.ZERO), "a limit of 0 is no call at all");
        assertThrows(IllegalArgumentException.class, () -> BudgetMeter.of(BudgetMeter.Scope.JOB, "j", "lots", null));
        assertThrows(IllegalArgumentException.class, () -> new BudgetMeter(BudgetMeter.Scope.JOB, "j",
                new BigDecimal("-1"), null));
    }

    private static ModelRequest request() {
        return ModelRequest.builder("resolve", ModelClass.SONNET_CLASS)
                .system(Content.Text.cached("catalogue")).message(Message.user("password=Zq7-Lantern-42x please"))
                .build();
    }

    @Test
    void theExtendedEntryKeepsThePhase2Constructor() {
        CostLedger.Entry old = new CostLedger.Entry("s", "slot", Strategy.CANONICAL_SQL, "p", "m", "r", 1, 2, "0.1");
        assertEquals("resolve", old.purpose());
        assertEquals(0, old.cacheReadTokens());
        assertFalse(old.batch());
        assertNull(old.note());
        Usage u = new Usage("claude", "a", "b", "msg_9", 10, 20, 30, 40, 50, true, "0.5", Duration.ZERO, "refusal: x");
        CostLedger.Entry e = LedgerWriter.entry(request(), u, "s", "slot", Strategy.LLM_SQL);
        assertEquals(new CostLedger.Entry("s", "slot", Strategy.LLM_SQL, "claude", "b", "msg_9", 10, 20, "0.5",
                "resolve", 30, 90, true, "refusal: x"), e);
        assertEquals(List.of("scenarioId", "slot", "strategy", "provider", "model", "requestId", "inputTokens",
                "outputTokens", "costUsd", "purpose", "cacheReadTokens", "cacheWriteTokens", "batch", "note"),
                List.copyOf(e.toJson().keySet()), "the new fields after the old ones");
    }

    @Test
    void theWriterRecordsSettlesAndAppendsAMaskedRecord() throws Exception {
        CostLedger ledger = new CostLedger();
        BudgetMeter meter = new BudgetMeter(BudgetMeter.Scope.SCENARIO, "s", new BigDecimal("1"), ledger);
        Path file = dir.resolve("inference.jsonl");
        PromptMask mask = PromptMask.of(SecretMask.of(List.of("Zq7-Lantern-42x")), List.of(), List.of());
        Clock clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC);
        LedgerWriter quiet = new LedgerWriter(ledger, meter, InferenceLog.toFile(file), mask, false, clock);
        meter.reserve(new BigDecimal("0.01"));
        Usage u = new Usage("claude", "claude-sonnet-5-5", "claude-sonnet-5-5", "msg_1", 100, 20, 500, 0, 0, false,
                "0.0004", Duration.ofMillis(800), "refusal: frontier_llm");
        ModelResponse response = new ModelResponse(List.of(Content.Text.of("{\"sql\":\"x\"}")),
                ModelResponse.StopReason.END_TURN, u, Map.of("sql", "SELECT 1", "token", "Zq7-Lantern-42x"));
        assertEquals(u, quiet.record(request(), response, "s", "slot", Strategy.LLM_SQL));
        assertEquals(1, ledger.size());
        assertEquals(new BigDecimal("0.0004"), meter.spent());
        assertEquals(new BigDecimal("0.9996"), meter.remaining(), "settled and the reservation released");
        Map<String, Object> record = Json.parseObject(Files.readAllLines(file).get(0));
        assertEquals("inference", record.get("type"));
        assertEquals("2026-10-08T10:00:00Z", record.get("t"));
        assertEquals("resolve", record.get("purpose"));
        assertEquals("s", record.get("scenarioId"));
        assertEquals(request().fingerprint(), record.get("fingerprint"));
        assertEquals("END_TURN", record.get("stop"));
        assertEquals(500L, record.get("cacheReadTokens"));
        assertEquals("0.0004", record.get("costUsd"));
        assertEquals(800L, record.get("elapsedMs"));
        assertEquals("refusal: frontier_llm", record.get("note"));
        assertEquals(Map.of("sql", "SELECT 1", "token", "<redacted>"), record.get("structured"), "masked by key");
        assertFalse(record.containsKey("system"), "no prompt without logPrompts");
        assertFalse(record.containsKey("messages"));
        LedgerWriter loud = new LedgerWriter(ledger, null, InferenceLog.toFile(file), mask, true, clock);
        loud.record(request(), response, null, null, Strategy.LLM_SQL);
        Map<String, Object> withPrompts = Json.parseObject(Files.readAllLines(file).get(1));
        assertFalse(withPrompts.containsKey("scenarioId"));
        assertTrue(withPrompts.containsKey("system"));
        assertTrue(withPrompts.containsKey("parts"));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("Zq7-Lantern-42x"), "the secret is in no record: " + text);
        assertTrue(text.contains("password=<redacted>"), text);
    }

    @Test
    void aFailureIsRecordedOnlyWhenItWasBilled() {
        CostLedger ledger = new CostLedger();
        BudgetMeter meter = new BudgetMeter(BudgetMeter.Scope.SCENARIO, "s", new BigDecimal("1"), ledger);
        InferenceLog.Memory log = InferenceLog.inMemory();
        LedgerWriter writer = new LedgerWriter(ledger, meter, log, PromptMask.keysOnly(), false, Clock.systemUTC());
        writer.recordFailure(request(), new InferenceException(InferenceException.Kind.TIMEOUT, "the completion timed"
                + " out"), "s", "x", Strategy.LLM_SQL);
        assertTrue(ledger.isEmpty(), "a timeout is not knowable as billed");
        assertEquals("TIMEOUT", log.records().get(0).get("error"));
        Usage billed = new Usage("claude", "m", "m", "r", 10, 0, 0, 0, 0, false, "0.001", null, null);
        writer.recordFailure(request(), new InferenceException(InferenceException.Kind.PROVIDER, "not JSON", "r",
                billed, null), "s", "x", Strategy.LLM_SQL);
        assertEquals(1, ledger.size());
        assertEquals(new BigDecimal("0.001"), meter.spent());
    }

    @Test
    void theRunsWriterFindsTheRecordsOfItsLedger() throws Exception {
        CostLedger ledger = new CostLedger();
        InferenceLog.Memory log = InferenceLog.forLedger(ledger);
        assertEquals(log, InferenceLog.forLedger(ledger), "one log per ledger");
        log.append(Map.of("type", "inference"));
        Path file = dir.resolve("run").resolve("inference.jsonl");
        log.writeTo(file);
        assertEquals(List.of("{\"type\":\"inference\"}"), Files.readAllLines(file));
        assertTrue(InferenceLog.forLedger(new CostLedger()).records().isEmpty());
    }

    @Test
    void theCacheHitRatio() {
        CostLedger.Entry a = new CostLedger.Entry("s", null, Strategy.LLM_SQL, "claude", "m", "r", 100, 10, "0",
                "resolve", 900, 0, true, null);
        CostLedger.Entry b = new CostLedger.Entry("s", null, Strategy.LLM_SQL, "claude", "m", "r", 200, 10, "0",
                "resolve", 0, 1000, true, null);
        assertEquals(new BigDecimal("0.7500"), LedgerWriter.cacheHitRatio(List.of(a, b)));
        assertNull(LedgerWriter.cacheHitRatio(List.of()));
    }
}
