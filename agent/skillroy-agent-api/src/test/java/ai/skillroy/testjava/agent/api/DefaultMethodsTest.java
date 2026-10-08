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

import ai.skillroy.testjava.agent.api.spi.ThreadPolicyProvider;
import ai.skillroy.testjava.protocol.JsonView;
import ai.skillroy.testjava.protocol.LoadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Default methods of the API: {@code AgentContext.option} (A20), {@code ThreadPolicyProvider.available} (A19) and
 * {@code Invocation.secretParams} (A34, phase4-contracts.md section 4.3).
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

    /** A context whose only real member is {@link AgentContext#options()}. */
    private static AgentContext context(final Map<String, String> options) {
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
                return null;
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
