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

package ai.skillroy.testjava.vision;

import ai.skillroy.testjava.controller.OutputFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The captures directory of a session (phase7-contracts.md 9.6): every capture the loop acted on and every anchor crop,
 * written once as {@code captures/<sha256 hex>.png} under the session's output directory through {@link OutputFiles}
 * (a second write of the same hash is skipped), the raster as captured (redacted by the agent, without marks), named by
 * its own hash. A session without an output directory keeps nothing ({@link #none()}). A {@code ROBOT} capture is
 * never written here (the loop never takes one, D341). The directory takes the process umask: on a shared host run the
 * session under {@code umask 077} or without an output directory.
 *
 * <p>Owned by one session's thread.
 */
public final class Captures {

    /** The directory's name under the session's output directory. */
    public static final String DIRECTORY = "captures";

    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    private final Path dir;

    private Captures(Path dir) {
        this.dir = dir;
    }

    /** The captures of a session whose output directory is {@code outputDir}; null keeps nothing. */
    public static Captures under(Path outputDir) {
        return new Captures(outputDir == null ? null : outputDir.resolve(DIRECTORY));
    }

    /** A session without an output directory: nothing is kept. */
    public static Captures none() {
        return new Captures(null);
    }

    /** True when files are kept. */
    public boolean keeps() {
        return dir != null;
    }

    /** The directory, or null. */
    public Path dir() {
        return dir;
    }

    /**
     * Writes the capture's PNG as {@code captures/<hex>.png}, once.
     *
     * @return the file's name relative to the session's output directory ({@code captures/<hex>.png}), or null when
     *     nothing is kept
     * @throws IOException when it cannot be written
     * @throws IllegalArgumentException for a hash that is not {@code sha256:} and 64 hex digits
     */
    public String write(String hash, byte[] png) throws IOException {
        if (dir == null) {
            return null;
        }
        String hex = hash != null && hash.startsWith("sha256:") ? hash.substring(7) : null;
        if (hex == null || !HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("a capture's hash is sha256: and 64 hex digits");
        }
        Path file = dir.resolve(hex + ".png");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            OutputFiles.write(file, png);
        }
        return DIRECTORY + "/" + hex + ".png";
    }

    /** The PNG a name of {@link #write} names, or null when it is not there or nothing is kept. */
    public byte[] read(String name) throws IOException {
        if (dir == null || name == null || !name.startsWith(DIRECTORY + "/")) {
            return null;
        }
        String hex = name.substring(DIRECTORY.length() + 1).replaceFirst("\\.png$", "");
        if (!HEX.matcher(hex).matches()) {
            return null;
        }
        Path file = dir.resolve(hex + ".png");
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Files.readAllBytes(file) : null;
    }
}
