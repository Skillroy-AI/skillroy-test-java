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

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The two empty files Chromium 152 writes into its root cache path when someone accepts its first-run "Additional
 * Terms of Service" dialog. Without them {@code CefInitialize} waits for that dialog, which nobody answers under a
 * display without a window manager (spike BS8, finding F-A); the web host therefore creates them before it builds
 * the {@code CefApp}. This is an application concern: the agent never does it.
 *
 * <p>Stateless; safe from any thread.
 */
public final class FirstRunSentinels {

    /** The file Chromium writes after its first run. */
    public static final String FIRST_RUN = "First Run";
    /** The file Chromium writes when its terms dialog is accepted. */
    public static final String EULA_ACCEPTED = "EULA Accepted";
    /** Both names, in the order {@link #prepare} creates them. */
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(FIRST_RUN, EULA_ACCEPTED));

    private FirstRunSentinels() {
    }

    /**
     * Creates the directory when it is missing, then each sentinel file that does not exist yet, empty. An existing
     * file (or anything else of that name) is left as it is. Idempotent.
     *
     * @param rootCachePath Chromium's root cache path ({@code CefSettings.root_cache_path})
     * @return the names of the files this call created, in {@link #NAMES} order; empty when both were present
     * @throws IOException when the directory or a file cannot be created
     */
    public static List<String> prepare(Path rootCachePath) throws IOException {
        Files.createDirectories(rootCachePath);
        List<String> created = new ArrayList<String>();
        for (String name : NAMES) {
            Path file = rootCachePath.resolve(name);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.createFile(file);
                created.add(name);
            } catch (FileAlreadyExistsException raced) {
                // created by someone else between the check and here: present, which is all that counts
            }
        }
        return Collections.unmodifiableList(created);
    }
}
