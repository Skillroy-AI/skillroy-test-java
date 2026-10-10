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

import ai.skillroy.testjava.agent.api.AgentContext;

import java.awt.Component;
import java.awt.Point;
import java.awt.Window;
import java.util.List;
import java.util.Map;

/**
 * The {@code space} parameter of {@code swing.hitTest} and {@code swing.pointer} (phase7-contracts.md section 3.2):
 * {@code {captureId: "cap-<n>"}} (a capture's image coordinates, the mapping rule applied by the agent),
 * {@code {window: ref}} (a window's own coordinates) or the string {@code SCREEN} (screen coordinates; the topmost
 * window of the JVM at the point by the stacking order). Parsed on the calling thread, resolved on the EDT.
 *
 * <p>Immutable.
 */
final class PointSpace {

    private final String captureId;
    private final String windowRef;
    private final boolean screen;

    private PointSpace(String captureId, String windowRef, boolean screen) {
        this.captureId = captureId;
        this.windowRef = windowRef;
        this.screen = screen;
    }

    /** A resolved point: the window and the point in its coordinates. */
    static final class At {
        final Window window;
        final Point point;
        final boolean screen;

        At(Window window, Point point, boolean screen) {
            this.window = window;
            this.point = point;
            this.screen = screen;
        }
    }

    /**
     * Reads {@code space}.
     *
     * @throws ai.skillroy.testjava.protocol.RpcException INVALID_PARAMS for a missing or unknown form
     */
    @SuppressWarnings("unchecked")
    static PointSpace parse(Object space) {
        if (space == null) {
            throw Errors.invalid("missing parameter 'space': {captureId}, {window: ref} or \"SCREEN\"",
                    Errors.data("param", "space"));
        }
        if (space instanceof String && "SCREEN".equalsIgnoreCase(((String) space).trim())) {
            return new PointSpace(null, null, true);
        }
        if (space instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) space;
            if (m.size() == 1 && m.get("captureId") instanceof String) {
                return new PointSpace((String) m.get("captureId"), null, false);
            }
            if (m.size() == 1 && m.get("window") instanceof String) {
                return new PointSpace(null, (String) m.get("window"), false);
            }
        }
        throw Errors.invalid("unknown space " + space + ": expected {captureId}, {window: ref} or \"SCREEN\"",
                Errors.data("param", "space"));
    }

    /**
     * Reads a point object {@code {x, y}}.
     *
     * @throws ai.skillroy.testjava.protocol.RpcException INVALID_PARAMS for a malformed point
     */
    @SuppressWarnings("unchecked")
    static Point point(Object p, String param) {
        if (p instanceof Map) {
            Object x = ((Map<String, Object>) p).get("x");
            Object y = ((Map<String, Object>) p).get("y");
            if (x instanceof Number && y instanceof Number) {
                double dx = ((Number) x).doubleValue();
                double dy = ((Number) y).doubleValue();
                if (!Double.isNaN(dx) && !Double.isNaN(dy) && Math.abs(dx) < 1e7 && Math.abs(dy) < 1e7) {
                    return new Point((int) Math.floor(dx), (int) Math.floor(dy));
                }
            }
        }
        throw Errors.invalid("parameter '" + param + "' must be a point {x, y}", Errors.data("param", param));
    }

    /** True for the {@code SCREEN} form. */
    boolean screen() {
        return screen;
    }

    /**
     * The window and the point in its coordinates. EDT only.
     *
     * @throws ai.skillroy.testjava.protocol.RpcException INVALID_PARAMS for a capture id the agent no longer holds
     *     ({@code data.captureId}) or a window ref that is not a window, NOT_FOUND ({@code data.reason: "outside"})
     *     for a screen point in no window of the JVM
     */
    At resolve(AgentContext ctx, Windows windows, RecentCaptures recent, Point p) {
        if (captureId != null) {
            RecentCaptures.Entry e = recent.get(captureId);
            if (e == null) {
                throw Errors.invalid("the agent no longer holds capture '" + captureId + "' (it keeps the last "
                        + RecentCaptures.SIZE + " for ten minutes); capture again", Errors.data("param", "space",
                        "captureId", captureId));
            }
            Point inSpace = e.toSpace(p.x, p.y);
            if ("SCREEN".equals(e.space)) {
                return onScreen(windows, inSpace);
            }
            Component w = e.window.get();
            if (!(w instanceof Window)) {
                throw Errors.invalid("the window of capture '" + captureId + "' is gone", Errors.data("param", "space",
                        "captureId", captureId));
            }
            return new At((Window) w, inSpace, false);
        }
        if (windowRef != null) {
            Component w = ctx.handles().resolve(windowRef, Component.class);
            if (!(w instanceof Window)) {
                throw Errors.invalid("space.window must name a window, not " + w.getClass().getSimpleName(),
                        Errors.data("param", "space"));
            }
            return new At((Window) w, p, false);
        }
        return onScreen(windows, p);
    }

    private static At onScreen(Windows windows, Point sp) {
        List<Windows.Layer> layers = windows.stacking();
        for (int i = layers.size() - 1; i >= 0; i--) {
            Windows.Layer l = layers.get(i);
            if (l.screen.contains(sp) && l.window instanceof Window) {
                return new At((Window) l.window, new Point(sp.x - l.screen.x, sp.y - l.screen.y), true);
            }
        }
        throw Errors.notFound("the screen point (" + sp.x + ", " + sp.y + ") lies in no window of the JVM",
                Errors.data("reason", "outside"));
    }
}
