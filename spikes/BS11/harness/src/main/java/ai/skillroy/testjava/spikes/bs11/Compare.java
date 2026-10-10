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
package ai.skillroy.testjava.spikes.bs11;

import ai.skillroy.testjava.protocol.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * {@code compare}: the difference between the same states of two runs (environment noise); {@code pair}: between two
 * states of one run (a real change). For each pair of images of the same size: pixels differing (count and
 * percentage), the largest per-channel difference, the counts above each tolerance of {@link Pixels#TOLERANCES}, the
 * bounding boxes of the differing regions, the components (from the first run's tree) the differing pixels fall in,
 * and per-component crops (the components of both trees matched by class, name and ordinal, compared where their
 * sizes agree). {@code table} turns the JSON records into the tables of RESULT.md.
 */
final class Compare {

    /** Classes whose pixels are mostly text (a label, a field, a table, a button with text, a text pane). */
    static final List<String> TEXT_CLASSES = List.of("JLabel", "EntryField", "JTextField", "JTextArea", "JTable",
            "JTableHeader", "PromptTextPane", "PromptButton", "JButton");

    private Compare() {
    }

    /** Compares every state both runs captured; writes {@code <out>} (JSON) and prints a summary. */
    static void runs(Path a, Path b, Path outFile) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(a.resolve("png"))) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".png") && !n.contains(".v"))
                    .sorted().forEach(n -> names.add(n.substring(0, n.length() - 4)));
        }
        for (String state : names) {
            if (state.contains("@launch")) {
                continue;
            }
            Path pb = b.resolve("png").resolve(state + ".png");
            if (!Files.exists(pb)) {
                continue;
            }
            // A state with a caret blink has two variants: compare every pair and keep the closest one.
            Map<String, Object> best = null;
            List<String> va = variants(a, state);
            List<String> vb = variants(b, state);
            for (String x : va) {
                for (String y : vb) {
                    Map<String, Object> r = record(label(a), label(b), state, a, x, b, y);
                    if (best == null || closer(r, best)) {
                        best = r;
                    }
                }
            }
            best.put("variants", va.size() + "/" + vb.size());
            records.add(best);
        }
        write(outFile, records);
    }

    private static boolean closer(Map<String, Object> r, Map<String, Object> best) {
        if (r.containsKey("geometry") || best.containsKey("geometry")) {
            return false;
        }
        return ((Number) r.get("differing")).longValue() < ((Number) best.get("differing")).longValue();
    }

    /** The state's own PNG and its variants ({@code <state>.v2.png} ...), as state names. */
    private static List<String> variants(Path run, String state) {
        List<String> out = new ArrayList<>();
        out.add(state);
        for (int v = 2; Files.exists(run.resolve("png").resolve(state + ".v" + v + ".png")); v++) {
            out.add(state + ".v" + v);
        }
        return out;
    }

    /** Compares two states of one run. */
    static void pair(Path run, String stateA, String stateB, Path outFile) throws Exception {
        write(outFile, List.of(record(label(run), label(run), stateA + " -> " + stateB, run, stateA, run, stateB)));
    }

    private static String label(Path run) {
        return run.getFileName().toString();
    }

    private static void write(Path outFile, List<Map<String, Object>> records) throws Exception {
        Files.createDirectories(outFile.toAbsolutePath().getParent());
        Files.writeString(outFile, Json.writePretty(records), StandardCharsets.UTF_8);
        for (Map<String, Object> r : records) {
            System.out.println(oneLine(r));
        }
    }

    static String oneLine(Map<String, Object> r) {
        if (r.containsKey("geometry")) {
            return String.format(Locale.ROOT, "%-24s %-24s %-40s geometry differs: %s", r.get("a"), r.get("b"),
                    r.get("state"), r.get("geometry"));
        }
        return String.format(Locale.ROOT, "%-24s %-24s %-40s differing %7s (%.3f%%) max %3s >8:%s >16:%s >32:%s "
                + "text-share %s regions %s", r.get("a"), r.get("b"), r.get("state"), r.get("differing"),
                ((Number) r.get("percent")).doubleValue(), r.get("maxChannel"), ((List<?>) r.get("above")).get(1),
                ((List<?>) r.get("above")).get(2), ((List<?>) r.get("above")).get(3), r.get("textShare"),
                r.get("regionCount"));
    }

    private static Map<String, Object> record(String labelA, String labelB, String state, Path runA, String stateA,
                                              Path runB, String stateB) throws Exception {
        Pixels pa = Pixels.decode(Files.readAllBytes(runA.resolve("png").resolve(stateA + ".png")));
        Pixels pb = Pixels.decode(Files.readAllBytes(runB.resolve("png").resolve(stateB + ".png")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", labelA);
        m.put("b", labelB);
        m.put("state", state);
        m.put("sizeA", pa.width + "x" + pa.height);
        m.put("sizeB", pb.width + "x" + pb.height);
        List<Node> nodesA = nodes(runA.resolve("tree").resolve(stateA + ".json"));
        List<Node> nodesB = nodes(runB.resolve("tree").resolve(stateB + ".json"));
        if (pa.width != pb.width || pa.height != pb.height) {
            m.put("geometry", pa.width + "x" + pa.height + " vs " + pb.width + "x" + pb.height);
            m.put("crops", crops(pa, nodesA, pb, nodesB));
            return m;
        }
        Pixels.Diff d = Pixels.diff(pa, pb);
        m.put("total", d.total());
        m.put("differing", d.differing);
        m.put("percent", d.percent());
        m.put("maxChannel", d.maxChannel);
        List<Object> above = new ArrayList<>();
        for (long v : d.above) {
            above.add(v);
        }
        m.put("tolerances", List.of(0, 8, 16, 32, 64));
        m.put("above", above);
        List<int[]> regions = d.regions(3);
        m.put("regionCount", regions.size());
        List<Object> top = new ArrayList<>();
        for (int i = 0; i < Math.min(8, regions.size()); i++) {
            int[] r = regions.get(i);
            top.add(String.format(Locale.ROOT, "%d,%d %dx%d (%d px)", r[0], r[1], r[2], r[3], r[4]));
        }
        m.put("regions", top);
        if (d.differing > 0) {
            int[] owner = owners(pa.width, pa.height, nodesA);
            Map<String, Integer> byOwner = new HashMap<>();
            int text = 0;
            for (int i = 0; i < d.magnitude.length; i++) {
                if (d.magnitude[i] == 0) {
                    continue;
                }
                Node n = owner[i] < 0 ? null : nodesA.get(owner[i]);
                String key = n == null ? "(window)" : n.key;
                byOwner.merge(key, 1, Integer::sum);
                if (n != null && TEXT_CLASSES.contains(n.cls)) {
                    text++;
                }
            }
            m.put("textShare", String.format(Locale.ROOT, "%.2f", (double) text / d.differing));
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(byOwner.entrySet());
            sorted.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
            Map<String, Integer> topOwners = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(12, sorted.size()); i++) {
                topOwners.put(sorted.get(i).getKey(), sorted.get(i).getValue());
            }
            m.put("owners", topOwners);
        } else {
            m.put("textShare", "-");
        }
        m.put("crops", crops(pa, nodesA, pb, nodesB));
        return m;
    }

    /** Per-component crops of the components both trees have with the same size: differing pixels and max channel. */
    private static List<Object> crops(Pixels pa, List<Node> nodesA, Pixels pb, List<Node> nodesB) {
        Map<String, Node> byKeyB = new HashMap<>();
        for (Node n : nodesB) {
            byKeyB.put(n.key, n);
        }
        List<Object> out = new ArrayList<>();
        for (Node na : nodesA) {
            Node nb = byKeyB.get(na.key);
            if (nb == null || na.depth == 0 || na.w <= 0 || na.h <= 0 || na.children > 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", na.key);
            m.put("text", TEXT_CLASSES.contains(na.cls));
            if (na.w != nb.w || na.h != nb.h) {
                m.put("size", na.w + "x" + na.h + " vs " + nb.w + "x" + nb.h);
                out.add(m);
                continue;
            }
            Pixels ca = pa.crop(na.x, na.y, na.w, na.h);
            Pixels cb = pb.crop(nb.x, nb.y, nb.w, nb.h);
            if (ca == null || cb == null || ca.width != cb.width || ca.height != cb.height) {
                continue;
            }
            Pixels.Diff d = Pixels.diff(ca, cb);
            m.put("size", na.w + "x" + na.h);
            m.put("offset", (nb.x - na.x) + "," + (nb.y - na.y));
            m.put("differing", d.differing);
            m.put("percent", d.percent());
            m.put("maxChannel", d.maxChannel);
            List<Object> above = new ArrayList<>();
            for (long v : d.above) {
                above.add(v);
            }
            m.put("above", above);
            out.add(m);
        }
        return out;
    }

    /** For every pixel, the index of the deepest node containing it (painted parents first), or -1. */
    private static int[] owners(int w, int h, List<Node> nodes) {
        int[] owner = new int[w * h];
        java.util.Arrays.fill(owner, -1);
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (n.depth == 0) {
                continue;
            }
            for (int y = Math.max(0, n.y); y < Math.min(h, n.y + n.h); y++) {
                for (int x = Math.max(0, n.x); x < Math.min(w, n.x + n.w); x++) {
                    owner[y * w + x] = i;
                }
            }
        }
        return owner;
    }

    /** One component of a snapshot: window-relative bounds, its class, a key unique within the tree. */
    record Node(String key, String cls, int x, int y, int w, int h, int depth, int children) {
    }

    @SuppressWarnings("unchecked")
    static List<Node> nodes(Path treeFile) throws Exception {
        List<Node> out = new ArrayList<>();
        if (!Files.exists(treeFile)) {
            return out;
        }
        Map<String, Object> tree = Json.parseObject(Files.readString(treeFile, StandardCharsets.UTF_8));
        Map<String, Integer> ordinals = new HashMap<>();
        for (Object w : (List<Object>) tree.get("windows")) {
            walk((Map<String, Object>) w, 0, ordinals, out);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(Map<String, Object> node, int depth, Map<String, Integer> ordinals, List<Node> out) {
        Map<String, Object> b = (Map<String, Object>) node.get("bounds");
        String cls = String.valueOf(node.get("class"));
        String base = cls + "[" + node.get("name") + "]";
        int ordinal = ordinals.merge(base, 1, Integer::sum);
        List<Object> kids = node.get("children") instanceof List<?> l ? (List<Object>) l : List.of();
        out.add(new Node(base + "#" + ordinal, cls, num(b.get("x")), num(b.get("y")), num(b.get("w")),
                num(b.get("h")), depth, kids.size()));
        for (Object k : kids) {
            walk((Map<String, Object>) k, depth + 1, ordinals, out);
        }
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    // ------------------------------------------------------------------------------------------------- tables

    /**
     * {@code table <noise.json...> -- <change.json...>}: the cross-environment table and the tolerance study as
     * Markdown on stdout.
     */
    @SuppressWarnings("unchecked")
    static void table(List<Path> noise, List<Path> changes) throws Exception {
        List<Map<String, Object>> n = new ArrayList<>();
        for (Path p : noise) {
            n.addAll((List<Map<String, Object>>) (List<?>) Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
        }
        List<Map<String, Object>> c = new ArrayList<>();
        for (Path p : changes) {
            c.addAll((List<Map<String, Object>>) (List<?>) Json.parse(Files.readString(p, StandardCharsets.UTF_8)));
        }
        System.out.println("| A | B | state | differing | % | max ch. | >8 | >16 | >32 | >64 | text share | regions |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (Map<String, Object> r : n) {
            System.out.println(row(r));
        }
        System.out.println();
        System.out.println("Changes (within one run):");
        System.out.println();
        System.out.println("| run | change | differing | % | max ch. | >8 | >16 | >32 | >64 | regions |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|");
        for (Map<String, Object> r : c) {
            List<?> above = (List<?>) r.get("above");
            System.out.printf(Locale.ROOT, "| %s | %s | %s | %.4f | %s | %s | %s | %s | %s | %s |%n", r.get("a"),
                    r.get("state"), r.get("differing"), ((Number) r.get("percent")).doubleValue(),
                    r.get("maxChannel"), above.get(1), above.get(2), above.get(3), above.get(4),
                    r.get("regionCount"));
        }
        // The decision table: pixels above tolerance t, as an absolute count, for the worst noise pair per state
        // family versus the smallest change.
        System.out.println();
        System.out.println("Worst noise (largest count above t over all pairs) versus smallest change, per t:");
        System.out.println();
        System.out.println("| t | worst noise, desk states | worst noise, toy | smallest toy change | smallest desk change |");
        System.out.println("|---|---|---|---|---|");
        String[] ts = {"0", "8", "16", "32", "64"};
        for (int t = 0; t < ts.length; t++) {
            long noiseDesk = 0;
            long noiseToy = 0;
            for (Map<String, Object> r : n) {
                if (r.containsKey("geometry")) {
                    continue;
                }
                long v = ((Number) ((List<?>) r.get("above")).get(t)).longValue();
                if (String.valueOf(r.get("state")).startsWith("toy")) {
                    noiseToy = Math.max(noiseToy, v);
                } else {
                    noiseDesk = Math.max(noiseDesk, v);
                }
            }
            long changeToy = Long.MAX_VALUE;
            long changeDesk = Long.MAX_VALUE;
            for (Map<String, Object> r : c) {
                long v = ((Number) ((List<?>) r.get("above")).get(t)).longValue();
                if (String.valueOf(r.get("state")).startsWith("toy")) {
                    changeToy = Math.min(changeToy, v);
                } else {
                    changeDesk = Math.min(changeDesk, v);
                }
            }
            System.out.printf(Locale.ROOT, "| %s | %d | %d | %s | %s |%n", ts[t], noiseDesk, noiseToy,
                    changeToy == Long.MAX_VALUE ? "-" : changeToy, changeDesk == Long.MAX_VALUE ? "-" : changeDesk);
        }
        // Component crops: the worst per text and non-text component over all noise pairs.
        System.out.println();
        System.out.println("Component crops over all noise pairs of equal geometry (leaf components, same size):");
        System.out.println();
        Map<String, long[]> worst = new TreeMap<>();
        for (Map<String, Object> r : n) {
            for (Object o : (List<Object>) r.get("crops")) {
                Map<String, Object> crop = (Map<String, Object>) o;
                if (!crop.containsKey("differing")) {
                    continue;
                }
                String key = crop.get("component") + (Boolean.TRUE.equals(crop.get("text")) ? " (text)" : "");
                long[] w = worst.computeIfAbsent(key, k -> new long[3]);
                w[0]++;
                w[1] = Math.max(w[1], ((Number) crop.get("differing")).longValue());
                w[2] = Math.max(w[2], ((Number) crop.get("maxChannel")).longValue());
            }
        }
        System.out.println("| component | pairs | worst differing | worst max ch. |");
        System.out.println("|---|---|---|---|");
        worst.forEach((k, v) -> System.out.printf(Locale.ROOT, "| %s | %d | %d | %d |%n", k, v[0], v[1], v[2]));
    }

    private static String row(Map<String, Object> r) {
        if (r.containsKey("geometry")) {
            return String.format(Locale.ROOT, "| %s | %s | %s | geometry %s | | | | | | | | |", r.get("a"), r.get("b"),
                    r.get("state"), r.get("geometry"));
        }
        List<?> above = (List<?>) r.get("above");
        return String.format(Locale.ROOT, "| %s | %s | %s | %s | %.3f | %s | %s | %s | %s | %s | %s | %s |", r.get("a"),
                r.get("b"), r.get("state"), r.get("differing"), ((Number) r.get("percent")).doubleValue(),
                r.get("maxChannel"), above.get(1), above.get(2), above.get(3), above.get(4), r.get("textShare"),
                r.get("regionCount"));
    }
}
