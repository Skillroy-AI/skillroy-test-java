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
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Capture, hit-test and the stale-capture rule (phase7-contracts.md 6.2 step 1, 6.4, D305), through the session's
 * facade only: every capture is {@code swing.capture {scope: SCREEN, mode: PAINT, redact: true, annotateRefs}} at the
 * window's own size (scaled down only when the long edge exceeds 1920 pixels, never below 0.75), never
 * {@code mode: ROBOT} (D341); a pixel of a capture is grounded by {@code swing.hitTest {point, space: {captureId}}}
 * before anything acts; when the window set changed since the capture's {@code seq} a fresh capture is taken and the
 * same point hit-tested on it, and the action goes on only when the target (and a table's row) is the same.
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
                    g.drawImage(page, s.rect().x(), s.rect().y(), s.rect().w(), s.rect().h(), null);
                }
            }
        } finally {
            g.dispose();
        }
        return copy;
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

    /**
     * Grounds a pixel of a capture with the stale-capture rule (6.4, D305): the hit-test; when a window opened or
     * closed since the capture, a fresh capture with the same annotation and the same point hit-tested on it, the
     * action allowed only on the same target (and, for a table or a list, the same row).
     *
     * @throws AgentRpcException with the agent's error
     * @throws AgentLostException if the agent is gone
     * @throws InterruptedException if the thread is interrupted
     */
    public Grounded ground(Capture capture, int x, int y, boolean annotate)
            throws AgentRpcException, AgentLostException, InterruptedException {
        Map<String, Object> hit = hitTest(capture, x, y);
        if (!windowsChangedSince(capture)) {
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** A PNG's raster, or null when it cannot be decoded. */
    public static BufferedImage decode(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A raster as PNG. */
    public static byte[] encode(BufferedImage raster) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(raster, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("a PNG could not be encoded", e);
        }
    }
}
