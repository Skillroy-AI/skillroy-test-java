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

package ai.skillroy.testjava.agent.web.reflect;

import ai.skillroy.testjava.agent.web.kit.RecordingClassLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "A class that is not yet loaded by the host counts as absent" (phase4-contracts.md section 3): the question is
 * answered without loading the class, by the means the JVM allows; a loader that can reach a class but has not loaded
 * it answers no and defines nothing.
 */
class LoadedClassesTest {

    private static final String SAMPLE = "ai.skillroy.testjava.agent.web.reflect.Sample";

    @Test
    void withoutInstrumentationAMeansWithoutWarningsIsChosen() {
        LoadedClasses lc = new LoadedClasses(null);
        assertNotEquals(LoadedClasses.Means.INSTRUMENTATION, lc.means());
        if (LoadedClasses.beforeJava9()) {
            assertEquals(LoadedClasses.Means.FIND_LOADED_CLASS, lc.means(), "Java 8 asks findLoadedClass");
        } else {
            assertTrue(lc.means() == LoadedClasses.Means.DEFINED_PACKAGE
                    || lc.means() == LoadedClasses.Means.FIND_LOADED_CLASS, String.valueOf(lc.means()));
        }
    }

    @Test
    void aClassTheHostLoadedIsLoadedAndOneItCannotReachIsNot() {
        LoadedClasses lc = new LoadedClasses(null);
        ClassLoader here = LoadedClassesTest.class.getClassLoader();
        assertTrue(new Sample().name.length() > 0);
        assertTrue(lc.isLoaded(SAMPLE, here));
        assertFalse(lc.isLoaded("com.example.never.Loaded", here));
        assertFalse(lc.isLoaded(null, here));
        assertTrue(LoadedClasses.isReachable(SAMPLE, here));
        assertFalse(LoadedClasses.isReachable("com.example.never.Loaded", here));
    }

    @Test
    void aLoaderThatCanReachAClassButHasNotLoadedItAnswersNoAndDefinesNothing() throws Exception {
        LoadedClasses lc = new LoadedClasses(null);
        ClassLoader here = LoadedClassesTest.class.getClassLoader();
        RecordingClassLoader pristine = new RecordingClassLoader(here, here, SAMPLE);
        assertTrue(LoadedClasses.isReachable(SAMPLE, pristine));
        assertFalse(lc.isLoaded(SAMPLE, pristine), "not loaded by that loader");
        assertTrue(pristine.defined().isEmpty(), "asking loaded nothing: " + pristine.defined());
        Class.forName(SAMPLE, false, pristine);
        assertEquals(1, pristine.defined().size());
        assertTrue(lc.isLoaded(SAMPLE, pristine), "loaded now");
    }
}
