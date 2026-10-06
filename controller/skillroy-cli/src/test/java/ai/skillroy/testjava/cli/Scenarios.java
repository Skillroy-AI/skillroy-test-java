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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Scenario files for the compile and run tests, written for the {@code clitest} pack and the fake agent. */
final class Scenarios {

    /** Two pokes, then the counter is 2: PASS on a fresh fake agent. */
    static final String PASS = """
            scenario: poke-twice
            pack: clitest
            requires: [test.poke]
            steps:
              - poke: {}
              - poke: { by: 1 }
              - expect: { "counter.value": 2 }
            verify:
              - { "counter.value": { gte: 2 } }
            """;

    /** A guarded expectation on the fake's UNAVAILABLE capability: DEGRADED. */
    static final String GUARDED = """
            scenario: guarded
            pack: clitest
            steps:
              - poke: {}
              - expect: { "counter.value": { gte: 1, capability: test.fancy } }
            """;

    /** An expectation that never holds: FAIL / ASSERTION at s2 (line 6) after a short step timeout. */
    static final String WRONG = """
            scenario: wrong-count
            pack: clitest
            timeouts: { step: 300ms }
            steps:
              - poke: {}
              - expect: { "counter.value": 99 }
            """;

    /** A COMPONENT click that falls back to ACTION on the fake agent: DEGRADED (amendment A29). */
    static final String FALLBACK = """
            scenario: click-fallback
            pack: clitest
            ui: { clickRung: COMPONENT }
            steps:
              - click: { by: { name: DUE } }
              - poke: {}
            """;

    /** The same click with strict fidelity: FAIL / ASSERTION. */
    static final String STRICT = FALLBACK.replace("click-fallback", "click-strict").replace("COMPONENT }",
            "COMPONENT, strict: true }");

    /** A run that breaks inside the pack (an InternalError from a fact). */
    static final String CRASH = """
            scenario: crash
            pack: clitest
            steps:
              - poke: {}
              - expect: { "crash.x": 1 }
            """;

    /** Requires the UNAVAILABLE capability: ERROR / PRECONDITION_FAILED. */
    static final String NEEDS_FANCY = """
            scenario: needs-fancy
            pack: clitest
            requires: [test.fancy]
            steps:
              - poke: {}
            """;

    /** An unknown verb on line 5: a compile error. */
    static final String BROKEN = """
            scenario: broken
            pack: clitest
            steps:
              - poke: {}
              - frobnicate: {}
            """;

    /** For the pack that only a --pack jar provides. */
    static final String OTHER = """
            scenario: other-mark
            pack: other
            steps:
              - mark: hello
            """;

    /** No pack key: needs exactly one pack. */
    static final String NO_PACK = """
            scenario: no-pack
            steps:
              - mark: hello
            """;

    private Scenarios() {
    }

    static Path write(Path dir, String name, String yaml) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), yaml);
    }

    /** A jar whose only content is a {@code META-INF/services} entry registering {@link OtherTestPack}. */
    static Path otherPackJar(Path dir) throws IOException {
        Path jar = dir.resolve("other-pack.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("META-INF/services/ai.skillroy.testjava.scenario.pack.DomainPack"));
            out.write((OtherTestPack.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
