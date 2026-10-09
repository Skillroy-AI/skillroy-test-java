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

package ai.skillroy.testjava.controller;

import java.util.List;

/**
 * Per-call metadata sent as {@code params._meta}: correlation id, timeout override, destructive-call confirmation, the
 * names of params whose values are secrets and, from Phase 6, the W3C trace parent of the controller's open call span.
 * Separate from the params so primitives never see it.
 *
 * <p>Immutable; safe to share between threads.
 *
 * @param correlationId correlation id for the call and the events it causes; null lets the client mint one
 * @param timeoutMs timeout override in milliseconds (the agent caps it at 120000); null uses the primitive's default
 * @param confirm true to allow a destructive primitive
 * @param secretParams top-level param names whose values the agent must redact wherever it logs, traces or reports
 *     the call (amendment A34, {@code _meta.secretParams}); empty for none. Why on the options: the primitive still
 *     receives the values; only the agent's own records are told what they are
 * @param traceparent the W3C {@code traceparent} of the controller's open call span (phase6-contracts.md 10.2, decision
 *     D142: {@code RunObserver.currentTraceparent()}), sent as {@code _meta.traceparent} so that the agent stamps it on
 *     every event of the call; null when no telemetry runs (nothing is sent). Not a secret: a trace and a span id
 */
public record CallOptions(String correlationId, Long timeoutMs, boolean confirm, List<String> secretParams,
                          String traceparent) {

    /** The most secret param names one call may announce (the agent refuses more). */
    public static final int MAX_SECRET_PARAMS = 100;

    /**
     * @throws IllegalArgumentException if {@code timeoutMs} is not positive, or {@code secretParams} holds a null or
     *     blank name or more than {@value #MAX_SECRET_PARAMS} names
     */
    public CallOptions {
        if (timeoutMs != null && timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        secretParams = secretParams == null ? List.of() : List.copyOf(secretParams);
        if (secretParams.size() > MAX_SECRET_PARAMS) {
            throw new IllegalArgumentException("at most " + MAX_SECRET_PARAMS + " secret params, found "
                    + secretParams.size());
        }
        for (String name : secretParams) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("a secret param name must not be blank");
            }
        }
        traceparent = traceparent == null || traceparent.isBlank() ? null : traceparent;
    }

    /**
     * Options without a trace parent (the shape of amendment A34).
     *
     * @throws IllegalArgumentException as the canonical constructor
     */
    public CallOptions(String correlationId, Long timeoutMs, boolean confirm, List<String> secretParams) {
        this(correlationId, timeoutMs, confirm, secretParams, null);
    }

    /**
     * Options without secret params (the Phase 1 shape).
     *
     * @throws IllegalArgumentException if {@code timeoutMs} is not positive
     */
    public CallOptions(String correlationId, Long timeoutMs, boolean confirm) {
        this(correlationId, timeoutMs, confirm, List.of(), null);
    }

    private static final CallOptions DEFAULTS = new CallOptions(null, null, false);

    /** No correlation id, the primitive's own timeout, no confirmation, no secret params. */
    public static CallOptions defaults() {
        return DEFAULTS;
    }

    /** A copy with the given correlation id. */
    public CallOptions withCorrelationId(String id) {
        return new CallOptions(id, timeoutMs, confirm, secretParams, traceparent);
    }

    /** A copy with the given timeout override (null for the primitive's default). */
    public CallOptions withTimeoutMs(Long ms) {
        return new CallOptions(correlationId, ms, confirm, secretParams, traceparent);
    }

    /** A copy with the given confirmation flag. */
    public CallOptions withConfirm(boolean on) {
        return new CallOptions(correlationId, timeoutMs, on, secretParams, traceparent);
    }

    /**
     * A copy that announces these top-level params as secrets ({@code _meta.secretParams}, amendment A34); null or
     * empty announces none.
     *
     * @throws IllegalArgumentException as the canonical constructor
     */
    public CallOptions withSecretParams(List<String> names) {
        return new CallOptions(correlationId, timeoutMs, confirm, names, traceparent);
    }

    /**
     * A copy that sends this W3C trace parent as {@code _meta.traceparent} (phase6-contracts.md 10.2); null or blank
     * sends none.
     */
    public CallOptions withTraceparent(String parent) {
        return new CallOptions(correlationId, timeoutMs, confirm, secretParams, parent);
    }
}
