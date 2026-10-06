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

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What a command runs with: the standard streams, the class loader domain packs are discovered with, and the
 * discovery directory used when {@code --discovery-dir} is not given. Passed explicitly (never read from
 * {@code System}) so that tests run commands in-process with captured output.
 *
 * <p>Immutable; the streams themselves are shared and must be written under their own lock when a command writes
 * from more than one thread.
 *
 * @param in standard input ({@code call ... -})
 * @param out human-readable results and {@code --json} output
 * @param err diagnostics, warnings and errors
 * @param classLoader the parent of {@code --pack} jars; packs on it are found with {@code ServiceLoader}
 * @param defaultDiscoveryDir where agents publish discovery files unless {@code --discovery-dir} says otherwise
 * @param terminal where {@code approve} asks (phase2-contracts.md 12.1)
 */
record Cli(InputStream in, PrintStream out, PrintStream err, ClassLoader classLoader, Path defaultDiscoveryDir,
           Terminal terminal) {

    /** Checks that nothing is null. */
    Cli {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(defaultDiscoveryDir, "defaultDiscoveryDir");
        Objects.requireNonNull(terminal, "terminal");
    }

    /** A context with the process's terminal ({@link Terminal#system()}). */
    Cli(InputStream in, PrintStream out, PrintStream err, ClassLoader classLoader, Path defaultDiscoveryDir) {
        this(in, out, err, classLoader, defaultDiscoveryDir, Terminal.system());
    }
}
