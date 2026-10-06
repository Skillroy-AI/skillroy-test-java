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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.protocol.Event;

import java.util.ArrayList;
import java.util.List;

/**
 * The call context of one dispatched JSON-RPC request: its correlation id and traceparent, and the events published
 * while it runs. Bound thread-locally around the primitive on whichever thread executes it (and carried by the
 * {@link CoreThreadBroker} to threads the primitive hands work to), so that {@code EventBus.publish} can stamp the
 * correlation id and the result can carry the call's own events, and so that the agent log can hide the call's secret
 * parameter values in the lines written about it (contract amendment A34, {@link SecretParams}). The events keep their
 * payloads as published.
 *
 * <p>The event list is thread-safe; it stops accepting events once the call has completed or timed out.
 */
final class CallScope {

    /** At most this many events are returned with a result. */
    static final int MAX_EVENTS = 50;

    private static final ThreadLocal<CallScope> CURRENT = new ThreadLocal<CallScope>();

    final String correlationId;
    final String traceparent;
    /** The call's secret parameter values (amendment A34); {@link SecretParams#NONE} when it declared none. */
    final SecretParams secrets;
    private final List<Event> events = new ArrayList<Event>();
    private boolean open = true;

    CallScope(String correlationId, String traceparent) {
        this(correlationId, traceparent, SecretParams.NONE);
    }

    CallScope(String correlationId, String traceparent, SecretParams secrets) {
        this.correlationId = correlationId;
        this.traceparent = traceparent;
        this.secrets = secrets == null ? SecretParams.NONE : secrets;
    }

    /** The secrets of the call bound to the calling thread; {@link SecretParams#NONE} outside a call. */
    static SecretParams currentSecrets() {
        CallScope scope = CURRENT.get();
        return scope == null ? SecretParams.NONE : scope.secrets;
    }

    /** The scope bound to the calling thread, or null. */
    static CallScope current() {
        return CURRENT.get();
    }

    /** Binds a scope (null unbinds) and returns the previous one, to be passed to {@link #restore}. */
    static CallScope bind(CallScope scope) {
        CallScope previous = CURRENT.get();
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
        return previous;
    }

    /** Restores what {@link #bind} returned. */
    static void restore(CallScope previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** Records an event published under this scope, while the call is still open and below the cap. */
    synchronized void record(Event event) {
        if (open && events.size() < MAX_EVENTS) {
            events.add(event);
        }
    }

    /** Ends recording and returns what was recorded. */
    synchronized List<Event> close() {
        open = false;
        return new ArrayList<Event>(events);
    }
}
