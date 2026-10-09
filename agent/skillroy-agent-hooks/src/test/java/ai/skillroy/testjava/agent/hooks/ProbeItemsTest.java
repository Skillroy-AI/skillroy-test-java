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

import ai.skillroy.testjava.fixture.hooks.ChildFirstLoader;
import ai.skillroy.testjava.fixture.hooks.DepositRule;
import ai.skillroy.testjava.fixture.hooks.Refusals;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import ai.skillroy.testjava.protocol.hook.HookMode;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe of a spec, item by item (phase6-contracts.md section 3.3, section 13.1 item 3), through {@code hook.probe}.
 * The strict-delegation loader's refusal needs the append first and runs in a JVM of its own
 * ({@link StrictLoaderFreshJvmTest}).
 */
class ProbeItemsTest {

    private Harness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> probe(HookSpec spec) throws Exception {
        Map<String, Object> answer = harness.ctx.map("hook.probe", map("specs", Harness.json(spec)));
        return (Map<String, Object>) ((List<Object>) answer.get("hooks")).get(0);
    }

    private void assertRefused(HookSpec spec, String reason) throws Exception {
        Map<String, Object> p = probe(spec);
        assertEquals("UNAVAILABLE", p.get("state"), String.valueOf(p));
        assertTrue(String.valueOf(p.get("reason")).startsWith(reason), p + " should say " + reason);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aLoadedClassOfTheApplicationIsOkAndTheSpyIsUncheckedBeforeTheAppend() throws Exception {
        new DepositRule();
        harness = new Harness();
        Map<String, Object> answer = harness.ctx.map("hook.probe", map("specs", Harness.json(
                spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"))));
        Map<String, Object> p = (Map<String, Object>) ((List<Object>) answer.get("hooks")).get(0);
        assertEquals("OK", p.get("state"), String.valueOf(p));
        assertEquals("advice", p.get("strategy"));
        assertEquals(1L, ((Number) p.get("loaders")).longValue());
        assertEquals(Collections.singletonList("system"), p.get("loaderIds"));
        assertTrue(((Number) p.get("sizeBytes")).longValue() > 0);
        assertTrue(((Number) p.get("methods")).longValue() >= 7);
        Map<String, Object> spy = (Map<String, Object>) answer.get("spy");
        if (Boolean.FALSE.equals(spy.get("onBootPath"))) {
            assertEquals("UNCHECKED", p.get("spyVisibility"), "no Spy on the bootstrap path yet");
            assertEquals(Boolean.TRUE, spy.get("slotFree"));
        }
        assertEquals(Boolean.FALSE, spy.get("slotHeldByThisEngine"));
        List<Object> others = (List<Object>) answer.get("otherAgents");
        assertTrue(others.toString().contains("-test-agent.jar"), "the test agent is another agent: " + others);
        harness.install("toys", spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"));
        assertEquals("VISIBLE", probe(spec("deposit.waived", HookMode.OBSERVE, DepositRule.class, "waives"))
                .get("spyVisibility"), "decided once Spy is on the bootstrap path");
        harness.ctx.call("hook.remove", map());
    }

    @Test
    void aProtectedPackageByName() throws Exception {
        harness = new Harness();
        assertRefused(spec("own", HookMode.OBSERVE, Descriptors.class, "parameterCount"),
                "class in a protected package");
        assertRefused(Harness.spec("jdk", HookMode.OBSERVE, "java.lang.String", "length", "()I", null, null, null,
                false), "class in a protected package");
    }

    @Test
    void aClassOfALoaderOfTheAgentsOwnIsRefusedByLoader() throws Exception {
        harness = new Harness();
        AgentLikeLoader loader = new AgentLikeLoader(new URL[] {Harness.testClasses()},
                ProbeItemsTest.class.getClassLoader(), "ai.skillroy.testjava.fixture.hooks.");
        String returns = "ai.skillroy.testjava.fixture.hooks.Returns";
        Class<?> copy = loader.loadClass(returns);
        assertEquals(loader, copy.getClassLoader());
        assertRefused(Harness.spec("agent.loader", HookMode.OBSERVE, returns, "count", "()I", null, null, null, false),
                "class is the agent's own or the JDK's");
        loader.close();
    }

    @Test
    void anUnloadedClassIsNeverLoadedByTheProbe() throws Exception {
        harness = new Harness();
        String name = "ai.skillroy.testjava.fixture.hooks.Refusals$NeverLoaded";
        assertRefused(Harness.spec("never", HookMode.OBSERVE, name, "x", "()I", null, null, null, false),
                "class not loaded");
        for (Class<?> c : Harness.inst().getAllLoadedClasses()) {
            assertTrue(!c.getName().equals(name), "the probe loaded the class");
        }
    }

    @Test
    void anUnmodifiableClass() throws Exception {
        final Instrumentation real = Harness.inst();
        new DepositRule();
        FakeHookContext ctx = new FakeHookContext();
        ctx.instrumentation = (Instrumentation) Proxy.newProxyInstance(ProbeItemsTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("isModifiableClass".equals(method.getName()) && args[0] == DepositRule.class) {
                            return Boolean.FALSE;
                        }
                        return method.invoke(real, args);
                    }
                });
        harness = new Harness(ctx);
        assertRefused(spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                "class is not modifiable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aClassAboveEitherBound() throws Exception {
        new DepositRule();
        new Refusals.Wide();
        FakeHookContext ctx = new FakeHookContext();
        ctx.options.put(HookOptions.MAX_CLASS_BYTES, "1024");
        ctx.options.put(HookOptions.MAX_METHODS, "5");
        harness = new Harness(ctx);
        Map<String, Object> bytes = probe(spec("deposit.computed", HookMode.OBSERVE, DepositRule.class,
                "depositFor"));
        assertTrue(String.valueOf(bytes.get("reason")).matches("class too large: \\d+ bytes > 1024"),
                String.valueOf(bytes));
        assertTrue(((Number) bytes.get("sizeBytes")).longValue() > 1024);
        Map<String, Object> methods = probe(spec("wide", HookMode.OBSERVE, Refusals.Wide.class, "a"));
        assertEquals("class too large: 7 methods > 5", methods.get("reason"), String.valueOf(methods));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMissingDescriptorListsTheDeclaredOnes() throws Exception {
        new DepositRule();
        harness = new Harness();
        Map<String, Object> p = probe(Harness.spec("deposit.computed", HookMode.OBSERVE, DepositRule.class.getName(),
                "depositFor", "(I)Ljava/math/BigDecimal;", null, null, null, false));
        assertEquals("no method depositFor(I)Ljava/math/BigDecimal;", p.get("reason"));
        assertEquals(Collections.singletonList("(Ljava/lang/String;)Ljava/math/BigDecimal;"), p.get("declared"));
    }

    @Test
    void aNativeAndAnAbstractMethod() throws Exception {
        new Refusals.WithNative();
        Class<?> abstractType = Refusals.WithAbstract.class;
        harness = new Harness();
        assertRefused(spec("native", HookMode.OBSERVE, Refusals.WithNative.class, "peek"), "method is native");
        assertRefused(spec("abstract", HookMode.OBSERVE, abstractType, "later"), "method is abstract");
    }

    @Test
    void aMethodSignatureNamingAMissingType() throws Exception {
        harness = new Harness();
        String needs = "ai.skillroy.testjava.fixture.hooks.NeedsMissing";
        ChildFirstLoader hiding = ChildFirstLoader.hiding(new URL[] {Harness.testClasses()},
                ProbeItemsTest.class.getClassLoader(), "ai.skillroy.testjava.fixture.hooks.MissingType",
                "ai.skillroy.testjava.fixture.hooks.");
        Class<?> type = hiding.loadClass(needs);
        assertEquals(hiding, type.getClassLoader());
        assertRefused(Harness.spec("needs", HookMode.OBSERVE, needs, "plain", "()I",
                Integer.toHexString(System.identityHashCode(hiding)), null, null, false),
                "a method signature of the class names a missing type: ");
        hiding.close();
    }

    @Test
    void theNameRulesAreInvalidParamsNamingTheField() throws Exception {
        harness = new Harness();
        RpcException e = harness.ctx.error("hook.probe", map("specs", Harness.json(
                spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                spec("engine", HookMode.OBSERVE, DepositRule.class, "waives"))));
        assertEquals(ErrorCodes.INVALID_PARAMS, e.error().code());
        assertEquals("specs[1].id", e.error().data().get("param"), String.valueOf(e.error().data()));
        RpcException ctor = harness.ctx.error("hook.probe", map("specs", Arrays.asList(map("id", "a", "mode",
                "OBSERVE", "target", map("class", "a.B", "method", "<init>", "descriptors",
                        Collections.singletonList("()V"))))));
        assertEquals("specs[0].target.method", ctor.error().data().get("param"));
        assertEquals("constructors are not hookable", ctor.getMessage());
        RpcException shape = harness.ctx.error("hook.probe", map("specs", Arrays.asList(map("id", "a"))));
        assertEquals(ErrorCodes.INVALID_PARAMS, shape.error().code());
        assertEquals("specs[0]", shape.error().data().get("param"));
        RpcException capture = harness.ctx.error("hook.probe", map("specs", Arrays.asList(map("id", "a", "mode",
                "OBSERVE", "target", map("class", "a.B", "method", "m", "descriptors",
                        Collections.singletonList("()V")), "capture", map("result", true)))));
        assertEquals("specs[0].capture", capture.error().data().get("param"));
    }

    @Test
    void aCaptureThatCanCopyNothingIsDegraded() throws Exception {
        new ai.skillroy.testjava.fixture.hooks.Capturing();
        harness = new Harness();
        Map<String, Object> p = probe(spec("ping", HookMode.CAPTURE, ai.skillroy.testjava.fixture.hooks.Capturing
                .class, "ping", Harness.capture(false, true, 0)));
        assertEquals("DEGRADED", p.get("state"));
        assertEquals("nothing to capture", p.get("reason"));
        assertNull(probe(spec("sign.on", HookMode.CAPTURE, ai.skillroy.testjava.fixture.hooks.Capturing.class,
                "signOn", Harness.capture(false, true))).get("reason"));
    }

    @Test
    void theProbeIsNotReadyBeforeTheTargetAndTheEngineIsUnavailableWithoutInstrumentation() throws Exception {
        FakeHookContext ctx = new FakeHookContext();
        HooksModule module = new HooksModule();
        module.install(ctx);
        RpcException early = ctx.error("hook.probe", map("specs", Harness.json(spec("deposit.computed",
                HookMode.OBSERVE, DepositRule.class, "depositFor"))));
        assertEquals(ErrorCodes.NOT_READY, early.error().code());
        module.uninstall();
        ctx.rollback();

        FakeHookContext embedded = new FakeHookContext();
        embedded.instrumentation = null;
        embedded.loadMode = ai.skillroy.testjava.protocol.LoadMode.EMBEDDED;
        HooksModule plain = new HooksModule();
        plain.install(embedded);
        assertEquals("no Instrumentation in EMBEDDED mode", embedded.capabilities.get(HooksModule.ENGINE).reason());
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, embedded.error("hook.probe", map("specs",
                Collections.emptyList())).error().code());
        RpcException remove = embedded.error("hook.remove", map());
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, remove.error().code(), "answered by the primitive itself");
        assertTrue(remove.getMessage().contains("no Instrumentation in EMBEDDED mode"), remove.getMessage());
        assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, embedded.error("hook.health", map()).error().code());
        plain.uninstall();
        embedded.rollback();
    }

    @Test
    void aMissingSpyJarMakesTheEngineUnavailable() throws Exception {
        String saved = System.getProperty("skillroy.agent.hooks.spyJar");
        System.setProperty("skillroy.agent.hooks.spyJar", "/no/such/dir/skillroy-agent-spy.jar");
        try {
            FakeHookContext ctx = new FakeHookContext();
            HooksModule module = new HooksModule();
            module.install(ctx);
            ctx.ready();
            String reason = ctx.capabilities.get(HooksModule.ENGINE).reason();
            if (Harness.bootSpy() == null) {
                assertEquals("spy jar not found: /no/such/dir/skillroy-agent-spy.jar".replace('/',
                        java.io.File.separatorChar), reason);
            } else {
                assertNull(reason, "the file is not needed once Spy is on the bootstrap path");
            }
            module.uninstall();
            ctx.rollback();
        } finally {
            System.setProperty("skillroy.agent.hooks.spyJar", saved);
        }
        assertNotNull(saved);
    }
}
