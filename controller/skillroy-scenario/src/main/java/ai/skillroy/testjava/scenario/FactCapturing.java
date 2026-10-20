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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The verb {@code captureFacts} where a run or a session performs it (phase8-contracts.md 8.7, decisions D604, D605):
 * each field's path bound against the public values and resolved once on its root's value (one read per root), no
 * retry; a {@code size} field captures the size the {@code size} matcher measures (a list's or a map's size, a string's
 * length). The caller binds the returned value into the slot and writes the {@code capture} record.
 *
 * <p>Failures, as {@link Abort}s naming the step: a path that does not resolve, or a size of a value that has none, is
 * FAIL / ASSERTION "captureFacts slot.field: path did not resolve: …" with {@code {slot, field, path, reason}}; a
 * captured value that holds a secret (the caller's rule) is FAIL / ASSERTION with {@link Matchers#HOLDS_A_SECRET}; a
 * fact read that fails, or an unknown root, is ERROR / ENVIRONMENT; a reference that does not resolve is ERROR /
 * RESOLUTION_FAILED. Stateless.
 */
final class FactCapturing {

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
     * @param secret the masked form of a value that holds a secret, else null (the run's or the session's rule)
     * @return the slot's value: each field's captured value, in field order (sorted)
     */
    static Map<String, Object> capture(Plan.Step step, Bindings bindings, Set<String> roots, Reader reader,
                                       UnaryOperator<Object> secret) throws Abort, AgentLostException,
            InterruptedException {
        Plan.FactCapture facts = step.facts();
        String slot = facts.slot();
        Map<String, Object> value = new LinkedHashMap<>();
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
            ExpectationPath.Resolution resolution = path.resolve(read[0]);
            if (!resolution.resolved()) {
                throw unresolved(step, where, slot, field, text, resolution.problem());
            }
            Object captured = resolution.value();
            if (e.getValue().size()) {
                Long size = size(captured);
                if (size == null) {
                    throw unresolved(step, where, slot, field, text, "'size' needs a list, a map or a string, but the"
                            + " value is " + ScenarioLoader.describe(captured));
                }
                captured = size;
            }
            Object masked = secret.apply(captured);
            if (masked != null) {
                throw new Abort(Status.FAIL, FailureKind.ASSERTION, step.id(), where + ": " + Matchers.HOLDS_A_SECRET,
                        RunLog.fields("slot", slot, "field", field, "path", text, "reason", Matchers.HOLDS_A_SECRET,
                                "actual", masked));
            }
            value.put(field, captured);
        }
        return value;
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
