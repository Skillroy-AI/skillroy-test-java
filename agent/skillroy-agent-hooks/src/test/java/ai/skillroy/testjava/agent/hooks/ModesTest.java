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
import ai.skillroy.testjava.fixture.hooks.Returns;
import ai.skillroy.testjava.protocol.Event;
import ai.skillroy.testjava.protocol.hook.HookMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.skillroy.testjava.agent.hooks.FakeHookContext.map;
import static ai.skillroy.testjava.agent.hooks.Harness.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three modes and their events (phase6-contracts.md sections 3.4 and 4.3), a mode's stub changed without any
 * retransformation, {@code hook.events}, and the health states (section 3.6).
 */
class ModesTest {

    private Harness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void observeReportsTheThrowableWithItsMessageRedactedAndTheHostStillSeesItsException() throws Exception {
        DepositRule rule = new DepositRule();
        harness = new Harness();
        harness.install("toys", spec("deposit.failing", HookMode.OBSERVE, DepositRule.class, "failing"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> rule.failing("T1"));
        assertTrue(e.getMessage().contains("password=hunter2"), "the host's own exception, untouched");
        Map<String, Object> payload = harness.ctx.events.last("hook.deposit.failing").payload();
        Map<String, Object> thrown = (Map<String, Object>) payload.get("thrown");
        assertEquals("java.lang.IllegalStateException", thrown.get("class"));
        assertEquals("deposit rule broke for T1: password=<redacted> limit=5", thrown.get("message"));
        harness.ctx.call("hook.remove", map());
    }

    @Test
    @SuppressWarnings("unchecked")
    void captureCopiesTheArgumentsTheReceiverAndTheResultItWasAskedFor() throws Exception {
        DepositRule rule = new DepositRule();
        harness = new Harness();
        harness.install("toys", spec("deposit.computed", HookMode.CAPTURE, DepositRule.class, "depositFor",
                Harness.capture(true, true, 0)));
        assertEquals(new BigDecimal("40.00"), rule.depositFor("P1"));
        Map<String, Object> payload = harness.ctx.events.last("hook.deposit.computed").payload();
        assertEquals("P1", ((Map<String, Object>) payload.get("args")).get("0"));
        assertEquals("40.00", payload.get("returned"));
        Map<String, Object> self = (Map<String, Object>) payload.get("self");
        assertEquals(DepositRule.class.getName(), self.get("class"));
        assertTrue(String.valueOf(self.get("ref")).startsWith("h"), "a handle, never a rendering: " + self);
        assertEquals(rule, harness.ctx.handles.resolve(String.valueOf(self.get("ref"))));
        assertFalse(payload.containsKey("truncated"));
        harness.ctx.call("hook.remove", map());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStubSkipsTheBodyReturnsItsValueAndChangesNeedNoRetransformation() throws Exception {
        Returns returns = new Returns();
        assertEquals(new BigDecimal("1.23"), DepositRule.money(new BigDecimal("1.234")));
        harness = new Harness();
        harness.install("toys", spec("line.amount", HookMode.STUB, DepositRule.class, "money"),
                spec("returns.broken", HookMode.STUB, Returns.class, "broken"),
                spec("returns.touch", HookMode.STUB, Returns.class, "touch"));
        final AtomicInteger transforms = new AtomicInteger();
        ClassFileTransformer counting = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (classBeingRedefined != null) {
                    transforms.incrementAndGet();
                }
                return null;
            }
        };
        Harness.inst().addTransformer(counting, true);
        try {
            assertEquals(new BigDecimal("12.35"), DepositRule.money(new BigDecimal("12.345")), "not armed yet");
            Map<String, Object> confirm = map("confirm", true);
            harness.ctx.call("hook.stub", map("id", "line.amount", "value", "1.00"), confirm);
            assertEquals(new BigDecimal("1.00"), DepositRule.money(new BigDecimal("12.345")));
            Event stubbed = harness.ctx.events.last("hook.line.amount");
            assertEquals(Boolean.TRUE, stubbed.payload().get("stubbed"));
            assertEquals(0L, ((Number) stubbed.payload().get("durationNs")).longValue());
            harness.ctx.call("hook.stub", map("id", "returns.broken", "value", "3.00"), confirm);
            assertEquals(new BigDecimal("3.00"), returns.broken(), "a throwing body is skipped too");
            harness.ctx.call("hook.stub", map("id", "returns.touch"), confirm);
            returns.touch();
            assertEquals(0, returns.touches, "a void STUB skips the body");
            Map<String, Object> health = harness.ctx.map("hook.health", map());
            assertEquals(Arrays.asList("line.amount", "returns.broken", "returns.touch"), health.get("stubs"));
            assertEquals(Boolean.TRUE, ((Map<String, Object>) ((Map<String, Object>) health.get("hooks"))
                    .get("line.amount")).get("armed"));
            harness.ctx.call("hook.stub", map("id", "line.amount", "clear", true), confirm);
            harness.ctx.call("hook.stub", map("id", "returns.touch", "clear", true), confirm);
            assertEquals(new BigDecimal("12.35"), DepositRule.money(new BigDecimal("12.345")), "computed again");
            returns.touch();
            assertEquals(1, returns.touches);
            assertEquals(0, transforms.get(), "arming and clearing retransformed nothing");
        } finally {
            Harness.inst().removeTransformer(counting);
        }
        harness.ctx.call("hook.remove", map());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hookEventsFiltersByHookSinceAndLimit() throws Exception {
        DepositRule rule = new DepositRule();
        harness = new Harness();
        harness.install("toys", spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor"),
                spec("deposit.waived", HookMode.OBSERVE, DepositRule.class, "waives"));
        long since = harness.ctx.events.lastSeq();
        rule.depositFor("T1");
        rule.waives("member");
        rule.depositFor("T2");
        List<Object> one = (List<Object>) harness.ctx.map("hook.events", map("since", since, "id",
                "deposit.computed")).get("events");
        assertEquals(2, one.size());
        List<Object> all = (List<Object>) harness.ctx.map("hook.events", map("since", since)).get("events");
        assertEquals(3, all.size(), "every hook.* topic, not hooks.*");
        List<Object> limited = (List<Object>) harness.ctx.map("hook.events", map("since", since, "limit", 1))
                .get("events");
        assertEquals(1, limited.size());
        assertEquals("hook.deposit.computed", ((Map<String, Object>) limited.get(0)).get("topic"));
        assertEquals(ai.skillroy.testjava.protocol.ErrorCodes.INVALID_PARAMS, harness.ctx.error("hook.events",
                map("since", since, "limit", 1001)).error().code());
        harness.ctx.call("hook.remove", map());
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthGoesFromInstalledToFiringAndToStaleWhenTheLoaderIsCollected() throws Exception {
        new DepositRule();
        harness = new Harness();
        ChildFirstLoader loader = new ChildFirstLoader(new URL[] {Harness.testClasses()},
                ModesTest.class.getClassLoader(), "ai.skillroy.testjava.fixture.hooks.");
        Class<?> copy = loader.loadClass("ai.skillroy.testjava.fixture.hooks.Capturing");
        harness.install("toys", spec("deposit.computed", HookMode.OBSERVE, DepositRule.class, "depositFor", null),
                Harness.spec("label.read", HookMode.OBSERVE, copy.getName(), "label", "()Ljava/lang/String;",
                        Integer.toHexString(System.identityHashCode(loader)), null, null, false));
        Map<String, Object> hooks = (Map<String, Object>) harness.ctx.map("hook.health", map()).get("hooks");
        Map<String, Object> computed = (Map<String, Object>) hooks.get("deposit.computed");
        assertEquals("INSTALLED", computed.get("state"));
        assertEquals(0L, ((Number) computed.get("count")).longValue());
        assertFalse(computed.containsKey("lastFiredMs"));
        new DepositRule().depositFor("T9");
        computed = (Map<String, Object>) ((Map<String, Object>) harness.ctx.map("hook.health", map()).get("hooks"))
                .get("deposit.computed");
        assertEquals("FIRING", computed.get("state"));
        assertEquals(1L, ((Number) computed.get("count")).longValue());
        assertNotNull(computed.get("lastFiredMs"));
        assertEquals("deposit.computed", ((Map<String, Object>) computed.get("last")).get("hook"));

        Method label = copy.getMethod("label");
        label.invoke(copy.newInstance());
        label = null;
        copy = null;
        loader.close();
        loader = null;
        long deadline = System.currentTimeMillis() + 20000;
        String state = null;
        while (System.currentTimeMillis() < deadline) {
            System.gc();
            state = String.valueOf(((Map<String, Object>) ((Map<String, Object>) harness.ctx.map("hook.health",
                    map()).get("hooks")).get("label.read")).get("state"));
            if ("STALE".equals(state)) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals("STALE", state, "the hooked class's loader was collected");
        harness.ctx.capabilities.reprobe(harness.ctx);
        assertEquals("DEGRADED", harness.ctx.capabilities.get("hook.label.read").state().name());
        Map<String, Object> removed = harness.ctx.map("hook.remove", map());
        assertTrue(String.valueOf(removed.get("restored")).contains("class unloaded"), String.valueOf(removed));
    }
}
