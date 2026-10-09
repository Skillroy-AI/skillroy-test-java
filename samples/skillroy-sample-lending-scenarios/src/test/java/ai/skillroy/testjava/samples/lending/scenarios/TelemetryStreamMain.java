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

import ai.skillroy.testjava.data.Credentials;
import ai.skillroy.testjava.junit.ScenarioSource;
import ai.skillroy.testjava.junit.ScenarioTests;
import ai.skillroy.testjava.samples.lending.scenarios.SampleCredentials.Env;
import org.junit.jupiter.api.DynamicTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The child JVM of {@link TelemetryStreamTest}: one JUnit stream of scenario C on the headless desk, with the system
 * properties the parent gives ({@code skillroy.telemetry=file}, {@code skillroy.telemetry.out}, the mode, the
 * environment, the bindings root); each test's executable is run and its outcome printed. The observer is not closed
 * here: the JUnit glue's shutdown hook must end the root span and flush the file when the JVM exits.
 */
public final class TelemetryStreamMain {

    private TelemetryStreamMain() {
    }

    /** {@code args[0]}: a work directory for the credentials file and the scenario copy. */
    public static void main(String[] args) throws Throwable {
        Path dir = Path.of(args[0]);
        Path scenarios = dir.resolve("scenarios");
        Files.createDirectories(scenarios);
        Files.copy(ScenarioC.yaml(), scenarios.resolve(ScenarioC.yaml().getFileName()));
        try (HeadlessDesk desk = HeadlessDesk.launch(Env.SEED_A)) {
            desk.credentials(dir.resolve("credentials"));
            System.setProperty("skillroy.credentials", dir.resolve("credentials").resolve(Credentials.FILE_NAME)
                    .toString());
            List<DynamicTest> tests = ScenarioTests.dynamicTests(ScenarioSource.directory(scenarios), () -> desk)
                    .toList();
            for (DynamicTest test : tests) {
                try {
                    test.getExecutable().execute();
                    System.out.println("test " + test.getDisplayName() + " ok");
                } catch (Throwable failure) {
                    System.out.println("test " + test.getDisplayName() + " failed: " + failure);
                }
            }
        }
        System.out.println("stream-done");
    }
}
