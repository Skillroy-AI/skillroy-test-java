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

package ai.skillroy.testjava.samples.lending.pack.branch;

import ai.skillroy.testjava.samples.lending.pack.LendingPack;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.hooks.HookCatalogues;
import ai.skillroy.testjava.scenario.pack.DelegatingDomainPack;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.PackSettings;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;

import java.util.List;
import java.util.Map;

/**
 * Pack {@code lending} as the branch edition needs it (phase6-contracts.md 11.3): every member of the base pack but
 * four, each override one of the kinds the roadmap names.
 *
 * <ul>
 *   <li>a <b>constant</b>: {@link #settings()} sets {@code lending.maxReturnSetting} to {@code return_ceiling}, which
 *       the base's {@code SystemLimit} resolver reads through its context;</li>
 *   <li>a <b>resolver</b>: {@link #resolution()} replaces the base's {@code StaffWithPermission} resolver (same id)
 *       with {@link BranchStaffResolver}, whose statement names {@code role_cap.ceiling};</li>
 *   <li>the <b>allow-list</b>: {@link #sqlAllowLists()} is {@link BranchAllowList}, {@code role_cap.ceiling} in place of
 *       {@code amount};</li>
 *   <li>the <b>hook catalogue</b>: {@link #hooks()} is the base's with {@code limit.applied} moved to
 *       {@code ReturnCeilings.applicable}, read from {@code META-INF/skillroy/hooks/lending-branch.yaml}.</li>
 * </ul>
 *
 * <p>Immutable and thread-safe: the four are built once, on first use.
 */
public final class BranchPack extends DelegatingDomainPack {

    /** The {@code desk_setting} name of the branch edition's system maximum return. */
    public static final String MAX_RETURN_SETTING = "return_ceiling";

    private final PackSettings settings;
    private volatile ResolutionCatalogue resolution;
    private volatile HookCatalogue hooks;

    BranchPack(DomainPack base) {
        super(base, LendingBranchOverlay.ID, LendingBranchOverlay.VERSION);
        this.settings = base.settings().with(Map.of(LendingPack.MAX_RETURN_SETTING, MAX_RETURN_SETTING));
    }

    @Override
    public PackSettings settings() {
        return settings;
    }

    @Override
    public ResolutionCatalogue resolution() {
        ResolutionCatalogue built = resolution;
        if (built == null) {
            built = ResolutionCatalogue.builder(base().resolution()).replace(new BranchStaffResolver()).build();
            resolution = built;
        }
        return built;
    }

    @Override
    public List<SqlAllowList> sqlAllowLists() {
        return List.of(BranchAllowList.LIST);
    }

    /**
     * The base catalogue overlaid by the resource {@code META-INF/skillroy/hooks/lending-branch.yaml}.
     *
     * @throws IllegalStateException when the resource is malformed (a compile reports it)
     */
    @Override
    public HookCatalogue hooks() {
        HookCatalogue built = hooks;
        if (built == null) {
            built = HookCatalogues.overlayResource(base().hooks(), BranchPack.class, LendingBranchOverlay.ID);
            hooks = built;
        }
        return built;
    }
}
