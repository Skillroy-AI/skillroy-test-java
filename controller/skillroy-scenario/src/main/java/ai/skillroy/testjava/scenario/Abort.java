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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stops a run, or ends a session's step, with a verdict: the failure classification of the step protocol
 * ({@link CallRunner}), shared by {@link PlanExecutor} and {@link ExecutorSession}. Never leaves the package.
 */
final class Abort extends Exception {

    private static final long serialVersionUID = 1L;

    final Status status;
    final FailureKind kind;
    final String stepId;
    final transient Map<String, Object> detail;

    Abort(Status status, FailureKind kind, String stepId, String message, Map<String, Object> detail) {
        super(message, null, false, false);
        this.status = status;
        this.kind = kind;
        this.stepId = stepId;
        this.detail = detail == null ? new LinkedHashMap<>() : detail;
    }
}
