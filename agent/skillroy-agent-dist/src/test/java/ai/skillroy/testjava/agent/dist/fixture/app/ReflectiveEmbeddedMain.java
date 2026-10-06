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

package ai.skillroy.testjava.agent.dist.fixture.app;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Properties;

/**
 * A host plugin that starts the agent by name, the way contract amendment A6 allows: it does not link against the
 * bootstrap, so it runs whether or not the bootstrap jar is there. It calls {@code EmbeddedAgent.start} twice, with
 * different host contexts. Arguments: the discovery directory, the module jar for the {@code modules} option, and
 * the bootstrap jar to load in a loader of its own (whose parent is the platform loader, so that it is a separate
 * copy), or {@code -} to look the class up on the class path.
 */
public final class ReflectiveEmbeddedMain {

    private static final String EMBEDDED_AGENT = "ai.skillroy.testjava.agent.bootstrap.EmbeddedAgent";

    private ReflectiveEmbeddedMain() {
    }

    public static void main(String[] args) throws IOException {
        ClassLoader appLoader = ReflectiveEmbeddedMain.class.getClassLoader();
        try {
            // A loader of the plugin's own, isolated from the class path: a separate copy of the bootstrap even when
            // the JVM also runs it as a -javaagent.
            ClassLoader loader = "-".equals(args[2]) ? appLoader
                    : new URLClassLoader(new URL[] {new File(args[2]).toURI().toURL()}, appLoader.getParent());
            Class<?> agent = Class.forName(EMBEDDED_AGENT, true, loader);
            Method start = agent.getMethod("start", Properties.class, ClassLoader.class, Object.class);
            Properties options = new Properties();
            options.setProperty("enabled", "true");
            options.setProperty("discoveryDir", args[0]);
            options.setProperty("modules", args[1]);
            Object first = start.invoke(null, options, appLoader, new HostThing("the reflective host"));
            Object again = start.invoke(null, options, appLoader, new HostThing("a second call"));
            System.out.println("embedded=" + first);
            System.out.println("again=" + again);
        } catch (ClassNotFoundException e) {
            System.out.println("embedded=absent");
        } catch (Exception e) {
            System.out.println("embedded=failed " + e);
        }
        ParkMain.report();
        ParkMain.park();
    }
}
