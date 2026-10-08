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

package ai.skillroy.testjava.spikes.bs6;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import ai.skillroy.testjava.spikes.bs6.Corpus.Entry;
import org.junit.jupiter.api.Test;

/**
 * Writes the spike's findings to {@code target/bs6/}: {@code summary.md} (per dialect: accepted, rejected with the
 * named reason, executed on HSQLDB, parse and guard time), {@code <dialect>.txt} (every entry with its rewrite or its
 * violation), {@code limits.txt}, and {@code corpus/<dialect>-accept.sql}, a copy of each accept corpus with the
 * {@code =>} lines set to the current rewrites (review it, then copy it over to refresh the expected texts). Never fails
 * on a finding; {@link SqlGuardCorpusTest} is the gate.
 */
class Bs6ReportTest {

    private static final int TIMING_ROUNDS = 25;
    private static final int WARM_UP_PASSES = 5;

    private long slowest;
    private String slowestEntry;

    @Test
    void writeReport() throws Exception {
        Path out = Path.of("target", "bs6");
        Files.createDirectories(out.resolve("corpus"));
        StringBuilder summary = new StringBuilder();
        summary.append("Times: per statement the median of ").append(TIMING_ROUNDS)
                .append(" runs; per dialect the mean and the 95th percentile of those medians.\n\n")
                .append("| Dialect | Accepted | Rejected (named reason) | Executed (HSQLDB) | Parse, mean µs | ")
                .append("Parse, p95 µs | Guard, mean µs |\n|---|---|---|---|---|---|---|\n");
        List<Long> allParse = new ArrayList<>();
        List<Long> allGuard = new ArrayList<>();
        Map<String, Integer> reasons = new TreeMap<>();
        slowest = 0;
        slowestEntry = "";
        warmUp();
        try (Connection db = LendingFixture.hsqldb("bs6report")) {
            for (SqlDialect d : SqlDialect.values()) {
                try (SqlGuard guard = new SqlGuard(LendingFixture.allowList(d))) {
                    StringBuilder detail = new StringBuilder();
                    List<Entry> accept = Corpus.accept(d).entries();
                    List<Entry> reject = Corpus.reject(d).entries();
                    List<Entry> all = new ArrayList<>(accept);
                    all.addAll(reject);
                    int accepted = 0;
                    int executed = 0;
                    Map<Integer, String> rewrites = new TreeMap<>();
                    for (Entry e : accept) {
                        detail.append("== ").append(e).append('\n').append(indent(e.sql())).append('\n');
                        try {
                            GuardedStatement g = guard.guard(e.sql());
                            accepted++;
                            rewrites.put(e.line(), g.sql());
                            detail.append("   => ").append(g.sql()).append('\n');
                            for (String i : g.injected()) {
                                detail.append("      injected: ").append(i).append('\n');
                            }
                            detail.append("      tables ").append(g.tables()).append(", columns ").append(g.columns())
                                    .append(", parameters ").append(g.parameterCount()).append('\n');
                            if (d == SqlDialect.HSQLDB) {
                                String run = execute(db, g, e);
                                executed += run.startsWith("ok") ? 1 : 0;
                                detail.append("      executed: ").append(run).append('\n');
                            }
                        } catch (GuardViolation v) {
                            detail.append("   REFUSED ").append(v.reason()).append(": ").append(v.fragment())
                                    .append('\n');
                        }
                    }
                    int rejected = 0;
                    for (Entry e : reject) {
                        detail.append("== ").append(e).append('\n').append(indent(e.sql())).append('\n');
                        try {
                            GuardedStatement g = guard.guard(e.sql());
                            detail.append("   ACCEPTED (expected ").append(e.reason()).append("): ").append(g.sql())
                                    .append('\n');
                        } catch (GuardViolation v) {
                            rejected += v.reason() == e.reason() ? 1 : 0;
                            reasons.merge(v.reason().name(), 1, Integer::sum);
                            detail.append("   ").append(v.reason() == e.reason() ? "refused " : "WRONG REASON ")
                                    .append(v.reason()).append(": ").append(v.fragment()).append('\n');
                        }
                    }
                    long[] parse = new long[all.size()];
                    long[] whole = new long[all.size()];
                    for (int i = 0; i < all.size(); i++) {
                        parse[i] = timeParse(guard, all.get(i).sql());
                        whole[i] = timeGuard(guard, all.get(i).sql());
                        allParse.add(parse[i]);
                        allGuard.add(whole[i]);
                        if (parse[i] > slowest) {
                            slowest = parse[i];
                            slowestEntry = all.get(i) + " (" + all.get(i).sql().length() + " characters)";
                        }
                    }
                    summary.append("| ").append(d).append(" | ").append(accepted).append('/').append(accept.size())
                            .append(" | ").append(rejected).append('/').append(reject.size()).append(" | ")
                            .append(d == SqlDialect.HSQLDB ? executed + "/" + accept.size() : "–").append(" | ")
                            .append(mean(parse) / 1000).append(" | ").append(p95(parse) / 1000).append(" | ")
                            .append(mean(whole) / 1000).append(" |\n");
                    Files.writeString(out.resolve(d.name().toLowerCase(Locale.ROOT) + ".txt"), detail.toString());
                    Files.writeString(out.resolve("corpus").resolve(d.name().toLowerCase(Locale.ROOT)
                            + "-accept.sql"), withRewrites(d, rewrites));
                }
            }
        }
        StringBuilder limits = new StringBuilder();
        Map<SqlDialect, SqlGuard> guards = new EnumMap<>(SqlDialect.class);
        for (Entry e : Corpus.limits().entries()) {
            SqlGuard g = guards.computeIfAbsent(e.dialect(), k -> new SqlGuard(LendingFixture.allowList(k)));
            limits.append("== ").append(e.dialect()).append(' ').append(e.label()).append('\n')
                    .append(indent(e.sql())).append('\n');
            try {
                limits.append("   accepted => ").append(g.guard(e.sql()).sql()).append('\n');
            } catch (GuardViolation v) {
                limits.append("   ").append(v.reason()).append(": ").append(v.fragment()).append('\n');
            }
        }
        guards.values().forEach(SqlGuard::close);
        Files.writeString(out.resolve("limits.txt"), limits.toString());
        long[] p = allParse.stream().mapToLong(Long::longValue).toArray();
        long[] g = allGuard.stream().mapToLong(Long::longValue).toArray();
        summary.append("\nAll statements: parse mean ").append(mean(p) / 1000).append(" µs, p95 ")
                .append(p95(p) / 1000).append(" µs, max ").append(Arrays.stream(p).max().orElse(0) / 1000)
                .append(" µs; guard mean ").append(mean(g) / 1000).append(" µs (").append(p.length)
                .append(" statements, ").append(TIMING_ROUNDS).append(" rounds each, after ").append(WARM_UP_PASSES)
                .append(" warm-up passes over every corpus; JDK ").append(Runtime.version()).append(", ")
                .append(System.getProperty("os.arch")).append("). Slowest parse: ").append(slowestEntry).append(", ")
                .append(slowest / 1000).append(" µs.\n\nReasons seen: ").append(reasons).append('\n');
        Files.writeString(out.resolve("summary.md"), summary.toString());
        System.out.println(summary);
    }

    private static String execute(Connection db, GuardedStatement g, Entry e) {
        try (PreparedStatement ps = db.prepareStatement(g.sql())) {
            LendingFixture.bind(ps, e.params());
            if (!ps.execute()) {
                return "ok, no result set";
            }
            int rows = 0;
            try (ResultSet rs = ps.getResultSet()) {
                while (rs.next()) {
                    rows++;
                }
            }
            return "ok, " + rows + " rows";
        } catch (Exception ex) {
            return "FAILED " + ex.getMessage();
        }
    }

    /** Five passes over every corpus with fresh guards, so that the timings below are of warm code. */
    private static void warmUp() {
        for (int pass = 0; pass < WARM_UP_PASSES; pass++) {
            for (SqlDialect d : SqlDialect.values()) {
                try (SqlGuard guard = new SqlGuard(LendingFixture.allowList(d))) {
                    for (Entry e : Corpus.accept(d).entries()) {
                        tryGuard(guard, e.sql());
                    }
                    for (Entry e : Corpus.reject(d).entries()) {
                        tryGuard(guard, e.sql());
                    }
                }
            }
        }
    }

    private static void tryGuard(SqlGuard guard, String sql) {
        try {
            guard.guard(sql);
        } catch (GuardViolation ignored) {
            // timing warm-up only
        }
    }

    /** Median nanoseconds of lexing and parsing alone (no rule, no rewrite); the median ignores GC and JIT pauses. */
    private static long timeParse(SqlGuard guard, String sql) {
        long[] t = new long[TIMING_ROUNDS];
        for (int r = 0; r < TIMING_ROUNDS; r++) {
            long t0 = System.nanoTime();
            try {
                guard.parseOne(SqlText.strip(sql, guard.profile()));
            } catch (GuardViolation ignored) {
                // a refused text is timed too
            }
            t[r] = System.nanoTime() - t0;
        }
        Arrays.sort(t);
        return t[TIMING_ROUNDS / 2];
    }

    /** Median nanoseconds of the whole guard. */
    private static long timeGuard(SqlGuard guard, String sql) {
        long[] t = new long[TIMING_ROUNDS];
        for (int r = 0; r < TIMING_ROUNDS; r++) {
            long t0 = System.nanoTime();
            tryGuard(guard, sql);
            t[r] = System.nanoTime() - t0;
        }
        Arrays.sort(t);
        return t[TIMING_ROUNDS / 2];
    }

    private static long mean(long[] values) {
        return values.length == 0 ? 0 : Arrays.stream(values).sum() / values.length;
    }

    private static long p95(long[] values) {
        if (values.length == 0) {
            return 0;
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1)];
    }

    private static String indent(String sql) {
        return "   " + sql.replace("\n", "\n   ");
    }

    /** The accept corpus file with each entry's "=>" line set to the current rewrite. */
    private static String withRewrites(SqlDialect d, Map<Integer, String> rewrites) throws Exception {
        String file = "/corpus/" + d.name().toLowerCase(Locale.ROOT) + "-accept.sql";
        String text;
        try (InputStream in = Bs6ReportTest.class.getResourceAsStream(file)) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            if (lines[i].startsWith("@@ ")) {
                int start = i + 1;
                List<String> entry = new ArrayList<>();
                entry.add(lines[i++]);
                while (i < lines.length && !lines[i].startsWith("@@ ")) {
                    entry.add(lines[i++]);
                }
                List<String> kept = new ArrayList<>();
                for (String l : entry) {
                    if (!l.startsWith("=> ")) {
                        kept.add(l);
                    }
                }
                int trailing = 0;
                while (!kept.isEmpty() && kept.getLast().isBlank()) {
                    kept.removeLast();
                    trailing++;
                }
                if (rewrites.containsKey(start)) {
                    kept.add("=> " + rewrites.get(start));
                }
                for (int t = 0; t < trailing; t++) {
                    kept.add("");
                }
                for (String l : kept) {
                    out.append(l).append('\n');
                }
            } else {
                out.append(lines[i++]).append('\n');
            }
        }
        return out.substring(0, out.length() - 1);
    }
}
