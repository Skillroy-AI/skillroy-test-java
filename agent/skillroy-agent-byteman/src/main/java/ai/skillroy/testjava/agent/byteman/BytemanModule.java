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

package ai.skillroy.testjava.agent.byteman;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.Primitive;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.Probe;
import ai.skillroy.testjava.agent.api.ProbeResult;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The optional agent module {@code byteman} (phase6-contracts.md 7, D132-D134): ad-hoc Byteman rules, in process,
 * with no listener. It lands in {@code <home>/optional/} and loads only when {@code optionalModules} names it.
 *
 * <p>{@link #install} registers the four primitives and the capability {@code rule.engine} (set UNAVAILABLE without an
 * {@code Instrumentation}, declared with the probe of 7.5 otherwise); it touches nothing of the host, appends nothing,
 * starts no thread. Byteman is loaded only under {@code rule.submit}, after the dispatcher's read-only and confirm
 * gates (a read never loads an agent into the host): the user's jar ({@code byteman.jar}, never bundled, no
 * environment fallback) and the project's {@code skillroy-agent-byteman-sys.jar} appended to the system class path,
 * {@code Main.premain("listener:false,manager:…RuleManager", inst)} called by reflection, the {@code Retransformer}
 * taken from the manager at once (spike BS14).
 *
 * <ul>
 *   <li>{@code rule.check {rule}}: read-only; the lint, the class deny by name (phase8-contracts.md 6.7), then
 *       Byteman's {@code RuleCheck} only when Byteman is loaded and the rule's class is the system loader's; never
 *       loads Byteman or a target class.</li>
 *   <li>{@code rule.submit {rule, checkOnly?}}: mutating and destructive (every submit needs {@code confirm}).</li>
 *   <li>{@code rule.unload {name}}: neither, never gated: it restores, and verifies the bytes.</li>
 *   <li>{@code rule.list {}}: read-only; the module's own rules only, and the class deny in force ({@code deny}:
 *       the option {@code hooks.deny}, the number of domain modules' {@code ClassDeny}s and, with the hooks module,
 *       its held catalogue list).</li>
 * </ul>
 *
 * <p>The agent option {@code hooks.deny} is read here too (phase8-contracts.md 6.5): an invalid pattern fails the
 * install naming the key. A rule on a denied class is refused before the count and before Byteman loads.
 */
public final class BytemanModule implements AgentModule {

    /** Module id. */
    public static final String ID = "byteman";
    /** The capability {@code rule.check}, {@code rule.submit} and {@code rule.list} name. */
    public static final String CAPABILITY = "rule.engine";
    /** The sys jar's place in the agent home. */
    static final String SYS_JAR = "optional" + File.separator + "skillroy-agent-byteman-sys.jar";

    private final File sysJarOverride;
    private final ClassLoader systemOverride;
    private AgentContext ctx;
    private Engine engine;
    private AutoCloseable declaration;
    private final List<String> registered = new ArrayList<String>();

    /** For {@code ServiceLoader}. */
    public BytemanModule() {
        this(null, null);
    }

    /** For tests: the sys jar and the loader Byteman lives in (null: the home's jar and the system class loader). */
    BytemanModule(File sysJar, ClassLoader system) {
        this.sysJarOverride = sysJar;
        this.systemOverride = system;
    }

    @Override
    public String id() {
        return ID;
    }

    /** The module's Maven version, from the filtered {@code byteman-module.properties}. */
    @Override
    public String version() {
        return Version.VALUE;
    }

    @Override
    public synchronized void install(AgentContext context) throws Exception {
        this.ctx = context;
        Settings settings = Settings.read(context);
        String home = context.options().get("home");
        File sysJar = sysJarOverride != null ? sysJarOverride
                : new File(home == null ? "." : home, SYS_JAR);
        Set<ClassLoader> agentLoaders = ClassBound.chainOf(BytemanModule.class.getClassLoader());
        engine = new Engine(context, settings, sysJar,
                systemOverride != null ? systemOverride : ClassLoader.getSystemClassLoader(), agentLoaders);
        for (Primitive p : primitives(engine)) {
            context.primitives().register(p);
            registered.add(p.spec().name());
        }
        if (context.instrumentation() == null) {
            context.capabilities().set(CAPABILITY, CapabilityState.UNAVAILABLE,
                    "no Instrumentation in " + context.loadMode() + " mode", null);
        } else {
            final Engine e = engine;
            declaration = context.capabilities().declare(CAPABILITY, new Probe() {
                @Override
                public ProbeResult run(AgentContext c) {
                    return e.probe();
                }
            });
        }
        context.log().info("byteman: installed " + registered.size() + " primitives; Byteman is loaded only by the"
                + " first confirmed rule.submit" + (settings.bytemanJar == null ? " (no byteman.jar configured)"
                : " from " + settings.bytemanJar));
    }

    @Override
    public synchronized void uninstall() {
        AgentContext c = ctx;
        if (c == null) {
            return;
        }
        for (String name : registered) {
            try {
                c.primitives().unregister(name);
            } catch (RuntimeException ignored) {
                // gone already
            }
        }
        registered.clear();
        if (declaration != null) {
            try {
                declaration.close();
            } catch (Exception ignored) {
                // withdrawn already
            }
            declaration = null;
        }
        if (engine != null) {
            try {
                engine.uninstall();
            } catch (Throwable t) {
                c.log().warn("byteman: uninstall", t);
            }
        }
        ctx = null;
    }

    /** The engine (tests). */
    Engine engine() {
        return engine;
    }

    // ---------------------------------------------------------------------------------------------- primitives

    static List<Primitive> primitives(final Engine engine) {
        List<Primitive> list = new ArrayList<Primitive>();
        list.add(Primitives.of(PrimitiveSpec.builder("rule.check")
                .description("Lints a Byteman rule, refuses a denied class (phase8-contracts.md 6.7) and, when Byteman"
                        + " is loaded and the rule's class is loaded by the system loader, type-checks it with"
                        + " Byteman's RuleCheck; never loads Byteman or a class.")
                .param("rule", "string", true, "the rule text: one RULE … ENDRULE")
                .thread(ThreadPolicy.WORKER).timeoutMs(30000).capability(CAPABILITY).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation invocation) {
                        return engine.check(text(invocation));
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("rule.submit")
                .description("Submits a Byteman rule (loads Byteman in process on the first call; no listener): the"
                        + " lint, the class deny and the class bound, the type check, then Byteman; every submit"
                        + " needs confirm.")
                .param("rule", "string", true, "the rule text: one RULE … ENDRULE")
                .param("checkOnly", "boolean", false, "rule.check under this primitive's gates")
                .thread(ThreadPolicy.WORKER).timeoutMs(30000).capability(CAPABILITY).mutating().destructive()
                .build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation invocation) throws Exception {
                        if (invocation.args().bool("checkOnly", false)) {
                            return engine.check(text(invocation));
                        }
                        return engine.submit(text(invocation));
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("rule.unload")
                .description("Removes a rule this module installed and verifies the restored classes byte for byte;"
                        + " a restore, never gated.")
                .param("name", "string", true, "the rule's name as written on its RULE line")
                .thread(ThreadPolicy.WORKER).timeoutMs(30000).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation invocation) throws Exception {
                        String name = invocation.args().str("name");
                        if (name == null || name.isEmpty()) {
                            throw invalid("name");
                        }
                        return engine.unload(name);
                    }
                }));
        list.add(Primitives.of(PrimitiveSpec.builder("rule.list")
                .description("The rules this module installed, with Byteman's status per class, and the class deny"
                        + " in force (deny: {option, modules, catalogue?}).")
                .thread(ThreadPolicy.WORKER).timeoutMs(10000).capability(CAPABILITY).build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation invocation) throws Exception {
                        return engine.describe();
                    }
                }));
        return Collections.unmodifiableList(list);
    }

    private static String text(Invocation invocation) {
        String rule = invocation.args().str("rule");
        if (rule == null || rule.isEmpty()) {
            throw invalid("rule");
        }
        return rule;
    }

    private static RpcException invalid(String param) {
        Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
        data.put("param", param);
        return new RpcException(ErrorCodes.INVALID_PARAMS, param + " is required", data);
    }

    private static final class Version {
        static final String VALUE = load();

        private Version() {
        }

        private static String load() {
            InputStream in = BytemanModule.class.getResourceAsStream("byteman-module.properties");
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
