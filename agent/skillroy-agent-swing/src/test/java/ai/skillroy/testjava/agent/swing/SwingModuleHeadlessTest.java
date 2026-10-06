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

package ai.skillroy.testjava.agent.swing;

import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;

import java.awt.AWTEvent;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.AWTEventListenerProxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.expectError;
import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Module wiring and capability gating. The states before AWT runs are checked in-process with a stand-in for the
 * event-dispatch-thread check; the JVM-level proof that nothing starts AWT is {@link AwtStartTest}. The headless
 * gating tests run only in a headless JVM (the native build).
 */
class SwingModuleHeadlessTest {

    private static final List<String> NAMES = Arrays.asList("swing.capture", "swing.click", "swing.dialogs",
            "swing.dismiss", "swing.edtState", "swing.find", "swing.key", "swing.settle", "swing.snapshot",
            "swing.type", "swing.windows");
    private static final Set<String> MUTATING = new HashSet<String>(Arrays.asList("swing.click", "swing.dismiss",
            "swing.key", "swing.type"));
    private static final List<String> DISPLAY_CAPS = Arrays.asList(SwingModule.CAP_TREE,
            SwingModule.CAP_CLICK_ACTION, SwingModule.CAP_CLICK_COMPONENT, SwingModule.CAP_CLICK_DISPATCH,
            SwingModule.CAP_CAPTURE_PAINT);

    private FakeContext ctx;
    private SwingModule module;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new FakeContext();
        module = new SwingModule();
        module.install(ctx);
    }

    @AfterEach
    void tearDown() {
        module.uninstall();
        ctx.shutdown();
    }

    @Test
    void registersEveryPrimitiveWithItsContract() {
        List<String> names = new ArrayList<String>();
        for (PrimitiveSpec spec : ctx.primitives.list()) {
            names.add(spec.name());
            assertEquals(ThreadPolicy.CALLER, spec.thread(), spec.name() + " runs on CALLER and hops itself");
            if ("swing.edtState".equals(spec.name())) {
                assertNull(spec.capabilityId(), "edtState answers in every state, so it names no capability");
            } else {
                assertNotNull(spec.capabilityId(), spec.name() + " names a capability");
            }
            assertEquals(MUTATING.contains(spec.name()), spec.mutating(), spec.name() + " mutating flag");
            assertFalse(spec.destructive(), spec.name());
            assertFalse(spec.description().isEmpty(), spec.name() + " has a description");
        }
        assertEquals(NAMES, names);
        assertEquals(SwingModule.CAP_CAPTURE_PAINT, ctx.primitives.find("swing.capture").spec().capabilityId());
        assertEquals(SwingModule.CAP_TREE, ctx.primitives.find("swing.click").spec().capabilityId());
        assertTrue(ctx.primitives.find("swing.settle").spec().timeoutMs() > SwingPrimitives.MAX_SETTLE_MS,
                "settle's own timeoutMs governs, not the dispatcher default");
    }

    @Test
    void idVersionAndServiceRegistration() {
        assertEquals("swing", module.id());
        assertNotEquals("unknown", module.version());
        assertFalse(module.version().contains("${"), "version resource is filtered: " + module.version());
        boolean found = false;
        for (AgentModule m : ServiceLoader.load(AgentModule.class, SwingModule.class.getClassLoader())) {
            found |= m instanceof SwingModule;
        }
        assertTrue(found, "META-INF/services registers SwingModule");
    }

    @Test
    void robotRungIsAlwaysUnavailable() {
        Capability robot = ctx.capabilities.get(SwingModule.CAP_CLICK_ROBOT);
        assertEquals(CapabilityState.UNAVAILABLE, robot.state());
        assertEquals("opt-in rung, not implemented", robot.reason());
    }

    @Test
    void headlessMakesEverySwingCapabilityUnavailable() throws Exception {
        assumeTrue(GraphicsEnvironment.isHeadless(), "headless JVM only");
        module.onTargetReady(ctx);
        for (String id : Arrays.asList(SwingModule.CAP_TREE, SwingModule.CAP_CLICK_ACTION,
                SwingModule.CAP_CLICK_COMPONENT, SwingModule.CAP_CLICK_DISPATCH, SwingModule.CAP_CAPTURE_PAINT)) {
            Capability c = ctx.capabilities.get(id);
            assertEquals(CapabilityState.UNAVAILABLE, c.state(), id);
            assertEquals("headless", c.reason(), id);
        }
        for (final String name : NAMES) {
            if ("swing.edtState".equals(name)) {
                continue;
            }
            RpcException e = expectError(ErrorCodes.CAPABILITY_UNAVAILABLE, () -> ctx.call(name, map()));
            assertEquals("headless", e.error().data().get("reason"), name);
        }
        Map<String, Object> state = ctx.call("swing.edtState", map());
        assertEquals(Boolean.FALSE, state.get("edtBlocked"), "edtState still answers: " + state);
        assertEquals(list(), state.get("modalDialogs"));
    }

    @Test
    void beforeAwtRunsEveryDisplayCapabilityWaitsWithTheReasonThePropertyGives() throws Exception {
        FakeContext other = new FakeContext();
        SwingModule waiting = new SwingModule(() -> false);
        try {
            waiting.install(other);
            waiting.onTargetReady(other);
            String expected = SwingModule.headlessProperty() ? "headless" : "AWT not started by the application yet";
            for (String id : DISPLAY_CAPS) {
                Capability c = other.capabilities.get(id);
                assertEquals(CapabilityState.UNAVAILABLE, c.state(), id);
                assertEquals(expected, c.reason(), id);
            }
            assertEquals("opt-in rung, not implemented", other.capabilities.get(SwingModule.CAP_CLICK_ROBOT).reason());
            RpcException e = expectError(ErrorCodes.CAPABILITY_UNAVAILABLE, () -> other.call("swing.find",
                    map("by", map("id", "x"))));
            assertEquals(expected, e.error().data().get("reason"));
            assertFalse(waiting.tapInstalled());
        } finally {
            waiting.uninstall();
            other.shutdown();
        }
    }

    @Test
    void onceAnEventDispatchThreadIsSeenTheCapabilitiesFollowTheToolkit() throws Exception {
        FakeContext other = new FakeContext();
        AtomicBoolean edt = new AtomicBoolean();
        SwingModule module2 = new SwingModule(edt::get);
        try {
            module2.install(other);
            module2.onTargetReady(other);
            edt.set(true);
            module2.onTargetReady(other); // confirms at once, without waiting for the watcher
            boolean headless = GraphicsEnvironment.isHeadless();
            for (String id : DISPLAY_CAPS) {
                Capability c = other.capabilities.get(id);
                assertEquals(headless ? CapabilityState.UNAVAILABLE : CapabilityState.OK, c.state(), id);
                assertEquals(headless ? "headless" : null, c.reason(), id);
            }
            assertEquals(!headless, module2.tapInstalled());
        } finally {
            module2.uninstall();
            other.shutdown();
        }
    }

    @Test
    void headlessInstallsNoAwtTap() throws Exception {
        assumeTrue(GraphicsEnvironment.isHeadless(), "headless JVM only");
        module.onTargetReady(ctx);
        assertFalse(module.tapInstalled());
        for (AWTEventListener l : Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.WINDOW_EVENT_MASK)) {
            AWTEventListener inner = l instanceof AWTEventListenerProxy
                    ? ((AWTEventListenerProxy) l).getListener() : l;
            assertFalse(inner instanceof WindowTap, "no window tap in a headless JVM");
        }
    }

    @Test
    void headlessPropertyIsReadLikeTheJdkReadsIt() {
        // read only: changing java.awt.headless in a shared test JVM could change what AWT decides for other tests
        assertEquals(Boolean.parseBoolean(System.getProperty("java.awt.headless")), SwingModule.headlessProperty());
    }

    @Test
    void uninstallUnregistersPrimitives() {
        module.uninstall();
        assertTrue(ctx.primitives.list().isEmpty());
        module.uninstall(); // idempotent
    }
}
