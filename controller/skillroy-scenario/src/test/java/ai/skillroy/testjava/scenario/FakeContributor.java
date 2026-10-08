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

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BatchableResolver;
import ai.skillroy.testjava.scenario.resolve.ContributionException;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.ResolverContributor;
import ai.skillroy.testjava.scenario.resolve.Strategy;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The test tree's {@link ResolverContributor} (registered in {@code META-INF/services}), standing in for
 * {@code skillroy-inference} (phase5-contracts.md 5.5): found by every bind of this module's tests that loads
 * contributors, it offers nothing until a test sets {@link #offer}, and nothing while the settings' gateway is
 * {@code none}. Counters say how often it was instantiated, asked and closed; {@link #reset()} clears everything (call
 * it before and after each test that uses it). Static on purpose: {@code ServiceLoader} instantiates it.
 *
 * <p>Two fake model resolvers for the toy pack's {@code Item} kind come with it: {@link FakeLlm} (strategy
 * {@code LLM_SQL}, plain) and {@link FakeBatch} (a {@link BatchableResolver}). Their candidates are item codes, looked
 * up in the toy database through {@code ctx.data()}; they record a ledger entry per "model call" when told to.
 */
public final class FakeContributor implements ResolverContributor {

    /** What the contributor offers when the gateway is not none. */
    @FunctionalInterface
    interface Offer {
        List<Resolver<?, ?>> resolvers(DomainPack pack, InferenceSettings settings, CostLedger ledger, Clock clock)
                throws ContributionException;
    }

    static final AtomicInteger CONSTRUCTED = new AtomicInteger();
    static final AtomicInteger ASKED = new AtomicInteger();
    static final AtomicInteger CLOSED = new AtomicInteger();
    static final List<InferenceSettings> SETTINGS = new CopyOnWriteArrayList<>();
    static volatile Offer offer;

    /** Clears the counters and the offer. */
    static void reset() {
        CONSTRUCTED.set(0);
        ASKED.set(0);
        CLOSED.set(0);
        SETTINGS.clear();
        offer = null;
    }

    /** Offers these resolvers whatever the pack, when the gateway is not none. */
    static void offering(Resolver<?, ?>... resolvers) {
        List<Resolver<?, ?>> list = List.of(resolvers);
        offer = (pack, settings, ledger, clock) -> list;
    }

    /** Instantiated by {@code ServiceLoader}. */
    public FakeContributor() {
        CONSTRUCTED.incrementAndGet();
    }

    @Override
    public List<Resolver<?, ?>> resolvers(DomainPack pack, InferenceSettings settings, CostLedger ledger, Clock clock)
            throws ContributionException {
        ASKED.incrementAndGet();
        SETTINGS.add(settings);
        Offer current = offer;
        if (!settings.enabled() || current == null) {
            return List.of();
        }
        return current.resolvers(pack, settings, ledger, clock);
    }

    @Override
    public Map<String, CapabilityState> capabilities(InferenceSettings settings) {
        return Map.of("inference.gateway", settings.enabled() ? CapabilityState.OK : CapabilityState.UNAVAILABLE);
    }

    @Override
    public void close() {
        CLOSED.incrementAndGet();
    }

    /** What a fake resolver saw of one call. */
    record Seen(String call, String slot, String scenarioId, Object query, Object answer) {
    }

    /** The items with these codes, read from the toy database through the context's data channel, in that order. */
    static List<ResolverCandidate<ToyResolution.ItemRef>> items(ResolveContext ctx, List<String> codes,
                                                               String sql)
            throws ai.skillroy.testjava.data.DataException, ai.skillroy.testjava.controller.AgentLostException,
            InterruptedException {
        Rows rows = ctx.data().query(ToyResolution.SOURCE, sql, List.of(), 200, null);
        List<ResolverCandidate<ToyResolution.ItemRef>> out = new ArrayList<>();
        for (String code : codes) {
            for (int r = 0; r < rows.rowCount(); r++) {
                if (code.equals(String.valueOf(rows.get(r, 0)))) {
                    out.add(new ResolverCandidate<>(new ToyResolution.ItemRef(code,
                            new BigDecimal(String.valueOf(rows.get(r, 1)))), "item " + code, 0.9));
                }
            }
        }
        return out;
    }

    /** A plain model-backed resolver for {@code Item}: id {@code inference.fake-sql}, strategy {@code LLM_SQL}. */
    static final class FakeLlm implements Resolver<ToyResolution.Item, ToyResolution.ItemRef> {

        final String id;
        final Strategy strategy;
        /** The codes it offers, looked up in the toy database. */
        volatile List<String> codes = List.of();
        /** The statement it reads the items with. */
        volatile String sql = ToyResolution.ITEM_SQL;
        /** Ledger entries it records per call (input tokens, output tokens, cost); empty records none. */
        volatile List<CostLedger.Entry> entries = List.of();
        /** Thrown instead of answering, when not null. */
        volatile Exception failure;
        /** Given to {@code ctx.rationale} on each call, when not null (the provenance seam). */
        volatile String rationale;
        /** Each {@code {label, reason}} given to {@code ctx.reject} on each call (the provenance seam). */
        volatile List<List<String>> rejections = List.of();
        final List<Seen> seen = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        FakeLlm() {
            this("inference.fake-sql", Strategy.LLM_SQL);
        }

        FakeLlm(String id, Strategy strategy) {
            this.id = id;
            this.strategy = strategy;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Class<ToyResolution.Item> queryClass() {
            return ToyResolution.Item.class;
        }

        @Override
        public Strategy strategy() {
            return strategy;
        }

        @Override
        public List<ResolverCandidate<ToyResolution.ItemRef>> candidates(ToyResolution.Item query,
                                                                        ResolveContext ctx) throws
                ResolutionException, ai.skillroy.testjava.data.DataException,
                ai.skillroy.testjava.controller.AgentLostException, InterruptedException {
            calls.incrementAndGet();
            seen.add(new Seen("candidates", ctx.slot(), ctx.scenarioId(), query, null));
            return answer(ctx);
        }

        List<ResolverCandidate<ToyResolution.ItemRef>> answer(ResolveContext ctx) throws ResolutionException,
                ai.skillroy.testjava.data.DataException, ai.skillroy.testjava.controller.AgentLostException,
                InterruptedException {
            for (CostLedger.Entry entry : entries) {
                ctx.ledger().record(entry);
            }
            if (rationale != null) {
                ctx.rationale(rationale);
            }
            for (List<String> rejection : rejections) {
                ctx.reject(rejection.get(0), rejection.get(1));
            }
            Exception f = failure;
            if (f instanceof ResolutionException e) {
                throw e;
            }
            if (f instanceof RuntimeException e) {
                throw e;
            }
            return items(ctx, codes, sql);
        }
    }

    /** A model-backed resolver for {@code Staff} ({@code inference.fake-staff}): offers the staff rows with these codes. */
    static final class FakeStaff implements Resolver<ToyResolution.Staff, ToyResolution.StaffRef> {

        volatile List<String> codes = List.of();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String id() {
            return "inference.fake-staff";
        }

        @Override
        public Class<ToyResolution.Staff> queryClass() {
            return ToyResolution.Staff.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.LLM_SQL;
        }

        @Override
        public List<ResolverCandidate<ToyResolution.StaffRef>> candidates(ToyResolution.Staff query,
                                                                         ResolveContext ctx)
                throws ai.skillroy.testjava.data.DataException, ai.skillroy.testjava.controller.AgentLostException,
                InterruptedException {
            calls.incrementAndGet();
            Rows rows = ctx.data().query(ToyResolution.SOURCE, ToyResolution.STAFF_SQL, List.of(), 200, null);
            List<ResolverCandidate<ToyResolution.StaffRef>> out = new ArrayList<>();
            for (String code : codes) {
                for (int r = 0; r < rows.rowCount(); r++) {
                    if (code.equals(String.valueOf(rows.get(r, 0)))) {
                        out.add(new ResolverCandidate<>(new ToyResolution.StaffRef(code,
                                new BigDecimal(String.valueOf(rows.get(r, 1)))), "staff " + code, 0.85));
                    }
                }
            }
            return out;
        }
    }

    /**
     * A batchable model-backed resolver for {@code Item}: id {@code inference.fake-batch}. {@code prepare} returns the
     * query's deposit range and the slot as its request; {@code complete} takes a list of item codes (the "model's
     * answer"), or a map {@code {sql, codes}} whose statement it runs; {@code candidates} offers {@link #codes}.
     */
    static final class FakeBatch implements BatchableResolver<ToyResolution.Item, ToyResolution.ItemRef> {

        volatile List<String> codes = List.of();
        /** Ledger entries recorded in {@code complete}. */
        volatile List<CostLedger.Entry> entries = List.of();
        /** Thrown by {@code prepare} when not null. */
        volatile ResolutionException prepareFailure;
        /** {@code prepare} returns null (a defect). */
        volatile boolean prepareNothing;
        final List<Seen> seen = new CopyOnWriteArrayList<>();
        final AtomicInteger candidateCalls = new AtomicInteger();

        @Override
        public String id() {
            return "inference.fake-batch";
        }

        @Override
        public Class<ToyResolution.Item> queryClass() {
            return ToyResolution.Item.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.LLM_SQL;
        }

        @Override
        public List<ResolverCandidate<ToyResolution.ItemRef>> candidates(ToyResolution.Item query,
                                                                        ResolveContext ctx)
                throws ai.skillroy.testjava.data.DataException, ai.skillroy.testjava.controller.AgentLostException,
                InterruptedException {
            candidateCalls.incrementAndGet();
            seen.add(new Seen("candidates", ctx.slot(), ctx.scenarioId(), query, null));
            return items(ctx, codes, ToyResolution.ITEM_SQL);
        }

        @Override
        public Object prepare(ToyResolution.Item query, ResolveContext ctx) throws ResolutionException {
            seen.add(new Seen("prepare", ctx.slot(), ctx.scenarioId(), query, null));
            if (prepareFailure != null) {
                throw prepareFailure;
            }
            if (prepareNothing) {
                return null;
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("slot", ctx.slot());
            request.put("mode", ctx.mode().label());
            request.put("lowerBound", query.deposit() == null || query.deposit().lowerBound() == null ? null
                    : query.deposit().lowerBound().toPlainString());
            return request;
        }

        @Override
        public List<ResolverCandidate<ToyResolution.ItemRef>> complete(ToyResolution.Item query, ResolveContext ctx,
                                                                      Object answer)
                throws ai.skillroy.testjava.data.DataException, ai.skillroy.testjava.controller.AgentLostException,
                InterruptedException {
            seen.add(new Seen("complete", ctx.slot(), ctx.scenarioId(), query, answer));
            for (CostLedger.Entry entry : entries) {
                ctx.ledger().record(entry);
            }
            if (answer instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                List<String> answerCodes = (List<String>) map.get("codes");
                return items(ctx, answerCodes, (String) map.get("sql"));
            }
            @SuppressWarnings("unchecked")
            List<String> answerCodes = (List<String>) answer;
            return items(ctx, answerCodes, ToyResolution.ITEM_SQL);
        }
    }
}
