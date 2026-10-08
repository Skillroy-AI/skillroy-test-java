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

package ai.skillroy.testjava.mcp;

import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.resolve.SqlProposal;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.util.List;

/**
 * A model-written (tier (b)) resolution of the desk's {@code Item} query with no key (phase5-contracts.md 4.3, 5.4): a
 * scripted gateway served for settings of a gateway name of its own ({@code Gateways.serve}), answering every request
 * with one guarded {@code SELECT} over the desk's allow-list at a small cost, and the desk's database answering the
 * allow-list check (the columns of {@code item}, {@link Desk#itemSchema}), the catalogue's sample and that statement.
 * Closing ends the registration.
 */
final class TierB implements AutoCloseable {

    /** The statement the scripted model writes for {@code Item {category: tools}}. */
    static final String STATEMENT = "SELECT code, title FROM item WHERE category = ?";
    /** What one scripted call costs. */
    static final String COST = "0.0123";

    final InferenceSettings settings;
    final ScriptedGateway gateway;
    private final Gateways.Served served;

    /**
     * @param gatewayName a name no other test serves (the registration is the JVM's)
     */
    TierB(String gatewayName, Desk desk) {
        settings = new InferenceSettings(gatewayName, null, false, null, null, null, null, null, null, true, false,
                null, false, null);
        SqlProposal proposal = new SqlProposal(STATEMENT, List.of(new SqlProposal.Param("category", "tools",
                "STRING")), "The items of the category, by code.", List.of("item"), "code", 0.9,
                new SqlProposal.RowBounds(1, 5), false, "");
        gateway = ScriptedGateway.answering(request -> ScriptedGateway.structured(proposal,
                ScriptedGateway.usage(400, 60, COST)));
        served = Gateways.serve(settings, gateway);
        desk.agent.on("data.query", params -> {
            String sql = String.valueOf(params.get("sql"));
            if (sql.equals(Desk.ITEM_SCHEMA_CHECK)) {
                return desk.itemSchema().toJson();
            }
            if (sql.contains("DISTINCT")) {
                return Rows.of(List.of(new Rows.Column("CATEGORY", "VARCHAR")), List.of(List.of("tools")), false)
                        .toJson();
            }
            return Rows.of(List.of(new Rows.Column("CODE", "VARCHAR"), new Rows.Column("TITLE", "VARCHAR")),
                    List.of(List.of("HAM-1", "Claw hammer")), false).toJson();
        });
    }

    @Override
    public void close() {
        served.close();
    }
}
