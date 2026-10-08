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

import java.util.Map;
import java.util.Objects;

/**
 * An interactive session could not open (phase5-contracts.md 12.2): a pre-flight check failed, a pinned web path is
 * not usable, the fingerprint could not be computed, or the journal could not be written. Carries the failure kind
 * a run would report ({@code ENVIRONMENT} or {@code PRECONDITION_FAILED}) and its detail, masked.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class SessionOpenException extends Exception {

    private static final long serialVersionUID = 1L;

    private final FailureKind kind;
    private final transient Map<String, Object> detail;

    /**
     * @param kind the failure kind
     * @param message one line
     * @param detail the detail, or null
     */
    public SessionOpenException(FailureKind kind, String message, Map<String, Object> detail) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = detail == null ? Map.of() : (Map<String, Object>) Plan.freeze(detail);
        this.detail = frozen;
    }

    /** The failure kind. */
    public FailureKind kind() {
        return kind;
    }

    /** The detail; unmodifiable, never null. */
    public Map<String, Object> detail() {
        return detail;
    }
}
