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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** The tables of RESULT.md, from every record: {@code target/summary.md} and {@code results/summary.json}. */
final class Summary {

    private Summary() {
    }

    static void run() throws Exception {
        List<JsonNode> all = Bs12.records();
        // The smoke run (two targets, before the main runs) counts for the spend only.
        List<JsonNode> ground = all.stream().filter(r -> "ground".equals(r.path("kind").asText())
                && !"smoke".equals(r.path("tag").asText())).toList();
        List<JsonNode> sessions = all.stream().filter(r -> "session".equals(r.path("kind").asText())).toList();
        ObjectNode json = Bs12.MAPPER.createObjectNode();
        StringBuilder md = new StringBuilder();

        // Spend by part.
        Map<String, BigDecimal> spend = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode r : all) {
            if (r.hasNonNull("costUsd")) {
                String part = switch (r.path("kind").asText()) {
                    case "ground" -> r.path("part").asText();
                    case "session-turn" -> "session " + r.path("model").asText() + " " + r.path("variant").asText();
                    default -> r.path("kind").asText();
                };
                BigDecimal c = new BigDecimal(r.get("costUsd").asText());
                spend.merge(part, c, BigDecimal::add);
                total = total.add(c);
            }
        }
        md.append("## Spend (sum of usage x price per call)\n\n| Part | USD |\n|---|---|\n");
        ObjectNode js = json.putObject("spendUsd");
        for (Map.Entry<String, BigDecimal> e : spend.entrySet()) {
            md.append("| ").append(e.getKey()).append(" | ").append(e.getValue().setScale(4, RoundingMode.HALF_UP)).append(" |\n");
            js.put(e.getKey(), e.getValue().setScale(6, RoundingMode.HALF_UP).toPlainString());
        }
        md.append("| **total** | **").append(total.setScale(4, RoundingMode.HALF_UP)).append("** |\n\n");
        js.put("total", total.setScale(6, RoundingMode.HALF_UP).toPlainString());

        // Accuracy, latency and cost per (variant, model, effort).
        Map<String, List<JsonNode>> groups = group(ground, r -> r.path("effort").asText() + " | "
                + r.path("variant").asText() + " | " + r.path("model").asText());
        md.append("## Grounding by effort x variant x model\n\n");
        md.append("| Effort | Variant | Model | n | Correct | Accuracy | No tool call | Partial | Errors | Latency median ms |"
                + " p90 ms | Per call median ms | Input tok (mean) | Output tok (mean) | Cache read (mean) | USD per attempt (mean) |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        ArrayNode jg = json.putArray("grounding");
        for (Map.Entry<String, List<JsonNode>> e : groups.entrySet()) {
            List<JsonNode> g = e.getValue();
            long n = g.size();
            long ok = g.stream().filter(r -> r.path("correct").asBoolean()).count();
            long partial = g.stream().filter(r -> r.path("partial").asBoolean()).count();
            long errors = g.stream().filter(r -> r.has("error")).count();
            long noCall = g.stream().filter(r -> !r.has("error") && r.path("answer").isEmpty()).count();
            List<Long> perCall = new ArrayList<>();
            g.forEach(r -> r.path("calls").forEach(c -> perCall.add(c.path("latencyMs").asLong())));
            Collections.sort(perCall);
            List<Long> lat = new ArrayList<>(g.stream().filter(r -> !r.has("error")).map(r -> r.path("latencyMs").asLong()).toList());
            Collections.sort(lat);
            double in = g.stream().mapToLong(r -> r.path("input").asLong()).average().orElse(0);
            double out = g.stream().mapToLong(r -> r.path("output").asLong()).average().orElse(0);
            double cr = g.stream().mapToLong(r -> r.path("cacheRead").asLong()).average().orElse(0);
            BigDecimal cost = g.stream().map(r -> new BigDecimal(r.path("costUsd").asText("0"))).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            BigDecimal mean = n == 0 ? BigDecimal.ZERO : cost.divide(BigDecimal.valueOf(n), 5, RoundingMode.HALF_UP);
            String[] k = e.getKey().split(" \\| ");
            md.append("| ").append(k[0]).append(" | ").append(k[1]).append(" | ").append(k[2]).append(" | ").append(n)
                    .append(" | ").append(ok).append(" | ").append(pct(ok, n)).append(" | ").append(noCall).append(" | ")
                    .append(partial).append(" | ")
                    .append(errors).append(" | ").append(q(lat, 0.5)).append(" | ").append(q(lat, 0.9)).append(" | ")
                    .append(q(perCall, 0.5)).append(" | ")
                    .append(Math.round(in)).append(" | ").append(Math.round(out)).append(" | ").append(Math.round(cr))
                    .append(" | ").append(mean.toPlainString()).append(" |\n");
            ObjectNode o = jg.addObject();
            o.put("effort", k[0]);
            o.put("variant", k[1]);
            o.put("model", k[2]);
            o.put("n", n);
            o.put("correct", ok);
            o.put("partial", partial);
            o.put("errors", errors);
            o.put("noToolCall", noCall);
            o.put("perCallMedianMs", q(perCall, 0.5));
            o.put("latencyMedianMs", q(lat, 0.5));
            o.put("latencyP90Ms", q(lat, 0.9));
            o.put("inputMean", Math.round(in));
            o.put("outputMean", Math.round(out));
            o.put("cacheReadMean", Math.round(cr));
            o.put("usdMean", mean.toPlainString());
            o.put("usdTotal", cost.toPlainString());
        }

        // Accuracy by target kind at each effort, per variant x model.
        md.append("\n## Accuracy by target kind (correct / n)\n\n");
        List<String> kinds = List.of("icon-key", "painted-button", "text-field", "table-row", "table", "label", "ambiguous");
        md.append("| Effort | Variant | Model |");
        kinds.forEach(k -> md.append(' ').append(k).append(" |"));
        md.append("\n|---|---|---|");
        kinds.forEach(k -> md.append("---|"));
        md.append('\n');
        for (Map.Entry<String, List<JsonNode>> e : groups.entrySet()) {
            String[] k = e.getKey().split(" \\| ");
            md.append("| ").append(k[0]).append(" | ").append(k[1]).append(" | ").append(k[2]).append(" |");
            for (String kind : kinds) {
                List<JsonNode> g = e.getValue().stream().filter(r -> kind.equals("ambiguous")
                        ? r.path("ambiguous").asBoolean() : kind.equals(r.path("targetKind").asText())).toList();
                long ok = g.stream().filter(r -> r.path("correct").asBoolean()).count();
                md.append(' ').append(ok).append('/').append(g.size()).append(" |");
            }
            md.append('\n');
        }

        // Per target: how many attempts were right.
        md.append("\n## Per target (correct / n over every model, variant and effort)\n\n| Target | Screen | Kind | Intent |"
                + " v1-text | v1-marks | v2-custom | v2-toolset |\n|---|---|---|---|---|---|---|---|\n");
        Map<String, List<JsonNode>> byTarget = group(ground, r -> r.path("target").asText());
        for (Map.Entry<String, List<JsonNode>> e : byTarget.entrySet()) {
            JsonNode f = e.getValue().get(0);
            md.append("| ").append(e.getKey()).append(" | ").append(f.path("screen").asText()).append(" | ")
                    .append(f.path("targetKind").asText()).append(f.path("ambiguous").asBoolean() ? " (ambiguous)" : "")
                    .append(" | ").append(f.path("intent").asText()).append(" |");
            for (String v : Grounding.VARIANTS) {
                List<JsonNode> g = e.getValue().stream().filter(r -> v.equals(r.path("variant").asText())).toList();
                long ok = g.stream().filter(r -> r.path("correct").asBoolean()).count();
                md.append(' ').append(ok).append('/').append(g.size()).append(" |");
            }
            md.append('\n');
        }

        // Toolset behaviour.
        md.append("\n## Computer toolset: members called per attempt\n\n");
        Map<String, Integer> seqs = new TreeMap<>();
        for (JsonNode r : ground) {
            if ("v2-toolset".equals(r.path("variant").asText())) {
                List<String> m = new ArrayList<>();
                r.path("members").forEach(x -> m.add(x.asText()));
                seqs.merge(r.path("model").asText() + ": " + String.join(" > ", m), 1, Integer::sum);
            }
        }
        md.append("| Model: sequence | Count |\n|---|---|\n");
        seqs.forEach((s, c) -> md.append("| ").append(s).append(" | ").append(c).append(" |\n"));

        // Pixel answers: how far from the target, and what kind of miss.
        md.append("\n## Pixel answers (V2): distance to the target and kind of miss\n\n| Variant | Model | Clicks | Right |"
                + " Median distance to centre (right) | Max distance to centre (right) | Wrong: inside another component |"
                + " Wrong: within 10 px of the target |\n|---|---|---|---|---|---|---|---|\n");
        Map<String, List<JsonNode>> pix = group(ground.stream().filter(r -> r.path("answer").has("x")).toList(),
                r -> r.path("variant").asText() + " | " + r.path("model").asText());
        for (Map.Entry<String, List<JsonNode>> e : pix.entrySet()) {
            List<JsonNode> g = e.getValue();
            List<Long> d = new ArrayList<>(g.stream().filter(r -> r.path("correct").asBoolean())
                    .map(r -> r.path("answer").path("distToCentre").asLong()).toList());
            Collections.sort(d);
            long wrong = g.stream().filter(r -> !r.path("correct").asBoolean()).count();
            long near = g.stream().filter(r -> !r.path("correct").asBoolean()
                    && r.path("answer").path("distToBox").asLong() <= 10).count();
            String[] k = e.getKey().split(" \\| ");
            md.append("| ").append(k[0]).append(" | ").append(k[1]).append(" | ").append(g.size()).append(" | ")
                    .append(g.size() - wrong).append(" | ").append(q(d, 0.5)).append(" | ")
                    .append(d.isEmpty() ? 0 : d.get(d.size() - 1)).append(" | ").append(wrong - near).append(" | ")
                    .append(near).append(" |\n");
        }

        // Failures.
        md.append("\n## Wrong answers\n\n| Effort | Model | Variant | Target | Intent | Answer | Truth |\n|---|---|---|---|---|---|---|\n");
        for (JsonNode r : ground) {
            if (!r.path("correct").asBoolean()) {
                md.append("| ").append(r.path("effort").asText()).append(" | ").append(r.path("model").asText())
                        .append(" | ").append(r.path("variant").asText()).append(" | ").append(r.path("target").asText())
                        .append(" | ").append(r.path("intent").asText()).append(" | ")
                        .append(r.path("answer").toString().replace("|", "/"))
                        .append(r.has("error") ? " ERROR " + r.path("error").asText().replace("|", "/") : "")
                        .append(" | ").append(r.path("truth").toString().replace("|", "/")).append(" |\n");
            }
        }

        // Sessions.
        md.append("\n## Sessions\n\n| Run | Task | Variant | Model | Effort | Hint | Success | End | UI actions | Screenshots |"
                + " Other calls | Turns | Max calls/turn | Stale | Revalidated | Errors | s/action (wall) | model ms/turn |"
                + " act ms/action | settle ms/action | capture ms | Input | Output | Cache read | Cache write | Hit ratio | USD |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        ArrayNode jsn = json.putArray("sessions");
        Map<String, List<JsonNode>> sessGroups = new TreeMap<>();
        for (JsonNode s0 : sessions) {
            ObjectNode s = s0.deepCopy();
            String run = s.path("session").asText().replaceAll("-[0-9]{10,}$", "");
            s.put("run", run);
            int ui = 0;
            int shots = 0;
            int other = 0;
            for (JsonNode m : s.path("members")) {
                String name = m.asText();
                if (Session.UI_ACTIONS.contains(name)) {
                    ui++;
                } else if (name.equals("screenshot") || name.equals("zoom")) {
                    shots++;
                } else {
                    other++;
                }
            }
            s.put("uiActions", ui);
            s.put("screenshotCalls", shots);
            s.put("otherCalls", other);
            int a1 = Math.max(1, ui);
            int t = Math.max(1, s.path("turns").asInt());
            double wall = (s.path("modelMs").asLong() + s.path("actMs").asLong() + s.path("settleMs").asLong()
                    + s.path("captureMs").asLong()) / 1000.0 / a1;
            s.put("secondsPerAction", Math.round(wall * 10) / 10.0);
            String hint = s.path("hint").asText("none") + ("none".equals(s.path("feedback").asText("hit")) ? ", no hit feedback" : "");
            md.append("| ").append(run).append(" | ").append(s.path("task").asText()).append(" | ")
                    .append(s.path("variant").asText()).append(" | ").append(s.path("model").asText()).append(" | ")
                    .append(s.path("effort").asText()).append(" | ").append(hint).append(" | ")
                    .append(s.path("success").asBoolean()).append(" | ").append(s.path("end").asText()).append(" | ")
                    .append(ui).append(" | ").append(shots).append(" | ").append(other).append(" | ")
                    .append(s.path("turns").asInt()).append(" | ").append(s.path("maxCallsPerTurn").asInt()).append(" | ")
                    .append(s.path("stale").asInt()).append(" | ").append(s.path("revalidated").asInt()).append(" | ")
                    .append(s.path("errors").asInt()).append(" | ").append(String.format("%.1f", wall)).append(" | ")
                    .append(s.path("modelMs").asLong() / t).append(" | ").append(s.path("actMs").asLong() / a1).append(" | ")
                    .append(s.path("settleMs").asLong() / a1).append(" | ")
                    .append(s.path("captureMs").asLong() / Math.max(1, s.path("captures").asInt())).append(" | ")
                    .append(s.path("input").asLong()).append(" | ").append(s.path("output").asLong()).append(" | ")
                    .append(s.path("cacheRead").asLong()).append(" | ").append(s.path("cacheWrite").asLong()).append(" | ")
                    .append(String.format("%.2f", s.path("cacheHitRatio").asDouble())).append(" | ")
                    .append(s.path("sessionCostUsd").asText()).append(" |\n");
            jsn.add(s);
            sessGroups.computeIfAbsent(s.path("variant").asText() + " | " + s.path("model").asText() + " | "
                    + s.path("effort").asText() + " | " + hint, x -> new ArrayList<>()).add(s);
        }
        md.append("\n## Sessions grouped (both tasks)\n\n| Variant | Model | Effort | Hint | Runs | Succeeded | UI actions"
                + " (mean, min-max) | Turns (mean) | s/action (mean) | USD per session (mean, max) | Hit ratio (mean) |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<JsonNode>> e : sessGroups.entrySet()) {
            List<JsonNode> g = e.getValue();
            String[] k = e.getKey().split(" \\| ");
            long okRuns = g.stream().filter(x -> x.path("success").asBoolean()).count();
            double ua = g.stream().mapToInt(x -> x.path("uiActions").asInt()).average().orElse(0);
            int umin = g.stream().mapToInt(x -> x.path("uiActions").asInt()).min().orElse(0);
            int umax = g.stream().mapToInt(x -> x.path("uiActions").asInt()).max().orElse(0);
            double tu = g.stream().mapToInt(x -> x.path("turns").asInt()).average().orElse(0);
            double spa = g.stream().mapToDouble(x -> x.path("secondsPerAction").asDouble()).average().orElse(0);
            double usd = g.stream().mapToDouble(x -> x.path("sessionCostUsd").asDouble()).average().orElse(0);
            double usdMax = g.stream().mapToDouble(x -> x.path("sessionCostUsd").asDouble()).max().orElse(0);
            double hr = g.stream().mapToDouble(x -> x.path("cacheHitRatio").asDouble()).average().orElse(0);
            md.append("| ").append(k[0]).append(" | ").append(k[1]).append(" | ").append(k[2]).append(" | ").append(k[3])
                    .append(" | ").append(g.size()).append(" | ").append(okRuns).append(" | ")
                    .append(String.format("%.1f (%d-%d)", ua, umin, umax)).append(" | ").append(String.format("%.1f", tu))
                    .append(" | ").append(String.format("%.1f", spa)).append(" | ")
                    .append(String.format("%.4f, %.4f", usd, usdMax)).append(" | ").append(String.format("%.2f", hr))
                    .append(" |\n");
        }
        Bs12.write(Bs12.target().resolve("summary.md"), md.toString(), false);
        Bs12.write(Bs12.results().resolve("summary.json"), Bs12.MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(json) + "\n", false);
        System.out.println(md);
    }

    static Map<String, List<JsonNode>> group(List<JsonNode> rs, Function<JsonNode, String> key) {
        Map<String, List<JsonNode>> m = new TreeMap<>();
        for (JsonNode r : rs) {
            m.computeIfAbsent(key.apply(r), x -> new ArrayList<>()).add(r);
        }
        return m;
    }

    static String pct(long a, long n) {
        return n == 0 ? "-" : String.format("%.0f %%", 100.0 * a / n);
    }

    static long q(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }
}
