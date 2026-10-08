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


package ai.skillroy.testjava.samples.webhost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first-run sentinel files Chromium 152 needs in its root cache path (spike BS8, F-A). */
class FirstRunSentinelsTest {

    @TempDir
    Path tmp;

    @Test
    void anEmptyDirectoryGetsExactlyTheTwoEmptyFiles() throws IOException {
        assertEquals(Arrays.asList("First Run", "EULA Accepted"), FirstRunSentinels.prepare(tmp));
        assertEquals(new TreeSet<String>(Arrays.asList("EULA Accepted", "First Run")), names(tmp));
        for (String name : FirstRunSentinels.NAMES) {
            assertTrue(Files.isRegularFile(tmp.resolve(name)), name);
            assertEquals(0L, Files.size(tmp.resolve(name)), name + " is empty");
        }
    }

    @Test
    void aMissingDirectoryIsCreated() throws IOException {
        Path cache = tmp.resolve("a").resolve("cache");
        assertEquals(FirstRunSentinels.NAMES, FirstRunSentinels.prepare(cache));
        assertEquals(new TreeSet<String>(FirstRunSentinels.NAMES), names(cache));
    }

    @Test
    void preparingTwiceChangesNothingAndExistingFilesAreLeftAlone() throws IOException {
        Files.write(tmp.resolve("First Run"), "kept".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("Local State"), "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(Collections.singletonList("EULA Accepted"), FirstRunSentinels.prepare(tmp));
        assertEquals(Collections.<String>emptyList(), FirstRunSentinels.prepare(tmp), "idempotent");
        assertEquals(new TreeSet<String>(Arrays.asList("EULA Accepted", "First Run", "Local State")), names(tmp));
        assertEquals("kept", new String(Files.readAllBytes(tmp.resolve("First Run")), StandardCharsets.UTF_8));
        assertEquals(0L, Files.size(tmp.resolve("EULA Accepted")));
    }

    private static Set<String> names(Path dir) throws IOException {
        Set<String> names = new TreeSet<String>();
        DirectoryStream<Path> entries = Files.newDirectoryStream(dir);
        try {
            for (Path p : entries) {
                names.add(p.getFileName().toString());
            }
        } finally {
            entries.close();
        }
        return names;
    }
}
