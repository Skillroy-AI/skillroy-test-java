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

import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.BindingsFormatException;
import ai.skillroy.testjava.scenario.resolve.Resolution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sample scenario A, {@code return-over-limit-needs-supervisor} (phase2-contracts.md 13.4): where its files are, what
 * it resolves to on each seed, and the {@code wrong-assistant} bindings the tests generate from the committed file.
 * Shared by the headless tests and the end-to-end checks.
 *
 * <p>Stateless; safe from any thread.
 */
final class ScenarioA {

    static final String ID = "return-over-limit-needs-supervisor";
    static final String YAML = ID + ".yaml";

    /** The slots and oracles in evaluation order ({@code resolutionGraph}). */
    static final List<String> GRAPH = List.of("systemMax", "item", "assistant", "supervisor", "keys", "oracle.auth",
            "oracle.returnLine");
    /** The query slots, in plan order. */
    static final List<String> QUERY_SLOTS = List.of("systemMax", "item", "assistant", "supervisor", "keys");

    /** The values of 13.4 for one seed, as the codec encodes them. */
    record Expected(String systemMax, Map<String, Object> item, Map<String, Object> assistant,
                    Map<String, Object> supervisor, String returnLine, String assistantPassword,
                    String supervisorPassword, String withinLimitAssistant, String withinLimitSupervisor) {

        String assistantCode() {
            return (String) assistant.get("code");
        }

        String supervisorCode() {
            return (String) supervisor.get("code");
        }

        String itemCode() {
            return (String) item.get("code");
        }

        String itemTitle() {
            return (String) item.get("title");
        }
    }

    static final Map<String, Object> KEYS = Map.of("DUE", "DUE", "RETURN", "RETURN");

    private ScenarioA() {
    }

    static Expected expected(Env env) {
        if (env == Env.SEED_A) {
            return new Expected("500.00",
                    item("PWR-001", "5000000000093", "Cordless drill", "34.99"),
                    staff("100", "20.00", List.of("RETURN", "SIGN_ON")),
                    staff("200", "300.00", List.of("RETURN", "RETURN_OVERRIDE", "SIGN_ON")),
                    "-34.99", SampleCredentials.SEED_A.get("100"), SampleCredentials.SEED_A.get("200"), "200", "300");
        }
        return new Expected("350.00",
                item("T1009", "4006000000080", "Jigsaw", "27.50"),
                staff("4711", "15.00", List.of("RETURN", "SIGN_ON")),
                staff("4720", "200.00", List.of("RETURN", "RETURN_OVERRIDE", "SIGN_ON")),
                "-27.50", SampleCredentials.SEED_B.get("4711"), SampleCredentials.SEED_B.get("4720"), "4715", "4720");
    }

    private static Map<String, Object> item(String code, String barcode, String title, String deposit) {
        return Map.of("code", code, "barcode", barcode, "title", title, "deposit", deposit);
    }

    private static Map<String, Object> staff(String code, String limit, List<String> permissions) {
        return Map.of("code", code, "limits", Map.of("RETURN", limit), "permissions", permissions);
    }

    /** {@code scenarios/<id>.yaml} (committed). */
    static Path yaml() {
        return ItEnv.scenario(YAML);
    }

    /** {@code plan/<id>.plan.json} (committed). */
    static Path plan() {
        return ItEnv.plan(ID);
    }

    /** The module's {@code bindings/} directory: the default root for the scenario and the plan (section 9). */
    static Path bindingsRoot() {
        return ItEnv.moduleDir().resolve("bindings");
    }

    /** {@code bindings/<env>/<id>.bindings.json} (committed). */
    static Path committedBindings(Env env) {
        return BindingsFile.locate(bindingsRoot(), env.id, ID);
    }

    /**
     * Writes the {@code wrong-assistant} bindings of 13.4 below {@code root}: the committed file of {@code env} with
     * the assistant's value and credential replaced by the supervisor's, everything else ({@code argsHash} included)
     * kept. Generated rather than committed, so that an edit of the scenario's {@code resolve} section cannot leave a
     * stale negative file behind.
     *
     * @return the written file
     */
    static Path writeWrongAssistant(Env env, Path root) throws IOException, BindingsFormatException {
        BindingsFile committed = BindingsFile.read(committedBindings(env));
        Resolution assistant = committed.resolutions().get("assistant");
        Resolution supervisor = committed.resolutions().get("supervisor");
        Resolution wrong = new Resolution(assistant.slot(), assistant.query(), assistant.args(),
                assistant.argsHash(), supervisor.value(), assistant.strategy(), assistant.provenance(),
                assistant.verified(), assistant.confidence(), supervisor.credential(), assistant.approval(),
                assistant.usage());
        Map<String, Resolution> resolutions = new LinkedHashMap<>(committed.resolutions());
        resolutions.put("assistant", wrong);
        BindingsFile file = new BindingsFile(committed.bindingsVersion(), committed.scenarioId(), committed.envId(),
                committed.pack(), committed.packVersion(), committed.planHash(), committed.resolveHash(),
                committed.env(), resolutions, committed.oracles());
        Path target = BindingsFile.locate(root, env.id, ID);
        Files.createDirectories(target.getParent());
        file.write(target);
        return target;
    }
}
