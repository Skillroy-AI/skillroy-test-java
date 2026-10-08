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

package ai.skillroy.testjava.samples.webhost.scenarios;

import ai.skillroy.testjava.controller.AgentHome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where the end-to-end tests find and put things: the agent home and the web host's class path that the build
 * assembled below this module's {@code target/} before the integration tests, the natives' install directory (one per
 * platform, shared by every launch), a fresh Chromium cache directory per launch (below the tests' own temporary
 * directory, which in the Linux image is the container's file system, as Chromium needs it), per-launch discovery
 * directories, target logs and scenario outputs. No absolute path is configured anywhere but in the POM.
 */
final class ItEnv {

    private static final AtomicInteger DIRS = new AtomicInteger();
    private static volatile Integer targetFeature;
    private static boolean playwrightReady;

    private ItEnv() {
    }

    /** {@code target/} of this module ({@code skillroy.it.buildDir}, set by the POM). */
    static Path buildDir() {
        return Path.of(System.getProperty("skillroy.it.buildDir", "target")).toAbsolutePath().normalize();
    }

    /** This module's directory ({@code skillroy.it.moduleDir}). */
    static Path moduleDir() {
        return Path.of(System.getProperty("skillroy.it.moduleDir", ".")).toAbsolutePath().normalize();
    }

    /** {@code target/skillroy-agent}: the unpacked agent distribution with the web host module in {@code modules/}. */
    static AgentHome agentHome() {
        return AgentHome.of(buildDir().resolve("skillroy-agent"));
    }

    /**
     * The jars of {@code target/it-target}: the web host, jcefmaven and its dependencies, and the natives of the
     * platform the build names ({@code skillroy.it.jcefPlatform}); the natives of another platform, left there by an
     * earlier build for another image, are not on the class path.
     */
    static List<Path> hostClassPath() throws IOException {
        String natives = "jcef-natives-" + System.getProperty("skillroy.it.jcefPlatform", "") + "-";
        try (Stream<Path> jars = Files.list(buildDir().resolve("it-target"))) {
            return jars.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().startsWith("jcef-natives-")
                            || p.getFileName().toString().startsWith(natives))
                    .sorted().toList();
        }
    }

    /** Where jcefmaven extracts the natives ({@code skillroy.it.jcefInstall}): one directory per platform. */
    static Path jcefInstall() {
        return Path.of(System.getProperty("skillroy.it.jcefInstall", buildDir().resolve("jcef-bundle").toString()))
                .toAbsolutePath().normalize();
    }

    /** The target's {@code java}: {@code -Dskillroy.it.targetJava}, else this JVM's own. */
    static Path targetJava() {
        String configured = System.getProperty("skillroy.it.targetJava");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    /** The target Java's feature version (8, 25, ...), from its {@code -version} output; read once. */
    static int targetFeature() {
        Integer known = targetFeature;
        if (known != null) {
            return known;
        }
        try {
            Process p = new ProcessBuilder(targetJava().toString(), "-version").redirectErrorStream(true).start();
            p.getOutputStream().close();
            String text;
            try (InputStream in = p.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                text = out.toString(StandardCharsets.UTF_8);
            }
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException(targetJava() + " -version did not finish");
            }
            Matcher m = Pattern.compile("version \"(1\\.)?(\\d+)").matcher(text);
            if (!m.find()) {
                throw new IllegalStateException("no version in the output of " + targetJava() + " -version: " + text);
            }
            int feature = Integer.parseInt(m.group(2));
            targetFeature = feature;
            return feature;
        } catch (IOException e) {
            throw new IllegalStateException("cannot run " + targetJava() + " -version", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A fresh discovery directory for one launch, owner-only (contract amendment A3). */
    static Path newDiscoveryDir(String label) throws IOException {
        Path parent = buildDir().resolve("it-discovery");
        Files.createDirectories(parent);
        Path dir = parent.resolve(label + "-" + ProcessHandle.current().pid() + "-" + DIRS.incrementAndGet());
        Files.createDirectories(dir);
        ownerOnly(dir);
        return dir;
    }

    /**
     * A fresh Chromium cache directory for one launch, below this JVM's temporary directory (the container's own file
     * system in the Linux image; Chromium's cache must not be on the bind-mounted repository).
     */
    static Path newCacheDir(String label) throws IOException {
        return Files.createTempDirectory("webhost-it-cache-" + label + "-");
    }

    static void ownerOnly(Path path) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(Files.isDirectory(path)
                    ? "rwx------" : "rw-------"));
        }
    }

    /** Whether the launch with this label runs: {@code -Dskillroy.it.launches} empty (all) or naming it. */
    static boolean selected(String label) {
        String only = System.getProperty("skillroy.it.launches", "");
        return only.isBlank() || List.of(only.split(",")).stream().map(String::strip).anyMatch(label::equals);
    }

    /** Creates the directory Playwright extracts its driver into ({@code playwright.driver.tmpdir}), if set. */
    static void driverDirectory() throws IOException {
        String dir = System.getProperty("playwright.driver.tmpdir");
        if (dir != null && !dir.isBlank()) {
            Files.createDirectories(Path.of(dir));
        }
    }

    /**
     * Starts Playwright once in this JVM and closes it again, so that its driver (Node.js and its package, about
     * 140 MB) is extracted below {@code playwright.driver.tmpdir} before the first attach: the extraction would
     * otherwise count against that attach's deadline (30 s), which matters under x86-64 emulation. The time it took
     * is recorded once. Starts no browser ({@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1}, as the CDP path sets it).
     */
    static synchronized void warmUpPlaywright() throws IOException {
        if (playwrightReady) {
            return;
        }
        driverDirectory();
        long t0 = System.nanoTime();
        try (com.microsoft.playwright.Playwright playwright = com.microsoft.playwright.Playwright.create(
                new com.microsoft.playwright.Playwright.CreateOptions().setEnv(java.util.Map.of(
                        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            playwright.selectors();
        }
        playwrightReady = true;
        timing("java=" + System.getProperty("java.version") + " playwright driver extracted and started in "
                + (System.nanoTime() - t0) / 1_000_000L + " ms (below " + System.getProperty("playwright.driver.tmpdir")
                + ")");
    }

    /** The Node.js binary of the driver this JVM extracted ({@link #warmUpPlaywright}), or null. */
    static Path extractedNode() throws IOException {
        String dir = System.getProperty("playwright.driver.tmpdir");
        if (dir == null || dir.isBlank() || !Files.isDirectory(Path.of(dir))) {
            return null;
        }
        try (Stream<Path> files = Files.walk(Path.of(dir), 3)) {
            return files.filter(p -> p.getFileName().toString().equals("node") && Files.isExecutable(p))
                    .findFirst().orElse(null);
        }
    }

    /** {@code target/it-logs/<name>}. */
    static Path log(String name) {
        return buildDir().resolve("it-logs").resolve(name);
    }

    /** {@code target/it-runs/<launch>/<what>}: one directory per scenario run. */
    static Path runDir(String launch, String what) {
        return buildDir().resolve("it-runs").resolve(launch).resolve(what);
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

    /** {@code bin/skillroy} of the assembled launcher ({@code skillroy.it.cliDist}). */
    static Path launcher() {
        return Path.of(System.getProperty("skillroy.it.cliDist", "missing")).resolve("bin").resolve("skillroy");
    }

    /** The jars the launcher's {@code --pack} needs for the CDP path ({@code skillroy.it.cliCdp}). */
    static List<Path> cdpJars() throws IOException {
        try (Stream<Path> jars = Files.list(Path.of(System.getProperty("skillroy.it.cliCdp", "missing")))) {
            return jars.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
    }

    /** This module's own jar: the webhost pack ({@code skillroy.it.packJar}). */
    static Path packJar() {
        return Path.of(System.getProperty("skillroy.it.packJar", "missing"));
    }

    /** Appends one line to {@code target/it-logs/timings.txt} and prints it. */
    static synchronized void timing(String line) {
        System.out.println("[timing] " + line);
        try {
            Path file = log("timings.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[timing] could not write: " + e);
        }
    }
}
