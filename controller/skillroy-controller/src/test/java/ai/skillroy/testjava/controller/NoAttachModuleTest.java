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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The controller must load and work without the jdk.attach module; only AttachSession needs it. */
class NoAttachModuleTest {

    @TempDir
    Path dir;

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @Test
    void controllerClassesWorkWithoutJdkAttach() throws Exception {
        String classPath = String.join(File.pathSeparator, location(AgentClient.class),
                location(NoAttachProbe.class), location(Json.class));
        Process process = new ProcessBuilder(List.of(Fixtures.currentJava().toString(),
                "--limit-modules", "java.base,java.net.http", "-cp", classPath, NoAttachProbe.class.getName(),
                Fixtures.get().home.dir().toString(), dir.toString()))
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.strip().endsWith("OK"), output);
    }
}
