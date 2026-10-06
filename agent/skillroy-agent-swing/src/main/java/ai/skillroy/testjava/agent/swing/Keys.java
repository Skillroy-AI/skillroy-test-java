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

package ai.skillroy.testjava.agent.swing;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Key names and non-posted key events. Names are the {@code KeyEvent.VK_} constants without the prefix ({@code ENTER},
 * {@code ESCAPE}, {@code F2}, {@code A}). Events are delivered with {@code Component.dispatchEvent}, never posted:
 * the focus manager retargets posted key events to the focus owner, or drops them when there is none, while a
 * dispatched event keeps its target as long as that target is showing, focusable and enabled.
 *
 * <p>Name resolution is stateless and thread-safe. The dispatch methods must be called on the EDT.
 */
final class Keys {

    private Keys() {
    }

    /** Lazily built map of VK_ names (without the prefix) to key codes. */
    private static final class Codes {
        static final Map<String, Integer> BY_NAME = build();

        private static Map<String, Integer> build() {
            Map<String, Integer> map = new HashMap<String, Integer>();
            for (Field f : KeyEvent.class.getFields()) {
                int mod = f.getModifiers();
                if (f.getName().startsWith("VK_") && f.getType() == int.class && Modifier.isStatic(mod)
                        && Modifier.isFinal(mod)) {
                    try {
                        map.put(f.getName().substring(3), Integer.valueOf(f.getInt(null)));
                    } catch (IllegalAccessException e) {
                        // public static fields are always readable
                    }
                }
            }
            return Collections.unmodifiableMap(map);
        }
    }

    /**
     * The key code for a name such as {@code ENTER}; case-insensitive, an optional {@code VK_} prefix is accepted.
     *
     * @throws ai.skillroy.testjava.protocol.RpcException INVALID_PARAMS for an unknown name
     */
    static int code(String name) {
        String n = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        if (n.startsWith("VK_")) {
            n = n.substring(3);
        }
        Integer code = Codes.BY_NAME.get(n);
        if (code == null || code.intValue() == KeyEvent.VK_UNDEFINED) {
            throw Errors.invalid("unknown key '" + name + "': expected a KeyEvent.VK_ name without the prefix, "
                    + "e.g. ENTER, ESCAPE, F2, A", Errors.data("param", "key", "key", name));
        }
        return code.intValue();
    }

    /**
     * Extended modifier mask from names {@code SHIFT}, {@code CTRL} (or {@code CONTROL}), {@code ALT}, {@code META};
     * case-insensitive.
     *
     * @throws ai.skillroy.testjava.protocol.RpcException INVALID_PARAMS for an unknown name
     */
    static int modifiers(List<Object> names) {
        int mask = 0;
        for (Object o : names) {
            String n = o == null ? "" : String.valueOf(o).trim().toUpperCase(Locale.ROOT);
            if ("SHIFT".equals(n)) {
                mask |= InputEvent.SHIFT_DOWN_MASK;
            } else if ("CTRL".equals(n) || "CONTROL".equals(n)) {
                mask |= InputEvent.CTRL_DOWN_MASK;
            } else if ("ALT".equals(n)) {
                mask |= InputEvent.ALT_DOWN_MASK;
            } else if ("META".equals(n)) {
                mask |= InputEvent.META_DOWN_MASK;
            } else {
                throw Errors.invalid("unknown modifier '" + o + "': expected SHIFT, CTRL, ALT or META",
                        Errors.data("param", "modifiers"));
            }
        }
        return mask;
    }

    /** The character a key types with the given modifiers, or {@code CHAR_UNDEFINED} if it types none. */
    static char charFor(int code, int modifiers) {
        boolean shift = (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
            char c = (char) ('a' + (code - KeyEvent.VK_A));
            return shift ? Character.toUpperCase(c) : c;
        }
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
            return (char) ('0' + (code - KeyEvent.VK_0));
        }
        if (code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_NUMPAD9) {
            return (char) ('0' + (code - KeyEvent.VK_NUMPAD0));
        }
        switch (code) {
            case KeyEvent.VK_SPACE: return ' ';
            case KeyEvent.VK_ENTER: return '\n';
            case KeyEvent.VK_TAB: return '\t';
            case KeyEvent.VK_BACK_SPACE: return '\b';
            case KeyEvent.VK_ESCAPE: return '\u001b';
            case KeyEvent.VK_DELETE: return '\u007f';
            case KeyEvent.VK_COMMA: return ',';
            case KeyEvent.VK_PERIOD: return '.';
            case KeyEvent.VK_SLASH: return '/';
            case KeyEvent.VK_SEMICOLON: return ';';
            case KeyEvent.VK_EQUALS: return '=';
            case KeyEvent.VK_MINUS: return '-';
            case KeyEvent.VK_OPEN_BRACKET: return '[';
            case KeyEvent.VK_CLOSE_BRACKET: return ']';
            case KeyEvent.VK_BACK_SLASH: return '\\';
            case KeyEvent.VK_QUOTE: return '\'';
            case KeyEvent.VK_BACK_QUOTE: return '`';
            case KeyEvent.VK_MULTIPLY: return '*';
            case KeyEvent.VK_ADD: return '+';
            case KeyEvent.VK_SUBTRACT: return '-';
            case KeyEvent.VK_DECIMAL: return '.';
            case KeyEvent.VK_DIVIDE: return '/';
            default: return KeyEvent.CHAR_UNDEFINED;
        }
    }

    /**
     * Delivers KEY_PRESSED, KEY_TYPED (only when the key types a character and no CTRL, ALT or META is down) and
     * KEY_RELEASED to {@code target}. EDT only.
     */
    static void press(Component target, int code, int modifiers) {
        char ch = charFor(code, modifiers);
        long when = System.currentTimeMillis();
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, when, modifiers, code, ch));
        int commandMask = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK;
        if (ch != KeyEvent.CHAR_UNDEFINED && (modifiers & commandMask) == 0) {
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_TYPED, when, modifiers, KeyEvent.VK_UNDEFINED,
                    ch));
        }
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, when, modifiers, code, ch));
    }

    /** Delivers the KEY_PRESSED / KEY_TYPED / KEY_RELEASED triple that typing {@code ch} produces. EDT only. */
    static void type(Component target, char ch) {
        int code = KeyEvent.getExtendedKeyCodeForChar(ch);
        long when = System.currentTimeMillis();
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, when, 0, code, ch));
        if (ch != KeyEvent.CHAR_UNDEFINED) {
            target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_TYPED, when, 0, KeyEvent.VK_UNDEFINED, ch));
        }
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, when, 0, code, ch));
    }
}
