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


package ai.skillroy.testjava.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off by default means nothing loads (phase6-contracts.md 10.1, 10.6, decision D140): in a child JVM run with
 * {@code -Xlog:class+load}, reading the settings with nothing named, asking for {@code none} and even finding the two
 * providers and asking their exporter loads no {@code io.opentelemetry} class (the providers name no OpenTelemetry type
 * until {@code create}); creating an observer does (the positive control, which proves the probe sees such a class).
 */
class ClassLoadingProbeTest {

    @TempDir
    Path dir;

    private List<String> run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                ClassLoadingProbe.class.getName()));
        command.addAll(List.of(args));
        Path out = dir.resolve("out-" + args.length + ".txt");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile())
                .start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe ends");
        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), String.join("\n", lines.subList(Math.max(0, lines.size() - 30),
                lines.size())));
        assertTrue(lines.contains("probe-done"), "the probe ran");
        return lines;
    }

    private static long openTelemetryClasses(List<String> lines) {
        return lines.stream().filter(l -> l.contains("[class,load]") && l.contains(" io.opentelemetry.")).count();
    }

    @Test
    void offAndFindingTheProvidersLoadNoOpenTelemetryClass() throws Exception {
        List<String> lines = run();
        assertTrue(lines.contains("none true"));
        assertTrue(lines.contains("provider otlp") && lines.contains("provider file"), String.valueOf(lines.stream()
                .filter(l -> l.startsWith("provider")).toList()));
        assertEquals(0, openTelemetryClasses(lines), lines.stream().filter(l -> l.contains("io.opentelemetry"))
                .limit(10).toList().toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("[class,load]")
                && l.contains("ai.skillroy.testjava.telemetry.FileObserverProvider")), "the provider class did load");
    }

    @Test
    void creatingAnObserverDoesThePositiveControl() throws Exception {
        List<String> lines = run("create", dir.resolve("spans").toString());
        assertTrue(openTelemetryClasses(lines) > 50, "the SDK loads when an exporter is made");
        assertTrue(Files.isRegularFile(dir.resolve("spans").resolve("spans.jsonl")));
    }
}
