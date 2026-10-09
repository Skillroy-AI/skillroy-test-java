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


package ai.skillroy.testjava.fixture.hooks;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * A loader that delegates only {@code java.*} to its parent (BS3 RESULT section 9 fact 6): it cannot see a
 * {@code Spy} on the bootstrap path in the project's package, so a hook on one of its classes is refused at install.
 */
public class StrictDelegationLoader extends URLClassLoader {

    public StrictDelegationLoader(URL[] urls) {
        super(urls, null);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (name.startsWith("java.")) {
                    c = ClassLoader.getSystemClassLoader().loadClass(name);
                } else {
                    c = findClass(name);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        return name.startsWith("java/") ? ClassLoader.getSystemResource(name) : findResource(name);
    }
}
