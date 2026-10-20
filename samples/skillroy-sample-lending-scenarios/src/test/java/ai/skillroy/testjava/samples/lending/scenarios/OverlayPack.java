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
import ai.skillroy.testjava.data.RowMask;
import ai.skillroy.testjava.samples.lending.pack.ExpectedWaiver;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PreflightCheck;
import ai.skillroy.testjava.scenario.pack.PromptPolicy;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.CrossCheck;
import ai.skillroy.testjava.scenario.resolve.CrossCheckResult;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprinter;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.StoredTable;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The lending pack with its resolution catalogue overlaid the way a variant pack does it
 * ({@code ResolutionCatalogue.builder(base).replace(...)}, decision D24): every other method is forwarded to the
 * real pack, so the id, the version, the verbs and the fingerprint stay the pack's own. {@code LendingPack} is final,
 * so this wraps it. The catalogue is built once.
 *
 * <p>Used for the wrong-oracle demonstration of phase3-contracts.md 10.4 (decision D46): nothing wrong is ever
 * committed under {@code scenarios/}; the wrong oracle exists only in the test that overlays it. Phase 5: the SQL
 * allow-lists are forwarded too, or replaced ({@link #changedCatalogue()}: the prompt catalogue a model sees changes,
 * phase5-contracts.md 15.2 item 2).
 */
final class OverlayPack implements DomainPack {

    private final DomainPack pack;
    private final ResolutionCatalogue catalogue;
    private final List<SqlAllowList> allowLists;

    OverlayPack(DomainPack pack, UnaryOperator<ResolutionCatalogue.Builder> overlay) {
        this(pack, overlay, pack.sqlAllowLists());
    }

    private OverlayPack(DomainPack pack, UnaryOperator<ResolutionCatalogue.Builder> overlay,
                        List<SqlAllowList> allowLists) {
        this.pack = pack;
        this.catalogue = overlay.apply(ResolutionCatalogue.builder(pack.resolution())).build();
        this.allowLists = List.copyOf(allowLists);
    }

    /**
     * The lending pack with one word more in the description of the allow-listed table {@code staff}: the prompt
     * catalogue a model is shown changes, so the request a tier (b) slot sends has another fingerprint and no
     * recording answers it ("no recording", phase5-contracts.md 4.2, 15.2 item 2). Everything else is the pack's.
     */
    static OverlayPack changedCatalogue() {
        List<SqlAllowList> changed = new ArrayList<>();
        for (SqlAllowList list : Runs.PACK.sqlAllowLists()) {
            List<SqlAllowList.AllowedTable> tables = new ArrayList<>();
            for (SqlAllowList.AllowedTable table : list.tables()) {
                tables.add(!table.name().equals("staff") ? table : new SqlAllowList.AllowedTable(table.name(),
                        table.columns(), table.key(), table.description() + " Changed.", table.otherColumns()));
            }
            changed.add(new SqlAllowList(list.source(), list.dialect(), tables, list.procedures(),
                    list.functions()));
        }
        return new OverlayPack(Runs.PACK, builder -> builder, changed);
    }

    /** The lending pack with {@code ExpectedWaiver} replaced by {@link WrongExpectedWaiver}; its cross-check stays. */
    static OverlayPack wrongOracle() {
        return new OverlayPack(Runs.PACK, builder -> builder.replace(new WrongExpectedWaiver()));
    }

    /**
     * The wrong oracle with a cross-check of the same id that agrees without asking anything: what an approval would
     * have written had the oracle been wrong and nothing checked it. Only for writing such a bindings file.
     */
    static OverlayPack wrongOracleUnchecked() {
        return new OverlayPack(Runs.PACK, builder -> builder.replace(new WrongExpectedWaiver())
                .replace(new AgreesWithoutAsking()));
    }

    /** A {@code lending.waiver.evaluate} cross-check that agrees with any value and calls nothing. */
    static final class AgreesWithoutAsking implements CrossCheck<ExpectedWaiver.Input, ExpectedWaiver.Waiver> {

        @Override
        public String id() {
            return "lending.waiver.evaluate";
        }

        @Override
        public String oracleFn() {
            return ExpectedWaiver.FN;
        }

        @Override
        public boolean acceptsTolerance() {
            return true;
        }

        @Override
        public CrossCheckResult check(ExpectedWaiver.Input input, ExpectedWaiver.Waiver output, BigDecimal tolerance,
                                      VerifyContext ctx) {
            return CrossCheckResult.agree("not asked", output.amount().toPlainString(), Map.of());
        }
    }

    @Override
    public String id() {
        return pack.id();
    }

    @Override
    public String version() {
        return pack.version();
    }

    @Override
    public StepLibrary steps() {
        return pack.steps();
    }

    @Override
    public SyncPolicy syncPolicy() {
        return pack.syncPolicy();
    }

    @Override
    public List<FactProvider> facts() {
        return pack.facts();
    }

    @Override
    public PromptPolicy promptPolicy() {
        return pack.promptPolicy();
    }

    @Override
    public List<PreflightCheck> preflight() {
        return pack.preflight();
    }

    @Override
    public void recover(AgentConnection agent) throws Exception {
        pack.recover(agent);
    }

    @Override
    public ResolutionCatalogue resolution() {
        return catalogue;
    }

    @Override
    public EnvFingerprinter fingerprinter() {
        return pack.fingerprinter();
    }

    @Override
    public List<StoredTable> storedTables() {
        return pack.storedTables();
    }

    @Override
    public List<RowMask> rowMasks() {
        return pack.rowMasks();
    }

    @Override
    public List<SqlAllowList> sqlAllowLists() {
        return allowLists;
    }

    /** The pack's capabilities, forwarded (phase8-contracts.md 9.1). */
    @Override
    public List<ai.skillroy.testjava.scenario.pack.PackCapability> capabilities() {
        return pack.capabilities();
    }
}
