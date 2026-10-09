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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.spi.hook.HookEngine;
import ai.skillroy.testjava.protocol.CapabilityState;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The optional agent module {@code hooks} (phase6-contracts.md sections 2.1, 3 and 4): the hook engine on the
 * relocated Byte Buddy, shipped as {@code optional/skillroy-agent-hooks.jar} and loaded only when the agent option
 * {@code optionalModules} names it (D114), so the default path loads no third-party class.
 *
 * <p><b>Install</b> registers the six {@code hook.*} primitives, the {@link HookEngine} SPI and the capability
 * {@code hook.engine}: set UNAVAILABLE at once without an {@code Instrumentation}, otherwise declared with the probe of
 * section 3.2, so that it reads "not probed yet" until the target is ready. It appends nothing, registers no
 * transformer and starts no thread: the first {@code hook.install} does the rest. A {@code hooks.*} option out of its
 * range fails the install (a FAILED record naming the key). <b>{@code onTargetReady}</b> does nothing: the engine
 * installs no hook of its own. <b>Uninstall</b> disarms the handler and restores the touched classes under
 * {@code hooks.detachBudgetMs} (section 3.5); it never throws.
 *
 * <p>The core calls the lifecycle methods one at a time.
 */
public final class HooksModule implements AgentModule {

    /** Module id. */
    public static final String ID = "hooks";
    /** The engine's capability. */
    public static final String ENGINE = "hook.engine";
    /** The source of every event the module publishes. */
    static final String SOURCE = "hooks";

    private volatile HookEngineImpl engine;

    /** {@value #ID}. */
    @Override
    public String id() {
        return ID;
    }

    /** The module's Maven version, from the filtered {@code hooks-module.properties}. */
    @Override
    public String version() {
        return Version.VALUE;
    }

    @Override
    public void install(AgentContext ctx) {
        HookOptions options = HookOptions.read(ctx);
        final HookEngineImpl e = new HookEngineImpl(ctx, options);
        for (Primitive p : HookPrimitives.all(e)) {
            ctx.primitives().register(p);
        }
        ctx.spi().register(HookEngine.class, e);
        if (ctx.instrumentation() == null) {
            ctx.capabilities().set(ENGINE, CapabilityState.UNAVAILABLE, "no Instrumentation in " + ctx.loadMode()
                    + " mode", null);
        } else {
            ctx.capabilities().declare(ENGINE, new Probe() {
                @Override
                public ProbeResult run(AgentContext context) {
                    return e.engineProbe();
                }
            });
        }
        this.engine = e;
        ctx.log().info("hooks: installed " + HookPrimitives.NAMES.size() + " primitives and the HookEngine SPI; "
                + "spy jar " + options.spyJar + "; nothing appended, no transformer registered");
    }

    /** Uninstalls: the detach of section 3.5 under {@code hooks.detachBudgetMs}. Never throws. */
    @Override
    public void uninstall() {
        HookEngineImpl e = engine;
        engine = null;
        if (e != null) {
            try {
                e.detach();
            } catch (Throwable t) {
                // Never into the core: the detach logs what it could not restore.
            }
        }
    }

    /** The engine, for the tests. */
    HookEngineImpl engine() {
        return engine;
    }

    private static final class Version {
        static final String VALUE = load();

        private static String load() {
            InputStream in = HooksModule.class.getResourceAsStream("hooks-module.properties");
            if (in == null) {
                return "unknown";
            }
            try {
                Properties p = new Properties();
                p.load(in);
                return p.getProperty("version", "unknown");
            } catch (IOException e) {
                return "unknown";
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // read-only resource
                }
            }
        }
    }
}
