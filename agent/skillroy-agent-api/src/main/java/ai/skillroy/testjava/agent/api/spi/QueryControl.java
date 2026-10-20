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

package ai.skillroy.testjava.agent.api.spi;

/**
 * What the agent hands a provider-executed read ({@link DataSourceProvider#query(String, java.util.List, int, long,
 * QueryControl)}): a scoped cancellation handle (phase8-contracts.md 4.3, D529). {@code data.query} creates one per
 * call, sets it cancelled when the caller's time runs out or the agent closes, and then runs the actions registered
 * here, bounded, on an agent thread of their own; it reports what happened in its {@code TIMEOUT} answer
 * ({@code cancelled}, {@code cancelPending}, {@code stillRunning}).
 *
 * <p>The agent implements this interface; a provider only uses the handle it is given, for the one read it is given
 * for. Thread-safe.
 */
public interface QueryControl {

    /** The most actions one read may register. */
    int MAX_ACTIONS = 4;

    /** How long one action may run before the agent abandons it (on the action's own thread) and logs it. */
    long ACTION_TIMEOUT_MS = 2000L;

    /** Time left of the call for the read, in ms (the module's reserve of 250 ms already taken); never negative. */
    long remainingMs();

    /** True once the agent has given up on this read: its caller's time ran out, or the agent is closing. */
    boolean cancelled();

    /**
     * Registers an action the agent runs once when it gives up (typically statement::cancel, or the application's own
     * abort): on an agent thread of its own (a nested worker task), never the dispatching worker, an application
     * thread or the EDT, bounded (2 s, then abandoned there and logged by class name). Registered after cancellation:
     * run at once on the registering thread. At most 4 per read (IllegalStateException beyond). Closing the handle
     * unregisters the action.
     *
     * @throws IllegalStateException for a fifth action
     */
    AutoCloseable onCancel(Runnable action);
}
