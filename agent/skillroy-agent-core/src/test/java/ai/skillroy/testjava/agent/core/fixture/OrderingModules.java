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

package ai.skillroy.testjava.agent.core.fixture;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.api.spi.AppStateProvider;
import ai.skillroy.testjava.agent.api.spi.ProductionMarker;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Modules that exercise ordering by requires(), failures and duplicate ids. */
public final class OrderingModules {

    private OrderingModules() {
    }

    /** Recording base. */
    abstract static class Recording implements AgentModule {
        @Override
        public String version() {
            return "1";
        }

        @Override
        public void install(AgentContext ctx) throws Exception {
            TestState.INSTALLED.add(id());
        }

        @Override
        public void onTargetReady(AgentContext ctx) {
            TestState.READY_CALLS.add(id() + "@" + Thread.currentThread().getName());
        }

        @Override
        public void uninstall() {
            TestState.UNINSTALLED.add(id());
        }
    }

    /** Listed before order.b but requires it. */
    public static final class OrderA extends Recording {
        @Override
        public String id() {
            return "order.a";
        }

        @Override
        public Set<String> requires() {
            return Collections.singleton("order.b");
        }
    }

    /** Required by order.a. */
    public static final class OrderB extends Recording {
        @Override
        public String id() {
            return "order.b";
        }
    }

    /** A second module claiming the id order.b; ignored with a warning. */
    public static final class DuplicateOrderB extends Recording {
        @Override
        public String id() {
            return "order.b";
        }

        @Override
        public String version() {
            return "duplicate";
        }
    }

    /** Requires a module nobody provides. */
    public static final class NeedsMissing extends Recording {
        @Override
        public String id() {
            return "needs.missing";
        }

        @Override
        public Set<String> requires() {
            return Collections.singleton("nope");
        }
    }

    /**
     * Registers a primitive, an SPI and a (passing) ProductionMarker, then fails: all must be rolled back, and the lost
     * marker must not let the production gate fail open (finding S14).
     */
    public static final class Failing implements AgentModule {
        @Override
        public String id() {
            return "failing";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public void install(AgentContext ctx) {
            ctx.primitives().register(Primitives.of(PrimitiveSpec.builder("failing.partial").build(),
                    new Primitives.Body() {
                        @Override
                        public Object invoke(Invocation inv) {
                            return "should not be callable";
                        }
                    }));
            ctx.spi().register(ProductionMarker.class, new ProductionMarker() {
                @Override
                public String productionReason() {
                    return null;
                }
            });
            ctx.spi().register(AppStateProvider.class, new AppStateProvider() {
                @Override
                public String id() {
                    return "failing";
                }

                @Override
                public Map<String, Object> appState() {
                    return Collections.emptyMap();
                }
            });
            throw new IllegalStateException("install boom");
        }

        @Override
        public void uninstall() {
            TestState.UNINSTALLED.add("failing");
        }
    }
}
