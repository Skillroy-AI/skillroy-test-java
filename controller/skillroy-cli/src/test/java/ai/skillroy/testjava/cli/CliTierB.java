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

import ai.skillroy.testjava.inference.Gateways;
import ai.skillroy.testjava.inference.resolve.SqlProposal;
import ai.skillroy.testjava.inference.scripted.ScriptedGateway;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.sql.Rows;
import ai.skillroy.testjava.scenario.pack.DomainPack;
import ai.skillroy.testjava.scenario.resolve.BindMode;
import ai.skillroy.testjava.scenario.resolve.InferenceSettings;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * A model-written (tier (b)) resolution through the CLI with no key (phase5-contracts.md 4.3, 5.4): the CLI runs with
 * {@link #loader} (whose only pack is {@link TierBPack}) and {@link #options()} ({@code --inference recorded} over a
 * directory of no recordings), and a scripted gateway is served for exactly those settings ({@code Gateways.serve}),
 * answering every request with one {@code SELECT} over the pack's allow-list at {@value #COST} a call; the fake agent's
 * {@code data.query} answers the allow-list check with the columns of {@code tool} ({@link #SCHEMA_CHECK}) and every
 * other statement with one tool. Closing ends the registration.
 */
final class CliTierB implements AutoCloseable {

    /** The statement the scripted model writes for {@code Tool {kind: hammer}}. */
    static final String STATEMENT = "SELECT code FROM tool WHERE kind = ?";
    /** What one scripted call costs. */
    static final String COST = "0.0123";
    /**
     * The allow-list check's statement on table {@code tool} ({@code AllowListCheck}, decision D100: a tier (b) bind
     * runs it before its catalogue, the MCP server at its start); answered with the table's columns, {@code code} and
     * {@code kind}, as the pack's allow-list names them.
     */
    static final String SCHEMA_CHECK = "SELECT * FROM tool WHERE 1 = 0";
    /** The REPL line of a model-written resolution. */
    static final String RESOLVE = "resolve tool Tool {\"kind\":\"hammer\"} --strategy llm-sql";

    final Path recordings;
    final ClassLoader loader;
    final ScriptedGateway gateway;
    private final Gateways.Served served;

    CliTierB(Path dir, FakeAgent agent) throws Exception {
        recordings = Files.createDirectories(dir.resolve("tier-b-recordings")).toAbsolutePath().normalize();
        InferenceSettings settings = InferenceSettings.load(BindMode.LIVE, null, Map.of(
                InferenceSettings.PROP_GATEWAY, InferenceSettings.GATEWAY_RECORDED,
                InferenceSettings.PROP_RECORDINGS, recordings.toString()));
        SqlProposal proposal = new SqlProposal(STATEMENT, List.of(new SqlProposal.Param("kind", "hammer",
                "STRING")), "The tools of the kind, by code.", List.of("tool"), "code", 0.9,
                new SqlProposal.RowBounds(1, 5), false, "");
        gateway = ScriptedGateway.answering(request -> ScriptedGateway.structured(proposal,
                ScriptedGateway.usage(400, 60, COST)));
        served = Gateways.serve(settings, gateway);
        agent.handlers.put("data.sources", p -> Map.of("sources", List.of(Map.of("id", "workshop"))));
        agent.handlers.put("data.query", p -> SCHEMA_CHECK.equals(p.get("sql"))
                ? Rows.of(List.of(new Rows.Column("CODE", "VARCHAR"), new Rows.Column("KIND", "VARCHAR")), List.of(),
                        false).toJson()
                : Rows.of(List.of(new Rows.Column("CODE", "VARCHAR")), List.of(List.of("T-1")), false).toJson());
        Path services = Files.createDirectories(dir.resolve("tier-b-services"))
                .resolve(DomainPack.class.getName());
        Files.writeString(services, TierBPack.class.getName() + "\n", StandardCharsets.UTF_8);
        URL url = services.toUri().toURL();
        String resource = "META-INF/services/" + DomainPack.class.getName();
        loader = new ClassLoader(CliHarness.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return name.equals(resource) ? Collections.enumeration(List.of(url)) : super.getResources(name);
            }
        };
    }

    /** The inference options of a command that should find the served gateway. */
    List<String> options() {
        return new ArrayList<>(List.of("--inference", "recorded", "--recordings", recordings.toString()));
    }

    /** The model calls the scripted gateway answered. */
    int calls() {
        return gateway.requests().size();
    }

    /** The {@code budget} records of a session's journal, in order. */
    static List<Map<String, Object>> budgets(Path journal) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : Files.readAllLines(journal)) {
            Map<String, Object> record = Json.parseObject(line);
            if ("budget".equals(record.get("type"))) {
                out.add(record);
            }
        }
        return out;
    }

    @Override
    public void close() {
        served.close();
    }
}
