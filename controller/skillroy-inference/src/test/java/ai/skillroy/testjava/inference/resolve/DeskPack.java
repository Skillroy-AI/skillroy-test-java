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

package ai.skillroy.testjava.inference.resolve;

import ai.skillroy.testjava.data.DataException;
import ai.skillroy.testjava.inference.sql.LendingFixture;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.ResolutionException;
import ai.skillroy.testjava.scenario.pack.CompileContext;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.PlannedCall;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.ResolveContext;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import ai.skillroy.testjava.scenario.resolve.ValueLoader;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A test pack that mirrors the lending desk's shapes over spike BS6's lending schema (the sample's own pack declares
 * no allow-list yet, and its verifiers need the desk): query kinds with a flat record value, a value with a list (and
 * its {@code ValueLoader}), a scalar, and a map; the schema's allow-list; one verb.
 */
public final class DeskPack implements DomainPack {

    /** An item to lend, the cheapest that fits first. */
    public record ItemByCategory(String category, BigDecimal maxDeposit) implements Query<ItemRef> {
    }

    /** An item as a scenario uses it. */
    public record ItemRef(String code, String title, BigDecimal deposit) {
    }

    /** An active staff member holding a role; the value carries every role of theirs. */
    public record StaffWithRole(String role) implements Query<StaffRef> {
    }

    /** The same value without a loader. */
    public record StaffPlain(String role) implements Query<StaffRef> {
    }

    /** A staff member with their roles. */
    public record StaffRef(String code, List<String> roles) {
    }

    /** How many loans have a status. */
    public record LoanCount(String status) implements Query<Long> {
    }

    /** Loans per status (a map value). */
    public record LoansByStatus(String since) implements Query<Map<String, Long>> {
    }

    /** Reads a staff member's roles by code; refuses the codes it is told to. */
    static final class RolesLoader implements ValueLoader<StaffWithRole, StaffRef> {

        private final Set<String> refuse;

        RolesLoader(Set<String> refuse) {
            this.refuse = refuse;
        }

        @Override
        public String kind() {
            return "StaffWithRole";
        }

        @Override
        public List<String> identityColumns() {
            return List.of("code");
        }

        @Override
        public StaffRef load(StaffWithRole query, Map<String, Object> identity, ResolveContext ctx)
                throws ResolutionException, DataException, InterruptedException,
                ai.skillroy.testjava.controller.AgentLostException {
            String code = String.valueOf(identity.get("code"));
            if (refuse.contains(code)) {
                throw new ResolutionException("NOT_ELIGIBLE", null, "staff " + code + " is on leave");
            }
            Rows rows = ctx.data().query("lending", "SELECT r.role FROM staff_role r WHERE r.staff_code = ?"
                    + " ORDER BY r.role", List.of(code), 200, Duration.ofSeconds(5));
            List<String> roles = new ArrayList<>();
            for (List<Object> row : rows.rows()) {
                roles.add(String.valueOf(row.get(0)));
            }
            return new StaffRef(code, roles);
        }
    }

    /** The deposit is within the query's bound and the item is of its category (a pure check, as the desk's would). */
    static final class DepositVerifier implements Verifier<ItemByCategory, ItemRef> {

        @Override
        public String id() {
            return "lending.verify.deposit";
        }

        @Override
        public Class<ItemByCategory> queryClass() {
            return ItemByCategory.class;
        }

        @Override
        public VerifierResult verify(ItemByCategory query, ItemRef candidate, VerifyContext ctx) {
            boolean ok = query.maxDeposit() == null || candidate.deposit().compareTo(query.maxDeposit()) <= 0;
            return ok ? VerifierResult.pass("deposit within bound", Map.of())
                    : VerifierResult.fail("deposit above bound", Map.of());
        }
    }

    /** A count is never negative (every query slot needs a verifier; the re-resolve job's tests count loans). */
    static final class CountVerifier implements Verifier<LoanCount, Long> {

        @Override
        public String id() {
            return "lending.verify.count";
        }

        @Override
        public Class<LoanCount> queryClass() {
            return LoanCount.class;
        }

        @Override
        public VerifierResult verify(LoanCount query, Long candidate, VerifyContext ctx) {
            return candidate >= 0 ? VerifierResult.pass("a count", Map.of())
                    : VerifierResult.fail("a negative count", Map.of());
        }
    }

    private final ResolutionCatalogue catalogue;
    private final List<SqlAllowList> allowLists;

    public DeskPack(Set<String> refuse, boolean withAllowList) {
        this.catalogue = ResolutionCatalogue.builder()
                .query(QueryType.of("ItemByCategory", ItemByCategory.class))
                .query(QueryType.of("StaffWithRole", StaffWithRole.class))
                .query(QueryType.of("StaffPlain", StaffPlain.class))
                .query(QueryType.of("LoanCount", LoanCount.class))
                .query(QueryType.of("LoansByStatus", LoansByStatus.class))
                .valueLoader(new RolesLoader(refuse))
                .verifier(new DepositVerifier())
                .verifier(new CountVerifier())
                .build();
        this.allowLists = withAllowList ? List.of(LendingFixture.allowList(SqlDialect.HSQLDB)) : List.of();
    }

    public DeskPack() {
        this(Set.of(), true);
    }

    @Override
    public String id() {
        return "lending";
    }

    @Override
    public String version() {
        return "0.0.1-test";
    }

    @Override
    public StepLibrary steps() {
        return new StepLibrary() {
            @Override
            public Set<String> verbs() {
                return Set.of("signOn", "scanItem");
            }

            @Override
            public List<PlannedCall> compile(String verb, Object args, CompileContext ctx) {
                return List.of();
            }

            @Override
            public Map<String, Object> argsSchema(String verb) {
                if (!verb.equals("signOn")) {
                    return null;
                }
                return Map.of("type", "object", "properties", Map.of(
                                "staff", Map.of("type", "string"),
                                "pin", Map.of("type", "string", "maxLength", 8),
                                "mode", Map.of("type", "string", "enum", List.of("DESK", "SUPERVISOR"))),
                        "required", List.of("staff"));
            }
        };
    }

    @Override
    public SyncPolicy syncPolicy() {
        return new SyncPolicy(null, null, null, null, null);
    }

    @Override
    public List<FactProvider> facts() {
        return List.of();
    }

    @Override
    public ResolutionCatalogue resolution() {
        return catalogue;
    }

    @Override
    public List<SqlAllowList> sqlAllowLists() {
        return allowLists;
    }
}
