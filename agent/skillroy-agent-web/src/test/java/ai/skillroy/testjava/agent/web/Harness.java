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
import ai.skillroy.testjava.agent.web.fake.FakeScript;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The web module installed on a {@link FakeAgentContext} with a {@link FakeWalk} (or the production walk without an
 * event-dispatch thread, {@link #withoutEventDispatchThread}) and {@link ScriptedAdapter}s instead of the Swing tree and
 * the built-in adapters; {@link #ready()} runs {@code onTargetReady} and the core's probes. The
 * re-probe's pace (A38) runs on a {@link FakePace}, so that no test waits a real second between two re-probes.
 */
final class Harness implements AutoCloseable {

    final FakeAgentContext ctx = new FakeAgentContext();
    final FakeWalk walk = new FakeWalk();
    final FakePace pace = new FakePace();
    final List<WebEngineAdapter> adapters = new ArrayList<WebEngineAdapter>();
    final WebModule module;

    Harness(WebEngineAdapter... builtIns) {
        this(false, builtIns);
    }

    /**
     * A harness whose module walks the Swing tree with the production {@link SwingSeeds}, told that no event-dispatch
     * thread runs (Phase 8 security fix S167): every read it would make on the EDT answers as it does then.
     */
    static Harness withoutEventDispatchThread(WebEngineAdapter... builtIns) {
        return new Harness(true, builtIns);
    }

    private Harness(final boolean noEventDispatchThread, WebEngineAdapter... builtIns) {
        walk.spi = ctx.spi();
        adapters.addAll(Arrays.asList(builtIns));
        module = new WebModule(new WebModule.SeedWalkFactory() {
            @Override
            public SeedWalk create(AgentContext context) {
                if (noEventDispatchThread) {
                    return new SwingSeeds(context, new java.util.function.BooleanSupplier() {
                        @Override
                        public boolean getAsBoolean() {
                            return false;
                        }
                    });
                }
                return walk;
            }
        }, new WebModule.AdapterFactory() {
            @Override
            public List<WebEngineAdapter> create(AgentContext context) {
                return new ArrayList<WebEngineAdapter>(adapters);
            }
        });
        module.reprobePace(pace);
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

    /**
     * What an adapter hands for the guard of {@code WebLib.boundedCallExpression} (P2-L4-F2) when the library's value
     * is {@code value}: the JSON text of the {@code stringify} text, a string.
     */
    static String guarded(Object value) {
        return Json.write(FakeScript.stringify(value));
    }

    /** What an adapter hands for a text of the guard's own (a {@code WebLib.GUARD_PREFIX} text). */
    static String guardedText(String text) {
        return Json.write(text);
    }

    @Override
    public void close() {
        module.uninstall();
        ctx.shutdown();
    }
}
