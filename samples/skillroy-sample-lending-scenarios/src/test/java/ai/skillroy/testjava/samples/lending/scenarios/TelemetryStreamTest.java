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

import ai.skillroy.testjava.junit.OutputScan;
import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JUnit glue's telemetry (phase6-contracts.md 10.6, 13.2 item 9's companion, headless): a stream run in a child JVM
 * with {@code -Dskillroy.telemetry=file -Dskillroy.telemetry.out=<dir>} on the headless desk ({@link HeadlessDesk},
 * scenario C in {@code replay}) passes as it does without telemetry, and writes {@code spans.jsonl}: the scenario's
 * spans as it runs, and the root span {@code skillroy junit} that only the JVM-exit hook ends, which proves the
 * shutdown-hook flush. No span holds a staff password or the desk's JDBC password.
 */
class TelemetryStreamTest {

    @TempDir
    Path dir;

    @Test
    void aStreamWritesItsSpansAndTheExitHookFlushesTheRoot() throws Exception {
        Path spans = dir.resolve("telemetry");
        List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Dskillroy.telemetry=file", "-Dskillroy.telemetry.out=" + spans,
                "-Dskillroy.mode=replay", "-Dskillroy.env=" + Env.SEED_A.id,
                "-Dskillroy.bindings=" + ScenarioA.bindingsRoot(), "-cp", System.getProperty("java.class.path"),
                TelemetryStreamMain.class.getName(), dir.resolve("work").toString());
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(dir.resolve("child.out").toFile());
        builder.environment().remove("SKILLROY_CREDENTIALS");
        Process process = builder.start();
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "the stream ends");
        String output = Files.readString(dir.resolve("child.out"), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("test " + ScenarioC.ID + " ok"), output);
        assertTrue(output.contains("stream-done"), output);

        Path file = spans.resolve("spans.jsonl");
        assertTrue(Files.isRegularFile(file), "the file exporter wrote " + file + ": " + output);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lines.add(Json.parseObject(line));
        }
        Set<Object> names = new HashSet<>();
        lines.forEach(l -> names.add(l.get("name")));
        assertTrue(names.contains("scenario " + ScenarioC.ID) && names.contains("bind") && names.contains("step s1"),
                String.valueOf(names));
        Map<String, Object> root = lines.get(lines.size() - 1);
        assertEquals("skillroy junit", root.get("name"), "the root, ended by the JVM-exit hook, is the last line");
        assertEquals(1, lines.stream().map(l -> l.get("traceId")).distinct().count(), "one trace per stream");
        Set<String> secrets = new HashSet<>(SampleCredentials.staff(Env.SEED_A).values());
        secrets.add(HeadlessDesk.readerPassword());
        assertEquals(List.of(), OutputScan.leaks(file, secrets), "no span holds a password");
    }
}
