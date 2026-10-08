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

package ai.skillroy.testjava.inference.job;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataChannels;
import ai.skillroy.testjava.data.SecretMask;
import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.budget.BudgetMeter;
import ai.skillroy.testjava.inference.budget.InferenceLog;
import ai.skillroy.testjava.inference.budget.LedgerWriter;
import ai.skillroy.testjava.inference.budget.PriceTable;
import ai.skillroy.testjava.inference.prompt.PromptMask;
import ai.skillroy.testjava.inference.spi.BatchHandle;
import ai.skillroy.testjava.inference.spi.BatchResult;
import ai.skillroy.testjava.inference.spi.BatchStatus;
import ai.skillroy.testjava.inference.spi.Content;
import ai.skillroy.testjava.inference.spi.GatewayCapability;
import ai.skillroy.testjava.inference.spi.InferenceException;
import ai.skillroy.testjava.inference.spi.InferenceGateway;
import ai.skillroy.testjava.inference.spi.Message;
import ai.skillroy.testjava.inference.spi.ModelRequest;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.scenario.BindException;
import ai.skillroy.testjava.scenario.BindListener;
import ai.skillroy.testjava.scenario.BindOptions;
import ai.skillroy.testjava.scenario.BindOutcome;
import ai.skillroy.testjava.scenario.BindStep;
import ai.skillroy.testjava.scenario.Binder;
import ai.skillroy.testjava.scenario.BindingsDiff;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.BindingsFormatException;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.SettingsException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The nightly {@code re-resolve} job (phase5-contracts.md 8, decisions D74, D75): binds every scenario of an
 * environment in {@code re-resolve} mode with {@link Binder#bindStepwise}, so that a slot whose ladder reaches a
 * batchable resolver (the tier (b) resolver) <b>parks</b> with its prepared request; when every scenario is parked or
 * finished, the round's requests go to the provider as <b>one batch</b>, the job waits for it (a status read every
 * {@code batchPoll}, at most {@code batchWait}, then the batch is cancelled and its scenarios are reported
 * {@code BATCH_TIMEOUT}), collects it, and resumes each parked bind with its answer, in parking order; a bind may park
 * again at a later slot, which starts the next round. The verifiers, oracles, cross-checks and guards run in the round
 * in which a scenario resumes, against the live target, exactly as in {@code live} (the engine is the same). Tier (a)
 * slots resolve inline. Every finished bind writes its file {@code APPROVED} by {@code re-resolve:<jobId>} (or
 * {@code approvedBy}) through the binder's own writer, an identical file left untouched; a dry run writes none.
 *
 * <p>The job budget (the {@code JOB} meter, 7.2, 8.3): before a round's batch is submitted, the sum of its requests'
 * estimates (input tokens by the gateway's count, else text length / 4, plus the answer's maximum, at the batch rate)
 * is reserved; when it does not fit, the requests that fit (in parking order) are submitted and the others' scenarios
 * are reported {@code RESOLUTION_FAILED} / {@code BUDGET}, and no later round submits a batch (D74: exhaustion stops a
 * job's further batches). A batch in flight is always awaited and collected: it is paid for. The meter is settled from
 * the job's ledger entries, so that a resolver's inline retry is counted too, and before a bind resumes with a
 * retryable failure (which its resolver retries inline, at the regular rate) the job meter reserves that call's
 * estimate; when it has no room, the scenario fails {@code RESOLUTION_FAILED} / {@code BUDGET} without the call, and no
 * later batch is submitted.
 *
 * <p>A gateway without {@link GatewayCapability#BATCH} answers a round's requests one by one through
 * {@code complete} (noted in the report); the recorded gateway serves batches request by request itself. Gateway
 * {@code none} and a missing job budget are refused before anything runs. A scenario that needs no bindings is
 * listed {@code SKIPPED}. The pack's pre-flight runs once, before the first bind; a failing check fails every scenario
 * that needs bindings ({@code ENVIRONMENT}, {@code PREFLIGHT}). The CLI checks {@code requires} and launch properties
 * when it connects.
 *
 * <p>The job runs on the calling thread and starts none; the only timed wait is the bounded wait between two status
 * reads of a batch, which an interrupt ends (the batch is then cancelled).
 */
public final class ReResolver {

    private ReResolver() {
    }

    /**
     * Runs the job through the shared gateway of the bind options' inference settings ({@link Gateways#open}), which
     * the binds' tier (b) resolvers lease too; writes the report and the job's ledger into {@code job.outDir()} when
     * it is set.
     *
     * @param plans the compiled scenarios, in the order the report lists them
     * @param pack the running pack
     * @param agent the target's agent
     * @param options the environment, the bindings root, the credentials, the ledger, the clock and the inference
     *     settings; the mode, {@code writeBindings} and {@code approvedBy} are the job's
     * @throws ReResolveException before anything runs: no job budget, gateway {@code none}, a gateway that cannot be
     *     opened, two plans with one scenario id; after the job ran: its outputs could not be written (the report is
     *     in the exception)
     * @throws AgentLostException if the agent is lost (every parked bind is closed first)
     * @throws InterruptedException if the thread is interrupted (a batch in flight is cancelled, every parked bind
     *     closed)
     */
    public static ReResolveReport run(List<Plan> plans, DomainPack pack, AgentConnection agent, BindOptions options,
                                      JobOptions job)
            throws ReResolveException, AgentLostException, InterruptedException {
        Objects.requireNonNull(options, "options");
        check(plans, options, job);
        InferenceGateway gateway;
        try {
            // The job's mask knows the environment's credentials: a recording written for record: true goes through
            // it (phase5-contracts.md 4.2).
            gateway = Gateways.open(options.inference(), PromptMask.of(options.credentials().mask(),
                    listOf(pack::sqlAllowLists), listOf(pack::rowMasks)).withImages(options.inference().images()));
        } catch (InferenceException e) {
            throw new ReResolveException("the " + options.inference().gateway() + " gateway cannot be opened: "
                    + options.credentials().mask().apply(String.valueOf(e.getMessage())), null);
        }
        try {
            return run(plans, pack, agent, options, job, gateway);
        } finally {
            gateway.close();
        }
    }

    /**
     * Runs the job through the given gateway (the caller keeps and closes it): what a test or a scenarios module does
     * with a scripted batch gateway. Otherwise as {@link #run(List, DomainPack, AgentConnection, BindOptions,
     * JobOptions)}; the binds' resolvers still lease the gateway of the settings, which in this mode they only call
     * to retry a failed answer.
     */
    public static ReResolveReport run(List<Plan> plans, DomainPack pack, AgentConnection agent, BindOptions options,
                                      JobOptions job, InferenceGateway gateway)
            throws ReResolveException, AgentLostException, InterruptedException {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(gateway, "gateway");
        check(plans, options, job);
        PriceTable prices;
        try {
            prices = PriceTable.of(options.inference());
        } catch (SettingsException e) {
            throw new ReResolveException(e.getMessage(), null);
        }
        ReResolveReport report = new Job(plans, pack, agent, options, job, gateway, prices).run();
        if (job.outDir() != null) {
            try {
                report.writeTo(job.outDir());
            } catch (IOException e) {
                throw new ReResolveException("the job's report and ledger could not be written to " + job.outDir()
                        + ": " + e.getClass().getSimpleName(), report);
            }
        }
        return report;
    }

    /** A pack's list (its allow-lists, its row masks), empty when the pack has none or fails to say. */
    private static <T> List<T> listOf(java.util.function.Supplier<List<T>> list) {
        try {
            List<T> value = list.get();
            return value == null ? List.of() : value;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static void check(List<Plan> plans, BindOptions options, JobOptions job) throws ReResolveException {
        Objects.requireNonNull(plans, "plans");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(job, "job");
        if (job.jobBudgetUsd() == null) {
            throw new ReResolveException("re-resolve needs a job budget (--budget-job, inference.yaml budget.jobUsd);"
                    + " there is no built-in value (B6)", null);
        }
        if (!options.inference().enabled()) {
            throw new ReResolveException("re-resolve needs a model gateway; the inference settings name none", null);
        }
        Set<String> ids = new HashSet<>();
        for (Plan plan : plans) {
            Objects.requireNonNull(plan, "a plan is null");
            if (!ids.add(plan.scenarioId())) {
                throw new ReResolveException("two plans have the scenario id '" + plan.scenarioId() + "'; their"
                        + " bindings files would collide", null);
            }
        }
    }

    /** One scenario of the job. */
    private static final class Item {

        final Plan plan;
        BindingsFile previous;
        String previousText;
        BindStep.Parked parked;
        int parkedCount;
        ReResolveReport.Status status;
        boolean written;
        List<String> changes = List.of();
        FailureKind failureKind;
        String reason;
        String message;

        Item(Plan plan) {
            this.plan = plan;
        }

        /** Abandons a parked bind; idempotent. */
        void abandon() {
            BindStep.Parked p = parked;
            parked = null;
            if (p != null) {
                p.close();
            }
        }
    }

    /** What a round's requests were answered with: one answer per request, or a batch that did not end in time. */
    private record Answers(List<Object> answers, boolean timedOut) {
    }

    /** One job. */
    private static final class Job {

        private final List<Plan> plans;
        private final DomainPack pack;
        private final AgentConnection agent;
        private final BindOptions base;
        private final JobOptions job;
        private final InferenceGateway gateway;
        private final InferenceSettings settings;
        private final PriceTable prices;
        private final SecretMask mask;
        private final Clock clock;
        private final CostLedger ledger;
        private final int ledgerStart;
        private final InferenceLog.Memory log;
        private final int logStart;
        private final BudgetMeter meter;
        private final long waitMs;
        private final long pollMs;
        private final long callMs;
        /** What the wait between two status reads waits on: nothing counts it down, an interrupt ends the wait. */
        private final CountDownLatch pause = new CountDownLatch(1);
        private final List<String> notes = new ArrayList<>();
        private int settled;
        private int rounds;
        private int batches;
        private int requests;
        private boolean budgetCut;
        private boolean noBatchNoted;

        Job(List<Plan> plans, DomainPack pack, AgentConnection agent, BindOptions options, JobOptions job,
            InferenceGateway gateway, PriceTable prices) {
            this.plans = List.copyOf(plans);
            this.pack = pack;
            this.agent = agent;
            this.job = job;
            this.gateway = gateway;
            this.prices = prices;
            this.settings = options.inference();
            this.base = options.withMode(BindMode.RE_RESOLVE).withWriteBindings(!job.dryRun())
                    .withApprovedBy(job.approvalLabel());
            this.mask = options.credentials().mask();
            this.clock = job.clock();
            this.ledger = base.ledger();
            this.ledgerStart = ledger.size();
            this.settled = ledgerStart;
            this.log = InferenceLog.forLedger(ledger);
            this.logStart = log.records().size();
            this.meter = new BudgetMeter(BudgetMeter.Scope.JOB, job.jobId(), job.jobBudgetUsd(), ledger);
            this.waitMs = millis(job.batchWait(), InferenceSettings.TIMEOUT_BATCH_WAIT_MS, 7_200_000L);
            this.pollMs = millis(job.batchPoll(), InferenceSettings.TIMEOUT_BATCH_POLL_MS, 30_000L);
            this.callMs = millis(null, InferenceSettings.TIMEOUT_CALL_MS, 120_000L);
        }

        private long millis(Duration given, String key, long fallback) {
            if (given != null) {
                return given.toMillis();
            }
            Long setting = settings.timeouts().get(key);
            return setting == null ? fallback : Math.max(0, setting);
        }

        ReResolveReport run() throws AgentLostException, InterruptedException {
            Instant startedAt = clock.instant();
            long t0 = clock.millis();
            List<Item> items = new ArrayList<>();
            for (Plan plan : plans) {
                items.add(new Item(plan));
            }
            try {
                String preflight = null;
                for (Item item : items) {
                    if (item.plan.needsBindings()) {
                        preflight = preflight();
                        break;
                    }
                }
                List<Item> pending = new ArrayList<>();
                for (Item item : items) {
                    if (!item.plan.needsBindings()) {
                        item.status = ReResolveReport.Status.SKIPPED;
                        continue;
                    }
                    if (preflight != null) {
                        fail(item, FailureKind.ENVIRONMENT, ReResolveReport.REASON_PREFLIGHT, preflight);
                        continue;
                    }
                    readPrevious(item);
                    step(item, null);
                    if (item.parked != null) {
                        pending.add(item);
                    }
                }
                settle();
                while (!pending.isEmpty()) {
                    rounds++;
                    pending = round(pending);
                }
            } finally {
                for (Item item : items) {
                    item.abandon();
                }
            }
            return report(items, startedAt, Duration.ofMillis(Math.max(0, clock.millis() - t0)));
        }

        // ---------------------------------------------------------------------------------------------- rounds

        /** One round: the parked requests as one batch (those the budget allows), then every bind resumed in order. */
        private List<Item> round(List<Item> round) throws AgentLostException, InterruptedException {
            List<Item> asking = new ArrayList<>();
            List<ModelRequest> requestsOfRound = new ArrayList<>();
            for (Item item : round) {
                Object prepared = item.parked.request();
                if (!(prepared instanceof ModelRequest request)) {
                    String what = prepared == null ? "nothing" : prepared.getClass().getName();
                    String where = "slot '" + item.parked.slot() + "': resolver " + item.parked.resolverId();
                    item.abandon();
                    fail(item, FailureKind.ENVIRONMENT, null, "pack defect: " + where + " prepared " + what
                            + ", not a model request");
                    continue;
                }
                if (budgetCut) {
                    String slot = item.parked.slot();
                    item.abandon();
                    fail(item, FailureKind.RESOLUTION_FAILED, ReResolveReport.REASON_BUDGET, "slot '" + slot + "': the"
                            + " job budget of $" + plain(meter.limit()) + " was exhausted in an earlier round ($"
                            + plain(meter.spent()) + " spent); no further batch is submitted");
                    continue;
                }
                asking.add(item);
                requestsOfRound.add(request);
            }
            if (asking.isEmpty()) {
                return List.of();
            }
            int fit = reserve(asking, requestsOfRound);
            if (fit == 0) {
                return List.of();
            }
            List<Item> submitted = asking.subList(0, fit);
            Answers answers = answer(requestsOfRound.subList(0, fit));
            meter.release();
            if (answers.timedOut()) {
                for (Item item : submitted) {
                    String slot = item.parked.slot();
                    item.abandon();
                    fail(item, FailureKind.ENVIRONMENT, ReResolveReport.REASON_BATCH_TIMEOUT, "slot '" + slot
                            + "': the batch did not end within " + Duration.ofMillis(waitMs) + "; it was cancelled");
                }
                notes.add("A batch did not end within " + Duration.ofMillis(waitMs) + " and was cancelled; what the"
                        + " provider processed before the cancel is billed and not in the job's ledger.");
                return List.of();
            }
            List<Item> next = new ArrayList<>();
            for (int i = 0; i < submitted.size(); i++) {
                Item item = submitted.get(i);
                Object answer = answers.answers().get(i);
                // A retryable failure is retried inline by the resolver, at the regular rate (8.3): the job meter
                // reserves that call's estimate first, or the scenario fails BUDGET without the call.
                boolean retry = retryable(answer);
                if (retry) {
                    BigDecimal estimate = estimate(requestsOfRound.get(i), false);
                    try {
                        meter.reserve(estimate);
                    } catch (ai.skillroy.testjava.inference.budget.BudgetException e) {
                        budgetCut = true;
                        String slot = item.parked.slot();
                        item.abandon();
                        fail(item, FailureKind.RESOLUTION_FAILED, ReResolveReport.REASON_BUDGET, "slot '" + slot
                                + "': the job budget of $" + plain(meter.limit()) + " has $" + plain(meter.spent())
                                + " spent and no room for the inline retry of its failed batch answer, estimated at $"
                                + plain(estimate) + " (the regular rate)");
                        notes.add("The job budget of $" + plain(meter.limit()) + " had no room for an inline retry in"
                                + " round " + rounds + ", and no later batch is submitted.");
                        continue;
                    }
                }
                try {
                    step(item, answer);
                } finally {
                    if (retry) {
                        // The retry, made or not, is over: its reservation gives way to its cost.
                        meter.release();
                        settle();
                    }
                }
                if (item.parked != null) {
                    next.add(item);
                }
            }
            settle();
            return next;
        }

        /**
         * The job meter's reservation for the round (8.3): the sum of the estimates; when it does not fit, the longest
         * prefix that does, the others' scenarios failed {@code BUDGET} and no later batch submitted.
         *
         * @return how many requests (from the first) are submitted
         */
        private int reserve(List<Item> asking, List<ModelRequest> requestsOfRound) {
            List<BigDecimal> estimates = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (ModelRequest request : requestsOfRound) {
                BigDecimal estimate = estimate(request);
                estimates.add(estimate);
                total = total.add(estimate);
            }
            try {
                meter.reserve(total);
                return asking.size();
            } catch (ai.skillroy.testjava.inference.budget.BudgetException whole) {
                budgetCut = true;
            }
            BigDecimal room = meter.remaining();
            int fit = 0;
            BigDecimal sum = BigDecimal.ZERO;
            if (room != null && meter.limit() != null && meter.limit().signum() > 0) {
                while (fit < estimates.size() && sum.add(estimates.get(fit)).compareTo(room) <= 0) {
                    sum = sum.add(estimates.get(fit));
                    fit++;
                }
            }
            if (fit > 0) {
                try {
                    meter.reserve(sum);
                } catch (ai.skillroy.testjava.inference.budget.BudgetException e) {
                    fit = 0;
                }
            }
            for (int i = fit; i < asking.size(); i++) {
                Item item = asking.get(i);
                String slot = item.parked.slot();
                item.abandon();
                fail(item, FailureKind.RESOLUTION_FAILED, ReResolveReport.REASON_BUDGET, "slot '" + slot + "': the job"
                        + " budget of $" + plain(meter.limit()) + " has $" + plain(meter.spent()) + " spent and no"
                        + " room for this round's model call estimated at $" + plain(estimates.get(i))
                        + " (the batch rate)");
            }
            notes.add("The job budget of $" + plain(meter.limit()) + " cut round " + rounds + ": " + fit + " of "
                    + asking.size() + " requests were submitted, and no later batch is.");
            return fit;
        }

        /** The estimate of one request at the batch rate: {@link #estimate(ModelRequest, boolean)}. */
        private BigDecimal estimate(ModelRequest request) {
            return estimate(request, true);
        }

        /** True for a round's answer that the resolver retries inline: a retryable failure. */
        private static boolean retryable(Object answer) {
            if (answer instanceof InferenceException e) {
                return e.retryable();
            }
            return answer instanceof BatchResult b && b.error() != null && b.error().retryable();
        }

        /**
         * The estimate of one request (7.2): its input tokens as the gateway counts them, else its text length / 4,
         * plus its {@code maxTokens}, at the price of the model its class names, at the batch rate or the regular one.
         */
        private BigDecimal estimate(ModelRequest request, boolean batch) {
            long input = -1;
            if (gateway.capabilities().contains(GatewayCapability.TOKEN_COUNT)) {
                try {
                    input = gateway.countTokens(request);
                } catch (InferenceException | RuntimeException e) {
                    input = -1;
                }
            }
            if (input < 0) {
                input = textLength(request) / 4;
            }
            String model = settings.models().get(request.modelClass().settingsKey());
            return prices.estimate(model, input, request.maxTokens(), batch);
        }

        private static long textLength(ModelRequest request) {
            long chars = 0;
            for (Content part : request.system()) {
                chars += part instanceof Content.Text t ? t.text().length() : 0;
            }
            for (Message message : request.messages()) {
                for (Content part : message.parts()) {
                    chars += part instanceof Content.Text t ? t.text().length() : 0;
                }
            }
            return chars;
        }

        /**
         * The round's answers: one batch when the gateway has {@code BATCH}, waited for within {@code batchWait}
         * (cancelled after), else one completion per request. A failed submission, status read or collection answers
         * every request with the failure, which the resolver then reports (or retries once, inline, when it is
         * retryable).
         */
        private Answers answer(List<ModelRequest> batch) throws InterruptedException {
            int n = batch.size();
            if (!gateway.capabilities().contains(GatewayCapability.BATCH)) {
                if (!noBatchNoted) {
                    noBatchNoted = true;
                    notes.add("The " + gateway.provider() + " gateway has no batch interface: each round's requests"
                            + " were answered one by one, at the regular rate.");
                }
                List<Object> out = new ArrayList<>(n);
                for (ModelRequest request : batch) {
                    try {
                        out.add(gateway.complete(request, Duration.ofMillis(Math.max(1, callMs))));
                    } catch (InferenceException e) {
                        out.add(e);
                    }
                    requests++;
                }
                return new Answers(out, false);
            }
            BatchHandle handle;
            try {
                handle = gateway.submitBatch(batch);
            } catch (InferenceException e) {
                return all(e, n);
            } catch (UnsupportedOperationException e) {
                return all(new InferenceException(InferenceException.Kind.UNSUPPORTED, "the gateway refused the"
                        + " batch: " + e.getMessage()), n);
            }
            batches++;
            requests += n;
            long deadline = clock.millis() + waitMs;
            try {
                while (true) {
                    BatchStatus status = null;
                    try {
                        status = gateway.batchStatus(handle);
                    } catch (InferenceException e) {
                        if (!e.retryable() && e.kind() != InferenceException.Kind.TIMEOUT) {
                            cancel(handle);
                            return all(e, n);
                        }
                    }
                    if (Thread.interrupted()) {
                        // A gateway reports an interrupt during a batch method as TIMEOUT with the flag set again.
                        throw new InterruptedException("interrupted while waiting for a batch");
                    }
                    if (status != null && status.state() == BatchStatus.State.ENDED) {
                        break;
                    }
                    long now = clock.millis();
                    if (now >= deadline) {
                        cancel(handle);
                        return new Answers(List.of(), true);
                    }
                    long wait = Math.min(pollMs, deadline - now);
                    if (wait > 0) {
                        pause.await(wait, TimeUnit.MILLISECONDS);
                    }
                }
            } catch (InterruptedException e) {
                cancel(handle);
                throw e;
            }
            List<BatchResult> results;
            try {
                results = gateway.collectBatch(handle);
            } catch (InferenceException e) {
                return all(e, n);
            }
            Object[] byIndex = new Object[n];
            for (BatchResult result : results == null ? List.<BatchResult>of() : results) {
                if (result != null && result.index() < n && byIndex[result.index()] == null) {
                    byIndex[result.index()] = result;
                }
            }
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(byIndex[i] != null ? byIndex[i] : new InferenceException(InferenceException.Kind.PROVIDER,
                        "the batch has no result for request " + i));
            }
            return new Answers(out, false);
        }

        private static Answers all(InferenceException failure, int n) {
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(failure);
            }
            return new Answers(out, false);
        }

        /** Asks the provider to cancel the batch; a failure is noted, never thrown. */
        private void cancel(BatchHandle handle) {
            try {
                gateway.cancelBatch(handle);
            } catch (InferenceException | RuntimeException e) {
                notes.add("The cancel of batch " + handle.id() + " failed: " + mask.apply(String.valueOf(
                        e.getMessage())));
            }
        }

        // ---------------------------------------------------------------------------------------------- binds

        /**
         * Starts a bind ({@code answer} null) or resumes a parked one with its answer, and records where it stands:
         * parked again, done, or failed.
         */
        private void step(Item item, Object answer) throws AgentLostException, InterruptedException {
            BindStep step;
            try {
                if (item.parked == null) {
                    step = Binder.bindStepwise(item.plan, pack, agent, base, BindListener.NONE);
                } else {
                    BindStep.Parked parked = item.parked;
                    item.parked = null;
                    step = parked.resume(answer);
                }
            } catch (BindException e) {
                fail(item, e.kind(), e.reason(), e.getMessage());
                return;
            }
            if (step instanceof BindStep.Parked parked) {
                item.parked = parked;
                item.parkedCount++;
            } else {
                done(item, ((BindStep.Done) step).outcome());
            }
        }

        private void fail(Item item, FailureKind kind, String reason, String message) {
            item.status = ReResolveReport.Status.FAILED;
            item.failureKind = kind;
            item.reason = reason;
            item.message = message == null ? null : mask.apply(message);
        }

        /** The scenario's file against the one it had: NEW, UNCHANGED (byte-identical) or CHANGED with the lines. */
        private void done(Item item, BindOutcome outcome) {
            BindingsFile file = outcome.file();
            item.written = outcome.written() != null;
            if (file == null) {
                item.status = ReResolveReport.Status.SKIPPED;
                return;
            }
            String text = file.toJsonText();
            if (item.previousText == null) {
                item.status = ReResolveReport.Status.NEW;
                List<String> lines = new ArrayList<>();
                for (Resolution r : file.resolutions().values()) {
                    lines.add("resolutions." + r.slot() + ".value: " + value(r.value()));
                }
                for (var oracle : file.oracles().values()) {
                    lines.add("oracles." + oracle.name() + ".value: " + value(oracle.value()));
                }
                item.changes = masked(lines);
                return;
            }
            if (text.equals(item.previousText)) {
                item.status = ReResolveReport.Status.UNCHANGED;
                return;
            }
            item.status = ReResolveReport.Status.CHANGED;
            List<String> lines = new ArrayList<>();
            BindingsDiff diff = item.previous.diff(file);
            for (BindingsDiff.Change change : diff.changes()) {
                lines.add(change.where() + ": " + value(change.before()) + " -> " + value(change.after()));
            }
            for (Resolution after : file.resolutions().values()) {
                Resolution before = item.previous.resolutions().get(after.slot());
                if (before == null) {
                    continue;
                }
                String where = "resolutions." + after.slot();
                if (!Objects.equals(approval(before.approval()), approval(after.approval()))) {
                    lines.add(where + ".approval: " + approval(before.approval()) + " -> "
                            + approval(after.approval()));
                }
                String ra = before.provenance() == null ? null : before.provenance().rationale();
                String rb = after.provenance() == null ? null : after.provenance().rationale();
                if (!Objects.equals(ra, rb)) {
                    lines.add(where + ".rationale: " + value(ra) + " -> " + value(rb));
                }
                if (Double.compare(before.confidence(), after.confidence()) != 0) {
                    lines.add(where + ".confidence: " + before.confidence() + " -> " + after.confidence());
                }
            }
            if (lines.isEmpty()) {
                lines.add("the file's text differs in what the comparison does not show (the model usage or the"
                        + " candidates counted)");
            }
            item.changes = masked(lines);
        }

        private List<String> masked(List<String> lines) {
            List<String> out = new ArrayList<>(lines.size());
            for (String line : lines) {
                out.add(mask.apply(line));
            }
            return out;
        }

        private static String approval(Approval approval) {
            if (approval == null) {
                return "none";
            }
            return approval.state().name() + (approval.by() == null ? "" : " by " + approval.by());
        }

        private static String value(Object value) {
            String text;
            try {
                text = value instanceof String s ? s : Json.write(value);
            } catch (RuntimeException e) {
                text = String.valueOf(value);
            }
            return text.length() > 200 ? text.substring(0, 197) + "..." : text;
        }

        /** The scenario's bindings file before the job, read once; an unreadable one is noted and counts as none. */
        private void readPrevious(Item item) {
            Path root = base.bindingsRoot();
            if (root == null) {
                return;
            }
            Path path = BindingsFile.locate(root, base.envId(), item.plan.scenarioId());
            if (!Files.isRegularFile(path)) {
                return;
            }
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                item.previous = BindingsFile.parse(text);
                item.previousText = text;
            } catch (IOException | BindingsFormatException e) {
                notes.add("The bindings file " + base.envId() + "/" + item.plan.scenarioId() + BindingsFile.SUFFIX
                        + " could not be read before the job (" + mask.apply(String.valueOf(e.getMessage()))
                        + "); the scenario is reported as new.");
            }
        }

        /** The pack's pre-flight, once (8.2 step 1): null when every check passes, else the first failure. */
        private String preflight() throws AgentLostException, InterruptedException {
            List<PreflightCheck> checks;
            try {
                checks = pack.preflight();
            } catch (RuntimeException e) {
                return "pack " + pack.id() + ": its pre-flight checks cannot be read: " + e;
            }
            if (checks == null || checks.isEmpty()) {
                return null;
            }
            List<ai.skillroy.testjava.data.RowMask> rowMasks;
            try {
                rowMasks = pack.rowMasks();
            } catch (RuntimeException e) {
                rowMasks = List.of();
            }
            DataChannels data = DataChannels.of(agent, base.credentials(), rowMasks == null ? List.of() : rowMasks);
            try {
                PreflightCheck.PreflightContext ctx = new PreflightCheck.PreflightContext() {
                    @Override
                    public AgentConnection agent() {
                        return agent;
                    }

                    @Override
                    public DataChannel data() {
                        return data;
                    }

                    @Override
                    public String envId() {
                        return base.envId();
                    }
                };
                for (PreflightCheck check : checks) {
                    String reason;
                    try {
                        reason = check.check(ctx);
                    } catch (AgentLostException | InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        reason = "failed: " + e;
                    }
                    if (reason != null) {
                        return "pre-flight check " + check.id() + ": " + mask.apply(reason);
                    }
                }
                return null;
            } finally {
                data.close();
            }
        }

        /** Settles the job meter with the ledger entries recorded since the last settle (the job's model calls). */
        private void settle() {
            List<CostLedger.Entry> all = ledger.entries();
            for (int i = settled; i < all.size(); i++) {
                CostLedger.Entry e = all.get(i);
                meter.settle(new ai.skillroy.testjava.inference.spi.Usage(e.provider() == null ? "unknown"
                        : e.provider(), e.model(), e.model(), e.requestId(), e.inputTokens(), e.outputTokens(),
                        e.cacheReadTokens(), 0, 0, e.batch(), cost(e.costUsd()), Duration.ZERO, null));
            }
            settled = all.size();
        }

        private static String cost(String text) {
            if (text == null) {
                return "0";
            }
            try {
                return new BigDecimal(text.strip()).toPlainString();
            } catch (NumberFormatException e) {
                return "0";
            }
        }

        // ---------------------------------------------------------------------------------------------- report

        private ReResolveReport report(List<Item> items, Instant startedAt, Duration duration) {
            List<CostLedger.Entry> all = ledger.entries();
            List<CostLedger.Entry> entries = List.copyOf(all.subList(Math.min(ledgerStart, all.size()), all.size()));
            List<ReResolveReport.ScenarioReport> scenarios = new ArrayList<>();
            for (Item item : items) {
                BigDecimal cost = BigDecimal.ZERO;
                for (CostLedger.Entry e : entries) {
                    if (item.plan.scenarioId().equals(e.scenarioId())) {
                        cost = cost.add(new BigDecimal(cost(e.costUsd())));
                    }
                }
                String file = item.status == ReResolveReport.Status.SKIPPED ? null
                        : base.envId() + "/" + item.plan.scenarioId() + BindingsFile.SUFFIX;
                scenarios.add(new ReResolveReport.ScenarioReport(item.plan.scenarioId(), item.status, file,
                        item.written, item.parkedCount, cost, item.changes, item.failureKind, item.reason,
                        item.message));
            }
            BigDecimal spent = BigDecimal.ZERO;
            Set<String> models = new LinkedHashSet<>();
            List<Map<String, Object>> ledgerLines = new ArrayList<>();
            for (CostLedger.Entry e : entries) {
                spent = spent.add(new BigDecimal(cost(e.costUsd())));
                if (e.model() != null) {
                    models.add(e.model());
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> line = (Map<String, Object>) mask.apply(e.toJson());
                ledgerLines.add(line);
            }
            List<Map<String, Object>> records = log.records();
            List<Map<String, Object>> inferenceLines = new ArrayList<>();
            for (int i = Math.min(logStart, records.size()); i < records.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> line = (Map<String, Object>) mask.apply(records.get(i));
                inferenceLines.add(line);
            }
            return new ReResolveReport(job.jobId(), base.envId(), job.dryRun(), job.approvalLabel(),
                    gateway.provider(), startedAt, duration, scenarios, spent, job.jobBudgetUsd(), rounds, batches,
                    requests, LedgerWriter.cacheHitRatio(entries), List.copyOf(models), notes, ledgerLines,
                    inferenceLines);
        }

        private static String plain(BigDecimal amount) {
            return amount == null ? "none" : amount.toPlainString();
        }
    }
}
