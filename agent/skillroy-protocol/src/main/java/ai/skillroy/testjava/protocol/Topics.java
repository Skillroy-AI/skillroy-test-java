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

package ai.skillroy.testjava.protocol;

/**
 * Generic event topics published by the agent core and the generic modules. Domain modules define their own topics
 * (for example {@code loan.posted}) in their own namespace; these names are shared so scenarios and packs never
 * spell them differently.
 *
 * <p>Constants only; usable from any thread.
 */
public final class Topics {

    /** Agent life cycle and generic UI/application topics. */
    public static final String AGENT_STARTED = "agent.started", AGENT_STOPPING = "agent.stopping",
            MODULE_INSTALLED = "module.installed", TARGET_READY = "target.ready",
            WINDOW_OPENED = "window.opened", WINDOW_CLOSED = "window.closed",
            INPUT_ACKED = "input.acked", INPUT_REQUESTED = "input.requested", INPUT_REJECTED = "input.rejected",
            PROMPT_WAITING = "prompt.waiting", STATE_CHANGED = "state.changed", TRACE_MARK = "trace.mark", GAP = "gap";

    /** The embedded web view topics of the web module (phase4-contracts.md section 4.3), source {@code web}. */
    public static final String WEB_LOAD = "web.load", WEB_READY = "web.ready", WEB_CONSOLE = "web.console",
            WEB_TAP = "web.tap", WEB_CLOSED = "web.closed", WEB_BROWSERS_CHANGED = "web.browsers.changed";

    private Topics() {
    }
}
