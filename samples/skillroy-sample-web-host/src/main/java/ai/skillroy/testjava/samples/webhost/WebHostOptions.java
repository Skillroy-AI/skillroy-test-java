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

import me.friwi.jcefmaven.EnumPlatform;
import me.friwi.jcefmaven.UnsupportedPlatformException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Start-up options of the web host. {@link #fromSystemProperties()} reads the documented system properties (the only
 * names the host reads are {@code webhost.*}); the setters exist so that a test can launch a host in its own JVM
 * without touching system properties. A mutable builder: set it up on one thread, then hand it to
 * {@link WebHostApp#launch}. Parsing has no side effects: no directory is created here.
 *
 * <table border="1">
 *   <caption>System properties</caption>
 *   <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@value #PROP_RENDERING}</td><td>{@code windowed}</td><td>{@code windowed} (CEF draws into native
 *       child windows) or {@code osr} (off-screen rendering into a JOGL canvas); case-insensitive. Windowed JCEF on
 *       a Java 8 JVM is unverified (spike BS8 saw no native browser created there): use {@code osr} on Java 8</td></tr>
 *   <tr><td>{@value #PROP_DEBUG_PORT}</td><td>0</td><td>Chromium's remote-debugging port, 1 to 65535; 0 means none.
 *       The only way a port exists: nothing else in the host or the framework opens one</td></tr>
 *   <tr><td>{@value #PROP_INSTALL}</td><td>{@code target/jcef-bundle-<platform>}</td><td>where jcefmaven extracts
 *       the natives (relative paths are resolved against the working directory). One directory per platform
 *       ({@link #platform()}), because a repository shared between a macOS host and Linux containers of two
 *       architectures would otherwise mix their bundles. jcefmaven deletes and refills a directory that does not
 *       hold a complete installation of this release, so never point it at a directory with other content</td></tr>
 *   <tr><td>{@value #PROP_CACHE}</td><td>a fresh temporary directory</td><td>Chromium's root cache path. It must
 *       be on the local file system (in a container: the container's own, never a bind mount); the host creates the
 *       first-run sentinel files in it ({@link FirstRunSentinels}). A temporary directory the host created is
 *       removed again by {@link WebHostApp#shutdown()}</td></tr>
 * </table>
 */
public final class WebHostOptions {

    public static final String PROP_RENDERING = "webhost.rendering";
    public static final String PROP_DEBUG_PORT = "webhost.debugPort";
    public static final String PROP_INSTALL = "webhost.install";
    public static final String PROP_CACHE = "webhost.cache";
    /** {@link #debugPort()} when no remote-debugging port is configured (the default). */
    public static final int NO_DEBUG_PORT = 0;

    /** How the host's browsers render. */
    public enum Rendering {
        /** CEF draws into native child windows ({@code CefBrowserWr}); the default. */
        WINDOWED("windowed"),
        /** CEF renders off screen into a buffer a JOGL canvas shows ({@code CefBrowserOsr}). */
        OSR("osr");

        private final String id;

        Rendering(String id) {
            this.id = id;
        }

        /** @return the property value: {@code windowed} or {@code osr} */
        public String id() {
            return id;
        }

        /**
         * @param value a property value; null or blank means the default ({@link #WINDOWED})
         * @throws IllegalArgumentException for anything but {@code windowed} or {@code osr} (case-insensitive)
         */
        public static Rendering parse(String value) {
            if (value == null || value.trim().isEmpty()) {
                return WINDOWED;
            }
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (Rendering r : values()) {
                if (r.id.equals(v)) {
                    return r;
                }
            }
            throw new IllegalArgumentException(PROP_RENDERING + " must be \"windowed\" or \"osr\": " + value);
        }
    }

    private final String platform;
    private Rendering rendering = Rendering.WINDOWED;
    private int debugPort = NO_DEBUG_PORT;
    private Path installDir;
    private Path cacheDir;

    private WebHostOptions(String platform) {
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /** @return the defaults: windowed, no debug port, the default install directory, a fresh cache directory */
    public static WebHostOptions defaults() {
        return new WebHostOptions(platform());
    }

    /** @return options from {@link System#getProperties()} */
    public static WebHostOptions fromSystemProperties() {
        return fromProperties(System.getProperties());
    }

    /**
     * @return options from the given properties ({@value #PROP_RENDERING}, {@value #PROP_DEBUG_PORT},
     *     {@value #PROP_INSTALL}, {@value #PROP_CACHE}); a blank value means the default
     * @throws IllegalArgumentException for an unknown rendering mode or a port that is not a whole number between 0
     *     and 65535
     */
    public static WebHostOptions fromProperties(Properties props) {
        return fromProperties(props, platform());
    }

    /** As {@link #fromProperties(Properties)}, for a given platform identifier (tests). */
    static WebHostOptions fromProperties(Properties props, String platform) {
        WebHostOptions o = new WebHostOptions(platform);
        o.rendering(Rendering.parse(props.getProperty(PROP_RENDERING)));
        String port = props.getProperty(PROP_DEBUG_PORT);
        if (port != null && !port.trim().isEmpty()) {
            int value;
            try {
                value = Integer.parseInt(port.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(portMessage(port));
            }
            o.debugPort(value);
        }
        String install = props.getProperty(PROP_INSTALL);
        if (install != null && !install.trim().isEmpty()) {
            o.installDir(Paths.get(install.trim()));
        }
        String cache = props.getProperty(PROP_CACHE);
        if (cache != null && !cache.trim().isEmpty()) {
            o.cacheDir(Paths.get(cache.trim()));
        }
        return o;
    }

    private static String portMessage(Object value) {
        return PROP_DEBUG_PORT + " must be a port number between 0 and 65535 (0 = none): " + value;
    }

    public WebHostOptions rendering(Rendering value) {
        this.rendering = Objects.requireNonNull(value, "rendering");
        return this;
    }

    /** @param port Chromium's remote-debugging port, or {@link #NO_DEBUG_PORT} for none */
    public WebHostOptions debugPort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(portMessage(Integer.valueOf(port)));
        }
        this.debugPort = port;
        return this;
    }

    /** @param dir where jcefmaven extracts the natives; null for the default ({@link #defaultInstallDir(String)}) */
    public WebHostOptions installDir(Path dir) {
        this.installDir = dir;
        return this;
    }

    /** @param dir Chromium's root cache path; null for a fresh temporary directory created at launch */
    public WebHostOptions cacheDir(Path dir) {
        this.cacheDir = dir;
        return this;
    }

    public Rendering rendering() {
        return rendering;
    }

    /** @return true for {@link Rendering#OSR} */
    public boolean offScreen() {
        return rendering == Rendering.OSR;
    }

    /** @return the remote-debugging port, or {@link #NO_DEBUG_PORT} */
    public int debugPort() {
        return debugPort;
    }

    /** @return the install directory as configured, else the default; possibly relative to the working directory */
    public Path installDir() {
        return installDir != null ? installDir : defaultInstallDir(platform);
    }

    /** @return the configured root cache path, or null when the host creates a fresh temporary directory */
    public Path cacheDir() {
        return cacheDir;
    }

    /** @return the platform identifier these options were made for (see {@link #platform()}) */
    public String platformId() {
        return platform;
    }

    /**
     * The identifier jcefmaven uses for this JVM's platform and the natives artifact's suffix:
     * {@code linux-amd64}, {@code linux-arm64}, {@code macosx-arm64}, {@code windows-amd64}, ... For a platform
     * jcefmaven does not know, {@code <os>-<arch>} in lower case (JCEF will not start there).
     */
    public static String platform() {
        try {
            return EnumPlatform.getCurrentPlatform().getIdentifier();
        } catch (UnsupportedPlatformException e) {
            String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
            return os.replaceAll("[^a-z0-9]", "") + "-" + arch.replaceAll("[^a-z0-9_]", "");
        }
    }

    /** @return {@code target/jcef-bundle-<platform>}, relative to the working directory */
    public static Path defaultInstallDir(String platform) {
        return Paths.get("target", "jcef-bundle-" + platform);
    }

    @Override
    public String toString() {
        return "WebHostOptions{rendering=" + rendering.id() + ", debugPort=" + debugPort + ", installDir="
                + installDir() + ", cacheDir=" + (cacheDir == null ? "(fresh temporary directory)" : cacheDir)
                + ", platform=" + platform + "}";
    }
}
