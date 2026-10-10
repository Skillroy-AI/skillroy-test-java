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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The computer toolset's key names (X keysyms: {@code Return}, {@code Escape}, {@code Tab}, {@code BackSpace},
 * {@code Delete}, {@code Up}, {@code F1}, letters and digits; combinations {@code ctrl+s}, {@code alt+Tab},
 * {@code shift+Tab}) to the {@code KeyEvent.VK_} names without the prefix and the modifier names that
 * {@code swing.key} and the {@code press} verb take (phase7-contracts.md 6.3). Stateless.
 */
public final class KeyNames {

    /** A key name and its modifiers. */
    public record Key(String key, List<String> modifiers) {

        /** Copies the modifiers. */
        public Key {
            modifiers = List.copyOf(modifiers);
        }
    }

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("return", "ENTER"), Map.entry("enter", "ENTER"), Map.entry("kp_enter", "ENTER"),
            Map.entry("escape", "ESCAPE"), Map.entry("esc", "ESCAPE"), Map.entry("tab", "TAB"),
            Map.entry("backspace", "BACK_SPACE"), Map.entry("delete", "DELETE"), Map.entry("insert", "INSERT"),
            Map.entry("up", "UP"), Map.entry("down", "DOWN"), Map.entry("left", "LEFT"), Map.entry("right", "RIGHT"),
            Map.entry("home", "HOME"), Map.entry("end", "END"), Map.entry("page_up", "PAGE_UP"),
            Map.entry("prior", "PAGE_UP"), Map.entry("page_down", "PAGE_DOWN"), Map.entry("next", "PAGE_DOWN"),
            Map.entry("space", "SPACE"), Map.entry("minus", "MINUS"), Map.entry("plus", "PLUS"),
            Map.entry("period", "PERIOD"), Map.entry("comma", "COMMA"), Map.entry("slash", "SLASH"),
            Map.entry("backslash", "BACK_SLASH"), Map.entry("semicolon", "SEMICOLON"), Map.entry("equal", "EQUALS"),
            Map.entry("asterisk", "MULTIPLY"), Map.entry("kp_add", "ADD"), Map.entry("kp_subtract", "SUBTRACT"),
            Map.entry("kp_multiply", "MULTIPLY"), Map.entry("kp_divide", "DIVIDE"),
            Map.entry("kp_decimal", "DECIMAL"));

    private static final Map<String, String> MODIFIERS = Map.of("ctrl", "CTRL", "control", "CTRL", "alt", "ALT",
            "shift", "SHIFT", "super", "META", "meta", "META", "cmd", "META", "command", "META");

    private KeyNames() {
    }

    /**
     * The key and modifiers of a toolset key text ({@code Return}, {@code ctrl+s}, {@code shift+Tab}).
     *
     * @throws IllegalArgumentException for a name this mapping does not know (answered to the model as text)
     */
    public static Key of(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a key needs a name");
        }
        String[] parts = text.strip().split("\\+");
        List<String> modifiers = new ArrayList<>();
        for (int i = 0; i < parts.length - 1; i++) {
            String m = MODIFIERS.get(parts[i].strip().toLowerCase(Locale.ROOT));
            if (m == null) {
                throw new IllegalArgumentException("unknown modifier '" + parts[i].strip() + "' in key " + text);
            }
            if (!modifiers.contains(m)) {
                modifiers.add(m);
            }
        }
        String last = parts[parts.length - 1].strip();
        if (last.isEmpty()) {
            throw new IllegalArgumentException("a key needs a name after its modifiers: " + text);
        }
        return new Key(key(last), modifiers);
    }

    private static String key(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String named = NAMED.get(lower);
        if (named != null) {
            return named;
        }
        if (name.length() == 1 && (Character.isLetterOrDigit(name.charAt(0)))
                && name.charAt(0) < 128) {
            return name.toUpperCase(Locale.ROOT);
        }
        if (lower.matches("f([1-9]|1[0-9]|2[0-4])")) {
            return lower.toUpperCase(Locale.ROOT);
        }
        if (lower.matches("kp_[0-9]")) {
            return "NUMPAD" + lower.charAt(3);
        }
        throw new IllegalArgumentException("unknown key '" + name + "'");
    }
}
