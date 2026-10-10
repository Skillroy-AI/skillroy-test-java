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

package ai.skillroy.testjava.scenario;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;
import ai.skillroy.testjava.scenario.image.ImageCompare;
import ai.skillroy.testjava.scenario.resolve.Approval;
import ai.skillroy.testjava.scenario.resolve.CredentialRef;
import ai.skillroy.testjava.scenario.resolve.EnvFingerprint;
import ai.skillroy.testjava.scenario.resolve.LlmUsage;
import ai.skillroy.testjava.scenario.resolve.Provenance;
import ai.skillroy.testjava.scenario.resolve.Resolution;
import ai.skillroy.testjava.scenario.resolve.Strategy;
import ai.skillroy.testjava.scenario.resolve.VerifierResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A bindings file, {@code <bindingsRoot>/<envId>/<scenarioId>.bindings.json} (phase2-contracts.md 9): the reviewed
 * answers to a scenario's queries and its oracle values in one environment, committed next to the scenario and
 * reviewed as a diff. {@code replay} reads it and re-runs the verifiers; {@code approve} (and {@code live
 * --write-bindings}) writes it.
 *
 * <p>Version 2 (phase7-contracts.md 8.7, decisions D329, D330, D350, D355) adds {@code images}: per label (an
 * {@code expectImage} baseline or an anchored {@code pointer}'s anchor) per render key one {@link ImageEntry}, the
 * masked PNG beside the file under {@code <scenarioId>.images/<label>.<renderKey>.png}. The reader accepts versions 1
 * and 2; a file without images is still written as version 1, so nothing committed changes until an image enters it.
 * An entry's {@code file} is informative: the reader of the PNG derives the path from the label and the render key.
 *
 * <p>Format: {@link Json#writePretty}, keys in the documented order, resolutions and oracles in the plan's graph
 * order, ending with a newline; no timestamps, host names, paths, row samples, verifier evidence, notes or secrets.
 * Only query slots and oracles are recorded; an oracle with a cross-check also records it ({@code crossCheck:
 * {check, tolerance?, capability?}}, informational, phase3-contracts.md 8.3; {@code bindingsVersion} stays 1).
 * Record to file: {@code provenance.resolverId} is written
 * {@code provenance.resolver}, {@code verified} is {@code verifier} with {@code verifierId} as {@code id},
 * {@code Rejected.resolverId} is {@code resolver}, {@code usage} is {@code llm}, the slot is the map key. A value a
 * model found also records why ({@code provenance.rationale}, the last key of the provenance, phase5-contracts.md
 * 5.4 step 5); the key is left out when there is none, so that a tier (a) file is unchanged. The text
 * reads back to equal objects ({@link #toJsonText()} of a read file is byte-identical to what was read, when that was
 * written by this class). The file itself is never masked: it is built only from data that never held a secret, and
 * the binder refuses to write a text the run's mask would change.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param bindingsVersion {@link #VERSION}
 * @param scenarioId the scenario
 * @param envId the environment
 * @param pack the pack id (information)
 * @param packVersion the pack version (information)
 * @param planHash the plan's hash (information)
 * @param resolveHash what {@code replay} compares with {@code Plan.resolveHash()}
 * @param env the fingerprint with its components
 * @param resolutions slot to resolution, in graph order
 * @param oracles oracle name to value, in graph order
 * @param images label to render key to entry, both sorted (phase7-contracts.md 8.7); null means none
 */
public record BindingsFile(int bindingsVersion, String scenarioId, String envId, String pack, String packVersion,
                           String planHash, String resolveHash, EnvFingerprint env,
                           Map<String, Resolution> resolutions, Map<String, OracleValue> oracles,
                           Map<String, Map<String, ImageEntry>> images) {

    /** The newest bindings file format this code reads and writes (version 2 holds {@code images}). */
    public static final int VERSION = 2;

    /** The version of a file without images. */
    public static final int VERSION_WITHOUT_IMAGES = 1;

    /** What a render key in a file looks like: it names a file, so nothing else is accepted. */
    static final Pattern RENDER_KEY = Pattern.compile("[A-Za-z0-9]{8,64}");

    /** The file name suffix. */
    public static final String SUFFIX = ".bindings.json";

    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");

    /**
     * Copies the maps (order kept; the images sorted by label and key). The version is 1 for a file without images and
     * {@link #VERSION} for one with them, whatever was given.
     */
    public BindingsFile {
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(envId, "envId");
        Objects.requireNonNull(env, "env");
        resolutions = Collections.unmodifiableMap(new LinkedHashMap<>(resolutions));
        oracles = Collections.unmodifiableMap(new LinkedHashMap<>(oracles));
        Map<String, Map<String, ImageEntry>> sorted = new java.util.TreeMap<>();
        if (images != null) {
            images.forEach((label, byKey) -> {
                if (byKey != null && !byKey.isEmpty()) {
                    sorted.put(label, Collections.unmodifiableMap(new java.util.TreeMap<>(byKey)));
                }
            });
        }
        images = Collections.unmodifiableMap(sorted);
        bindingsVersion = images.isEmpty() ? VERSION_WITHOUT_IMAGES : VERSION;
    }

    /** A file without images (the version 1 shape). */
    public BindingsFile(int bindingsVersion, String scenarioId, String envId, String pack, String packVersion,
                        String planHash, String resolveHash, EnvFingerprint env,
                        Map<String, Resolution> resolutions, Map<String, OracleValue> oracles) {
        this(bindingsVersion, scenarioId, envId, pack, packVersion, planHash, resolveHash, env, resolutions, oracles,
                Map.of());
    }

    /** This file with another {@code images} section (null: none). */
    public BindingsFile withImages(Map<String, Map<String, ImageEntry>> newImages) {
        return new BindingsFile(bindingsVersion, scenarioId, envId, pack, packVersion, planHash, resolveHash, env,
                resolutions, oracles, newImages);
    }

    /** {@code <bindingsRoot>/<envId>/}: the directory the file and its {@code <scenarioId>.images/} live in. */
    public static Path envDir(Path bindingsRoot, String envId) {
        return bindingsRoot.resolve(envId);
    }

    /** {@code <bindingsRoot>/<envId>/<scenarioId>.bindings.json}. */
    public static Path locate(Path bindingsRoot, String envId, String scenarioId) {
        return bindingsRoot.resolve(envId).resolve(scenarioId + SUFFIX);
    }

    /**
     * Reads and checks a bindings file.
     *
     * @throws IOException if the file cannot be read
     * @throws BindingsFormatException if it is not a bindings file of a known version
     */
    public static BindingsFile read(Path file) throws IOException, BindingsFormatException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * @throws BindingsFormatException if the text is not a bindings file of a known version
     */
    public static BindingsFile parse(String text) throws BindingsFormatException {
        Map<String, Object> json;
        try {
            json = Json.parseObject(text);
        } catch (JsonException e) {
            throw new BindingsFormatException("not a JSON object: " + e.getMessage());
        }
        return new Reader().file(json);
    }

    /** The JSON object, in the documented key order; a new map on each call. */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("bindingsVersion", bindingsVersion);
        json.put("scenarioId", scenarioId);
        json.put("envId", envId);
        json.put("pack", pack);
        json.put("packVersion", packVersion);
        json.put("planHash", planHash);
        json.put("resolveHash", resolveHash);
        json.put("env", envJson(env));
        Map<String, Object> slots = new LinkedHashMap<>();
        resolutions.forEach((slot, resolution) -> slots.put(slot, resolutionJson(resolution)));
        json.put("resolutions", slots);
        Map<String, Object> oracleJson = new LinkedHashMap<>();
        oracles.forEach((name, value) -> oracleJson.put(name, value.toJson()));
        json.put("oracles", oracleJson);
        if (!images.isEmpty()) {
            Map<String, Object> imageJson = new LinkedHashMap<>();
            images.forEach((label, byKey) -> {
                Map<String, Object> keys = new LinkedHashMap<>();
                byKey.forEach((key, entry) -> keys.put(key, entry.toJson()));
                imageJson.put(label, keys);
            });
            json.put("images", imageJson);
        }
        return json;
    }

    /** The file's text: deterministic, as documented. */
    public String toJsonText() {
        return Json.writePretty(toJson());
    }

    /**
     * Writes the file: the text goes to a temporary file in the same directory (created if needed), then an atomic
     * move replaces the target.
     *
     * @throws IOException if writing fails
     */
    public void write(Path file) throws IOException {
        writeText(file, toJsonText());
    }

    static void writeText(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + file.getFileName(), ".tmp");
        try {
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** What differs between this file and {@code other} (this = before, other = after). */
    public BindingsDiff diff(BindingsFile other) {
        return BindingsDiff.between(this, other);
    }

    static Map<String, Object> envJson(EnvFingerprint env) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("fingerprint", env.hash());
        json.put("components", new LinkedHashMap<>(env.components()));
        return json;
    }

    /** One resolution in the file's shape. */
    static Map<String, Object> resolutionJson(Resolution r) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("query", r.query());
        json.put("args", r.args());
        json.put("argsHash", r.argsHash());
        json.put("value", r.value());
        if (r.credential() != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("realm", r.credential().realm());
            c.put("id", r.credential().id());
            json.put("credential", c);
        }
        json.put("strategy", r.strategy().name());
        json.put("provenance", provenanceJson(r.provenance()));
        List<Object> verifiers = new ArrayList<>();
        for (VerifierResult v : r.verified()) {
            Map<String, Object> vj = new LinkedHashMap<>();
            vj.put("id", v.verifierId());
            vj.put("passed", v.passed());
            vj.put("reason", v.reason());
            verifiers.add(vj);
        }
        json.put("verifier", verifiers);
        json.put("confidence", r.confidence());
        Map<String, Object> approval = new LinkedHashMap<>();
        Approval a = r.approval() == null ? new Approval(Approval.State.NOT_REQUIRED, null) : r.approval();
        approval.put("state", a.state().name());
        approval.put("by", a.by());
        json.put("approval", approval);
        json.put("llm", r.usage() == null ? null : usageJson(r.usage()));
        return json;
    }

    static Map<String, Object> provenanceJson(Provenance p) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("resolver", p.resolverId());
        List<Object> statements = new ArrayList<>();
        for (Provenance.Statement s : p.statements()) {
            Map<String, Object> sj = new LinkedHashMap<>();
            sj.put("channel", s.channel());
            sj.put("source", s.source());
            sj.put("sql", s.sql());
            sj.put("params", s.params());
            sj.put("rowCount", s.rowCount());
            sj.put("truncated", s.truncated());
            statements.add(sj);
        }
        json.put("statements", statements);
        List<Object> calls = new ArrayList<>();
        for (Provenance.Call c : p.calls()) {
            Map<String, Object> cj = new LinkedHashMap<>();
            cj.put("primitive", c.primitive());
            cj.put("params", c.params());
            calls.add(cj);
        }
        json.put("calls", calls);
        json.put("candidates", p.candidates());
        json.put("candidatesTruncated", p.candidatesTruncated());
        List<Object> rejected = new ArrayList<>();
        for (Provenance.Rejected r : p.rejected()) {
            Map<String, Object> rj = new LinkedHashMap<>();
            rj.put("resolver", r.resolverId());
            rj.put("label", r.label());
            rj.put("reason", r.reason());
            rejected.add(rj);
        }
        json.put("rejected", rejected);
        if (p.rationale() != null) {
            json.put("rationale", p.rationale());
        }
        return json;
    }

    private static Map<String, Object> usageJson(LlmUsage usage) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("model", usage.model());
        json.put("requestId", usage.requestId());
        json.put("inputTokens", usage.inputTokens());
        json.put("outputTokens", usage.outputTokens());
        json.put("costUsd", usage.costUsd());
        return json;
    }

    /**
     * One image baseline or anchor of the {@code images} section (phase7-contracts.md 8.7, decisions D329, D330, D350,
     * D355), under its label and the render key it was made with: the masked raster's hash and size, the capture's
     * masks, carets and focus (what a comparison blanks on the baseline's side), the derived file name, the rendering
     * for the reviewer, the window's size and insets, and for an anchor its origin in the step's target's coordinate
     * space. JSON: {@code {sha256, width, height, masks, carets, focus: {owner, ownerRect?, active}, file, rendering,
     * window, anchor?}}.
     *
     * @param sha256 the masked raster's hash ({@code ImageCompare.rasterHash}), {@code sha256:<hex>}
     * @param width the image's width
     * @param height the image's height
     * @param masks the rectangles the capture blanked (its {@code redacted}) and the step's own masks, image coordinates
     * @param carets the capture's caret rectangles, image coordinates
     * @param focus the capture's focus state
     * @param file {@code <scenarioId>.images/<label>.<renderKey>.png}: informative; the reader derives the path and
     *     refuses a mismatch
     * @param rendering {@code swing.rendering}'s answer without {@code renderKey} (nested), for the reviewer only
     * @param window {@code {size: {w, h}, insets: {top, left}}}, or null
     * @param anchor an anchor's origin in the step's target's coordinate space; null for a baseline
     */
    public record ImageEntry(String sha256, int width, int height, List<ImageCompare.Rect> masks,
                             List<ImageCompare.Rect> carets, Focus focus, String file, Map<String, Object> rendering,
                             Map<String, Object> window, Anchor anchor) {

        /** Copies and freezes. */
        @SuppressWarnings("unchecked")
        public ImageEntry {
            Objects.requireNonNull(sha256, "sha256");
            if (width <= 0 || height <= 0 || width > ImageCompare.MAX_SIDE || height > ImageCompare.MAX_SIDE) {
                throw new IllegalArgumentException("an image entry is 1 to " + ImageCompare.MAX_SIDE
                        + " pixels a side, found " + width + " x " + height);
            }
            masks = masks == null ? List.of() : List.copyOf(masks);
            carets = carets == null ? List.of() : List.copyOf(carets);
            focus = focus == null ? new Focus(null, null, false) : focus;
            rendering = rendering == null ? Map.of() : (Map<String, Object>) Plan.freeze(rendering);
            window = window == null ? null : (Map<String, Object>) Plan.freeze(window);
        }

        /** The file name an entry of this label and render key has: {@code <scenarioId>.images/<label>.<key>.png}. */
        public static String fileName(String scenarioId, String label, String renderKey) {
            return scenarioId + ".images/" + label + "." + renderKey + ".png";
        }

        /** This entry with another file name. */
        public ImageEntry withFile(String newFile) {
            return new ImageEntry(sha256, width, height, masks, carets, focus, newFile, rendering, window, anchor);
        }

        /** The file's shape, in the documented key order; a new map. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("sha256", sha256);
            json.put("width", (long) width);
            json.put("height", (long) height);
            json.put("masks", masks.stream().map(ImageCompare.Rect::toJson).toList());
            json.put("carets", carets.stream().map(ImageCompare.Rect::toJson).toList());
            json.put("focus", focus.toJson());
            json.put("file", file);
            json.put("rendering", rendering);
            if (window != null) {
                json.put("window", window);
            }
            if (anchor != null) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("x", (long) anchor.x());
                a.put("y", (long) anchor.y());
                json.put("anchor", a);
            }
            return json;
        }
    }

    /**
     * The focus state of a capture as an image entry records it (phase7-contracts.md 3.1, 8.7).
     *
     * @param owner the focus owner's locator ({@code focus.ownerLocator} of the capture), null when nothing had the
     *     focus or no locator matched it alone
     * @param ownerRect the focus owner's rectangle in image coordinates, or null
     * @param active whether the captured window was active
     */
    public record Focus(Map<String, Object> owner, ImageCompare.Rect ownerRect, boolean active) {

        /** Freezes the locator. */
        @SuppressWarnings("unchecked")
        public Focus {
            owner = owner == null ? null : (Map<String, Object>) Plan.freeze(owner);
        }

        /** {@code {owner, ownerRect?, active}}; a new map. */
        public Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("owner", owner);
            if (ownerRect != null) {
                json.put("ownerRect", ownerRect.toJson());
            }
            json.put("active", active);
            return json;
        }
    }

    /**
     * An anchor's origin in the step's target's coordinate space (phase7-contracts.md 6.5, 8.7).
     *
     * @param x the left edge
     * @param y the top edge
     */
    public record Anchor(int x, int y) {
    }

    /** Typed reading with errors that name the place. */
    private static final class Reader {

        private static final Set<String> TOP = Set.of("bindingsVersion", "scenarioId", "envId", "pack",
                "packVersion", "planHash", "resolveHash", "env", "resolutions", "oracles", "images");
        private static final Set<String> IMAGE = Set.of("sha256", "width", "height", "masks", "carets", "focus",
                "file", "rendering", "window", "anchor");
        private static final Set<String> RESOLUTION = Set.of("query", "args", "argsHash", "value", "credential",
                "strategy", "provenance", "verifier", "confidence", "approval", "llm");
        private static final Set<String> PROVENANCE = Set.of("resolver", "statements", "calls", "candidates",
                "candidatesTruncated", "rejected", "rationale");

        BindingsFile file(Map<String, Object> json) throws BindingsFormatException {
            keys(json, TOP, "the file");
            if (!(json.get("bindingsVersion") instanceof Long version) || version < VERSION_WITHOUT_IMAGES
                    || version > VERSION) {
                throw new BindingsFormatException("bindingsVersion " + json.get("bindingsVersion")
                        + " is not supported (expected " + VERSION_WITHOUT_IMAGES + " or " + VERSION + ")");
            }
            if (json.containsKey("images") && version < VERSION) {
                throw new BindingsFormatException("'images' needs bindingsVersion " + VERSION);
            }
            String scenarioId = id(json, "scenarioId");
            String envId = id(json, "envId");
            Map<String, Object> envJson = object(json, "env", "the file");
            keys(envJson, Set.of("fingerprint", "components"), "env");
            Map<String, String> components = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : object(envJson, "components", "env").entrySet()) {
                if (!(e.getValue() instanceof String value)) {
                    throw new BindingsFormatException("env.components." + e.getKey() + " must be a string");
                }
                components.put(e.getKey(), value);
            }
            EnvFingerprint env = new EnvFingerprint(EnvFingerprint.VERSION, str(envJson, "fingerprint", "env"),
                    components);
            Map<String, Resolution> resolutions = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : object(json, "resolutions", "the file").entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> raw)) {
                    throw new BindingsFormatException("resolutions." + e.getKey() + " must be an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) raw;
                resolutions.put(e.getKey(), resolution(e.getKey(), r));
            }
            Map<String, OracleValue> oracles = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : object(json, "oracles", "the file").entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> raw)) {
                    throw new BindingsFormatException("oracles." + e.getKey() + " must be an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> o = (Map<String, Object>) raw;
                String where = "oracles." + e.getKey();
                keys(o, Set.of("fn", "inputsHash", "value", "mustEqual", "crossCheck"), where);
                if (!o.containsKey("value")) {
                    throw new BindingsFormatException(where + ": 'value' is required");
                }
                Plan.CrossCheckSpec crossCheck = o.get("crossCheck") == null ? null
                        : crossCheck(object(o, "crossCheck", where), where + ".crossCheck");
                oracles.put(e.getKey(), new OracleValue(e.getKey(), str(o, "fn", where), str(o, "inputsHash", where),
                        o.get("value"), o.get("mustEqual"), crossCheck));
            }
            Map<String, Map<String, ImageEntry>> images = new LinkedHashMap<>();
            if (json.containsKey("images")) {
                for (Map.Entry<String, Object> e : object(json, "images", "the file").entrySet()) {
                    String label = e.getKey();
                    if (!ImageCheck.LABEL.matcher(label).matches()) {
                        throw new BindingsFormatException("images: '" + label + "' is not a label");
                    }
                    if (!(e.getValue() instanceof Map<?, ?> raw)) {
                        throw new BindingsFormatException("images." + label + " must be an object");
                    }
                    Map<String, ImageEntry> byKey = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> k : raw.entrySet()) {
                        String key = String.valueOf(k.getKey());
                        String where = "images." + label + "." + key;
                        if (!RENDER_KEY.matcher(key).matches()) {
                            throw new BindingsFormatException(where + ": the render key must match [A-Za-z0-9]{8,64}");
                        }
                        if (!(k.getValue() instanceof Map<?, ?> entry)) {
                            throw new BindingsFormatException(where + " must be an object");
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) entry;
                        byKey.put(key, imageEntry(typed, where));
                    }
                    images.put(label, byKey);
                }
            }
            return new BindingsFile(VERSION, scenarioId, envId, nullableStr(json, "pack"),
                    nullableStr(json, "packVersion"), nullableStr(json, "planHash"),
                    str(json, "resolveHash", "the file"), env, resolutions, oracles, images);
        }

        /** One entry of {@code images} (phase7-contracts.md 8.7). */
        @SuppressWarnings("unchecked")
        private static ImageEntry imageEntry(Map<String, Object> e, String where) throws BindingsFormatException {
            keys(e, IMAGE, where);
            String sha = str(e, "sha256", where);
            if (!sha.matches("sha256:[0-9a-f]{64}")) {
                throw new BindingsFormatException(where + ": 'sha256' must be sha256:<64 hex digits>");
            }
            long width = num(e, "width", where);
            long height = num(e, "height", where);
            List<ImageCompare.Rect> masks = rects(e, "masks", where);
            List<ImageCompare.Rect> carets = rects(e, "carets", where);
            Map<String, Object> f = object(e, "focus", where);
            keys(f, Set.of("owner", "ownerRect", "active"), where + ".focus");
            Object owner = f.get("owner");
            if (owner != null && !(owner instanceof Map)) {
                throw new BindingsFormatException(where + ".focus: 'owner' must be a locator object or null");
            }
            ImageCompare.Rect ownerRect = null;
            if (f.get("ownerRect") != null) {
                ownerRect = ImageCompare.Rect.of(f.get("ownerRect"));
                if (ownerRect == null) {
                    throw new BindingsFormatException(where + ".focus: 'ownerRect' must be {x, y, w, h}");
                }
            }
            BindingsFile.Focus focus = new BindingsFile.Focus((Map<String, Object>) owner, ownerRect,
                    bool(f, "active", where + ".focus"));
            Map<String, Object> rendering = object(e, "rendering", where);
            Map<String, Object> window = e.get("window") == null ? null : object(e, "window", where);
            Anchor anchor = null;
            if (e.get("anchor") != null) {
                Map<String, Object> a = object(e, "anchor", where);
                keys(a, Set.of("x", "y"), where + ".anchor");
                if (!(a.get("x") instanceof Long x) || !(a.get("y") instanceof Long y)
                        || Math.abs(x) > 100_000 || Math.abs(y) > 100_000) {
                    throw new BindingsFormatException(where + ".anchor: 'x' and 'y' must be integers");
                }
                anchor = new Anchor(x.intValue(), y.intValue());
            }
            try {
                return new ImageEntry(sha, (int) width, (int) height, masks, carets, focus, str(e, "file", where),
                        rendering, window, anchor);
            } catch (IllegalArgumentException ex) {
                throw new BindingsFormatException(where + ": " + ex.getMessage());
            }
        }

        private static List<ImageCompare.Rect> rects(Map<String, Object> json, String key, String where)
                throws BindingsFormatException {
            List<ImageCompare.Rect> out = new ArrayList<>();
            for (Map<String, Object> r : objects(json, key, where)) {
                keys(r, Set.of("x", "y", "w", "h"), where + "." + key);
                ImageCompare.Rect rect = ImageCompare.Rect.of(r);
                if (rect == null || !(r.get("x") instanceof Long) || !(r.get("y") instanceof Long)
                        || !(r.get("w") instanceof Long) || !(r.get("h") instanceof Long)) {
                    throw new BindingsFormatException(where + "." + key + ": a rectangle is {x, y, w, h} in integers");
                }
                out.add(rect);
            }
            return out;
        }

        private Resolution resolution(String slot, Map<String, Object> r) throws BindingsFormatException {
            String where = "resolutions." + slot;
            keys(r, RESOLUTION, where);
            if (r.get("value") == null) {
                throw new BindingsFormatException(where + ": 'value' is required and never null");
            }
            Strategy strategy;
            try {
                strategy = Strategy.valueOf(str(r, "strategy", where));
            } catch (IllegalArgumentException e) {
                throw new BindingsFormatException(where + ": unknown strategy '" + r.get("strategy") + "'");
            }
            CredentialRef credential = null;
            if (r.get("credential") != null) {
                Map<String, Object> c = object(r, "credential", where);
                keys(c, Set.of("realm", "id"), where + ".credential");
                credential = new CredentialRef(str(c, "realm", where + ".credential"),
                        str(c, "id", where + ".credential"));
            }
            List<VerifierResult> verified = new ArrayList<>();
            for (Map<String, Object> v : objects(r, "verifier", where)) {
                keys(v, Set.of("id", "passed", "reason"), where + ".verifier");
                if (!(v.get("passed") instanceof Boolean passed)) {
                    throw new BindingsFormatException(where + ".verifier: 'passed' must be a boolean");
                }
                verified.add(new VerifierResult(str(v, "id", where + ".verifier"), passed,
                        nullableStr(v, "reason"), Map.of()));
            }
            if (!(r.get("confidence") instanceof Number confidence)) {
                throw new BindingsFormatException(where + ": 'confidence' must be a number");
            }
            Map<String, Object> a = object(r, "approval", where);
            keys(a, Set.of("state", "by"), where + ".approval");
            Approval approval;
            try {
                approval = new Approval(Approval.State.valueOf(str(a, "state", where + ".approval")),
                        nullableStr(a, "by"));
            } catch (IllegalArgumentException e) {
                throw new BindingsFormatException(where + ".approval: unknown state '" + a.get("state") + "'");
            }
            LlmUsage usage = null;
            if (r.get("llm") != null) {
                Map<String, Object> u = object(r, "llm", where);
                usage = new LlmUsage(nullableStr(u, "model"), nullableStr(u, "requestId"),
                        u.get("inputTokens") instanceof Long in ? in : 0L,
                        u.get("outputTokens") instanceof Long out ? out : 0L, nullableStr(u, "costUsd"));
            }
            return new Resolution(slot, str(r, "query", where), r.get("args"), str(r, "argsHash", where),
                    r.get("value"), strategy, provenance(object(r, "provenance", where), where + ".provenance"),
                    verified, confidence.doubleValue(), credential, approval, usage);
        }

        /** An oracle's informational {@code crossCheck}: {@code {check, tolerance?, capability?}} (8.3). */
        private static Plan.CrossCheckSpec crossCheck(Map<String, Object> c, String where)
                throws BindingsFormatException {
            keys(c, Set.of("check", "tolerance", "capability"), where);
            try {
                return new Plan.CrossCheckSpec(str(c, "check", where), nullableStr(c, "tolerance"),
                        nullableStr(c, "capability"));
            } catch (IllegalArgumentException e) {
                throw new BindingsFormatException(where + ": " + e.getMessage());
            }
        }

        private Provenance provenance(Map<String, Object> p, String where) throws BindingsFormatException {
            keys(p, PROVENANCE, where);
            List<Provenance.Statement> statements = new ArrayList<>();
            for (Map<String, Object> s : objects(p, "statements", where)) {
                String w = where + ".statements";
                keys(s, Set.of("channel", "source", "sql", "params", "rowCount", "truncated"), w);
                if (!(s.get("params") instanceof List<?> params)) {
                    throw new BindingsFormatException(w + ": 'params' must be an array");
                }
                statements.add(new Provenance.Statement(str(s, "channel", w), str(s, "source", w), str(s, "sql", w),
                        new ArrayList<>(params), (int) num(s, "rowCount", w), bool(s, "truncated", w), List.of()));
            }
            List<Provenance.Call> calls = new ArrayList<>();
            for (Map<String, Object> c : objects(p, "calls", where)) {
                keys(c, Set.of("primitive", "params"), where + ".calls");
                calls.add(new Provenance.Call(str(c, "primitive", where + ".calls"), c.get("params")));
            }
            List<Provenance.Rejected> rejected = new ArrayList<>();
            for (Map<String, Object> r : objects(p, "rejected", where)) {
                keys(r, Set.of("resolver", "label", "reason"), where + ".rejected");
                rejected.add(new Provenance.Rejected(nullableStr(r, "resolver"), nullableStr(r, "label"),
                        nullableStr(r, "reason")));
            }
            return new Provenance(nullableStr(p, "resolver"), statements, calls, List.of(),
                    (int) num(p, "candidates", where), bool(p, "candidatesTruncated", where), rejected,
                    nullableStr(p, "rationale"));
        }

        private static void keys(Map<String, Object> json, Set<String> allowed, String where)
                throws BindingsFormatException {
            for (String key : json.keySet()) {
                if (!allowed.contains(key)) {
                    throw new BindingsFormatException(where + ": unknown key '" + key + "'");
                }
            }
        }

        private static String id(Map<String, Object> json, String key) throws BindingsFormatException {
            String value = str(json, key, "the file");
            if (!ID.matcher(value).matches()) {
                throw new BindingsFormatException("'" + key + "' must match [a-z0-9-]+");
            }
            return value;
        }

        private static String str(Map<String, Object> json, String key, String where)
                throws BindingsFormatException {
            if (!(json.get(key) instanceof String s)) {
                throw new BindingsFormatException(where + ": '" + key + "' must be a string");
            }
            return s;
        }

        private static String nullableStr(Map<String, Object> json, String key) throws BindingsFormatException {
            Object value = json.get(key);
            if (value != null && !(value instanceof String)) {
                throw new BindingsFormatException("'" + key + "' must be a string or null");
            }
            return (String) value;
        }

        private static long num(Map<String, Object> json, String key, String where) throws BindingsFormatException {
            if (!(json.get(key) instanceof Long n) || n < 0 || n > Integer.MAX_VALUE) {
                throw new BindingsFormatException(where + ": '" + key + "' must be a non-negative integer");
            }
            return n;
        }

        private static boolean bool(Map<String, Object> json, String key, String where)
                throws BindingsFormatException {
            if (!(json.get(key) instanceof Boolean b)) {
                throw new BindingsFormatException(where + ": '" + key + "' must be a boolean");
            }
            return b;
        }

        private static Map<String, Object> object(Map<String, Object> json, String key, String where)
                throws BindingsFormatException {
            if (!(json.get(key) instanceof Map<?, ?> map)) {
                throw new BindingsFormatException(where + ": '" + key + "' must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }

        private static List<Map<String, Object>> objects(Map<String, Object> json, String key, String where)
                throws BindingsFormatException {
            if (!(json.get(key) instanceof List<?> list)) {
                throw new BindingsFormatException(where + ": '" + key + "' must be an array");
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    throw new BindingsFormatException(where + ": '" + key + "' must hold objects");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                out.add(typed);
            }
            return out;
        }
    }
}
