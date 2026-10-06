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

/**
 * Checks whether one capability works in this target. Declared with {@link CapabilityRegistry#declare}.
 *
 * <p>Called on an agent worker thread, after the target is ready and again on reprobe; never concurrently for the
 * same capability. Must be bounded in time; use the {@link ThreadBroker} to touch the EDT or application threads.
 */
public interface Probe {

    /** The probed state; an exception = UNAVAILABLE(message). */
    ProbeResult run(AgentContext ctx) throws Exception;
}
