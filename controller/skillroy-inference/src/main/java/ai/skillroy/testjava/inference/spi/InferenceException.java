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

package ai.skillroy.testjava.inference.spi;

import java.util.Objects;

/**
 * A gateway call failed (phase5-contracts.md 3.2, 4.1). The message is one line and never holds the model key, a
 * workspace id or a prompt; it may hold the provider's own error message.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class InferenceException extends Exception {

    private static final long serialVersionUID = 1L;

    /** What went wrong. */
    public enum Kind {
        /** The key was refused (401, 403), or the request needs a workspace id the settings do not give. */
        AUTH,
        /** HTTP 402: the account cannot pay; not retryable (BS13 F-9). */
        BILLING,
        /** HTTP 429; retryable. */
        RATE_LIMIT,
        /** HTTP 529 or {@code overloaded_error}; retryable. */
        OVERLOADED,
        /** HTTP 400, 404, 413, 422: the request is wrong. */
        INVALID_REQUEST,
        /** The provider declined in a way that is not an answer (a refusal answer is a response, not this). */
        REFUSAL,
        /** The connection failed; retryable. */
        TRANSPORT,
        /** The call's deadline passed. */
        TIMEOUT,
        /** The recorded gateway has no recording of the request's fingerprint (4.2). */
        NO_RECORDING,
        /** A budget meter refused the call (7.2). */
        BUDGET,
        /** The gateway cannot do this (gateway {@code none}, a capability it lacks, an image while images are off). */
        UNSUPPORTED,
        /** Anything else the provider answered (500, an unreadable response). */
        PROVIDER
    }

    private final Kind kind;
    private final String requestId;
    private final transient Usage usage;

    /** A failure without a request id. */
    public InferenceException(Kind kind, String message) {
        this(kind, message, null, null, null);
    }

    /** A failure with the provider's request id (may be null). */
    public InferenceException(Kind kind, String message, String requestId) {
        this(kind, message, requestId, null, null);
    }

    /**
     * @param kind what went wrong
     * @param message one line, no secret
     * @param requestId the provider's request id, or null
     * @param usage what the failed call was billed, when the provider said so; null when not knowable
     * @param cause the underlying failure, or null; its message must hold no secret either
     */
    public InferenceException(Kind kind, String message, String requestId, Usage usage, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.requestId = requestId;
        this.usage = usage;
    }

    /** What went wrong. */
    public Kind kind() {
        return kind;
    }

    /** The provider's request id, or null. */
    public String requestId() {
        return requestId;
    }

    /** True for {@link Kind#RATE_LIMIT}, {@link Kind#OVERLOADED} and {@link Kind#TRANSPORT}: worth one more try. */
    public boolean retryable() {
        return kind == Kind.RATE_LIMIT || kind == Kind.OVERLOADED || kind == Kind.TRANSPORT;
    }

    /** What the failed call was billed, or null when the provider did not say (a timeout is not knowable). */
    public Usage usage() {
        return usage;
    }
}
