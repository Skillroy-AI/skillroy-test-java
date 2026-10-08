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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.JdbcDataChannel;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.samples.lending.LendingDb;
import ai.skillroy.testjava.samples.lending.Seed;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ItemRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LendableItem;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LimitCondition;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffWithPermission;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.JdbcSource;
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.CostLedger;
import ai.skillroy.testjava.scenario.resolve.CredentialIndex;
import ai.skillroy.testjava.scenario.resolve.CredentialSpec;
import ai.skillroy.testjava.scenario.resolve.NumberRange;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.ValueLoader;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lending pack's tier (b) value loaders on the real schema, both seeds (phase5-contracts.md 5.4a, 14.1, decision
 * D90): for every key the tier (a) resolver answers, under scenario A's queries and broader ones, the loader answers
 * the same value, through the same statements; a key the committed reads do not answer (an unknown code, a former
 * staff member, a retired item, an item lent the other way or outside the range) is a {@link ResolutionException}
 * with the loader's reason; and the loaded values pass the pack's verifiers against the desk's real access rules and
 * catalogue ({@link DeskConnection}). The loaders and resolvers are the pack's own, taken from its catalogue.
 */
class ValueLoadersOnRealSchemaTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String READER_PASSWORD = "Loader-Schema-Reader-7302";

    @TempDir
    Path tmp;

    private LendingDb db;
    private JdbcDataChannel channel;

    @AfterEach
    void close() {
        if (channel != null) {
            channel.close();
        }
        if (db != null) {
            db.close();
        }
    }

    /** A resolve and verify context over the JDBC channel and the desk; records the statements it ran. */
    private static final class Ctx implements ResolveContext, VerifyContext {
        final DataChannel channel;
        final AgentConnection desk;
        final String envId;
        final List<String> statements = new ArrayList<>();

        Ctx(DataChannel channel, AgentConnection desk, String envId) {
            this.channel = channel;
            this.desk = desk;
            this.envId = envId;
        }

        @Override
        public AgentConnection agent() {
            return desk;
        }

        @Override
        public DataChannel data() {
            return new DataChannel() {
                @Override
                public boolean serves(String source) {
                    return "lending".equals(source);
                }

                @Override
                public Rows query(String source, String sql, List<Object> params, int maxRows, Duration timeout)
                        throws ai.skillroy.testjava.data.DataException, InterruptedException,
                        ai.skillroy.testjava.controller.AgentLostException {
                    statements.add(sql + " " + params);
                    return channel.query(source, sql, params, maxRows, timeout);
                }

                @Override
                public String kind(String source) {
                    return "jdbc";
                }
            };
        }

        @Override
        public CredentialSpec credential() {
            return null;
        }

        @Override
        public CredentialIndex credentials() {
            return (realm, id) -> true;
        }

        @Override
        public String envId() {
            return envId;
        }

        @Override
        public BindMode mode() {
            return BindMode.LIVE;
        }

        @Override
        public int maxCandidates() {
            return 20;
        }

        @Override
        public long remainingMs() {
            return 60_000L;
        }

        @Override
        public CostLedger ledger() {
            return new CostLedger();
        }

        @Override
        public void note(String text) {
        }

        @Override
        public Rows requireComplete(Rows rows) throws ResolutionException {
            if (rows.truncated()) {
                throw new ResolutionException("TRUNCATED", null, "truncated");
            }
            return rows;
        }
    }

    private Ctx open(Env env) throws Exception {
        LocalDate today = LocalDate.now();
        db = LendingDb.create("p5loaders" + env.seed + DATABASES.incrementAndGet(), Seed.parse(env.seed), today);
        db.createReadOnlyUser(READER_PASSWORD);
        Credentials credentials = SampleCredentials.load(tmp.resolve("credentials-" + env.id), env,
                new JdbcSource(db.url(), LendingDb.READER_USER, READER_PASSWORD));
        channel = new JdbcDataChannel(credentials, Runs.PACK.rowMasks());
        return new Ctx(channel, new DeskConnection(db, today), env.id);
    }

    @SuppressWarnings("unchecked")
    private static <Q extends Query<T>, T> ValueLoader<Q, T> loader(String kind) {
        ValueLoader<?, ?> loader = Runs.PACK.resolution().valueLoader(kind);
        assertEquals(List.of("code"), loader.identityColumns(), kind);
        return (ValueLoader<Q, T>) loader;
    }

    @SuppressWarnings("unchecked")
    private static <Q extends Query<T>, T> Resolver<Q, T> resolver(String kind) {
        List<Resolver<?, ?>> resolvers = Runs.PACK.resolution().resolvers(kind);
        assertEquals(1, resolvers.size(), kind);
        return (Resolver<Q, T>) resolvers.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <Q extends Query<T>, T> Verifier<Q, T> verifier(String kind) {
        return (Verifier<Q, T>) Runs.PACK.resolution().verifiers(kind).get(0);
    }

    private static Map<String, Object> code(String code) {
        return Map.of("code", code);
    }

    private static BigDecimal deposit(Env env) {
        return new BigDecimal(String.valueOf(ScenarioA.expected(env).item().get("deposit")));
    }

    /** The queries the staff loader is checked under: scenario A's two and one any signed-on staff member answers. */
    private static List<StaffWithPermission> staffQueries(Env env) {
        String assistant = ScenarioA.expected(env).assistantCode();
        return List.of(
                new StaffWithPermission(List.of("SIGN_ON", "RETURN"), new LimitCondition("RETURN", deposit(env), null,
                        null, null), true, null),
                new StaffWithPermission(List.of("RETURN_OVERRIDE"), new LimitCondition("RETURN", null, null, null,
                        deposit(env)), null, List.of(assistant)),
                new StaffWithPermission(List.of("SIGN_ON"), null, null, null));
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void theStaffLoaderAnswersWhatTheStaffResolverAnswersForEveryKey(Env env) throws Exception {
        Ctx ctx = open(env);
        ValueLoader<StaffWithPermission, StaffRef> loader = loader("StaffWithPermission");
        Resolver<StaffWithPermission, StaffRef> resolver = resolver("StaffWithPermission");
        int compared = 0;
        for (StaffWithPermission query : staffQueries(env)) {
            List<ResolverCandidate<StaffRef>> candidates = resolver.candidates(query, ctx);
            assertFalse(candidates.isEmpty(), String.valueOf(query));
            for (ResolverCandidate<StaffRef> candidate : candidates) {
                ctx.statements.clear();
                assertEquals(candidate.value(), loader.load(query, code(candidate.value().code()), ctx),
                        env.id + " " + candidate.label());
                assertEquals(2, ctx.statements.size(), "the staff statement, then the permissions: "
                        + ctx.statements);
                assertTrue(ctx.statements.get(1).endsWith("[" + candidate.value().code() + "]"),
                        String.valueOf(ctx.statements));
                compared++;
            }
        }
        assertTrue(compared >= 4, "at least the assistant, the supervisors and the signed-on staff: " + compared);
        StaffWithPermission supervisor = staffQueries(env).get(1);
        assertEquals(ScenarioA.expected(env).supervisorCode(), resolver.candidates(supervisor, ctx).get(0).value()
                .code(), "the committed SQL's first supervisor, scenario A's");
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void anUnknownOrFormerStaffMemberIsAResolutionExceptionWithAReason(Env env) throws Exception {
        Ctx ctx = open(env);
        ValueLoader<StaffWithPermission, StaffRef> loader = loader("StaffWithPermission");
        String former = env == Env.SEED_A ? "102" : "4713";
        for (String key : List.of("999", former)) {
            ResolutionException e = assertThrows(ResolutionException.class, () -> loader.load(staffQueries(env)
                    .get(2), code(key), ctx), key);
            assertEquals("UNKNOWN_STAFF", e.reason(), key);
            assertEquals("no active staff member has the code " + key, e.getMessage());
        }
    }

    /** The queries the item loader is checked under: scenario A's, every counted item, every per-day item. */
    private static List<LendableItem> itemQueries(Env env) {
        BigDecimal max = new BigDecimal(ScenarioA.expected(env).systemMax());
        return List.of(
                new LendableItem(new NumberRange(null, null, null, max, List.of(new BigDecimal("20.00"),
                        new BigDecimal("200.00"))), null),
                new LendableItem(null, false),
                new LendableItem(null, true));
    }

    @ParameterizedTest
    @EnumSource(Env.class)
    void theItemLoaderAnswersWhatTheItemResolverAnswersForEveryKeyAndRefusesTheRest(Env env) throws Exception {
        Ctx ctx = open(env);
        ValueLoader<LendableItem, ItemRef> loader = loader("LendableItem");
        Resolver<LendableItem, ItemRef> resolver = resolver("LendableItem");
        int compared = 0;
        for (LendableItem query : itemQueries(env)) {
            for (ResolverCandidate<ItemRef> candidate : resolver.candidates(query, ctx)) {
                assertEquals(candidate.value(), loader.load(query, code(candidate.value().code()), ctx),
                        env.id + " " + candidate.label());
                compared++;
            }
        }
        assertTrue(compared >= 10, "every lendable item of the seed: " + compared);
        assertEquals(ScenarioA.expected(env).itemCode(), resolver.candidates(itemQueries(env).get(0), ctx).get(0)
                .value().code());
        String retired = env == Env.SEED_A ? "TIL-001" : "T1012";
        String perDay = env == Env.SEED_A ? "CLN-001" : "T1007";
        String outside = env == Env.SEED_A ? "HND-001" : "T1001";
        for (String key : List.of("NOPE-1", retired, perDay, outside)) {
            ResolutionException e = assertThrows(ResolutionException.class, () -> loader.load(itemQueries(env)
                    .get(0), code(key), ctx), key);
            assertEquals("NOT_LENDABLE", e.reason(), key);
        }
    }

    /** What a loader builds is what the verifiers pass: the supervisor and the item of scenario A on this seed. */
    @ParameterizedTest
    @EnumSource(Env.class)
    void theLoadedValuesPassThePacksVerifiers(Env env) throws Exception {
        Ctx ctx = open(env);
        ValueLoader<StaffWithPermission, StaffRef> staffLoader = loader("StaffWithPermission");
        Verifier<StaffWithPermission, StaffRef> access = verifier("StaffWithPermission");
        ValueLoader<LendableItem, ItemRef> itemLoader = loader("LendableItem");
        Verifier<LendableItem, ItemRef> scan = verifier("LendableItem");
        StaffWithPermission supervisorQuery = staffQueries(env).get(1);
        StaffRef supervisor = staffLoader.load(supervisorQuery, code(ScenarioA.expected(env).supervisorCode()), ctx);
        VerifierResult staff = access.verify(supervisorQuery, supervisor, ctx);
        assertTrue(staff.passed(), staff.reason());
        LendableItem itemQuery = itemQueries(env).get(0);
        ItemRef item = itemLoader.load(itemQuery, code(ScenarioA.expected(env).itemCode()), ctx);
        VerifierResult deposit = scan.verify(itemQuery, item, ctx);
        assertTrue(deposit.passed(), deposit.reason());
        StaffRef assistant = staffLoader.load(supervisorQuery, code(ScenarioA.expected(env).assistantCode()), ctx);
        assertFalse(access.verify(supervisorQuery, assistant, ctx).passed(), "a staff member the query does not take"
                + " is loaded, and the verifier refuses it");
    }
}
