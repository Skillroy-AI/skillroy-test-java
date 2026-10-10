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

package ai.skillroy.testjava.spikes.bs12;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Spike BS12: grounding accuracy, latency and cost of a vision loop on the sample lending desk (live model calls).
 *
 * <p>Commands (each launches its own desk in the Linux image, see README.md):
 * <ul>
 *   <li>{@code probe}: drives the desk through the grounding screens and writes the composite captures, the marked
 *       captures, the text snapshots and the ground truth of every target into {@code target/probe/}; no model call;</li>
 *   <li>{@code count}: token counts (free) of one capture and of the variants' fixed parts;</li>
 *   <li>{@code ground}: single-shot grounding (options below);</li>
 *   <li>{@code session}: one short task end to end as a loop;</li>
 *   <li>{@code summary}: the tables of RESULT.md from {@code results/*.jsonl};</li>
 *   <li>{@code spend}: the total spent so far, from the records.</li>
 * </ul>
 *
 * <p>The model key is read from the environment by name ({@code ANTHROPIC_API_KEY}) and never printed or written;
 * every file this program writes is checked against it first ({@link #write}). The budget is enforced in
 * {@link Model}: no call is sent whose worst-case cost could take the recorded total above the cap.
 */
public final class Bs12 {

    static final JsonMapper MAPPER = ObjectMappers.jsonMapper();
    /** The hard budget of the whole spike, in US dollars (the brief: USD 10.00). */
    static final BigDecimal HARD_CAP = new BigDecimal("10.00");

    private Bs12() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Bs12 probe|count|ground|session|summary|spend [--option value ...]");
            System.exit(2);
        }
        Map<String, String> opts = options(Arrays.copyOfRange(args, 1, args.length));
        int rc = 0;
        switch (args[0]) {
            case "probe" -> Grounding.probe();
            case "count" -> Grounding.count(opts);
            case "ground" -> Grounding.run(opts);
            case "session" -> rc = Session.run(opts);
            case "summary" -> Summary.run();
            case "spend" -> System.out.println("spent USD " + Ledger.spent());
            default -> {
                System.err.println("unknown command " + args[0]);
                rc = 2;
            }
        }
        System.exit(rc);
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("expected --option, got " + a);
            }
            String key = a.substring(2);
            String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            out.put(key, value);
        }
        return out;
    }

    /** {@code spikes/BS12} ({@code -Dbs12.home}). */
    static Path home() {
        return Path.of(System.getProperty("bs12.home", "spikes/BS12")).toAbsolutePath().normalize();
    }

    static Path target() {
        return home().resolve("target");
    }

    static Path results() {
        return home().resolve("results");
    }

    /**
     * Writes {@code text} to {@code file} (replacing it, or appending with {@code append}) after checking that it
     * does not contain the model key's value. The key is compared, never printed.
     */
    static synchronized void write(Path file, String text, boolean append) throws IOException {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && key.length() >= 8 && text.contains(key)) {
            throw new IllegalStateException("refusing to write " + file.getFileName() + ": it would contain the model key");
        }
        String ws = System.getenv("ANTHROPIC_WORKSPACE_ID");
        if (ws != null && ws.length() >= 8 && text.contains(ws)) {
            throw new IllegalStateException("refusing to write " + file.getFileName() + ": it would contain the workspace id");
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (append) {
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
    }

    /**
     * Appends one JSON record as a line of {@code results/<name>.jsonl}. The model's own free text (the field
     * {@code text}) is not kept in the repository: it goes to the git-ignored {@code target/model-text.jsonl} with the
     * record's identifying fields, and the record keeps only its length ({@code textChars}).
     */
    static void record(String name, ObjectNode record) throws IOException {
        if (record.has("text")) {
            ObjectNode t = MAPPER.createObjectNode();
            for (String k : new String[] {"kind", "session", "turn", "model", "effort", "variant", "target", "rep", "tag"}) {
                if (record.has(k)) {
                    t.set(k, record.get(k));
                }
            }
            t.put("text", record.get("text").asText());
            write(target().resolve("model-text.jsonl"), MAPPER.writeValueAsString(t) + "\n", true);
            record.put("textChars", record.get("text").asText().length());
            record.remove("text");
        }
        write(results().resolve(name + ".jsonl"), MAPPER.writeValueAsString(record) + "\n", true);
    }

    /** Every record of every {@code results/*.jsonl}. */
    static List<JsonNode> records() throws IOException {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.isDirectory(results())) {
            return out;
        }
        try (Stream<Path> files = Files.list(results())) {
            for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        out.add(MAPPER.readTree(line));
                    }
                }
            }
        }
        return out;
    }

    /** The spend recorded so far: the sum of {@code costUsd} over every record. */
    static final class Ledger {
        private Ledger() {
        }

        static BigDecimal spent() throws IOException {
            BigDecimal sum = BigDecimal.ZERO;
            for (JsonNode r : records()) {
                if (r.hasNonNull("costUsd")) {
                    sum = sum.add(new BigDecimal(r.get("costUsd").asText()));
                }
            }
            return sum;
        }
    }

    static void log(String fmt, Object... args) {
        System.out.println(String.format(fmt, args));
        System.out.flush();
    }
}
