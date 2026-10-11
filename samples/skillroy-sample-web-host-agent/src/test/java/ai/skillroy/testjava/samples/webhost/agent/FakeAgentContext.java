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


package ai.skillroy.testjava.samples.webhost.agent;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentLog;
import ai.skillroy.testjava.agent.api.SpiRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link AgentContext} for the webhost module's tests: a real SPI registry, a recording log and the agent options
 * ({@code options()} and {@code option(key)} over {@link #options}); every other accessor fails the test, which shows
 * that the module uses nothing else (no primitive, no capability, no event, no thread of the agent, nothing of the
 * host but its registry).
 */
final class FakeAgentContext {

    final Spi spi = new Spi();
    final Log log = new Log();
    final List<String> unexpected = new CopyOnWriteArrayList<String>();
    /** The agent options the context answers. */
    final Map<String, String> options = new ConcurrentHashMap<String, String>();
    final AgentContext context = (AgentContext) Proxy.newProxyInstance(AgentContext.class.getClassLoader(),
            new Class<?>[] {AgentContext.class}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String name = method.getName();
                    if ("spi".equals(name)) {
                        return spi;
                    }
                    if ("log".equals(name)) {
                        return log;
                    }
                    if ("options".equals(name)) {
                        return Collections.unmodifiableMap(options);
                    }
                    if ("option".equals(name)) {
                        return args[0] == null ? null : options.get((String) args[0]);
                    }
                    if ("toString".equals(name)) {
                        return "FakeAgentContext";
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    unexpected.add(name);
                    throw new AssertionError("the webhost module must not use AgentContext." + name + "()");
                }
            });

    /** A list-backed registry: registration order, handles that unregister. */
    static final class Spi implements SpiRegistry {
        private final List<Object[]> entries = new CopyOnWriteArrayList<Object[]>();

        @Override
        public <T> AutoCloseable register(Class<T> type, T implementation) {
            final Object[] entry = {type, implementation};
            entries.add(entry);
            return new AutoCloseable() {
                @Override
                public void close() {
                    entries.remove(entry);
                }
            };
        }

        @Override
        public <T> List<T> all(Class<T> type) {
            List<T> result = new ArrayList<T>();
            for (Object[] e : entries) {
                if (e[0] == type) {
                    result.add(type.cast(e[1]));
                }
            }
            return Collections.unmodifiableList(result);
        }

        @Override
        public <T> T first(Class<T> type) {
            List<T> all = all(type);
            return all.isEmpty() ? null : all.get(0);
        }

        int count() {
            return entries.size();
        }
    }

    /** Keeps every line. */
    static final class Log implements AgentLog {
        final List<String> lines = new CopyOnWriteArrayList<String>();

        @Override
        public void info(String msg) {
            lines.add("INFO " + msg);
        }

        @Override
        public void warn(String msg) {
            lines.add("WARN " + msg);
        }

        @Override
        public void warn(String msg, Throwable t) {
            lines.add("WARN " + msg + " " + t);
        }

        @Override
        public void error(String msg, Throwable t) {
            lines.add("ERROR " + msg + " " + t);
        }

        @Override
        public List<String> tail(int n) {
            List<String> all = new ArrayList<String>(lines);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }
}
