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

package ai.skillroy.testjava.agent.web;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What the browser index needs from the Swing tree (phase4-contracts.md section 4.1, step 2): the components that
 * can show a browser, and whether given components are showing. The production walk is {@link SwingSeeds}; tests
 * replace it with a fake walk. Typed with {@code Object} so that the index never names an AWT class (the module loads
 * none until AWT runs).
 *
 * <p>Implementations are thread-safe, called on agent workers, bounded by the given budget.
 */
interface SeedWalk {

    /**
     * Every showing or displayable component of every displayable window (at most {@link SwingSeeds#MAX_COMPONENTS}),
     * as seeds for the adapters; empty when no event-dispatch thread runs.
     *
     * @throws Exception when the bounded EDT read fails or times out (the rebuild then goes on without Swing seeds)
     */
    List<Object> components(long budgetMs) throws Exception;

    /**
     * Whether each of the given objects is a component that is showing ({@code isShowing()} read on the EDT); objects
     * that are not components, and every object when no event-dispatch thread runs, are not showing. The map is keyed
     * by identity.
     *
     * @throws Exception when the bounded EDT read fails or times out
     */
    Map<Object, Boolean> showing(Collection<?> components, long budgetMs) throws Exception;
}
