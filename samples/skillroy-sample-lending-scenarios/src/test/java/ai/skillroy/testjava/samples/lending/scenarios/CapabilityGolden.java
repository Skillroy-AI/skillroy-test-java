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

import ai.skillroy.testjava.controller.CapabilityMatrix;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The committed golden capability matrix of a load mode (phase6-contracts.md 8.2, decision D136):
 * {@code src/test/resources/capabilities/golden/<mode slug>.json}, compared with a target's live matrix after
 * {@code target.ready} in both legs of the matrix (Java 8 and Java 25 targets), so that a drift fails in every leg.
 *
 * <p>Normalisation: ids and {@code state} strict (a missing or an extra id fails), {@code strategy} strict (it names
 * an implementation), {@code reason} by presence ({@code true}/{@code false}: free text that may embed a message of
 * the JDK). An entry of {@code allow-differences.json} ({@code {id, states: [...], reason}}) lets an id's state differ
 * between two of its {@code states}, explained rather than tolerated silently; the list is empty at first. A failure
 * prints a unified diff of the normalised forms. {@code -Dskillroy.golden.write=true} rewrites a golden from the live
 * matrix (the committed-plans pattern); a build never sets it.
 */
final class CapabilityGolden {

    /** One allowed difference: {@code id}'s state may be any of {@code states}, for {@code reason}. */
    record Allowed(String id, List<String> states, String reason) {
    }

    private CapabilityGolden() {
    }

    static Path dir() {
        return ItEnv.moduleDir().resolve("src/test/resources/capabilities/golden");
    }

    /** {@code {"capabilities": {id: {"state", "strategy", "reason": true|false}}}}, ids sorted. */
    static Map<String, Object> normalise(CapabilityMatrix matrix) {
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Capability> e : new TreeMap<>(matrix.all()).entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("state", e.getValue().state().name());
            entry.put("strategy", e.getValue().strategy());
            entry.put("reason", e.getValue().reason() != null && !e.getValue().reason().isBlank());
            entries.put(e.getKey(), entry);
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("capabilities", entries);
        return json;
    }

    /** The allow-list, {@code allow-differences.json} beside the goldens. */
    static List<Allowed> allowed(Path file) throws IOException {
        List<Allowed> out = new ArrayList<>();
        for (Object item : (List<?>) Json.parse(Files.readString(file, StandardCharsets.UTF_8))) {
            Map<?, ?> entry = (Map<?, ?>) item;
            List<String> states = new ArrayList<>();
            ((List<?>) entry.get("states")).forEach(state -> states.add(String.valueOf(state)));
            out.add(new Allowed(String.valueOf(entry.get("id")), states, String.valueOf(entry.get("reason"))));
        }
        return out;
    }

    /**
     * Null when {@code live} matches {@code golden} (both normalised), else the unified diff of the two, the golden
     * first. An id's entry whose two states are both in its allow-list entry's {@code states} is compared without its
     * state, strategy and reason.
     */
    @SuppressWarnings("unchecked")
    static String compare(Map<String, Object> golden, Map<String, Object> live, List<Allowed> allowed) {
        Map<String, Object> g = new TreeMap<>((Map<String, Object>) golden.get("capabilities"));
        Map<String, Object> l = new TreeMap<>((Map<String, Object>) live.get("capabilities"));
        for (Allowed allow : allowed) {
            Object ge = g.get(allow.id());
            Object le = l.get(allow.id());
            if (ge instanceof Map<?, ?> gm && le instanceof Map<?, ?> lm
                    && allow.states().contains(String.valueOf(gm.get("state")))
                    && allow.states().contains(String.valueOf(lm.get("state")))) {
                g.put(allow.id(), "allowed: " + allow.reason());
                l.put(allow.id(), "allowed: " + allow.reason());
            }
        }
        if (g.equals(l)) {
            return null;
        }
        return unifiedDiff(lines(g), lines(l));
    }

    /**
     * Compares the target's live matrix with the golden of {@code slug}, or writes the golden when
     * {@code -Dskillroy.golden.write=true}.
     *
     * @throws AssertionError with the diff when they differ
     */
    static void check(String slug, CapabilityMatrix live) throws IOException {
        Map<String, Object> normalised = normalise(live);
        Path file = dir().resolve(slug + ".json");
        if (Boolean.getBoolean("skillroy.golden.write")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.writePretty(normalised) + "\n", StandardCharsets.UTF_8);
            return;
        }
        if (!Files.isRegularFile(file)) {
            throw new AssertionError("no golden matrix " + file + " (write it with -Dskillroy.golden.write=true)");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> golden = (Map<String, Object>) Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        String diff = compare(golden, normalised, allowed(dir().resolve("allow-differences.json")));
        if (diff != null) {
            throw new AssertionError("the capability matrix of " + slug + " differs from its golden (" + file
                    + "):\n" + diff);
        }
    }

    private static List<String> lines(Map<String, Object> entries) {
        List<String> out = new ArrayList<>();
        entries.forEach((id, entry) -> out.add(id + " " + Json.write(entry)));
        return out;
    }

    /** A unified diff of two line lists (one hunk, every line: the matrices are short), {@code ---} golden. */
    static String unifiedDiff(List<String> a, List<String> b) {
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        StringBuilder out = new StringBuilder("--- golden\n+++ live\n@@ -1," + a.size() + " +1," + b.size()
                + " @@\n");
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                out.append(' ').append(a.get(i)).append('\n');
                i++;
                j++;
            } else if (j < b.size() && (i == a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                out.append('+').append(b.get(j)).append('\n');
                j++;
            } else {
                out.append('-').append(a.get(i)).append('\n');
                i++;
            }
        }
        return out.toString();
    }
}
