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

import ai.skillroy.testjava.controller.AgentLostException;
import ai.skillroy.testjava.protocol.text.TextBounds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The verb {@code captureFacts} where a run or a session performs it (phase8-contracts.md 8.7, decisions D604, D605):
 * each field's path bound against the public values and resolved once on its root's value (one read per root), no
 * retry; a {@code size} field captures the size the {@code size} matcher measures (a list's or a map's size, a string's
 * length). The caller binds the returned value into the slot and writes the {@code capture} record.
 *
 * <p>Failures, as {@link Abort}s naming the step: a path that does not resolve, or a size of a value that has none, is
 * FAIL / ASSERTION "captureFacts slot.field: path did not resolve: …" with {@code {slot, field, path, reason}}, the
 * reason naming a value's kind, never the value (Phase 8 security fix S176); a path that is ambiguous (several elements
 * match a single filter) is FAIL / ASSERTION too (S154); a path a bound stopped ({@code UNDECIDABLE}) is ERROR /
 * ENVIRONMENT, as for a read error (S149); where the masked-value rule applies ({@code view} given: a session always, a
 * run with {@link RunOptions#expectationsOnMaskedValues()}) every path is followed on the fact's {@link FactView} and a
 * value that holds a secret (the view's marker), or a path that met one, is FAIL / ASSERTION with
 * {@link Matchers#HOLDS_A_SECRET}, decided before a {@code size} is measured or its failure written (S173, S177); a
 * plain path's value whose JSON text is longer than {@value #MAX_CAPTURED_CHARS} characters is FAIL / ASSERTION "the
 * value is too large to capture" (S150: a slot feeds an operand, and the {@code capture} record, {@code bindings.json}
 * and every matcher that substitutes it would copy it); a fact read that fails, or an unknown root, is ERROR /
 * ENVIRONMENT; a reference that does not resolve is ERROR / RESOLUTION_FAILED. Stateless.
 */
final class FactCapturing {

    /** The longest JSON text of a plain path's captured value (estimated, never under-counting a string). */
    static final int MAX_CAPTURED_CHARS = 64 * 1024;

    private FactCapturing() {
    }

    /** One root's value for this capture: {@code {value}}, or {@code {null, error}} when the read failed. */
    @FunctionalInterface
    interface Reader {
        Object[] read(String root) throws AgentLostException, InterruptedException;
    }

    /**
     * @param step a {@code captureFacts} step (its {@code facts} not null)
     * @param bindings what the paths' references resolve against (the public values)
     * @param roots the fact roots the run or session has
     * @param reader one read per root (the caller caches)
     * @param view where the masked-value rule applies, the view of the run's or the session's mask as it stands, else
     *     null (a person's run: the values as read)
     * @return the slot's value: each field's captured value, in field order (sorted)
     */
    static Map<String, Object> capture(Plan.Step step, Bindings bindings, Set<String> roots, Reader reader,
                                       Supplier<FactView> view) throws Abort, AgentLostException,
            InterruptedException {
        Plan.FactCapture facts = step.facts();
        String slot = facts.slot();
        Map<String, Object> value = new LinkedHashMap<>();
        Map<String, Object> viewed = new LinkedHashMap<>();
        FactView factView = view == null ? null : view.get();
        for (Map.Entry<String, Plan.FactField> e : facts.fields().entrySet()) {
            String field = e.getKey();
            String text = e.getValue().path();
            String where = Plan.CAPTURE_FACTS + " " + slot + "." + field;
            ExpectationPath path;
            try {
                path = ExpectationPath.parse(text).bind(bindings::lookupPublic);
            } catch (ResolutionException ex) {
                throw new Abort(Status.ERROR, FailureKind.RESOLUTION_FAILED, step.id(), ex.getMessage(),
                        RunLog.fields("reason", ex.reason(), "reference", ex.reference(), "slot", slot, "field", field,
                                "path", text));
            } catch (IllegalArgumentException ex) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, step.id(), "invalid " + where + " in the plan: "
                        + ex.getMessage(), RunLog.fields("slot", slot, "field", field, "path", text));
            }
            if (!roots.contains(path.root())) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, step.id(), "no fact provider for root '"
                        + path.root() + "' of " + text + "; roots: " + roots, RunLog.fields("slot", slot, "field",
                        field, "path", text));
            }
            Object[] read = reader.read(path.root());
            if (read.length == 2) {
                throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, step.id(), "fact '" + path.root()
                        + "' could not be read: " + read[1], RunLog.fields("slot", slot, "field", field, "path", text,
                        "error", read[1]));
            }
            ExpectationPath.Resolution resolution;
            if (factView == null) {
                resolution = path.resolve(read[0]);
            } else {
                Object root = viewed.get(path.root());
                if (root == null && !viewed.containsKey(path.root())) {
                    try {
                        root = factView.apply(read[0]);
                    } catch (IllegalArgumentException ex) {
                        throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, step.id(), "fact '" + path.root()
                                + "' could not be read: " + ex.getMessage(), RunLog.fields("slot", slot, "field",
                                field, "path", text, "error", ex.getMessage()));
                    }
                    viewed.put(path.root(), root);
                }
                resolution = path.resolveViewed(root, factView);
            }
            switch (resolution.state()) {
                case UNDECIDABLE -> throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, step.id(), where + ": "
                        + text + " could not be decided: " + resolution.problem(), RunLog.fields("slot", slot,
                        "field", field, "path", text, "reason", resolution.problem()));
                case SECRET -> throw secret(step, where, slot, field, text, null,
                        Matchers.HOLDS_A_SECRET + " (" + resolution.problem() + ")");
                case MISSING, AMBIGUOUS -> throw unresolved(step, where, slot, field, text, resolution.problem());
                default -> {
                }
            }
            Object captured = resolution.value();
            if (factView != null && FactView.holdsMarker(captured)) {
                // S173, S177: decided on the view, before a size is measured or its failure written.
                throw secret(step, where, slot, field, text, captured, Matchers.HOLDS_A_SECRET);
            }
            if (e.getValue().size()) {
                Long size = size(captured);
                if (size == null) {
                    throw unresolved(step, where, slot, field, text, "'size' needs a list, a map or a string, but the"
                            + " value is " + Values.kind(captured));
                }
                captured = size;
            } else if (TextBounds.exceeds(captured, MAX_CAPTURED_CHARS)) {
                String reason = "the value is too large to capture (more than " + MAX_CAPTURED_CHARS + " characters"
                        + " of JSON" + (captured instanceof List<?> list ? ", a list of " + list.size() : "")
                        + "); capture its size or a field";
                throw new Abort(Status.FAIL, FailureKind.ASSERTION, step.id(), where + ": " + reason,
                        RunLog.fields("slot", slot, "field", field, "path", text, "reason", reason));
            }
            value.put(field, captured);
        }
        return value;
    }

    private static Abort secret(Plan.Step step, String where, String slot, String field, String path,
                                Object actual, String reason) {
        Map<String, Object> detail = RunLog.fields("slot", slot, "field", field, "path", path, "reason", reason);
        if (actual != null) {
            detail.put("actual", RunLog.cutActual(RunLog.fields("actual", actual)).get("actual"));
        }
        return new Abort(Status.FAIL, FailureKind.ASSERTION, step.id(), where + ": " + reason, detail);
    }

    private static Abort unresolved(Plan.Step step, String where, String slot, String field, String path,
                                    String reason) {
        return new Abort(Status.FAIL, FailureKind.ASSERTION, step.id(), where + ": " + path + " did not resolve: "
                + reason, RunLog.fields("slot", slot, "field", field, "path", path, "reason", reason));
    }

    /** The size the {@code size} matcher measures: a list's or a map's size, a string's length; null for any other. */
    private static Long size(Object value) {
        if (value instanceof List<?> list) {
            return (long) list.size();
        }
        if (value instanceof Map<?, ?> map) {
            return (long) map.size();
        }
        if (value instanceof String s) {
            return (long) s.length();
        }
        return null;
    }
}
