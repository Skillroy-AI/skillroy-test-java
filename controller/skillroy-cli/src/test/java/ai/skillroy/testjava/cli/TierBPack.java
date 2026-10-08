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

package ai.skillroy.testjava.cli;

import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.pack.FactProvider;
import ai.skillroy.testjava.scenario.pack.StepLibrary;
import ai.skillroy.testjava.scenario.pack.SyncPolicy;
import ai.skillroy.testjava.scenario.resolve.Query;
import ai.skillroy.testjava.scenario.resolve.QueryType;
import ai.skillroy.testjava.scenario.resolve.ResolutionCatalogue;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList;
import ai.skillroy.testjava.scenario.resolve.SqlAllowList.AllowedColumn;
import ai.skillroy.testjava.scenario.resolve.SqlDialect;
import ai.skillroy.testjava.scenario.resolve.Verifier;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;
import ai.skillroy.testjava.scenario.resolve.VerifyContext;

import java.util.List;
import java.util.Map;

/**
 * The CLI tests' pack ({@code clitest}, {@link CliTestPack}'s verbs and facts) with a query kind that only a model
 * answers: {@code Tool {kind}} over the SQL allow-list of the source {@code workshop} (table {@code tool}, key
 * {@code code}), verified by a check that always passes. Deliberately not registered on the test class path: a test
 * that needs a model-written (tier (b)) resolution runs the CLI with {@link CliTierB#loader}, which finds this pack
 * instead of {@link CliTestPack}.
 */
public final class TierBPack implements DomainPack {

    private final CliTestPack base = new CliTestPack();

    /** A tool of a kind. */
    public record Tool(String kind) implements Query<ToolRef> {
    }

    /** The answer to {@link Tool}: a tool by its code. */
    public record ToolRef(String code) {
    }

    @Override
    public String id() {
        return base.id();
    }

    @Override
    public String version() {
        return base.version();
    }

    @Override
    public StepLibrary steps() {
        return base.steps();
    }

    @Override
    public SyncPolicy syncPolicy() {
        return base.syncPolicy();
    }

    @Override
    public List<FactProvider> facts() {
        return base.facts();
    }

    @Override
    public ResolutionCatalogue resolution() {
        return ResolutionCatalogue.builder(base.resolution()).query(QueryType.of("Tool", Tool.class))
                .verifier(new Known()).build();
    }

    @Override
    public List<SqlAllowList> sqlAllowLists() {
        return List.of(SqlAllowList.builder("workshop", SqlDialect.HSQLDB)
                .table("tool", List.of("code"), AllowedColumn.of("code", "VARCHAR"), AllowedColumn.of("kind",
                        "VARCHAR"))
                .build());
    }

    /** Every tool the model finds is known. */
    static final class Known implements Verifier<Tool, ToolRef> {

        @Override
        public String id() {
            return "clitest.tool.known";
        }

        @Override
        public Class<Tool> queryClass() {
            return Tool.class;
        }

        @Override
        public VerifierResult verify(Tool query, ToolRef candidate, VerifyContext ctx) {
            return VerifierResult.pass("known", Map.of());
        }
    }
}
