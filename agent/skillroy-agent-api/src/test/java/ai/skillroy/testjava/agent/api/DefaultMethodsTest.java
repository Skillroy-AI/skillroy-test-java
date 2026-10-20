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

package ai.skillroy.testjava.agent.api;

import ai.skillroy.testjava.agent.api.spi.DataSourceProvider;
import ai.skillroy.testjava.agent.api.spi.QueryControl;
import ai.skillroy.testjava.agent.api.spi.QueryResult;
import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.agent.api.spi.hook.HookEngine;
import ai.skillroy.testjava.protocol.hook.HookHealth;
import ai.skillroy.testjava.protocol.hook.HookProbe;
import ai.skillroy.testjava.protocol.hook.HookSpec;
import ai.skillroy.testjava.protocol.hook.HookStatus;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Default methods of the API: {@code AgentContext.option} (A20), {@code ThreadPolicyProvider.available} (A19),
 * {@code Invocation.secretParams} (A34, phase4-contracts.md section 4.3) and the Phase 6 defaults
 * {@code AgentContext.hooks}, {@code isSecretKey} and {@code redactText} (phase6-contracts.md section 3.6).
 */
class DefaultMethodsTest {

    private static final String LIVE = "skillroy.agent.defaultMethodsTest.live";
    private static final String BOTH = "skillroy.agent.defaultMethodsTest.both";

    @AfterEach
    void clear() {
        System.clearProperty(LIVE);
        System.clearProperty(BOTH);
    }

    @Test
    void optionPrefersTheStartTimeValueAndElseReadsTheSystemPropertyLive() {
        AgentContext ctx = context(Collections.singletonMap("defaultMethodsTest.both", "from-start"));
        assertNull(ctx.option("defaultMethodsTest.live"), "absent everywhere");
        assertNull(ctx.option(null));
        System.setProperty(LIVE, "set-later");
        assertEquals("set-later", ctx.option("defaultMethodsTest.live"), "a property set after the start is seen");
        System.setProperty(LIVE, "changed");
        assertEquals("changed", ctx.option("defaultMethodsTest.live"), "read live on every call");
        System.setProperty(BOTH, "from-property");
        assertEquals("from-start", ctx.option("defaultMethodsTest.both"), "the start-time options win");
        assertTrue(ctx.options().size() == 1, "options() stays the start-time snapshot");
    }

    @Test
    void aThreadPolicyProviderIsAvailableByDefault() {
        ThreadPolicyProvider provider = new ThreadPolicyProvider() {
            @Override
            public String id() {
                return "p";
            }

            @Override
            public boolean isForbidden(Thread thread) {
                return false;
            }

            @Override
            public <T> T callOnAppThread(Callable<T> task, long timeoutMs) throws Exception {
                return task.call();
            }
        };
        assertTrue(provider.available());
    }

    @Test
    void anInvocationNamesNoSecretParametersByDefault() {
        Invocation invocation = new Invocation() {
            @Override
            public JsonView args() {
                return JsonView.of(null);
            }

            @Override
            public String correlationId() {
                return null;
            }

            @Override
            public String traceparent() {
                return null;
            }

            @Override
            public AgentContext context() {
                return null;
            }

            @Override
            public long remainingMs() {
                return 0;
            }
        };
        assertTrue(invocation.secretParams().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> invocation.secretParams().add("text"),
                "the default list is read-only");
    }

    @Test
    void hooksIsTheFirstRegisteredHookEngineOrNull() {
        assertNull(context(Collections.<String, String>emptyMap()).hooks(), "no SPI registry at all");
        final HookEngine engine = new HookEngine() {
            @Override
            public HookProbe probe(HookSpec spec) {
                return null;
            }

            @Override
            public List<HookStatus> install(List<HookSpec> specs, String catalogueId, String catalogueHash,
                                            boolean replace) {
                return null;
            }

            @Override
            public boolean stub(String id, Object value, boolean clear) {
                return false;
            }

            @Override
            public List<HookStatus> remove(List<String> ids) {
                return null;
            }

            @Override
            public Map<String, HookHealth> health(List<String> ids) {
                return null;
            }

            @Override
            public Map<String, Object> catalogue() {
                return Collections.emptyMap();
            }
        };
        SpiRegistry spi = new SpiRegistry() {
            @Override
            public <T> AutoCloseable register(Class<T> type, T implementation) {
                return null;
            }

            @Override
            public <T> List<T> all(Class<T> type) {
                return Collections.emptyList();
            }

            @Override
            public <T> T first(Class<T> type) {
                return type == HookEngine.class ? type.cast(engine) : null;
            }
        };
        assertSame(engine, context(Collections.<String, String>emptyMap(), spi).hooks());
    }

    @Test
    void theSecretRuleAndTheTextRedactionDefaultToTheProtocolsRuleAndTheIdentity() {
        AgentContext ctx = context(Collections.<String, String>emptyMap());
        assertTrue(ctx.isSecretKey("password"));
        assertTrue(ctx.isSecretKey("db.apiToken"));
        assertFalse(ctx.isSecretKey("staffCode"));
        assertFalse(ctx.isSecretKey(null));
        assertEquals("password=hunter2", ctx.redactText("password=hunter2"), "the default leaves text as it is");
        assertNull(ctx.redactText(null));
    }

    /** phase8-contracts.md 4.1: a provider offers no quiescent point unless it says so. */
    @Test
    void aThreadPolicyProviderOffersNoQuiescentPointByDefault() {
        ThreadPolicyProvider provider = new ThreadPolicyProvider() {
            @Override
            public String id() {
                return "p";
            }

            @Override
            public boolean isForbidden(Thread thread) {
                return false;
            }

            @Override
            public <T> T callOnAppThread(Callable<T> task, long timeoutMs) throws Exception {
                return task.call();
            }
        };
        assertNull(provider.quiescentPoint());
    }

    /**
     * phase8-contracts.md 4.2 to 4.4: a 0.1.0 data source has no lane, no column names, does not enforce the timeout,
     * and is served through the five-argument query, which delegates to its four-argument one.
     */
    @Test
    void aDataSourceProvidersPhase8MembersDefaultToTheOldBehaviour() throws Exception {
        final List<Object> seen = new java.util.ArrayList<Object>();
        final QueryResult answer = new QueryResult(null, null, null, false);
        DataSourceProvider provider = new DataSourceProvider() {
            @Override
            public String id() {
                return "positional";
            }

            @Override
            public String describe() {
                return "answers by position";
            }

            @Override
            public QueryResult query(String sql, List<Object> params, int maxRows, long timeoutMs) {
                seen.add(sql);
                seen.add(params);
                seen.add(Integer.valueOf(maxRows));
                seen.add(Long.valueOf(timeoutMs));
                return answer;
            }
        };
        assertNull(provider.lane());
        assertNull(provider.columns("staff"));
        assertFalse(provider.enforcesTimeout());
        QueryControl control = new QueryControl() {
            @Override
            public long remainingMs() {
                return 1;
            }

            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public AutoCloseable onCancel(Runnable action) {
                throw new AssertionError("the default never registers an action");
            }
        };
        List<Object> params = Collections.<Object>singletonList("A1");
        assertSame(answer, provider.query("SELECT code FROM item WHERE code = ?", params, 5, 750L, control));
        assertEquals(java.util.Arrays.asList("SELECT code FROM item WHERE code = ?", params, Integer.valueOf(5),
                Long.valueOf(750L)), seen);
        DataSourceProvider neither = new DataSourceProvider() {
            @Override
            public String id() {
                return "jdbc";
            }

            @Override
            public String describe() {
                return "JDBC only";
            }
        };
        assertNull(neither.query("SELECT 1", params, 1, 10L, control), "no statement form: open() is used");
    }

    /**
     * phase8-contracts.md 4.1, 4.2: a broker written for 0.1.0 (a test fake) compiles unchanged; its Phase 8 members
     * throw until the agent's own broker implements them.
     */
    @Test
    void aBrokersPhase8MembersThrowByDefault() {
        final ThreadBroker broker = new ThreadBroker() {
            @Override
            public <T> T call(ThreadPolicy policy, Callable<T> task, long timeoutMs) throws Exception {
                return task.call();
            }

            @Override
            public void post(ThreadPolicy policy, Runnable task) {
                task.run();
            }

            @Override
            public boolean isForbiddenThread(Thread thread) {
                return false;
            }
        };
        final Callable<String> task = new Callable<String>() {
            @Override
            public String call() {
                throw new AssertionError("the default never runs the task");
            }
        };
        assertThrows(UnsupportedOperationException.class, () -> broker.callQuiescent(task, 50L, 1000L));
        assertThrows(UnsupportedOperationException.class, () -> broker.declareLane("test.reads", 1, 0));
        assertThrows(UnsupportedOperationException.class, () -> broker.callOnLane("test.reads", task, 1000L));
    }

    /**
     * phase8-contracts.md 3.2 rule 4 (A68): a module written for 0.1.0 compiles unchanged and reports no {@code info}
     * entry; the default map is empty and read-only.
     */
    @Test
    void aModuleReportsNoInfoByDefault() {
        AgentModule module = new AgentModule() {
            @Override
            public String id() {
                return "m";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public void install(AgentContext ctx) {
            }
        };
        assertTrue(module.info().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> module.info().put("x", "y"),
                "the default map is read-only");
    }

    /** A context whose only real member is {@link AgentContext#options()}. */
    private static AgentContext context(final Map<String, String> options) {
        return context(options, null);
    }

    /** A context whose only real members are {@link AgentContext#options()} and {@link AgentContext#spi()}. */
    private static AgentContext context(final Map<String, String> options, final SpiRegistry spi) {
        return new AgentContext() {
            @Override
            public Instrumentation instrumentation() {
                return null;
            }

            @Override
            public LoadMode loadMode() {
                return LoadMode.EMBEDDED;
            }

            @Override
            public Map<String, String> options() {
                return options;
            }

            @Override
            public ClassLoader hostClassLoader() {
                return null;
            }

            @Override
            public Object hostContext() {
                return null;
            }

            @Override
            public PrimitiveRegistry primitives() {
                return null;
            }

            @Override
            public EventBus events() {
                return null;
            }

            @Override
            public HandleRegistry handles() {
                return null;
            }

            @Override
            public ThreadBroker threads() {
                return null;
            }

            @Override
            public CapabilityRegistry capabilities() {
                return null;
            }

            @Override
            public SpiRegistry spi() {
                return spi;
            }

            @Override
            public AgentLog log() {
                return null;
            }

            @Override
            public boolean readOnly() {
                return false;
            }

            @Override
            public boolean targetReady() {
                return false;
            }
        };
    }
}
