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

import ai.skillroy.testjava.controller.AgentClient;
import ai.skillroy.testjava.controller.AgentHome;
import ai.skillroy.testjava.controller.Endpoint;
import ai.skillroy.testjava.controller.LaunchedTarget;
import ai.skillroy.testjava.controller.SecureFiles;
import ai.skillroy.testjava.controller.TargetLauncher;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.RpcResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@code run}: the within-environment suite of one environment. Launches the sample lending desk (seed A) with the
 * built agent home plus the sample's agent module through the real {@link TargetLauncher}, connects the real
 * {@link AgentClient}, and calls {@code swing.capture} over the control channel; then the restarts, then the toy. Each
 * capture is decoded and hashed (ARGB raster, and the PNG bytes as sent). Writes {@code captures.csv},
 * {@code summary.json}, {@code env.json}, the first PNG and the component tree of every state and every distinct
 * variant into the output directory. Every wait is bounded; every process it starts is ended.
 */
final class Runner {

    static final String DESK_MAIN = "ai.skillroy.testjava.samples.lending.LendingApp";
    static final String TOY_MAIN = "ai.skillroy.testjava.spikes.bs11.toy.CaptureToy";
    static final String DESK_TITLE = "Tool Library Lending Desk";
    static final String TOY_TITLE = "BS11 toy";
    static final Duration AGENT_TIMEOUT = Duration.ofSeconds(240);
    static final Duration EVENT_TIMEOUT = Duration.ofSeconds(60);
    /** Seed A fixtures: staff 103 signs on with the code alone; HND-001 and LAD-001 (induction required). */
    static final String AUTO_STAFF = "103";
    static final String ITEM_BARCODE = "5000000000048";
    static final String INDUCTION_BARCODE = "5000000000086";

    private final Path out;
    private final String label;
    private final Path java;
    private final List<String> jvmArgs;
    private final int captures;
    private final int launches;
    private final Path dist;
    private final AgentHome deskHome;
    private final AgentHome plainHome;
    private final Path appJar;
    private final String moveTool;
    private final Path discoveryRoot;
    private int discoveryCount;

    private final Map<String, State> states = new LinkedHashMap<>();
    private final List<Map<String, Object>> relations = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final StringBuilder csv = new StringBuilder("state,index,width,height,rasterSha256,pngSha256,pngBytes,ms\n");

    /** What was seen of one state. */
    static final class State {
        final String name;
        final String window;
        int width;
        int height;
        int n;
        long ms;
        final Map<String, Integer> raster = new LinkedHashMap<>();
        final Set<String> png = new LinkedHashSet<>();
        String first;

        State(String name, String window) {
            this.name = name;
            this.window = window;
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("window", window);
            m.put("n", n);
            m.put("width", width);
            m.put("height", height);
            m.put("distinctRaster", raster.size());
            m.put("distinctPng", png.size());
            m.put("firstRaster", first);
            m.put("rasterCounts", raster);
            m.put("meanMs", n == 0 ? 0 : ms / n);
            return m;
        }
    }

    Runner(Map<String, List<String>> opts) {
        this.out = Path.of(one(opts, "out")).toAbsolutePath().normalize();
        this.label = one(opts, "label");
        this.java = Path.of(one(opts, "java"));
        this.jvmArgs = opts.getOrDefault("jvm-arg", List.of());
        this.captures = Integer.parseInt(opts.getOrDefault("captures", List.of("100")).getFirst());
        this.launches = Integer.parseInt(opts.getOrDefault("launches", List.of("5")).getFirst());
        this.dist = Path.of(one(opts, "dist")).toAbsolutePath().normalize();
        this.deskHome = AgentHome.of(Path.of(one(opts, "desk-home")));
        this.plainHome = AgentHome.of(Path.of(one(opts, "plain-home")));
        this.appJar = Path.of(one(opts, "app-jar"));
        this.moveTool = opts.getOrDefault("move-tool", List.of("none")).getFirst();
        this.discoveryRoot = Path.of(System.getProperty("java.io.tmpdir"), "bs11-" + ProcessHandle.current().pid());
    }

    static String one(Map<String, List<String>> opts, String key) {
        List<String> v = opts.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return v.getFirst();
    }

    void run() throws Exception {
        Files.createDirectories(out.resolve("png"));
        Files.createDirectories(out.resolve("tree"));
        Files.createDirectories(out.resolve("logs"));
        writeEnv();
        long t0 = System.nanoTime();
        deskFirstLaunch();
        for (int i = 2; i <= launches; i++) {
            deskRestart(i);
        }
        toy();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("label", label);
        summary.put("seconds", (System.nanoTime() - t0) / 1_000_000_000L);
        Map<String, Object> s = new LinkedHashMap<>();
        states.forEach((k, v) -> s.put(k, v.json()));
        summary.put("states", s);
        summary.put("relations", relations);
        summary.put("notes", notes);
        Files.writeString(out.resolve("summary.json"), Json.writePretty(summary), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("captures.csv"), csv, StandardCharsets.UTF_8);
        System.out.println("BS11 run " + label + ": " + states.size() + " states in " + summary.get("seconds") + " s");
        for (State st : states.values()) {
            System.out.printf("  %-34s n=%-4d %dx%d distinct raster=%d png=%d%n", st.name, st.n, st.width, st.height,
                    st.raster.size(), st.png.size());
        }
        for (Map<String, Object> r : relations) {
            System.out.println("  " + r.get("a") + " vs " + r.get("b") + ": " + (Boolean.TRUE.equals(r.get("same"))
                    ? "same" : "DIFFERENT") + " (variants " + r.get("variantsA") + "/" + r.get("variantsB")
                    + ", first " + (Boolean.TRUE.equals(r.get("sameFirst")) ? "same" : "different") + ")");
        }
        for (String n : notes) {
            System.out.println("  note: " + n);
        }
    }

    // ------------------------------------------------------------------------------------------------- the desk

    private void deskFirstLaunch() throws Exception {
        try (Session s = launch("desk-1", deskHome, DESK_MAIN, List.of(appJar, dist.resolve("lib/hsqldb-jdk8.jar")),
                List.of("-Dlending.seed=A"))) {
            AgentClient c = s.client;
            settle(c);
            String main = windowRef(c, DESK_TITLE);
            Map<String, Object> info = value(c.call("agent.info", Map.of()));
            Files.writeString(out.resolve("agent-info.json"), Json.writePretty(info), StandardCharsets.UTF_8);
            series(c, "desk-signed-off", main, captures);
            notes.add("focus at start: " + focusSummary(c, main));

            // A field typed and cleared again.
            type(c, "entry", "12345", false);
            settle(c);
            series(c, "desk-typed", main, 10);
            type(c, "entry", "12346", false);
            settle(c);
            series(c, "desk-typed-1char", main, 10);
            type(c, "entry", "", false);
            settle(c);
            series(c, "desk-signed-off-after-type", main, 10);
            relate("desk-signed-off", "desk-signed-off-after-type");

            // A dialog opened and closed.
            RpcResult r = type(c, "entry", "999", true);
            awaitEvent(c, "window.opened", r.seqBefore(), null);
            settle(c);
            String dialog = otherWindowRef(c, main);
            series(c, "dlg-unknown-staff", dialog, 20);
            dismiss(c, dialog, "OK");
            settle(c);
            series(c, "desk-signed-off-after-dialog", main, 10);
            relate("desk-signed-off", "desk-signed-off-after-dialog");

            // Covered by another process's window, then uncovered.
            Process cover = startCover();
            try {
                settle(c);
                notes.add("focus while covered: " + focusSummary(c, main));
                series(c, "desk-signed-off-covered", main, 10);
            } finally {
                stop(cover);
            }
            settle(c);
            // Without a window manager nothing gives the focus back: the full series of an unfocused window (no caret).
            notes.add("focus after uncovering: " + focusSummary(c, main));
            series(c, "desk-signed-off-uncovered", main, captures);
            relate("desk-signed-off", "desk-signed-off-covered");
            relate("desk-signed-off", "desk-signed-off-uncovered");

            // Moved, then moved back.
            if ("xdotool".equals(moveTool)) {
                Map<String, Object> before = windowBounds(c, main);
                runTool(List.of("xdotool", "search", "--name", "^" + DESK_TITLE + "$", "windowmove", "137", "91"));
                awaitMoved(c, main, before);
                settle(c);
                notes.add("bounds before move " + before + ", after " + windowBounds(c, main));
                series(c, "desk-signed-off-moved", main, 10);
                Map<String, Object> moved = windowBounds(c, main);
                runTool(List.of("xdotool", "search", "--name", "^" + DESK_TITLE + "$", "windowmove", "0", "0"));
                awaitMoved(c, main, moved);
                settle(c);
                series(c, "desk-signed-off-moved-back", main, 5);
                relate("desk-signed-off", "desk-signed-off-moved");
                relate("desk-signed-off", "desk-signed-off-moved-back");
            } else {
                notes.add("move: skipped (no move tool in this environment)");
            }

            // Signed on with a loan of one item: the table, the totals and the status line filled.
            signOnWithItem(c);
            series(c, "desk-loan", main, 20);

            // The induction dialog (Yes/No), answered No: back to the same loan.
            r = type(c, "entry", INDUCTION_BARCODE, true);
            awaitEvent(c, "window.opened", r.seqBefore(), null);
            settle(c);
            dialog = otherWindowRef(c, main);
            series(c, "dlg-induction", dialog, 20);
            r = dismiss(c, dialog, "No");
            awaitEvent(c, "input.requested", r.seqBefore(), Map.of("state", "LOAN"));
            settle(c);
            series(c, "desk-loan-after-dialog", main, 10);
            relate("desk-loan", "desk-loan-after-dialog");
        }
    }

    private void deskRestart(int i) throws Exception {
        try (Session s = launch("desk-" + i, deskHome, DESK_MAIN, List.of(appJar, dist.resolve("lib/hsqldb-jdk8.jar")),
                List.of("-Dlending.seed=A"))) {
            AgentClient c = s.client;
            settle(c);
            String main = windowRef(c, DESK_TITLE);
            series(c, "desk-signed-off@launch" + i, main, 10);
            relate("desk-signed-off", "desk-signed-off@launch" + i);
            signOnWithItem(c);
            series(c, "desk-loan@launch" + i, main, 5);
            relate("desk-loan", "desk-loan@launch" + i);
        }
    }

    private void signOnWithItem(AgentClient c) throws Exception {
        RpcResult r = type(c, "entry", AUTO_STAFF, true);
        awaitEvent(c, "input.requested", r.seqBefore(), Map.of("state", "IDLE"));
        r = type(c, "entry", ITEM_BARCODE, true);
        awaitEvent(c, "input.requested", r.seqBefore(), Map.of("state", "LOAN"));
        settle(c);
    }

    // -------------------------------------------------------------------------------------------------- the toy

    private void toy() throws Exception {
        try (Session s = launch("toy", plainHome, TOY_MAIN, List.of(dist.resolve("bs11-toy.jar")), List.of())) {
            AgentClient c = s.client;
            // No readiness source: the swing module turns OK once the toy has started AWT, and its window tap then
            // reports the window already showing as window.opened.
            awaitEvent(c, "window.opened", 0, null);
            settle(c);
            String main = windowRef(c, TOY_TITLE);
            series(c, "toy-base", main, 20);
            for (String change : List.of("label", "disable", "colour", "tint")) {
                type(c, "command", change, true);
                settle(c);
                series(c, "toy-" + change, main, 5);
                type(c, "command", "reset", true);
                settle(c);
                series(c, "toy-reset-after-" + change, main, 3);
                relate("toy-base", "toy-reset-after-" + change);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ captures

    private void series(AgentClient c, String name, String ref, int n) throws Exception {
        State st = states.computeIfAbsent(name, k -> new State(k, ref));
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            Map<String, Object> v = value(c.call("swing.capture", Map.of("target", ref)));
            long ms = (System.nanoTime() - t) / 1_000_000L;
            byte[] png = Base64.getDecoder().decode(String.valueOf(v.get("base64")));
            Pixels px = Pixels.decode(png);
            String raster = px.sha256();
            String pngSha = Pixels.sha256(png);
            st.width = px.width;
            st.height = px.height;
            st.n++;
            st.ms += ms;
            boolean newVariant = !st.raster.containsKey(raster);
            st.raster.merge(raster, 1, Integer::sum);
            st.png.add(pngSha);
            if (st.first == null) {
                st.first = raster;
            }
            if (newVariant) {
                int variant = st.raster.size();
                String file = variant == 1 ? name : name + ".v" + variant;
                Files.write(out.resolve("png").resolve(file + ".png"), png);
                Map<String, Object> tree = value(c.call("swing.snapshot", Map.of("root", ref, "maxDepth", 40)));
                Files.writeString(out.resolve("tree").resolve(file + ".json"), Json.writePretty(tree),
                        StandardCharsets.UTF_8);
            }
            csv.append(name).append(',').append(i).append(',').append(px.width).append(',').append(px.height)
                    .append(',').append(raster).append(',').append(pngSha).append(',').append(png.length).append(',')
                    .append(ms).append('\n');
        }
    }

    private void relate(String a, String b) {
        State sa = states.get(a);
        State sb = states.get(b);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", a);
        m.put("b", b);
        boolean ok = sa != null && sb != null && sa.first != null && sb.first != null;
        // "same": every variant b showed is one a showed too (a caret blink gives a state two variants).
        m.put("same", ok && sa.raster.keySet().containsAll(sb.raster.keySet()));
        m.put("sameFirst", ok && sa.first.equals(sb.first));
        m.put("variantsA", ok ? sa.raster.size() : 0);
        m.put("variantsB", ok ? sb.raster.size() : 0);
        relations.add(m);
    }

    // -------------------------------------------------------------------------------------------- agent calls

    private static Map<String, Object> value(RpcResult r) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) r.value();
        return m;
    }

    private static void settle(AgentClient c) throws Exception {
        c.call("swing.settle", Map.of("timeoutMs", 10000, "quietMs", 300));
    }

    private static RpcResult type(AgentClient c, String name, String text, boolean enter) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("by", Map.of("name", name));
        p.put("text", text);
        p.put("clear", true);
        p.put("enter", enter);
        return c.call("swing.type", p);
    }

    private static RpcResult dismiss(AgentClient c, String dialog, String button) throws Exception {
        RpcResult r = c.call("swing.dismiss", Map.of("dialog", dialog, "button", button));
        awaitEvent(c, "window.closed", r.seqBefore(), null);
        return r;
    }

    private static Event awaitEvent(AgentClient c, String topic, long since, Map<String, Object> match)
            throws Exception {
        Event e = c.await(topic, since, match, EVENT_TIMEOUT);
        if (e == null) {
            throw new IllegalStateException("no " + topic + " " + match + " within " + EVENT_TIMEOUT);
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> windows(AgentClient c) throws Exception {
        return (List<Map<String, Object>>) value(c.call("swing.windows", Map.of())).get("windows");
    }

    private static String windowRef(AgentClient c, String title) throws Exception {
        for (Map<String, Object> w : windows(c)) {
            if (title.equals(w.get("title")) && Boolean.TRUE.equals(w.get("visible"))) {
                return String.valueOf(w.get("ref"));
            }
        }
        throw new IllegalStateException("no showing window titled '" + title + "': " + windows(c));
    }

    private static String otherWindowRef(AgentClient c, String main) throws Exception {
        for (Map<String, Object> w : windows(c)) {
            if (!main.equals(w.get("ref")) && Boolean.TRUE.equals(w.get("visible"))) {
                return String.valueOf(w.get("ref"));
            }
        }
        throw new IllegalStateException("no second showing window: " + windows(c));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> windowBounds(AgentClient c, String ref) throws Exception {
        for (Map<String, Object> w : windows(c)) {
            if (ref.equals(w.get("ref"))) {
                return (Map<String, Object>) w.get("bounds");
            }
        }
        return Map.of();
    }

    /** Waits (bounded, by re-reading swing.windows after each settle) until the window's bounds changed. */
    private void awaitMoved(AgentClient c, String ref, Map<String, Object> before) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            settle(c);
            if (!windowBounds(c, ref).equals(before)) {
                return;
            }
        }
        notes.add("move: the window's bounds did not change within 20 s (" + before + ")");
    }

    @SuppressWarnings("unchecked")
    private static String focusSummary(AgentClient c, String main) throws Exception {
        Map<String, Object> tree = value(c.call("swing.snapshot", Map.of("root", main, "maxDepth", 40)));
        List<String> focused = new ArrayList<>();
        for (Object w : (List<Object>) tree.get("windows")) {
            collectFocused((Map<String, Object>) w, focused);
        }
        Map<String, Object> edt = value(c.call("swing.edtState", Map.of()));
        return "focused=" + focused + " focusOwner=" + edt.get("focusOwner");
    }

    @SuppressWarnings("unchecked")
    private static void collectFocused(Map<String, Object> node, List<String> out) {
        if (Boolean.TRUE.equals(node.get("focused"))) {
            out.add(node.get("class") + "[" + node.get("name") + "]");
        }
        Object kids = node.get("children");
        if (kids instanceof List<?> list) {
            for (Object k : list) {
                collectFocused((Map<String, Object>) k, out);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- processes

    /** One target process with its connected client; closing ends both. */
    static final class Session implements AutoCloseable {
        final LaunchedTarget target;
        final AgentClient client;

        Session(LaunchedTarget target, AgentClient client) {
            this.target = target;
            this.client = client;
        }

        @Override
        public void close() {
            try {
                client.close();
            } finally {
                target.close();
            }
        }
    }

    private Session launch(String what, AgentHome home, String mainClass, List<Path> classPath,
                           List<String> extraJvmArgs) throws Exception {
        Files.createDirectories(discoveryRoot);
        Path dir = SecureFiles.ensureOwnerOnlyDirectory(discoveryRoot.resolve(what + "-" + (++discoveryCount)),
                "the BS11 discovery directory");
        TargetLauncher.Builder b = TargetLauncher.java(java).mainClass(mainClass).discoveryDir(dir)
                .inheritOutputTo(out.resolve("logs").resolve(what + ".log"));
        for (String a : jvmArgs) {
            b.jvmArg(a);
        }
        for (String a : extraJvmArgs) {
            b.jvmArg(a);
        }
        b.javaAgent(home, Map.of("discoveryDir", dir.toString()));
        b.classPath(classPath);
        LaunchedTarget t = b.start();
        AgentClient c = null;
        try {
            Endpoint e = t.awaitAgent(AGENT_TIMEOUT);
            c = AgentClient.connect(e);
            Event ready = c.await("target.ready", 0, Map.of("ready", true), AGENT_TIMEOUT);
            if (ready == null) {
                throw new IllegalStateException(what + ": no target.ready within " + AGENT_TIMEOUT);
            }
            return new Session(t, c);
        } catch (Exception | Error e) {
            if (c != null) {
                c.close();
            }
            t.close();
            throw e;
        }
    }

    private Process startCover() throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java.toString(), "-Dtoy.role=cover", "-Dtoy.x=100", "-Dtoy.y=80",
                "-Dtoy.w=800", "-Dtoy.h=600", "-cp", dist.resolve("bs11-toy.jar").toString(), TOY_MAIN));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        CompletableFuture<Boolean> shown = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("BS11-COVER-SHOWN")) {
                        return true;
                    }
                }
            } catch (IOException e) {
                return false;
            }
            return false;
        });
        try {
            if (!shown.get(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the covering window ended before it showed");
            }
        } catch (Exception e) {
            stop(p);
            throw e;
        }
        return p;
    }

    private static void stop(Process p) throws InterruptedException {
        p.destroy();
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String runTool(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        CompletableFuture<String> text = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "unreadable: " + e;
            }
        });
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException(cmd + " did not end within 30 s");
        }
        return text.get(5, TimeUnit.SECONDS).strip();
    }

    private void writeEnv() throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("label", label);
        env.put("harnessJava", System.getProperty("java.runtime.version"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        env.put("targetJava", java.toString());
        env.put("targetJavaVersion", safeTool(List.of(java.toString(), "-version")));
        env.put("jvmArgs", jvmArgs);
        env.put("display", System.getenv("DISPLAY"));
        env.put("xvfbScreen", System.getenv("BS11_XVFB_SCREEN"));
        env.put("image", System.getenv("BS11_IMAGE"));
        env.put("fcMatch", safeTool(List.of("sh", "-c", "command -v fc-match >/dev/null && { fc-match sans; "
                + "fc-match sans:bold; fc-match serif; fc-match monospace; } || echo 'no fc-match'")));
        env.put("freetype", safeTool(List.of("sh", "-c", "command -v dpkg-query >/dev/null && dpkg-query -W "
                + "'libfreetype*' 'fontconfig' 'fonts-*' 2>/dev/null || echo 'no dpkg'")));
        Files.writeString(out.resolve("env.json"), Json.writePretty(env), StandardCharsets.UTF_8);
    }

    private static String safeTool(List<String> cmd) {
        try {
            return runTool(cmd);
        } catch (Exception e) {
            return "failed: " + e;
        }
    }
}
