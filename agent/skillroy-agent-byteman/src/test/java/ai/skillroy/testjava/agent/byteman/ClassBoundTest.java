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

package ai.skillroy.testjava.agent.byteman;

import ai.skillroy.testjava.fixture.byteman.BranchDesk;
import ai.skillroy.testjava.fixture.byteman.DepositDesk;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The class bound of a rule (phase6-contracts.md 7.3, D124): matched by name, by supertype for {@code ^}, by interface
 * for {@code INTERFACE}; refused above {@code hooks.maxClasses}, above the byte and method bounds, when not modifiable,
 * and for a class of the JDK's or the agent's loaders.
 */
class ClassBoundTest {

    static final String DESK = DepositDesk.class.getName();

    /** A loader whose class is under the agent's prefix: the bound recognises the agent's loaders by it. */
    static final class AgentNamedLoader extends URLClassLoader {
        AgentNamedLoader(URL[] urls) {
            super(urls, null);
        }
    }

    private static ClassBound.Jvm jvm(final boolean modifiable, final Class<?>... loaded) {
        return new ClassBound.Jvm() {
            @Override
            public Class<?>[] loadedClasses() {
                return loaded;
            }

            @Override
            public boolean isModifiable(Class<?> c) {
                return modifiable;
            }
        };
    }

    private static Set<ClassLoader> none() {
        return Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
    }

    private static RuleLint.Result rule(String classLine) {
        RuleLint.Result r = RuleLint.check("RULE r\n" + classLine + "\nMETHOD ceiling\nAT ENTRY\nIF true\n"
                + "DO fired($0)\nENDRULE\n");
        assertTrue(r.ok(), r.errors().toString());
        return r;
    }

    @Test
    void matchingByNameBySupertypeAndByInterface() {
        Class<?>[] loaded = {DepositDesk.class, BranchDesk.class, String.class, Object.class};
        ClassBound bound = new ClassBound(jvm(true, loaded), Settings.defaults(), none());
        assertEquals(Collections.<Class<?>>singletonList(DepositDesk.class),
                bound.check(rule("CLASS " + DESK)).matched);
        assertEquals(Arrays.<Class<?>>asList(DepositDesk.class, BranchDesk.class),
                bound.check(rule("CLASS ^" + DESK)).matched);
        assertEquals(Arrays.<Class<?>>asList(DepositDesk.class, BranchDesk.class),
                bound.check(rule("INTERFACE ai.skillroy.testjava.fixture.byteman.Ceilings")).matched);
        assertTrue(bound.check(rule("CLASS " + DESK)).ok());
    }

    @Test
    void tooManyClassesAreRefused() {
        Settings one = new Settings(null, 1, 262144L, 2000, 8000L, 16384);
        ClassBound.Result r = new ClassBound(jvm(true, DepositDesk.class, BranchDesk.class), one, none())
                .check(rule("CLASS ^" + DESK));
        assertFalse(r.ok());
        assertEquals("rule would retransform 2 classes, more than hooks.maxClasses (1)", r.errors.get(0));
    }

    @Test
    void theSizeAndModifiableBoundsRefuse() {
        Settings smallMethods = new Settings(null, 64, 262144L, 2, 8000L, 16384);
        ClassBound.Result methods = new ClassBound(jvm(true, DepositDesk.class), smallMethods, none())
                .check(rule("CLASS " + DESK));
        assertTrue(methods.errors.get(0).contains("class too large: 4 methods > 2"), methods.errors.toString());
        Settings smallBytes = new Settings(null, 64, 100L, 2000, 8000L, 16384);
        ClassBound.Result bytes = new ClassBound(jvm(true, DepositDesk.class), smallBytes, none())
                .check(rule("CLASS " + DESK));
        assertTrue(bytes.errors.get(0).contains("bytes > 100"), bytes.errors.toString());
        ClassBound.Result fixed = new ClassBound(jvm(false, DepositDesk.class), Settings.defaults(), none())
                .check(rule("CLASS " + DESK));
        assertTrue(fixed.errors.get(0).endsWith("class is not modifiable"), fixed.errors.toString());
    }

    @Test
    void theJdksAndTheAgentsLoadersAreRefused() throws Exception {
        ClassBound bound = new ClassBound(jvm(true), Settings.defaults(), none());
        assertEquals("class is the agent's own or the JDK's", bound.problem(String.class));
        URL classes = DepositDesk.class.getProtectionDomain().getCodeSource().getLocation();
        // A fixture of its own (its copy stays loaded): a rule another test submits in this JVM must not match it.
        String loaded = "ai.skillroy.testjava.fixture.byteman.AgentLoaded";
        try (AgentNamedLoader agentLoader = new AgentNamedLoader(new URL[] {classes})) {
            Class<?> copy = agentLoader.loadClass(loaded);
            assertTrue(copy.getClassLoader() == agentLoader);
            assertEquals("class is the agent's own or the JDK's", bound.problem(copy),
                    "a loader whose class is under ai.skillroy.testjava.agent. is the agent's");
            Set<ClassLoader> chain = none();
            URLClassLoader plain = new URLClassLoader(new URL[] {classes}, null);
            chain.add(plain);
            assertEquals("class is the agent's own or the JDK's",
                    new ClassBound(jvm(true), Settings.defaults(), chain).problem(plain.loadClass(loaded)),
                    "a loader of the module's own chain");
            plain.close();
        }
        assertNull(bound.problem(DepositDesk.class), "the application's system loader is fine");
        assertTrue(ClassBound.chainOf(ClassLoader.getSystemClassLoader()).isEmpty());
    }
}
