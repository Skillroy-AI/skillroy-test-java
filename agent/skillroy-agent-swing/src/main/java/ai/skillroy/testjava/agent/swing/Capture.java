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

import ai.skillroy.testjava.agent.api.spi.RegionResolver;
import ai.skillroy.testjava.agent.api.spi.SurfaceSource;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.table.JTableHeader;
import javax.swing.text.Caret;
import javax.swing.text.JTextComponent;

/**
 * PAINT capture (phase7-contracts.md section 3.1, A61): {@code printAll} into an image on the EDT, then PNG encoding
 * and the raster hash off the EDT. Needs no Robot and works when the window is covered.
 *
 * <p><b>What is painted.</b> A window is painted from its root pane ({@link Windows#paintRoot}), so the image holds no
 * decoration, real or imitated (BS9: {@code XFramePeer.print} draws imitation Motif decorations into every
 * {@code printAll} of a frame on the X toolkit; gap 76). Scopes: {@code WINDOW} (the root pane, {@code origin} the
 * insets), {@code COMPONENT} (the component, {@code origin} its window offset; a window's root pane for a window),
 * {@code REGION} (a rectangle of the target through a clipped, translated graphics) and {@code SCREEN} (every showing
 * window from its root pane at its content's screen position on one canvas, the union of the windows' screen bounds, in
 * the module's stacking order; {@code stackingAssumed}). The mapping rule: an image pixel {@code (px, py)} lies at the
 * point {@code (origin.x + px / scale, origin.y + py / scale)} of the result's {@code space}. The heavyweight
 * components below a lightweight paint root (which a root pane's printing does not reach) are painted on top at their
 * place.
 *
 * <p><b>What is reported.</b> The geometry ({@code space}, {@code window}, {@code origin}, {@code scale},
 * {@code windows}), the redacted rectangles (blanked grey after the paint, before the image leaves the task;
 * {@link Redaction}), the surfaces that are not Swing-painted (the registered {@code SurfaceSource}s' and unclaimed
 * heavyweights), the marks' geometry and labels with {@code annotateRefs} (the agent draws nothing, D346), the focused
 * text components' carets, the focus state and, off the EDT, the {@link #hash} of the decoded raster.
 *
 * <p><b>Bounds</b> (section 2.4): at most {@value #MAX_SIDE} pixels a side after {@code scale} (the cap lowers the
 * effective scale); a raster larger than an eighth of the heap headroom is refused ({@code INVALID_PARAMS} naming both
 * figures); an {@code OutOfMemoryError} around the allocation or the paint is answered {@code INTERNAL_ERROR}; at most
 * {@value #MAX_SCREEN_WINDOWS} windows in a {@code SCREEN} composite, {@value #MAX_MARKS} marks and
 * {@value #MAX_REDACTED} reported redacted rectangles.
 *
 * <p><b>Guard</b> (phase4-contracts.md sections 2.5 and 14.1): when {@code printAll} of a paint root throws (BS8: the
 * GL canvas of an embedded browser rendered off-screen throws {@code IllegalStateException}), the image is painted
 * again part by part: a part whose {@code printAll} throws is painted child by child, a part that alone throws is
 * painted as a placeholder rectangle and reported as {@link Skipped}; when no part alone throws, the root itself is the
 * placeholder.
 *
 * <p>Stateless. {@link #take} and {@link #paint} must run on the EDT; {@link #png} and {@link #hash} on any thread.
 */
final class Capture {

    /** Largest width and height of a capture. */
    static final int MAX_SIDE = 4096;
    /** Most windows in a SCREEN composite. */
    static final int MAX_SCREEN_WINDOWS = 32;
    /** Most marks reported. */
    static final int MAX_MARKS = 200;
    /** Most redacted rectangles reported (more are blanked and counted). */
    static final int MAX_REDACTED = 64;
    /** Smallest and largest {@code scale}. */
    static final double MIN_SCALE = 0.25;
    static final double MAX_SCALE = 4.0;
    /** Longest mark label. */
    static final int MAX_LABEL = 120;
    /** Fill of a placeholder. */
    static final Color PLACEHOLDER_FILL = new Color(224, 224, 224);
    /** Outline and cross of a placeholder. */
    static final Color PLACEHOLDER_LINE = new Color(96, 96, 96);
    /** The SCREEN canvas outside every window's content (decoration bands, gaps between windows). */
    static final Color SCREEN_BACKGROUND = new Color(64, 64, 64);
    private static final int MAX_ERROR = 200;

    /** The heap headroom in bytes (a seam: tests hand in a fake figure). */
    interface Heap {
        long headroom();
    }

    /** {@code maxMemory() - (totalMemory() - freeMemory())}, read before the allocation. */
    static final Heap RUNTIME_HEAP = new Heap() {
        @Override
        public long headroom() {
            Runtime rt = Runtime.getRuntime();
            return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        }
    };

    static volatile Heap heap = RUNTIME_HEAP;

    /** The capture's scope. */
    enum Scope { WINDOW, COMPONENT, SCREEN, REGION }

    /** A component painted as a placeholder because its {@code printAll} threw. */
    static final class Skipped {
        final Component component;
        final String error;

        Skipped(Component component, Throwable error) {
            this.component = component;
            String text = error.getClass().getSimpleName() + (error.getMessage() == null ? ""
                    : ": " + error.getMessage());
            this.error = text.length() > MAX_ERROR ? text.substring(0, MAX_ERROR) : text;
        }
    }

    /** What to capture, parsed on the calling thread. */
    static final class Request {
        Scope scope;
        Rectangle region;
        String regionId;
        double scale = 1.0;
        boolean redact = true;
        boolean annotate;
    }

    /** The geometry a hit-test may later name by {@code captureId}. */
    static final class Geometry {
        final String space;
        final Component window;
        final double originX;
        final double originY;
        final double scale;
        final int width;
        final int height;

        Geometry(String space, Component window, double originX, double originY, double scale, int width,
                 int height) {
            this.space = space;
            this.window = window;
            this.originX = originX;
            this.originY = originY;
            this.scale = scale;
            this.width = width;
            this.height = height;
        }

        /** The point of the space an image pixel lies at (the mapping rule). */
        Point toSpace(double px, double py) {
            return new Point((int) Math.floor(originX + px / scale), (int) Math.floor(originY + py / scale));
        }
    }

    /** The outcome of {@link #take}: the raster, the result fields (without the PNG) and the geometry. */
    static final class Shot {
        final BufferedImage image;
        final Map<String, Object> fields;
        final Geometry geometry;
        final List<Skipped> skipped;

        Shot(BufferedImage image, Map<String, Object> fields, Geometry geometry, List<Skipped> skipped) {
            this.image = image;
            this.fields = fields;
            this.geometry = geometry;
            this.skipped = skipped;
        }
    }

    /** One painted root: the component and where its (0, 0) lies in the capture's space; for SCREEN its layer. */
    private static final class Root {
        final Component paint;
        final Component window;
        final double x;
        final double y;
        final Windows.Layer layer;

        Root(Component paint, Component window, double x, double y, Windows.Layer layer) {
            this.paint = paint;
            this.window = window;
            this.x = x;
            this.y = y;
            this.layer = layer;
        }
    }

    private Capture() {
    }

    // ------------------------------------------------------------------------------------------------------ take

    /**
     * Takes a PAINT capture of {@code target}, which the caller has resolved (the default window when none was named)
     * and checked to be showing; ignored for {@code SCREEN}. EDT only.
     *
     * @param seq the event bus position read in the same EDT task
     * @throws RpcException INVALID_PARAMS, NOT_FOUND (an unknown region id, nothing showing), INTERNAL_ERROR (out of
     *     memory)
     */
    static Shot take(ComponentView view, Windows windows, SwingSpis spis, Component target, Request req, long seq) {
        Component t = target;
        if (req.scope != Scope.SCREEN && t == null) {
            throw Errors.notFound("nothing to capture: no window is showing", Errors.data());
        }
        Scope scope = req.scope != null ? req.scope : (t instanceof Window ? Scope.WINDOW : Scope.COMPONENT);
        String space = scope == Scope.SCREEN ? "SCREEN" : "WINDOW";
        List<Root> roots = new ArrayList<Root>();
        Component window = null;
        double ox;
        double oy;
        double w;
        double h;
        Color background;
        Map<String, Object> regionField = null;
        Rectangle clip = null;
        switch (scope) {
            case SCREEN: {
                List<Windows.Layer> layers = windows.stacking();
                if (layers.isEmpty()) {
                    throw Errors.notFound("nothing to capture: no window is showing", Errors.data());
                }
                if (layers.size() > MAX_SCREEN_WINDOWS) {
                    layers = layers.subList(layers.size() - MAX_SCREEN_WINDOWS, layers.size());
                }
                Rectangle union = null;
                for (Windows.Layer l : layers) {
                    union = union == null ? new Rectangle(l.screen) : union.union(l.screen);
                }
                ox = union.x;
                oy = union.y;
                w = union.width;
                h = union.height;
                for (Windows.Layer l : layers) {
                    roots.add(new Root(l.paint, l.window, l.screen.x + l.content.x, l.screen.y + l.content.y, l));
                }
                background = SCREEN_BACKGROUND;
                break;
            }
            case WINDOW: {
                Window win = t instanceof Window ? (Window) t : Windows.windowOf(t);
                Component paint = win != null ? Windows.paintRoot(win) : t;
                window = win;
                Point off = offsetIn(paint, win);
                ox = off.x;
                oy = off.y;
                w = paint.getWidth();
                h = paint.getHeight();
                roots.add(new Root(paint, win, ox, oy, null));
                background = paint.getBackground();
                break;
            }
            case COMPONENT: {
                Component paint = t instanceof Window ? Windows.paintRoot((Window) t) : t;
                window = Windows.windowOf(t);
                Point off = offsetIn(paint, window);
                ox = off.x;
                oy = off.y;
                w = paint.getWidth();
                h = paint.getHeight();
                roots.add(new Root(paint, window, ox, oy, null));
                background = paint.getBackground();
                break;
            }
            default: { // REGION
                Rectangle r = req.region;
                if (req.regionId != null) {
                    r = spis.rectOf(t, req.regionId);
                    if (r == null) {
                        throw Errors.notFound("no region '" + req.regionId + "' on " + view.describe(t),
                                Errors.data("region", req.regionId, "ref", view.ref(t)));
                    }
                    regionField = new LinkedHashMap<String, Object>();
                    regionField.put("id", req.regionId);
                    regionField.put("rect", rectJson(r));
                }
                Rectangle inside = r.intersection(new Rectangle(0, 0, t.getWidth(), t.getHeight()));
                if (inside.isEmpty()) {
                    throw Errors.invalid("the region " + rectText(r) + " lies outside " + view.describe(t),
                            Errors.data("param", "region", "reason", "outside"));
                }
                window = Windows.windowOf(t);
                Point toff = offsetIn(t, window);
                ox = toff.x + inside.x;
                oy = toff.y + inside.y;
                w = inside.width;
                h = inside.height;
                Component paint = t instanceof Window ? Windows.paintRoot((Window) t) : t;
                Point poff = offsetIn(paint, window);
                roots.add(new Root(paint, window, poff.x, poff.y, null));
                background = paint.getBackground();
                clip = new Rectangle(0, 0, inside.width, inside.height);
                break;
            }
        }
        if (w <= 0 || h <= 0) {
            throw Errors.invalid("nothing to capture: the component is " + (int) w + " x " + (int) h,
                    Errors.data("reason", "empty"));
        }
        double scale = Math.min(req.scale, Math.min(MAX_SIDE / w, MAX_SIDE / h));
        int iw = Math.max(1, (int) Math.floor(w * scale));
        int ih = Math.max(1, (int) Math.floor(h * scale));
        heapBound(4L * iw * ih, "scale", "lower 'scale' or capture a smaller scope");
        Geometry geometry = new Geometry(space, window, ox, oy, scale, iw, ih);
        List<Skipped> skipped = new ArrayList<Skipped>();
        BufferedImage image;
        try {
            image = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(background != null ? background : Color.WHITE);
                g.fillRect(0, 0, iw, ih);
                g.scale(scale, scale);
                if (clip != null) {
                    g.clipRect(clip.x, clip.y, clip.width, clip.height);
                }
                for (Root root : roots) {
                    Graphics2D rg = (Graphics2D) g.create();
                    try {
                        rg.translate(root.x - ox, root.y - oy);
                        paintGuarded(root.paint, rg, skipped);
                    } finally {
                        rg.dispose();
                    }
                }
            } finally {
                g.dispose();
            }
        } catch (OutOfMemoryError e) {
            throw new RpcException(ErrorCodes.INTERNAL_ERROR, "out of memory while capturing an image of " + iw
                    + " x " + ih + " pixels; lower 'scale' or capture a smaller scope", Errors.data("reason",
                    "out of memory"));
        }

        Walk walk = new Walk(view, spis, geometry, roots, req.redact, req.annotate);
        walk.run();
        if (req.redact) {
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(Redaction.FILL);
                for (Rectangle r : walk.blankedPixels) {
                    g.fillRect(r.x, r.y, r.width, r.height);
                }
            } finally {
                g.dispose();
            }
        }

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("seq", Long.valueOf(seq));
        m.put("mode", "PAINT");
        m.put("scope", scope.name());
        m.put("space", space);
        if (window != null && scope != Scope.SCREEN) {
            m.put("window", view.ref(window));
        }
        m.put("origin", point(ox, oy));
        m.put("scale", Double.valueOf(scale));
        m.put("windows", walk.windowsJson());
        m.put("surfaces", walk.surfaces);
        if (req.annotate) {
            m.put("marks", walk.marksJson());
            if (walk.marksTruncated) {
                m.put("marksTruncated", Boolean.TRUE);
            }
        }
        m.put("carets", walk.carets);
        m.put("focus", walk.focus(scope == Scope.SCREEN ? null : window, scope == Scope.SCREEN));
        m.put("redacted", walk.redacted);
        if (walk.redactedTruncated) {
            m.put("redactedTruncated", Boolean.TRUE);
        }
        if (scope == Scope.SCREEN) {
            m.put("stackingAssumed", Boolean.TRUE);
        }
        if (regionField != null) {
            m.put("region", regionField);
        }
        return new Shot(image, m, geometry, skipped);
    }

    /** What a glass capture reports beside its pixels: the geometry facts of the windows the screen rectangle holds. */
    static final class Facts {
        List<Rectangle> blanked;
        List<Object> redacted;
        boolean redactedTruncated;
        List<Object> carets;
        Map<String, Object> focus;
        List<Object> windows;
        List<Object> surfaces;
    }

    /**
     * The facts of a screen rectangle (a person's {@code ROBOT} capture, section 3.4): the mask rectangles, the carets,
     * the focus, the windows and the surfaces of the given layers (every window the rectangle meets), in the
     * rectangle's pixels (scale 1); {@code focus.active} is the captured window's, or for a {@code SCREEN} capture
     * (null) whether any window is active. EDT only.
     */
    static Facts facts(ComponentView view, SwingSpis spis, List<Windows.Layer> layers, Rectangle screen,
                       boolean redact, Component captured) {
        Geometry g = new Geometry("SCREEN", null, screen.x, screen.y, 1.0, screen.width, screen.height);
        List<Root> roots = new ArrayList<Root>();
        for (Windows.Layer l : layers) {
            roots.add(new Root(l.paint, l.window, l.screen.x + l.content.x, l.screen.y + l.content.y, l));
        }
        Walk walk = new Walk(view, spis, g, roots, redact, false);
        walk.run();
        Facts f = new Facts();
        f.blanked = walk.blankedPixels;
        f.redacted = walk.redacted;
        f.redactedTruncated = walk.redactedTruncated;
        f.carets = walk.carets;
        f.focus = walk.focus(captured, captured == null);
        f.windows = walk.windowsJson();
        f.surfaces = walk.surfaces;
        return f;
    }

    /**
     * Section 2.4's heap bound: a raster of {@code needed} bytes is refused when it is larger than an eighth of the heap
     * headroom read now ({@code INVALID_PARAMS} naming the needed and the free megabytes). Used by the PAINT capture and
     * the glass capture (its grab and its conversion copy each).
     */
    static void heapBound(long needed, String param, String hint) {
        long headroom = heap.headroom();
        if (needed > headroom / 8) {
            throw Errors.invalid("the capture needs " + mb(needed) + " MB, more than an eighth of the " + mb(headroom)
                    + " MB of free heap; " + hint, Errors.data("param", param, "neededMb", Long.valueOf(mb(needed)),
                    "freeMb", Long.valueOf(mb(headroom))));
        }
    }

    /** The component's offset in its window's space (its own location for a window; the topmost ancestor's else). */
    static Point offsetIn(Component c, Component window) {
        if (c == window || c instanceof Window) {
            return new Point(0, 0);
        }
        return ComponentView.windowOffset(c);
    }

    private static long mb(long bytes) {
        return (bytes + (1L << 20) - 1) >> 20;
    }

    // ------------------------------------------------------------------------------------------------------ walk

    /** One walk over the painted trees: the redaction, the marks, the carets, the surfaces, the focus owner. */
    private static final class Walk {
        final ComponentView view;
        final SwingSpis spis;
        final Geometry g;
        final List<Root> roots;
        final boolean redact;
        final boolean annotate;
        final Rectangle image;
        final List<Rectangle> blankedPixels = new ArrayList<Rectangle>();
        final List<Object> redacted = new ArrayList<Object>();
        boolean redactedTruncated;
        final List<Object> carets = new ArrayList<Object>();
        final List<Object> surfaces = new ArrayList<Object>();
        final List<Map<String, Object>> marks = new ArrayList<Map<String, Object>>();
        boolean marksTruncated;
        final List<SurfaceSource.Surface> known;
        final Map<Component, Boolean> claimed = new IdentityHashMap<Component, Boolean>();
        final Map<Component, Root> rootOf = new IdentityHashMap<Component, Root>();

        Walk(ComponentView view, SwingSpis spis, Geometry g, List<Root> roots, boolean redact, boolean annotate) {
            this.view = view;
            this.spis = spis;
            this.g = g;
            this.roots = roots;
            this.redact = redact;
            this.annotate = annotate;
            this.image = new Rectangle(0, 0, g.width, g.height);
            this.known = spis.surfaces();
        }

        void run() {
            for (Root r : roots) {
                rootOf.put(r.paint, r);
                if (redact && blankAncestors(r)) {
                    caret(r, r.paint);
                    continue; // a masked ancestor blanked the whole paint root: nothing below is marked
                }
                Component parent = r.paint.getParent();
                walk(r, r.paint, !(r.paint instanceof Window) && parent != null && view.masked(parent));
            }
            for (SurfaceSource.Surface s : known) {
                Root r = rootFor(s.component);
                if (r == null || !s.component.isVisible()) {
                    continue;
                }
                Rectangle rect = toImage(r, s.component, new Rectangle(0, 0, s.component.getWidth(),
                        s.component.getHeight()));
                if (rect == null) {
                    continue;
                }
                claimed.put(s.component, Boolean.TRUE);
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("kind", s.kind == null ? "WEB" : s.kind);
                one.put("rect", rectJson(rect));
                one.put("ref", view.ref(s.component));
                if (s.browserRef != null) {
                    one.put("browser", s.browserRef);
                }
                one.put("redacted", Boolean.valueOf(view.masked(s.component) || intersectsBlanked(rect)));
                surfaces.add(one);
            }
            for (Root r : roots) {
                heavy(r, r.paint);
            }
            sortMarks();
        }

        private Root rootFor(Component c) {
            for (Component p = c; p != null; p = p.getParent()) {
                Root r = rootOf.get(p);
                if (r != null) {
                    return r;
                }
            }
            return null;
        }

        /**
         * Blanks what the ancestors of a paint root, up to and including its window, mask (fix wave of the Phase 7
         * review, FA, L2-F1): a paint root inside a masked component (a {@code COMPONENT} or {@code REGION} capture of
         * a child of a masked container, or any capture of a window a mask names, whose root pane is the paint root)
         * is otherwise never blanked, since the walk visits the paint root and what lies below it only. An ancestor's
         * {@code regions} are blanked where they meet the image; an ancestor masked whole blanks its rectangle and the
         * paint root's own (which a {@code COMPONENT} capture paints whole even where it overhangs the ancestor),
         * reported as one rectangle under the ancestor's ref.
         *
         * @return true when an ancestor was masked whole, so that nothing below the paint root is marked or walked
         */
        private boolean blankAncestors(Root root) {
            if (root.paint instanceof Window) {
                return false;
            }
            boolean whole = false;
            for (Component a = root.paint.getParent(); a != null; a = a.getParent()) {
                String rule = view.redaction().ownRule(a);
                List<Rectangle> blank = view.redaction().blanked(a, rule);
                boolean all = isWhole(a, rule, blank);
                for (Rectangle b : blank) {
                    Rectangle rect = toImage(root, a, b);
                    if (all) {
                        Rectangle own = toImage(root, root.paint, new Rectangle(0, 0, root.paint.getWidth(),
                                root.paint.getHeight()));
                        rect = rect == null ? own : own == null ? rect : rect.union(own);
                    }
                    addBlanked(a, rect, rule);
                }
                whole |= all;
                if (a instanceof Window) {
                    break; // a window's owner is its parent in AWT; the masked-text rule stops here too
                }
            }
            return whole;
        }

        private static boolean isWhole(Component c, String rule, List<Rectangle> blank) {
            return rule != null && blank.size() == 1
                    && blank.get(0).equals(new Rectangle(0, 0, c.getWidth(), c.getHeight()));
        }

        /** Adds one blanked rectangle (image pixels; null is ignored) and reports it under the component's ref. */
        private void addBlanked(Component c, Rectangle rect, String rule) {
            if (rect == null) {
                return;
            }
            blankedPixels.add(rect);
            if (redacted.size() < MAX_REDACTED) {
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("ref", view.ref(c));
                one.put("rect", rectJson(rect));
                one.put("rule", rule != null ? rule : Redaction.CAPTURE_MASK);
                redacted.add(one);
            } else {
                redactedTruncated = true;
            }
        }

        private void walk(Root root, Component c, boolean insideMasked) {
            if (!c.isVisible()) {
                return;
            }
            String rule = view.redaction().ownRule(c);
            boolean masked = insideMasked || rule != null;
            boolean whole = false;
            if (redact) {
                List<Rectangle> blank = view.redaction().blanked(c, rule);
                whole = isWhole(c, rule, blank);
                for (Rectangle b : blank) {
                    addBlanked(c, toImage(root, c, b), rule);
                }
            }
            // the caret is geometry a comparison blanks: reported for a masked field too (P7 13.2 item 1)
            caret(root, c);
            if (!masked) {
                if (annotate) {
                    mark(root, c);
                }
            } else if (whole || !redact) {
                return; // nothing below a blanked component is visible, marked or typed into
            }
            if (c instanceof Container) {
                for (Component k : ((Container) c).getComponents()) {
                    if (k != null && !(k instanceof Window)) {
                        walk(root, k, masked);
                    }
                }
            }
        }

        private void heavy(Root root, Component c) {
            if (!(c instanceof Container)) {
                return;
            }
            for (Component k : ((Container) c).getComponents()) {
                if (k == null || k instanceof Window || !k.isVisible()) {
                    continue;
                }
                if (claimed.containsKey(k)) {
                    continue;
                }
                if (k.isDisplayable() && !k.isLightweight()) {
                    Rectangle rect = toImage(root, k, new Rectangle(0, 0, k.getWidth(), k.getHeight()));
                    if (rect != null) {
                        Map<String, Object> one = new LinkedHashMap<String, Object>();
                        one.put("kind", "HEAVYWEIGHT");
                        one.put("rect", rectJson(rect));
                        one.put("ref", view.ref(k));
                        one.put("redacted", Boolean.valueOf(view.masked(k) || intersectsBlanked(rect)));
                        surfaces.add(one);
                    }
                } else {
                    heavy(root, k);
                }
            }
        }

        private boolean intersectsBlanked(Rectangle rect) {
            for (Rectangle b : blankedPixels) {
                if (b.intersects(rect)) {
                    return true;
                }
            }
            return false;
        }

        /** A rectangle of a component's own coordinates in image pixels, clipped to the image; null when outside. */
        Rectangle toImage(Root root, Component c, Rectangle local) {
            Point off = offsetIn(c, root.window);
            Point base = offsetIn(root.paint, root.window);
            double sx = root.x + (off.x - base.x) + local.x;
            double sy = root.y + (off.y - base.y) + local.y;
            int x0 = (int) Math.floor((sx - g.originX) * g.scale);
            int y0 = (int) Math.floor((sy - g.originY) * g.scale);
            int x1 = (int) Math.ceil((sx + local.width - g.originX) * g.scale);
            int y1 = (int) Math.ceil((sy + local.height - g.originY) * g.scale);
            Rectangle r = new Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)).intersection(image);
            return r.isEmpty() ? null : r;
        }

        // ------------------------------------------------------------------------------------------------ marks

        private static boolean skipChildrenForMarks(Component c) {
            return c instanceof JComboBox || c instanceof JScrollBar;
        }

        private boolean insideSkipped(Component c) {
            for (Component p = c.getParent(); p != null && !(p instanceof Window); p = p.getParent()) {
                if (skipChildrenForMarks(p)) {
                    return true;
                }
            }
            return false;
        }

        private void mark(Root root, Component c) {
            if (insideSkipped(c)) {
                return;
            }
            List<RegionResolver.Region> regions = spis.regionsOf(c);
            if (!regions.isEmpty()) {
                for (RegionResolver.Region region : regions) {
                    Rectangle rect = visible(root, c, region.rect);
                    if (rect != null) {
                        addMark(c, rect, label("region " + region.id, view.name(c), null), null, region.id);
                    }
                }
                return;
            }
            if (c instanceof JTable) {
                JTable table = (JTable) c;
                Rectangle vis = table.getVisibleRect();
                for (int row = 0; row < table.getRowCount(); row++) {
                    Rectangle cell = table.getCellRect(row, 0, true);
                    Rectangle rowRect = new Rectangle(0, cell.y, table.getWidth(), cell.height);
                    if (!rowRect.intersects(vis)) {
                        continue;
                    }
                    Rectangle rect = visible(root, c, rowRect);
                    if (rect != null) {
                        int model = table.convertRowIndexToModel(row);
                        addMark(c, rect, rowLabel(table, row), Integer.valueOf(model), null);
                    }
                }
                return;
            }
            if (c instanceof JTabbedPane) {
                JTabbedPane tabs = (JTabbedPane) c;
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    Rectangle b = tabs.getBoundsAt(i);
                    if (b != null) {
                        Rectangle rect = visible(root, c, b);
                        if (rect != null) {
                            addMark(c, rect, label("tab", ComponentText.visible(tabs.getTitleAt(i)), null), null,
                                    null);
                        }
                    }
                }
                return;
            }
            if (c instanceof JTableHeader) {
                JTableHeader header = (JTableHeader) c;
                StringBuilder cols = new StringBuilder();
                if (header.getColumnModel() != null) {
                    for (int i = 0; i < header.getColumnModel().getColumnCount(); i++) {
                        if (i > 0) {
                            cols.append(" | ");
                        }
                        cols.append(header.getColumnModel().getColumn(i).getHeaderValue());
                    }
                }
                Rectangle rect = visible(root, c, null);
                if (rect != null) {
                    addMark(c, rect, label("header", null, cols.toString()), null, null);
                }
                return;
            }
            if (!markable(c)) {
                return;
            }
            Rectangle rect = visible(root, c, null);
            if (rect != null) {
                addMark(c, rect, label(Roles.of(c), view.name(c), view.text(c)), null, null);
            }
        }

        private boolean markable(Component c) {
            if (c instanceof AbstractButton || c instanceof JTextComponent || c instanceof JComboBox
                    || c instanceof JList || c instanceof JTree || c instanceof JSlider || c instanceof JSpinner) {
                return true;
            }
            if (c instanceof JLabel) {
                String t = view.text(c);
                return t != null && !t.trim().isEmpty();
            }
            return spis.fires(c) || view.enrichedName(c) != null;
        }

        /** The visible part of a rectangle of the component (the whole component for null), in image pixels. */
        private Rectangle visible(Root root, Component c, Rectangle local) {
            Rectangle r = local == null ? new Rectangle(0, 0, c.getWidth(), c.getHeight()) : new Rectangle(local);
            if (c instanceof JComponent) {
                r = r.intersection(((JComponent) c).getVisibleRect());
            }
            if (r.isEmpty()) {
                return null;
            }
            return toImage(root, c, r);
        }

        private void addMark(Component c, Rectangle rect, String label, Integer row, String region) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ref", view.ref(c));
            m.put("rect", rect);
            m.put("label", label);
            m.put("role", Roles.of(c));
            if (row != null) {
                m.put("row", Long.valueOf(row.longValue()));
            }
            if (region != null) {
                m.put("region", region);
            }
            m.put("component", c);
            marks.add(m);
        }

        private String rowLabel(JTable table, int row) {
            StringBuilder sb = new StringBuilder("row ").append(table.convertRowIndexToModel(row)).append(':');
            for (int col = 0; col < table.getColumnCount() && sb.length() < MAX_LABEL; col++) {
                Object v;
                try {
                    v = table.getValueAt(row, col);
                } catch (RuntimeException e) {
                    v = null;
                }
                sb.append(col == 0 ? " " : " | ").append(v == null ? "" : String.valueOf(v));
            }
            return cap(sb.toString());
        }

        private void sortMarks() {
            Collections.sort(marks, new Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> a, Map<String, Object> b) {
                    Rectangle ra = (Rectangle) a.get("rect");
                    Rectangle rb = (Rectangle) b.get("rect");
                    if (ra.y != rb.y) {
                        return ra.y < rb.y ? -1 : 1;
                    }
                    return ra.x == rb.x ? 0 : (ra.x < rb.x ? -1 : 1);
                }
            });
            if (roots.size() > 1) {
                // SCREEN: a mark whose centre a window higher in the stack covers is not visible
                List<Map<String, Object>> kept = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> m : marks) {
                    if (!covered(m)) {
                        kept.add(m);
                    }
                }
                marks.clear();
                marks.addAll(kept);
            }
        }

        private boolean covered(Map<String, Object> m) {
            Component c = (Component) m.get("component");
            Root own = rootFor(c);
            Rectangle rect = (Rectangle) m.get("rect");
            int cx = rect.x + rect.width / 2;
            int cy = rect.y + rect.height / 2;
            boolean above = false;
            for (Root r : roots) {
                if (r == own) {
                    above = true;
                    continue;
                }
                if (above && r.layer != null) {
                    Rectangle wr = windowRect(r);
                    if (wr != null && wr.contains(cx, cy)) {
                        return true;
                    }
                }
            }
            return false;
        }

        List<Object> marksJson() {
            List<Object> out = new ArrayList<Object>();
            int n = 0;
            for (Map<String, Object> m : marks) {
                if (n >= MAX_MARKS) {
                    marksTruncated = true;
                    break;
                }
                n++;
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("mark", Long.valueOf(n));
                one.put("ref", m.get("ref"));
                one.put("rect", rectJson((Rectangle) m.get("rect")));
                one.put("label", m.get("label"));
                one.put("role", m.get("role"));
                if (m.containsKey("row")) {
                    one.put("row", m.get("row"));
                }
                if (m.containsKey("region")) {
                    one.put("region", m.get("region"));
                }
                out.add(one);
            }
            return out;
        }

        // ------------------------------------------------------------------------------------- carets and focus

        private void caret(Root root, Component c) {
            if (!(c instanceof JTextComponent)) {
                return;
            }
            JTextComponent tc = (JTextComponent) c;
            if (!tc.hasFocus()) {
                return;
            }
            Caret caret = tc.getCaret();
            if (caret == null || caret.getDot() != caret.getMark()) {
                return; // a selection has no caret entry
            }
            Rectangle r;
            try {
                r = tc.modelToView(caret.getDot());
            } catch (Exception e) {
                return;
            } catch (LinkageError e) {
                return;
            }
            if (r == null) {
                return;
            }
            int width = 1;
            try {
                Object uiWidth = UIManager.get("Caret.width");
                if (uiWidth instanceof Integer) {
                    width = Math.max(1, ((Integer) uiWidth).intValue());
                }
            } catch (RuntimeException ignored) {
                // the default width
            }
            Rectangle wide = new Rectangle(r.x - 2, r.y, width + 4, Math.max(1, r.height));
            Rectangle rect = toImage(root, c, wide);
            if (rect != null) {
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("ref", view.ref(c));
                one.put("rect", rectJson(rect));
                carets.add(one);
            }
        }

        Map<String, Object> focus(Component capturedWindow, boolean screen) {
            KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            Component owner = kfm.getFocusOwner();
            Window focused = kfm.getFocusedWindow();
            Map<String, Object> f = new LinkedHashMap<String, Object>();
            f.put("owner", owner == null ? null : view.ref(owner));
            Map<String, Object> locator = null;
            Map<String, Object> ownerRect = null;
            if (owner != null && owner.isShowing()) {
                locator = HitTest.firstUniqueLocator(view, spis, owner);
                Root r = rootFor(owner);
                if (r != null) {
                    Rectangle rect = toImage(r, owner, new Rectangle(0, 0, owner.getWidth(), owner.getHeight()));
                    if (rect != null) {
                        ownerRect = rectJson(rect);
                    }
                }
            }
            f.put("ownerLocator", locator);
            f.put("ownerRect", ownerRect);
            f.put("window", focused == null ? null : view.ref(focused));
            boolean active;
            if (screen) {
                active = Windows.activeWindow() != null;
            } else {
                active = capturedWindow instanceof Window && ((Window) capturedWindow).isActive();
            }
            f.put("active", Boolean.valueOf(active));
            return f;
        }

        // -------------------------------------------------------------------------------------------- windows

        private Rectangle windowRect(Root r) {
            Component win = r.window != null ? r.window : r.paint;
            Rectangle local;
            Point base = offsetIn(r.paint, r.window);
            if (r.layer != null) {
                double sx = r.layer.screen.x;
                double sy = r.layer.screen.y;
                int x0 = (int) Math.floor((sx - g.originX) * g.scale);
                int y0 = (int) Math.floor((sy - g.originY) * g.scale);
                int x1 = (int) Math.ceil((sx + r.layer.screen.width - g.originX) * g.scale);
                int y1 = (int) Math.ceil((sy + r.layer.screen.height - g.originY) * g.scale);
                Rectangle rect = new Rectangle(x0, y0, x1 - x0, y1 - y0).intersection(image);
                return rect.isEmpty() ? null : rect;
            }
            local = new Rectangle(-base.x, -base.y, win.getWidth(), win.getHeight());
            return toImage(r, r.paint, local);
        }

        List<Object> windowsJson() {
            List<Object> out = new ArrayList<Object>();
            for (Root r : roots) {
                Component win = r.window != null ? r.window : r.paint;
                Rectangle rect = windowRect(r);
                if (rect == null) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("ref", view.ref(win));
                one.put("rect", rectJson(rect));
                out.add(one);
            }
            return out;
        }
    }

    private static String label(String role, String name, String text) {
        StringBuilder sb = new StringBuilder(role);
        if (name != null && !name.isEmpty()) {
            sb.append(" '").append(name).append('\'');
        }
        if (text != null && !text.isEmpty() && !text.equals(name)) {
            sb.append(": ").append(text);
        }
        return cap(sb.toString());
    }

    private static String cap(String s) {
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= MAX_LABEL ? one : one.substring(0, MAX_LABEL) + "…";
    }

    static Map<String, Object> rectJson(Rectangle r) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", Long.valueOf(r.x));
        m.put("y", Long.valueOf(r.y));
        m.put("w", Long.valueOf(r.width));
        m.put("h", Long.valueOf(r.height));
        return m;
    }

    static Map<String, Object> point(double x, double y) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", Long.valueOf(Math.round(x)));
        m.put("y", Long.valueOf(Math.round(y)));
        return m;
    }

    private static String rectText(Rectangle r) {
        return String.format(Locale.ROOT, "{x: %d, y: %d, w: %d, h: %d}", r.x, r.y, r.width, r.height);
    }

    // ----------------------------------------------------------------------------------------------- painting

    /**
     * Paints the component into a new RGB image, guarded, scaled down to fit {@value #MAX_SIDE} pixels a side: every
     * part painted as a placeholder is added to {@code skipped}. The component is painted as it is (a window is not
     * replaced by its root pane here). EDT only.
     */
    static BufferedImage paint(Component c, List<Skipped> skipped) {
        int w = c.getWidth();
        int h = c.getHeight();
        if (w <= 0 || h <= 0) {
            throw Errors.invalid("nothing to capture: the component is " + w + " x " + h,
                    Errors.data("reason", "empty"));
        }
        double scale = Math.min(1.0, Math.min((double) MAX_SIDE / w, (double) MAX_SIDE / h));
        int iw = Math.max(1, (int) Math.floor(w * scale));
        int ih = Math.max(1, (int) Math.floor(h * scale));
        BufferedImage image = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            Color background = c.getBackground();
            g.setColor(background != null ? background : Color.WHITE);
            g.fillRect(0, 0, iw, ih);
            if (scale < 1.0) {
                g.scale(scale, scale);
            }
            paintGuarded(c, g, skipped);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * {@code printAll} of a paint root with the guard; for a lightweight root, the heavyweights below it are painted on
     * top afterwards (a Swing container's printing does not reach them).
     */
    static void paintGuarded(Component c, Graphics2D g, List<Skipped> skipped) {
        Throwable failure = print(c, g);
        if (failure != null) {
            // What was painted before the failure stays; the parts are painted again on top of it.
            List<Skipped> found = new ArrayList<Skipped>();
            guarded(c, g, found, failure);
            heavyweights(c, g, found);
            if (found.isEmpty()) {
                placeholder(g, c.getWidth(), c.getHeight(), true);
                found.add(new Skipped(c, failure));
            }
            skipped.addAll(found);
        } else if (c.isLightweight()) {
            List<Skipped> found = new ArrayList<Skipped>();
            heavyweights(c, g, found);
            skipped.addAll(found);
        }
    }

    /** {@code printAll} on a graphics of its own; the failure, or null. */
    private static Throwable print(Component c, Graphics2D g) {
        Graphics2D cg = (Graphics2D) g.create();
        try {
            c.printAll(cg);
            return null;
        } catch (RuntimeException e) {
            return e;
        } catch (LinkageError e) {
            return e;
        } finally {
            cg.dispose();
        }
    }

    /**
     * Paints a part whose {@code printAll} failed (with {@code failure}) child by child, last child first as Swing
     * does; a part is a placeholder when it has no visible child, or when it is a Swing component (whose own printing
     * reaches no heavyweight) none of whose children failed alone.
     */
    private static void guarded(Component c, Graphics2D g, List<Skipped> found, Throwable failure) {
        List<Component> kids = visibleChildren(c);
        int before = found.size();
        for (int i = kids.size() - 1; i >= 0; i--) {
            Component k = kids.get(i);
            Graphics2D kg = (Graphics2D) g.create(k.getX(), k.getY(), k.getWidth(), k.getHeight());
            try {
                Throwable kidFailure = print(k, kg);
                if (kidFailure != null) {
                    guarded(k, kg, found, kidFailure);
                }
            } finally {
                kg.dispose();
            }
        }
        if (found.size() == before && (kids.isEmpty() || c instanceof JComponent)) {
            placeholder(g, c.getWidth(), c.getHeight(), kids.isEmpty());
            found.add(new Skipped(c, failure));
        }
    }

    /**
     * Paints the heavyweight components below the root one by one at their place, on top (a window's own printing
     * reaches them, a Swing container's does not); one that throws is handled as {@link #guarded} does.
     */
    private static void heavyweights(Component root, Graphics2D g, List<Skipped> found) {
        Map<Component, Boolean> skipped = new IdentityHashMap<Component, Boolean>();
        for (Skipped s : found) {
            skipped.put(s.component, Boolean.TRUE);
        }
        List<Component> heavy = new ArrayList<Component>();
        collectHeavyweights(root, skipped, heavy);
        for (Component k : heavy) {
            int x = 0;
            int y = 0;
            for (Component p = k; p != null && p != root; p = p.getParent()) {
                x += p.getX();
                y += p.getY();
            }
            Graphics2D kg = (Graphics2D) g.create(x, y, k.getWidth(), k.getHeight());
            try {
                Throwable failure = print(k, kg);
                if (failure != null) {
                    guarded(k, kg, found, failure);
                }
            } finally {
                kg.dispose();
            }
        }
    }

    /** The outermost heavyweight components below {@code c} (a window's own heavyweight is not one of them). */
    private static void collectHeavyweights(Component c, Map<Component, Boolean> skipped, List<Component> out) {
        for (Component k : visibleChildren(c)) {
            if (k.isDisplayable() && !k.isLightweight()) {
                if (!skipped.containsKey(k)) {
                    out.add(k);
                }
            } else {
                collectHeavyweights(k, skipped, out);
            }
        }
    }

    /** The visible children of a container with a size, in index order (index 0 is on top). */
    private static List<Component> visibleChildren(Component c) {
        List<Component> out = new ArrayList<Component>();
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                if (k != null && k.isVisible() && k.getWidth() > 0 && k.getHeight() > 0 && !(k instanceof Window)) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    /** A placeholder rectangle over {@code w x h}: filled (a leaf) or not (a container), outlined and crossed. */
    private static void placeholder(Graphics2D g, int w, int h, boolean fill) {
        Graphics2D pg = (Graphics2D) g.create();
        try {
            if (fill) {
                pg.setColor(PLACEHOLDER_FILL);
                pg.fillRect(0, 0, w, h);
            }
            pg.setColor(PLACEHOLDER_LINE);
            pg.drawRect(0, 0, w - 1, h - 1);
            pg.drawLine(0, 0, w - 1, h - 1);
            pg.drawLine(0, h - 1, w - 1, 0);
        } finally {
            pg.dispose();
        }
    }

    // ------------------------------------------------------------------------------------------ hash and PNG

    /**
     * {@code sha256:<hex>} over the decoded raster (phase7-contracts.md section 3.1): the width and the height as
     * 4-byte big-endian integers, then every pixel's {@code getRGB} value (alpha included, {@code 0xFF} for an opaque
     * image) as a 4-byte big-endian integer, rows top to bottom; never the PNG bytes. The controller's
     * {@code ImageCompare.rasterHash} computes the same bytes. Any thread.
     */
    static String hash(BufferedImage image) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        int w = image.getWidth();
        int h = image.getHeight();
        ByteBuffer head = ByteBuffer.allocate(8);
        head.putInt(w).putInt(h);
        digest.update(head.array());
        int[] row = new int[w];
        ByteBuffer bytes = ByteBuffer.allocate(4 * w);
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            bytes.clear();
            for (int x = 0; x < w; x++) {
                bytes.putInt(row[x]);
            }
            digest.update(bytes.array(), 0, 4 * w);
        }
        byte[] d = digest.digest();
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * PNG bytes of the image. Encodes in memory with an explicit writer, so the JVM-wide ImageIO cache setting is
     * neither used (no temporary files) nor changed.
     */
    static byte[] png(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IOException("no PNG ImageWriter available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageOutputStream out = new MemoryCacheImageOutputStream(bytes);
        try {
            writer.setOutput(out);
            writer.write(image);
        } finally {
            writer.dispose();
            out.close();
        }
        return bytes.toByteArray();
    }
}
