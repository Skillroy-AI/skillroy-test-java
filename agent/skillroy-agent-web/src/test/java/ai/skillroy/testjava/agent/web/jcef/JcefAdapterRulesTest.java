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

package ai.skillroy.testjava.agent.web.jcef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two rules of the {@code jcef} adapter that need no browser, checked on every test JVM: the windowed-on-Java-8 mark
 * of the strategy (phase4-contracts.md section 2.5) and the age check of a {@code DevToolsActivePort} file (section
 * 4.5: a file older than the JVM is a previous run's).
 */
class JcefAdapterRulesTest {

    @Test
    void theWindowedJava8MarkNeedsWindowedRenderingAndAJava8Target() {
        assertEquals(", windowed, Java 8: unverified", JcefAdapter.strategySuffix(Boolean.FALSE, "1.8"));
        assertEquals("", JcefAdapter.strategySuffix(Boolean.TRUE, "1.8"), "off screen");
        assertEquals("", JcefAdapter.strategySuffix(null, "1.8"), "the mode unknown");
        assertEquals("", JcefAdapter.strategySuffix(Boolean.FALSE, "9"));
        assertEquals("", JcefAdapter.strategySuffix(Boolean.FALSE, "25"));
        assertEquals("", JcefAdapter.strategySuffix(Boolean.FALSE, null), "the version unknown");
    }

    @Test
    void aDevToolsActivePortFileOlderThanTheBoundIsIgnored(@TempDir Path dir) throws Exception {
        File file = dir.resolve("DevToolsActivePort").toFile();
        Files.write(file.toPath(), "9444\n/devtools/browser/fake\n".getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        assertEquals(Integer.valueOf(9444), JcefAdapter.activePort(dir.toString(), now - 60000));
        assertTrue(file.setLastModified(now - 120000));
        assertNull(JcefAdapter.activePort(dir.toString(), now - 60000), "written before the bound");
        assertEquals(Integer.valueOf(9444), JcefAdapter.activePort(dir.toString(), 0), "0: no time checked");
        assertNull(JcefAdapter.activePort(dir.resolve("nothing").toString(), 0));
        assertNull(JcefAdapter.activePort(null, 0));
    }
}
