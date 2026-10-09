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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.fixture.hooks.ChildFirstLoader;

import java.io.File;
import java.net.URL;

/**
 * The hooks module as the agent loads it: in a child-first loader of its own over the module's classes and the
 * relocated Byte Buddy (the core's {@code ModuleClassLoader} rule: the agent API, the protocol and the {@code Spy}
 * package from the parent), so that what it leaves in the shared loaders, and whether its loader is collectable, can
 * be seen.
 */
final class ModuleLoaderFixture {

    private ModuleLoaderFixture() {
    }

    static ChildFirstLoader loader() throws Exception {
        URL classes = new File(System.getProperty("skillroy.hooks.classes", "target/classes")).toURI().toURL();
        URL bytebuddy = new File(System.getProperty("skillroy.hooks.bytebuddyJar")).toURI().toURL();
        return new ChildFirstLoader(new URL[] {classes, bytebuddy}, ModuleLoaderFixture.class.getClassLoader(),
                "ai.skillroy.testjava.agent.hooks.");
    }

    static AgentModule module(ClassLoader loader) throws Exception {
        Class<?> type = Class.forName(HooksModule.class.getName(), true, loader);
        if (type == HooksModule.class) {
            throw new IllegalStateException("the module was not loaded by its own loader");
        }
        return (AgentModule) type.getDeclaredConstructor().newInstance();
    }
}
