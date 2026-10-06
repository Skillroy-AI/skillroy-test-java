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
 * A {@code $slot} reference cannot be resolved against the run's bindings (the slot is unknown, a field along the
 * path is missing, fixture slots reference each other in a cycle, a credential field is referenced where it may not
 * be), or a resolver ends a slot with a reason of its own (phase2-contracts.md 5.1, 6.4). The executor reports it as
 * RESOLUTION_FAILED with {@link #reason()} in {@code detail.reason}.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class ResolutionException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The reason of the Phase 1 constructor. */
    public static final String REFERENCE = "REFERENCE";

    private final String reference;
    private final String reason;

    /**
     * A reference failure (reason {@code REFERENCE}), or, thrown by a resolver, a failure the binder reports with
     * reason {@code RESOLVER} and this message in {@code detail.message}.
     *
     * @param reference the reference text as written, for example {@code "$item.code"}; may be null
     */
    public ResolutionException(String reference, String message) {
        super(message);
        this.reference = reference;
        this.reason = null;
    }

    /**
     * A failure with a reason code ({@code TRUNCATED}, {@code REFERENCE}, or a code a resolver chose).
     *
     * @param reason the reason code, {@code [A-Z][A-Z0-9_]*} by convention; null means {@code REFERENCE}
     * @param reference the reference text, or null
     */
    public ResolutionException(String reason, String reference, String message) {
        super(message);
        this.reference = reference;
        this.reason = reason;
    }

    /** The reason code: {@code "REFERENCE"} for the two-argument constructor. */
    public String reason() {
        return reason == null ? REFERENCE : reason;
    }

    /** True when the exception was built with an explicit reason code. */
    public boolean hasReason() {
        return reason != null;
    }

    /** The reference as written in the plan, or null when the failure is not about one reference. */
    public String reference() {
        return reference;
    }
}
