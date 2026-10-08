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

import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ItemRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LendableItem;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LimitCondition;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffWithPermission;
import ai.skillroy.testjava.samples.lending.pack.LendingResolutionTest.Arrays2;
import ai.skillroy.testjava.samples.lending.pack.LendingResolutionTest.Ctx;
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.resolve.NumberRange;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.ResolverCandidate;
import ai.skillroy.testjava.scenario.resolve.ValueLoader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.samples.lending.pack.LendingResolutionTest.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack's tier (b) value loaders over canned rows (phase5-contracts.md 5.4a, 14.1, decision D90): registered in the
 * catalogue for {@code StaffWithPermission} and {@code LendableItem} with the identity column {@code code}; each loads,
 * for a key, exactly the value the tier (a) resolver offers for it, through the same statements with the same
 * parameters; a key the committed read does not answer is a {@link ResolutionException} with a reason. The same
 * loaders against the real schema on both seeds are tested in the scenarios module
 * ({@code ValueLoadersOnRealSchemaTest}).
 */
class LendingLoadersTest {

    private static final Map<String, List<String>> PERMISSIONS = Map.of("100", List.of("RETURN", "SIGN_ON"),
            "101", List.of("SIGN_ON"), "200", List.of("RETURN", "RETURN_OVERRIDE", "SIGN_ON"), "300",
            List.of("RETURN", "RETURN_OVERRIDE", "SIGN_ON"));

    /** Staff rows (code, auto_sign_on, limit_amount) and their permissions, as the resolver's two statements read. */
    private static Ctx staffDesk() {
        Ctx ctx = new Ctx();
        ctx.answer(LendingResolvers.STAFF_SQL, params -> rows(List.of("CODE", "AUTO_SIGN_ON", "LIMIT_AMOUNT"),
                List.of(List.of("100", false, "20.00"), List.of("101", false, "5.0"), Arrays2.row("200", false,
                        "300.00"), Arrays2.row("300", false, null))));
        ctx.answer(LendingResolvers.PERMISSIONS_SQL, params -> {
            List<List<?>> out = new ArrayList<>();
            PERMISSIONS.getOrDefault(String.valueOf(params.get(0)), List.of()).forEach(p -> out.add(List.of(p)));
            return rows(List.of("PERMISSION"), out);
        });
        return ctx;
    }

    private static Map<String, Object> code(Object code) {
        Map<String, Object> identity = new HashMap<>();
        identity.put("code", code);
        return identity;
    }

    @Test
    void theCatalogueRegistersBothLoadersByKindWithTheCodeAsIdentity() {
        ResolutionCatalogue catalogue = new LendingPack().resolution();
        ValueLoader<?, ?> staff = catalogue.valueLoader("StaffWithPermission");
        ValueLoader<?, ?> item = catalogue.valueLoader("LendableItem");
        assertInstanceOf(LendingLoaders.StaffLoader.class, staff);
        assertInstanceOf(LendingLoaders.ItemLoader.class, item);
        assertEquals(List.of("code"), staff.identityColumns());
        assertEquals(List.of("code"), item.identityColumns());
        for (String kind : List.of("SystemLimit", "ActionKey", "ActiveWaiverRuleWithTriggers", "ItemsTriggering")) {
            assertNull(catalogue.valueLoader(kind), kind + " has no loader");
        }
    }

    @Test
    void theStaffLoaderLoadsWhatTheResolverOffersForTheSameCodeThroughTheSameStatements() throws Exception {
        StaffWithPermission supervisor = new StaffWithPermission(List.of("RETURN_OVERRIDE"),
                new LimitCondition("RETURN", null, null, null, new BigDecimal("34.99")), null, List.of("100"));
        Ctx resolved = staffDesk();
        List<ResolverCandidate<StaffRef>> candidates = new LendingResolvers.StaffResolver().candidates(supervisor,
                resolved);
        assertEquals(List.of("200", "300"), candidates.stream().map(c -> c.value().code()).toList());
        for (ResolverCandidate<StaffRef> candidate : candidates) {
            Ctx loaded = staffDesk();
            StaffRef value = new LendingLoaders.StaffLoader().load(supervisor, code(candidate.value().code()), loaded);
            assertEquals(candidate.value(), value, candidate.label());
            assertEquals(List.of(List.of("RETURN"), List.of(candidate.value().code())), loaded.params,
                    "the staff statement with the kind, then the one staff member's permissions");
        }
        Map<String, BigDecimal> unlimited = new HashMap<>();
        unlimited.put("RETURN", null);
        assertEquals(new StaffRef("300", List.of("RETURN", "RETURN_OVERRIDE", "SIGN_ON"), unlimited),
                new LendingLoaders.StaffLoader().load(supervisor, code("300"), staffDesk()), "null is unlimited");
        assertEquals(new StaffRef("101", List.of("SIGN_ON"), Map.of("RETURN", new BigDecimal("5.00"))),
                new LendingLoaders.StaffLoader().load(supervisor, code("101"), staffDesk()),
                "a staff member the query would not take is still loaded: the verifier decides");
        Ctx kind = staffDesk();
        new LendingLoaders.StaffLoader().load(new StaffWithPermission(List.of("SIGN_ON"), null, null, null),
                code("100"), kind);
        assertEquals(List.of("RETURN"), kind.params.get(0), "RETURN by default");
    }

    @Test
    void anUnknownStaffCodeIsAResolutionExceptionWithAReason() {
        StaffWithPermission query = new StaffWithPermission(List.of("SIGN_ON"), null, null, null);
        for (Object key : java.util.Arrays.asList("999", "102", null, " ")) {
            ResolutionException e = assertThrows(ResolutionException.class,
                    () -> new LendingLoaders.StaffLoader().load(query, code(key), staffDesk()), String.valueOf(key));
            assertEquals(LendingLoaders.UNKNOWN_STAFF, e.reason(), String.valueOf(key));
        }
        ResolutionException e = assertThrows(ResolutionException.class,
                () -> new LendingLoaders.StaffLoader().load(query, code("999"), staffDesk()));
        assertEquals("no active staff member has the code 999", e.getMessage());
    }

    @Test
    void theItemLoaderLoadsWhatTheResolverOffersForTheSameCodeAndRefusesTheRest() throws Exception {
        NumberRange range = new NumberRange(new BigDecimal("20.00"), null, null, null,
                List.of(new BigDecimal("10.00"), new BigDecimal("200.00")));
        LendableItem query = new LendableItem(range, null);
        Ctx resolved = new Ctx();
        resolved.answer(LendingResolvers.LENDABLE_ITEM_SQL, params -> rows(List.of("CODE", "BARCODE", "TITLE",
                "DEPOSIT"), List.of(List.of("A", "1", "Cheap", "20.00"), List.of("B", "2", "Mid", "34.99"),
                List.of("C", "3", "Dear", "40.00"))));
        List<ResolverCandidate<ItemRef>> candidates = new LendingResolvers.LendableItemResolver().candidates(query,
                resolved);
        assertEquals(List.of("B", "C"), candidates.stream().map(c -> c.value().code()).toList());
        for (ResolverCandidate<ItemRef> candidate : candidates) {
            assertEquals(candidate.value(), new LendingLoaders.ItemLoader().load(query, code(candidate.value()
                    .code()), resolved), candidate.label());
        }
        assertEquals(List.of(Boolean.FALSE, "20.00", "200.00"), resolved.params.get(resolved.params.size() - 1),
                "the resolver's statement with the resolver's parameters");
        for (String key : List.of("A", "Z")) {
            ResolutionException e = assertThrows(ResolutionException.class,
                    () -> new LendingLoaders.ItemLoader().load(query, code(key), resolved), key);
            assertEquals(LendingLoaders.NOT_LENDABLE, e.reason(), key);
            assertTrue(e.getMessage().startsWith("item " + key + " is not one the query can lend"), e.getMessage());
        }
        ResolutionException none = assertThrows(ResolutionException.class,
                () -> new LendingLoaders.ItemLoader().load(query, code(null), resolved));
        assertEquals(LendingLoaders.NOT_LENDABLE, none.reason());
    }
}
