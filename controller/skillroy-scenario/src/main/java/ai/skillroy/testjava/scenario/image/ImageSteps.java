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

package ai.skillroy.testjava.scenario.image;

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.OutputFiles;
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.ImageCheck;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.resolve.BindMode;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The one code path of image steps for both hosts (phase7-contracts.md 6.7, decisions D313-D315, D357): the capture,
 * the lookup of a label's entry by the current render key ({@code swing.rendering} read once, cached), the exact
 * comparison ({@link ImageCompare}), the files ({@code failure/image-<label>-expected.png}, {@code -actual.png},
 * {@code -diff.png}; {@code candidates/image-<label>.png} in {@code approve}) and the {@code image} record. Used by
 * {@code PlanExecutor} for an {@code ImageCheck} (from the step's own capture call result) and for an anchored
 * {@code pointer} call's guard (before the pointer call), and by {@code ExecutorSession.expectImage} for the scenario
 * player.
 *
 * <p>Outcomes: an {@code expectImage} mismatch is FAIL / ASSERTION; no baseline for the current render key is
 * REBIND_NEEDED (reason {@code IMAGE}) in {@code replay}, the capture approved at the step in {@code approve}
 * ({@link Approver#reviewImage}, a rejection REBIND_NEEDED {@code "candidate not approved"}) and recorded and skipped
 * in {@code live}; an anchor mismatch, or no anchor entry for the key, is REBIND_NEEDED (reason {@code IMAGE}) in every
 * mode, {@code approve} included. One instance per run or session; not thread-safe.
 */
public final class ImageSteps {

    /** What the host gives the image steps. */
    public interface Host {

        /**
         * One bounded read of the target ({@code swing.capture}, {@code swing.rendering}, {@code swing.find}), sent
         * after the start mark; the agent's error is thrown.
         */
        Object read(String primitive, Map<String, Object> args) throws AgentRpcException, AgentLostException,
                InterruptedException;

        /** Writes the {@code image} record ({@code run.jsonl}, or the session's log); the host masks it. */
        void record(Map<String, Object> fields);

        /** The run's or the session's output directory ({@code failure/}, {@code candidates/} below it), or null. */
        Path outputDir();

        /** The mode the step runs in ({@code live} for a session). */
        BindMode mode();

        /** The scenario id (the approval names it). */
        String scenarioId();

        /** The environment id. */
        String envId();

        /** Who approves a baseline candidate in {@code approve}. */
        Approver approver();
    }

    /** The baselines and anchors of the scenario's bindings file, and the approvals of this run. */
    public interface Baselines {

        /** The entry of {@code label} for {@code renderKey}, or null. */
        BindingsFile.ImageEntry entry(String label, String renderKey);

        /** The render keys {@code label} has entries for (any order), empty when none. */
        Set<String> keys(String label);

        /**
         * The entry's PNG, decoded; the path is derived from the label and the key below the bindings directory.
         *
         * @throws IOException if the file cannot be read or is not the entry's raster
         */
        BufferedImage image(String label, String renderKey) throws IOException;

        /**
         * A baseline approved at its step in {@code approve}: kept for the rewrite of the bindings file after the
         * steps, with its PNG (masked) bytes.
         */
        void approved(String label, String renderKey, BindingsFile.ImageEntry entry, byte[] png);

        /** No baselines at all (a session without a scenario's bindings file). */
        static Baselines none() {
            return NoBaselines.INSTANCE;
        }
    }

    /**
     * What one image check or guard came to.
     *
     * @param kind held, failed, rebind needed, skipped, or an environment error
     * @param message one line for the step's message, or null when it held
     * @param detail the fields of the {@code image} record and the step's detail ({@code label, renderKey, result,
     *     differing?, maxChannelDelta?, expected?, actual?, files?, reason?}); never bytes
     */
    public record Outcome(Kind kind, String message, Map<String, Object> detail) {

        /** The four outcomes. */
        public enum Kind {
            /** The capture matches the entry (a guard lets its pointer call run). */
            HELD,
            /** An {@code expectImage} capture differs from its baseline: FAIL / ASSERTION. */
            FAILED,
            /** No entry for the render key, an anchor mismatch, or a rejected candidate: REBIND_NEEDED (IMAGE). */
            REBIND,
            /** {@code live} without a baseline: recorded, the check skipped (DEGRADED at best). */
            SKIPPED,
            /** The capture or the baseline file cannot be used: ERROR / ENVIRONMENT. */
            ERROR
        }

        /** Copies the detail. */
        public Outcome {
            Objects.requireNonNull(kind, "kind");
            detail = detail == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        }
    }

    /** The {@code reason} of every REBIND_NEEDED an image step gives (phase7-contracts.md 8.7, P2 10). */
    public static final String REASON = "IMAGE";

    /** The prefix of the image evidence files under {@code failure/} and {@code candidates/}. */
    public static final String FILE_PREFIX = "image-";

    private final Host host;
    private final Baselines baselines;
    /** {@code swing.rendering}'s answer, read once; null until then. */
    private Map<String, Object> rendering;
    private String renderKey;

    /**
     * @param host the run's or the session's side
     * @param baselines the scenario's entries ({@link Baselines#none()} without a bindings file)
     */
    public ImageSteps(Host host, Baselines baselines) {
        this.host = Objects.requireNonNull(host, "host");
        this.baselines = baselines == null ? Baselines.none() : baselines;
    }

    /**
     * The target's render key ({@code swing.rendering}'s {@code renderKey}), read once and cached.
     *
     * @throws AgentRpcException if the target cannot answer {@code swing.rendering}
     * @throws IllegalStateException if the answer has no render key of the expected shape
     */
    public String renderKey() throws AgentRpcException, AgentLostException, InterruptedException {
        if (renderKey == null) {
            Object answer = host.read("swing.rendering", Map.of());
            if (!(answer instanceof Map<?, ?> map) || !(map.get("renderKey") instanceof String key)
                    || !RENDER_KEY.matcher(key).matches()) {
                throw new IllegalStateException("swing.rendering answered no render key ([A-Za-z0-9]{8,64})");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (!"renderKey".equals(k) && !"keyed".equals(k)) {
                    copy.put(String.valueOf(k), v);
                }
            });
            rendering = copy;
            renderKey = key;
        }
        return renderKey;
    }

    /** {@code swing.rendering}'s answer without {@code renderKey} and {@code keyed}, as an entry records it. */
    public Map<String, Object> rendering() throws AgentRpcException, AgentLostException, InterruptedException {
        renderKey();
        return rendering;
    }

    /** What a render key looks like: it names a file, so nothing else is accepted. */
    public static final java.util.regex.Pattern RENDER_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9]{8,64}");

    /**
     * Evaluates an {@code expectImage} step's check from its own capture call's result.
     *
     * @param stepId the step
     * @param check the step's check
     * @param capture the {@code swing.capture} result the step's call returned
     */
    public Outcome check(String stepId, ImageCheck check, Object capture) throws AgentRpcException,
            AgentLostException, InterruptedException {
        String label = check.label();
        Capture current;
        try {
            current = Capture.of(capture);
        } catch (IOException e) {
            return environment(stepId, label, null, "the capture of step " + stepId + " cannot be read: "
                    + e.getMessage());
        }
        String key = renderKey();
        List<ImageCompare.Rect> stepMasks = new ArrayList<>();
        Outcome unresolved = stepMasks(stepId, label, key, check.masks(), current, stepMasks);
        if (unresolved != null) {
            return unresolved;
        }
        BindingsFile.ImageEntry entry = baselines.entry(label, key);
        if (entry == null) {
            return missingBaseline(stepId, label, key, current, stepMasks);
        }
        return compare(stepId, label, key, entry, current, stepMasks, check.maxDiffPixels(), false);
    }

    /**
     * The guard of an anchored {@code pointer} call: the entry of the current render key for the guard's label, the
     * capture of that rectangle of the call's target ({@code swing.capture {target|by, scope: REGION, region, redact:
     * true}}), the comparison. {@link Outcome.Kind#HELD} lets the pointer call run; anything else is
     * {@link Outcome.Kind#REBIND} (an anchor is recorded by a session, never approved at a step).
     *
     * @param stepId the step
     * @param guard the call's guard
     * @param pointerArgs the pointer call's arguments, references substituted (its {@code target} or {@code by})
     */
    public Outcome guard(String stepId, Plan.Guard guard, Map<String, Object> pointerArgs) throws AgentRpcException,
            AgentLostException, InterruptedException {
        String label = guard.label();
        String key = renderKey();
        BindingsFile.ImageEntry entry = baselines.entry(label, key);
        if (entry == null || entry.anchor() == null) {
            Map<String, Object> detail = record(stepId, label, key, "missing");
            detail.put("guard", true);
            detail.put("keys", new ArrayList<>(new java.util.TreeSet<>(baselines.keys(label))));
            host.record(detail);
            return rebind(detail, "no anchor " + label + " for rendering " + key + ": re-record the step through"
                    + " skillroy session --play");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (pointerArgs.containsKey("target")) {
            args.put("target", pointerArgs.get("target"));
        }
        if (pointerArgs.containsKey("by")) {
            args.put("by", pointerArgs.get("by"));
        }
        args.put("scope", "REGION");
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("x", (long) entry.anchor().x());
        region.put("y", (long) entry.anchor().y());
        region.put("w", (long) entry.width());
        region.put("h", (long) entry.height());
        args.put("region", region);
        args.put("redact", true);
        Capture current;
        try {
            current = Capture.of(host.read("swing.capture", args));
        } catch (AgentRpcException e) {
            Map<String, Object> detail = record(stepId, label, key, "failed");
            detail.put("guard", true);
            detail.put("error", e.error() == null ? e.getMessage() : e.error().message());
            host.record(detail);
            return rebind(detail, "the anchor " + label + " cannot be captured: " + e.getMessage());
        } catch (IOException e) {
            Map<String, Object> detail = record(stepId, label, key, "failed");
            detail.put("guard", true);
            host.record(detail);
            return rebind(detail, "the anchor " + label + "'s capture cannot be read: " + e.getMessage());
        }
        return compare(stepId, label, key, entry, current, List.of(), 0, true);
    }

    // ------------------------------------------------------------------------------------------------ internals

    /** Resolves the step's masks to image rectangles; an outcome when one cannot be resolved. */
    private Outcome stepMasks(String stepId, String label, String key, List<Object> masks, Capture current,
                              List<ImageCompare.Rect> out) throws AgentLostException, InterruptedException {
        for (Object mask : masks) {
            ImageCompare.Rect rect = ImageCompare.Rect.of(mask);
            if (rect != null) {
                out.add(rect);
                continue;
            }
            if (!(mask instanceof Map<?, ?> map) || !(map.get("by") instanceof Map<?, ?> by)) {
                continue;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("by", by);
            Object found;
            try {
                found = host.read("swing.find", params);
            } catch (AgentRpcException e) {
                Map<String, Object> detail = record(stepId, label, key, "failed");
                detail.put("mask", by);
                detail.put("error", e.getMessage());
                host.record(detail);
                return new Outcome(Outcome.Kind.FAILED, "image " + label + ": the mask " + by
                        + " matches no single component: " + e.getMessage(), detail);
            }
            if (!(found instanceof Map<?, ?> f) || !(f.get("matches") instanceof List<?> matches)
                    || matches.size() != 1 || !(matches.get(0) instanceof Map<?, ?> node)
                    || ImageCompare.Rect.of(node.get("bounds")) == null) {
                Map<String, Object> detail = record(stepId, label, key, "failed");
                detail.put("mask", by);
                host.record(detail);
                return new Outcome(Outcome.Kind.FAILED, "image " + label + ": the mask " + by
                        + " matches no single component", detail);
            }
            out.add(current.toImage(ImageCompare.Rect.of(node.get("bounds"))));
        }
        return null;
    }

    /** No entry for the key: REBIND in replay, the candidate approved at the step in approve, skipped in live. */
    private Outcome missingBaseline(String stepId, String label, String key, Capture current,
                                    List<ImageCompare.Rect> stepMasks) throws AgentRpcException,
            AgentLostException, InterruptedException {
        String reason = "no baseline " + label + " for rendering " + key;
        BindMode mode = host.mode();
        if (mode == BindMode.APPROVE) {
            return approve(stepId, label, key, current, stepMasks);
        }
        if (mode == BindMode.LIVE) {
            Map<String, Object> detail = record(stepId, label, key, "skipped");
            detail.put("reason", reason);
            host.record(detail);
            return new Outcome(Outcome.Kind.SKIPPED, "image " + label + " skipped: no baseline for rendering " + key,
                    detail);
        }
        Map<String, Object> detail = record(stepId, label, key, "missing");
        detail.put("keys", new ArrayList<>(new java.util.TreeSet<>(baselines.keys(label))));
        host.record(detail);
        return rebind(detail, reason);
    }

    /** The capture as the candidate baseline (approve): written, shown, approved or rejected. */
    private Outcome approve(String stepId, String label, String key, Capture current,
                            List<ImageCompare.Rect> stepMasks) throws AgentRpcException, AgentLostException,
            InterruptedException {
        List<ImageCompare.Rect> blank = new ArrayList<>(stepMasks);
        blank.addAll(current.carets);
        BufferedImage candidate = ImageCompare.masked(current.image, blank);
        byte[] png;
        try {
            png = ImageCompare.encodePng(candidate);
        } catch (IOException e) {
            return environment(stepId, label, key, "the candidate baseline cannot be encoded: " + e.getMessage());
        }
        Path file = null;
        Path dir = host.outputDir();
        if (dir != null) {
            file = dir.resolve("candidates").resolve(FILE_PREFIX + label + ".png");
            try {
                OutputFiles.write(file, png);
            } catch (IOException e) {
                file = null;
            }
        }
        List<ImageCompare.Rect> masks = new ArrayList<>(current.redacted);
        masks.addAll(stepMasks);
        BindingsFile.ImageEntry entry = new BindingsFile.ImageEntry(ImageCompare.rasterHash(candidate),
                candidate.getWidth(), candidate.getHeight(), masks, current.carets, current.focus,
                BindingsFile.ImageEntry.fileName(host.scenarioId(), label, key), rendering(), current.window, null);
        BindingsFile.ImageEntry existing = null;
        for (String other : baselines.keys(label)) {
            existing = baselines.entry(label, other);
            break;
        }
        Approver.Decision decision;
        try {
            Approver approver = host.approver();
            decision = approver == null ? null : approver.reviewImage(new Approver.ImageApproval(host.scenarioId(),
                    host.envId(), label, key, file, candidate.getWidth(), candidate.getHeight(), existing));
        } catch (RuntimeException e) {
            decision = new Approver.Decision(false, null, "the approver failed: " + e.getClass().getName());
        }
        Map<String, Object> detail = record(stepId, label, key, decision != null && decision.approved()
                ? "approved" : "rejected");
        detail.put("actual", entry.sha256());
        if (file != null) {
            detail.put("files", List.of("candidates/" + file.getFileName()));
        }
        if (decision == null || !decision.approved()) {
            detail.put("reason", "candidate not approved");
            host.record(detail);
            return rebind(detail, "image " + label + ": candidate not approved"
                    + (decision == null || decision.reason() == null ? "" : " (" + decision.reason() + ")"));
        }
        baselines.approved(label, key, entry, png);
        host.record(detail);
        return new Outcome(Outcome.Kind.HELD, null, detail);
    }

    /** The comparison of a capture with an entry (a baseline, or a guard's anchor). */
    private Outcome compare(String stepId, String label, String key, BindingsFile.ImageEntry entry, Capture current,
                            List<ImageCompare.Rect> stepMasks, int maxDiffPixels, boolean guard)
            throws AgentLostException, InterruptedException {
        BufferedImage expected;
        try {
            expected = baselines.image(label, key);
        } catch (IOException e) {
            Map<String, Object> detail = record(stepId, label, key, "missing");
            detail.put("reason", e.getMessage());
            if (guard) {
                detail.put("guard", true);
            }
            host.record(detail);
            return environment(stepId, label, key, "the baseline " + entry.file() + " cannot be used: "
                    + e.getMessage(), detail);
        }
        List<ImageCompare.Rect> masks = new ArrayList<>(entry.masks());
        masks.addAll(stepMasks);
        masks.addAll(entry.carets());
        masks.addAll(current.carets);
        boolean focusMasked = !java.util.Objects.equals(entry.focus().owner(), current.focus.owner());
        if (focusMasked) {
            if (entry.focus().ownerRect() != null) {
                masks.add(entry.focus().ownerRect());
            }
            if (current.focus.ownerRect() != null) {
                masks.add(current.focus.ownerRect());
            }
        }
        ImageCompare.Result result = ImageCompare.compare(expected, current.image, masks, maxDiffPixels, focusMasked);
        Map<String, Object> detail = record(stepId, label, key, result.holds() ? "held" : "failed");
        detail.putAll(result.toJson());
        if (guard) {
            detail.put("guard", true);
        }
        if (result.holds()) {
            host.record(detail);
            return new Outcome(Outcome.Kind.HELD, null, detail);
        }
        List<String> files = evidence(label, expected, current.image, masks, result.sameSize());
        if (!files.isEmpty()) {
            detail.put("files", files);
        }
        host.record(detail);
        String what = result.sameSize() ? result.differing() + " pixel" + (result.differing() == 1 ? "" : "s")
                + " differ (at most " + maxDiffPixels + ")" : "the size differs (" + expected.getWidth() + " x "
                + expected.getHeight() + " expected, " + current.image.getWidth() + " x " + current.image.getHeight()
                + " captured)";
        if (guard) {
            return rebind(detail, "the anchor " + label + " no longer matches for rendering " + key + ": " + what);
        }
        return new Outcome(Outcome.Kind.FAILED, "image " + label + " differs from its baseline for rendering " + key
                + ": " + what, detail);
    }

    /** The three files of a mismatch under {@code failure/}; the names written, relative to the output directory. */
    private List<String> evidence(String label, BufferedImage expected, BufferedImage actual,
                                  List<ImageCompare.Rect> masks, boolean sameSize) {
        Path dir = host.outputDir();
        List<String> names = new ArrayList<>();
        if (dir == null) {
            return names;
        }
        Path failure = dir.resolve("failure");
        try {
            write(failure, FILE_PREFIX + label + "-expected.png", ImageCompare.encodePng(expected), names);
            write(failure, FILE_PREFIX + label + "-actual.png", ImageCompare.encodePng(actual), names);
            if (sameSize) {
                write(failure, FILE_PREFIX + label + "-diff.png", ImageCompare.encodePng(ImageCompare.diff(expected,
                        actual, masks)), names);
            }
        } catch (IOException | RuntimeException e) {
            // Best effort: the record names the files that were written.
        }
        return names;
    }

    private static void write(Path dir, String name, byte[] png, List<String> names) throws IOException {
        OutputFiles.write(dir.resolve(name), png);
        names.add("failure/" + name);
    }

    private static Map<String, Object> record(String stepId, String label, String key, String result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("step", stepId);
        detail.put("label", label);
        detail.put("renderKey", key);
        detail.put("result", result);
        return detail;
    }

    private static Outcome rebind(Map<String, Object> detail, String message) {
        Map<String, Object> d = new LinkedHashMap<>(detail);
        d.put("reason", REASON);
        if (detail.get("reason") instanceof String why && !REASON.equals(why)) {
            d.put("why", why);
        }
        return new Outcome(Outcome.Kind.REBIND, message, d);
    }

    private Outcome environment(String stepId, String label, String key, String message) {
        Map<String, Object> detail = record(stepId, label, key, "failed");
        detail.put("error", message);
        host.record(detail);
        return environment(stepId, label, key, message, detail);
    }

    private static Outcome environment(String stepId, String label, String key, String message,
                                       Map<String, Object> detail) {
        return new Outcome(Outcome.Kind.ERROR, message, detail);
    }

    /** A {@code swing.capture} result, decoded: the raster and the fields an entry and a comparison need. */
    static final class Capture {

        final BufferedImage image;
        final List<ImageCompare.Rect> carets;
        final List<ImageCompare.Rect> redacted;
        final BindingsFile.Focus focus;
        final Map<String, Object> window;
        final double originX;
        final double originY;
        final double scale;

        private Capture(BufferedImage image, List<ImageCompare.Rect> carets, List<ImageCompare.Rect> redacted,
                        BindingsFile.Focus focus, Map<String, Object> window, double originX, double originY,
                        double scale) {
            this.image = image;
            this.carets = carets;
            this.redacted = redacted;
            this.focus = focus;
            this.window = window;
            this.originX = originX;
            this.originY = originY;
            this.scale = scale;
        }

        /** @throws IOException if the value is not a capture with a PNG */
        @SuppressWarnings("unchecked")
        static Capture of(Object value) throws IOException {
            if (!(value instanceof Map<?, ?> map) || !(map.get("base64") instanceof String base64)) {
                throw new IOException("the result holds no image");
            }
            byte[] bytes;
            try {
                bytes = java.util.Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                throw new IOException("the image is not base64");
            }
            BufferedImage image = ImageCompare.decodePng(bytes);
            List<ImageCompare.Rect> carets = rects(map.get("carets"), "rect");
            List<ImageCompare.Rect> redacted = rects(map.get("redacted"), "rect");
            Map<String, Object> owner = null;
            ImageCompare.Rect ownerRect = null;
            boolean active = false;
            if (map.get("focus") instanceof Map<?, ?> focus) {
                owner = focus.get("ownerLocator") instanceof Map<?, ?> locator ? (Map<String, Object>) locator : null;
                ownerRect = ImageCompare.Rect.of(focus.get("ownerRect"));
                active = Boolean.TRUE.equals(focus.get("active"));
            }
            double ox = 0;
            double oy = 0;
            if (map.get("origin") instanceof Map<?, ?> origin && origin.get("x") instanceof Number x
                    && origin.get("y") instanceof Number y) {
                ox = x.doubleValue();
                oy = y.doubleValue();
            }
            double scale = map.get("scale") instanceof Number s && s.doubleValue() > 0 ? s.doubleValue() : 1.0;
            Map<String, Object> window = null;
            if (map.get("windows") instanceof List<?> windows && windows.size() == 1
                    && windows.get(0) instanceof Map<?, ?> w && ImageCompare.Rect.of(w.get("rect")) != null) {
                ImageCompare.Rect rect = ImageCompare.Rect.of(w.get("rect"));
                Map<String, Object> size = new LinkedHashMap<>();
                size.put("w", (long) rect.w());
                size.put("h", (long) rect.h());
                window = new LinkedHashMap<>();
                window.put("size", size);
                if ("WINDOW".equals(map.get("scope"))) {
                    Map<String, Object> insets = new LinkedHashMap<>();
                    insets.put("top", (long) Math.round(oy));
                    insets.put("left", (long) Math.round(ox));
                    window.put("insets", insets);
                }
            }
            return new Capture(image, carets, redacted, new BindingsFile.Focus(owner, ownerRect, active), window, ox,
                    oy, scale);
        }

        /** A rectangle of the capture's space (a node's window bounds) in image coordinates. */
        ImageCompare.Rect toImage(ImageCompare.Rect r) {
            int x = (int) Math.floor((r.x() - originX) * scale);
            int y = (int) Math.floor((r.y() - originY) * scale);
            int w = (int) Math.ceil(r.w() * scale);
            int h = (int) Math.ceil(r.h() * scale);
            return new ImageCompare.Rect(x, y, Math.max(0, w), Math.max(0, h));
        }

        private static List<ImageCompare.Rect> rects(Object value, String key) {
            List<ImageCompare.Rect> out = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        ImageCompare.Rect r = ImageCompare.Rect.of(m.get(key));
                        if (r != null) {
                            out.add(r);
                        }
                    }
                }
            }
            return out;
        }
    }

    /** The empty baselines. */
    private enum NoBaselines implements Baselines {
        INSTANCE;

        @Override
        public BindingsFile.ImageEntry entry(String label, String renderKey) {
            return null;
        }

        @Override
        public Set<String> keys(String label) {
            return Set.of();
        }

        @Override
        public BufferedImage image(String label, String renderKey) throws IOException {
            throw new IOException("no baseline " + label + " for rendering " + renderKey);
        }

        @Override
        public void approved(String label, String renderKey, BindingsFile.ImageEntry entry, byte[] png) {
            // Nothing keeps them: a session without a bindings file approves nothing.
        }
    }
}
