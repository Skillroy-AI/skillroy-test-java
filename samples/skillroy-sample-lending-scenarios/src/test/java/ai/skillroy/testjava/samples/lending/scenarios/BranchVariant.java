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

package ai.skillroy.testjava.samples.lending.scenarios;

import ai.skillroy.testjava.scenario.BindingsFile;
import ai.skillroy.testjava.scenario.DomainPacks;
import ai.skillroy.testjava.scenario.ScenarioException;
import ai.skillroy.testjava.scenario.pack.DomainPack;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The branch edition of the sample desk with the overlay {@code lending-branch} (phase6-contracts.md 11.3, 11.4): the
 * overlay's jar ({@code -Dskillroy.it.overlayJar}, the module's test dependency, kept off the test class path so that
 * the standard-edition tests keep the base pack) in a class loader of its own over the test class path, where
 * {@code DomainPacks.discover} applies it to the base pack; the variant's committed plans and bindings under
 * {@code variants/branch/}; its environment {@value #ENV} (seed A, branch edition).
 */
final class BranchVariant {

    /** The variant's environment id: seed A, branch edition. */
    static final String ENV = "branch-a";
    /** The desk's edition property and value. */
    static final String EDITION = "branch";
    /** The scenarios the variant runs (C's and D's recordings are keyed by prompt content: a live item, 13.4). */
    static final List<String> SCENARIOS = List.of(ScenarioB.ID, ScenarioA.ID, ScenarioE.ID);
    /** The overlaid pack's version, as the variant's plans record it. */
    static final String PACK_VERSION = "0.5.0+lending-branch@0.1.0";

    private static URLClassLoader loader;
    private static DomainPack pack;

    private BranchVariant() {
    }

    /** The overlay's jar (or class directory) from {@code -Dskillroy.it.overlayJar}. */
    static Path overlayJar() {
        String path = System.getProperty("skillroy.it.overlayJar");
        if (path == null || path.isBlank() || path.startsWith("${")) {
            throw new IllegalStateException("skillroy.it.overlayJar is not set: run through the module's surefire or"
                    + " failsafe configuration");
        }
        Path jar = Path.of(path);
        if (!Files.exists(jar)) {
            throw new IllegalStateException("the overlay's jar is missing: " + jar);
        }
        return jar;
    }

    /** The class loader of the overlay's jar over the test class path (open for the JVM's life). */
    static synchronized ClassLoader loader() {
        if (loader == null) {
            try {
                loader = new URLClassLoader("lending-branch", new URL[] {overlayJar().toUri().toURL()},
                        BranchVariant.class.getClassLoader());
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
        return loader;
    }

    /** Pack {@code lending} with the overlay applied, as discovery finds it with the overlay's jar beside the base's. */
    static synchronized DomainPack pack() throws ScenarioException {
        if (pack == null) {
            pack = DomainPacks.select("lending", DomainPacks.discover(loader()));
        }
        return pack;
    }

    static Path dir() {
        return ItEnv.moduleDir().resolve("variants/branch");
    }

    static Path plan(String id) {
        return dir().resolve("plan").resolve(id + ".plan.json");
    }

    static Path bindingsRoot() {
        return dir().resolve("bindings");
    }

    static Path committedBindings(String id) {
        return BindingsFile.locate(bindingsRoot(), ENV, id);
    }
}
