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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard of the normal build (phase4-contracts.md section 12.1 item 5): no unit test of this module creates a
 * Playwright instance, because the first one extracts the Playwright driver (about 140 MB) and starts it. Three
 * checks: this test JVM carries the property that makes the module's only factory refuse; that factory refuses; and
 * no compiled class of the module, main or test, calls {@code Playwright.create} except the factory itself.
 */
class NoPlaywrightInUnitTestsTest {

    private static final String PLAYWRIGHT = "com/microsoft/playwright/Playwright";

    @Test
    void thisTestJvmForbidsTheDriver() {
        assertTrue(Boolean.getBoolean(PlaywrightFactory.FORBID_PROPERTY), "the surefire configuration of"
                + " skillroy-cdp must set -D" + PlaywrightFactory.FORBID_PROPERTY + "=true");
    }

    @Test
    void theModulesFactoryRefusesWithoutCreatingAnything() {
        PlaywrightFactory.Forbidden e = assertThrows(PlaywrightFactory.Forbidden.class,
                () -> PlaywrightFactory.DEFAULT.create(Map.of()));
        assertTrue(e.getMessage().contains(PlaywrightFactory.FORBID_PROPERTY), e.getMessage());
    }

    @Test
    void noTestClassCallsPlaywrightCreate() throws Exception {
        List<String> calls = createCalls(classesOf(NoPlaywrightInUnitTestsTest.class));
        assertTrue(calls.isEmpty(), "unit tests must use a fake factory: " + calls);
    }

    @Test
    void onlyTheFactoryCallsPlaywrightCreateInTheModule() throws Exception {
        List<String> calls = createCalls(classesOf(CdpWebPath.class));
        assertFalse(calls.isEmpty(), "the production factory calls it");
        for (String call : calls) {
            assertTrue(call.startsWith("ai/skillroy/testjava/cdp/PlaywrightFactory."), call);
        }
    }

    @Test
    void theDriverEnvironmentSkipsTheBrowserDownload() {
        Map<String, String> env = PlaywrightFactory.environment();
        assertEquals("1", env.get("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"));
        for (String name : env.keySet()) {
            assertTrue(name.equals("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD") || name.equals("PLAYWRIGHT_BROWSERS_PATH")
                    || name.equals("PLAYWRIGHT_DRIVER_TMPDIR"), name);
        }
    }

    private static Path classesOf(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** {@code <class>.<method>} of every invocation of {@code Playwright.create} under {@code root}. */
    private static List<String> createCalls(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                for (MethodModel method : model.methods()) {
                    method.code().ifPresent(code -> {
                        for (CodeElement element : code) {
                            if (element instanceof InvokeInstruction invoke
                                    && invoke.owner().asInternalName().equals(PLAYWRIGHT)
                                    && invoke.name().equalsString("create")) {
                                out.add(model.thisClass().asInternalName() + "." + method.methodName()
                                        .stringValue());
                            }
                        }
                    });
                }
            }
        }
        return out;
    }
}
