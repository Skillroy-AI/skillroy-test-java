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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the assembled launcher ({@code bin/skillroy}) as a child process with this JVM's Java as its
 * {@code JAVA_HOME}, its output in files below {@code logs}; at most {@value #TIMEOUT_S} s per command. Playwright's
 * driver, when a command loads the CDP path, is extracted below the directory {@code playwright.driver.tmpdir} names
 * (handed on as {@code PLAYWRIGHT_DRIVER_TMPDIR}), with the Node.js binary this JVM extracted already
 * ({@code PLAYWRIGHT_NODEJS_PATH}).
 */
final class Cli {

    static final long TIMEOUT_S = 300;

    /** A command's exit code and output. */
    record Result(int exit, String out, String err) {
        @Override
        public String toString() {
            return "exit " + exit + "\nstdout:\n" + out + "\nstderr:\n" + err;
        }
    }

    private Cli() {
    }

    static Result run(Path launcher, List<String> args, Path logs) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.addAll(args);
        Files.createDirectories(logs);
        Path stdout = Files.createTempFile(logs, "cli", ".out");
        Path stderr = Files.createTempFile(logs, "cli", ".err");
        ProcessBuilder builder = new ProcessBuilder(command).directory(logs.toFile()).redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile()).redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        String driverDir = System.getProperty("playwright.driver.tmpdir");
        if (driverDir != null) {
            Files.createDirectories(Path.of(driverDir));
            builder.environment().put("PLAYWRIGHT_DRIVER_TMPDIR", driverDir);
        }
        // The Node.js binary this JVM's Playwright extracted: the launcher's Playwright then extracts its package
        // only (a few MB instead of about 140), which keeps the launcher's first attach well inside its deadline.
        Path node = ItEnv.extractedNode();
        if (node != null) {
            builder.environment().put("PLAYWRIGHT_NODEJS_PATH", node.toString());
        }
        Process process = builder.start();
        process.getOutputStream().close();
        boolean exited = process.waitFor(TIMEOUT_S, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertTrue(exited, "bin/skillroy " + args.get(0) + " did not finish within " + TIMEOUT_S + " s");
        return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }
}
