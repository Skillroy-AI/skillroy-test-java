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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.session.SessionPolicy;
import ai.skillroy.testjava.inference.session.SessionService;
import ai.skillroy.testjava.inference.session.Turn;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.Usage;
import ai.skillroy.testjava.scenario.ExecutorSession;
import ai.skillroy.testjava.scenario.StepOutcome;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The session service as the CLI hosts it (phase5-contracts.md 12.4: hosts 1 and 2): built over an open
 * {@link ExecutorSession} with the gateway the settings name, opened with the session's prompt mask
 * ({@link Gateways#open(InferenceSettings, PromptMask)}: the recording gateway of {@code record: true} writes through
 * it, 4.2), the session's budget meter ({@code budget.sessionUsd}), a {@link LedgerWriter} that records every call in
 * the session's ledger and appends its masked record to {@code inference.jsonl} in the session's output directory, the
 * prompt mask of the session's credentials and the pack's allow-lists and row masks, and the price table of the
 * settings; and a turn as JSON and as text for {@code skillroy session} and {@code skillroy_session_ask}.
 *
 * <p>Stateless; the service it opens is used on one thread at a time.
 */
final class SessionServices {

    /** The file of the session's model calls, beside its journal. */
    static final String INFERENCE_FILE = "inference.jsonl";

    private SessionServices() {
    }

    /**
     * An open service and the gateway lease it calls through; closing closes both (never the session, which its owner
     * closes).
     */
    record Opened(SessionService service, InferenceGateway gateway) implements AutoCloseable {

        @Override
        public void close() {
            try {
                service.close();
            } finally {
                gateway.close();
            }
        }
    }

    /**
     * Opens the service over {@code session}.
     *
     * @param ledger the ledger the session's binds record into (the session's {@code cost-ledger.jsonl})
     * @param outDir where {@code inference.jsonl} is appended; null keeps no record
     * @throws InferenceException when the settings' gateway cannot be opened (no model key)
     * @throws SettingsException when the price table of the settings cannot be read
     */
    static Opened open(ExecutorSession session, InferenceSettings settings, SessionPolicy policy, CostLedger ledger,
                       Path outDir) throws InferenceException, SettingsException {
        DomainPack pack = session.pack();
        PromptMask mask = PromptMask.of(session.options().bind().credentials().mask(), safe(pack::sqlAllowLists),
                safe(pack::rowMasks));
        InferenceGateway gateway = Gateways.open(settings, mask);
        boolean ok = false;
        try {
            BudgetMeter meter = BudgetMeter.of(BudgetMeter.Scope.SESSION, session.id(),
                    settings.budgets().get(InferenceSettings.BUDGET_SESSION), ledger);
            InferenceLog log = outDir == null ? InferenceLog.NONE : InferenceLog.toFile(outDir.resolve(INFERENCE_FILE));
            LedgerWriter writer = new LedgerWriter(ledger, meter, log, mask, settings.logPrompts(),
                    session.options().clock());
            SessionService service = new SessionService(session, gateway, policy, meter, writer,
                    PriceTable.of(settings), null);
            ok = true;
            return new Opened(service, gateway);
        } finally {
            if (!ok) {
                gateway.close();
            }
        }
    }

    /**
     * A turn as JSON-like data: its number, the request, how it ended, the answer, each action (the tool, its masked
     * input, the step's status, kind and message for a verb or a primitive, the result the model was told), the
     * turn's usage and its duration. Masked as the service masks it; the caller masks the text once more.
     */
    static Map<String, Object> turnJson(Turn turn) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("turn", (long) turn.index());
        json.put("request", turn.request());
        json.put("outcome", turn.outcome().name());
        json.put("answer", turn.answer());
        List<Object> actions = new ArrayList<>();
        for (Turn.Action action : turn.actions()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("tool", action.tool());
            a.put("input", action.input());
            StepOutcome step = action.step();
            if (step != null) {
                a.put("stepId", step.stepId());
                a.put("status", step.status().name());
                a.put("failureKind", step.failureKind() == null ? null : step.failureKind().name());
                a.put("message", step.message());
            }
            a.put("result", action.result());
            actions.add(a);
        }
        json.put("actions", actions);
        Usage usage = turn.totalUsage();
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("model", usage.model());
        u.put("inputTokens", usage.inputTokens());
        u.put("outputTokens", usage.outputTokens());
        u.put("costUsd", usage.costUsd());
        json.put("usage", u);
        json.put("durationMs", turn.duration().toMillis());
        return json;
    }

    /** A turn for a person: one line per action, then how it ended with the answer, then its cost. */
    static String turnText(Turn turn) {
        StringBuilder text = new StringBuilder();
        for (Turn.Action action : turn.actions()) {
            StepOutcome step = action.step();
            text.append("  ").append(action.tool());
            if (step != null) {
                text.append(" (").append(step.stepId()).append("): ").append(step.status());
                if (step.failureKind() != null) {
                    text.append(' ').append(step.failureKind());
                }
                if (step.message() != null) {
                    text.append(": ").append(Text.oneLine(step.message(), 200));
                }
            } else {
                text.append(": ").append(Text.oneLine(action.result(), 200));
            }
            text.append('\n');
        }
        text.append(turn.outcome()).append(": ").append(turn.answer() == null ? "" : turn.answer().strip())
                .append('\n');
        Usage usage = turn.totalUsage();
        text.append("  (turn ").append(turn.index()).append(": ").append(turn.actions().size()).append(" action")
                .append(turn.actions().size() == 1 ? "" : "s").append(", model cost $")
                .append(usage.costUsd() == null ? "0" : usage.costUsd()).append(")\n");
        return text.toString();
    }

    /** A pack's list (its allow-lists, its row masks), empty when the pack has none or fails to say. */
    private static <T> List<T> safe(Supplier<List<T>> list) {
        try {
            List<T> value = list.get();
            return value == null ? List.of() : value;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
