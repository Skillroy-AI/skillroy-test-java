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

import ai.skillroy.testjava.scenario.UiSnapshotText;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;

/**
 * What the model is shown of one desk state, and what the harness knows about it.
 *
 * <p><b>Coordinate space.</b> The composite capture is in the main frame's own pixel space: {@code swing.capture}
 * ({@code PAINT}) of the frame, then each other showing window (the prompt dialogs, which are separate undecorated
 * windows) captured on its own and drawn at its position relative to the frame ({@code swing.windows} bounds, screen
 * coordinates, minus the frame's). Snapshot bounds are relative to the containing window (phase1-contracts.md 6), so
 * a node's composite position is its window's offset plus its bounds. Under the WM-less Xvfb the frame sits at the
 * screen origin with zero insets, which the probe checks ({@code frameInsets}).
 */
final class Screen {

    /** A node of the snapshot, with its bounds in the composite's space. */
    static final class Node {
        String ref;
        String role;
        String cls;
        String id;
        String name;
        String text;
        boolean enabled;
        boolean showing;
        Rectangle r;
        int depth;
        int window;
        Node parent;
        final List<Node> children = new ArrayList<>();
        Map<?, ?> value;

        String label() {
            return name != null && !name.isEmpty() ? name : text == null ? "" : text;
        }

        boolean isText() {
            return "textbox".equals(role) || "password".equals(role);
        }

        int rowCount() {
            return value != null && value.get("rowCount") instanceof Number n ? n.intValue() : 0;
        }

        @SuppressWarnings("unchecked")
        List<Object> row(int i) {
            if (value != null && value.get("cells") instanceof List<?> cells && i < cells.size()
                    && cells.get(i) instanceof List<?> row) {
                return (List<Object>) row;
            }
            return List.of();
        }
    }

    /** One window of the composite: its ref and offset. */
    record Win(String ref, String type, String title, int dx, int dy, int w, int h, Node root) {
    }

    /** The result of a hit-test: the deepest showing component under the point and, for a table, the row (or -1). */
    record Hit(Node node, int row) {
    }

    /** A Set-of-Marks mark: a number, the node (and row) it stands for, its box. */
    record Mark(int n, Node node, int row, Rectangle r, String text) {
    }

    static final Set<String> INTERACTIVE = Set.of("button", "toggle", "checkbox", "radio", "textbox", "password",
            "combobox", "list", "tree", "tab", "menu", "menuitem");
    private static final Color[] PALETTE = {new Color(0xd6198c), new Color(0x0b6bcb), new Color(0xd35400),
        new Color(0x1e8449), new Color(0x7d3c98), new Color(0xb7950b)};

    final String name;
    final BufferedImage image;
    final byte[] png;
    final String sha256;
    final Map<?, ?> snapshot;
    final Object appState;
    final List<Win> windows = new ArrayList<>();
    final List<Node> nodes = new ArrayList<>();
    final String windowsKey;
    final long captureMs;
    final int rowHeight;
    final Map<String, Object> notes;

    private Screen(String name, BufferedImage image, Map<?, ?> snapshot, Object appState, String windowsKey,
                   long captureMs, Map<String, Object> notes) throws IOException {
        this.name = name;
        this.image = image;
        this.png = encode(image);
        this.sha256 = sha256(png);
        this.snapshot = snapshot;
        this.appState = appState;
        this.windowsKey = windowsKey;
        this.captureMs = captureMs;
        this.rowHeight = Integer.getInteger("bs12.rowHeight", 16);
        this.notes = notes;
    }

    int width() {
        return image.getWidth();
    }

    int height() {
        return image.getHeight();
    }

    String base64() {
        return Base64.getEncoder().encodeToString(png);
    }

    /**
     * Takes the composite capture, the snapshot and {@code app.state} of the desk as it is now. Each window is
     * captured through its {@code JRootPane} (at the root pane's offset in the window), not through the window itself:
     * on the X toolkit a frame's {@code printAll} also prints a substitute title bar over the top of the content when
     * the frame has no window-manager insets (BS12 finding); {@code -Dbs12.captureWindow=true} captures the windows
     * themselves.
     */
    static Screen take(Desk desk, String name) throws Exception {
        long t0 = System.nanoTime();
        Map<?, ?> snapshot = desk.value("swing.snapshot", Map.of());
        List<?> wins = (List<?>) desk.value("swing.windows", Map.of()).get("windows");
        Map<?, ?> frame = null;
        List<Map<?, ?>> others = new ArrayList<>();
        for (Object o : wins) {
            Map<?, ?> w = (Map<?, ?>) o;
            if (!Boolean.TRUE.equals(w.get("visible"))) {
                continue;
            }
            if (frame == null && "frame".equals(w.get("type")) && FRAME_TITLE_OK(w)) {
                frame = w;
            } else {
                others.add(w);
            }
        }
        if (frame == null) {
            throw new IllegalStateException("no showing desk frame among " + wins);
        }
        boolean windowCapture = Boolean.getBoolean("bs12.captureWindow");
        Rectangle fb = rect(frame.get("bounds"));
        Map<String, Object> notes = new java.util.LinkedHashMap<>();
        notes.put("frameBounds", fb.x + "," + fb.y + "," + fb.width + "x" + fb.height);
        notes.put("capturedThrough", windowCapture ? "window" : "JRootPane");
        BufferedImage comp = new BufferedImage(fb.width, fb.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = comp.createGraphics();
        List<Object[]> placed = new ArrayList<>();
        // Modal dialogs last, so that they are drawn on top.
        others.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(a.get("modal")), Boolean.TRUE.equals(b.get("modal"))));
        List<Map<?, ?>> order = new ArrayList<>();
        order.add(frame);
        order.addAll(others);
        for (Map<?, ?> w : order) {
            Rectangle b = rect(w.get("bounds"));
            int dx = b.x - fb.x;
            int dy = b.y - fb.y;
            String ref = String.valueOf(w.get("ref"));
            String captureRef = ref;
            int ox = 0;
            int oy = 0;
            Map<?, ?> root = windowCapture ? null : rootPane(snapshot, ref);
            if (root != null) {
                captureRef = String.valueOf(root.get("ref"));
                Rectangle rb = rect(root.get("bounds"));
                ox = rb.x;
                oy = rb.y;
            }
            BufferedImage img = capture(desk, captureRef);
            g.drawImage(img, dx + ox, dy + oy, null);
            placed.add(new Object[] {w, dx, dy, b.width, b.height});
            notes.put("window " + w.get("type") + " " + ref, "at " + dx + "," + dy + " " + b.width + "x" + b.height
                    + ", captured " + captureRef + " " + img.getWidth() + "x" + img.getHeight() + " at +" + ox + ",+" + oy);
        }
        g.dispose();
        Object appState = desk.client.call("app.state", Map.of()).value();
        TreeSet<String> key = new TreeSet<>();
        for (Object[] p : placed) {
            key.add(String.valueOf(((Map<?, ?>) p[0]).get("ref")));
        }
        Screen s = new Screen(name, comp, snapshot, appState, String.join(",", key),
                (System.nanoTime() - t0) / 1_000_000L, notes);
        s.index(placed);
        return s;
    }

    /** The {@code JRootPane} child of the snapshot's window {@code ref}, or null. */
    private static Map<?, ?> rootPane(Map<?, ?> snapshot, String ref) {
        if (snapshot.get("windows") instanceof List<?> ws) {
            for (Object o : ws) {
                Map<?, ?> w = (Map<?, ?>) o;
                if (ref.equals(String.valueOf(w.get("ref"))) && w.get("children") instanceof List<?> cs) {
                    for (Object c : cs) {
                        if (c instanceof Map<?, ?> cm && "JRootPane".equals(cm.get("class"))) {
                            return cm;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** One window captured as it is (the probe's evidence of the substitute title bar). */
    static byte[] captureWindowAsIs(Desk desk) throws Exception {
        for (Object o : (List<?>) desk.value("swing.windows", Map.of()).get("windows")) {
            Map<?, ?> w = (Map<?, ?>) o;
            if ("frame".equals(w.get("type")) && Boolean.TRUE.equals(w.get("visible"))) {
                return encode(capture(desk, String.valueOf(w.get("ref"))));
            }
        }
        return new byte[0];
    }

    private static boolean FRAME_TITLE_OK(Map<?, ?> w) {
        return Desk.FRAME_TITLE.equals(w.get("title"));
    }

    /** The set of showing windows now (for the stale-capture check of design §3.10, step 1). */
    static String windowsKey(Desk desk) throws Exception {
        TreeSet<String> key = new TreeSet<>();
        for (Object o : (List<?>) desk.value("swing.windows", Map.of()).get("windows")) {
            Map<?, ?> w = (Map<?, ?>) o;
            if (Boolean.TRUE.equals(w.get("visible"))) {
                key.add(String.valueOf(w.get("ref")));
            }
        }
        return String.join(",", key);
    }

    private static BufferedImage capture(Desk desk, String ref) throws Exception {
        Map<String, Object> v = desk.value("swing.capture", Map.of("target", ref, "mode", "PAINT"));
        byte[] bytes = Base64.getDecoder().decode(String.valueOf(v.get("base64")));
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private void index(List<Object[]> placed) {
        List<?> wins = snapshot.get("windows") instanceof List<?> l ? l : List.of();
        for (Object o : wins) {
            Map<?, ?> wn = (Map<?, ?>) o;
            String ref = String.valueOf(wn.get("ref"));
            Object[] p = null;
            for (Object[] q : placed) {
                if (ref.equals(String.valueOf(((Map<?, ?>) q[0]).get("ref")))) {
                    p = q;
                }
            }
            if (p == null) {
                continue;
            }
            int dx = (int) p[1];
            int dy = (int) p[2];
            Map<?, ?> wm = (Map<?, ?>) p[0];
            int index = windows.size();
            Node root = node(wn, null, 0, index, dx, dy, true);
            root.r = new Rectangle(dx, dy, (int) p[3], (int) p[4]);
            windows.add(new Win(ref, String.valueOf(wm.get("type")), String.valueOf(wm.get("title")), dx, dy, (int) p[3],
                    (int) p[4], root));
        }
        // z-order: the frame first, then the others as drawn.
        windows.sort((a, b) -> Integer.compare(order(placed, a.ref()), order(placed, b.ref())));
    }

    private static int order(List<Object[]> placed, String ref) {
        for (int i = 0; i < placed.size(); i++) {
            if (ref.equals(String.valueOf(((Map<?, ?>) placed.get(i)[0]).get("ref")))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private Node node(Map<?, ?> m, Node parent, int depth, int window, int dx, int dy, boolean isWindow) {
        Node n = new Node();
        n.ref = String.valueOf(m.get("ref"));
        n.role = str(m.get("role"));
        n.cls = str(m.get("class"));
        n.id = m.get("id") == null ? null : String.valueOf(m.get("id"));
        n.name = str(m.get("name"));
        n.text = str(m.get("text"));
        n.enabled = !Boolean.FALSE.equals(m.get("enabled"));
        n.showing = !Boolean.FALSE.equals(m.get("showing")) && !Boolean.FALSE.equals(m.get("visible"));
        Rectangle b = rect(m.get("bounds"));
        n.r = isWindow ? b : new Rectangle(dx + b.x, dy + b.y, b.width, b.height);
        n.depth = depth;
        n.window = window;
        n.parent = parent;
        n.value = m.get("value") instanceof Map<?, ?> v ? v : null;
        nodes.add(n);
        if (m.get("children") instanceof List<?> children) {
            for (Object c : children) {
                n.children.add(node((Map<?, ?>) c, n, depth + 1, window, dx, dy, false));
            }
        }
        return n;
    }

    /** The deepest showing component under the point (Swing's own rule: lower child index on top), topmost window first. */
    Hit hitTest(int x, int y) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            Win w = windows.get(i);
            if (!new Rectangle(w.dx(), w.dy(), w.w(), w.h()).contains(x, y)) {
                continue;
            }
            Node n = w.root();
            descend:
            while (true) {
                for (Node c : n.children) {
                    if (c.showing && c.r.contains(x, y)) {
                        n = c;
                        continue descend;
                    }
                }
                break;
            }
            int row = -1;
            if ("table".equals(n.role)) {
                int r = (y - n.r.y) / rowHeight;
                row = r >= 0 && r < n.rowCount() ? r : -1;
            }
            return new Hit(n, row);
        }
        return new Hit(null, -1);
    }

    Node byRef(String ref) {
        for (Node n : nodes) {
            if (n.ref.equals(ref)) {
                return n;
            }
        }
        return null;
    }

    /** The bounds of row {@code row} of a table node. */
    Rectangle rowRect(Node table, int row) {
        return new Rectangle(table.r.x, table.r.y + row * rowHeight, table.r.width, rowHeight);
    }

    /** The project's text snapshot of the desk (design §2.5; the renderer the Phase 5 session shows the model). */
    String text() {
        return UiSnapshotText.render(snapshot, appState, Integer.getInteger("bs12.textBudget", 8000));
    }

    // ------------------------------------------------------------------------------------------------ marks

    /**
     * The Set-of-Marks marks: every showing interactive component, every icon-like custom component with a name
     * (the keypad keys, the prompt's painted buttons), every label or text component with text, the table header,
     * and one mark per table row (or the table itself when it is empty), numbered in reading order.
     */
    List<Mark> marks() {
        List<Mark> out = new ArrayList<>();
        for (Win w : windows) {
            collect(w.root(), out);
        }
        return out;
    }

    private void collect(Node n, List<Mark> out) {
        if (!n.showing || n.r.width < 4 || n.r.height < 4) {
            return;
        }
        if ("table".equals(n.role)) {
            int rows = n.rowCount();
            Rectangle clip = n.parent != null ? n.parent.r : n.r;
            if (rows == 0) {
                out.add(new Mark(out.size() + 1, n, -1, n.r.intersection(clip), "table (empty)"));
            }
            for (int i = 0; i < rows; i++) {
                Rectangle rr = rowRect(n, i).intersection(clip);
                if (!rr.isEmpty()) {
                    List<String> cells = new ArrayList<>();
                    for (Object c : n.row(i)) {
                        cells.add(String.valueOf(c));
                    }
                    out.add(new Mark(out.size() + 1, n, i, rr, "table row " + (i + 1) + ": " + String.join(" | ", cells)));
                }
            }
            return;
        }
        boolean leaf = n.children.isEmpty();
        boolean mark = INTERACTIVE.contains(n.role)
                || ("unknown".equals(n.role) && leaf && !n.name.isEmpty())
                || (("label".equals(n.role) || "text".equals(n.role)) && !n.label().isBlank())
                || "JTableHeader".equals(n.cls);
        if (mark) {
            String what = "JTableHeader".equals(n.cls) ? "table header" : n.role;
            String label = n.label();
            out.add(new Mark(out.size() + 1, n, -1, n.r, what + (label.isBlank() ? "" : " \"" + clip(label, 60) + "\"")));
        }
        for (Node c : n.children) {
            collect(c, out);
        }
    }

    /** The capture with the marks drawn on it: a coloured box per mark and its number in a label at the top left. */
    BufferedImage drawMarks(List<Mark> marks) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        for (Mark m : marks) {
            Color c = PALETTE[(m.n() - 1) % PALETTE.length];
            g.setColor(c);
            g.setStroke(new BasicStroke(2f));
            Rectangle r = m.r();
            g.drawRect(r.x + 1, r.y + 1, Math.max(1, r.width - 2), Math.max(1, r.height - 2));
            String label = String.valueOf(m.n());
            int lw = fm.stringWidth(label) + 6;
            int lh = Math.min(fm.getHeight(), Math.max(12, r.height));
            g.fillRect(r.x, r.y, lw, lh);
            g.setColor(Color.WHITE);
            g.drawString(label, r.x + 3, r.y + Math.min(lh - 2, fm.getAscent()));
        }
        g.dispose();
        return out;
    }

    static String marksList(List<Mark> marks) {
        StringBuilder sb = new StringBuilder();
        for (Mark m : marks) {
            sb.append('[').append(m.n()).append("] ").append(m.text()).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    static Rectangle rect(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return new Rectangle();
        }
        return new Rectangle(i(m.get("x")), i(m.get("y")), i(m.get("w")), i(m.get("h")));
    }

    private static int i(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A crop of the capture, scaled up to fit 1024x768 (what the toolset's {@code zoom} returns). */
    byte[] zoom(int x0, int y0, int x1, int y1) throws IOException {
        Rectangle r = new Rectangle(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0))
                .intersection(new Rectangle(0, 0, width(), height()));
        if (r.isEmpty()) {
            r = new Rectangle(0, 0, width(), height());
        }
        double scale = Math.min(1024.0 / r.width, 768.0 / r.height);
        int w = Math.max(1, (int) Math.round(r.width * scale));
        int h = Math.max(1, (int) Math.round(r.height * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, w, h, r.x, r.y, r.x + r.width, r.y + r.height, null);
        g.dispose();
        return encode(out);
    }
}
