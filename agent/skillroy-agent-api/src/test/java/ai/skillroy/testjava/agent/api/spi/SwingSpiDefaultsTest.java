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

package ai.skillroy.testjava.agent.api.spi;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 7 Swing SPIs' defaults and value types (phase7-contracts.md sections 3.6 and 3.7, A64): a resolver that
 * predates {@code describe} describes nothing, a region resolver lists no regions, a capture mask's regions mean the
 * whole rectangle, and the two value classes keep what they are given.
 */
class SwingSpiDefaultsTest {

    @Test
    void aLocatorResolverDescribesNothingByDefault() {
        LocatorResolver resolver = new LocatorResolver() {
            @Override
            public Set<String> strategies() {
                return Collections.singleton("action");
            }

            @Override
            public List<Component> resolve(String strategy, String value, Component root) {
                return Collections.emptyList();
            }
        };
        assertTrue(resolver.describe(null).isEmpty());
    }

    @Test
    void aRegionResolverListsNoRegionsByDefault() {
        RegionResolver resolver = new RegionResolver() {
            @Override
            public boolean supports(Component component) {
                return true;
            }

            @Override
            public Region regionAt(Component component, Point point) {
                return null;
            }

            @Override
            public Rectangle rectOf(Component component, String id) {
                return null;
            }
        };
        assertTrue(resolver.regions(null).isEmpty());
    }

    @Test
    void aCaptureMaskBlanksTheWholeRectangleByDefault() {
        CaptureMask mask = new CaptureMask() {
            @Override
            public boolean masks(Component component) {
                return true;
            }
        };
        assertNull(mask.regions(null), "null means the whole rectangle");
    }

    @Test
    void aRegionCopiesItsRectangleAndComparesByValue() {
        Rectangle r = new Rectangle(1, 2, 3, 4);
        RegionResolver.Region a = new RegionResolver.Region("key:7", r);
        r.x = 99;
        assertEquals(new Rectangle(1, 2, 3, 4), a.rect, "the rectangle is a copy");
        assertNotSame(r, a.rect);
        assertEquals(a, new RegionResolver.Region("key:7", new Rectangle(1, 2, 3, 4)));
        assertEquals(a.hashCode(), new RegionResolver.Region("key:7", new Rectangle(1, 2, 3, 4)).hashCode());
        assertNotEquals(a, new RegionResolver.Region("key:8", new Rectangle(1, 2, 3, 4)));
        assertEquals("Region{key:7, 1,2 3x4}", a.toString());
    }

    @Test
    void aSurfaceKeepsItsComponentKindAndBrowserRef() {
        Component c = new Component() {
        };
        SurfaceSource.Surface s = new SurfaceSource.Surface(c, "WEB", "b1");
        assertSame(c, s.component);
        assertEquals("WEB", s.kind);
        assertEquals("b1", s.browserRef);
        assertFalse(s.masked, "the older constructor: not masked");
    }

    /** Phase 8 security fix S166: the surface carries the module's mask flag (additive, D636). */
    @Test
    void aSurfaceCarriesTheModulesMaskFlag() {
        Component c = new Component() {
        };
        SurfaceSource.Surface s = new SurfaceSource.Surface(c, "WEB", "b1", true);
        assertTrue(s.masked);
        assertEquals("Surface{WEB, b1, masked}", s.toString());
    }
}
