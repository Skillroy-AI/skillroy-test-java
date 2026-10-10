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
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.scenario.image.ImageSteps;

import java.util.LinkedHashMap;

/**
 * How a host turns an image step's outcome into its own (phase7-contracts.md 6.7, decision D315): held goes on, an
 * {@code expectImage} mismatch is FAIL / ASSERTION, no baseline or an anchor mismatch REBIND_NEEDED (reason
 * {@code IMAGE}), a {@code live} run without a baseline a skipped line, an unusable capture or baseline file, or a
 * target that cannot answer {@code swing.rendering}, ERROR / ENVIRONMENT. Shared by {@code PlanExecutor} and
 * {@code ExecutorSession}.
 */
final class ImageRuns {

    private ImageRuns() {
    }

    /** One image step's work. */
    @FunctionalInterface
    interface Work {
        ImageSteps.Outcome run() throws AgentRpcException, AgentLostException, InterruptedException;
    }

    /**
     * Runs the work; an agent error or an unusable answer of {@code swing.rendering} is ERROR / ENVIRONMENT.
     *
     * @throws Abort ERROR / ENVIRONMENT
     */
    static ImageSteps.Outcome run(String stepId, Work work) throws Abort, AgentLostException, InterruptedException {
        try {
            return work.run();
        } catch (AgentRpcException e) {
            throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, "an image step could not read the target: "
                    + e.getMessage(), CallRunner.errorDetail("swing.rendering", e));
        } catch (IllegalStateException e) {
            throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, e.getMessage(),
                    RunLog.fields("image", "rendering"));
        }
    }

    /**
     * @return the skipped line of a {@code live} run without a baseline, or null when the step goes on
     * @throws Abort for a mismatch, a missing baseline outside {@code live}, an anchor mismatch or an environment error
     */
    static String raise(String stepId, ImageSteps.Outcome outcome) throws Abort {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>(outcome.detail());
        return switch (outcome.kind()) {
            case HELD -> null;
            case SKIPPED -> stepId + ": " + outcome.message();
            case FAILED -> throw new Abort(Status.FAIL, FailureKind.ASSERTION, stepId, outcome.message(), detail);
            case REBIND -> throw new Abort(Status.ERROR, FailureKind.REBIND_NEEDED, stepId, outcome.message(), detail);
            case ERROR -> throw new Abort(Status.ERROR, FailureKind.ENVIRONMENT, stepId, outcome.message(), detail);
        };
    }
}
