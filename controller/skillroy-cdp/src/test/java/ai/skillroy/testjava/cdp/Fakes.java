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

package ai.skillroy.testjava.cdp;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Fakes of Playwright's interfaces as {@link Proxy} instances, so that the CDP path's glue runs in unit tests without a
 * browser or the Playwright driver. Each fake answers the methods named in its answer map by signature ({@code
 * "name(SimpleType,...)"}, for example {@code "evaluate(String,Object)"}); an interface's default method without an
 * answer runs its own body; any other method fails the test. {@code equals} is identity.
 */
final class Fakes {

    /** Answers one method. */
    @FunctionalInterface
    interface Answer {
        Object answer(Object[] args) throws Throwable;
    }

    private Fakes() {
    }

    static <T> T fake(Class<T> type, Map<String, Answer> answers) {
        InvocationHandler handler = (proxy, method, args) -> {
            Object[] a = args == null ? new Object[0] : args;
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "fake " + type.getSimpleName() + "@" + Integer.toHexString(
                            System.identityHashCode(proxy));
                };
            }
            Answer answer = answers.get(signature(method));
            if (answer != null) {
                try {
                    return answer.answer(a);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw new UnsupportedOperationException("the fake " + type.getSimpleName() + " has no answer for "
                    + signature(method));
        };
        return type.cast(Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {type}, handler));
    }

    static String signature(Method method) {
        StringJoiner params = new StringJoiner(",", method.getName() + "(", ")");
        for (Class<?> p : method.getParameterTypes()) {
            params.add(p.getSimpleName());
        }
        return params.toString();
    }
}
