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
import ai.skillroy.testjava.samples.lending.pack.LendingResolvers;
import ai.skillroy.testjava.scenario.DomainPacks;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.hooks.HookEntry;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.Resolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay {@code lending-branch} (phase6-contracts.md 11.3, 13.1 item 6): found beside the base pack, it keeps
 * the base's id and changes a constant, a resolver, the allow-list and the hook catalogue, and nothing else.
 */
class LendingBranchOverlayTest {

    private final DomainPack base = new LendingPack();
    private final DomainPack branch = new LendingBranchOverlay().overlay(base);

    @Test
    void discoveryAppliesTheOverlayToTheBasePack() {
        List<DomainPack> packs = DomainPacks.discover(getClass().getClassLoader());
        assertEquals(1, packs.size(), String.valueOf(packs));
        DomainPack pack = packs.get(0);
        assertEquals("lending", pack.id(), "scenarios keep pack: lending");
        assertEquals("0.6.0+lending-branch@0.1.0", pack.version());
        assertTrue(pack instanceof BranchPack, String.valueOf(pack));
    }

    @Test
    void theConstantTheResolverTheAllowListAndTheCatalogueAreTheBranchs() {
        assertEquals(Map.of(LendingPack.MAX_RETURN_SETTING, "return_ceiling"), branch.settings().asMap());
        List<Resolver<?, ?>> staff = branch.resolution().resolvers("StaffWithPermission");
        assertEquals(1, staff.size());
        assertTrue(staff.get(0) instanceof BranchStaffResolver, String.valueOf(staff));
        assertEquals(LendingResolvers.STAFF_ID, staff.get(0).id());
        assertTrue(BranchStaffResolver.SQL.contains("rl.ceiling") && !BranchStaffResolver.SQL.contains("rl.amount"),
                BranchStaffResolver.SQL);
        assertSame(base.resolution().resolvers("SystemLimit").get(0).getClass(),
                branch.resolution().resolvers("SystemLimit").get(0).getClass(), "the SystemLimit resolver is the base's");
        assertEquals(base.resolution().kinds(), branch.resolution().kinds());
        assertEquals(List.of(BranchAllowList.LIST), branch.sqlAllowLists());
        assertNotNull(BranchAllowList.LIST.table("role_cap").column("ceiling"));
        assertNull(BranchAllowList.LIST.table("role_cap").column("amount"));

        HookCatalogue hooks = branch.hooks();
        assertEquals("lending-branch", hooks.id());
        assertEquals(1, hooks.version());
        HookEntry limit = hooks.entry("limit.applied");
        assertEquals("ai.skillroy.testjava.samples.lending.ReturnCeilings", limit.target().className());
        assertEquals("applicable", limit.target().method());
        assertEquals(base.hooks().entry("limit.applied").capture(), limit.capture());
        assertEquals(base.hooks().hooks().stream().map(HookEntry::id).toList(),
                hooks.hooks().stream().map(HookEntry::id).toList(), "every base entry, limit.applied replaced");
        assertEquals(base.hooks().entry("access.checked"), hooks.entry("access.checked"));
        // catalogue version 2 (phase8-contracts.md 6.8, 12.3): the replaced entry inherits the qualifier from the
        // merged package rules, and the overlay's deny is the base's
        assertEquals(base.hooks().packageRules().get(0).codeSource(), limit.target().codeSource(),
                "the replaced entry inherits the qualifier");
        assertEquals(base.hooks().deny(), hooks.deny());
        assertEquals(base.hooks().packageRules(), hooks.packageRules());
        assertEquals(base.hooks().enabled().stream().map(HookEntry::id).toList(),
                hooks.enabled().stream().map(HookEntry::id).toList());
        assertNotEquals(base.hooks().hash(), hooks.hash());
        assertSame(hooks, branch.hooks(), "built once");
    }

    @Test
    void everythingElseIsTheBasePacks() {
        assertSame(base.steps(), branch.steps());
        assertSame(base.syncPolicy(), branch.syncPolicy());
        assertSame(base.facts(), branch.facts());
        assertSame(base.preflight(), branch.preflight());
        assertSame(base.fingerprinter(), branch.fingerprinter(), "the fingerprint is unchanged (D146)");
        assertSame(base.storedTables(), branch.storedTables());
        assertSame(base.rowMasks(), branch.rowMasks());
        assertEquals(base.promptPolicy(), branch.promptPolicy());
        assertSame(base.capabilities(), branch.capabilities(), "the pack's capabilities are the base's");
    }

    /**
     * phase8-contracts.md 12.3, 13.1 row 50: the base's {@code lending.returns.ceiling}, evaluated with the branch
     * pack's settings, reads the branch's row {@code return_ceiling} (an overlay changes what an evaluation reads
     * without replacing it).
     */
    @Test
    void theReturnsCeilingReadsTheBranchsRow() throws Exception {
        List<List<Object>> asked = new java.util.ArrayList<>();
        ai.skillroy.testjava.data.DataChannel data = new ai.skillroy.testjava.data.DataChannel() {
            @Override
            public boolean serves(String source) {
                return true;
            }

            @Override
            public ai.skillroy.testjava.protocol.sql.Rows query(String source, String sql, List<Object> params,
                                                               int maxRows, java.time.Duration timeout) {
                asked.add(params);
                return ai.skillroy.testjava.protocol.sql.Rows.of(List.of(new ai.skillroy.testjava.protocol.sql.Rows
                        .Column("VALUE", "VARCHAR")), List.of(List.of("75.00")), false);
            }

            @Override
            public String kind(String source) {
                return "agent";
            }
        };
        ai.skillroy.testjava.scenario.pack.PreflightCheck.PreflightContext ctx =
                new ai.skillroy.testjava.scenario.pack.PreflightCheck.PreflightContext() {
                    @Override
                    public ai.skillroy.testjava.controller.AgentConnection agent() {
                        return null;
                    }

                    @Override
                    public ai.skillroy.testjava.data.DataChannel data() {
                        return data;
                    }

                    @Override
                    public String envId() {
                        return "branch-a";
                    }

                    @Override
                    public ai.skillroy.testjava.scenario.pack.PackSettings settings() {
                        return branch.settings();
                    }
                };
        ai.skillroy.testjava.protocol.Capability answer = branch.capabilities().get(0).evaluate(ctx);
        assertEquals(LendingPack.CAP_RETURNS_CEILING, answer.id());
        assertEquals(ai.skillroy.testjava.protocol.CapabilityState.OK, answer.state());
        assertEquals(List.of(List.of("return_ceiling")), asked);
    }
}
