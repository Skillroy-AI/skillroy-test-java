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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BrowserSeed}: the factories, the null check and value semantics by the identity of the object plus the tag
 * (Phase 4 contract §3, §12.1 item 1).
 */
class BrowserSeedTest {

    @Test
    void ofObjectMakesAnUntaggedSeed() {
        Object browser = new Object();
        BrowserSeed seed = BrowserSeed.of(browser);
        assertSame(browser, seed.object());
        assertNull(seed.tag());
    }

    @Test
    void ofObjectAndTagKeepsTheTagAsGiven() {
        Object browser = new Object();
        BrowserSeed seed = BrowserSeed.of(browser, "hidden-card");
        assertSame(browser, seed.object());
        assertEquals("hidden-card", seed.tag());
        assertNull(BrowserSeed.of(browser, null).tag());
        assertEquals(" back ", BrowserSeed.of(browser, " back ").tag());
    }

    @Test
    void aNullObjectIsRejected() {
        NullPointerException untagged = assertThrows(NullPointerException.class, () -> BrowserSeed.of(null));
        assertEquals("object must not be null", untagged.getMessage());
        NullPointerException tagged = assertThrows(NullPointerException.class, () -> BrowserSeed.of(null, "back"));
        assertEquals("object must not be null", tagged.getMessage());
    }

    @Test
    void seedsAreEqualByTheIdentityOfTheObjectAndTheTag() {
        String browser = new String("browser");
        String twin = new String("browser");
        assertEquals(browser, twin);
        assertNotSame(browser, twin);

        assertEquals(BrowserSeed.of(browser, "back"), BrowserSeed.of(browser, "back"));
        assertEquals(BrowserSeed.of(browser, "back").hashCode(), BrowserSeed.of(browser, "back").hashCode());
        assertEquals(BrowserSeed.of(browser), BrowserSeed.of(browser, null));
        assertNotEquals(BrowserSeed.of(browser, "back"), BrowserSeed.of(twin, "back"),
                "equal but distinct objects are different seeds");
        assertNotEquals(BrowserSeed.of(browser, "back"), BrowserSeed.of(browser, "front"));
        assertNotEquals(BrowserSeed.of(browser, "back"), BrowserSeed.of(browser));
        assertFalse(BrowserSeed.of(browser).equals(null));
        assertFalse(BrowserSeed.of(browser).equals(browser));
    }

    @Test
    void equalsHashCodeAndToStringNeverCallTheObject() {
        Untouchable browser = new Untouchable();
        BrowserSeed seed = BrowserSeed.of(browser, "back");
        assertEquals(seed, BrowserSeed.of(browser, "back"));
        assertNotEquals(seed, BrowserSeed.of(new Untouchable(), "back"));
        assertEquals(seed.hashCode(), BrowserSeed.of(browser, "back").hashCode());
        assertEquals("BrowserSeed{object=" + Untouchable.class.getName() + "@"
                + Integer.toHexString(System.identityHashCode(browser)) + ", tag=back}", seed.toString());
    }

    /** Stands for a vendor object whose own methods may block or throw: calling any of them fails the test. */
    private static final class Untouchable {

        @Override
        public boolean equals(Object o) {
            throw new AssertionError("equals called");
        }

        @Override
        public int hashCode() {
            throw new AssertionError("hashCode called");
        }

        @Override
        public String toString() {
            throw new AssertionError("toString called");
        }
    }
}
