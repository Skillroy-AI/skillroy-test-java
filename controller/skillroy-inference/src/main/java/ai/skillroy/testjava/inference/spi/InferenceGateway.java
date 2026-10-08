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

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The one seam to a model provider (phase5-contracts.md 3, B7): a structured completion with typed content parts, an
 * optional batch submit and collect, an optional token count, a usage report per call. Instances are thread-safe;
 * every call is synchronous, runs on the caller's thread and is bounded by the gateway's per-call deadline
 * ({@code inference.yaml} {@code timeouts.callMs}, 2.4); an interrupt during a batch method or a token count is
 * reported as {@link InferenceException.Kind#TIMEOUT} with the thread's interrupt flag set again. No gateway starts a thread of its own except its HTTP
 * client's pool, which {@link #close()} closes.
 */
public interface InferenceGateway extends AutoCloseable {

    /** {@code claude}, {@code recorded}, {@code scripted}, {@code none}. */
    String provider();

    /** What this gateway can do beyond {@link #complete}. */
    Set<GatewayCapability> capabilities();

    /**
     * One completion within the gateway's per-call deadline.
     *
     * @return the answer, never null; a refusal is an answer
     * @throws InferenceException with a {@link InferenceException.Kind}
     * @throws InterruptedException if the calling thread is interrupted
     */
    ModelResponse complete(ModelRequest request) throws InferenceException, InterruptedException;

    /**
     * One completion within {@code timeout} or the gateway's own deadline, whichever is shorter: what a resolver uses
     * so that a model call never outlives the bind budget it runs in (2.4). The default ignores the timeout; a gateway
     * that talks to a provider honours it. (An addition to the contract's 3.2: the SPI had no way to clamp a call.)
     *
     * @param timeout the most the call may take; null means the gateway's own deadline
     * @throws InferenceException with {@link InferenceException.Kind#TIMEOUT} when the time ran out
     * @throws InterruptedException if the calling thread is interrupted
     */
    default ModelResponse complete(ModelRequest request, Duration timeout)
            throws InferenceException, InterruptedException {
        return complete(request);
    }

    /**
     * {@link GatewayCapability#BATCH}: submits many requests for asynchronous, cheaper processing.
     *
     * @throws UnsupportedOperationException without the capability
     */
    default BatchHandle submitBatch(List<ModelRequest> requests) throws InferenceException {
        throw new UnsupportedOperationException("BATCH");
    }

    /** {@link GatewayCapability#BATCH}: where the batch stands. */
    default BatchStatus batchStatus(BatchHandle handle) throws InferenceException {
        throw new UnsupportedOperationException("BATCH");
    }

    /** {@link GatewayCapability#BATCH}: the results of an ended batch, one per request, in request order. */
    default List<BatchResult> collectBatch(BatchHandle handle) throws InferenceException {
        throw new UnsupportedOperationException("BATCH");
    }

    /** {@link GatewayCapability#BATCH}: asks the provider to cancel the batch. */
    default void cancelBatch(BatchHandle handle) throws InferenceException {
        throw new UnsupportedOperationException("BATCH");
    }

    /**
     * {@link GatewayCapability#TOKEN_COUNT}: the input tokens of a request as the provider would count them; -1 when
     * unknown.
     */
    default long countTokens(ModelRequest request) throws InferenceException {
        return -1;
    }

    /** Closes the gateway and its HTTP client; idempotent; never throws. */
    @Override
    void close();
}
