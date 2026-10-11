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

package ai.skillroy.testjava.vision;

import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.scenario.VisionBridge;
import ai.skillroy.testjava.scenario.image.ImageCompare;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The vision module's fake agent (phase7-contracts.md 6.1, 13.1 item 8): the executor tests' fake agent (through
 * {@link VisionBridge}) answering {@code swing.capture} with canned captures from this module's PNG fixtures and their
 * geometry, {@code swing.hitTest} from a table of components on the fixture, {@code swing.rendering},
 * {@code swing.edtState}'s focus owner, and recording the acting primitives. Components are kept in their window's
 * coordinates and hit-tested in screen coordinates through each capture's own origin: the frame {@code h1} lies at
 * screen (100, 50), 320 x 240, no insets; with the modal dialog {@code d1} shown at screen (60, 110), 160 x 100, the
 * composite grows to 360 x 240 with its origin at (60, 50) (the dialog sticks out to the left), so that the same image
 * point names another screen point on the two captures (what the stale-capture rule exists for).
 */
final class FakeVisionAgent {

    /** A component of the fixture: its rectangle in image coordinates (= window coordinates of {@code h1}). */
    record Comp(String ref, String window, Capture.Rect rect, String role, String name, String cls,
                List<Map<String, Object>> locators, boolean fires, boolean masked, String surface, String browser,
                Map<String, Capture.Rect> regions, int rowHeight) {
    }

    static final int ORIGIN_X = 100;
    static final int ORIGIN_Y = 50;
    static final int DIALOG_X = 60;
    static final int DIALOG_Y = 110;
    /** Each capture's origin in screen coordinates, by its id. */
    final Map<String, int[]> origins = new LinkedHashMap<>();
    static final String RENDER_KEY = "0123456789abcdef";

    final VisionBridge bridge;
    final byte[] desk = png("desk.png");
    final byte[] deskDialog = png("desk-dialog.png");
    final byte[] page = png("page.png");
    final byte[] crop = png("crop.png");
    boolean dialog;
    String focusOwner = "h4";
    boolean webRedacted;
    /** The browser surface is one a pack's mask names: its hit-test answers {@code masked: true} (L2-F2). */
    boolean webMasked;
    /** The frame's insets {left, top}: a {@code WINDOW} capture's {@code origin} (L1-F4). */
    int[] insets = {0, 0};
    int captures;
    final List<Comp> comps = new ArrayList<>();
    final List<Comp> dialogComps = new ArrayList<>();

    FakeVisionAgent(Path dir) {
        this.bridge = new VisionBridge(dir);
        comps.add(new Comp("h1", "h1", new Capture.Rect(0, 0, 320, 240), "window", "Desk", "javax.swing.JFrame",
                List.of(Map.of("name", "Desk", "role", "window")), false, false, "SWING", null, Map.of(), 0));
        comps.add(new Comp("h2", "h1", new Capture.Rect(20, 20, 60, 30), "button", "DUE", "lending.IconKey",
                List.of(Map.of("action", "DUE"), Map.of("class", "IconKey", "index", 0L)), true, false, "SWING", null,
                Map.of(), 0));
        comps.add(new Comp("h3", "h1", new Capture.Rect(100, 20, 100, 30), "password", null,
                "javax.swing.JPasswordField", List.of(Map.of("id", "pin")), false, true, "SWING", null, Map.of(), 0));
        comps.add(new Comp("h4", "h1", new Capture.Rect(20, 70, 180, 24), "textbox", "entry", "lending.EntryField",
                List.of(Map.of("id", "entry")), false, false, "SWING", null, Map.of(), 0));
        comps.add(new Comp("h5", "h1", new Capture.Rect(20, 110, 280, 90), "table", "loans", "javax.swing.JTable",
                List.of(Map.of("id", "loans")), false, false, "SWING", null, Map.of(), 30));
        Map<String, Capture.Rect> keys = new LinkedHashMap<>();
        keys.put("key:1", new Capture.Rect(0, 0, 40, 40));
        keys.put("key:2", new Capture.Rect(40, 0, 40, 40));
        keys.put("key:3", new Capture.Rect(0, 40, 40, 40));
        keys.put("key:4", new Capture.Rect(40, 40, 40, 40));
        comps.add(new Comp("h6", "h1", new Capture.Rect(220, 20, 80, 80), "panel", "numberPad", "lending.NumberPad",
                List.of(Map.of("id", "numberPad")), false, false, "SWING", null, keys, 0));
        comps.add(new Comp("h9", "h1", new Capture.Rect(240, 210, 60, 20), "label", null, "lending.Strip", List.of(),
                false, false, "SWING", null, Map.of(), 0));
        comps.add(new Comp("h7", "h1", new Capture.Rect(20, 205, 100, 30), "panel", null, "lending.BrowserPanel",
                List.of(), false, false, "WEB", "b1", Map.of(), 0));
        // A composite button: a label is drawn at its centre (what the target form's deepest names), L1-F5.
        comps.add(new Comp("h10", "h1", new Capture.Rect(125, 205, 100, 30), "button", "Renew", "lending.RoundKey",
                List.of(Map.of("name", "Renew", "role", "button")), false, false, "SWING", null, Map.of(), 0));
        dialogComps.add(new Comp("d1", "d1", new Capture.Rect(0, 0, 160, 100), "dialog", "Question",
                "javax.swing.JDialog", List.of(Map.of("name", "Question", "role", "dialog")), false, false, "SWING",
                null, Map.of(), 0));
        dialogComps.add(new Comp("h8", "d1", new Capture.Rect(50, 60, 60, 25), "button", "OK",
                "javax.swing.JButton", List.of(Map.of("name", "OK", "role", "button")), false, false, "SWING", null,
                Map.of(), 0));
        bridge.capability("swing.capture.screen", CapabilityState.OK, null)
                .capability("swing.capture.paint", CapabilityState.OK, null)
                .capability("swing.hittest", CapabilityState.OK, null)
                .capability("swing.pointer.routed", CapabilityState.OK, null);
        for (String read : List.of("swing.capture", "swing.hitTest", "swing.rendering", "swing.edtState",
                "swing.settle", "web.bitmap", "web.hitTest", "swing.snapshot")) {
            bridge.describe(read, false);
        }
        for (String act : List.of("swing.click", "swing.type", "swing.key", "swing.pointer", "web.dom.click",
                "web.pointer")) {
            bridge.describe(act, true);
        }
        bridge.on("swing.capture", this::capture);
        bridge.on("swing.hitTest", this::hitTest);
        bridge.on("swing.edtState", params -> focusOwner == null ? Map.of("dispatchThreadAlive", true)
                : Map.of("dispatchThreadAlive", true, "focusOwner", focusOwner));
        bridge.on("swing.rendering", params -> rendering());
        for (String act : List.of("swing.click", "swing.key", "swing.pointer", "web.dom.click", "web.pointer")) {
            bridge.on(act, params -> Map.of("fired", true, "received", String.valueOf(params.get("target"))));
        }
        bridge.on("swing.type", params -> Map.of("typed", (long) String.valueOf(params.get("text")).length()));
        bridge.on("web.bitmap", params -> Map.of("format", "png", "width", 100L, "height", 30L, "base64",
                Base64.getEncoder().encodeToString(page), "source", "PROTOCOL"));
        bridge.on("web.hitTest", params -> Map.of("browser", "b1", "unique", true, "element", Map.of("role", "button",
                "name", "Borrow", "tag", "BUTTON"), "locators", List.of(Map.of("role", "button", "name", "Borrow"))));
    }

    /** A window's screen position. */
    static int[] windowAt(String window) {
        return "d1".equals(window) ? new int[] {DIALOG_X, DIALOG_Y} : new int[] {ORIGIN_X, ORIGIN_Y};
    }

    /** A component's rectangle in screen coordinates. */
    static Capture.Rect screen(Comp c) {
        int[] w = windowAt(c.window());
        return new Capture.Rect(c.rect().x() + w[0], c.rect().y() + w[1], c.rect().w(), c.rect().h());
    }

    /** The current composite's origin. */
    int[] origin() {
        return dialog ? new int[] {DIALOG_X, ORIGIN_Y} : new int[] {ORIGIN_X, ORIGIN_Y};
    }

    /** A screen rectangle in the current composite's image coordinates. */
    Capture.Rect image(Capture.Rect screen) {
        int[] o = origin();
        return new Capture.Rect(screen.x() - o[0], screen.y() - o[1], screen.w(), screen.h());
    }

    private static byte[] png(String name) {
        try (InputStream in = FakeVisionAgent.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hash(byte[] png) {
        try {
            return ImageCompare.rasterHash(ImageCompare.decodePng(png));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The marks of the screen, in reading order (the agent's rule; a masked component carries none). */
    List<Map<String, Object>> marks() {
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        if (dialog) {
            out.add(mark(++n, "h8", image(screen(dialogComps.get(1))), "button OK", "button", null, null));
        }
        out.add(mark(++n, "h2", image(screen(comps.get(1))), "button DUE", "button", null, null));
        out.add(mark(++n, "h4", image(screen(comps.get(3))), "textbox entry", "textbox", null, null));
        for (int row = 0; row < 3; row++) {
            Capture.Rect table = screen(comps.get(4));
            out.add(mark(++n, "h5", image(new Capture.Rect(table.x(), table.y() + 30 * row, table.w(), 30)),
                    "Garden spade " + row, "table", (long) row, null));
        }
        Capture.Rect pad = screen(comps.get(5));
        for (Map.Entry<String, Capture.Rect> k : comps.get(5).regions().entrySet()) {
            Capture.Rect r = k.getValue();
            out.add(mark(++n, "h6", image(new Capture.Rect(pad.x() + r.x(), pad.y() + r.y(), r.w(), r.h())), null,
                    "panel", null, k.getKey()));
        }
        return out;
    }

    private static Map<String, Object> mark(int n, String ref, Capture.Rect rect, String label, String role, Long row,
                                            String region) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mark", (long) n);
        m.put("ref", ref);
        m.put("rect", rect.json());
        if (label != null) {
            m.put("label", label);
        }
        m.put("role", role);
        if (row != null) {
            m.put("row", row);
        }
        if (region != null) {
            m.put("region", region);
        }
        return m;
    }

    private Object capture(Map<String, Object> params) {
        captures++;
        String scope = String.valueOf(params.getOrDefault("scope", "WINDOW"));
        Map<String, Object> r = new LinkedHashMap<>();
        if ("REGION".equals(scope)) {
            Capture.Rect region = Capture.Rect.of(params.get("region"));
            r.put("format", "png");
            r.put("width", 64L);
            r.put("height", 64L);
            r.put("base64", Base64.getEncoder().encodeToString(crop));
            r.put("captureId", "cap-" + captures);
            r.put("seq", bridge.lastSeq());
            r.put("mode", "PAINT");
            r.put("scope", "REGION");
            r.put("space", "WINDOW");
            r.put("window", params.get("target"));
            r.put("origin", Map.of("x", (long) (region == null ? 0 : region.x()), "y", (long) (region == null ? 0
                    : region.y())));
            r.put("scale", 1.0);
            r.put("windows", List.of(Map.of("ref", String.valueOf(params.get("target")), "rect", Map.of("x", 0L,
                    "y", 0L, "w", 64L, "h", 64L))));
            r.put("redacted", List.of());
            r.put("carets", List.of());
            r.put("focus", Map.of("ownerLocator", Map.of("id", "entry"), "active", true));
            r.put("hash", hash(crop));
            return r;
        }
        if ("WINDOW".equals(scope) && params.get("target") != null) {
            // A window's own capture: the root pane's paint, its origin the window's insets (3.1).
            r.put("format", "png");
            r.put("width", 64L);
            r.put("height", 64L);
            r.put("base64", Base64.getEncoder().encodeToString(crop));
            r.put("captureId", "cap-" + captures);
            r.put("seq", bridge.lastSeq());
            r.put("mode", "PAINT");
            r.put("scope", "WINDOW");
            r.put("space", "WINDOW");
            r.put("window", params.get("target"));
            r.put("origin", Map.of("x", (long) insets[0], "y", (long) insets[1]));
            r.put("scale", params.getOrDefault("scale", 1.0));
            r.put("windows", List.of(Map.of("ref", String.valueOf(params.get("target")), "rect", Map.of("x", 0L,
                    "y", 0L, "w", 64L, "h", 64L))));
            r.put("redacted", List.of());
            r.put("carets", List.of());
            r.put("hash", hash(crop));
            return r;
        }
        byte[] png = dialog ? deskDialog : desk;
        int[] o = origin();
        String id = "cap-" + captures;
        origins.put(id, o);
        r.put("format", "png");
        r.put("width", dialog ? 360L : 320L);
        r.put("height", 240L);
        r.put("base64", Base64.getEncoder().encodeToString(png));
        r.put("captureId", id);
        r.put("seq", bridge.lastSeq());
        r.put("mode", "PAINT");
        r.put("scope", scope);
        r.put("space", "SCREEN");
        r.put("origin", Map.of("x", (long) o[0], "y", (long) o[1]));
        r.put("scale", 1.0);
        List<Object> windows = new ArrayList<>();
        windows.add(Map.of("ref", "h1", "rect", image(screen(comps.get(0))).json()));
        if (dialog) {
            windows.add(Map.of("ref", "d1", "rect", image(screen(dialogComps.get(0))).json()));
        }
        r.put("windows", windows);
        r.put("surfaces", List.of(Map.of("kind", "WEB", "rect", image(screen(comps.get(7))).json(), "ref", "h7",
                "browser", "b1", "redacted", webRedacted)));
        if (Boolean.TRUE.equals(params.get("annotateRefs"))) {
            r.put("marks", marks());
        }
        Capture.Rect entry = image(screen(comps.get(3)));
        r.put("carets", List.of(Map.of("ref", "h4", "rect", Map.of("x", (long) entry.x() + 2, "y", (long) entry.y()
                + 2, "w", 1L, "h", 20L))));
        r.put("focus", Map.of("owner", "h4", "ownerLocator", Map.of("id", "entry"), "window", "h1", "active", true));
        r.put("redacted", List.of(Map.of("ref", "h3", "rect", image(screen(comps.get(2))).json(), "rule",
                "password-field")));
        r.put("hash", hash(png));
        r.put("stackingAssumed", true);
        return r;
    }

    private Object hitTest(Map<String, Object> params) throws ai.skillroy.testjava.controller.AgentRpcException {
        Comp hit = null;
        int sx;
        int sy;
        if (params.get("target") instanceof String ref) {
            for (Comp c : all()) {
                if (c.ref().equals(ref)) {
                    hit = c;
                }
            }
            if (hit == null) {
                throw new ai.skillroy.testjava.controller.AgentRpcException("swing.hitTest",
                        new ai.skillroy.testjava.protocol.RpcError(ErrorCodes.STALE_HANDLE, "no " + ref, Map.of()));
            }
            int[] c = screen(hit).centre();
            sx = c[0];
            sy = c[1];
        } else {
            Map<?, ?> point = (Map<?, ?>) params.get("point");
            Map<?, ?> space = (Map<?, ?>) params.get("space");
            int[] o = origins.get(String.valueOf(space.get("captureId")));
            if (o == null) {
                throw new ai.skillroy.testjava.controller.AgentRpcException("swing.hitTest",
                        new ai.skillroy.testjava.protocol.RpcError(ErrorCodes.INVALID_PARAMS, "unknown capture",
                                Map.of("captureId", String.valueOf(space.get("captureId")))));
            }
            sx = o[0] + ((Number) point.get("x")).intValue();
            sy = o[1] + ((Number) point.get("y")).intValue();
            List<Comp> order = new ArrayList<>();
            if (dialog) {
                order.add(dialogComps.get(1));
                order.add(dialogComps.get(0));
            }
            List<Comp> frame = new ArrayList<>(comps.subList(1, comps.size()));
            java.util.Collections.reverse(frame);
            order.addAll(frame);
            order.add(comps.get(0));
            for (Comp c : order) {
                if (screen(c).contains(sx, sy)) {
                    hit = c;
                    break;
                }
            }
            if (hit == null) {
                throw new ai.skillroy.testjava.controller.AgentRpcException("swing.hitTest",
                        new ai.skillroy.testjava.protocol.RpcError(ErrorCodes.NOT_FOUND, "outside", Map.of("reason",
                                "outside")));
            }
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("ref", hit.ref());
        node.put("role", hit.role());
        if (hit.name() != null && !hit.masked()) {
            node.put("name", hit.name());
        }
        node.put("class", hit.cls());
        node.put("bounds", hit.rect().json());
        if (hit.masked()) {
            node.put("masked", true);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        int[] w = windowAt(hit.window());
        r.put("window", hit.window());
        r.put("windowRect", Map.of("x", (long) w[0], "y", (long) w[1], "w", "d1".equals(hit.window()) ? 160L : 320L,
                "h", "d1".equals(hit.window()) ? 100L : 240L));
        r.put("covering", List.of());
        boolean targetForm = params.get("target") instanceof String;
        if (targetForm && "h10".equals(hit.ref())) {
            // The agent's target form reports what is drawn at the component's centre beside it (D388): a label.
            Map<String, Object> label = new LinkedHashMap<>();
            label.put("ref", "h10-label");
            label.put("role", "label");
            label.put("name", "Renew");
            label.put("class", "javax.swing.JLabel");
            label.put("bounds", Map.of("x", 30L, "y", 7L, "w", 40L, "h", 16L));
            r.put("deepest", label);
        } else {
            r.put("deepest", node);
        }
        r.put("mouseTarget", null);
        r.put("target", hit.ref());
        r.put("targetKind", targetForm ? "TARGET" : "DEEPEST");
        if (targetForm) {
            // The target form's own node (fix wave FC, L1-F5).
            r.put("targetNode", node);
        }
        Capture.Rect s = screen(hit);
        int lx = sx - s.x();
        int ly = sy - s.y();
        r.put("local", Map.of("x", (long) lx, "y", (long) ly));
        r.put("relative", Map.of("fx", (double) lx / s.w(), "fy", (double) ly / s.h()));
        if (hit.rowHeight() > 0) {
            r.put("row", (long) (ly / hit.rowHeight()));
        }
        r.put("locators", hit.masked() ? List.of() : hit.locators());
        r.put("fires", hit.fires());
        for (Map.Entry<String, Capture.Rect> k : hit.regions().entrySet()) {
            if (k.getValue().contains(lx, ly)) {
                r.put("region", Map.of("id", k.getKey(), "rect", k.getValue().json(), "resolver",
                        "lending.LendingRegions"));
            }
        }
        r.put("surface", hit.surface());
        if (hit.browser() != null) {
            r.put("browser", hit.browser());
            r.put("surfaceLocal", Map.of("x", (long) lx, "y", (long) ly));
        }
        r.put("enabled", true);
        r.put("listening", false);
        r.put("masked", hit.masked() || webMasked && "WEB".equals(hit.surface()));
        r.put("seq", bridge.lastSeq());
        return r;
    }

    private List<Comp> all() {
        List<Comp> out = new ArrayList<>(comps);
        if (dialog) {
            out.addAll(dialogComps);
        }
        return out;
    }

    private static Map<String, Object> rendering() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jdk", Map.of("vendor", "Test", "version", "25+1", "specification", "25"));
        r.put("os", Map.of("name", "Linux", "version", "6", "arch", "aarch64"));
        r.put("lookAndFeel", "javax.swing.plaf.metal.MetalLookAndFeel");
        r.put("antialiasing", Map.of("desktopHints", List.of(), "property", "lcd"));
        r.put("screenScale", 1.0);
        r.put("probeHash", "sha256:0000");
        r.put("keyed", List.of("jdk.specification", "lookAndFeel", "antialiasing", "screenScale", "probeHash"));
        r.put("renderKey", RENDER_KEY);
        return r;
    }
}
