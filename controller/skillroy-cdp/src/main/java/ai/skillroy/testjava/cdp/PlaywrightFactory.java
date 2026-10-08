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

package ai.skillroy.testjava.cdp;

import com.microsoft.playwright.Playwright;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only place a {@link Playwright} instance is created, so that tests can replace it and a unit-test JVM can forbid
 * it: creating the first instance extracts the Playwright driver (Node.js and the Playwright package, about 140 MB on
 * Linux x86-64) into a temporary directory and starts it as a child process.
 *
 * <p>The default factory refuses in a JVM whose system property {@value #FORBID_PROPERTY} is {@code true} (this
 * module's unit tests set it), maps the environment variable {@code PLAYWRIGHT_DRIVER_TMPDIR} onto Playwright's own
 * system property {@code playwright.driver.tmpdir} (the directory the driver is extracted under; Playwright reads
 * only the property) when that property is not set, and passes {@link #environment()} to the driver.
 *
 * <p>Implementations are called on the attach's Playwright thread and must be thread-safe.
 */
@FunctionalInterface
interface PlaywrightFactory {

    /** {@code -Dskillroy.cdp.noDriver=true} forbids the default factory (set by this module's unit tests). */
    String FORBID_PROPERTY = "skillroy.cdp.noDriver";

    /** Playwright Java's system property for the driver's extraction directory. */
    String DRIVER_TMPDIR_PROPERTY = "playwright.driver.tmpdir";

    /** Creates a Playwright instance whose driver runs with the given extra environment. */
    Playwright create(Map<String, String> environment);

    /** The default factory refused because {@value #FORBID_PROPERTY} is set: a test reached it by mistake. */
    final class Forbidden extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        Forbidden() {
            super("the Playwright driver may not start in this JVM (" + FORBID_PROPERTY + "=true): use a test factory");
        }
    }

    /** The production factory: {@code Playwright.create} with the environment, unless forbidden. */
    PlaywrightFactory DEFAULT = environment -> {
        if (Boolean.getBoolean(FORBID_PROPERTY)) {
            throw new Forbidden();
        }
        String tmp = environment.get("PLAYWRIGHT_DRIVER_TMPDIR");
        if (tmp != null && !tmp.isBlank() && System.getProperty(DRIVER_TMPDIR_PROPERTY) == null) {
            System.setProperty(DRIVER_TMPDIR_PROPERTY, tmp);
        }
        return Playwright.create(new Playwright.CreateOptions().setEnv(environment));
    };

    /**
     * The environment the driver gets on top of this process's own (Playwright starts it with a copy of {@code
     * System.getenv()} plus these): {@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1} (the path attaches only, it never
     * needs a browser of its own), and {@code PLAYWRIGHT_BROWSERS_PATH} and {@code PLAYWRIGHT_DRIVER_TMPDIR} passed
     * through when this process has them.
     */
    static Map<String, String> environment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        for (String name : new String[] {"PLAYWRIGHT_BROWSERS_PATH", "PLAYWRIGHT_DRIVER_TMPDIR"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                env.put(name, value);
            }
        }
        return env;
    }
}
