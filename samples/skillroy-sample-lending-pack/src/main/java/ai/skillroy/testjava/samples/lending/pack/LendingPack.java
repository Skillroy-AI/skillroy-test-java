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
import ai.skillroy.testjava.data.AgentDataChannel;
import ai.skillroy.testjava.data.DataChannel;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.StoredTable;

import java.time.Duration;
import java.util.List;

/**
 * Domain pack {@value #ID}: the controller half of the sample lending desk (contract section 9.3), next to the agent
 * module of the same id. Scenarios name it with {@code pack: lending}; it is found with
 * {@code ServiceLoader<DomainPack>}.
 *
 * <ul>
 *   <li>Sync policy: ack {@code input.acked}; ready {@code input.requested} and {@code prompt.waiting}; prompts
 *       {@code prompt.waiting}. All three are published by the lending agent module from the desk's own engine
 *       callbacks.</li>
 *   <li>Verbs: {@code signOn}, {@code signOff}, {@code scan}, {@code key}, {@code enter}, {@code quantity},
 *       {@code deposit}, {@code expectPrompt}, {@code answerPrompt}, {@code authorise} (see {@link LendingSteps}).
 *   </li>
 *   <li>Facts: {@code loan}, {@code state}, {@code slip}, {@code prompt} (see {@link LendingFacts}).</li>
 *   <li>Resolution (phase2-contracts.md 13.3, phase3-contracts.md 10.3): the query kinds {@code SystemLimit},
 *       {@code LendableItem}, {@code StaffWithPermission}, {@code ActionKey}, {@code ActiveWaiverRuleWithTriggers}
 *       and {@code ItemsTriggering} ({@link LendingQueries}), their resolvers ({@link LendingResolvers}) and
 *       verifiers ({@link LendingVerifiers}), the oracles {@link ExpectedAuthorisation}, {@link MoneyNegate},
 *       {@link LoanFromTrigger}, {@link ExpectedWaiver} and {@link ExpectedTotals}, the cross-check
 *       {@code lending.waiver.evaluate} of {@code ExpectedWaiver} ({@link LendingCrossChecks}), the fingerprinter
 *       ({@link LendingFingerprinter}), the stored tables and the password row mask ({@link LendingTables}).</li>
 *   <li>Pre-flight: {@value #PREFLIGHT_DATABASE}, the lending database answers a read on source {@code lending},
 *       through whichever channel serves it (the agent's {@code data.query}, or JDBC when the environment's
 *       credentials name the source).</li>
 *   <li>Recovery: answer any open prompt with its first button, cancel any open loan, sign off
 *       ({@link LendingRecovery}).</li>
 * </ul>
 *
 * <p>Stateless and thread-safe; one instance may serve every scenario in the JVM.
 */
public final class LendingPack implements DomainPack {

    /** Pack id, the {@code pack:} of lending scenarios. */
    public static final String ID = "lending";
    /**
     * Written into every plan; change it whenever a verb compiles differently. {@code 0.3.0}: the waiver queries,
     * oracles and cross-check of phase3-contracts.md 10.3.
     */
    public static final String VERSION = "0.3.0";
    /** Published once per input the desk accepted from {@code lending.input}. */
    public static final String ACK_TOPIC = "input.acked";
    /** Published when the desk waits for input again, or asks a question. */
    public static final List<String> READY_TOPICS = List.of("input.requested", "prompt.waiting");
    /** The desk asks a question. */
    public static final List<String> PROMPT_TOPICS = List.of("prompt.waiting");
    /** Id of the pre-flight check. */
    public static final String PREFLIGHT_DATABASE = "lending.database";
    /** The desk's keypad actions: what {@code key: {action}} accepts and what {@code ActionKey} may answer. */
    public static final List<String> KEY_ACTIONS = List.of("DUE", "DEPOSIT", "RETURN", "CANCEL", "SIGN_OFF", "QTY");

    private static final SyncPolicy SYNC = new SyncPolicy(ACK_TOPIC, READY_TOPICS, PROMPT_TOPICS, null, null);
    private static final List<FactProvider> FACTS = LendingFacts.all();
    private static final List<PreflightCheck> PREFLIGHT = List.of(new DatabaseCheck());
    private static final ResolutionCatalogue CATALOGUE = catalogue();
    private static final EnvFingerprinter FINGERPRINTER = new LendingFingerprinter();
    private static final List<RowMask> ROW_MASKS = List.of(LendingTables.PASSWORD_MASK);

    /** Public no-argument constructor for {@code ServiceLoader}. */
    public LendingPack() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public StepLibrary steps() {
        return LendingSteps.INSTANCE;
    }

    @Override
    public SyncPolicy syncPolicy() {
        return SYNC;
    }

    @Override
    public List<FactProvider> facts() {
        return FACTS;
    }

    @Override
    public List<PreflightCheck> preflight() {
        return PREFLIGHT;
    }

    @Override
    public void recover(AgentConnection agent) throws Exception {
        LendingRecovery.recover(agent);
    }

    /** The same catalogue on every call: built once, immutable. */
    @Override
    public ResolutionCatalogue resolution() {
        return CATALOGUE;
    }

    @Override
    public EnvFingerprinter fingerprinter() {
        return FINGERPRINTER;
    }

    @Override
    public List<StoredTable> storedTables() {
        return LendingTables.ALL;
    }

    @Override
    public List<RowMask> rowMasks() {
        return ROW_MASKS;
    }

    /**
     * The pack's query kinds, resolvers (in the order they are tried), verifiers, oracles and oracle cross-checks.
     * Built once; a variant pack starts from it with {@code ResolutionCatalogue.builder(base)} and replaces by id.
     */
    static ResolutionCatalogue catalogue() {
        return ResolutionCatalogue.builder()
                .query(QueryType.of("SystemLimit", LendingQueries.SystemLimit.class))
                .query(QueryType.of("LendableItem", LendingQueries.LendableItem.class))
                .query(QueryType.of("StaffWithPermission", LendingQueries.StaffWithPermission.class))
                .query(QueryType.of("ActionKey", LendingQueries.ActionKey.class))
                .query(QueryType.of("ActiveWaiverRuleWithTriggers", LendingQueries.ActiveWaiverRuleWithTriggers.class))
                .query(QueryType.of("ItemsTriggering", LendingQueries.ItemsTriggering.class))
                .resolver(new LendingResolvers.SystemLimitResolver())
                .resolver(new LendingResolvers.LendableItemResolver())
                .resolver(new LendingResolvers.StaffResolver())
                .resolver(new LendingResolvers.ActionKeyResolver())
                .resolver(new LendingResolvers.WaiverRuleResolver())
                .resolver(new LendingResolvers.ItemsTriggeringResolver())
                .verifier(new LendingVerifiers.LimitsVerifier())
                .verifier(new LendingVerifiers.DepositVerifier())
                .verifier(new LendingVerifiers.AccessVerifier())
                .verifier(new LendingVerifiers.KeyVerifier())
                .verifier(new LendingVerifiers.WaiverVerifier())
                .verifier(new LendingVerifiers.TriggeringItemVerifier())
                .oracle(new ExpectedAuthorisation())
                .oracle(new MoneyNegate())
                .oracle(new LoanFromTrigger())
                .oracle(new ExpectedWaiver())
                .oracle(new ExpectedTotals())
                .crossCheck(new LendingCrossChecks.WaiverEvaluate())
                .build();
    }

    /**
     * The lending database answers a read: at least one staff member. It reads through the pre-flight's data channel
     * (phase2-contracts.md 6.1), so it also passes where the environment serves source {@code lending} over JDBC.
     */
    static final class DatabaseCheck implements PreflightCheck {

        static final String SQL = "SELECT COUNT(*) AS staff FROM staff";
        static final Duration TIMEOUT = Duration.ofSeconds(10);

        @Override
        public String id() {
            return PREFLIGHT_DATABASE;
        }

        /** The Phase 1 entry: reads through the agent's {@code data.query}. */
        @Override
        public String check(AgentConnection agent) throws Exception {
            return check(new AgentDataChannel(agent));
        }

        @Override
        public String check(PreflightContext ctx) throws Exception {
            return check(ctx.data());
        }

        static String check(DataChannel data) throws Exception {
            Rows rows;
            try {
                rows = data.query(LendingResolvers.SOURCE, SQL, List.of(), 1, TIMEOUT);
            } catch (DataException e) {
                return "the lending database could not be read (" + e.kind()
                        + (e.rpcCode() == null ? "" : ", " + e.rpcCode()) + "): " + e.getMessage();
            }
            if (rows.rowCount() > 0 && rows.get(0, 0) instanceof Number n && n.longValue() > 0) {
                return null;
            }
            return "the lending database has no staff: " + rows.rowCount() + " row(s)";
        }
    }
}
