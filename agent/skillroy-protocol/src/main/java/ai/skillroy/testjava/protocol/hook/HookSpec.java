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

package ai.skillroy.testjava.protocol.hook;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.JsonException;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One hook as the agent receives it (phase6-contracts.md section 4.1): JSON {@code {id, mode, target{class, method,
 * descriptors[], loader?, codeSource?}, emits?, capture?{args[], self, result}, secretArgs?[], secretResult?,
 * note?}}. A pack author's or a person's, never a model's (section 4.2): the controller's hook catalogue carries it to
 * the agent's {@code hook.probe} and {@code hook.install}.
 *
 * <p>{@link #fromJson} refuses the shape errors (an unknown key, a missing id, mode, class, method or descriptors, an
 * empty descriptor list, a negative {@code secretArgs} or {@code capture.args} index); {@link #violation()} checks the
 * name rules of section 3.3 item 1, which the agent answers with {@code INVALID_PARAMS} and the controller's catalogue
 * reader reports with the file's line.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class HookSpec {

    /** The hook id: lower-camel segments separated by dots; it becomes a capability id and a topic suffix. */
    public static final String ID_REGEX = "[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)*";
    /** The longest hook id. */
    public static final int MAX_ID_LENGTH = 64;
    /** The longest note (free text carried to the agent). */
    public static final int MAX_NOTE_LENGTH = 200;
    /** Every hook's topic starts with this; the default topic is {@code hook.<id>}. */
    public static final String TOPIC_PREFIX = "hook.";
    /**
     * A constructor's method name: a valid name, which the agent answers with the per-hook state
     * {@code UNAVAILABLE("constructors are not hookable")} (section 3.3 item 1), so that the call goes on with the
     * other specs and a catalogue's fallback applies.
     */
    public static final String CONSTRUCTOR = "<init>";
    /**
     * Hook ids that are refused because {@code hook.<id>} would be the engine's capability or one of the primitives'
     * names (section 4.6).
     */
    public static final List<String> RESERVED_IDS = Collections.unmodifiableList(Arrays.asList(
            "engine", "probe", "install", "stub", "remove", "health", "events"));

    private static final String TYPE = "HookSpec";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern ID = Pattern.compile(ID_REGEX);
    private static final String FIELD = "(?:\\[*(?:[BCDFIJSZ]|L[^;\\[.]+;))";
    private static final Pattern DESCRIPTOR = Pattern.compile("\\(" + FIELD + "*\\)(?:V|" + FIELD + ")");

    private final String id;
    private final HookMode mode;
    private final HookTarget target;
    private final String emits;
    private final HookCapture capture;
    private final List<Integer> secretArgs;
    private final boolean secretResult;
    private final String note;

    /**
     * @param id the hook id, not null
     * @param mode not null
     * @param target not null
     * @param emits the topic, or null for {@code hook.<id>}
     * @param capture what a CAPTURE hook copies, or null
     * @param secretArgs argument indexes that are never captured, null for none
     * @param secretResult true: the return value is never captured
     * @param note free text, or null
     * @throws IllegalArgumentException on a negative or null {@code secretArgs} index
     */
    public HookSpec(String id, HookMode mode, HookTarget target, String emits, HookCapture capture,
                    List<Integer> secretArgs, boolean secretResult, String note) {
        this.id = Objects.requireNonNull(id, "id");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.target = Objects.requireNonNull(target, "target");
        this.emits = emits;
        this.capture = capture;
        List<Integer> copy = secretArgs == null ? new ArrayList<Integer>() : new ArrayList<Integer>(secretArgs);
        for (Integer index : copy) {
            if (index == null || index.intValue() < 0) {
                throw new IllegalArgumentException("a secretArgs index must be a non-negative integer: " + index);
            }
        }
        this.secretArgs = Collections.unmodifiableList(copy);
        this.secretResult = secretResult;
        this.note = note;
    }

    public String id() {
        return id;
    }

    public HookMode mode() {
        return mode;
    }

    public HookTarget target() {
        return target;
    }

    /** The topic as given, or null (then {@link #topic()} is {@code hook.<id>}). */
    public String emits() {
        return emits;
    }

    /** The topic the hook's events are published under: {@link #emits()}, or {@code hook.<id>}. */
    public String topic() {
        return emits != null ? emits : TOPIC_PREFIX + id;
    }

    /** The capability the hook is declared as once installed: {@code hook.<id>}. */
    public String capabilityId() {
        return TOPIC_PREFIX + id;
    }

    /** What a CAPTURE hook copies, or null. */
    public HookCapture capture() {
        return capture;
    }

    /** Unmodifiable, never null. */
    public List<Integer> secretArgs() {
        return secretArgs;
    }

    public boolean secretResult() {
        return secretResult;
    }

    /** May be null. */
    public String note() {
        return note;
    }

    /**
     * The first name-rule violation of section 3.3 item 1 (and of sections 4.3 and 4.6), or null when there is none:
     * the id's pattern and length and the reserved ids, the class as a binary name, the method as an identifier or
     * {@link #CONSTRUCTOR} (a constructor is not a violation: the agent answers it with a per-hook state; a static
     * initialiser is one), each descriptor as a JVM method descriptor, the target's {@code codeSource} as a regular
     * expression of at most 200 characters (phase8-contracts.md section 6.3), the topic's {@code hook.} prefix, and the
     * note's length.
     */
    public Violation violation() {
        if (id.length() > MAX_ID_LENGTH) {
            return new Violation("id", "hook id longer than " + MAX_ID_LENGTH + " characters: " + id);
        }
        if (!ID.matcher(id).matches()) {
            return new Violation("id", "hook id '" + id + "' does not match " + ID_REGEX);
        }
        if (RESERVED_IDS.contains(id)) {
            return new Violation("id", "hook id '" + id + "' is reserved: hook." + id + " names the engine");
        }
        if (!isBinaryName(target.className())) {
            return new Violation("target.class", "not a binary class name: " + target.className());
        }
        if ("<clinit>".equals(target.method())) {
            return new Violation("target.method", "a static initialiser is not hookable");
        }
        if (!CONSTRUCTOR.equals(target.method()) && !isIdentifier(target.method())) {
            return new Violation("target.method", "not a method name: " + target.method());
        }
        for (int i = 0; i < target.descriptors().size(); i++) {
            String descriptor = target.descriptors().get(i);
            if (!DESCRIPTOR.matcher(descriptor).matches()) {
                return new Violation("target.descriptors[" + i + "]", "not a JVM method descriptor: " + descriptor);
            }
        }
        String codeSource = HookTarget.codeSourceViolation(target.codeSource());
        if (codeSource != null) {
            return new Violation("target.codeSource", codeSource);
        }
        if (emits != null && (!emits.startsWith(TOPIC_PREFIX) || emits.length() == TOPIC_PREFIX.length())) {
            return new Violation("emits", "a hook's topic must start with '" + TOPIC_PREFIX + "': " + emits);
        }
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            return new Violation("note", "note longer than " + MAX_NOTE_LENGTH + " characters");
        }
        return null;
    }

    /**
     * The SHA-256 of the canonical JSON ({@link Json#writeCanonical}) of {@link #toJson()}, hexadecimal and prefixed
     * {@code sha256:}; stable under the key order of the JSON the spec was read from.
     */
    public String hash() {
        return sha256(Json.writeCanonical(toJson()));
    }

    /**
     * {@code {"id","mode","target","emits"?,"capture"?,"secretArgs"?,"secretResult"?,"note"?}}: the optional keys only
     * when they say something ({@code secretArgs} when not empty, {@code secretResult} when true). A new map on each
     * call.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("id", id);
        json.put("mode", mode.name());
        json.put("target", target.toJson());
        if (emits != null) {
            json.put("emits", emits);
        }
        if (capture != null) {
            json.put("capture", capture.toJson());
        }
        if (!secretArgs.isEmpty()) {
            json.put("secretArgs", new ArrayList<Object>(secretArgs));
        }
        if (secretResult) {
            json.put("secretResult", Boolean.TRUE);
        }
        if (note != null) {
            json.put("note", note);
        }
        return json;
    }

    /**
     * Reads the shape written by {@link #toJson()}.
     *
     * @throws JsonException on an unknown key, a missing id, mode, class, method or descriptors, an empty descriptor
     *     list, a negative {@code secretArgs} or {@code capture.args} index, an unknown mode or a field of the wrong
     *     type
     */
    public static HookSpec fromJson(Map<String, Object> json) {
        HookFields.requireObject(json, TYPE);
        HookFields.refuseUnknown(json, TYPE, "id", "mode", "target", "emits", "capture", "secretArgs",
                "secretResult", "note");
        String id = HookFields.requireString(json, "id", TYPE);
        HookMode mode = HookFields.requireEnum(json, "mode", HookMode.class, TYPE);
        Map<String, Object> target = HookFields.optObject(json, "target", TYPE);
        if (target == null) {
            throw HookFields.missing("target", TYPE);
        }
        Map<String, Object> capture = HookFields.optObject(json, "capture", TYPE);
        return new HookSpec(id, mode, HookTarget.fromJson(target), HookFields.optString(json, "emits", TYPE),
                capture == null ? null : HookCapture.fromJson(capture),
                HookFields.indexes(json, "secretArgs", TYPE), HookFields.optBool(json, "secretResult", false, TYPE),
                HookFields.optString(json, "note", TYPE));
    }

    /** {@code sha256:} and the lower-case hexadecimal SHA-256 of the text's UTF-8 bytes. */
    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private static boolean isBinaryName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (String segment : name.split("\\.", -1)) {
            if (!isIdentifier(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HookSpec)) {
            return false;
        }
        HookSpec other = (HookSpec) o;
        return id.equals(other.id) && mode == other.mode && target.equals(other.target)
                && Objects.equals(emits, other.emits) && Objects.equals(capture, other.capture)
                && secretArgs.equals(other.secretArgs) && secretResult == other.secretResult
                && Objects.equals(note, other.note);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, mode, target, emits, capture, secretArgs, Boolean.valueOf(secretResult), note);
    }

    @Override
    public String toString() {
        return TYPE + Json.write(toJson());
    }

    /** One name-rule violation: the field (relative to the spec) and why. Immutable. */
    public static final class Violation {
        private final String field;
        private final String message;

        public Violation(String field, String message) {
            this.field = Objects.requireNonNull(field, "field");
            this.message = Objects.requireNonNull(message, "message");
        }

        /** The field, relative to the spec ({@code id}, {@code target.descriptors[1]}, ...). */
        public String field() {
            return field;
        }

        public String message() {
            return message;
        }

        @Override
        public String toString() {
            return field + ": " + message;
        }
    }
}
