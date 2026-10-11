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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.inference.session.SessionActions;
import ai.skillroy.testjava.protocol.Event;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Capture, hit-test and the stale-capture rule (phase7-contracts.md 6.2 step 1, 6.4, D305), through the session's
 * facade only: every capture is {@code swing.capture {scope: SCREEN, mode: PAINT, redact: true, annotateRefs}} at the
 * window's own size (scaled down only when the long edge exceeds 1920 pixels, never below 0.75), never
 * {@code mode: ROBOT} (D341); a pixel of a capture is grounded by {@code swing.hitTest {point, space: {captureId}}}
 * before anything acts; when the window set changed since the capture's {@code seq} a fresh capture is taken and the
 * same point hit-tested on it, and the action goes on only when the target (and a table's row) is the same.
 * Phase 7 security fix S114 (SI): the rule also compares the hit with what the capture itself showed: a V1 mark's own
 * ref, always, and after a window event the window the capture shows at the pixel.
 *
 * <p>Owned by one session's thread.
 */
public final class Grounding {

    /** The long edge above which a capture is scaled down (BS12: 1920 keeps it under the high-resolution tier). */
    public static final int MAX_EDGE = 1920;
    /** The smallest scale a capture for a model takes (BS12 RESULT "Failures": icons failed first at 0.5). */
    public static final double MIN_SCALE = 0.75;
    /** The topics that say the set of windows changed. */
    public static final String WINDOW_TOPICS = "window.opened,window.closed";

    private final SessionActions actions;

    /** @param actions the session's facade */
    public Grounding(SessionActions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /**
     * One capture of the target's windows for the model (3.1's "pixels a model sees").
     *
     * @param annotate true for V1's marks geometry
     * @throws AgentRpcException with the agent's error
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Capture capture(boolean annotate) throws AgentRpcException, AgentLostException, InterruptedException {
        Capture first = Capture.of(actions.read("swing.capture", params(annotate, 1.0)));
        int edge = Math.max(first.width(), first.height());
        if (edge <= MAX_EDGE) {
            return first;
        }
        double scale = Math.max(MIN_SCALE, (double) MAX_EDGE / edge);
        return Capture.of(actions.read("swing.capture", params(annotate, scale)));
    }

    private static Map<String, Object> params(boolean annotate, double scale) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("scope", "SCREEN");
        p.put("mode", "PAINT");
        p.put("redact", true);
        p.put("annotateRefs", annotate);
        if (scale != 1.0) {
            p.put("scale", scale);
        }
        return p;
    }

    /**
     * The image the model sees of a capture: each {@code WEB} surface whose {@code redacted} is false composited with
     * its browser's {@code web.bitmap} (4.8; a redacted surface stays grey), and V1's marks drawn on this copy (D346).
     * The capture's own PNG unchanged when there is nothing to add.
     *
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public byte[] modelImage(Capture capture, boolean marks) throws AgentLostException, InterruptedException {
        boolean web = false;
        for (Capture.Surface s : capture.surfaces()) {
            web |= "WEB".equals(s.kind()) && !s.redacted() && s.browser() != null;
        }
        if (!web && (!marks || capture.marks().isEmpty())) {
            return capture.png();
        }
        BufferedImage raster = decode(capture.png());
        if (raster == null) {
            return capture.png();
        }
        if (web) {
            raster = composite(raster, capture);
        }
        if (marks && !capture.marks().isEmpty()) {
            raster = Marks.draw(raster, capture.marks());
        }
        return encode(raster);
    }

    /**
     * The image of a capture with V1's marks drawn and nothing composited: what the scenario player's visual question
     * sends (it holds no facade to read a page's bitmap through).
     */
    public static byte[] encodeMarked(Capture capture, boolean marks) {
        if (!marks || capture.marks().isEmpty()) {
            return capture.png();
        }
        BufferedImage raster = decode(capture.png());
        return raster == null ? capture.png() : encode(Marks.draw(raster, capture.marks()));
    }

    private BufferedImage composite(BufferedImage raster, Capture capture)
            throws AgentLostException, InterruptedException {
        BufferedImage copy = new BufferedImage(raster.getWidth(), raster.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(raster, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (Capture.Surface s : capture.surfaces()) {
                if (!"WEB".equals(s.kind()) || s.redacted() || s.browser() == null) {
                    continue;
                }
                BufferedImage page;
                try {
                    Object bitmap = actions.read("web.bitmap", Map.of("browser", s.browser()));
                    page = bitmap instanceof Map<?, ?> m && m.get("base64") instanceof String b64
                            ? decode(Base64.getDecoder().decode(b64)) : null;
                } catch (AgentRpcException | IllegalArgumentException e) {
                    page = null;
                }
                if (page != null) {
                    draw(g, page, s);
                }
            }
        } finally {
            g.dispose();
        }
        return copy;
    }

    /**
     * Draws a page's bitmap into its surface (4.8; Phase 7 security fix S108): at the component's whole rectangle (the
     * surface's {@code bounds}, else its {@code rect}), clipped to the visible part ({@code rect}) minus every
     * rectangle the capture reports above it ({@code covered}), so that a dialog, a popup or a window above the browser
     * keeps its own pixels in the model's image. Without {@code bounds}, a {@code rect} whose shape differs from the
     * bitmap's (the agent clipped it to the visible part of a component a viewport cuts) is not drawn at all: where the
     * visible part lies in the page is not known, and the area keeps what the capture painted.
     */
    static void draw(Graphics2D g, BufferedImage page, Capture.Surface s) {
        Capture.Rect target = s.bounds() != null ? s.bounds() : s.rect();
        if (target.w() <= 0 || target.h() <= 0 || page.getWidth() <= 0 || page.getHeight() <= 0) {
            return;
        }
        if (s.bounds() == null && !sameShape(page.getWidth(), page.getHeight(), target.w(), target.h())) {
            return;
        }
        Area clip = new Area(new Rectangle(s.rect().x(), s.rect().y(), s.rect().w(), s.rect().h()));
        for (Capture.Rect c : s.covered()) {
            clip.subtract(new Area(new Rectangle(c.x(), c.y(), c.w(), c.h())));
        }
        if (clip.isEmpty()) {
            return;
        }
        java.awt.Shape before = g.getClip();
        g.setClip(clip);
        try {
            g.drawImage(page, target.x(), target.y(), target.w(), target.h(), null);
        } finally {
            g.setClip(before);
        }
    }

    /** True when two sizes have the same aspect ratio within two pixels of the smaller one (a scaled copy). */
    static boolean sameShape(int w1, int h1, int w2, int h2) {
        double scale = Math.min((double) w2 / w1, (double) h2 / h1);
        return Math.abs(w1 * scale - w2) <= 2.0 && Math.abs(h1 * scale - h2) <= 2.0;
    }

    /**
     * The hit-test of a pixel of a capture (3.2), before anything acts.
     *
     * @throws AgentRpcException with the agent's error ({@code NOT_FOUND} with {@code reason: outside}, an expired
     *     capture's {@code INVALID_PARAMS})
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Map<String, Object> hitTest(Capture capture, int x, int y)
            throws AgentRpcException, AgentLostException, InterruptedException {
        return map(actions.read("swing.hitTest", Map.of("point", Map.of("x", (long) x, "y", (long) y), "space",
                Map.of("captureId", capture.captureId()))));
    }

    /**
     * The hit-test of a component by ref (its centre; the focus owner, {@code click_ref}).
     *
     * @throws AgentRpcException with the agent's error
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Map<String, Object> describe(String ref) throws AgentRpcException, AgentLostException,
            InterruptedException {
        return map(actions.read("swing.hitTest", Map.of("target", ref)));
    }

    /** True when a window opened or closed since the capture (the stale-capture rule, D305). */
    public boolean windowsChangedSince(Capture capture) {
        List<Event> events = actions.eventsSince(capture.seq(), WINDOW_TOPICS, 1);
        return !events.isEmpty();
    }

    /**
     * A grounded pixel: the capture it was grounded on and the hit-test; {@code changed} when the stale-capture rule
     * found another target on the fresh capture (nothing may act then; {@code capture} is the fresh one).
     */
    public record Grounded(Capture capture, Map<String, Object> hit, boolean changed) {
    }

    /** {@link #ground(Capture, int, int, boolean, String)} without a mark (V2, V3). */
    public Grounded ground(Capture capture, int x, int y, boolean annotate)
            throws AgentRpcException, AgentLostException, InterruptedException {
        return ground(capture, x, y, annotate, null);
    }

    /**
     * Grounds a pixel of a capture with the stale-capture rule (6.4, D305): the hit-test; when a window opened or
     * closed since the capture, a fresh capture with the same annotation and the same point hit-tested on it, the
     * action allowed only on the same target (and, for a table or a list, the same row).
     *
     * <p>Phase 7 security fix S114 (SI): the hit is also compared with what the capture itself showed, since both
     * hit-tests are live (the agent resolves a capture's pixel to the topmost window now): for a V1 mark,
     * {@code markRef}, the hit's target must be the mark's own component, always (or the component that component
     * draws at its centre, an editable combo box's editor, in the window the capture shows at the pixel); and when
     * the window set changed since the capture, the hit's window must be the window the capture shows at the pixel
     * ({@link Capture#windowAt}, topmost first). Otherwise the screen changed since that capture, with the fresh one.
     *
     * @param markRef the ref of the V1 mark the model named, or null
     * @throws AgentRpcException with the agent's error
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Grounded ground(Capture capture, int x, int y, boolean annotate, String markRef)
            throws AgentRpcException, AgentLostException, InterruptedException {
        Map<String, Object> hit = hitTest(capture, x, y);
        boolean changed = windowsChangedSince(capture);
        boolean shown = markRef == null || markShown(capture, x, y, hit, markRef);
        if (shown && changed) {
            Capture.Region was = capture.windowAt(x, y);
            shown = was != null && Objects.equals(was.ref(), hit.get("window"));
        }
        if (!shown) {
            Capture fresh;
            try {
                fresh = capture(annotate);
            } catch (AgentRpcException | IllegalArgumentException e) {
                fresh = capture;
            }
            return new Grounded(fresh, hit, true);
        }
        if (!changed) {
            return new Grounded(capture, hit, false);
        }
        Capture fresh = capture(annotate);
        Map<String, Object> again;
        try {
            again = hitTest(fresh, x, y);
        } catch (AgentRpcException e) {
            return new Grounded(fresh, hit, true);
        }
        boolean same = Objects.equals(hit.get("target"), again.get("target"))
                && Objects.equals(hit.get("row"), again.get("row"));
        return new Grounded(fresh, again, !same);
    }

    /**
     * True when the hit is about the mark's own component (S114): its target is the mark's ref, or, in the window the
     * capture shows at the pixel, the component the mark's component draws at its centre now ({@code swing.hitTest
     * {target: markRef}}'s {@code mouseTarget} or {@code deepest}: an editable combo box's editor, a spinner's field).
     */
    private boolean markShown(Capture capture, int x, int y, Map<String, Object> hit, String markRef)
            throws AgentLostException, InterruptedException {
        Object target = hit.get("target");
        if (markRef.equals(target)) {
            return true;
        }
        Capture.Region was = capture.windowAt(x, y);
        if (target == null || was == null || !Objects.equals(was.ref(), hit.get("window"))) {
            return false;
        }
        Map<String, Object> described;
        try {
            described = describe(markRef);
        } catch (AgentRpcException e) {
            return false;
        }
        return Objects.equals(described.get("window"), hit.get("window"))
                && (target.equals(map(described.get("mouseTarget")).get("ref"))
                || target.equals(map(described.get("deepest")).get("ref")));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /**
     * A PNG's raster, or null when it cannot be decoded. Through a memory-cached stream (Phase 7 security fix S143, P2
     * 11.3): no pixel data passes through {@code java.io.tmpdir}, as {@code ImageIO.read} of a stream would write it
     * there with its default disk cache.
     */
    public static BufferedImage decode(byte[] png) {
        if (png == null) {
            return null;
        }
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A raster as PNG, through a memory-cached stream (S143: nothing through {@code java.io.tmpdir}). */
    public static byte[] encode(BufferedImage raster) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IllegalStateException("no PNG writer");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(raster);
        } catch (IOException e) {
            throw new IllegalStateException("a PNG could not be encoded", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
