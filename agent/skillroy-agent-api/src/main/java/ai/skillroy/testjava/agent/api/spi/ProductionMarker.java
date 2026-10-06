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
 * Non-null = "this looks like a production system because …"; the agent then refuses to serve and shuts down.
 * A safety net against a test agent being enabled on a live system.
 *
 * <p>Called once on an agent worker thread after the target is ready; must not block.
 */
public interface ProductionMarker {

    /** Why this looks like production, or null if it does not. */
    String productionReason();
}
