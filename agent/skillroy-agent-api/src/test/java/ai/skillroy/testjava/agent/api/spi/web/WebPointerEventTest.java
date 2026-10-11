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

package ai.skillroy.testjava.agent.api.spi.web;

import ai.skillroy.testjava.agent.api.spi.web.WebPointerEvent.Action;
import ai.skillroy.testjava.agent.api.spi.web.WebPointerEvent.Button;
import ai.skillroy.testjava.agent.api.spi.web.WebPointerEvent.Modifier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebPointerEvent} (phase7-contracts.md section 4.1, A66): the builder's defaults per action, its bounds and
 * its refusals, immutability and equality.
 */
class WebPointerEventTest {

    @Test
    void theDefaultsDependOnTheAction() {
        WebPointerEvent press = WebPointerEvent.of(Action.PRESS, 10, 20).build();
        assertEquals(Action.PRESS, press.action());
        assertEquals(10, press.x());
        assertEquals(20, press.y());
        assertEquals(Button.LEFT, press.button());
        assertEquals(1, press.clickCount());
        assertTrue(press.modifiers().isEmpty());
        assertEquals(0, press.deltaX());
        assertEquals(0, press.deltaY());
        WebPointerEvent release = WebPointerEvent.of(Action.RELEASE, 0, 0).build();
        assertEquals(Button.LEFT, release.button());
        assertEquals(1, release.clickCount());
        for (Action a : new Action[] {Action.MOVE, Action.WHEEL}) {
            WebPointerEvent e = WebPointerEvent.of(a, 1, 1).build();
            assertEquals(Button.NONE, e.button(), a.name());
            assertEquals(0, e.clickCount(), a.name());
        }
    }

    @Test
    void everyFieldIsKeptAsGiven() {
        WebPointerEvent e = WebPointerEvent.of(Action.PRESS, 16384, 16384).button(Button.RIGHT).clickCount(3)
                .modifiers(EnumSet.of(Modifier.SHIFT, Modifier.ALT)).build();
        assertEquals(16384, e.x());
        assertEquals(16384, e.y());
        assertEquals(Button.RIGHT, e.button());
        assertEquals(3, e.clickCount());
        assertEquals(EnumSet.of(Modifier.ALT, Modifier.SHIFT), e.modifiers());
        WebPointerEvent wheel = WebPointerEvent.of(Action.WHEEL, 5, 6).delta(-16384, 16384).build();
        assertEquals(-16384, wheel.deltaX());
        assertEquals(16384, wheel.deltaY());
    }

    @Test
    void theBoundsAreChecked() {
        for (int[] p : new int[][] {{-1, 0}, {0, -1}, {16385, 0}, {0, 16385}}) {
            assertThrows(IllegalArgumentException.class, () -> WebPointerEvent.of(Action.MOVE, p[0], p[1]),
                    Arrays.toString(p));
        }
        assertThrows(NullPointerException.class, () -> WebPointerEvent.of(null, 0, 0));
        WebPointerEvent.Builder b = WebPointerEvent.of(Action.PRESS, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> b.clickCount(-1));
        assertThrows(IllegalArgumentException.class, () -> b.clickCount(4));
        assertThrows(NullPointerException.class, () -> b.button(null));
        assertThrows(NullPointerException.class, () -> b.modifiers(null));
        Set<Modifier> withNull = new HashSet<Modifier>(Arrays.asList(Modifier.CTRL, null));
        assertThrows(NullPointerException.class, () -> b.modifiers(withNull));
        WebPointerEvent.Builder w = WebPointerEvent.of(Action.WHEEL, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> w.delta(16385, 0));
        assertThrows(IllegalArgumentException.class, () -> w.delta(0, -16385));
        assertThrows(IllegalArgumentException.class, () -> w.delta(Integer.MIN_VALUE, 0));
    }

    @Test
    void aPressNeedsAButtonAndAClickAndADeltaIsForAWheelOnly() {
        assertThrows(IllegalStateException.class, () -> WebPointerEvent.of(Action.PRESS, 0, 0).button(Button.NONE)
                .build());
        assertThrows(IllegalStateException.class, () -> WebPointerEvent.of(Action.RELEASE, 0, 0).clickCount(0)
                .build());
        assertThrows(IllegalStateException.class, () -> WebPointerEvent.of(Action.MOVE, 0, 0).delta(1, 1).build());
        assertThrows(IllegalStateException.class, () -> WebPointerEvent.of(Action.PRESS, 0, 0).delta(0, 0).build());
        WebPointerEvent drag = WebPointerEvent.of(Action.MOVE, 3, 4).button(Button.LEFT).build();
        assertEquals(Button.LEFT, drag.button(), "a move may carry the button held");
    }

    @Test
    void theEventIsImmutableAndEqualByValue() {
        Set<Modifier> mods = EnumSet.of(Modifier.CTRL);
        WebPointerEvent.Builder b = WebPointerEvent.of(Action.PRESS, 7, 8).modifiers(mods);
        WebPointerEvent first = b.build();
        mods.add(Modifier.META);
        assertEquals(EnumSet.of(Modifier.CTRL), first.modifiers(), "the builder copied the set");
        assertThrows(UnsupportedOperationException.class, () -> first.modifiers().add(Modifier.ALT));
        b.button(Button.MIDDLE);
        assertEquals(Button.LEFT, first.button(), "a later builder change does not reach a built event");
        WebPointerEvent same = WebPointerEvent.of(Action.PRESS, 7, 8).modifiers(Collections.singleton(Modifier.CTRL))
                .build();
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, b.build());
        assertNotEquals(first, WebPointerEvent.of(Action.PRESS, 7, 9).modifiers(EnumSet.of(Modifier.CTRL)).build());
        assertTrue(first.toString().contains("PRESS at 7,8"), first.toString());
    }
}
