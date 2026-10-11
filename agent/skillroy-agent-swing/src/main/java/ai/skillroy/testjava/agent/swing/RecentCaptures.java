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
import java.awt.Point;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The geometry of the agent's last {@value #SIZE} captures, kept for at most ten minutes (phase7-contracts.md section
 * 3.1, {@code captureId}), so that {@code swing.hitTest} and {@code swing.pointer} may name a capture instead of
 * repeating its geometry; never the pixels. The window is held weakly. Thread-safe.
 */
final class RecentCaptures {

    /** How many captures are kept. */
    static final int SIZE = 32;
    /** How long a capture's geometry is kept. */
    static final long TTL_MS = TimeUnit.MINUTES.toMillis(10);

    /** A kept geometry. */
    static final class Entry {
        final String space;
        final WeakReference<Component> window;
        final double originX;
        final double originY;
        final double scale;
        final long at;

        Entry(Capture.Geometry g, long at) {
            this.space = g.space;
            this.window = new WeakReference<Component>(g.window);
            this.originX = g.originX;
            this.originY = g.originY;
            this.scale = g.scale;
            this.at = at;
        }

        /** The point of the capture's space an image pixel lies at (the mapping rule of section 3.1). */
        Point toSpace(double px, double py) {
            return new Point((int) Math.floor(originX + px / scale), (int) Math.floor(originY + py / scale));
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private long counter;
    private final Clicks.Clock clock;

    RecentCaptures() {
        this(Clicks.SYSTEM_CLOCK);
    }

    RecentCaptures(Clicks.Clock clock) {
        this.clock = clock;
    }

    /** Keeps a capture's geometry; returns its id {@code cap-<n>}. */
    synchronized String put(Capture.Geometry g) {
        String id = "cap-" + (++counter);
        entries.put(id, new Entry(g, clock.millis()));
        while (entries.size() > SIZE) {
            Iterator<String> it = entries.keySet().iterator();
            it.next();
            it.remove();
        }
        return id;
    }

    /** The geometry of a capture, or null when the agent no longer holds it. */
    synchronized Entry get(String id) {
        Entry e = entries.get(id);
        if (e == null) {
            return null;
        }
        if (clock.millis() - e.at > TTL_MS) {
            entries.remove(id);
            return null;
        }
        return e;
    }
}
