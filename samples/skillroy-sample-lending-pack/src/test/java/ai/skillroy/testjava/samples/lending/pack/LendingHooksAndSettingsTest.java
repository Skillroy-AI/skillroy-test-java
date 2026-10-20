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

import ai.skillroy.testjava.protocol.hook.HookCapture;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LimitCondition;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffWithPermission;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.SystemLimit;
import ai.skillroy.testjava.scenario.hooks.HookCatalogue;
import ai.skillroy.testjava.scenario.hooks.HookEntry;
import ai.skillroy.testjava.scenario.pack.PackSettings;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.pack.LendingResolutionTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pack 0.5.0 (phase6-contracts.md 6.3, 11.3, 13.1 item 6): the hook catalogue resource read and valid, the pack's
 * settings and the public surface an overlay builds on (the settings-driven {@code SystemLimit} resolver, the staff
 * resolver with another statement, the allow-list of another limit column). Catalogue version 2
 * (phase8-contracts.md 6.8, 12.3, section 13.1 row 28): the qualified package rule every enabled entry inherits, and
 * the deny of the desk's input queue.
 */
class LendingHooksAndSettingsTest {

    private final LendingPack pack = new LendingPack();

    @Test
    void theCatalogueResourceIsReadAndValid() {
        HookCatalogue catalogue = pack.hooks();
        assertSame(catalogue, pack.hooks(), "read once");
        assertEquals("lending", catalogue.id());
        assertEquals(2, catalogue.version());
        assertEquals(List.of("ai.skillroy.testjava.samples.lending"), catalogue.packages(), "still the one name");
        String qualifier = "skillroy-sample-lending-app(-[^/]*)?\\.jar";
        assertEquals(List.of(new HookCatalogue.PackageRule("ai.skillroy.testjava.samples.lending", qualifier)),
                catalogue.packageRules());
        assertEquals(List.of("ai.skillroy.testjava.samples.lending.Inbox"), catalogue.deny());
        assertEquals("ai.skillroy.testjava.samples.lending.Inbox",
                catalogue.denied("ai.skillroy.testjava.samples.lending.Inbox"));
        assertEquals(List.of("ai.skillroy.testjava.samples.lending.Inbox"), catalogue.descriptor().get("deny"));
        for (HookSpec spec : catalogue.toSpecs()) {
            assertEquals(qualifier, spec.target().codeSource(), "every enabled entry inherits it: " + spec.id());
        }
        java.util.regex.Pattern anchored = java.util.regex.Pattern.compile(qualifier);
        assertTrue(anchored.matcher("skillroy-sample-lending-app.jar").matches(), "the IT copy");
        assertTrue(anchored.matcher("skillroy-sample-lending-app-0.2.0-SNAPSHOT.jar").matches(), "a versioned jar");
        assertFalse(anchored.matcher("classes").matches(), "a class directory");
        assertFalse(anchored.matcher("skillroy-sample-lending-agent.jar").matches(), "the agent module's jar");
        assertEquals(List.of("limit.applied", "access.checked", "waiver.evaluated", "sign.on.checked", "line.amount"),
                catalogue.hooks().stream().map(HookEntry::id).toList());
        assertEquals(List.of("limit.applied", "access.checked", "waiver.evaluated", "line.amount"),
                catalogue.enabled().stream().map(HookEntry::id).toList(), "sign.on.checked is catalogued and off");
        HookEntry limit = catalogue.entry("limit.applied");
        assertEquals(HookMode.CAPTURE, limit.mode());
        assertEquals("ai.skillroy.testjava.samples.lending.AccessControl", limit.target().className());
        assertEquals("returnLimit", limit.target().method());
        assertEquals(List.of("(Ljava/lang/String;)Ljava/math/BigDecimal;"), limit.target().descriptors());
        assertEquals(new HookCapture(List.of(0), false, true), limit.capture());
        assertEquals(List.of(new HookEntry.Fallback.Seam("lending.limits")), limit.fallbacks());
        HookEntry signOn = catalogue.entry("sign.on.checked");
        assertEquals(List.of(1), signOn.secretArgs(), "the password argument is never captured");
        assertEquals(HookMode.STUB, catalogue.entry("line.amount").mode());
        assertEquals("ai.skillroy.testjava.samples.lending.Deposits", catalogue.entry("line.amount").target()
                .className());
        assertEquals(HookMode.OBSERVE, catalogue.entry("access.checked").mode());
        for (HookSpec spec : catalogue.toSpecs()) {
            assertEquals(null, spec.violation(), spec.id());
            assertEquals(spec, HookSpec.fromJson(spec.toJson()), "round trip of " + spec.id());
        }
        assertTrue(catalogue.hash().matches("sha256:[0-9a-f]{64}"), catalogue.hash());
        // version 2's hash, pinned (phase8-contracts.md 2.4): the qualifier baked into every entry and the deny both
        // enter it; version 1's was sha256:62651ba44da7b6dadd5647c6a56d522514d7e5527a087e1cd76b03c9ef4569c5
        assertEquals("sha256:b4395bf3297d97083015babd26d92aa4102faf3c9937eee441864524d8381cb8", catalogue.hash());
        // The one warning: sign.on.checked captures passwordMatches' result without secretResult (a boolean: the
        // author's knowing choice), never its password argument.
        assertEquals(1, catalogue.warnings().size(), String.valueOf(catalogue.warnings()));
        assertTrue(catalogue.warnings().get(0).contains("sign.on.checked"), catalogue.warnings().get(0));
    }

    @Test
    void theSettingsNameTheSystemMaximumsSetting() {
        assertEquals(Map.of(LendingPack.MAX_RETURN_SETTING, "max_return"), pack.settings().asMap());
        assertEquals("lending.maxReturnSetting", LendingPack.MAX_RETURN_SETTING);
        assertEquals("0.6.0", LendingPack.VERSION);
    }

    /** The {@code SystemLimit} resolver reads the setting's name from its context (the branch overlay's constant). */
    @Test
    void theSystemLimitResolverReadsItsSettingNameFromTheContext() throws Exception {
        LendingResolutionTest.Ctx ctx = new LendingResolutionTest.Ctx();
        ctx.answer(LendingResolvers.SYSTEM_LIMIT_SQL, params -> rows(List.of("VALUE"),
                "return_ceiling".equals(params.get(0)) ? List.of(List.of("350.00")) : List.of()));
        ctx.settings = PackSettings.of(Map.of(LendingPack.MAX_RETURN_SETTING, "return_ceiling"));
        List<ResolverCandidate<BigDecimal>> found = new LendingResolvers.SystemLimitResolver().candidates(
                new SystemLimit("RETURN"), ctx);
        assertEquals(List.of(new BigDecimal("350.00")), found.stream().map(ResolverCandidate::value).toList());
        assertEquals(List.of("return_ceiling"), ctx.params.get(0));
        ctx.settings = PackSettings.EMPTY;
        assertEquals(List.of(), new LendingResolvers.SystemLimitResolver().candidates(new SystemLimit("RETURN"), ctx),
                "without the setting the standard name, which this desk does not have");
        assertEquals(List.of("max_return"), ctx.params.get(1));
    }

    /** The staff resolver runs the statement it was built with: what an overlay's subclass replaces. */
    @Test
    void theStaffResolverRunsTheStatementItWasGiven() throws Exception {
        String sql = LendingResolvers.STAFF_SQL.replace("rl.amount", "rl.ceiling");
        LendingResolvers.StaffResolver resolver = new LendingResolvers.StaffResolver(sql) {
        };
        assertEquals(LendingResolvers.STAFF_ID, resolver.id(), "the same id: it replaces the base's");
        assertEquals(sql, resolver.staffSql());
        LendingResolutionTest.Ctx ctx = new LendingResolutionTest.Ctx();
        ctx.answer(sql, params -> rows(List.of("CODE", "AUTO_SIGN_ON", "LIMIT_AMOUNT"),
                List.of(List.of("100", false, "20.00"))));
        ctx.answer(LendingResolvers.PERMISSIONS_SQL, params -> rows(List.of("PERMISSION"),
                List.of(List.of("RETURN"), List.of("SIGN_ON"))));
        var found = resolver.candidates(new StaffWithPermission(List.of("RETURN"), new LimitCondition("RETURN", null,
                null, null, new BigDecimal("10.00")), null, null), ctx);
        assertEquals(1, found.size());
        assertEquals("100", found.get(0).value().code());
        assertEquals(LendingResolvers.STAFF_SQL, new LendingResolvers.StaffResolver().staffSql());
    }

    @Test
    void theAllowListOfAnotherLimitColumnNamesThatColumn() {
        SqlAllowList branch = LendingAllowList.of("ceiling", "return_ceiling");
        SqlAllowList standard = LendingAllowList.LIST;
        assertNotNull(branch.table("role_cap").column("ceiling"));
        assertEquals(null, branch.table("role_cap").column("amount"));
        assertNotNull(standard.table("role_cap").column("amount"));
        assertEquals(null, standard.table("role_cap").column("ceiling"));
        assertTrue(branch.table("desk_setting").column("name").description().contains("return_ceiling"));
        assertFalse(standard.table("desk_setting").column("name").description().contains("return_ceiling"));
        assertEquals(standard.source(), branch.source());
        assertEquals(standard.table("item"), branch.table("item"), "every other table is the standard's");
    }
}
