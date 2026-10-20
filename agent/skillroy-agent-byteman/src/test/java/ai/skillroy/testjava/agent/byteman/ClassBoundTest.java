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
 * and for a class of the JDK's or the agent's loaders. Phase 8 (phase8-contracts.md 6.7, section 13.1 row 25): the
 * class deny refuses a rule's name before the count, on every path, and a denied matched subtype.
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

    /**
     * Phase 6 security fix, pass 2, P2-L2-F1: a rule naming a JDK class or interface outside the lint's prefixes is
     * refused by name whether or not the target has loaded it (the loader rule sees loaded classes only, and Byteman
     * would inject into the JDK class at its first load, which the bootstrap loader cannot link), on the {@code CLASS},
     * {@code ^} and {@code INTERFACE} paths alike, even when an application implementor is loaded; a name in a jar of
     * the agent's own loaders is refused likewise; a rule on an application class the target has not loaded yet is
     * still accepted (7.3's "type check skipped: class not loaded").
     */
    @Test
    void aJdkNameIsRefusedByNameAndAnUnloadedApplicationNameIsAccepted() throws Exception {
        ClassBound bound = new ClassBound(jvm(true, DepositDesk.class, BranchDesk.class), Settings.defaults(), none());
        for (String line : Arrays.asList("CLASS org.xml.sax.helpers.LocatorImpl", "CLASS ^org.xml.sax.helpers.DefaultHandler",
                "INTERFACE org.xml.sax.ErrorHandler", "CLASS org.w3c.dom.Node", "CLASS org.ietf.jgss.GSSName")) {
            ClassBound.Result r = bound.check(rule(line));
            assertFalse(r.ok(), line);
            String name = line.substring(line.indexOf(' ') + 1).replace("^", "");
            assertEquals(Collections.singletonList(name + ": class is the JDK's"), r.errors, line);
            assertTrue(r.matched.isEmpty(), "refused before anything is matched");
        }
        assertNull(bound.nameProblem("ai.skillroy.testjava.fixture.byteman.NotLoadedYet"),
                "an application class the target has not loaded is not the JDK's");
        assertTrue(bound.check(rule("CLASS ai.skillroy.testjava.fixture.byteman.NotLoadedYet")).ok());
        assertTrue(bound.check(rule("CLASS " + DESK)).ok(), "the application's loaded class");

        URL classes = DepositDesk.class.getProtectionDomain().getCodeSource().getLocation();
        Set<ClassLoader> chain = none();
        try (URLClassLoader agents = new URLClassLoader(new URL[] {classes}, null)) {
            chain.add(agents);
            ClassBound own = new ClassBound(jvm(true), Settings.defaults(), chain);
            assertEquals("class is the agent's own", own.nameProblem("ai.skillroy.testjava.fixture.byteman.AgentLoaded"),
                    "a class file in a jar of the agent's own loaders");
            assertNull(own.nameProblem("ai.skillroy.testjava.fixture.byteman.NotLoadedYet"));
        }
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

    // ---- Phase 8 (phase8-contracts.md 6.7) ----

    /** A deny view refusing the names it holds, with the reason the engine would give. */
    private static ClassBound.Deny denying(final String... names) {
        return new ClassBound.Deny() {
            @Override
            public String refuse(String className) {
                return Arrays.asList(names).contains(className)
                        ? "denied by the agent option hooks.deny: " + className : null;
            }
        };
    }

    @Test
    void aDeniedNameIsRefusedBeforeTheCountAndTheClassListIsNeverRead() {
        final int[] reads = {0};
        ClassBound.Jvm counting = new ClassBound.Jvm() {
            @Override
            public Class<?>[] loadedClasses() {
                reads[0]++;
                return new Class<?>[] {DepositDesk.class, BranchDesk.class};
            }

            @Override
            public boolean isModifiable(Class<?> c) {
                return true;
            }
        };
        String ceilings = "ai.skillroy.testjava.fixture.byteman.Ceilings";
        ClassBound bound = new ClassBound(counting, Settings.defaults(), none(), denying(DESK, ceilings));
        for (String line : Arrays.asList("CLASS " + DESK, "CLASS ^" + DESK, "INTERFACE " + ceilings)) {
            ClassBound.Result r = bound.check(rule(line));
            String name = line.substring(line.indexOf(' ') + 1).replace("^", "");
            assertEquals(Collections.singletonList(name + ": denied by the agent option hooks.deny: " + name),
                    r.errors, line);
            assertTrue(r.matched.isEmpty(), "refused before anything is matched");
        }
        assertEquals(0, reads[0], "the class list is never read for a denied name");
        assertTrue(new ClassBound(counting, Settings.defaults(), none(), denying("x.Other")).check(
                rule("CLASS " + DESK)).ok());
        assertEquals(1, reads[0]);
    }

    @Test
    void aDeniedMatchedSubtypeOfAnAllowedRuleIsRefused() {
        String branch = BranchDesk.class.getName();
        ClassBound bound = new ClassBound(jvm(true, DepositDesk.class, BranchDesk.class), Settings.defaults(), none(),
                denying(branch));
        ClassBound.Result r = bound.check(rule("CLASS ^" + DESK));
        assertEquals(Arrays.<Class<?>>asList(DepositDesk.class, BranchDesk.class), r.matched);
        assertEquals(Collections.singletonList(branch + ": denied by the agent option hooks.deny: " + branch),
                r.errors);
        assertEquals("denied by the agent option hooks.deny: " + branch, bound.problem(BranchDesk.class));
        assertNull(bound.problem(DepositDesk.class));
        ClassBound.Result byInterface = bound.check(rule("INTERFACE ai.skillroy.testjava.fixture.byteman.Ceilings"));
        assertEquals(Collections.singletonList(branch + ": denied by the agent option hooks.deny: " + branch),
                byInterface.errors);
    }
}
