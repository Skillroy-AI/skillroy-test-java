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

package ai.skillroy.testjava.agent.api;

import ai.skillroy.testjava.protocol.JsonView;

/**
 * One call of a primitive: its arguments plus the call metadata the dispatcher extracted from {@code params._meta}.
 * Gives primitives typed parameter access and their remaining time budget without seeing the JSON-RPC envelope.
 *
 * <p>Valid only during {@link Primitive#invoke}; may be passed to other threads that finish before invoke returns.
 */
public interface Invocation {

    /** Params without "_meta". */
    JsonView args();

    /** Correlation id minted by the controller for this call, or null. */
    String correlationId();

    /** W3C trace context of this call, or null. */
    String traceparent();

    /** The agent context. */
    AgentContext context();

    /** Time left before the dispatcher reports TIMEOUT, in milliseconds; never negative. */
    long remainingMs();
}
