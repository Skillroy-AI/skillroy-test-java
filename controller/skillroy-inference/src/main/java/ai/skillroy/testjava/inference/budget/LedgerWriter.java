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

import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.inference.spi.ModelResponse;
import ai.skillroy.testjava.inference.spi.Prompts;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.Strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What every caller of a gateway records a call with (phase5-contracts.md 7.3, 7.4, decision D83): the
 * {@link CostLedger.Entry} built from the response's usage (purpose, cache tokens, batch, note), recorded in the
 * run's ledger; the meter settled; and one masked {@code inference.jsonl} record appended: metadata and the masked
 * structured answer, with the masked prompt parts and response parts only when {@code logPrompts} is on, a refusal's
 * category in {@code note}. A call answered by the recorded gateway is recorded too (provider {@code recorded}, cost
 * {@code 0}), so that a run against recordings still shows its calls; {@code replay} has none.
 *
 * <p>Thread-safe if the ledger, meter and log are (they are).
 */
public final class LedgerWriter {

    private final CostLedger ledger;
    private final BudgetMeter meter;
    private final InferenceLog log;
    private final PromptMask mask;
    private final boolean logPrompts;
    private final Clock clock;

    /**
     * @param ledger the run's ledger
     * @param meter the meter to settle, or null
     * @param log where the {@code inference.jsonl} records go; null keeps none
     * @param mask the mask the structured answer and (with {@code logPrompts}) the prompt pass through once more
     * @param logPrompts true: the masked system parts, messages and response parts go into the record
     * @param clock the record's time
     */
    public LedgerWriter(CostLedger ledger, BudgetMeter meter, InferenceLog log, PromptMask mask, boolean logPrompts,
                        Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.meter = meter;
        this.log = log == null ? InferenceLog.NONE : log;
        this.mask = mask == null ? PromptMask.keysOnly() : mask;
        this.logPrompts = logPrompts;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** The meter this writer settles, or null. */
    public BudgetMeter meter() {
        return meter;
    }

    /**
     * Records one answered call: the ledger entry, the meter settled, the {@code inference.jsonl} record.
     *
     * @return the response's usage
     */
    public Usage record(ModelRequest request, ModelResponse response, String scenarioId, String slot,
                        Strategy strategy) {
        Usage usage = response.usage();
        ledger.record(entry(request, usage, scenarioId, slot, strategy));
        if (meter != null) {
            meter.settle(usage);
        }
        Map<String, Object> record = base(request, usage, scenarioId, slot);
        record.put("stop", response.stop().name());
        if (response.structured() != null) {
            record.put("structured", mask.jsonLike(response.structured()));
        }
        if (usage.note() != null) {
            record.put("note", mask.text(usage.note()));
        }
        if (logPrompts) {
            prompts(record, request);
            record.put("parts", Prompts.parts(mask.parts(response.parts()), false));
        }
        log.append(record);
        return usage;
    }

    /**
     * Records a failed call: a ledger entry and the meter settled only when the failure carries the usage the provider
     * billed; the meter's reservation released otherwise; an {@code inference.jsonl} record either way, with the
     * failure's kind.
     */
    public void recordFailure(ModelRequest request, InferenceException failure, String scenarioId, String slot,
                              Strategy strategy) {
        Usage usage = failure.usage();
        if (usage != null) {
            ledger.record(entry(request, usage, scenarioId, slot, strategy));
            if (meter != null) {
                meter.settle(usage);
            }
        } else if (meter != null) {
            meter.release();
        }
        Map<String, Object> record = base(request, usage, scenarioId, slot);
        record.put("stop", null);
        record.put("error", failure.kind().name());
        record.put("requestId", failure.requestId());
        record.put("note", mask.text(failure.getMessage()));
        if (logPrompts) {
            prompts(record, request);
        }
        log.append(record);
    }

    /**
     * The ledger entry of a call (phase5-contracts.md 7.3): the Phase 2 fields, then the purpose, the cache tokens
     * (writes of both times to live summed), the batch flag and the note.
     */
    public static CostLedger.Entry entry(ModelRequest request, Usage usage, String scenarioId, String slot,
                                         Strategy strategy) {
        return new CostLedger.Entry(scenarioId, slot, strategy, usage.provider(), usage.model(), usage.requestId(),
                usage.inputTokens(), usage.outputTokens(), usage.costUsd(), request.purpose(),
                usage.cacheReadTokens(), usage.cacheWriteTokens(), usage.batch(), usage.note());
    }

    /**
     * The cache-hit ratio of a set of ledger entries (phase5-contracts.md 8.2 step 5): {@code cacheReadTokens /
     * (inputTokens + cacheReadTokens)} over all of them, 4 decimals, rounded half up; null when no input was sent.
     */
    public static BigDecimal cacheHitRatio(Collection<CostLedger.Entry> entries) {
        long read = 0;
        long input = 0;
        for (CostLedger.Entry e : entries) {
            read += e.cacheReadTokens();
            input += e.inputTokens();
        }
        long total = input + read;
        return total == 0 ? null : BigDecimal.valueOf(read).divide(BigDecimal.valueOf(total), 4,
                RoundingMode.HALF_UP);
    }

    private Map<String, Object> base(ModelRequest request, Usage usage, String scenarioId, String slot) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "inference");
        record.put("t", clock.instant().toString());
        record.put("purpose", request.purpose());
        if (scenarioId != null) {
            record.put("scenarioId", scenarioId);
        }
        if (slot != null) {
            record.put("slot", slot);
        }
        record.put("provider", usage == null ? null : usage.provider());
        record.put("requestedModel", usage == null ? null : usage.requestedModel());
        record.put("model", usage == null ? null : usage.model());
        record.put("requestId", usage == null ? null : usage.requestId());
        record.put("fingerprint", request.fingerprint());
        record.put("inputTokens", usage == null ? 0L : usage.inputTokens());
        record.put("outputTokens", usage == null ? 0L : usage.outputTokens());
        record.put("cacheReadTokens", usage == null ? 0L : usage.cacheReadTokens());
        record.put("cacheWriteTokens", usage == null ? 0L : usage.cacheWriteTokens());
        record.put("batch", usage != null && usage.batch());
        record.put("costUsd", usage == null ? "0" : usage.costUsd());
        record.put("elapsedMs", usage == null ? 0L : usage.elapsed().toMillis());
        return record;
    }

    private void prompts(Map<String, Object> record, ModelRequest request) {
        record.put("system", Prompts.parts(mask.parts(request.system()), false));
        List<Object> messages = new ArrayList<>();
        for (Message m : request.messages()) {
            messages.add(Prompts.message(new Message(m.role(), mask.parts(m.parts())), false));
        }
        record.put("messages", messages);
    }
}
