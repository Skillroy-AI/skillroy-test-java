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

/**
 * A session's journal cannot be exported as a scenario (phase5-contracts.md 12.3): an argument the journal masked, a
 * call no {@code call} step can express, or a scenario that does not compile or whose references do not resolve
 * against the exported bindings. Nothing is written then. Names the session step the problem is about, when it is
 * about one.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class ExportException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String stepId;

    /**
     * @param stepId the session step ({@code s3}) the problem is about, or null
     * @param message one line
     */
    public ExportException(String stepId, String message) {
        super(stepId == null ? message : "step " + stepId + ": " + message);
        this.stepId = stepId;
    }

    /** As {@link #ExportException(String, String)}, with a cause. */
    public ExportException(String stepId, String message, Throwable cause) {
        super(stepId == null ? message : "step " + stepId + ": " + message, cause);
        this.stepId = stepId;
    }

    /** The session step the problem is about, or null. */
    public String stepId() {
        return stepId;
    }
}
