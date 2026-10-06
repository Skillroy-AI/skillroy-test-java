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
import java.util.Objects;

/**
 * A bind failed (phase2-contracts.md 10): {@code REBIND_NEEDED} ({@code replay}: a recorded answer no longer fits),
 * {@code RESOLUTION_FAILED} (asked now, this environment has no answer, or a reference does not resolve) or
 * {@code ENVIRONMENT} (credentials, budget, a pack defect, the fingerprint, the bindings file). Carries the reason
 * code and the detail the executor puts into {@code RunResult.detail}; the message and detail are masked by whoever
 * writes them.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class BindException extends Exception {

    private static final long serialVersionUID = 1L;

    private final FailureKind kind;
    private final String reason;
    private final String slot;
    private final transient Map<String, Object> detail;

    /**
     * @param kind the failure kind
     * @param reason the reason code ({@code NO_BINDINGS}, {@code VERIFIER}, …), or null for ENVIRONMENT failures
     *     without one
     * @param slot the slot (or {@code oracle.<name>}) the failure is about, or null
     * @param message one line
     * @param detail the detail of section 10 ({@code reason} and {@code slot} are added)
     */
    public BindException(FailureKind kind, String reason, String slot, String message, Map<String, Object> detail) {
        super(message, null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.reason = reason;
        this.slot = slot;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (reason != null) {
            copy.put("reason", reason);
        }
        if (slot != null) {
            if (slot.startsWith("oracle.")) {
                copy.put("oracle", slot.substring("oracle.".length()));
            } else {
                copy.put("slot", slot);
            }
        }
        if (detail != null) {
            detail.forEach(copy::putIfAbsent);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) Plan.freeze(copy);
        this.detail = frozen;
    }

    /** The failure kind. */
    public FailureKind kind() {
        return kind;
    }

    /** The reason code, or null. */
    public String reason() {
        return reason;
    }

    /** The slot or {@code oracle.<name>}, or null. */
    public String slot() {
        return slot;
    }

    /** The detail, with {@code reason} and {@code slot} (or {@code oracle}); unmodifiable. */
    public Map<String, Object> detail() {
        return detail;
    }
}
