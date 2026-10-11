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

package ai.skillroy.testjava.agent.swing;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static ai.skillroy.testjava.agent.swing.JsonCheck.list;
import static ai.skillroy.testjava.agent.swing.JsonCheck.map;
import static ai.skillroy.testjava.agent.swing.LocatorTest.onEdt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code swing.rendering}'s key over fixed inputs (phase7-contracts.md sections 3.8, 13.1 item 1, D350): the keyed set
 * only, so a changed vendor, full version or OS leaves it; a changed specification version, look and feel,
 * antialiasing, screen scale or probe hash changes it; the probe render is stable within one JVM.
 */
class RenderingTest {

    private static Map<String, Object> inputs(String vendor, String version, String spec, String os, String laf,
                                              String probe, double scale) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("jdk", map("vendor", vendor, "version", version, "specification", spec));
        m.put("os", map("name", os, "version", "6.1", "arch", "aarch64"));
        m.put("lookAndFeel", laf);
        m.put("antialiasing", map("desktopHints", list("Text-specific antialiasing enable key=LCD HRGB"),
                "property", null));
        m.put("screenScale", Double.valueOf(scale));
        m.put("probeHash", probe);
        return m;
    }

    @Test
    void theKeyHashesWhatRendersAndNothingElse() {
        String base = Rendering.renderKey(inputs("Eclipse Adoptium", "25.0.1+8", "25", "Linux", "Metal", "sha256:a",
                1.0));
        assertEquals(16, base.length());
        assertTrue(base.matches("[0-9a-f]{16}"), base);
        assertEquals(base, Rendering.renderKey(inputs("Other Vendor", "25.0.2+1", "25", "Mac OS X", "Metal",
                "sha256:a", 1.0)), "vendor, full version and OS are not keyed");
        assertNotEquals(base, Rendering.renderKey(inputs("Eclipse Adoptium", "1.8.0_504", "1.8", "Linux", "Metal",
                "sha256:a", 1.0)), "the Java line is keyed");
        assertNotEquals(base, Rendering.renderKey(inputs("Eclipse Adoptium", "25.0.1+8", "25", "Linux", "Nimbus",
                "sha256:a", 1.0)));
        assertNotEquals(base, Rendering.renderKey(inputs("Eclipse Adoptium", "25.0.1+8", "25", "Linux", "Metal",
                "sha256:b", 1.0)));
        assertNotEquals(base, Rendering.renderKey(inputs("Eclipse Adoptium", "25.0.1+8", "25", "Linux", "Metal",
                "sha256:a", 2.0)));
        Map<String, Object> aa = inputs("Eclipse Adoptium", "25.0.1+8", "25", "Linux", "Metal", "sha256:a", 1.0);
        aa.put("antialiasing", map("desktopHints", list(), "property", "on"));
        assertNotEquals(base, Rendering.renderKey(aa));
    }

    @Test
    void theProbeRenderIsStableWithinOneJvm() throws Exception {
        String first = onEdt(() -> Capture.hash(Rendering.probe()));
        String second = onEdt(() -> Capture.hash(Rendering.probe()));
        assertEquals(first, second);
        assertEquals(Rendering.PROBE_WIDTH, (int) onEdt(() -> Rendering.probe().getWidth()));
        assertEquals(list("jdk.specification", "lookAndFeel", "antialiasing", "screenScale", "probeHash"),
                new java.util.ArrayList<Object>(Rendering.KEYED));
    }
}
