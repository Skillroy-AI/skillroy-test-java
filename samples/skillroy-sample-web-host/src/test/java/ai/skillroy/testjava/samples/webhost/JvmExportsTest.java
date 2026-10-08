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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The JVM arguments off-screen rendering needs on Java 16+, and how this module's test JVMs receive them. */
class JvmExportsTest {

    @Test
    void theArgumentsExportExactlyThePairsTheDiagnosticChecks() {
        List<String> pairs = new ArrayList<String>();
        List<String> args = WebHostApp.OFF_SCREEN_JVM_ARGS;
        assertEquals(6, args.size());
        for (int i = 0; i < args.size(); i += 2) {
            assertEquals("--add-exports", args.get(i));
            assertTrue(args.get(i + 1).endsWith("=ALL-UNNAMED"), args.get(i + 1));
            pairs.add(args.get(i + 1).substring(0, args.get(i + 1).indexOf('=')));
        }
        assertEquals(JvmExports.OFF_SCREEN_EXPORTS, pairs);
    }

    @Test
    void theFeatureVersionIsThisJvms() {
        String spec = System.getProperty("java.specification.version");
        assertEquals(spec.startsWith("1.") ? spec.substring(2) : spec, String.valueOf(JvmExports.javaFeatureVersion()));
        assertTrue(JvmExports.javaFeatureVersion() >= 8);
    }

    /**
     * Under this module's surefire configuration the exports arrive through {@code JDK_JAVA_OPTIONS}: applied by Java 9
     * and newer, ignored by Java 8 (which has no modules, so nothing is missing there either).
     */
    @Test
    void thisModulesTestJvmsHaveTheExports() {
        String options = System.getenv("JDK_JAVA_OPTIONS");
        assumeTrue(options != null && options.contains("--add-exports"), "run by this module's surefire configuration");
        assertEquals(new ArrayList<String>(), JvmExports.missingOffScreenExports());
    }
}
