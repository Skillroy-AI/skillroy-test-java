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

package ai.skillroy.testjava.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the assembled {@code bin/skillroy} as a separate process (integration tests): output goes to files, so a
 * chatty command can never block on a full pipe, and every run is bounded.
 */
final class Launcher {

    /** Generous: the Linux container runs x86-64 under emulation, and attach waits for a 5 s old target. */
    static final long DEADLINE_S = 300;

    private static final AtomicInteger RUNS = new AtomicInteger();

    /** What one launcher run produced. */
    record Result(int exit, String out, String err) {

        Result expectExit(int expected) {
            assertEquals(expected, exit, () -> "exit code; stdout:\n" + out + "\nstderr:\n" + err);
            return this;
        }
    }

    private Launcher() {
    }

    /** The assembled distribution directory ({@code target/skillroy-cli}). */
    static Path dist() {
        return Path.of(System.getProperty("skillroy.cli.it.dist", "target/skillroy-cli")).toAbsolutePath();
    }

    /** {@code bin/skillroy} in the distribution. */
    static Path script() {
        return dist().resolve("bin").resolve("skillroy");
    }

    /** This JVM's Java home: a Java 25+ runtime for the launcher. */
    static String javaHome() {
        return System.getProperty("java.home");
    }

    /** Runs {@code bin/skillroy} with JAVA_HOME set to this JVM's home. */
    static Result run(Path work, String... args) throws IOException, InterruptedException {
        return run(work, env -> env.put("JAVA_HOME", javaHome()), script(), args);
    }

    /**
     * Runs {@code launcher} with the given arguments.
     *
     * @param environment adjusts the inherited environment
     */
    static Result run(Path work, Consumer<Map<String, String>> environment, Path launcher, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.addAll(List.of(args));
        int n = RUNS.incrementAndGet();
        Path out = work.resolve("launcher-" + n + ".out");
        Path err = work.resolve("launcher-" + n + ".err");
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE);
        environment.accept(builder.environment());
        Process process = builder.start();
        process.getOutputStream().close();
        boolean exited = process.waitFor(DEADLINE_S, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertTrue(exited, "bin/skillroy " + String.join(" ", args) + " did not finish within " + DEADLINE_S
                + " s; stderr so far:\n" + Files.readString(err, StandardCharsets.UTF_8));
        return new Result(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
    }
}
