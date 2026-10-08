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

import ai.skillroy.testjava.samples.webhost.WebHostOptions.Rendering;
import me.friwi.jcefmaven.EnumPlatform;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code -Dwebhost.*} properties: parsing and defaults. Nothing is created on disk. */
class WebHostOptionsTest {

    private static Properties props(String... keysAndValues) {
        Properties p = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            p.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return p;
    }

    @Test
    void defaults() {
        WebHostOptions o = WebHostOptions.fromProperties(new Properties(), "linux-arm64");
        assertEquals(Rendering.WINDOWED, o.rendering());
        assertFalse(o.offScreen());
        assertEquals(WebHostOptions.NO_DEBUG_PORT, o.debugPort());
        assertEquals(0, o.debugPort());
        assertEquals(Paths.get("target", "jcef-bundle-linux-arm64"), o.installDir(), "relative to the working dir");
        assertFalse(o.installDir().isAbsolute());
        assertNull(o.cacheDir(), "a fresh temporary directory is made at launch, not here");
        assertEquals("linux-arm64", o.platformId());
        assertEquals(Paths.get("target", "jcef-bundle-" + WebHostOptions.platform()),
                WebHostOptions.defaults().installDir());
    }

    @Test
    void thePlatformIsJcefmavensIdentifier() throws Exception {
        assertEquals(EnumPlatform.getCurrentPlatform().getIdentifier(), WebHostOptions.platform());
        assertTrue(WebHostOptions.platform().matches("(linux|macosx|windows)-[a-z0-9]+"), WebHostOptions.platform());
    }

    @Test
    void renderingIsWindowedOrOsrInAnyCase() {
        assertEquals(Rendering.OSR, WebHostOptions.fromProperties(props("webhost.rendering", "osr")).rendering());
        assertEquals(Rendering.OSR, WebHostOptions.fromProperties(props("webhost.rendering", " OSR ")).rendering());
        assertTrue(WebHostOptions.fromProperties(props("webhost.rendering", "osr")).offScreen());
        assertEquals(Rendering.WINDOWED,
                WebHostOptions.fromProperties(props("webhost.rendering", "Windowed")).rendering());
        assertEquals(Rendering.WINDOWED, WebHostOptions.fromProperties(props("webhost.rendering", " ")).rendering());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WebHostOptions.fromProperties(props("webhost.rendering", "offscreen")));
        assertEquals("webhost.rendering must be \"windowed\" or \"osr\": offscreen", e.getMessage());
        assertEquals("osr", Rendering.OSR.id());
        assertEquals("windowed", Rendering.WINDOWED.id());
    }

    @Test
    void theDebugPortIsAPortNumberAndZeroMeansNone() {
        assertEquals(9229, WebHostOptions.fromProperties(props("webhost.debugPort", "9229")).debugPort());
        assertEquals(0, WebHostOptions.fromProperties(props("webhost.debugPort", " 0 ")).debugPort());
        assertEquals(65535, WebHostOptions.fromProperties(props("webhost.debugPort", "65535")).debugPort());
        assertEquals(0, WebHostOptions.fromProperties(props("webhost.debugPort", "")).debugPort());
        for (String bad : new String[] {"-1", "65536", "abc", "92.29"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> WebHostOptions.fromProperties(props("webhost.debugPort", bad)), bad);
            assertEquals("webhost.debugPort must be a port number between 0 and 65535 (0 = none): " + bad,
                    e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> WebHostOptions.defaults().debugPort(70000));
    }

    @Test
    void installAndCacheDirectoriesAreTakenAsGiven() {
        WebHostOptions o = WebHostOptions.fromProperties(props("webhost.install", " /opt/bundle ",
                "webhost.cache", "relative/cache"), "linux-amd64");
        assertEquals(Paths.get("/opt/bundle"), o.installDir());
        assertEquals(Paths.get("relative/cache"), o.cacheDir());
        WebHostOptions blank = WebHostOptions.fromProperties(props("webhost.install", "", "webhost.cache", " "),
                "linux-amd64");
        assertEquals(Paths.get("target", "jcef-bundle-linux-amd64"), blank.installDir());
        assertNull(blank.cacheDir());
    }

    @Test
    void theSettersBuildTheSameOptions() {
        WebHostOptions o = WebHostOptions.defaults().rendering(Rendering.OSR).debugPort(9222)
                .installDir(Paths.get("b")).cacheDir(Paths.get("c"));
        assertEquals(Rendering.OSR, o.rendering());
        assertEquals(9222, o.debugPort());
        assertEquals(Paths.get("b"), o.installDir());
        assertEquals(Paths.get("c"), o.cacheDir());
        assertEquals(WebHostOptions.defaultInstallDir(WebHostOptions.platform()), o.installDir(null).installDir());
        assertNull(o.cacheDir(null).cacheDir());
        assertThrows(NullPointerException.class, () -> o.rendering(null));
        assertTrue(o.toString().contains("rendering=osr"), o.toString());
    }

    @Test
    void systemPropertiesAreRead() {
        String before = System.getProperty("webhost.debugPort");
        System.setProperty("webhost.debugPort", "9333");
        try {
            assertEquals(9333, WebHostOptions.fromSystemProperties().debugPort());
        } finally {
            if (before == null) {
                System.clearProperty("webhost.debugPort");
            } else {
                System.setProperty("webhost.debugPort", before);
            }
        }
    }
}
