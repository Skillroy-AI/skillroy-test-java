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
 * and reports, for example:
 *
 * <pre>
 * app:
 *   lending: {"name":"IDLE","prompt":"Scan an item"}
 * - window "Lending Desk" [ref=h1]
 *   - textbox "entry" [ref=h5]
 *   - button "DUE" [ref=h12] (disabled)
 * </pre>
 *
 * <p>Unnamed panels, scroll panes and unknown containers are not shown; their children move up a level. The text is
 * capped by a character budget and ends with a note of how many lines were left out. Every value taken from the
 * target (role, name, text, ref, value, application-state keys and values) is put on one line with its control
 * characters made visible ({@code Values.printableLine}), because the text ends up in terminals and reports.
 *
 * <p>Stateless; safe from any thread.
 */
public final class UiSnapshotText {

    /** The default character budget (roughly a thousand tokens). */
    public static final int DEFAULT_BUDGET = 4000;

    private static final Set<String> CONTAINERS = Set.of("panel", "scrollpane", "unknown");
    private static final int MAX_NODES = 5000;
    private static final int MAX_TEXT = 80;

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

    private static String text(Object value) {
        return value == null ? "" : Values.stringOf(value).strip();
    }

    private static String oneLine(String text, int max) {
        String flat = Values.printableLine(text);
        return flat.length() <= max ? flat : flat.substring(0, Math.max(0, max - 3)) + "...";
    }
}
