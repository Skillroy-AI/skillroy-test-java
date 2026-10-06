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

package ai.skillroy.testjava.samples.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hsqldb.jdbc.JDBCDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@code LendingApp.main} in a child JVM (the Java named by {@code skillroy.it.targetJava}, else this JVM's),
 * headless, with system properties and a plugin, the way an agent-side end-to-end test launches the lending desk.
 */
class LendingAppMainTest {

    /** The read-only account's password the child desks are given; test-local, any value works. */
    private static final String READER_PASSWORD = "Child-Reader-7315";

    /** A finished child JVM: its exit code, its output and the lines its plugin wrote (none if it wrote nothing). */
    private static final class Child {
        final int exit;
        final String log;
        final Path marker;
        final List<String> lines;

        Child(int exit, String log, Path marker, List<String> lines) {
            this.exit = exit;
            this.log = log;
            this.marker = marker;
            this.lines = lines;
        }
    }

    private static String location(Class<?> type) throws URISyntaxException {
        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @Test
    void mainStartsTheDeskFromSystemProperties(@TempDir Path dir) throws Exception {
        String java = java();
        List<String> lines = runChild(dir, MarkerPlugin.class, "-Dlending.seed=B", "-Dlending.title=Child Desk");
        assertTrue(lines.get(0).startsWith("started seed=B state=SIGNED_OFF title=Child Desk thread=LendingEngine"),
            lines.get(0));
        assertEquals("stopped", lines.get(1));
        System.out.println("[lending-app] child JVM " + java + ": " + lines.get(0));
    }

    /**
     * {@code -Dlending.dbPort=0 -Dlending.dbPassword=<password>}: the desk serves its database on a loopback port it
     * picks itself (so no other process can take the port between a test's choice and the desk's bind), reports it
     * ({@code dbServer()}) and logs it, and a client outside the desk's own code reads it as the read-only account with
     * that password and cannot write. The desk never logs the password.
     */
    @Test
    void mainServesTheDatabaseOnTheDbPort(@TempDir Path dir) throws Exception {
        Child child = startChild(dir, DbProbePlugin.class, "-Dlending.dbPort=0",
            "-Dlending.dbPassword=" + READER_PASSWORD);
        List<String> lines = assertExitedNormally(child);
        Matcher served = Pattern.compile("db staff=7 delete=refused 42501 server=LendingDbServer\\{127\\.0\\.0\\.1:"
            + "(\\d+)/lending\\}").matcher(lines.get(0));
        assertTrue(served.matches(), lines.get(0));
        int port = Integer.parseInt(served.group(1));
        assertTrue(port > 0 && port <= 65535, "the port the desk chose: " + port);
        assertTrue(child.log.contains("serving the database read-only on jdbc:hsqldb:hsql://127.0.0.1:" + port
            + "/lending (user reader)"), "the desk logs the port it chose:\n" + child.log);
        assertFalse(child.log.contains(READER_PASSWORD), "the desk never logs the password");
        assertEquals("stopped", lines.get(1));
    }

    /**
     * Contract 13.1: a start with {@code lending.dbPort} but without {@code lending.dbPassword} fails, naming the
     * property, before anything is created: {@code main} ends with the exception, the plugins never start (they start
     * last), and no listener was opened.
     */
    @Test
    void mainRefusesADbPortWithoutThePassword(@TempDir Path dir) throws Exception {
        Child child = startChild(dir, DbProbePlugin.class, "-Dlending.dbPort=0");
        assertEquals(1, child.exit, child.log);
        assertTrue(child.log.contains("java.lang.IllegalArgumentException: lending.dbPassword is required when"
            + " lending.dbPort is set"), child.log);
        assertFalse(Files.exists(child.marker), "no plugin started:\n" + child.log);
        assertFalse(child.log.contains("serving the database"), "no listener was opened:\n" + child.log);
    }

    private static String java() {
        String target = System.getProperty("skillroy.it.targetJava");
        return target != null && !target.isEmpty() ? target
            : Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Runs the desk's {@code main} headless with the plugin and properties; returns the plugin's marker lines. */
    private static List<String> runChild(Path dir, Class<?> plugin, String... properties) throws Exception {
        return assertExitedNormally(startChild(dir, plugin, properties));
    }

    /** The child exited 0 and its plugin wrote two lines: they are returned. */
    private static List<String> assertExitedNormally(Child child) {
        assertEquals(0, child.exit, child.log);
        assertEquals(2, child.lines.size(), child.lines + "\n" + child.log);
        return child.lines;
    }

    /** Runs the desk's {@code main} headless with the plugin and properties, and waits for the JVM to end. */
    private static Child startChild(Path dir, Class<?> plugin, String... properties) throws Exception {
        String java = java();
        Path marker = dir.resolve("marker.txt");
        String classPath = location(LendingApp.class) + File.pathSeparator + location(plugin)
            + File.pathSeparator + location(JDBCDriver.class);
        List<String> command = new ArrayList<String>();
        command.add(java);
        command.add("-Djava.awt.headless=true");
        Collections.addAll(command, properties);
        command.add("-Dlending.plugins=" + plugin.getName());
        command.add("-Dlending.test.marker=" + marker);
        command.add("-cp");
        command.add(classPath);
        command.add(LendingApp.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(dir.resolve("child.log").toFile()).start();
        boolean exited = process.waitFor(180, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String log = new String(Files.readAllBytes(dir.resolve("child.log")), StandardCharsets.UTF_8);
        assertTrue(exited, "child JVM did not exit; log:\n" + log);
        List<String> lines = Files.exists(marker) ? Files.readAllLines(marker, StandardCharsets.UTF_8)
            : Collections.<String>emptyList();
        return new Child(process.exitValue(), log, marker, lines);
    }
}
