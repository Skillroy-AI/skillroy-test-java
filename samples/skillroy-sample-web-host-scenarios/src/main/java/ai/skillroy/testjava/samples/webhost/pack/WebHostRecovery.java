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

package ai.skillroy.testjava.samples.webhost.pack;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.AgentRpcException;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.ErrorCodes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pack's recovery after a scenario that did not pass (phase4-contracts.md 9.3): dispatches the page event
 * {@code webhost.reset} to the front page, whose script then empties the form and the status line, and lets the UI
 * settle. The front page is selected by its tag ({@code front}, from the sample's agent module), else by the marker in
 * its URL. Idempotent and bounded (at most about {@value #DISPATCH_MS} ms for the event and {@value #SETTLE_MS} ms for
 * the settle); a failure propagates to the executor, which logs it and keeps the verdict.
 */
final class WebHostRecovery {

    static final long DISPATCH_MS = 10_000L;
    static final long SETTLE_MS = 5_000L;

    private WebHostRecovery() {
    }

    static void recover(AgentConnection agent) throws Exception {
        try {
            dispatchReset(agent, Map.of("tag", "front"));
        } catch (AgentRpcException e) {
            if (e.error().code() != ErrorCodes.NOT_FOUND) {
                throw e;
            }
            dispatchReset(agent, Map.of("url", "webhost-front"));
        }
        Map<String, Object> settle = new LinkedHashMap<>();
        settle.put("timeoutMs", SETTLE_MS);
        agent.call("swing.settle", settle, CallOptions.defaults().withTimeoutMs(SETTLE_MS + 2000L));
    }

    private static void dispatchReset(AgentConnection agent, Map<String, Object> by) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("by", by);
        params.put("name", "webhost.reset");
        agent.call("web.dispatchEvent", params, CallOptions.defaults().withTimeoutMs(DISPATCH_MS));
    }
}
