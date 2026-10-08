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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.ItemRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LendableItem;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.LimitCondition;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffRef;
import ai.skillroy.testjava.samples.lending.pack.LendingQueries.StaffWithPermission;
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.ValueLoader;

import java.util.List;
import java.util.Map;

/**
 * The lending pack's tier (b) value loaders (phase5-contracts.md 5.4a, 14.1, decision D90): when a model writes the
 * statement that finds a staff member or an item, it selects only the identity column {@value #CODE}, and the value is
 * built here from that code with the same committed reads the tier (a) resolvers use (the shared helpers of
 * {@link LendingResolvers.StaffResolver} and {@link LendingResolvers.LendableItemResolver}), so that a value found by a
 * model and one found by the committed SQL are the same value for the same key. A {@code StaffRef} cannot come from
 * one flat row anyway: its permissions are a list and its limits a map.
 *
 * <p>What each loader refuses (a {@link ResolutionException} with a reason: the row is rejected with it, and the other
 * rows of the model's statement are still tried) is a key the committed read does not answer: an unknown or inactive
 * staff member, an item the query could not lend. Whether a staff member satisfies the query (permissions, limit,
 * sign-on, {@code distinctFrom}) is the verifier's to decide, through the desk's own access control, exactly as for a
 * value of the committed SQL; an item's deposit range, which the item's verifier does not look at, is applied by the
 * read the query parameterises, as the tier (a) resolver applies it.
 *
 * <p>Stateless; one instance of each serves every bind in the JVM, called on the binding thread.
 */
final class LendingLoaders {

    /** The identity column of both kinds: the staff member's or the item's code. */
    static final String CODE = "code";
    /** The reason of a staff code that names no active staff member. */
    static final String UNKNOWN_STAFF = "UNKNOWN_STAFF";
    /** The reason of an item code the query could not lend. */
    static final String NOT_LENDABLE = "NOT_LENDABLE";

    private LendingLoaders() {
    }

    /** The code of a row's identity: its {@value #CODE} value as text, or null when the row has none. */
    static String code(Map<String, Object> identity) {
        Object code = identity == null ? null : identity.get(CODE);
        return code == null || String.valueOf(code).isBlank() ? null : String.valueOf(code);
    }

    /**
     * {@code StaffWithPermission} by code: the active staff member's {@code StaffRef} as the staff resolver builds it
     * ({@link LendingResolvers.StaffResolver#activeStaff}, then {@link LendingResolvers.StaffResolver#staffRef}): the
     * permissions their roles grant and their limit of the query's kind ({@code RETURN} by default). A code that
     * names no active staff member is refused ({@value #UNKNOWN_STAFF}).
     */
    static final class StaffLoader implements ValueLoader<StaffWithPermission, StaffRef> {

        @Override
        public String kind() {
            return "StaffWithPermission";
        }

        @Override
        public List<String> identityColumns() {
            return List.of(CODE);
        }

        @Override
        public StaffRef load(StaffWithPermission query, Map<String, Object> identity, ResolveContext ctx)
                throws ResolutionException, DataException, AgentLostException, InterruptedException {
            String code = code(identity);
            if (code == null) {
                throw new ResolutionException(UNKNOWN_STAFF, null, "the row names no staff code");
            }
            LimitCondition limit = query.limit();
            String kind = limit == null ? "RETURN" : limit.kind();
            Rows staff = LendingResolvers.StaffResolver.activeStaff(kind, ctx);
            for (int r = 0; r < staff.rowCount(); r++) {
                if (code.equals(String.valueOf(staff.get(r, 0)))) {
                    return LendingResolvers.StaffResolver.staffRef(staff, r, kind, ctx);
                }
            }
            throw new ResolutionException(UNKNOWN_STAFF, null, "no active staff member has the code " + code);
        }
    }

    /**
     * {@code LendableItem} by code: the item as the item resolver answers it for the same query
     * ({@link LendingResolvers.LendableItemResolver#lendable}, then
     * {@link LendingResolvers.LendableItemResolver#itemRef}): active, scannable, lent the way the query asks
     * (counted by default) and with a deposit in the query's range. Any other code is refused ({@value #NOT_LENDABLE}):
     * an unknown or retired item, one without a barcode, one lent the other way, one outside the range.
     */
    static final class ItemLoader implements ValueLoader<LendableItem, ItemRef> {

        @Override
        public String kind() {
            return "LendableItem";
        }

        @Override
        public List<String> identityColumns() {
            return List.of(CODE);
        }

        @Override
        public ItemRef load(LendableItem query, Map<String, Object> identity, ResolveContext ctx)
                throws ResolutionException, DataException, AgentLostException, InterruptedException {
            String code = code(identity);
            if (code == null) {
                throw new ResolutionException(NOT_LENDABLE, null, "the row names no item code");
            }
            Rows rows = LendingResolvers.LendableItemResolver.lendable(query, ctx);
            for (int r = 0; r < rows.rowCount(); r++) {
                if (code.equals(String.valueOf(rows.get(r, 0)))) {
                    ItemRef item = LendingResolvers.LendableItemResolver.itemRef(query, rows, r);
                    if (item != null) {
                        return item;
                    }
                    break;
                }
            }
            throw new ResolutionException(NOT_LENDABLE, null, "item " + code + " is not one the query can lend: no"
                    + " active, scannable item of that code is lent " + (Boolean.TRUE.equals(query.perDay())
                    ? "per day" : "counted") + " with a deposit in the query's range");
        }
    }
}
