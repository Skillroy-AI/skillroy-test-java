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

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.spi.web.WebEngineAdapter;
import ai.skillroy.testjava.protocol.Capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The web module installed on a {@link FakeAgentContext} with a {@link FakeWalk} and {@link ScriptedAdapter}s instead
 * of the Swing tree and the built-in adapters; {@link #ready()} runs {@code onTargetReady} and the core's probes.
 */
final class Harness implements AutoCloseable {

    final FakeAgentContext ctx = new FakeAgentContext();
    final FakeWalk walk = new FakeWalk();
    final List<WebEngineAdapter> adapters = new ArrayList<WebEngineAdapter>();
    final WebModule module;

    Harness(WebEngineAdapter... builtIns) {
        adapters.addAll(Arrays.asList(builtIns));
        module = new WebModule(new WebModule.SeedWalkFactory() {
            @Override
            public SeedWalk create(AgentContext context) {
                return walk;
            }
        }, new WebModule.AdapterFactory() {
            @Override
            public List<WebEngineAdapter> create(AgentContext context) {
                return new ArrayList<WebEngineAdapter>(adapters);
            }
        });
    }

    /** Installs the module. */
    Harness install() throws Exception {
        module.install(ctx);
        return this;
    }

    /** Runs onTargetReady and the core's probes. */
    Harness ready() {
        module.onTargetReady(ctx);
        ctx.probeAll();
        return this;
    }

    /** {@code agent.capabilities {reprobe: true}}. */
    void reprobe() {
        ctx.probeAll();
    }

    Capability cap(String id) {
        return ctx.capabilities.get(id);
    }

    static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Override
    public void close() {
        module.uninstall();
        ctx.shutdown();
    }
}
