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
import java.util.List;

/**
 * Areas of the tree that are not Swing-painted, as another module knows them (the web module's browser index):
 * answered from what the module already holds, never by asking a browser; the list is a snapshot
 * (phase7-contracts.md section 3.7, A64). A capture reports each surface whose component is in the image in its
 * {@code surfaces}; the hit-test reports {@code surface: WEB} for a point inside one.
 *
 * <p>Called on the EDT inside the capture's or the hit-test's read; must be fast, lock-free and must not change
 * anything. A throwing implementation is logged once and ignored.
 */
public interface SurfaceSource {

    /** The surfaces this module knows now; empty when none, never null. */
    List<Surface> surfaces();

    /**
     * One surface: the component that shows it, its kind, for a web surface the browser's ref, and whether the module
     * masks what it shows.
     */
    final class Surface {

        /** The component the surface is painted into. */
        public final Component component;
        /** The surface's kind: {@code "WEB"} for an embedded page. */
        public final String kind;
        /** The browser's ref for a web surface, else null. */
        public final String browserRef;
        /**
         * True when the module masks what the surface shows (Phase 8 security fix S166: the web module's flag of a
         * masked browser, whatever masks it): the capture then blanks the component whole and reports the surface
         * {@code redacted}, and the hit-test reports a hit on it, or below it, {@code masked}, as for a component a
         * {@code CaptureMask} names.
         */
        public final boolean masked;

        /** A surface the module does not mask. */
        public Surface(Component component, String kind, String browserRef) {
            this(component, kind, browserRef, false);
        }

        /** A surface (Phase 8 security fix S166). */
        public Surface(Component component, String kind, String browserRef, boolean masked) {
            this.component = component;
            this.kind = kind;
            this.browserRef = browserRef;
            this.masked = masked;
        }

        @Override
        public String toString() {
            return "Surface{" + kind + ", " + browserRef + (masked ? ", masked" : "") + "}";
        }
    }
}
