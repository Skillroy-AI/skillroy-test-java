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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extra locator strategies beyond the built-in ones. Called on the EDT. Lets a domain module offer locators in the
 * application's own vocabulary (for example {@code {action:"DUE"}}).
 *
 * <p>Called on the EDT; must be fast and must not change the component tree.
 */
public interface LocatorResolver {

    /** Locator keys this resolver handles, e.g. "action". */
    Set<String> strategies();

    /** Components under {@code root} matching {@code value} for the strategy; empty if none, never null. */
    List<java.awt.Component> resolve(String strategy, String value, java.awt.Component root);

    /**
     * The strategy values that name this component in the resolver's own vocabulary (for example
     * {@code {action: "DUE"}}), so that a hit-test can propose a domain locator first (design 3.7; phase7-contracts.md
     * section 3.7, A64). Each entry is checked by the agent to match exactly this component before it is proposed.
     * Empty by default, never null.
     */
    default Map<String, String> describe(java.awt.Component component) {
        return Collections.emptyMap();
    }
}
