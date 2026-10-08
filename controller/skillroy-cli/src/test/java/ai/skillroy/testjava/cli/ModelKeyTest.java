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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 2.3, 4.4, 15.1 items 15 and 16: the model key reaches the CLI through the environment variable
 * only. A child JVM runs {@link Main} as {@code bin/skillroy} would, against the fake agent of this JVM: without the
 * key, {@code --inference claude} is exit code 64 with "the model key is not set (ANTHROPIC_API_KEY)" before anything
 * runs; with a dummy key, a live {@code run} with prompt logging and a {@code session} that opens the live gateway
 * (and makes no model call) succeed, and the dummy value is in no output and in no file the commands wrote.
 */
class ModelKeyTest {

    private static final String DUMMY_KEY = "sk-dummy-cli-key-0451-never-real";

    @TempDir
    Path tmp;

    private FakeAgent agent;
    private List<String> connection;
    private Path scenario;

    @BeforeEach
    void setUp() throws Exception {
        agent = new FakeAgent();
        Path tokenFile = agent.writeTokenFile(tmp.resolve("agent.token"));
        connection = List.of("--port", Integer.toString(agent.port()), "--token-file", tokenFile.toString());
        scenario = Scenarios.write(tmp.resolve("module/scenarios"), "with-oracle.yaml", BindingCommandsTest.ORACLE);
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    /** What a child CLI printed, and its exit code. */
    private record Child(int exit, String out, String err) {
        String both() {
            return out + err;
        }
    }

    private Child child(String key, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-cp", System.getProperty("java.class.path"), Main.class.getName()));
        command.addAll(List.of(args));
        command.addAll(connection);
        ProcessBuilder pb = new ProcessBuilder(command).directory(tmp.toFile());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("ANTHROPIC_") || k.startsWith("SKILLROY_"));
        if (key != null) {
            env.put("ANTHROPIC_API_KEY", key);
            // Nothing listens there: a call that should not happen could not reach anything.
            env.put("ANTHROPIC_BASE_URL", "http://127.0.0.1:9");
        }
        Path out = tmp.resolve("child-" + System.nanoTime() + ".out");
        Path err = tmp.resolve("child-" + System.nanoTime() + ".err");
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        Process process = pb.start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the child CLI finished in time");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return new Child(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
    }

    @Test
    void withoutTheKeyClaudeIsExitCode64AndNothingRuns() throws Exception {
        Child child = child(null, "run", scenario.toString(), "--mode", "live", "--inference", "claude");
        assertEquals(Exit.USAGE, child.exit(), child.both());
        assertTrue(child.err().contains("the model key is not set (ANTHROPIC_API_KEY)"), child.err());
        assertTrue(agent.requests.isEmpty(), "nothing reached the agent");
    }

    @Test
    void theKeyIsReadFromTheEnvironmentAndWrittenNowhere() throws Exception {
        Child run = child(DUMMY_KEY, "run", scenario.toString(), "--mode", "live", "--inference", "claude",
                "--log-prompts", "--out", tmp.resolve("run-out").toString(), "--json");
        assertEquals(Exit.OK, run.exit(), run.both());
        assertTrue(run.out().contains("\"gateway\": \"claude\""), "the live gateway was chosen: " + run.out());
        Path script = Files.writeString(tmp.resolve("note.session"), "note the key stays in the environment\nquit\n");
        Child session = child(DUMMY_KEY, "session", "--script", script.toString(), "--inference", "claude",
                "--out", tmp.resolve("session-out").toString());
        assertEquals(Exit.OK, session.exit(), session.both());
        assertFalse(session.out().contains("ask is not available"), "the live gateway opened: " + session.out());
        for (Child c : List.of(run, session)) {
            assertFalse(c.both().contains(DUMMY_KEY), "the key is in no output");
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(tmp)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("journal.jsonl")), files.toString());
        for (Path f : files) {
            assertFalse(read(f).contains(DUMMY_KEY), "the key is in " + f);
        }
    }

    private static String read(Path f) throws IOException {
        return new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
    }
}
