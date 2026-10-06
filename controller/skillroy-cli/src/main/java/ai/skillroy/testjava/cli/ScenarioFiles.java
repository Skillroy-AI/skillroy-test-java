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

import ai.skillroy.testjava.scenario.ScenarioException;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Turns the file arguments of {@code compile} and {@code run} into files, and formats load and compile errors the
 * way compilers do ({@code path:line: reason}) so that editors and CI logs can jump to the line.
 *
 * <p>Stateless; safe from any thread.
 */
final class ScenarioFiles {

    private ScenarioFiles() {
    }

    /**
     * Each argument as a path: a file as given, a directory as its {@code *.yaml} and {@code *.yml} files (not
     * recursive), sorted by name.
     *
     * @throws UsageException if an argument does not exist or a directory holds no scenario file
     */
    static List<Path> expand(List<String> args) throws UsageException {
        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            Path path = ParsedArgs.toPath("argument", arg);
            if (Files.isDirectory(path)) {
                List<Path> inDir = new ArrayList<>();
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                    for (Path entry : entries) {
                        if (Files.isRegularFile(entry) && isYaml(entry)) {
                            inDir.add(entry);
                        }
                    }
                } catch (IOException e) {
                    throw new UsageException(arg + ": the directory cannot be listed: " + e.getMessage());
                }
                if (inDir.isEmpty()) {
                    throw new UsageException(arg + ": the directory holds no *.yaml or *.yml scenario file");
                }
                inDir.sort(Comparator.comparing(p -> p.getFileName().toString()));
                files.addAll(inDir);
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                throw new UsageException(arg + ": no such file or directory");
            }
        }
        return files;
    }

    /** True for a {@code plan.json} argument of {@code run} (any {@code .json} file); everything else is YAML. */
    static boolean isPlan(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /** {@code path:line: reason (scenario id)}; the line and the id are left out when unknown. */
    static String describe(Path file, ScenarioException e) {
        StringBuilder text = new StringBuilder(file.toString());
        if (e.line() > 0) {
            text.append(':').append(e.line());
        }
        text.append(": ").append(e.reason());
        String id = e.scenarioId();
        if (id != null && !id.equals(String.valueOf(file.getFileName()))) {
            text.append(" (scenario ").append(id).append(')');
        }
        return text.toString();
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
