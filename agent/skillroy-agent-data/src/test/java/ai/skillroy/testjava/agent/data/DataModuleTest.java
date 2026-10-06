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

package ai.skillroy.testjava.agent.data;

import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.ThreadPolicy;
import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.protocol.Capability;
import ai.skillroy.testjava.protocol.CapabilityState;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.PrimitiveDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataModuleTest {

    @Test
    void isDiscoveredThroughServiceLoader() {
        List<AgentModule> found = new ArrayList<AgentModule>();
        for (AgentModule module : ServiceLoader.load(AgentModule.class, DataModule.class.getClassLoader())) {
            found.add(module);
        }
        assertEquals(1, found.size());
        assertTrue(found.get(0) instanceof DataModule);
        assertEquals("data", found.get(0).id());
        assertEquals(DataModule.VERSION, found.get(0).version());
        assertTrue(found.get(0).requires().isEmpty());
    }

    @Test
    void installRegistersBothPrimitivesWithTheirContract() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext();
        new DataModule().install(ctx);

        PrimitiveSpec query = ctx.primitives.find("data.query").spec();
        assertEquals(ThreadPolicy.WORKER, query.thread());
        assertEquals(10000L, query.timeoutMs());
        assertEquals("data.query", query.capabilityId());
        assertFalse(query.mutating());
        assertFalse(query.destructive());
        assertEquals(Arrays.asList("source", "sql"), query.paramsSchema().get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) query.paramsSchema().get("properties");
        assertEquals(Arrays.asList("source", "sql", "params", "maxRows"), new ArrayList<String>(properties.keySet()));

        PrimitiveSpec sources = ctx.primitives.find("data.sources").spec();
        assertEquals(ThreadPolicy.WORKER, sources.thread());
        assertNull(sources.capabilityId());
        assertFalse(sources.mutating());

        for (PrimitiveSpec spec : ctx.primitives.list()) {
            PrimitiveDescriptor d = spec.describe();
            assertEquals(d, PrimitiveDescriptor.fromJson(Json.parseObject(Json.write(d.toJson()))));
        }
    }

    @Test
    void capabilityIsOkOnlyWhileAProviderIsRegistered() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext();
        new DataModule().install(ctx);
        assertTrue(ctx.capabilities.declared("data.query"));

        Capability none = ctx.capabilities.probe("data.query");
        assertEquals(CapabilityState.UNAVAILABLE, none.state());
        assertEquals("no DataSourceProvider is registered", none.reason());

        AutoCloseable registration = ctx.spi.register(DataSourceProvider.class, new NamedProvider("db", "d"));
        assertEquals(CapabilityState.OK, ctx.capabilities.probe("data.query").state());

        registration.close();
        assertEquals(CapabilityState.UNAVAILABLE, ctx.capabilities.probe("data.query").state());
    }

    @Test
    void sourcesListsEachIdOnceInRegistrationOrder() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext();
        new DataModule().install(ctx);
        ctx.spi.register(DataSourceProvider.class, new NamedProvider("main", "the main database"));
        ctx.spi.register(DataSourceProvider.class, new NamedProvider("archive", null));
        ctx.spi.register(DataSourceProvider.class, new NamedProvider("main", "a shadowed duplicate"));
        ctx.spi.register(DataSourceProvider.class, new NamedProvider(null, "no id"));
        ctx.spi.register(DataSourceProvider.class, new NamedProvider("broken", "describe fails") {
            @Override
            public String describe() {
                throw new IllegalStateException("describe failed");
            }
        });
        ctx.spi.register(DataSourceProvider.class, new NamedProvider("x", "id fails") {
            @Override
            public String id() {
                throw new IllegalStateException("id failed");
            }
        });

        Object value = ctx.call("data.sources", Collections.<String, Object>emptyMap());
        assertEquals("{\"sources\":[{\"id\":\"main\",\"describe\":\"the main database\"},"
                + "{\"id\":\"archive\",\"describe\":null},{\"id\":\"broken\",\"describe\":null}]}", Json.write(value));
        assertTrue(ctx.log.all().contains("failed in describe()"), ctx.log.all());
        assertTrue(ctx.log.all().contains("failed in id()"), ctx.log.all());
    }

    @Test
    void sourcesIsEmptyWithoutProviders() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext();
        new DataModule().install(ctx);
        assertEquals("{\"sources\":[]}", Json.write(ctx.call("data.sources",
                Collections.<String, Object>emptyMap())));
    }

    @Test
    void uninstallUnregistersThePrimitives() throws Exception {
        FakeAgentContext ctx = new FakeAgentContext();
        DataModule module = new DataModule();
        module.install(ctx);
        assertEquals(2, ctx.primitives.list().size());
        module.uninstall();
        assertTrue(ctx.primitives.list().isEmpty());
        module.uninstall();
        new DataModule().uninstall();
    }

    @Test
    void failedInstallLeavesNothingRegistered() {
        FakeAgentContext ctx = new FakeAgentContext();
        ctx.primitives.register(Primitives.of(PrimitiveSpec.builder("data.query").build(), inv -> null));
        assertThrows(IllegalStateException.class, () -> new DataModule().install(ctx));
        assertNull(ctx.primitives.find("data.sources"));
        assertNotNull(ctx.primitives.find("data.query"));
        assertFalse(ctx.capabilities.declared("data.query"));
    }

    @Test
    void failedCapabilityDeclarationUnregistersThePrimitives() {
        FakeAgentContext ctx = new FakeAgentContext();
        ctx.capabilities.declareFailure = new IllegalStateException("registry closed");
        DataModule module = new DataModule();
        assertThrows(IllegalStateException.class, () -> module.install(ctx));
        assertTrue(ctx.primitives.list().isEmpty());
        module.uninstall();
    }

    static class NamedProvider implements DataSourceProvider {
        private final String id;
        private final String describe;

        NamedProvider(String id, String describe) {
            this.id = id;
            this.describe = describe;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String describe() {
            return describe;
        }
    }
}
