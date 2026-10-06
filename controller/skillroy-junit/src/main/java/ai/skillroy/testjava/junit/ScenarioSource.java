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

package ai.skillroy.testjava.junit;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Where {@link ScenarioTests} finds scenario YAML files: a directory of {@code *.yaml}, or class path resources (single
 * files or directories, in a directory or a jar).
 *
 * <p>Implementations must be safe to call from the thread that builds the dynamic tests; {@link #entries()} may be
 * called more than once and reads the files each time.
 */
public interface ScenarioSource {

    /**
     * One scenario file.
     *
     * @param name the file name, used to sort and in messages
     * @param uri where it came from ({@code file:} or {@code classpath:}), shown by IDEs as the test source
     * @param content the YAML bytes (the plan's {@code sourceHash} is computed from them)
     */
    record Entry(String name, URI uri, byte[] content) {

        /** Checks the components. */
        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * The scenario files, sorted by name.
     *
     * @throws IOException if the directory or a resource cannot be read
     */
    List<Entry> entries() throws IOException;

    /**
     * The directory the scenario files are in, or null for a source without one (class path resources). The JUnit
     * glue's default bindings root is {@code bindings} in its parent (phase2-contracts.md 9, decision D31).
     */
    default Path scenarioDirectory() {
        return null;
    }

    /** Every {@code *.yaml} and {@code *.yml} file directly inside {@code dir} (not recursive). */
    static ScenarioSource directory(Path dir) {
        return new Sources.Directory(dir);
    }

    /**
     * Class path resources, looked up with the thread's context class loader. Each name is a {@code .yaml}/{@code .yml}
     * resource, or a directory whose {@code *.yaml}/{@code *.yml} files (not recursive) are all taken, for example
     * {@code classpath("scenarios")}.
     */
    static ScenarioSource classpath(String... resources) {
        return classpath(Thread.currentThread().getContextClassLoader(), resources);
    }

    /** As {@link #classpath(String...)} with an explicit class loader. */
    static ScenarioSource classpath(ClassLoader loader, String... resources) {
        return new Sources.Classpath(loader, List.of(resources));
    }
}
