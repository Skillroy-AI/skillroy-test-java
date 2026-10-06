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

import ai.skillroy.testjava.protocol.Event;

/**
 * Receiver of a {@link EventBus#subscribe} subscription (the SSE stream, for instance).
 *
 * <p>Called on the publishing thread, which may be the EDT or an application thread: must not block.
 */
public interface EventSink {

    /** Receives one event; must not block and should not throw (exceptions are logged and dropped). */
    void onEvent(Event event);
}
