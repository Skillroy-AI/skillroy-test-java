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

package ai.skillroy.testjava.protocol.sql;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps a real JDBC connection in dynamic proxies that record every call on the connection, its prepared statements
 * and their result sets, and can inject failures or side effects. Calls are recorded as {@code Type.method} or
 * {@code Type.method[arg, ...]}. (A copy of the agent data module's test helper, for the shared read path.)
 */
final class JdbcSpy {

    final List<String> calls = Collections.synchronizedList(new ArrayList<String>());
    private final Map<String, Throwable> failures = new ConcurrentHashMap<String, Throwable>();
    private final Map<String, Runnable> hooks = new ConcurrentHashMap<String, Runnable>();
    private volatile int statementsOpened;
    private volatile int resultSetsOpened;

    /** Makes the method (e.g. "Connection.setReadOnly") throw instead of reaching the driver. */
    JdbcSpy fail(String method, Throwable failure) {
        failures.put(method, failure);
        return this;
    }

    /** Runs {@code hook} before every call of the method; the call then proceeds. */
    JdbcSpy before(String method, Runnable hook) {
        hooks.put(method, hook);
        return this;
    }

    Connection wrap(Connection real) {
        return proxy(Connection.class, real);
    }

    int count(String call) {
        int n = 0;
        synchronized (calls) {
            for (String c : calls) {
                if (c.equals(call) || c.startsWith(call + "[")) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Index of the first recorded call equal to or starting with {@code call}, or -1. */
    int indexOf(String call) {
        synchronized (calls) {
            for (int i = 0; i < calls.size(); i++) {
                String c = calls.get(i);
                if (c.startsWith(call)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * True when every statement and result set the connection produced was closed once and the connection itself was
     * left open: {@link ReadOnlyJdbc} never closes the caller's connection.
     */
    boolean statementsClosedConnectionKept() {
        return count("Connection.close") == 0 && count("PreparedStatement.close") == statementsOpened
                && count("ResultSet.close") == resultSetsOpened;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(final Class<T> type, final Object real) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object self, Method method, Object[] args) throws Throwable {
                if (method.getDeclaringClass() == Object.class) {
                    if ("equals".equals(method.getName())) {
                        return self == args[0];
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(self);
                    }
                    return type.getSimpleName() + "Spy(" + real + ")";
                }
                String name = type.getSimpleName() + "." + method.getName();
                calls.add(args == null || args.length == 0 ? name : name + Arrays.toString(args));
                Runnable hook = hooks.get(name);
                if (hook != null) {
                    hook.run();
                }
                Throwable failure = failures.get(name);
                if (failure != null) {
                    throw failure;
                }
                Object result;
                try {
                    result = method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
                if (result instanceof PreparedStatement) {
                    statementsOpened++;
                    return proxy(PreparedStatement.class, result);
                }
                if (result instanceof ResultSet) {
                    resultSetsOpened++;
                    return proxy(ResultSet.class, result);
                }
                if (result instanceof Statement) {
                    return proxy(Statement.class, result);
                }
                return result;
            }
        };
        return (T) Proxy.newProxyInstance(JdbcSpy.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    /** A SQLException with a SQL state, for injected failures. */
    static SQLException sqlException(String message, String sqlState) {
        return new SQLException(message, sqlState, 4242);
    }

    @Override
    public String toString() {
        return "JdbcSpy" + calls;
    }
}
