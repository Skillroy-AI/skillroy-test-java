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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.spi.AppStateProvider;
import ai.skillroy.testjava.agent.api.spi.ReadinessSource;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Primitive, SPI, capability and handle registries, and the log. */
class RegistriesTest {

    private static final Primitives.Body NOOP = new Primitives.Body() {
        @Override
        public Object invoke(Invocation invocation) {
            return null;
        }
    };

    @Test
    void primitivesAreUniqueAndListedByName() {
        CorePrimitiveRegistry registry = new CorePrimitiveRegistry();
        registry.register(Primitives.of(PrimitiveSpec.builder("b.two").build(), NOOP));
        registry.register(Primitives.of(PrimitiveSpec.builder("a.one").build(), NOOP));
        assertThrows(IllegalStateException.class,
                () -> registry.register(Primitives.of(PrimitiveSpec.builder("a.one").build(), NOOP)));
        assertEquals("a.one", registry.list().get(0).name());
        assertEquals("b.two", registry.find("b.two").spec().name());
        registry.unregister("b.two");
        assertNull(registry.find("b.two"));
        registry.unregister("never.there");
        assertEquals(1, registry.list().size());
    }

    @Test
    void spiKeepsRegistrationOrderAndHandlesRemoveTheirOwnEntry() throws Exception {
        CoreSpiRegistry spi = new CoreSpiRegistry();
        ReadinessSource first = source("first");
        ReadinessSource second = source("second");
        AutoCloseable h1 = spi.register(ReadinessSource.class, first);
        AutoCloseable h2 = spi.register(ReadinessSource.class, second);
        AutoCloseable h3 = spi.register(ReadinessSource.class, first);
        assertEquals(Arrays.asList(first, second, first), spi.all(ReadinessSource.class));
        assertSame(first, spi.first(ReadinessSource.class));
        h1.close();
        assertEquals(Arrays.asList(second, first), spi.all(ReadinessSource.class));
        h2.close();
        h3.close();
        assertNull(spi.first(ReadinessSource.class));
        assertTrue(spi.all(AppStateProvider.class).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registerUnchecked(spi));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerUnchecked(CoreSpiRegistry spi) {
        spi.register((Class) ReadinessSource.class, "not a source");
    }

    private static ReadinessSource source(final String id) {
        return new ReadinessSource() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public String describe() {
                return id;
            }
        };
    }

    @Test
    void capabilitiesStartPendingUntilProbedAndSetDeclares() {
        CoreCapabilityRegistry caps = new CoreCapabilityRegistry(new RingLog());
        caps.declare("x.probe", (ctx) -> {
            throw new IllegalStateException("probe exploded");
        });
        caps.declare("y.ok", (ctx) -> ai.skillroy.testjava.agent.api.ProbeResult.degraded("slow"));
        caps.set("z.set", CapabilityState.OK, null, "direct");
        assertTrue(caps.isPending("x.probe"));
        assertEquals(CapabilityState.UNAVAILABLE, caps.get("x.probe").state());
        assertEquals(CoreCapabilityRegistry.PENDING_REASON, caps.get("x.probe").reason());
        assertFalse(caps.isPending("z.set"));
        assertNull(caps.get("nope"));
        assertEquals(Arrays.asList("x.probe", "y.ok", "z.set"), Arrays.asList(caps.snapshot().keySet().toArray()));
        caps.probeAll();
        assertFalse(caps.isPending("x.probe"));
        assertEquals("probe exploded", caps.get("x.probe").reason());
        assertEquals(CapabilityState.DEGRADED, caps.get("y.ok").state());
        assertEquals("slow", caps.get("y.ok").reason());
        Map<String, Object> json = caps.toJson();
        assertTrue(((Map<?, ?>) json.get("capabilities")).containsKey("z.set"));
        // gap 92: declared once probes run, pending until its posted probe has run (no worker here), and it says so
        caps.declare("w.late", (ctx) -> ai.skillroy.testjava.agent.api.ProbeResult.ok());
        assertTrue(caps.isPending("w.late"));
        assertEquals(CapabilityState.UNAVAILABLE, caps.get("w.late").state());
        assertEquals(CoreCapabilityRegistry.LATE_REASON, caps.get("w.late").reason());
        assertTrue(caps.get("w.late").reason().startsWith("not probed yet"), "the prefix callers read");
    }

    @Test
    void handlesAreStablePerIdentityAndWeak() throws Exception {
        WeakHandleRegistry handles = new WeakHandleRegistry();
        Object a = new String("same");
        Object b = new String("same");
        String ha = handles.handleFor(a, "thing");
        assertTrue(ha.matches("h\\d+"));
        assertEquals(ha, handles.handleFor(a, "other kind"));
        String hb = handles.handleFor(b, "thing");
        assertNotEquals(ha, hb, "identity, not equals()");
        assertSame(a, handles.resolve(ha));
        assertSame(a, handles.resolve(ha, String.class));
        RpcException wrongType = assertThrows(RpcException.class, () -> handles.resolve(ha, Integer.class));
        assertEquals(ErrorCodes.INVALID_PARAMS, wrongType.error().code());
        RpcException unknown = assertThrows(RpcException.class, () -> handles.resolve("h999999"));
        assertEquals(ErrorCodes.STALE_HANDLE, unknown.error().code());
        handles.release(hb);
        assertEquals(ErrorCodes.STALE_HANDLE, assertThrows(RpcException.class, () -> handles.resolve(hb))
                .error().code());
        String hc = handles.handleFor(new Object(), "garbage");
        AgentHarness.await("collected", 30000, () -> {
            System.gc();
            try {
                handles.resolve(hc);
                return false;
            } catch (RpcException stale) {
                return stale.error().code() == ErrorCodes.STALE_HANDLE;
            }
        });
        assertSame(a, handles.resolve(ha), "a live object keeps its handle");
        assertThrows(IllegalArgumentException.class, () -> handles.handleFor(null, "x"));
    }

    @Test
    void logKeepsTheNewestLines() {
        RingLog log = new RingLog();
        for (int i = 0; i < RingLog.CAPACITY + 10; i++) {
            log.info("line " + i);
        }
        log.warn("warned", new IllegalStateException("why"));
        java.util.List<String> tail = log.tail(2);
        assertEquals(2, tail.size());
        assertTrue(tail.get(0).endsWith("line " + (RingLog.CAPACITY + 9)), tail.get(0));
        assertTrue(tail.get(1).contains("WARN") && tail.get(1).contains("why"));
        assertEquals(RingLog.CAPACITY, log.tail(Integer.MAX_VALUE).size());
        assertEquals(Collections.emptyList(), log.tail(-1));
    }
}
