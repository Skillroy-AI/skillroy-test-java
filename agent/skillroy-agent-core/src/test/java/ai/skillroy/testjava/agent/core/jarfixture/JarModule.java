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

package ai.skillroy.testjava.agent.core.jarfixture;

import ai.skillroy.testjava.agent.api.AgentContext;
import ai.skillroy.testjava.agent.api.AgentModule;
import ai.skillroy.testjava.agent.api.Invocation;
import ai.skillroy.testjava.agent.api.PrimitiveSpec;
import ai.skillroy.testjava.agent.api.Primitives;
import ai.skillroy.testjava.agent.core.fixture.TestState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A module the tests package into a jar at run time (never registered on the test class path): proves discovery from
 * {@code home/modules} and the {@code modules} option, and what a module loader can see.
 */
public class JarModule implements AgentModule {

    /** Id; a subclass changes it so that two jars can carry distinct modules. */
    protected String moduleId() {
        return "jarmod";
    }

    @Override
    public String id() {
        return moduleId();
    }

    @Override
    public String version() {
        return "jar";
    }

    @Override
    public void install(final AgentContext ctx) {
        TestState.JAR_LOADERS.add(getClass().getClassLoader());
        ctx.primitives().register(Primitives.of(PrimitiveSpec.builder(moduleId() + ".where").build(),
                new Primitives.Body() {
                    @Override
                    public Object invoke(Invocation inv) {
                        Map<String, Object> out = new LinkedHashMap<String, Object>();
                        ClassLoader own = JarModule.this.getClass().getClassLoader();
                        out.put("loader", own.getClass().getName());
                        out.put("apiShared", Boolean.valueOf(
                                AgentModule.class.getClassLoader() == AgentContext.class.getClassLoader()));
                        out.put("seesHostClass", Boolean.valueOf(canLoad(own,
                                "ai.skillroy.testjava.agent.core.fixture.TestState")));
                        out.put("hostContext", String.valueOf(ctx.hostContext()));
                        return out;
                    }
                }));
    }

    private static boolean canLoad(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
