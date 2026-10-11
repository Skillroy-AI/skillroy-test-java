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

package ai.skillroy.testjava.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a {@code swing.snapshot} value and an {@code app.state} value as compact indented text for failure messages
 * and reports, and after the Swing windows the {@code WEB} surface (phase4-contracts.md 6.2): one block per embedded
 * browser that is visible in Swing, headed {@code [web <ref>] <title> <url> (<adapter>, <rendering>, <path>)} and
 * followed by the nodes of its {@code web.ariaSnapshot}, one line {@code role "name" [value]} per node, indented one
 * level per depth; a hidden browser is one line {@code [web <ref> hidden] <title> <url>}; a masked browser (a pack's
 * {@code CaptureMask} names its component, Phase 7 security fix S111) is one line {@code [web <ref> masked]} with
 * nothing of its page. For example:
 *
 * <pre>
 * app:
 *   lending: {"name":"IDLE","prompt":"Scan an item"}
 * - window "Lending Desk" [ref=h1]
 *   - textbox "entry" [ref=h5]
 *   - button "DUE" [ref=h12] (disabled)
 * [web h20] Borrow data:text/html;base64,PCFkb2N0eXBlIGh0bWw+... (jcef, WINDOWED, cdp)
 *   heading "Borrow"
 *   textbox "Item" [hammer]
 *   button "Borrow"
 * [web h21 hidden] Back data:text/html;base64,PCFkb2N0eXBlIGh0bWw+...
 * </pre>
 *
 * <p>Unnamed panels, scroll panes and unknown containers are not shown; their children move up a level. The text is
 * capped by a character budget, which the web blocks share, and ends with a note of how many lines were left out.
 * Every value taken from the target (role, name, text, ref, value, application-state keys and values, a browser's
 * title and URL) is put on one line with its control characters made visible ({@code Values.printableLine}), because
 * the text ends up in terminals and reports.
 *
 * <p>Stateless; safe from any thread.
 */
public final class UiSnapshotText {

    /**
     * One embedded browser for the {@code WEB} surface.
     *
     * @param browser its entry of the {@code web.browsers} value ({@code ref}, {@code title}, {@code url},
     *     {@code adapter}, {@code rendering}, {@code visibleInSwing}, …)
     * @param aria the {@code web.ariaSnapshot} value ({@code {nodes, truncated}}) of a visible browser, or null when
     *     it was not read or the read failed
     * @param path the web path that read it ({@code cdp} or {@code agent}), or null
     */
    public record WebView(Map<?, ?> browser, Object aria, String path) {
    }

    /** The default character budget (roughly a thousand tokens). */
    public static final int DEFAULT_BUDGET = 4000;

    private static final Set<String> CONTAINERS = Set.of("panel", "scrollpane", "unknown");
    private static final int MAX_NODES = 5000;
    private static final int MAX_TEXT = 80;
    /** The deepest aria node shown; the walker's own depth is bounded by its node count. */
    private static final int MAX_DEPTH = 100;

    private UiSnapshotText() {
    }

    /** {@link #render(Object, Object, int)} with {@link #DEFAULT_BUDGET}. */
    public static String render(Object snapshot, Object appState) {
        return render(snapshot, appState, DEFAULT_BUDGET);
    }

    /**
     * @param snapshot the {@code swing.snapshot} value ({@code {seq, truncated, windows:[Node…]}}), or null
     * @param appState the {@code app.state} value ({@code {providerId: {…}}}), or null
     * @param maxChars the character budget, at least 100
     * @return the text, possibly empty; never longer than {@code maxChars} plus the omission note
     */
    public static String render(Object snapshot, Object appState, int maxChars) {
        return render(snapshot, appState, List.of(), maxChars);
    }

    /**
     * As {@link #render(Object, Object, int)}, followed by the {@code WEB} surface of {@code web} in the given order.
     *
     * @param web the embedded browsers, or null or empty for none (a target without {@code web.browsers})
     */
    public static String render(Object snapshot, Object appState, List<WebView> web, int maxChars) {
        int budget = Math.max(100, maxChars);
        List<String> lines = new ArrayList<>();
        if (appState instanceof Map<?, ?> app && !app.isEmpty()) {
            lines.add("app:");
            for (Map.Entry<?, ?> e : app.entrySet()) {
                lines.add("  " + oneLine(String.valueOf(e.getKey()), MAX_TEXT) + ": "
                        + oneLine(Values.safeWrite(e.getValue()), 300));
            }
        }
        if (snapshot instanceof Map<?, ?> snap) {
            int[] nodes = {0};
            if (snap.get("windows") instanceof List<?> windows) {
                for (Object window : windows) {
                    node(window, 0, lines, nodes);
                }
            }
            if (Boolean.TRUE.equals(snap.get("truncated")) || nodes[0] >= MAX_NODES) {
                lines.add("(snapshot truncated)");
            }
        }
        if (web != null) {
            for (WebView view : web) {
                if (view != null && view.browser() != null) {
                    web(view, lines);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (out.length() + line.length() + 1 > budget) {
                out.append("... (").append(lines.size() - i).append(" more lines)");
                return out.toString();
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void node(Object raw, int depth, List<String> lines, int[] nodes) {
        if (!(raw instanceof Map<?, ?> n) || nodes[0] >= MAX_NODES) {
            return;
        }
        nodes[0]++;
        String role = text(n.get("role"));
        if (role.isEmpty()) {
            role = "unknown";
        }
        String name = text(n.get("name"));
        String text = text(n.get("text"));
        int childDepth = depth + 1;
        if (depth > 0 && CONTAINERS.contains(role) && name.isEmpty() && text.isEmpty()) {
            childDepth = depth;
        } else {
            StringBuilder line = new StringBuilder("  ".repeat(depth)).append("- ").append(oneLine(role, MAX_TEXT));
            String label = name.isEmpty() ? text : name;
            if (!label.isEmpty()) {
                line.append(" \"").append(oneLine(label, MAX_TEXT)).append('"');
            }
            if (!text.isEmpty() && !text.equals(label)) {
                line.append(": \"").append(oneLine(text, MAX_TEXT)).append('"');
            }
            Object ref = n.get("ref");
            if (ref != null) {
                line.append(" [ref=").append(oneLine(Values.stringOf(ref), MAX_TEXT)).append(']');
            }
            Object value = n.get("value");
            if (value != null && !Values.stringOf(value).equals(text)) {
                line.append(" value=").append(oneLine(Values.brief(value, MAX_TEXT), MAX_TEXT + 2));
            }
            List<String> flags = new ArrayList<>();
            if (Boolean.FALSE.equals(n.get("enabled"))) {
                flags.add("disabled");
            }
            if (Boolean.FALSE.equals(n.get("visible")) || Boolean.FALSE.equals(n.get("showing"))) {
                flags.add("hidden");
            }
            if (Boolean.TRUE.equals(n.get("focused"))) {
                flags.add("focused");
            }
            if (Boolean.TRUE.equals(n.get("selected"))) {
                flags.add("selected");
            }
            if (!flags.isEmpty()) {
                line.append(" (").append(String.join(", ", flags)).append(')');
            }
            lines.add(line.toString());
        }
        if (n.get("children") instanceof List<?> children) {
            for (Object child : children) {
                node(child, childDepth, lines, nodes);
            }
        }
    }

    /**
     * True when a browser of {@code web.browsers} is masked (phase7-contracts.md 3.6, 4.8; Phase 7 security fix S111):
     * the agent says {@code masked: true}, or, from an older agent that does not, its {@code component} is a ref the
     * {@code swing.snapshot} value marks {@code masked: true} (itself or through an ancestor).
     *
     * @param snapshot the {@code swing.snapshot} value, or null
     */
    public static boolean masked(Map<?, ?> browser, Object snapshot) {
        if (Boolean.TRUE.equals(browser.get("masked"))) {
            return true;
        }
        Object component = browser.get("component");
        if (component == null || !(snapshot instanceof Map<?, ?> snap) || !(snap.get("windows") instanceof List<?> ws)) {
            return false;
        }
        for (Object w : ws) {
            if (maskedRef(w, String.valueOf(component), false, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean maskedRef(Object raw, String ref, boolean inMasked, int depth) {
        if (!(raw instanceof Map<?, ?> n) || depth > MAX_DEPTH) {
            return false;
        }
        boolean masked = inMasked || Boolean.TRUE.equals(n.get("masked"));
        if (masked && ref.equals(String.valueOf(n.get("ref")))) {
            return true;
        }
        if (n.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (maskedRef(child, ref, masked, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The view of a masked browser: its ref alone, so that nothing of its page is rendered (S111). */
    public static WebView maskedView(Map<?, ?> browser) {
        return new WebView(Map.of("ref", String.valueOf(browser.get("ref")), "masked", true), null, null);
    }

    /** One browser of the {@code WEB} surface: its header line and, when visible, its aria nodes. */
    private static void web(WebView view, List<String> lines) {
        Map<?, ?> b = view.browser();
        if (Boolean.TRUE.equals(b.get("masked"))) {
            lines.add("[web " + oneLine(text(b.get("ref")), MAX_TEXT) + " masked]");
            return;
        }
        boolean visible = Boolean.TRUE.equals(b.get("visibleInSwing"));
        StringBuilder head = new StringBuilder("[web ").append(oneLine(text(b.get("ref")), MAX_TEXT));
        if (!visible) {
            head.append(" hidden");
        }
        head.append(']');
        String title = text(b.get("title"));
        if (!title.isEmpty()) {
            head.append(' ').append(oneLine(title, MAX_TEXT));
        }
        String url = text(b.get("url"));
        if (!url.isEmpty()) {
            head.append(' ').append(oneLine(url, MAX_TEXT));
        }
        if (!visible) {
            lines.add(head.toString());
            return;
        }
        List<String> about = new ArrayList<>();
        for (Object part : new Object[] {b.get("adapter"), b.get("rendering"), view.path()}) {
            String value = text(part);
            if (!value.isEmpty()) {
                about.add(oneLine(value, MAX_TEXT));
            }
        }
        if (!about.isEmpty()) {
            head.append(" (").append(String.join(", ", about)).append(')');
        }
        lines.add(head.toString());
        if (view.aria() instanceof Map<?, ?> aria) {
            int[] nodes = {0};
            if (aria.get("nodes") instanceof List<?> list) {
                for (Object node : list) {
                    ariaNode(node, 1, lines, nodes);
                }
            }
            if (Boolean.TRUE.equals(aria.get("truncated")) || nodes[0] >= MAX_NODES) {
                lines.add("  (aria snapshot truncated)");
            }
        }
    }

    /** {@code role "name" [value]}, indented two spaces per depth, then the children one level deeper. */
    private static void ariaNode(Object raw, int depth, List<String> lines, int[] nodes) {
        if (!(raw instanceof Map<?, ?> n) || nodes[0] >= MAX_NODES || depth > MAX_DEPTH) {
            return;
        }
        nodes[0]++;
        String role = text(n.get("role"));
        StringBuilder line = new StringBuilder("  ".repeat(depth)).append(oneLine(role.isEmpty() ? "generic" : role,
                MAX_TEXT));
        String name = text(n.get("name"));
        if (!name.isEmpty()) {
            line.append(" \"").append(oneLine(name, MAX_TEXT)).append('"');
        }
        Object value = n.get("value");
        if (value != null && !text(value).isEmpty()) {
            line.append(" [").append(oneLine(text(value), MAX_TEXT)).append(']');
        }
        lines.add(line.toString());
        if (n.get("children") instanceof List<?> children) {
            for (Object child : children) {
                ariaNode(child, depth + 1, lines, nodes);
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : Values.stringOf(value).strip();
    }

    private static String oneLine(String text, int max) {
        String flat = Values.printableLine(text);
        return flat.length() <= max ? flat : flat.substring(0, Math.max(0, max - 3)) + "...";
    }
}
