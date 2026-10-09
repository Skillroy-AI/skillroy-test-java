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

package ai.skillroy.testjava.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The agent home directory produced by {@code skillroy-agent-dist} (contract §2.1): the bootstrap jar that
 * {@code -javaagent} and attach load, {@code lib/} with the agent's own jars, and {@code modules/}; from Phase 6 also
 * the spy jar at its root and {@code optional/} (phase6-contracts.md 2.1). Validated once
 * here so that a misconfigured path fails with a clear message before a target is launched or attached.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class AgentHome {

    /** File name of the bootstrap jar inside the home; versionless by design (contract §2.1). */
    public static final String BOOTSTRAP_JAR = "skillroy-agent-bootstrap.jar";

    /** File name of the spy jar at the home's root (phase6-contracts.md 2.1). */
    public static final String SPY_JAR = "skillroy-agent-spy.jar";

    /** The directory of optional module jars, never scanned (phase6-contracts.md 2.1). */
    public static final String OPTIONAL_DIR = "optional";

    /** The hooks module's file stem under {@code optional/}, as the agent option {@code optionalModules} names it. */
    public static final String HOOKS_MODULE = "skillroy-agent-hooks";

    /** System property naming the agent home. */
    public static final String PROPERTY = "skillroy.agent.home";

    /** Environment variable naming the agent home, consulted when the system property is not set. */
    public static final String ENVIRONMENT = "SKILLROY_AGENT_HOME";

    private final Path dir;

    private AgentHome(Path dir) {
        this.dir = dir;
    }

    /**
     * The agent home named by the system property {@code skillroy.agent.home}, else by the environment variable
     * {@code SKILLROY_AGENT_HOME}.
     *
     * @throws IllegalStateException if neither is set, or the directory they name is not a valid agent home
     */
    public static AgentHome locate() {
        return locate(System::getProperty, System::getenv);
    }

    /** {@link #locate()} with injectable lookups (tests). */
    static AgentHome locate(UnaryOperator<String> properties, UnaryOperator<String> environment) {
        String fromProperty = properties.apply(PROPERTY);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return located(fromProperty, "system property " + PROPERTY);
        }
        String fromEnvironment = environment.apply(ENVIRONMENT);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return located(fromEnvironment, "environment variable " + ENVIRONMENT);
        }
        throw new IllegalStateException("no agent home configured: set -D" + PROPERTY + "=<dir> or " + ENVIRONMENT
                + " to the skillroy-agent directory built by skillroy-agent-dist");
    }

    private static AgentHome located(String value, String source) {
        try {
            return of(Path.of(value.strip()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(source + " does not name a valid agent home: " + e.getMessage(), e);
        }
    }

    /**
     * Validates {@code dir} as an agent home.
     *
     * @throws IllegalArgumentException if {@code dir} is not a directory, has no {@code skillroy-agent-bootstrap.jar}
     *     or has no {@code lib/} directory
     */
    public static AgentHome of(Path dir) {
        Objects.requireNonNull(dir, "dir");
        Path absolute = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("agent home is not a directory: " + absolute);
        }
        if (!Files.isRegularFile(absolute.resolve(BOOTSTRAP_JAR))) {
            throw new IllegalArgumentException("agent home has no " + BOOTSTRAP_JAR + ": " + absolute);
        }
        if (!Files.isDirectory(absolute.resolve("lib"))) {
            throw new IllegalArgumentException("agent home has no lib/ directory: " + absolute);
        }
        return new AgentHome(absolute);
    }

    /** The absolute path of {@code skillroy-agent-bootstrap.jar}. */
    public Path bootstrapJar() {
        return dir.resolve(BOOTSTRAP_JAR);
    }

    /** The absolute, normalised home directory. */
    public Path dir() {
        return dir;
    }

    /** {@code modules/}, where module jars are dropped (it may not exist). */
    public Path modulesDir() {
        return dir.resolve("modules");
    }

    /**
     * {@code optional/} (phase6-contracts.md 2.1, decision D114): jars the agent never scans, each loaded as a module
     * jar only when the agent option {@code optionalModules} names its file stem. It may not exist.
     */
    public Path optionalDir() {
        return dir.resolve(OPTIONAL_DIR);
    }

    /**
     * The path of {@code optional/<stem>.jar}, whether or not the file exists
     * ({@code optionalModule("skillroy-agent-hooks")} is the hooks module that the option
     * {@code optionalModules=skillroy-agent-hooks} loads).
     *
     * @throws IllegalArgumentException if {@code stem} is blank or names a path rather than a file stem
     */
    public Path optionalModule(String stem) {
        if (stem == null || stem.isBlank() || stem.contains("/") || stem.contains("\\") || stem.equals(".")
                || stem.equals("..")) {
            throw new IllegalArgumentException("an optional module is named by its file stem, found \"" + stem + "\"");
        }
        return optionalDir().resolve(stem + ".jar");
    }

    /**
     * {@code skillroy-agent-spy.jar} at the home's root (phase6-contracts.md 2.1, decision D116): the jar the hooks
     * module appends to the target's bootstrap search path at its first {@code hook.install}. It may not exist (a home
     * older than Phase 6).
     */
    public Path spyJar() {
        return dir.resolve(SPY_JAR);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AgentHome other && dir.equals(other.dir);
    }

    @Override
    public int hashCode() {
        return dir.hashCode();
    }

    @Override
    public String toString() {
        return "AgentHome[" + dir + "]";
    }
}
