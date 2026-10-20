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

package ai.skillroy.testjava.samples.lending.agent;

import ai.skillroy.testjava.agent.api.spi.InputVeto;

import java.awt.Component;

/**
 * The desk's input veto (Phase 8, phase8-contracts.md 3.2, 12.2): the panel {@value #CLOSE_MENU} of the "Desk tools"
 * window holds the controls that would close the desk (the application would end), so the agent must never deliver
 * input to it, on any route, and readers flag it. The veto answers by the component's name only, for every route and
 * {@code ANY}: it touches no application class. It refuses the panel itself; the agent's ancestor rule covers the
 * controls inside it.
 *
 * <p>Called on the event dispatch thread; fast, stateless, changes nothing.
 */
final class LendingInputVeto implements InputVeto {

    /** The name of the "Desk tools" window's close panel. */
    static final String CLOSE_MENU = "closeMenu";
    /** The refusal's text. */
    static final String TEXT = "closes the desk (the application would end)";

    @Override
    public String refuse(Component component, String route) {
        return component != null && CLOSE_MENU.equals(component.getName()) ? TEXT : null;
    }
}
