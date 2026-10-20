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
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConnectSupport} (Phase 8, phase8-contracts.md 5.3, D552, section 13.1 row 19): detection by field presence on
 * the real 1.63.0 options class finds all three options and the manifest's version; the {@code of} seam stands for an
 * older line; the record's {@code connectOptions} per line.
 */
class ConnectSupportTest {

    /** An options class of a line before 1.58: none of the three fields. */
    static final class OldOptions {
        public Double timeout;
        public Double slowMo;
    }

    @Test
    void detectionOnTheRealClassFindsAllThreeOptionsAndTheVersion() {
        ConnectSupport real = ConnectSupport.detect(BrowserType.ConnectOverCDPOptions.class);
        assertTrue(real.noDefaults());
        assertTrue(real.isLocal());
        assertTrue(real.artifactsDir());
        assertEquals("1.63.0", real.version(), "the Playwright jar's Implementation-Version");
        ConnectSupport memo = ConnectSupport.detected();
        assertSame(memo, ConnectSupport.detected(), "decided once per JVM");
        assertTrue(memo.noDefaults() && memo.isLocal() && memo.artifactsDir());
    }

    @Test
    void aClassWithoutTheFieldsHasNoneOfTheOptions() {
        ConnectSupport old = ConnectSupport.detect(OldOptions.class);
        assertFalse(old.noDefaults());
        assertFalse(old.isLocal());
        assertFalse(old.artifactsDir());
    }

    @Test
    void theSeamStandsForAnOlderLine() {
        ConnectSupport line160 = ConnectSupport.of(Set.of("noDefaults", "isLocal"), "1.60.0");
        assertTrue(line160.noDefaults());
        assertTrue(line160.isLocal());
        assertFalse(line160.artifactsDir());
        assertEquals("1.60.0", line160.version());
        ConnectSupport repackaged = ConnectSupport.of(Set.of(), null);
        assertNull(repackaged.version());
        assertTrue(repackaged.noDefaultsRefusal().contains("(unknown)"), repackaged.noDefaultsRefusal());
        assertEquals("the Playwright on the class path (1.59.0) cannot attach without overriding the application's"
                + " browser context (no noDefaults; Playwright 1.60 or later has it); set"
                + " -Dskillroy.cdp.allowDefaultOverrides=true to accept the overrides",
                ConnectSupport.of(Set.of("isLocal"), "1.59.0").noDefaultsRefusal());
    }

    @Test
    void theRecordsConnectOptionsSayWhatWasApplied() {
        CdpOptions options = CdpOptions.defaults();
        ConnectSupport full = ConnectSupport.of(Set.of("noDefaults", "isLocal", "artifactsDir"), "1.63.0");
        assertEquals(Map.of("noDefaults", true, "isLocal", true, "artifactsDir", false),
                full.connectOptions(options, false));
        assertEquals(Map.of("noDefaults", true, "isLocal", true, "artifactsDir", true),
                full.connectOptions(options, true));
        assertEquals(Map.of("noDefaults", "unsupported", "isLocal", "unsupported", "artifactsDir", "unsupported",
                "defaultOverridesAccepted", true), ConnectSupport.of(Set.of(), "1.57.0").connectOptions(options, true));
    }
}
