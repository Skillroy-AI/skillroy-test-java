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

package ai.skillroy.testjava.vision;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One capture the loop took (phase7-contracts.md 3.1, 6.2): the PNG as the agent sent it (redacted by the agent,
 * without marks: what {@code captures/<hash>.png} holds), and the geometry the result named. Immutable but for the PNG
 * array, which is copied in and out.
 *
 * @param captureId the agent's id of the capture ({@code cap-<n>}), which a hit-test names
 * @param seq the event bus position at the paint
 * @param hash {@code sha256:<hex>} of the decoded raster (the agent's)
 * @param width the image's width in pixels
 * @param height the image's height in pixels
 * @param scale the effective scale
 * @param space {@code WINDOW} or {@code SCREEN}
 * @param window the window's ref, or null for {@code SCREEN}
 * @param originX where pixel (0, 0) lies in {@code space}
 * @param originY where pixel (0, 0) lies in {@code space}
 * @param windows every window in the image, bottom to top
 * @param surfaces the areas that are not Swing-painted
 * @param marks the marks' geometry and labels ({@code annotateRefs}), else empty
 * @param redacted the rectangles the agent blanked
 * @param carets the carets' rectangles
 * @param focus the focus state as the agent answered it, or an empty map
 * @param png the PNG's bytes as received
 */
public record Capture(String captureId, long seq, String hash, int width, int height, double scale, String space,
                      String window, int originX, int originY, List<Region> windows, List<Surface> surfaces,
                      List<Mark> marks, List<Rect> redacted, List<Rect> carets, Map<String, Object> focus,
                      byte[] png) {

    /** A rectangle in image coordinates. */
    public record Rect(int x, int y, int w, int h) {

        /** The rectangle's centre. */
        public int[] centre() {
            return new int[] {x + w / 2, y + h / 2};
        }

        /** True when the point lies inside. */
        public boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }

        /** The intersection with another rectangle, or null when they do not meet. */
        public Rect intersect(Rect o) {
            int x0 = Math.max(x, o.x);
            int y0 = Math.max(y, o.y);
            int x1 = Math.min(x + w, o.x + o.w);
            int y1 = Math.min(y + h, o.y + o.h);
            return x1 > x0 && y1 > y0 ? new Rect(x0, y0, x1 - x0, y1 - y0) : null;
        }

        /** {@code {x, y, w, h}}. */
        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", (long) x);
            m.put("y", (long) y);
            m.put("w", (long) w);
            m.put("h", (long) h);
            return m;
        }

        /** A rectangle of {@code {x, y, w, h}}, or null. */
        public static Rect of(Object json) {
            if (!(json instanceof Map<?, ?> m)) {
                return null;
            }
            return new Rect(i(m.get("x")), i(m.get("y")), i(m.get("w")), i(m.get("h")));
        }
    }

    /** A window of the image: its ref and its rectangle (the root pane's area) in image coordinates. */
    public record Region(String ref, Rect rect) {
    }

    /**
     * An area that is not Swing-painted ({@code WEB} or {@code HEAVYWEIGHT}). Phase 7 security fix S108 (SI): the
     * agent's {@code rect} is the visible part of the component and {@code covered} lists, in image coordinates, the
     * rectangles of what lies above it (a window higher in the stack, a popup, a modal or drag layer, a glass pane), an
     * additive field read as empty from an agent that does not send it; {@code bounds}, when an agent sends it, is the
     * component's whole rectangle in image coordinates (the page's bitmap is drawn there, clipped to {@code rect} minus
     * {@code covered}), else null.
     *
     * @param kind {@code WEB} or {@code HEAVYWEIGHT}
     * @param rect the visible part, in image coordinates
     * @param ref the component's ref
     * @param browser the browser's ref, or null
     * @param redacted true when a mask names the component or a blanked rectangle meets it
     * @param covered what lies above the surface, in image coordinates; never null
     * @param bounds the component's whole rectangle in image coordinates, or null
     */
    public record Surface(String kind, Rect rect, String ref, String browser, boolean redacted, List<Rect> covered,
                          Rect bounds) {

        /** Copies the covered rectangles (null is none). */
        public Surface {
            covered = covered == null ? List.of() : List.copyOf(covered);
        }

        /** A surface with nothing above it and no separate bounds (an older agent's shape). */
        public Surface(String kind, Rect rect, String ref, String browser, boolean redacted) {
            this(kind, rect, ref, browser, redacted, List.of(), null);
        }
    }

    /**
     * A mark's geometry (phase7-contracts.md 3.1): the number a drawn mark carries, the component's ref, the rectangle
     * a mark stands on, the label the marks list gives, the role, a table row's model index, a region's id.
     */
    public record Mark(int mark, String ref, Rect rect, String label, String role, Integer row, String region) {
    }

    /** Copies the lists and the PNG. */
    public Capture {
        windows = List.copyOf(windows);
        surfaces = List.copyOf(surfaces);
        marks = List.copyOf(marks);
        redacted = List.copyOf(redacted);
        carets = List.copyOf(carets);
        focus = focus == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(focus));
        png = png.clone();
    }

    /** A copy of the PNG's bytes. */
    @Override
    public byte[] png() {
        return png.clone();
    }

    /** The hash's hex digits, without {@code sha256:} (the captures file's name). */
    public String hex() {
        return hash != null && hash.startsWith("sha256:") ? hash.substring(7) : hash;
    }

    /** The mark of a number, or null. */
    public Mark mark(int number) {
        for (Mark m : marks) {
            if (m.mark() == number) {
                return m;
            }
        }
        return null;
    }

    /** The topmost window whose rectangle holds the point (the stacking order of 3.1), or null. */
    public Region windowAt(int x, int y) {
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).rect().contains(x, y)) {
                return windows.get(i);
            }
        }
        return null;
    }

    /** The capture's facts for a step's provenance (phase7-contracts.md 9.2): no bytes. */
    public Map<String, Object> provenance(String file) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hash", hash);
        m.put("width", (long) width);
        m.put("height", (long) height);
        m.put("seq", seq);
        m.put("space", space);
        m.put("origin", Map.of("x", (long) originX, "y", (long) originY));
        List<Object> ws = new ArrayList<>();
        for (Region r : windows) {
            ws.add(Map.of("ref", r.ref(), "rect", r.rect().json()));
        }
        m.put("windows", ws);
        if (file != null) {
            m.put("file", file);
        }
        return m;
    }

    /**
     * A capture from {@code swing.capture}'s result (phase7-contracts.md 3.1).
     *
     * @throws IllegalArgumentException when the result has no {@code base64} PNG or no {@code captureId}
     */
    public static Capture of(Object result) {
        if (!(result instanceof Map<?, ?> r) || !(r.get("base64") instanceof String b64)) {
            throw new IllegalArgumentException("the capture's result holds no image");
        }
        byte[] png;
        try {
            png = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the capture's image is not base64");
        }
        Map<?, ?> origin = r.get("origin") instanceof Map<?, ?> o ? o : Map.of();
        List<Region> windows = new ArrayList<>();
        for (Object w : list(r.get("windows"))) {
            if (w instanceof Map<?, ?> m && m.get("ref") instanceof String ref && Rect.of(m.get("rect")) != null) {
                windows.add(new Region(ref, Rect.of(m.get("rect"))));
            }
        }
        List<Surface> surfaces = new ArrayList<>();
        for (Object s : list(r.get("surfaces"))) {
            if (s instanceof Map<?, ?> m && Rect.of(m.get("rect")) != null) {
                List<Rect> covered = new ArrayList<>();
                for (Object c : list(m.get("covered"))) {
                    Rect over = c instanceof Map<?, ?> cm && cm.get("rect") != null ? Rect.of(cm.get("rect"))
                            : Rect.of(c);
                    if (over != null && over.w() > 0 && over.h() > 0) {
                        covered.add(over);
                    }
                }
                surfaces.add(new Surface(String.valueOf(m.get("kind")), Rect.of(m.get("rect")), str(m.get("ref")),
                        str(m.get("browser")), Boolean.TRUE.equals(m.get("redacted")), covered,
                        Rect.of(m.get("bounds"))));
            }
        }
        List<Mark> marks = new ArrayList<>();
        for (Object k : list(r.get("marks"))) {
            if (k instanceof Map<?, ?> m && Rect.of(m.get("rect")) != null) {
                marks.add(new Mark(i(m.get("mark")), str(m.get("ref")), Rect.of(m.get("rect")), str(m.get("label")),
                        str(m.get("role")), m.get("row") instanceof Number n ? n.intValue() : null,
                        str(m.get("region"))));
            }
        }
        List<Rect> redacted = new ArrayList<>();
        for (Object d : list(r.get("redacted"))) {
            if (d instanceof Map<?, ?> m && Rect.of(m.get("rect")) != null) {
                redacted.add(Rect.of(m.get("rect")));
            }
        }
        List<Rect> carets = new ArrayList<>();
        for (Object c : list(r.get("carets"))) {
            if (c instanceof Map<?, ?> m && Rect.of(m.get("rect")) != null) {
                carets.add(Rect.of(m.get("rect")));
            }
        }
        Map<String, Object> focus = new LinkedHashMap<>();
        if (r.get("focus") instanceof Map<?, ?> f) {
            f.forEach((k, v) -> focus.put(String.valueOf(k), v));
        }
        String id = str(r.get("captureId"));
        return new Capture(id, r.get("seq") instanceof Number n ? n.longValue() : 0L, str(r.get("hash")),
                i(r.get("width")), i(r.get("height")), r.get("scale") instanceof Number n ? n.doubleValue() : 1.0,
                str(r.get("space")), str(r.get("window")), i(origin.get("x")), i(origin.get("y")), windows, surfaces,
                marks, redacted, carets, focus, png);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static String str(Object value) {
        return value instanceof String s ? s : null;
    }

    static int i(Object value) {
        return value instanceof Number n ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE,
                n.longValue())) : 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Capture c && java.util.Objects.equals(captureId, c.captureId)
                && java.util.Objects.equals(hash, c.hash) && seq == c.seq;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(captureId, hash, seq);
    }

    /** The id, the size and the hash; never the bytes. */
    @Override
    public String toString() {
        return "Capture[" + captureId + ", " + width + "x" + height + ", " + hash + "]";
    }
}
