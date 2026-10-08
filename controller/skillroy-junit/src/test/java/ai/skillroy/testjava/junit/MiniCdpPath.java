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

package ai.skillroy.testjava.junit;

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.controller.CallOptions;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.RpcResult;
import ai.skillroy.testjava.scenario.web.WebPath;
import ai.skillroy.testjava.scenario.web.WebPathException;
import ai.skillroy.testjava.scenario.web.WebRunContext;
import ai.skillroy.testjava.scenario.web.WebSettings;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A CDP path (id {@code cdp}) on the glue tests' class path, registered through the test-only
 * {@code META-INF/services/ai.skillroy.testjava.scenario.web.WebPath}, as a suite with {@code skillroy-cdp} on its
 * class path has it (phase4-contracts.md 6.1, D49). It serves {@code web.dom.click}; with {@link #refuse} set it
 * refuses every call as {@code UNSUPPORTED}, so that the agent serves it.
 */
public final class MiniCdpPath implements WebPath {

    /** The primitives the instances served, in order. */
    static final List<String> SERVED = new CopyOnWriteArrayList<>();

    /** True: every call is refused as {@code UNSUPPORTED}. */
    static volatile boolean refuse;

    /** The public no-argument constructor {@code ServiceLoader} needs. */
    public MiniCdpPath() {
    }

    @Override
    public String id() {
        return "cdp";
    }

    @Override
    public String open(AgentConnection agent, WebSettings settings, WebRunContext ctx) {
        return null;
    }

    @Override
    public boolean serves(String primitive) {
        return primitive.equals("web.dom.click");
    }

    @Override
    public RpcResult call(String primitive, Map<String, Object> args, CallOptions options) throws Exception {
        if (refuse) {
            throw WebPathException.unsupported("refused by the test");
        }
        SERVED.add(primitive);
        return new RpcResult(Map.of("matched", 1L, "fidelity", "DOM"), List.of(), 0, 0, 1, "controller");
    }

    @Override
    public List<Event> eventsSince(long seq, String topics) {
        return List.of();
    }

    @Override
    public void close() {
    }
}
