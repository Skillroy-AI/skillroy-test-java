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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Single-shot grounding: the screens, the four variants, the runner, the free token counts and the probe. */
final class Grounding {

    static final List<String> VARIANTS = List.of("v1-text", "v1-marks", "v2-custom", "v2-toolset");
    static final String TOOLSET = "computer_toolset_20260801";

    static final String SYS_V1_TEXT = """
            You see one window of a desktop application, a tool-library lending desk, as a screenshot and as a text \
            snapshot of its component tree; each component line of the snapshot ends with [ref=...]. The user names \
            one element of the window. Call select_component with the ref of that element. For a row of a table, give \
            the table's ref and the row number (1 is the first row). Call the tool exactly once and write nothing else.""";
    static final String SYS_V1_TEXT_NOIMAGE = """
            You see one window of a desktop application, a tool-library lending desk, as a text snapshot of its \
            component tree; each component line of the snapshot ends with [ref=...]. The user names one element of \
            the window. Call select_component with the ref of that element. For a row of a table, give the table's \
            ref and the row number (1 is the first row). Call the tool exactly once and write nothing else.""";
    static final String SYS_V1_MARKS = """
            You see one window of a desktop application, a tool-library lending desk, as a screenshot on which \
            numbered marks are drawn: each mark is a coloured box around one element with its number in a small label \
            at the box's top-left corner. A list of the marks follows the image. The user names one element of the \
            window. Call select_mark with the number of that element's mark. Call the tool exactly once and write \
            nothing else.""";
    static final String SYS_V2_CUSTOM = """
            You see one window of a desktop application, a tool-library lending desk, as a screenshot of %d x %d \
            pixels. The user names one element of the window. Call click with the pixel coordinates (x from the left \
            edge, y from the top edge of the screenshot) of a point inside that element, preferably its centre. Call \
            the tool exactly once and write nothing else.""";
    static final String SYS_V2_TOOLSET = """
            You are operating one window of a desktop application, a tool-library lending desk, through the computer \
            tools. Screenshots show only that window. The user names one element of the window: take a screenshot, \
            then click that element once with left_click, then stop.""";

    private Grounding() {
    }

    // ------------------------------------------------------------------------------------------------ screens

    /** Drives a fresh desk through the five grounding screens and takes each. */
    static Map<String, Screen> screens(Desk d) throws Exception {
        Map<String, Screen> out = new LinkedHashMap<>();
        out.put("signed-off", Screen.take(d, "signed-off"));
        d.signOn();
        for (String code : List.of("HND-001", "HND-002", "HND-003", "YRD-001", "PWR-001")) {
            d.input("Entry", code);
        }
        out.put("loan", Screen.take(d, "loan"));
        d.input("Entry", "LAD-001");
        out.put("induction", Screen.take(d, "induction"));
        d.answer("Yes");
        d.input("Key.DUE", null);
        out.put("deposit", Screen.take(d, "deposit"));
        d.input("Key.DEPOSIT", null);
        d.input("Key.RETURN", null);
        d.input("Entry", "PWR-001");
        out.put("supervisor", Screen.take(d, "supervisor"));
        Bs12.log("screens taken: %s", out.keySet());
        return out;
    }

    /** No model call: writes what each screen shows the model and the ground truth of each target. */
    static void probe() throws Exception {
        Path dir = Bs12.target().resolve("probe");
        Files.createDirectories(dir);
        try (Desk d = Desk.launch("probe")) {
            Bs12.log("desk up in %d ms on %s", d.launchMs, d.targetJava);
            Map<String, Object> edt = d.value("swing.edtState", Map.of());
            Files.write(dir.resolve("signed-off-frame-as-is.png"), Screen.captureWindowAsIs(d));
            Map<String, Screen> screens = screens(d);
            StringBuilder all = new StringBuilder();
            all.append("targetJava: ").append(d.targetJava).append('\n').append("edtState at the end: ").append(edt)
                    .append('\n');
            for (Screen s : screens.values()) {
                Files.write(dir.resolve(s.name + ".png"), s.png);
                List<Screen.Mark> marks = s.marks();
                Files.write(dir.resolve(s.name + "-marks.png"), Screen.encode(s.drawMarks(marks)));
                StringBuilder sb = new StringBuilder();
                sb.append("screen ").append(s.name).append(' ').append(s.width()).append('x').append(s.height())
                        .append(" sha256=").append(s.sha256).append(" captureMs=").append(s.captureMs).append('\n');
                sb.append("notes: ").append(s.notes).append('\n');
                sb.append("windows: ");
                for (Screen.Win w : s.windows) {
                    sb.append(w.type()).append(' ').append(w.ref()).append(" at ").append(w.dx()).append(',')
                            .append(w.dy()).append(' ').append(w.w()).append('x').append(w.h()).append("; ");
                }
                sb.append("\n--- text snapshot (").append(s.text().length()).append(" chars)\n").append(s.text());
                sb.append("\n--- marks (").append(marks.size()).append(")\n").append(Screen.marksList(marks));
                sb.append("--- nodes\n");
                for (Screen.Node n : s.nodes) {
                    sb.append("  ".repeat(n.depth)).append(n.ref).append(' ').append(n.role).append(' ').append(n.cls)
                            .append(n.id == null ? "" : " id=" + n.id).append(" name=\"").append(Screen.clip(n.name, 40))
                            .append("\" text=\"").append(Screen.clip(n.text, 40)).append("\" ").append(n.r.x).append(',')
                            .append(n.r.y).append(' ').append(n.r.width).append('x').append(n.r.height)
                            .append(n.showing ? "" : " hidden").append('\n');
                }
                sb.append("--- targets\n");
                for (Targets.Target t : Targets.on(s.name)) {
                    try {
                        Targets.Truth tr = Targets.truth(t, s);
                        int cx = tr.r().x + tr.r().width / 2;
                        int cy = tr.r().y + tr.r().height / 2;
                        Screen.Hit h = s.hitTest(cx, cy);
                        sb.append(t.id()).append(' ').append(t.intent()).append(" -> ").append(tr.node().ref)
                                .append(" row=").append(tr.row()).append(" rect=").append(tr.r().x).append(',')
                                .append(tr.r().y).append(' ').append(tr.r().width).append('x').append(tr.r().height)
                                .append(" centre hit=").append(h.node() == null ? "none" : h.node().ref).append(" row=")
                                .append(h.row()).append(h.node() == tr.node() && h.row() == tr.row() ? " OK" : " MISMATCH")
                                .append('\n');
                    } catch (RuntimeException e) {
                        sb.append(t.id()).append(" ERROR ").append(e.getMessage()).append('\n');
                    }
                }
                Bs12.write(dir.resolve(s.name + ".txt"), sb.toString(), false);
                all.append(sb).append("\n\n");
            }
            Bs12.write(dir.resolve("probe.txt"), all.toString(), false);
            Bs12.log("probe written to %s", dir);
        }
    }

    // ------------------------------------------------------------------------------------------------ bodies

    static ObjectNode base(String model, String effort, int maxTokens, String system) {
        ObjectNode b = Bs12.MAPPER.createObjectNode();
        b.put("model", model);
        b.put("max_tokens", maxTokens);
        if (effort != null && !"default".equals(effort)) {
            b.putObject("output_config").put("effort", effort);
        }
        ObjectNode sys = b.putArray("system").addObject();
        sys.put("type", "text");
        sys.put("text", system);
        sys.putObject("cache_control").put("type", "ephemeral");
        return b;
    }

    static ObjectNode image(byte[] png) {
        ObjectNode i = Bs12.MAPPER.createObjectNode();
        i.put("type", "image");
        ObjectNode src = i.putObject("source");
        src.put("type", "base64");
        src.put("media_type", "image/png");
        src.put("data", Base64.getEncoder().encodeToString(png));
        return i;
    }

    static ObjectNode text(String s) {
        ObjectNode t = Bs12.MAPPER.createObjectNode();
        t.put("type", "text");
        t.put("text", s);
        return t;
    }

    static ObjectNode tool(String name, String description, Map<String, Object> properties, List<String> required) {
        ObjectNode t = Bs12.MAPPER.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("input_schema");
        schema.put("type", "object");
        schema.set("properties", Bs12.MAPPER.valueToTree(properties));
        schema.set("required", Bs12.MAPPER.valueToTree(required));
        return t;
    }

    static ObjectNode userMessage(ArrayNode messages, List<ObjectNode> content) {
        ObjectNode m = messages.addObject();
        m.put("role", "user");
        ArrayNode c = m.putArray("content");
        content.forEach(c::add);
        return m;
    }

    /** A screen as the model is shown it in each variant (built once per screen). */
    static final class Shown {
        final Screen screen;
        final String text;
        final List<Screen.Mark> marks;
        final byte[] marked;
        final String marksList;

        Shown(Screen s) throws IOException {
            this.screen = s;
            this.text = s.text();
            this.marks = s.marks();
            this.marked = Screen.encode(s.drawMarks(marks));
            this.marksList = Screen.marksList(marks);
        }

        /** The capture scaled by {@code scale} (bilinear), for the capture-size variants. */
        synchronized byte[] scaled(double scale) throws IOException {
            return scaledCache.computeIfAbsent(scale, k -> {
                int w = (int) Math.round(screen.width() * k);
                int h = (int) Math.round(screen.height() * k);
                java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = out.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(screen.image, 0, 0, w, h, null);
                g.dispose();
                try {
                    return Screen.encode(out);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }

        private final Map<Double, byte[]> scaledCache = new java.util.HashMap<>();
    }

    /** The capture scale of a variant: {@code v2-custom-s75} is 0.75, {@code v2-custom-s50} 0.5, others 1. */
    static double scaleOf(String variant) {
        int i = variant.lastIndexOf("-s");
        return i > 0 && variant.startsWith("v2-custom-s") ? Integer.parseInt(variant.substring(i + 2)) / 100.0 : 1.0;
    }

    static ObjectNode body(String variant, String model, String effort, Shown sh, String intent) {
        String element = "Element: " + intent;
        switch (variant) {
            case "v1-text" -> {
                ObjectNode b = base(model, effort, 4096, SYS_V1_TEXT);
                b.putArray("tools").add(tool("select_component", "Select the element by its ref from the snapshot.",
                        Map.of("ref", Map.of("type", "string", "description", "the ref, as in [ref=...]"),
                               "row", Map.of("type", "integer", "description", "for a table row only: the row number, 1 for the first row")),
                        List.of("ref")));
                userMessage(b.putArray("messages"), List.of(image(sh.screen.png), text("Text snapshot:\n" + sh.text),
                        text(element)));
                return b;
            }
            case "v1-marks" -> {
                ObjectNode b = base(model, effort, 4096, SYS_V1_MARKS);
                b.putArray("tools").add(tool("select_mark", "Select the element by the number of its mark.",
                        Map.of("mark", Map.of("type", "integer", "description", "the mark's number")), List.of("mark")));
                userMessage(b.putArray("messages"), List.of(image(sh.marked), text("Marks:\n" + sh.marksList),
                        text(element)));
                return b;
            }
            case "v1-text-noimage" -> {
                ObjectNode b = base(model, effort, 4096, SYS_V1_TEXT_NOIMAGE);
                b.putArray("tools").add(tool("select_component", "Select the element by its ref from the snapshot.",
                        Map.of("ref", Map.of("type", "string", "description", "the ref, as in [ref=...]"),
                               "row", Map.of("type", "integer", "description", "for a table row only: the row number, 1 for the first row")),
                        List.of("ref")));
                userMessage(b.putArray("messages"), List.of(text("Text snapshot:\n" + sh.text), text(element)));
                return b;
            }
            case "v2-custom-s75", "v2-custom-s50" -> {
                double k = scaleOf(variant);
                int w = (int) Math.round(sh.screen.width() * k);
                int h = (int) Math.round(sh.screen.height() * k);
                ObjectNode b = base(model, effort, 4096, SYS_V2_CUSTOM.formatted(w, h));
                b.putArray("tools").add(tool("click", "Click at a pixel of the screenshot.",
                        Map.of("x", Map.of("type", "integer", "description", "pixels from the left edge"),
                               "y", Map.of("type", "integer", "description", "pixels from the top edge")),
                        List.of("x", "y")));
                try {
                    userMessage(b.putArray("messages"), List.of(image(sh.scaled(k)), text(element)));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return b;
            }
            case "v2-custom" -> {
                ObjectNode b = base(model, effort, 4096, SYS_V2_CUSTOM.formatted(sh.screen.width(), sh.screen.height()));
                b.putArray("tools").add(tool("click", "Click at a pixel of the screenshot.",
                        Map.of("x", Map.of("type", "integer", "description", "pixels from the left edge"),
                               "y", Map.of("type", "integer", "description", "pixels from the top edge")),
                        List.of("x", "y")));
                userMessage(b.putArray("messages"), List.of(image(sh.screen.png), text(element)));
                return b;
            }
            case "v2-toolset" -> {
                ObjectNode b = base(model, effort, 4096, SYS_V2_TOOLSET);
                b.putArray("tools").addObject().put("type", TOOLSET);
                userMessage(b.putArray("messages"), List.of(text(element)));
                return b;
            }
            default -> throw new IllegalArgumentException("unknown variant " + variant);
        }
    }

    // ------------------------------------------------------------------------------------------------ one attempt

    /** One (target, variant, model, effort, rep) attempt: the calls, the answer and whether it is right. */
    static ObjectNode attempt(Model api, String part, String tag, String variant, String model, String effort, int rep,
                              Targets.Target t, Shown sh) throws Exception {
        ObjectNode rec = Bs12.MAPPER.createObjectNode();
        rec.put("kind", "ground");
        rec.put("part", part);
        rec.put("tag", tag);
        rec.put("at", Instant.now().toString());
        rec.put("model", model);
        rec.put("effort", effort);
        rec.put("variant", variant);
        rec.put("screen", sh.screen.name);
        rec.put("screenSha256", sh.screen.sha256);
        rec.put("target", t.id());
        rec.put("targetKind", t.kind());
        rec.put("ambiguous", t.ambiguous());
        rec.put("intent", t.intent());
        rec.put("rep", rep);
        Targets.Truth truth = Targets.truth(t, sh.screen);
        ObjectNode gt = rec.putObject("truth");
        gt.put("ref", truth.node().ref);
        gt.put("row", truth.row());
        gt.put("rect", truth.r().x + "," + truth.r().y + " " + truth.r().width + "x" + truth.r().height);
        ArrayNode calls = rec.putArray("calls");
        ObjectNode body = body(variant, model, effort, sh, t.intent());
        ObjectNode answer = rec.putObject("answer");
        List<String> members = new ArrayList<>();
        Integer px = null;
        Integer py = null;
        String ref = null;
        Integer row = null;
        Integer mark = null;
        String texts = "";
        int turns = 0;
        try {
            for (int turn = 0; turn < 4; turn++) {
                Model.Call c = api.send(body);
                turns++;
                calls.add(c.toJson());
                JsonNode content = c.message().path("content");
                List<JsonNode> uses = new ArrayList<>();
                for (JsonNode block : content) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        uses.add(block);
                    } else if ("text".equals(block.path("type").asText())) {
                        texts += block.path("text").asText() + " ";
                    }
                }
                if (!"v2-toolset".equals(variant)) {
                    if (!uses.isEmpty()) {
                        JsonNode in = uses.get(0).path("input");
                        members.add(uses.get(0).path("name").asText());
                        switch (variant) {
                            case "v1-text", "v1-text-noimage" -> {
                                ref = in.path("ref").asText(null);
                                row = in.hasNonNull("row") ? in.get("row").asInt() : null;
                            }
                            case "v1-marks" -> mark = in.hasNonNull("mark") ? in.get("mark").asInt() : null;
                            default -> {
                                px = in.hasNonNull("x") ? in.get("x").asInt() : null;
                                py = in.hasNonNull("y") ? in.get("y").asInt() : null;
                            }
                        }
                    }
                    break;
                }
                // The computer toolset: answer the calls of this turn until a click arrives.
                if (uses.isEmpty()) {
                    break;
                }
                ArrayNode messages = (ArrayNode) body.get("messages");
                ObjectNode assistant = messages.addObject();
                assistant.put("role", "assistant");
                assistant.set("content", content.deepCopy());
                List<ObjectNode> results = new ArrayList<>();
                for (JsonNode u : uses) {
                    String name = u.path("name").asText();
                    members.add(name);
                    ObjectNode r = Bs12.MAPPER.createObjectNode();
                    r.put("type", "tool_result");
                    r.put("tool_use_id", u.path("id").asText());
                    r.put("toolset_name", "computer");
                    JsonNode coord = u.path("input").path("coordinate");
                    if ("screenshot".equals(name)) {
                        r.putArray("content").add(image(sh.screen.png));
                    } else if ("zoom".equals(name)) {
                        JsonNode reg = u.path("input").path("region");
                        r.putArray("content").add(image(sh.screen.zoom(reg.path(0).asInt(), reg.path(1).asInt(),
                                reg.path(2).asInt(), reg.path(3).asInt())));
                    } else {
                        if (px == null && coord.isArray() && coord.size() == 2 && name.contains("click")) {
                            px = coord.get(0).asInt();
                            py = coord.get(1).asInt();
                            answer.put("member", name);
                        }
                        r.put("content", "OK");
                    }
                    results.add(r);
                }
                if (px != null) {
                    break;
                }
                userMessage(messages, results);
            }
        } catch (Model.BudgetExceeded e) {
            throw e;
        } catch (Exception e) {
            rec.put("error", e.getClass().getSimpleName() + ": " + Screen.clip(String.valueOf(e.getMessage()), 400));
        }
        rec.put("turns", turns);
        rec.set("members", Bs12.MAPPER.valueToTree(members));
        if (!texts.isBlank()) {
            rec.put("text", Screen.clip(texts, 400));
        }
        // Evaluate.
        boolean correct = false;
        boolean partial = false;
        if (variant.startsWith("v1-text") && ref != null) {
            answer.put("ref", ref);
            if (row != null) {
                answer.put("row", row);
            }
            Screen.Node n = sh.screen.byRef(ref);
            answer.put("refKnown", n != null);
            if (n != null) {
                answer.put("role", n.role);
                answer.put("name", Screen.clip(n.label(), 40));
            }
            boolean sameNode = n == truth.node();
            correct = sameNode && (truth.row() < 0 || (row != null && row - 1 == truth.row()));
            partial = sameNode && !correct;
        } else if ("v1-marks".equals(variant) && mark != null) {
            answer.put("mark", mark);
            if (mark >= 1 && mark <= sh.marks.size()) {
                Screen.Mark m = sh.marks.get(mark - 1);
                answer.put("markText", Screen.clip(m.text(), 60));
                correct = m.node() == truth.node() && m.row() == truth.row();
                partial = m.node() == truth.node() && !correct;
            }
        } else if (px != null && py != null) {
            double k = scaleOf(variant);
            if (k != 1.0) {
                answer.put("xScaled", px);
                answer.put("yScaled", py);
                px = (int) Math.round(px / k);
                py = (int) Math.round(py / k);
            }
            answer.put("x", px);
            answer.put("y", py);
            Screen.Hit h = sh.screen.hitTest(px, py);
            answer.put("inside", truth.r().contains(px, py));
            if (h.node() != null) {
                answer.put("hitRef", h.node().ref);
                answer.put("hitRole", h.node().role);
                answer.put("hitName", Screen.clip(h.node().label(), 40));
                answer.put("hitRow", h.row());
            }
            correct = h.node() == truth.node() && (truth.row() < 0 || h.row() == truth.row());
            partial = h.node() == truth.node() && !correct;
            // Distance from the point to the target's box (0 inside), and to its centre.
            java.awt.Rectangle r = truth.r();
            int dx = Math.max(Math.max(r.x - px, 0), px - (r.x + r.width - 1));
            int dy = Math.max(Math.max(r.y - py, 0), py - (r.y + r.height - 1));
            answer.put("distToBox", (int) Math.round(Math.hypot(Math.max(dx, 0), Math.max(dy, 0))));
            answer.put("distToCentre", (int) Math.round(Math.hypot(px - r.getCenterX(), py - r.getCenterY())));
        }
        rec.put("correct", correct);
        rec.put("partial", partial);
        long latency = 0;
        long in = 0;
        long out = 0;
        long cr = 0;
        long cw = 0;
        BigDecimal cost = BigDecimal.ZERO;
        List<String> stops = new ArrayList<>();
        for (JsonNode c : calls) {
            latency += c.path("latencyMs").asLong();
            in += c.path("input").asLong();
            out += c.path("output").asLong();
            cr += c.path("cacheRead").asLong();
            cw += c.path("cacheWrite").asLong();
            cost = cost.add(new BigDecimal(c.path("costUsd").asText("0")));
            stops.add(c.path("stopReason").asText());
        }
        rec.put("latencyMs", latency);
        rec.put("input", in);
        rec.put("output", out);
        rec.put("cacheRead", cr);
        rec.put("cacheWrite", cw);
        rec.put("costUsd", cost.toPlainString());
        rec.set("stopReasons", Bs12.MAPPER.valueToTree(stops));
        return rec;
    }

    // ------------------------------------------------------------------------------------------------ runner

    /**
     * {@code ground --models sonnet,opus,haiku --variants v1-text,... --effort low|default --reps 1 --rep-start 1
     * --targets all|sample|t01,t02 --threads 4 --max-usd 10 --tag main --haiku-variants v1-text,v2-custom}.
     */
    static void run(Map<String, String> o) throws Exception {
        List<String> models = Arrays.stream(o.getOrDefault("models", "sonnet,opus,haiku").split(",")).map(Grounding::model)
                .toList();
        List<String> variants = Arrays.asList(o.getOrDefault("variants", String.join(",", VARIANTS)).split(","));
        List<String> haikuVariants = Arrays.asList(o.getOrDefault("haiku-variants", "v1-text,v2-custom").split(","));
        String effort = o.getOrDefault("effort", "low");
        int reps = Integer.parseInt(o.getOrDefault("reps", "1"));
        int repStart = Integer.parseInt(o.getOrDefault("rep-start", "1"));
        String targetSel = o.getOrDefault("targets", "all");
        int threads = Integer.parseInt(o.getOrDefault("threads", "4"));
        String tag = o.getOrDefault("tag", "main");
        String part = "ground-" + effort;
        BigDecimal maxUsd = new BigDecimal(o.getOrDefault("max-usd", Bs12.HARD_CAP.toPlainString()));
        Model api = new Model(maxUsd);
        Map<String, Shown> shown = new LinkedHashMap<>();
        try (Desk d = Desk.launch("ground-" + tag)) {
            Bs12.log("desk up in %d ms on %s", d.launchMs, d.targetJava);
            for (Screen s : screens(d).values()) {
                shown.put(s.name, new Shown(s));
            }
        }
        List<Targets.Target> targets = Targets.ALL.stream().filter(t -> switch (targetSel) {
            case "all" -> true;
            case "sample" -> t.sample();
            default -> Arrays.asList(targetSel.split(",")).contains(t.id());
        }).toList();
        List<Object[]> jobs = new ArrayList<>();
        for (int rep = repStart; rep < repStart + reps; rep++) {
            for (Targets.Target t : targets) {
                for (String v : variants) {
                    for (String m : models) {
                        if (m.equals(Model.HAIKU) && !haikuVariants.contains(v)) {
                            continue;
                        }
                        jobs.add(new Object[] {t, v, m, rep});
                    }
                }
            }
        }
        Bs12.log("ground: %d attempts (%d targets, variants %s, models %s, effort %s, reps %d from %d), %d threads",
                jobs.size(), targets.size(), variants, models, effort, reps, repStart, threads);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger right = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (Object[] j : jobs) {
            futures.add(pool.submit(() -> {
                if (stop.get()) {
                    return null;
                }
                Targets.Target t = (Targets.Target) j[0];
                String v = (String) j[1];
                String m = (String) j[2];
                int rep = (int) j[3];
                try {
                    ObjectNode rec = attempt(api, part, tag, v, m, effort, rep, t, shown.get(t.screen()));
                    Bs12.record("ground-" + tag, rec);
                    int n = done.incrementAndGet();
                    if (rec.path("correct").asBoolean()) {
                        right.incrementAndGet();
                    }
                    Bs12.log("[%d/%d] %s %s %s rep%d %s -> %s %s (%d ms, USD %s)%s", n, jobs.size(), m, v, t.id(), rep,
                            effort, rec.path("correct").asBoolean() ? "RIGHT" : "wrong", rec.path("answer"),
                            rec.path("latencyMs").asLong(), rec.path("costUsd").asText(),
                            rec.has("error") ? " ERROR " + rec.path("error").asText() : "");
                } catch (Model.BudgetExceeded e) {
                    if (stop.compareAndSet(false, true)) {
                        Bs12.log("BUDGET STOP: %s", e.getMessage());
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        Bs12.log("ground %s done: %d attempts, %d right; spent this run USD %s, total USD %s", tag, done.get(),
                right.get(), api.spentThisRun().toPlainString(), api.committed().toPlainString());
    }

    static String model(String alias) {
        return switch (alias) {
            case "sonnet" -> Model.SONNET;
            case "opus" -> Model.OPUS;
            case "haiku" -> Model.HAIKU;
            default -> alias;
        };
    }

    // ------------------------------------------------------------------------------------------------ counts

    /** Free token counts: a capture alone, and each variant's request on one screen, per model. */
    static void count(Map<String, String> o) throws Exception {
        Model api = new Model(Bs12.HARD_CAP);
        Map<String, Shown> shown = new LinkedHashMap<>();
        try (Desk d = Desk.launch("count")) {
            for (Screen s : screens(d).values()) {
                shown.put(s.name, new Shown(s));
            }
        }
        for (String model : List.of(Model.SONNET, Model.OPUS, Model.HAIKU)) {
            ObjectNode rec = Bs12.MAPPER.createObjectNode();
            rec.put("kind", "count");
            rec.put("model", model);
            rec.put("costUsd", "0");
            ObjectNode b = Bs12.MAPPER.createObjectNode();
            b.put("model", model);
            userMessage(b.putArray("messages"), List.of(text("x")));
            long empty = api.countTokens(b);
            rec.put("textX", empty);
            Shown loan = shown.get("loan");
            ObjectNode bi = Bs12.MAPPER.createObjectNode();
            bi.put("model", model);
            userMessage(bi.putArray("messages"), List.of(image(loan.screen.png), text("x")));
            long withImage = api.countTokens(bi);
            rec.put("image" + loan.screen.width() + "x" + loan.screen.height(), withImage - empty);
            ObjectNode bt = Bs12.MAPPER.createObjectNode();
            bt.put("model", model);
            bt.putArray("tools").addObject().put("type", TOOLSET);
            userMessage(bt.putArray("messages"), List.of(text("x")));
            rec.put("toolsetOverhead", api.countTokens(bt) - empty);
            ObjectNode bz = bt.deepCopy();
            ((ObjectNode) bz.get("tools").get(0)).putObject("configs").putObject("zoom").put("enabled", false);
            rec.put("toolsetOverheadNoZoom", api.countTokens(bz) - empty);
            ObjectNode texts = rec.putObject("textSnapshot");
            ObjectNode marksTok = rec.putObject("marksList");
            for (Shown sh : shown.values()) {
                ObjectNode bs = Bs12.MAPPER.createObjectNode();
                bs.put("model", model);
                userMessage(bs.putArray("messages"), List.of(text("Text snapshot:\n" + sh.text)));
                texts.put(sh.screen.name, api.countTokens(bs) - empty);
                ObjectNode bm = Bs12.MAPPER.createObjectNode();
                bm.put("model", model);
                userMessage(bm.putArray("messages"), List.of(text("Marks:\n" + sh.marksList)));
                marksTok.put(sh.screen.name, api.countTokens(bm) - empty);
            }
            ObjectNode perVariant = rec.putObject("requestOnLoanScreen");
            for (String v : VARIANTS) {
                perVariant.put(v, api.countTokens(body(v, model, "low", loan, "the item entry field")));
            }
            Bs12.record("count", rec);
            Bs12.log("count %s: %s", model, rec);
        }
    }
}
