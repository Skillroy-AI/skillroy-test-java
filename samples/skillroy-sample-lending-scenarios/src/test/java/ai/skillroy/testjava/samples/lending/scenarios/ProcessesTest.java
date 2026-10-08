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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Processes}, natively (the end-to-end check that uses it runs only in the Linux image): a subprocess that
 * ends in time is left alone; one that does not is destroyed with its descendants, and both are gone when the wait
 * returns.
 */
class ProcessesTest {

    @BeforeEach
    void needsAShell() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs /bin/sh");
    }

    @Test
    void aProcessThatEndsInTimeIsLeftAlone() throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", "exit 3").start();
        assertTrue(Processes.await(process, Duration.ofSeconds(30)));
        assertEquals(3, process.exitValue());
    }

    /**
     * A shell that starts a child and waits for it, as the launcher would wait for a JVM it did not {@code exec}: after
     * the bound, the shell and its child are both gone.
     */
    @Test
    void aProcessThatDoesNotEndInTimeIsDestroyedWithItsDescendants() throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", "sleep 300 & echo $!; wait").start();
        long child = -1;
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.US_ASCII));
            child = Long.parseLong(CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (java.io.IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).get(30, TimeUnit.SECONDS).strip());
            assertTrue(ProcessHandle.of(child).isPresent(), "the child runs");

            assertFalse(Processes.await(process, Duration.ofMillis(300)), "not ended in time");
            assertFalse(process.isAlive(), "the shell is gone");
            Optional<ProcessHandle> left = ProcessHandle.of(child);
            if (left.isPresent()) {
                left.get().onExit().get(5, TimeUnit.SECONDS);
            }
        } finally {
            if (child > 0) {
                ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
            }
            process.destroyForcibly();
        }
    }
}
