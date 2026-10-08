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

package ai.skillroy.testjava.agent.api.spi.web;

import java.util.Objects;

/**
 * One request for {@link WebBrowser#evaluate}: the expression, the frame it runs in, whether a promise it yields is
 * awaited, and how long the adapter waits for the answer. Built with {@link #of(String)}, for example
 * {@code WebEval.of("document.title").frameKey(key).timeoutMs(5000).build()}.
 *
 * <p>Immutable; safe to share between threads. Equal when every field is equal.
 */
public final class WebEval {

    private static final long DEFAULT_TIMEOUT_MS = 10000L;

    private final String expression;
    private final String frameKey;
    private final boolean awaitPromise;
    private final long timeoutMs;

    private WebEval(Builder builder) {
        this.expression = builder.expression;
        this.frameKey = builder.frameKey;
        this.awaitPromise = builder.awaitPromise;
        this.timeoutMs = builder.timeoutMs;
    }

    /**
     * Starts a request; the defaults are the main frame, no promise awaited and a timeout of 10000 ms.
     *
     * @param expression the JavaScript expression, kept as given
     * @throws NullPointerException if expression is null
     * @throws IllegalArgumentException if expression is empty or only whitespace
     */
    public static Builder of(String expression) {
        return new Builder(expression);
    }

    /** The JavaScript expression; never null or blank. */
    public String expression() {
        return expression;
    }

    /**
     * The {@link WebFrame#key()} of the frame to evaluate in, or null for the main frame. A key that no longer names a
     * frame fails the evaluation with {@link WebFrameGoneException}.
     */
    public String frameKey() {
        return frameKey;
    }

    /** True: a promise the expression yields is awaited and its settled value is the result. Default false. */
    public boolean awaitPromise() {
        return awaitPromise;
    }

    /** The adapter abandons the call after this many milliseconds (default 10000); always positive. */
    public long timeoutMs() {
        return timeoutMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebEval)) {
            return false;
        }
        WebEval other = (WebEval) o;
        return expression.equals(other.expression) && Objects.equals(frameKey, other.frameKey)
                && awaitPromise == other.awaitPromise && timeoutMs == other.timeoutMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(expression, frameKey, Boolean.valueOf(awaitPromise), Long.valueOf(timeoutMs));
    }

    /**
     * Shows the expression's length, never its text: the DOM primitives embed page input in the expression (the text
     * of a fill, which may be a secret).
     */
    @Override
    public String toString() {
        return "WebEval{expression=<" + expression.length() + " chars>, frameKey=" + frameKey + ", awaitPromise="
                + awaitPromise + ", timeoutMs=" + timeoutMs + "}";
    }

    /** Collects the parts of a {@link WebEval}. Not thread-safe; {@link #build()} may be called more than once. */
    public static final class Builder {

        private final String expression;
        private String frameKey;
        private boolean awaitPromise;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;

        private Builder(String expression) {
            Objects.requireNonNull(expression, "expression must not be null");
            if (expression.trim().isEmpty()) {
                throw new IllegalArgumentException("expression must not be blank");
            }
            this.expression = expression;
        }

        /** The {@link WebFrame#key()} of the frame to evaluate in; null (the default) means the main frame. */
        public Builder frameKey(String frameKey) {
            this.frameKey = frameKey;
            return this;
        }

        /** Default false. */
        public Builder awaitPromise(boolean awaitPromise) {
            this.awaitPromise = awaitPromise;
            return this;
        }

        /**
         * Default 10000.
         *
         * @throws IllegalArgumentException if not positive
         */
        public Builder timeoutMs(long timeoutMs) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
            }
            this.timeoutMs = timeoutMs;
            return this;
        }

        /** An immutable request; later builder changes do not affect it. */
        public WebEval build() {
            return new WebEval(this);
        }
    }
}
