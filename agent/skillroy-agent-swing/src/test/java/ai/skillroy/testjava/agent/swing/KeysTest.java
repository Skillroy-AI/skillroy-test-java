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

import ai.skillroy.testjava.protocol.ErrorCodes;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Key-name and modifier resolution. Headless. */
class KeysTest {

    @Test
    void namesResolveToVkConstants() {
        assertEquals(KeyEvent.VK_ENTER, Keys.code("ENTER"));
        assertEquals(KeyEvent.VK_ESCAPE, Keys.code("ESCAPE"));
        assertEquals(KeyEvent.VK_F2, Keys.code("F2"));
        assertEquals(KeyEvent.VK_A, Keys.code("A"));
        assertEquals(KeyEvent.VK_BACK_SPACE, Keys.code("back_space"), "case-insensitive");
        assertEquals(KeyEvent.VK_TAB, Keys.code("VK_TAB"), "prefix tolerated");
        assertEquals(KeyEvent.VK_5, Keys.code(" 5 "));
    }

    @Test
    void unknownNamesAreInvalidParams() {
        expectError(ErrorCodes.INVALID_PARAMS, () -> Keys.code("RETURN"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Keys.code(""));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Keys.code("UNDEFINED"));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Keys.code(null));
    }

    @Test
    void modifiers() {
        assertEquals(0, Keys.modifiers(list()));
        assertEquals(InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK,
                Keys.modifiers(list("shift", "CTRL")));
        assertEquals(InputEvent.CTRL_DOWN_MASK, Keys.modifiers(list("CONTROL")));
        assertEquals(InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK, Keys.modifiers(list("ALT", "META")));
        expectError(ErrorCodes.INVALID_PARAMS, () -> Keys.modifiers(list("HYPER")));
    }

    @Test
    void typedCharacters() {
        assertEquals('a', Keys.charFor(KeyEvent.VK_A, 0));
        assertEquals('A', Keys.charFor(KeyEvent.VK_A, InputEvent.SHIFT_DOWN_MASK));
        assertEquals('7', Keys.charFor(KeyEvent.VK_7, 0));
        assertEquals('3', Keys.charFor(KeyEvent.VK_NUMPAD3, 0));
        assertEquals('\n', Keys.charFor(KeyEvent.VK_ENTER, 0));
        assertEquals('\u001b', Keys.charFor(KeyEvent.VK_ESCAPE, 0));
        assertEquals(' ', Keys.charFor(KeyEvent.VK_SPACE, 0));
        assertEquals(KeyEvent.CHAR_UNDEFINED, Keys.charFor(KeyEvent.VK_F2, 0));
        assertEquals(KeyEvent.CHAR_UNDEFINED, Keys.charFor(KeyEvent.VK_SHIFT, 0));
    }
}
