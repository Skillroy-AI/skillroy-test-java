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

import java.util.Collection;

/**
 * Technology-neutral: a domain module hands the agent seeds for browsers the Swing-tree scan cannot see (cached,
 * hidden, or without a view). It replaces the vendor-typed sketch of the design overview: a pack returns the objects
 * its application already holds and needs to know no vendor type. Registered through {@code AgentContext.spi()}.
 *
 * <p>Called on agent workers whenever the agent rebuilds its browser index; must be cheap and must not block. Never
 * create, initialise or configure a browser or an engine from this call: agent threads are daemon threads in the
 * agent's thread group, which anything started on them inherits. A call that throws is logged and skipped.
 */
public interface BrowserRegistryAdapter {

    /**
     * Seeds for every {@link WebEngineAdapter}: browsers, views, engines or clients, as their technology knows them,
     * each optionally tagged. Null or empty means none; null elements are skipped.
     */
    Collection<BrowserSeed> seeds();
}
