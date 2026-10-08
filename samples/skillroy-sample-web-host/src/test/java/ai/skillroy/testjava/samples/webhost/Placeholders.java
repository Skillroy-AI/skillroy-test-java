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


package ai.skillroy.testjava.samples.webhost;

import org.cef.browser.CefBrowser;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Placeholder browsers for tests that need {@code CefBrowser} objects but no CEF: proxies that answer
 * {@code toString}, {@code equals} and {@code hashCode} (identity) and throw for everything else. Creating one loads
 * JCEF's interfaces, not its natives.
 */
final class Placeholders {

    private Placeholders() {
    }

    static CefBrowser browser(final String name) {
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(),
                new Class<?>[] {CefBrowser.class}, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("toString".equals(method.getName())) {
                            return "placeholder browser " + name;
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        throw new UnsupportedOperationException("placeholder browser " + name + ": " + method);
                    }
                });
    }
}
