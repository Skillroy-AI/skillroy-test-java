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

import ai.skillroy.testjava.cli.Main;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 acceptance 14.1 item 9, the part that needs the committed files: {@code skillroy diff-bindings} (offline, no
 * agent) on the committed {@code seed-a} and {@code seed-b} bindings of scenario A reports the four slots that differ
 * between the seeds (and the oracle computed from them) and the fingerprint components that tell them apart, and
 * exits 1; a file against itself exits 0. On scenario C's files (phase5-contracts.md 14.3) it reports its two slots
 * and the refund computed from the item, and the same fingerprint components; nothing else differs.
 * Both forms of the command are used: two files, and the bindings root with two environment ids. The CLI's own tests
 * cover the rest of item 9 against its fake agent; this one lives here because the files do.
 */
class DiffCommittedBindingsTest {

    record Outcome(int exit, String out, String err) {
    }

    static Outcome cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Main.run(args, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true,
                StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theCommittedSeedsDifferInFourSlotsAndTheFingerprint() {
        String a = ScenarioA.committedBindings(Env.SEED_A).toString();
        String b = ScenarioA.committedBindings(Env.SEED_B).toString();
        Outcome json = cli("diff-bindings", a, b, "--json");
        assertEquals(1, json.exit(), json.toString());
        Map<String, Object> document = Json.parseObject(json.out());
        assertEquals(Boolean.TRUE, document.get("differ"));
        assertEquals(List.of("systemMax", "item", "assistant", "supervisor", "oracle.returnLine"),
                document.get("slots"), "four slots and the return line differ; keys and the guarded oracle do not");
        List<String> where = ((List<?>) document.get("changes")).stream().map(c -> String.valueOf(((Map<?, ?>) c)
                .get("where"))).toList();
        assertTrue(where.contains("env.components.lending.seed") && where.contains("env.components.lending.site"),
                String.valueOf(where));
        assertTrue(where.stream().noneMatch(w -> w.startsWith("env.components.lending.schema")),
                "the seeds share the schema: " + where);
        assertTrue(where.stream().noneMatch(w -> w.startsWith("resolutions.keys")), String.valueOf(where));

        Outcome text = cli("diff-bindings", "--bindings", ScenarioA.bindingsRoot().toString(), "--env", "seed-a",
                "--env", "seed-b", ScenarioA.ID);
        assertEquals(1, text.exit(), text.toString());
        for (String slot : List.of("slot systemMax", "slot item", "slot assistant", "slot supervisor",
                "lending.seed", "lending.site")) {
            assertTrue(text.out().contains(slot), slot + " in\n" + text.out());
        }

        Outcome same = cli("diff-bindings", a, a);
        assertEquals(0, same.exit(), same.toString());
        assertTrue(same.out().contains("no differences"), same.out());
    }

    @Test
    void scenarioCsSeedsDifferInItsTwoSlotsTheRefundAndTheFingerprint() {
        Outcome json = cli("diff-bindings", ScenarioC.committedBindings(Env.SEED_A).toString(),
                ScenarioC.committedBindings(Env.SEED_B).toString(), "--json");
        assertEquals(1, json.exit(), json.toString());
        Map<String, Object> document = Json.parseObject(json.out());
        assertEquals(Boolean.TRUE, document.get("differ"));
        assertEquals(List.of("item", "assistant", "oracle.refund"), document.get("slots"),
                "the item, the assistant and the refund computed from the item's deposit");
        List<String> where = ((List<?>) document.get("changes")).stream().map(c -> String.valueOf(((Map<?, ?>) c)
                .get("where"))).toList();
        assertTrue(where.contains("env.components.lending.seed") && where.contains("env.components.lending.site"),
                String.valueOf(where));
        assertTrue(where.stream().noneMatch(w -> w.startsWith("env.components.lending.schema")),
                "the seeds share the schema: " + where);
        assertTrue(where.stream().noneMatch(w -> w.startsWith("planHash") || w.startsWith("resolveHash")),
                "one plan for both seeds: " + where);
    }
}
