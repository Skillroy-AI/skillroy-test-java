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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Runs the CLI in-process with captured standard streams. */
final class CliHarness {

    /** What one CLI run produced. */
    record Outcome(int exit, String out, String err) {

        /** Fails with both streams in the message unless the exit code is {@code expected}. */
        Outcome expectExit(int expected) {
            assertEquals(expected, exit, () -> "exit code; stdout:\n" + out + "\nstderr:\n" + err);
            return this;
        }

        /** Fails if {@code secret} appears on either stream. */
        Outcome never(String secret) {
            assertFalse(out.contains(secret), "stdout leaks a secret:\n" + out);
            assertFalse(err.contains(secret), "stderr leaks a secret:\n" + err);
            return this;
        }
    }

    private CliHarness() {
    }

    /** Runs {@code args} with empty standard input and {@code defaultDiscoveryDir} as the default directory. */
    static Outcome run(Path defaultDiscoveryDir, String... args) {
        return runWithInput(defaultDiscoveryDir, "", args);
    }

    /** As {@link #run}, with {@code stdin} as standard input. */
    static Outcome runWithInput(Path defaultDiscoveryDir, String stdin, String... args) {
        return runWithTerminal(defaultDiscoveryDir, stdin, Terminal.NONE, args);
    }

    /** As {@link #runWithInput}, with a scripted terminal (approve's question). */
    static Outcome runWithTerminal(Path defaultDiscoveryDir, String stdin, Terminal terminal, String... args) {
        return runWith(CliHarness.class.getClassLoader(), defaultDiscoveryDir, stdin, terminal, args);
    }

    /** As {@link #run}, with the packs found on {@code loader} instead of the test class path. */
    static Outcome runWithLoader(ClassLoader loader, Path defaultDiscoveryDir, String... args) {
        return runWith(loader, defaultDiscoveryDir, "", Terminal.NONE, args);
    }

    private static Outcome runWith(ClassLoader loader, Path defaultDiscoveryDir, String stdin, Terminal terminal,
                                   String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Cli cli = new Cli(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                loader, defaultDiscoveryDir, terminal);
        int exit = Main.run(List.of(args), cli);
        return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
