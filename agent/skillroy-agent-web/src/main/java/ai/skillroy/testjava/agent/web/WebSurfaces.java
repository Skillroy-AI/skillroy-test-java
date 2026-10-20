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

package ai.skillroy.testjava.agent.web;

import ai.skillroy.testjava.agent.api.spi.SurfaceSource;

import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The web module's {@link SurfaceSource} (phase7-contracts.md sections 3.7 and 4.8, D336): one
 * {@code Surface(component, "WEB", browserRef, masked)} per indexed browser whose component, as the last index rebuild
 * found it, lies in a window ({@code masked}: the entry's flag, Phase 8 security fix S166). Answered from the index alone, never by asking a browser or an adapter (no vendor call): the
 * Swing module calls it on the EDT inside its capture's or its hit-test's read, so it reads one volatile snapshot,
 * walks each component's parents and takes no lock. A browser the module saw close is left out.
 *
 * <p>Registered at install through {@code SpiRegistry}, withdrawn at uninstall. Loading this class loads no AWT class;
 * the AWT types it names are resolved only when the Swing module calls it, which happens only once AWT runs.
 *
 * <p>Thread-safe.
 */
final class WebSurfaces implements SurfaceSource {

    /** The surface kind of an embedded page. */
    static final String WEB = "WEB";

    /** Whether a component lies in a window; tests replace it (no window exists in a headless test JVM). */
    interface Placement {
        boolean inWindow(Object component);
    }

    /**
     * The production placement: the component or one of its parents is a {@code java.awt.Window}. A class of its own,
     * created at the first call only, so that loading {@link WebSurfaces} at install verifies nothing that names AWT.
     */
    static final class Parents implements Placement {
        @Override
        public boolean inWindow(Object component) {
            if (!(component instanceof Component)) {
                return false;
            }
            for (Component p = (Component) component; p != null; p = p.getParent()) {
                if (p instanceof Window) {
                    return true;
                }
            }
            return false;
        }
    }

    private final BrowserIndex index;
    private volatile Placement placement;

    WebSurfaces(BrowserIndex index) {
        this(index, null);
    }

    WebSurfaces(BrowserIndex index, Placement placement) {
        this.index = index;
        this.placement = placement;
    }

    @Override
    public List<Surface> surfaces() {
        List<Entry> entries = index.snapshot().entries;
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        Placement where = placement;
        if (where == null) {
            where = new Parents();
            placement = where;
        }
        List<Surface> out = new ArrayList<Surface>();
        for (Entry e : entries) {
            Object c = e.component;
            if (c == null || e.closedPublished.get() || !where.inWindow(c)) {
                continue;
            }
            // Phase 8 security fix S166 (FE): the browser's mask flag goes with its surface, so that the Swing capture
            // blanks a masked browser's view and its hit-test reports it masked, whatever masks it (a BrowserMask, the
            // view-less rule, a CaptureMask); a volatile read, no vendor call.
            out.add(new Surface((Component) c, WEB, e.ref, e.masked));
        }
        return out;
    }
}
