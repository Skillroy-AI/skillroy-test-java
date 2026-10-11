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

package ai.skillroy.testjava.samples.webhost.agent;

import ai.skillroy.testjava.agent.api.spi.CaptureMask;

import java.awt.Component;
import java.awt.Container;

/**
 * The web host's front card as a component that shows a secret (phase4-contracts.md 9.2; phase7-contracts.md 3.6,
 * 13.2 item 10, gap 95): the component the host names {@value #FRONT} whose parent is the panel it names
 * {@value #DECK}, that is the front browser's view; through the ancestor rule nothing else (the back card, the bar
 * and the window stay unmasked). Registered by {@link WebHostModule} only with the agent option
 * {@code webhost.mask=front}, so that the web host's end-to-end check can see a masked browser on every route
 * against real JCEF.
 *
 * <p>Called on the event dispatch thread for every component of a read; reads two names, holds no state and changes
 * nothing.
 */
final class FrontCardMask implements CaptureMask {

    /** The name the host gives the front browser's view (its card). */
    static final String FRONT = "front";
    /** The name the host gives the panel that holds the cards. */
    static final String DECK = "deck";

    @Override
    public boolean masks(Component component) {
        if (component == null || !FRONT.equals(component.getName())) {
            return false;
        }
        Container parent = component.getParent();
        return parent != null && DECK.equals(parent.getName());
    }
}
