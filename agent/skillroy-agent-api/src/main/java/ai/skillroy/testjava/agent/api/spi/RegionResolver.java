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

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;

/**
 * A custom-painted component's stable regions: a point to a region id and back (phase7-contracts.md section 3.7,
 * A64). A capture's marks, {@code swing.hitTest}'s {@code region} and {@code swing.pointer}'s component form with
 * {@code region} ask the registered resolvers in registration order; the first that supports the component and answers
 * wins.
 *
 * <p>A region id matches {@code [A-Za-z0-9][A-Za-z0-9:._-]{0,63}}; the agent refuses another. Called on the EDT inside
 * the capture's, the snapshot's or the hit-test's read; must be fast, lock-free and must not change the component. A
 * throwing implementation is logged once and ignored.
 */
public interface RegionResolver {

    /** True when this resolver knows the component's regions. */
    boolean supports(Component component);

    /** The region under a point of the component's own coordinates, or null. */
    Region regionAt(Component component, Point point);

    /** The rectangle of a region id in the component's own coordinates, or null when the id is unknown. */
    Rectangle rectOf(Component component, String id);

    /** Every region, for the marks of a capture; empty by default. */
    default List<Region> regions(Component component) {
        return Collections.emptyList();
    }

    /** One region: its id and its rectangle in the component's own coordinates. */
    final class Region {

        /** The region id. */
        public final String id;
        /** The rectangle in the component's own coordinates (a copy). */
        public final Rectangle rect;

        /** A region; the rectangle is copied. */
        public Region(String id, Rectangle rect) {
            this.id = id;
            this.rect = rect == null ? null : new Rectangle(rect);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Region)) {
                return false;
            }
            Region other = (Region) o;
            return (id == null ? other.id == null : id.equals(other.id))
                    && (rect == null ? other.rect == null : rect.equals(other.rect));
        }

        @Override
        public int hashCode() {
            return 31 * (id == null ? 0 : id.hashCode()) + (rect == null ? 0 : rect.hashCode());
        }

        @Override
        public String toString() {
            return "Region{" + id + ", " + (rect == null ? "null"
                    : rect.x + "," + rect.y + " " + rect.width + "x" + rect.height) + "}";
        }
    }
}
