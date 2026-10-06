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
 * The ACTION rung for custom widgets: trigger the widget's semantic action without focus or mouse events. EDT.
 * Needed because custom widgets often ignore {@code doClick()} when unfocused.
 *
 * <p>Both methods are called on the EDT and must not block.
 */
public interface ActionFirer {

    /** True if this firer knows how to trigger the component's action. */
    boolean canFire(java.awt.Component component);

    /**
     * Triggers the action. The click is posted to the EDT and this runs there afterwards, so the click result's
     * {@code fired} means "posted", not "the action succeeded": an exception thrown here is logged by the agent, not
     * reported to the caller of the click. Observe the action's effect (an event, the application's state) instead.
     */
    void fire(java.awt.Component component) throws Exception;
}
