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

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which {@code connectOverCDP} options the Playwright on the class path has (phase8-contracts.md section 5.3, D552,
 * gap 121), decided once per JVM by field presence on {@link BrowserType.ConnectOverCDPOptions}: its option fields are
 * public in every line that has the setter ({@code isLocal} since 1.58, {@code noDefaults} since 1.60,
 * {@code artifactsDir} since 1.61). {@link CdpAttach} calls a setter only when its field exists, so on a line without
 * it the instruction is never executed and never linked (JVMS 5.4); no reflective invocation.
 *
 * <p>Per missing option (D553): without {@code isLocal} the attach connects without it; without {@code noDefaults} the
 * CDP path is not used unless the operator sets {@value #ALLOW_PROPERTY} (Playwright's download, focus and media
 * overrides then apply to the application's own browser context, accepted and logged); without {@code artifactsDir} a
 * traced run opens untraced. The {@code web.path} record says which ({@link #connectOptions}).
 *
 * <p>Immutable; the detection runs on the thread that first needs it (the path's {@code open}, before any driver starts,
 * or an attach's Playwright thread) and reads three fields, once.
 */
final class ConnectSupport {

    /** The operator's opt-in for a Playwright line without {@code noDefaults}: a system property of the controller. */
    static final String ALLOW_PROPERTY = "skillroy.cdp.allowDefaultOverrides";

    /** The value {@link #connectOptions} records for an option the Playwright on the class path lacks. */
    static final String UNSUPPORTED = "unsupported";

    private static volatile ConnectSupport detected;

    private final boolean noDefaults;
    private final boolean isLocal;
    private final boolean artifactsDir;
    private final String version;

    private ConnectSupport(boolean noDefaults, boolean isLocal, boolean artifactsDir, String version) {
        this.noDefaults = noDefaults;
        this.isLocal = isLocal;
        this.artifactsDir = artifactsDir;
        this.version = version;
    }

    /** The Playwright on the class path, memoised: {@code detect(BrowserType.ConnectOverCDPOptions.class)}. */
    static ConnectSupport detected() {
        ConnectSupport d = detected;
        if (d == null) {
            d = detect(BrowserType.ConnectOverCDPOptions.class);
            detected = d;
        }
        return d;
    }

    /** The options {@code optionsClass} has public fields for, and the version of the Playwright jar. */
    static ConnectSupport detect(Class<?> optionsClass) {
        return new ConnectSupport(hasField(optionsClass, "noDefaults"), hasField(optionsClass, "isLocal"),
                hasField(optionsClass, "artifactsDir"), playwrightVersion());
    }

    /** The test seam: a line with the {@code present} option fields ({@code noDefaults}, {@code isLocal}, ...). */
    static ConnectSupport of(Set<String> present, String version) {
        return new ConnectSupport(present.contains("noDefaults"), present.contains("isLocal"),
                present.contains("artifactsDir"), version);
    }

    /** Whether {@code setNoDefaults} may be called. */
    boolean noDefaults() {
        return noDefaults;
    }

    /** Whether {@code setIsLocal} may be called. */
    boolean isLocal() {
        return isLocal;
    }

    /** Whether {@code setArtifactsDir} may be called. */
    boolean artifactsDir() {
        return artifactsDir;
    }

    /**
     * {@code Playwright.class.getPackage().getImplementationVersion()}, or null (a repackaged jar without a manifest
     * version).
     */
    String version() {
        return version;
    }

    /**
     * Why the CDP path is not used on this line (no {@code noDefaults}, no opt-in), the reason {@code open} returns
     * before any driver starts.
     */
    String noDefaultsRefusal() {
        return "the Playwright on the class path (" + (version == null ? "unknown" : version) + ") cannot attach"
                + " without overriding the application's browser context (no noDefaults; Playwright 1.60 or later has"
                + " it); set -D" + ALLOW_PROPERTY + "=true to accept the overrides";
    }

    /**
     * The {@code web.path} record's {@code connectOptions} (A80): {@code {noDefaults: true | "unsupported", isLocal:
     * true | "unsupported", artifactsDir: true | false | "unsupported", defaultOverridesAccepted?: true}} for an attach
     * made with {@code options}; {@code artifactsDir} is false when no trace was asked for.
     *
     * @param traced whether the run asked for a trace
     */
    Map<String, Object> connectOptions(CdpOptions options, boolean traced) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("noDefaults", noDefaults ? (Object) options.noDefaults() : UNSUPPORTED);
        m.put("isLocal", isLocal ? (Object) options.isLocal() : UNSUPPORTED);
        m.put("artifactsDir", artifactsDir ? (Object) traced : UNSUPPORTED);
        if (!noDefaults) {
            m.put("defaultOverridesAccepted", true);
        }
        return m;
    }

    @Override
    public String toString() {
        return "ConnectSupport{" + version + ", noDefaults=" + noDefaults + ", isLocal=" + isLocal
                + ", artifactsDir=" + artifactsDir + "}";
    }

    private static boolean hasField(Class<?> type, String name) {
        try {
            type.getField(name);
            return true;
        } catch (NoSuchFieldException | SecurityException e) {
            return false;
        }
    }

    private static String playwrightVersion() {
        try {
            Package p = Playwright.class.getPackage();
            String v = p == null ? null : p.getImplementationVersion();
            return v == null || v.isBlank() ? null : v;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
