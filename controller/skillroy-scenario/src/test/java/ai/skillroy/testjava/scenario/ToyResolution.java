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

import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.CrossCheck;
import ai.skillroy.testjava.scenario.resolve.CrossCheckResult;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.NumberRange;
import ai.skillroy.testjava.scenario.resolve.Oracle;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The toy pack's resolution model for binder and executor tests: three query kinds (an item in a deposit range, a
 * staff member below a limit, a configured limit), SQL resolvers that read a small in-memory database through the
 * fake agent's {@code data.query}, verifiers that ask the fake "application" through domain primitives, three
 * oracles and a fingerprinter. Fields let a test change the data and inject behaviour.
 *
 * <p>Phase 3: with {@link #withCrossChecks} the catalogue also holds two cross-checks: {@code toy.negate.check} for
 * {@code Negate} (asks the fake application's {@code toy.negate}, accepts a tolerance, requires {@code toy.negate})
 * and {@code toy.needs.check} for {@code Needs} (asks {@code toy.needs}, takes no tolerance, requires
 * {@code toy.needs}). Off by default, so that the Phase 2 tests keep their catalogue (one of them replaces
 * {@code Negate} by an oracle of other types, which a check for it would refuse).
 */
final class ToyResolution {

    /** An item whose deposit is in a range. */
    record Item(NumberRange deposit) implements Query<ItemRef> {
    }

    /** The answer to {@link Item}. */
    record ItemRef(String code, BigDecimal deposit) {
    }

    /** A staff member whose limit is below an amount. */
    record Staff(BigDecimal limitBelow, List<String> distinctFrom) implements Query<StaffRef> {
    }

    /** The answer to {@link Staff}. */
    record StaffRef(String code, BigDecimal limit) {
    }

    /** A configured limit. */
    record Limit(String type) implements Query<BigDecimal> {
    }

    /** A pack hook that may throw anything. */
    @FunctionalInterface
    interface Hook {
        void run(ResolveContext ctx) throws Exception;
    }

    /** A verifier hook. */
    @FunctionalInterface
    interface VerifyHook {
        void run(VerifyContext ctx) throws Exception;
    }

    /** What one call of a cross-check received. */
    record CheckCall(String check, Object input, Object output, BigDecimal tolerance) {
    }

    static final String SOURCE = "toy";
    static final String ITEM_SQL = "SELECT code, deposit FROM item ORDER BY deposit, code";
    static final String STAFF_SQL = "SELECT code, lim FROM staff ORDER BY code";
    static final String LIMIT_SQL = "SELECT value FROM setting WHERE name = ? ORDER BY name";

    /** item rows: code, deposit (text). */
    final List<List<Object>> items = new ArrayList<>(List.of(List.of("A1", "1.00"), List.of("B2", "5.00"),
            List.of("C3", "9.99")));
    /** staff rows: code, limit (text, or null for unlimited). */
    final List<List<Object>> staff = new ArrayList<>(List.of(List.of("100", "2.00"), List.of("200", "50.00")));
    /** setting name to value. */
    final Map<String, String> settings = new LinkedHashMap<>(Map.of("max_return", "100.00", "max_want", "100.00"));
    /** Other statements: sql to rows, for db checks. */
    final Map<String, Rows> tables = new HashMap<>();
    /** Items the application reports inactive. */
    final Set<String> inactive = new HashSet<>();
    /** Item rows are reported truncated. */
    boolean truncateItems;
    /** The item resolver needs its rows whole. */
    boolean requireCompleteItems;
    /** The staff resolver skips rows without credentials itself. */
    boolean staffSkipsEarly;
    /** The limit verifier accepts any value. */
    boolean limitVerifierAccepts;
    /** The seed the fingerprinter reports. */
    String seed = "A";
    /** Candidates of a second, Java, item resolver; null = no such resolver. */
    List<ItemRef> javaItems;
    /** Runs at the start of the SQL item resolver. */
    Hook beforeItem = ctx -> { };
    /** Runs at the start of the staff verifier. */
    VerifyHook beforeStaffCheck = ctx -> { };
    /** The catalogue holds the two cross-checks (Phase 3). */
    boolean withCrossChecks;
    /** What the fake application's {@code toy.negate} answers; null computes the negation itself. */
    String negateAnswer;
    /** Runs at the start of {@code toy.negate.check}. */
    VerifyHook beforeNegateCheck = ctx -> { };
    /** {@code toy.negate.check} returns null. */
    boolean negateCheckReturnsNull;
    /** Added to the evidence of {@code toy.negate.check} under {@code note}, when not null. */
    String negateEvidenceNote;
    /** The timeout {@code toy.negate.check} asks for its call, when not null. */
    Long negateCallTimeoutMs;
    /** Every cross-check call, in order. */
    final List<CheckCall> checkCalls = new ArrayList<>();

    final AtomicInteger itemResolverCalls = new AtomicInteger();
    final AtomicInteger javaResolverCalls = new AtomicInteger();
    final AtomicInteger verifierCalls = new AtomicInteger();

    /** Answers {@code data.sources}, {@code data.query}, the verifiers' primitives and {@code app.state}. */
    void install(FakeAgentConnection agent) {
        agent.value("data.sources", Map.of("sources", List.of(Map.of("id", SOURCE))));
        agent.on("data.query", params -> {
            String sql = String.valueOf(params.get("sql"));
            try {
                sql = ai.skillroy.testjava.protocol.sql.StatementGuard.check(sql);
            } catch (ai.skillroy.testjava.protocol.RpcException e) {
                throw new ai.skillroy.testjava.controller.AgentRpcException("data.query", e.error());
            }
            return query(sql, (List<?>) params.get("params")).toJson();
        });
        agent.on("toy.item", params -> {
            String code = String.valueOf(params.get("code"));
            if (javaItems != null) {
                for (ItemRef ref : javaItems) {
                    if (ref.code().equals(code)) {
                        return Map.of("found", true, "active", !inactive.contains(code), "deposit",
                                ref.deposit().toPlainString());
                    }
                }
            }
            for (List<Object> row : items) {
                if (row.get(0).equals(code)) {
                    return Map.of("found", true, "active", !inactive.contains(code), "deposit", row.get(1));
                }
            }
            return Map.of("found", false);
        });
        agent.on("toy.staff", params -> {
            String code = String.valueOf(params.get("code"));
            for (List<Object> row : staff) {
                if (row.get(0).equals(code)) {
                    Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("found", true);
                    answer.put("limit", row.get(1));
                    return answer;
                }
            }
            return Map.of("found", false);
        });
        agent.on("toy.limits", params -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("max", settings.get("max_" + String.valueOf(params.get("type")).toLowerCase()));
            return answer;
        });
        agent.on("app.state", params -> Map.of("toy", Map.of("seed", seed)));
        agent.on("toy.negate", params -> {
            String amount = negateAnswer != null ? negateAnswer
                    : new BigDecimal(String.valueOf(params.get("amount"))).negate().toPlainString();
            return Map.of("amount", amount);
        });
        agent.on("toy.needs", params -> {
            Object limit = params.get("limit");
            BigDecimal amount = new BigDecimal(String.valueOf(params.get("amount")));
            boolean prompt = limit != null && amount.compareTo(new BigDecimal(String.valueOf(limit))) > 0;
            return Map.of("decision", prompt ? "PROMPT" : "NONE");
        });
    }

    private Rows query(String sql, List<?> params) {
        List<Rows.Column> columns;
        List<List<Object>> rows;
        boolean truncated = false;
        switch (sql) {
            case ITEM_SQL -> {
                columns = List.of(new Rows.Column("CODE", "VARCHAR"), new Rows.Column("DEPOSIT", "DECIMAL"));
                rows = new ArrayList<>(items);
                truncated = truncateItems;
            }
            case STAFF_SQL -> {
                columns = List.of(new Rows.Column("CODE", "VARCHAR"), new Rows.Column("LIM", "DECIMAL"));
                rows = new ArrayList<>(staff);
            }
            case LIMIT_SQL -> {
                columns = List.of(new Rows.Column("VALUE", "VARCHAR"));
                rows = new ArrayList<>();
                String value = settings.get(String.valueOf(params.get(0)));
                if (value != null) {
                    rows.add(List.of(value));
                }
            }
            default -> {
                Rows table = tables.get(sql);
                if (table == null) {
                    throw new IllegalStateException("toy database: no rows for " + sql);
                }
                return table;
            }
        }
        return Rows.of(columns, rows, truncated);
    }

    /** The catalogue: kinds Item, Staff, Limit; resolvers, verifiers; oracles Negate, Needs, Identity. */
    ResolutionCatalogue catalogue() {
        ResolutionCatalogue.Builder builder = ResolutionCatalogue.builder()
                .query(QueryType.of("Item", Item.class))
                .query(QueryType.of("Staff", Staff.class))
                .query(QueryType.of("Limit", Limit.class))
                .resolver(new ItemResolver())
                .resolver(new StaffResolver())
                .resolver(new LimitResolver());
        if (javaItems != null) {
            builder.resolver(new JavaItemResolver());
        }
        builder.verifier(new ItemCheck())
                .verifier(new StaffCheck())
                .verifier(new LimitCheck())
                .oracle(new Negate())
                .oracle(new Needs())
                .oracle(new Identity());
        if (withCrossChecks) {
            builder.crossCheck(new NegateCheck()).crossCheck(new NeedsCheck());
        }
        return builder.build();
    }

    /** Reports the seed as {@code toy.seed}. */
    EnvFingerprinter fingerprinter() {
        return ctx -> {
            Object state = ctx.agent().call("app.state", Map.of()).value();
            Object seedValue = ((Map<?, ?>) ((Map<?, ?>) state).get("toy")).get("seed");
            return Map.of("toy.seed", String.valueOf(seedValue));
        };
    }

    /** A toy pack with this resolution model. */
    ToyPack pack() {
        ToyPack pack = new ToyPack();
        pack.resolution = catalogue();
        pack.fingerprinter = fingerprinter();
        return pack;
    }

    final class ItemResolver implements Resolver<Item, ItemRef> {
        @Override
        public String id() {
            return "toy.sql.Item";
        }

        @Override
        public Class<Item> queryClass() {
            return Item.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_SQL;
        }

        @Override
        public List<ResolverCandidate<ItemRef>> candidates(Item query, ResolveContext ctx) throws
                ResolutionException, ai.skillroy.testjava.data.DataException,
                ai.skillroy.testjava.controller.AgentRpcException,
                ai.skillroy.testjava.controller.AgentLostException, InterruptedException {
            itemResolverCalls.incrementAndGet();
            try {
                beforeItem.run(ctx);
            } catch (ResolutionException | RuntimeException | ai.skillroy.testjava.data.DataException
                     | ai.skillroy.testjava.controller.AgentRpcException
                     | ai.skillroy.testjava.controller.AgentLostException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            Rows rows = ctx.data().query(SOURCE, ITEM_SQL, List.of(), 200, null);
            if (requireCompleteItems) {
                ctx.requireComplete(rows);
            }
            List<ResolverCandidate<ItemRef>> out = new ArrayList<>();
            for (int r = 0; r < rows.rowCount(); r++) {
                BigDecimal deposit = new BigDecimal(String.valueOf(rows.get(r, 1)));
                if (query.deposit() == null || query.deposit().test(deposit)) {
                    String code = String.valueOf(rows.get(r, 0));
                    out.add(ResolverCandidate.of(new ItemRef(code, deposit), "item " + code));
                }
            }
            return out;
        }
    }

    final class JavaItemResolver implements Resolver<Item, ItemRef> {
        @Override
        public String id() {
            return "toy.java.Item";
        }

        @Override
        public Class<Item> queryClass() {
            return Item.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_JAVA;
        }

        @Override
        public List<ResolverCandidate<ItemRef>> candidates(Item query, ResolveContext ctx) {
            javaResolverCalls.incrementAndGet();
            List<ResolverCandidate<ItemRef>> out = new ArrayList<>();
            for (ItemRef ref : javaItems) {
                out.add(ResolverCandidate.of(ref, "java item " + ref.code()));
            }
            return out;
        }
    }

    final class StaffResolver implements Resolver<Staff, StaffRef> {
        @Override
        public String id() {
            return "toy.sql.Staff";
        }

        @Override
        public Class<Staff> queryClass() {
            return Staff.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_SQL;
        }

        @Override
        public List<ResolverCandidate<StaffRef>> candidates(Staff query, ResolveContext ctx) throws
                ai.skillroy.testjava.data.DataException, ai.skillroy.testjava.controller.AgentLostException,
                InterruptedException {
            Rows rows = ctx.data().query(SOURCE, STAFF_SQL, List.of(), 200, null);
            List<ResolverCandidate<StaffRef>> out = new ArrayList<>();
            for (int r = 0; r < rows.rowCount(); r++) {
                String code = String.valueOf(rows.get(r, 0));
                Object raw = rows.get(r, 1);
                BigDecimal limit = raw == null ? null : new BigDecimal(String.valueOf(raw));
                if (query.distinctFrom() != null && query.distinctFrom().contains(code)) {
                    continue;
                }
                if (query.limitBelow() != null && (limit == null || limit.compareTo(query.limitBelow()) >= 0)) {
                    continue;
                }
                if (staffSkipsEarly && ctx.credential() != null
                        && !ctx.credentials().has(ctx.credential().realm(), code)) {
                    continue;
                }
                out.add(ResolverCandidate.of(new StaffRef(code, limit), "staff " + code));
            }
            return out;
        }
    }

    final class LimitResolver implements Resolver<Limit, BigDecimal> {
        @Override
        public String id() {
            return "toy.sql.Limit";
        }

        @Override
        public Class<Limit> queryClass() {
            return Limit.class;
        }

        @Override
        public Strategy strategy() {
            return Strategy.CANONICAL_SQL;
        }

        @Override
        public List<ResolverCandidate<BigDecimal>> candidates(Limit query, ResolveContext ctx) throws
                ai.skillroy.testjava.data.DataException, ai.skillroy.testjava.controller.AgentLostException,
                InterruptedException {
            Rows rows = ctx.data().query(SOURCE, LIMIT_SQL, List.of("max_" + query.type().toLowerCase()), 200,
                    null);
            if (rows.rowCount() != 1 || rows.get(0, 0) == null) {
                return List.of();
            }
            return List.of(ResolverCandidate.of(new BigDecimal(String.valueOf(rows.get(0, 0))), "limit "
                    + query.type()));
        }
    }

    final class ItemCheck implements Verifier<Item, ItemRef> {
        @Override
        public String id() {
            return "toy.item.check";
        }

        @Override
        public Class<Item> queryClass() {
            return Item.class;
        }

        @Override
        public List<String> requires() {
            return List.of("toy.item");
        }

        @Override
        public VerifierResult verify(Item query, ItemRef candidate, VerifyContext ctx) throws
                ai.skillroy.testjava.controller.AgentRpcException, ai.skillroy.testjava.controller.AgentLostException {
            verifierCalls.incrementAndGet();
            Object answer = ctx.agent().call("toy.item", Map.of("code", candidate.code())).value();
            Map<?, ?> a = (Map<?, ?>) answer;
            Map<String, Object> evidence = Map.of("toy.item", answer);
            if (!Boolean.TRUE.equals(a.get("active"))) {
                return VerifierResult.fail("item " + candidate.code() + " is not active", evidence);
            }
            if (new BigDecimal(String.valueOf(a.get("deposit"))).compareTo(candidate.deposit()) != 0) {
                return VerifierResult.fail("deposit differs", evidence);
            }
            return VerifierResult.pass("active, deposit " + a.get("deposit"), evidence);
        }
    }

    final class StaffCheck implements Verifier<Staff, StaffRef> {
        @Override
        public String id() {
            return "toy.staff.check";
        }

        @Override
        public Class<Staff> queryClass() {
            return Staff.class;
        }

        @Override
        public VerifierResult verify(Staff query, StaffRef candidate, VerifyContext ctx) throws
                ai.skillroy.testjava.controller.AgentRpcException, ai.skillroy.testjava.controller.AgentLostException,
                ai.skillroy.testjava.data.DataException, InterruptedException {
            verifierCalls.incrementAndGet();
            try {
                beforeStaffCheck.run(ctx);
            } catch (RuntimeException | ai.skillroy.testjava.controller.AgentRpcException
                     | ai.skillroy.testjava.controller.AgentLostException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            Map<?, ?> a = (Map<?, ?>) ctx.agent().call("toy.staff", Map.of("code", candidate.code())).value();
            Object limit = a.get("limit");
            BigDecimal reported = limit == null ? null : new BigDecimal(String.valueOf(limit));
            boolean same = reported == null ? candidate.limit() == null
                    : candidate.limit() != null && reported.compareTo(candidate.limit()) == 0;
            if (!same) {
                return VerifierResult.fail("the application reports limit " + limit, Map.of());
            }
            if (query.limitBelow() != null && (reported == null || reported.compareTo(query.limitBelow()) >= 0)) {
                return VerifierResult.fail("limit " + limit + " is not below " + query.limitBelow(), Map.of());
            }
            return VerifierResult.pass("limit " + limit, Map.of("limit", String.valueOf(limit)));
        }
    }

    final class LimitCheck implements Verifier<Limit, BigDecimal> {
        @Override
        public String id() {
            return "toy.limit";
        }

        @Override
        public Class<Limit> queryClass() {
            return Limit.class;
        }

        @Override
        public VerifierResult verify(Limit query, BigDecimal candidate, VerifyContext ctx) throws
                ai.skillroy.testjava.controller.AgentRpcException, ai.skillroy.testjava.controller.AgentLostException {
            verifierCalls.incrementAndGet();
            if (limitVerifierAccepts) {
                return VerifierResult.pass("accepted", Map.of());
            }
            Object max = ((Map<?, ?>) ctx.agent().call("toy.limits", Map.of("type", query.type())).value())
                    .get("max");
            return max != null && new BigDecimal(String.valueOf(max)).compareTo(candidate) == 0
                    ? VerifierResult.pass("max " + max, Map.of()) : VerifierResult.fail("max is " + max, Map.of());
        }
    }

    /**
     * The application's own negation, asked through {@code toy.negate}: agrees when the answer is within the tolerance
     * (none: exact) of the oracle's output.
     */
    final class NegateCheck implements CrossCheck<BigDecimal, BigDecimal> {
        @Override
        public String id() {
            return "toy.negate.check";
        }

        @Override
        public String oracleFn() {
            return "Negate";
        }

        @Override
        public List<String> requires() {
            return List.of("toy.negate");
        }

        @Override
        public boolean acceptsTolerance() {
            return true;
        }

        @Override
        public CrossCheckResult check(BigDecimal input, BigDecimal output, BigDecimal tolerance,
                                      VerifyContext ctx) throws ai.skillroy.testjava.data.DataException,
                ai.skillroy.testjava.controller.AgentRpcException, ai.skillroy.testjava.controller.AgentLostException,
                InterruptedException {
            checkCalls.add(new CheckCall(id(), input, output, tolerance));
            try {
                beforeNegateCheck.run(ctx);
            } catch (RuntimeException | ai.skillroy.testjava.data.DataException
                     | ai.skillroy.testjava.controller.AgentRpcException
                     | ai.skillroy.testjava.controller.AgentLostException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (negateCheckReturnsNull) {
                return null;
            }
            Map<String, Object> params = Map.of("amount", input.toPlainString());
            Object answer = negateCallTimeoutMs == null ? ctx.agent().call("toy.negate", params).value()
                    : ctx.agent().call("toy.negate", params, CallOptions.defaults().withTimeoutMs(negateCallTimeoutMs))
                    .value();
            BigDecimal computed = new BigDecimal(String.valueOf(((Map<?, ?>) answer).get("amount")));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("toy.negate", answer);
            if (negateEvidenceNote != null) {
                evidence.put("note", negateEvidenceNote);
            }
            BigDecimal allowed = tolerance == null ? BigDecimal.ZERO : tolerance;
            String reason = "the application negates " + input.toPlainString() + " to " + computed.toPlainString()
                    + ", the oracle to " + output.toPlainString();
            Map<String, Object> actual = Map.of("amount", computed.toPlainString());
            return computed.subtract(output).abs().compareTo(allowed) <= 0
                    ? CrossCheckResult.agree(reason, actual, evidence)
                    : CrossCheckResult.disagree(reason, actual, evidence);
        }
    }

    /** The application's own authorisation decision, asked through {@code toy.needs}; exact, no tolerance. */
    final class NeedsCheck implements CrossCheck<Needs.Input, String> {
        @Override
        public String id() {
            return "toy.needs.check";
        }

        @Override
        public String oracleFn() {
            return "Needs";
        }

        @Override
        public List<String> requires() {
            return List.of("toy.needs");
        }

        @Override
        public CrossCheckResult check(Needs.Input input, String output, BigDecimal tolerance, VerifyContext ctx)
                throws ai.skillroy.testjava.controller.AgentRpcException,
                ai.skillroy.testjava.controller.AgentLostException {
            checkCalls.add(new CheckCall(id(), input, output, tolerance));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("limit", input.limit() == null ? null : input.limit().toPlainString());
            params.put("amount", input.amount().toPlainString());
            Object decision = ((Map<?, ?>) ctx.agent().call("toy.needs", params).value()).get("decision");
            return output.equals(decision) ? CrossCheckResult.agree("the application decides " + decision,
                    decision, Map.of()) : CrossCheckResult.disagree("the application decides " + decision, decision,
                    Map.of());
        }
    }

    /** {@code -amount}, scale kept. */
    static final class Negate implements Oracle<BigDecimal, BigDecimal> {
        @Override
        public String fn() {
            return "Negate";
        }

        @Override
        public BigDecimal compute(BigDecimal input) {
            return input.negate();
        }
    }

    /** Whether a staff member with this limit needs authorisation for this amount. */
    static final class Needs implements Oracle<Needs.Input, String> {
        /** The inputs. */
        record Input(BigDecimal limit, BigDecimal amount) {
        }

        @Override
        public String fn() {
            return "Needs";
        }

        @Override
        public String compute(Input input) {
            return input.limit() == null || input.amount().compareTo(input.limit()) <= 0 ? "NONE" : "PROMPT";
        }
    }

    /** The input itself. */
    static final class Identity implements Oracle<BigDecimal, BigDecimal> {
        @Override
        public String fn() {
            return "Identity";
        }

        @Override
        public BigDecimal compute(BigDecimal input) {
            return input;
        }
    }
}
