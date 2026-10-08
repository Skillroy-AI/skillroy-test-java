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
 * A {@code $slot} reference cannot be resolved against the run's bindings (the slot is unknown, a field along the
 * path is missing, fixture slots reference each other in a cycle, a credential field is referenced where it may not
 * be), or a resolver ends a slot with a reason of its own (phase2-contracts.md 5.1, 6.4). The executor reports it as
 * RESOLUTION_FAILED with {@link #reason()} in {@code detail.reason}; a resolver's {@link #detail()} goes into the
 * failure's detail beside it (phase5-contracts.md 7.2: reason {@link #BUDGET} with {@code scope, id, limitUsd,
 * spentUsd, estimateUsd}).
 *
 * <p>Immutable; safe to share between threads.
 */
public final class ResolutionException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The reason of the Phase 1 constructor. */
    public static final String REFERENCE = "REFERENCE";

    /**
     * The reason of a model-backed resolver whose budget meter refused the call (phase5-contracts.md 7.2, decision
     * D74): the slot fails RESOLUTION_FAILED reason {@code BUDGET}; see {@link #budget}.
     */
    public static final String BUDGET = "BUDGET";

    private final String reference;
    private final String reason;
    private final transient Map<String, Object> detail;

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
        this.detail = Map.of();
    }

    /**
     * A failure with a reason code ({@code TRUNCATED}, {@code REFERENCE}, or a code a resolver chose).
     *
     * @param reason the reason code, {@code [A-Z][A-Z0-9_]*} by convention; null means {@code REFERENCE}
     * @param reference the reference text, or null
     */
    public ResolutionException(String reason, String reference, String message) {
        this(reason, reference, message, null);
    }

    /**
     * A failure with a reason code and the detail a resolver adds to the failure's {@code RunResult.detail} (beside
     * {@code reason}, {@code slot} and {@code message}, which the binder sets and the detail cannot replace).
     *
     * @param reason the reason code; null means {@code REFERENCE}
     * @param reference the reference text, or null
     * @param detail JSON-like values (strings, numbers, booleans, lists and maps of them), never a secret; null is
     *     empty; copied
     */
    public ResolutionException(String reason, String reference, String message, Map<String, Object> detail) {
        super(message);
        this.reference = reference;
        this.reason = reason;
        if (detail == null || detail.isEmpty()) {
            this.detail = Map.of();
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Object> frozen = (Map<String, Object>) Plan.freeze(new LinkedHashMap<>(detail));
            this.detail = frozen;
        }
    }

    /**
     * Reason {@link #BUDGET}: the meter of a scope had no room for a call's estimate (phase5-contracts.md 7.2). The
     * amounts are plain decimal strings of US dollars.
     *
     * @param scope the meter's scope ({@code SCENARIO}, {@code JOB}, {@code SESSION})
     * @param id the meter's id (the scenario, the job, the session)
     * @param limitUsd the limit
     * @param spentUsd what was spent before the call
     * @param estimateUsd what the refused call was estimated to cost
     */
    public static ResolutionException budget(String scope, String id, String limitUsd, String spentUsd,
                                             String estimateUsd) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scope", scope);
        detail.put("id", id);
        detail.put("limitUsd", limitUsd);
        detail.put("spentUsd", spentUsd);
        detail.put("estimateUsd", estimateUsd);
        return new ResolutionException(BUDGET, null, "the " + (scope == null ? "" : scope.toLowerCase(
                java.util.Locale.ROOT) + " ") + "budget of $" + limitUsd + " has $" + spentUsd + " spent and no room"
                + " for a model call estimated at $" + estimateUsd, detail);
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

    /** What the resolver adds to the failure's detail; empty unless given; unmodifiable. */
    public Map<String, Object> detail() {
        return detail == null ? Map.of() : detail;
    }
}
