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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sample scenario B, {@code borrow-2-waive-1-slip} (phase3-contracts.md 10.4): where its files are, what it resolves
 * to on each seed, and the guarded variant of its cross-check. Shared by the headless tests and the end-to-end checks.
 *
 * <p>The waiver rule depends on the desk's business date, which the rule's resolver reads at bind time and applies in
 * Java; no date is a statement parameter or part of a verifier's reason, so a bindings file holds no date and the
 * committed files are the bytes an approval writes on any day.
 *
 * <p>Stateless; safe from any thread.
 */
final class ScenarioB {

    static final String ID = "borrow-2-waive-1-slip";
    static final String YAML = ID + ".yaml";

    /** The slots and oracles in evaluation order ({@code resolutionGraph}). */
    static final List<String> GRAPH = List.of("assistant", "rule", "item", "keys", "oracle.needed", "oracle.waiver",
            "oracle.due");
    /** The query slots, in plan order. */
    static final List<String> QUERY_SLOTS = List.of("assistant", "rule", "item", "keys");
    static final Map<String, Object> KEYS = Map.of("DUE", "DUE");
    /** The cross-check of the oracle {@code waiver}. */
    static final String CHECK = "lending.waiver.evaluate";
    /** A date as ISO-8601 text ({@code 2026-10-03}): no bindings file of the scenario holds one. */
    static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** The cross-check as the scenario writes it, and its guarded form (10.4, the negative material). */
    static final String UNGUARDED = "crossCheck: { check: lending.waiver.evaluate, tolerance: \"0.00\" }";
    static final String GUARDED = "crossCheck: { check: lending.waiver.evaluate, capability: lending.waiver.evaluate }";

    /**
     * The values of 10.4 for one seed, as the codec encodes them.
     *
     * @param wrongAmount what {@link WrongExpectedWaiver} expects for the same loan
     */
    record Expected(Map<String, Object> assistant, Map<String, Object> rule, Map<String, Object> item,
                    Map<String, Object> needed, Map<String, Object> waiver, Map<String, Object> due,
                    String assistantPassword, String wrongAmount) {

        String assistantCode() {
            return (String) assistant.get("code");
        }

        String ruleCode() {
            return (String) rule.get("code");
        }

        String itemCode() {
            return (String) item.get("code");
        }

        String itemTitle() {
            return (String) item.get("title");
        }

        String amount() {
            return (String) waiver.get("amount");
        }

        String total() {
            return (String) due.get("total");
        }

        String subtotal() {
            return (String) due.get("subtotal");
        }
    }

    private ScenarioB() {
    }

    static Expected expected(Env env) {
        if (env == Env.SEED_A) {
            return new Expected(staff("100", "20.00"),
                    rule("B2W1-HAND", item("HND-003", "5000000000062", "Screwdriver set", "0.99"),
                            item("HND-001", "5000000000048", "Claw hammer", "1.10"),
                            item("HND-002", "5000000000055", "Hand saw", "1.45")),
                    ref("HND-003", "5000000000062", "Screwdriver set", "0.99"),
                    Map.of("code", "HND-003", "qty", 3L, "unitDeposit", "0.99"),
                    Map.of("amount", "0.99", "ruleCode", "B2W1-HAND", "waivedUnits", 1L),
                    Map.of("subtotal", "2.97", "waiverTotal", "0.99", "total", "1.98"),
                    SampleCredentials.SEED_A.get("100"), "1.98");
        }
        return new Expected(staff("4711", "15.00"),
                rule("B2W1-CYCLE", item("T1002", "4006000000028", "Tyre levers", "0.79"),
                        item("T1001", "4006000000011", "Bike pump", "0.89"),
                        item("T1013", "4006000000127", "Puncture repair kit", "1.15")),
                ref("T1002", "4006000000028", "Tyre levers", "0.79"),
                Map.of("code", "T1002", "qty", 3L, "unitDeposit", "0.79"),
                Map.of("amount", "0.79", "ruleCode", "B2W1-CYCLE", "waivedUnits", 1L),
                Map.of("subtotal", "2.37", "waiverTotal", "0.79", "total", "1.58"),
                SampleCredentials.SEED_B.get("4711"), "1.58");
    }

    private static Map<String, Object> staff(String code, String limit) {
        return Map.of("code", code, "limits", Map.of("RETURN", limit), "permissions", List.of("RETURN", "SIGN_ON"));
    }

    private static Map<String, Object> rule(String code, Map<?, ?>... items) {
        return Map.of("code", code, "kind", "BORROW_N_WAIVE_M", "borrowQty", 2L, "waivedQty", 1L, "items",
                List.of(items));
    }

    /** A counted item without the induction check, as {@code WaiverRuleRef.items} encodes it. */
    private static Map<String, Object> item(String code, String barcode, String title, String deposit) {
        return Map.of("code", code, "barcode", barcode, "title", title, "deposit", deposit, "perDay", false,
                "inductionRequired", false);
    }

    private static Map<String, Object> ref(String code, String barcode, String title, String deposit) {
        return Map.of("code", code, "barcode", barcode, "title", title, "deposit", deposit);
    }

    /** {@code scenarios/<id>.yaml} (committed). */
    static Path yaml() {
        return ItEnv.scenario(YAML);
    }

    /** {@code plan/<id>.plan.json} (committed). */
    static Path plan() {
        return ItEnv.plan(ID);
    }

    /** {@code bindings/<env>/<id>.bindings.json} (committed), in the module's {@code bindings/} root. */
    static Path committedBindings(Env env) {
        return BindingsFile.locate(ScenarioA.bindingsRoot(), env.id, ID);
    }

    /** Scenario B with its cross-check guarded by the capability of the same name (10.4, the negative material). */
    static byte[] guardedYaml() throws IOException {
        String yaml = Files.readString(yaml(), StandardCharsets.UTF_8);
        String guarded = yaml.replace(UNGUARDED, GUARDED);
        if (guarded.equals(yaml)) {
            throw new IllegalStateException("scenario B's cross-check is no longer written as " + UNGUARDED);
        }
        return guarded.getBytes(StandardCharsets.UTF_8);
    }
}
