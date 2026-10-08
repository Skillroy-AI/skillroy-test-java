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

package ai.skillroy.testjava.inference.claude;

import ai.skillroy.testjava.inference.claude.FakeAnthropic.Answer;
import ai.skillroy.testjava.inference.claude.FakeAnthropic.Seen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * phase5-contracts.md 15.1 items 2 and 16, 2.3: the key reaches the gateway through the environment variable only, and
 * appears in no output and no file. A child JVM ({@link KeyProbe}) gets a dummy key, the fake's base URL and the
 * variables the SDK's {@code fromEnv()} would also read (an auth token, custom headers, a debug log level); it opens
 * the gateway as a run does, with recording and prompt logging on, against the loopback fake. The fake must have seen
 * the dummy key in its header and nothing of the other variables; the child's output and every file it wrote must
 * hold neither the key nor the token.
 */
class KeyFromEnvironmentTest {

    private static final String DUMMY_KEY = "sk-dummy-env-key-0451-never-real";
    private static final String DUMMY_TOKEN = "dummy-auth-token-0451-never-read";

    @TempDir
    Path out;

    @Test
    void theKeyIsReadFromTheEnvironmentAndWrittenNowhere() throws Exception {
        try (FakeAnthropic fake = new FakeAnthropic()) {
            fake.answer(Answer.json(FakeAnthropic.message("end_turn",
                            "[{\"type\":\"text\",\"text\":\"{\\\"answer\\\":\\\"DRL-01\\\"}\"}]", "{}")),
                    Answer.error(401, "authentication_error", "invalid x-api-key", Map.of()));
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                    KeyProbe.class.getName(), out.toString());
            Map<String, String> env = pb.environment();
            env.keySet().removeIf(k -> k.startsWith("ANTHROPIC_") || k.startsWith("SKILLROY_"));
            env.put("ANTHROPIC_API_KEY", DUMMY_KEY);
            env.put("ANTHROPIC_BASE_URL", fake.url());
            env.put("ANTHROPIC_AUTH_TOKEN", DUMMY_TOKEN);
            env.put("ANTHROPIC_CUSTOM_HEADERS", "x-from-custom-headers: leaked");
            env.put("ANTHROPIC_LOG", "debug");
            Path stdout = out.resolve("child.out");
            Path stderr = out.resolve("child.err");
            pb.redirectOutput(stdout.toFile());
            pb.redirectError(stderr.toFile());
            Process child = pb.start();
            try {
                assertTrue(child.waitFor(120, TimeUnit.SECONDS), "the child JVM finished in time");
            } finally {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            String printed = Files.readString(stdout, StandardCharsets.UTF_8) + Files.readString(stderr,
                    StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), printed);
            assertTrue(printed.contains("done"), printed);
            assertTrue(printed.contains("refused AUTH"), printed);
            List<Seen> seen = fake.seen();
            assertEquals(3, seen.size(), "the answered call, the refused one and the masked opener's");
            for (Seen s : seen) {
                assertEquals(DUMMY_KEY, s.header("x-api-key"), "the key came from the environment");
                assertNull(s.header("authorization"), "the auth token is not read");
                assertNull(s.header("x-from-custom-headers"), "custom headers are not read");
            }
            assertFalse(printed.contains(DUMMY_KEY), "the key is in no output");
            assertFalse(printed.contains(DUMMY_TOKEN));
            assertFalse(printed.toLowerCase(java.util.Locale.ROOT).contains("x-api-key:"), "no debug log");
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(out)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals("inference.jsonl")), files.toString());
        assertTrue(files.stream().anyMatch(f -> f.getParent().getFileName().toString().equals("recordings")),
                "a recording was written: " + files);
        assertTrue(files.stream().anyMatch(f -> f.getParent().getFileName().toString().equals("recordings-masked")),
                "a recording was written through the opener's mask (4.2): " + files);
        for (Path f : files) {
            String text = read(f);
            assertFalse(text.contains(DUMMY_KEY), "the key is in " + f.getFileName());
            assertFalse(text.contains(DUMMY_TOKEN), "the token is in " + f.getFileName());
            assertFalse(text.contains(KeyProbe.CREDENTIAL), "the opener's credential is in " + f + ": the recording"
                    + " gateway did not write through the opener's mask");
        }
    }

    private static String read(Path f) throws IOException {
        return new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
    }
}
