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

import ai.skillroy.testjava.controller.AgentHome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where the end-to-end tests find and put things, all below this module's {@code target/} (no absolute path is ever
 * configured): the agent home and the target's class path that the build assembled before the integration tests,
 * per-launch discovery directories, target logs and scenario outputs.
 */
final class ItEnv {

    private static final AtomicInteger DISCOVERY_DIRS = new AtomicInteger();

    private ItEnv() {
    }

    /** {@code target/} of this module ({@code skillroy.it.buildDir}, set by the pom). */
    static Path buildDir() {
        return Path.of(System.getProperty("skillroy.it.buildDir", "target")).toAbsolutePath().normalize();
    }

    /** This module's directory ({@code skillroy.it.moduleDir}, set by the pom). */
    static Path moduleDir() {
        return Path.of(System.getProperty("skillroy.it.moduleDir", ".")).toAbsolutePath().normalize();
    }

    /** {@code target/skillroy-agent}: the unpacked agent distribution with the lending module in {@code modules/}. */
    static AgentHome agentHome() {
        return AgentHome.of(buildDir().resolve("skillroy-agent"));
    }

    /** {@code target/it-target/<name>.jar}. */
    static Path targetJar(String name) {
        return buildDir().resolve("it-target").resolve(name + ".jar");
    }

    /** The lending application and HSQLDB: the target's class path in every mode. */
    static List<Path> applicationClassPath() {
        return List.of(targetJar("skillroy-sample-lending-app"), targetJar("hsqldb"));
    }

    /** The target's {@code java}: {@code -Dskillroy.it.targetJava}, else this JVM's own. */
    static Path targetJava() {
        String configured = System.getProperty("skillroy.it.targetJava");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    /** A fresh discovery directory for one launch, owner-only (contract amendment A3). */
    static Path newDiscoveryDir(String label) throws IOException {
        Path parent = buildDir().resolve("it-discovery");
        Files.createDirectories(parent);
        Path dir = parent.resolve(label + "-" + ProcessHandle.current().pid() + "-"
                + DISCOVERY_DIRS.incrementAndGet());
        Files.createDirectories(dir);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        return dir;
    }

    /** {@code target/it-logs/<name>}. */
    static Path log(String name) {
        return buildDir().resolve("it-logs").resolve(name);
    }

    /** {@code target/it-runs/<mode>/<what>}: one directory per scenario run. */
    static Path runDir(String mode, String what) {
        return buildDir().resolve("it-runs").resolve(mode).resolve(what);
    }

    /** {@code scenarios/<file>} (committed). */
    static Path scenario(String file) {
        return moduleDir().resolve("scenarios").resolve(file);
    }

    /** {@code plan/<id>.plan.json} (committed). */
    static Path plan(String id) {
        return moduleDir().resolve("plan").resolve(id + ".plan.json");
    }

    /** {@code src/test/resources/negative/<file>}. */
    static Path negative(String file) {
        return moduleDir().resolve("src").resolve("test").resolve("resources").resolve("negative").resolve(file);
    }

    /** Appends one line to {@code target/it-logs/timings.txt} and prints it. */
    static synchronized void timing(String line) {
        System.out.println("[timing] " + line);
        try {
            Path file = log("timings.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[timing] could not write: " + e);
        }
    }
}
