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
 * Components the agent must never deliver input to: a key whose action ends the JVM, a control whose effect cannot be
 * undone in a test (Phase 8, phase8-contracts.md 3.2, A67). Asked on the EDT inside a primitive's bounded read and
 * again inside each posted input task, for the component and each of its ancestors up to and including its window
 * (the items of a popup menu, a combo's list or a context menu with the chain of the component that opened the popup,
 * whether it is drawn inside the window or as a window of its own); must be fast, lock-free, must not block and must
 * not change the component tree. A veto that throws refuses (the safe side).
 *
 * <p>The agent asks on every input route that resolves a target, after its argument checks (not showing, collapsed,
 * disabled) and before the modal check; no argument skips a refusal, {@code force} included. The first refusal
 * nearest the component wins, so one answer for a panel or a window vetoes everything inside it. A refusal is
 * {@code INPUT_REJECTED} with {@code data.reason: "vetoed"}, the text in {@code data.veto} and the refusing
 * component in {@code data.vetoed}.
 *
 * <p>The route strings equal the rung's capability id where one exists. A veto that discriminates routes <b>must
 * answer {@link #ANY} with a refusal whenever it would refuse at least one route</b>: the readers (snapshot, find,
 * hit-test, capture marks, dialogs) ask with {@link #ANY} to flag {@code vetoed: true}, so that a recorder and a model
 * are never offered the component; a veto that answers {@link #ANY} with null while refusing a route is still
 * enforced, but invisible until it acts.
 *
 * <p>Not covered: Swing's key bindings, mnemonics and menu accelerators can route a key delivered to one component to
 * an action another component owns; only the delivery target's chain and ENTER's default buttons (every root pane's up
 * to the window) are asked, so veto the window, or the bound control; a menu item's own veto does not cover the
 * keyboard of an open menu, so veto the window there. A veto on a window or panel does not cover {@code web.*} input
 * into a browser inside it (gap 126).
 */
public interface InputVeto {

    /** {@code swing.click} rungs ACTION and AUTO; COMPONENT's fall-back to ACTION. */
    String CLICK_ACTION = "swing.click.action";
    /**
     * {@code swing.click} rung COMPONENT: before the window activation and the focus request, and again before the
     * click.
     */
    String CLICK_COMPONENT = "swing.click.component";
    /** {@code swing.click} rung DISPATCH. */
    String CLICK_DISPATCH = "swing.click.dispatch";
    /** {@code swing.type} mode SET. */
    String TYPE_SET = "swing.type.set";
    /** {@code swing.type} mode DISPATCH. */
    String TYPE_DISPATCH = "swing.type.dispatch";
    /** {@code swing.key}. */
    String KEY = "swing.key";
    /** {@code swing.pointer} with {@code via: ROUTED}, before the sequence and per event. */
    String POINTER_ROUTED = "swing.pointer.routed";
    /** {@code swing.pointer} with {@code via: ROBOT}, before anything moves. */
    String POINTER_ROBOT = "swing.pointer.robot";
    /** {@code swing.dismiss {button}}. */
    String DISMISS_BUTTON = "swing.dismiss.button";
    /** {@code swing.dismiss {close}}: the dialog window itself. */
    String DISMISS_CLOSE = "swing.dismiss.close";
    /**
     * The readers' question (snapshot, find, hit-test, capture marks, dialogs): would input on any route be refused?
     */
    String ANY = "*";

    /**
     * Whether the agent may deliver input to the component on the route.
     *
     * @param component the component the input aims at, or one of its ancestors up to and including its window
     * @param route one of the route constants, or {@link #ANY}
     * @return null to allow, else one line saying why (the agent cuts it at 200 characters)
     */
    String refuse(java.awt.Component component, String route);
}
