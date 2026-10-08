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

import java.util.Collections;
import java.util.List;

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

    /**
     * The names of the top-level parameters the caller marked as secret in {@code _meta.secretParams} (contract
     * amendment A34), in the order given, without duplicates; empty when it named none. The dispatcher already keeps
     * those values out of the call's error answer and of the log lines written on the call's threads; this read-only
     * accessor lets a module keep them out of what it publishes later on its own, outside the call (an embedded page's
     * console output that echoes a filled value, phase4-contracts.md section 4.3). It changes nothing the core does.
     * The default (an invocation made outside the dispatcher) is empty.
     */
    default List<String> secretParams() {
        return Collections.emptyList();
    }
}
