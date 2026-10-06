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

package ai.skillroy.testjava.scenario.resolve;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.data.DataChannel;

/**
 * What a {@link Verifier} may use. Implemented by the engine; valid only during the {@code verify} call it was passed
 * to. The agent is the same read-only view a resolver gets (see {@link ResolveContext#agent()}).
 */
public interface VerifyContext {

    /** A read-only view of the agent; timeouts are clamped to {@link #remainingMs()}. */
    AgentConnection agent();

    /** Guarded read-only SQL, routed by source id. */
    DataChannel data();

    /** The environment id of the bind. */
    String envId();

    /** What is left of the bind budget, in milliseconds. */
    long remainingMs();
}
