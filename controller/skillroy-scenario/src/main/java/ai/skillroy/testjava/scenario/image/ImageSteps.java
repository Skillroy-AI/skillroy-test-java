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
import ai.skillroy.testjava.scenario.Approver;
import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.ImageCheck;
import ai.skillroy.testjava.scenario.Plan;
import ai.skillroy.testjava.scenario.resolve.BindMode;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
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
     * @param kind held, failed, rebind needed, or skipped
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
            SKIPPED
        }

        /** Copies the detail. */
        public Outcome {
            Objects.requireNonNull(kind, "kind");
            detail = detail == null ? Map.of() : Map.copyOf(detail);
        }
    }

    private final Host host;
    private final Baselines baselines;

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
     */
    public String renderKey() throws AgentRpcException, AgentLostException, InterruptedException {
        throw new UnsupportedOperationException("ImageSteps.renderKey: Phase 7 VC body lands after the API commit");
    }

    /**
     * Evaluates an {@code expectImage} step's check from its own capture call's result.
     *
     * @param stepId the step
     * @param check the step's check
     * @param capture the {@code swing.capture} result the step's call returned
     */
    public Outcome check(String stepId, ImageCheck check, Object capture) throws AgentRpcException,
            AgentLostException, InterruptedException {
        throw new UnsupportedOperationException("ImageSteps.check: Phase 7 VC body lands after the API commit");
    }

    /**
     * The guard of an anchored {@code pointer} call: the entry of the current render key for the guard's label, the
     * capture of that rectangle of the call's target ({@code swing.capture {target|by, scope: REGION, region, redact:
     * true}}), the comparison. {@link Outcome.Kind#HELD} lets the pointer call run.
     *
     * @param stepId the step
     * @param guard the call's guard
     * @param pointerArgs the pointer call's arguments, references substituted (its {@code target} or {@code by})
     */
    public Outcome guard(String stepId, Plan.Guard guard, Map<String, Object> pointerArgs) throws AgentRpcException,
            AgentLostException, InterruptedException {
        throw new UnsupportedOperationException("ImageSteps.guard: Phase 7 VC body lands after the API commit");
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
