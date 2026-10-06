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

package ai.skillroy.testjava.agent.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bootstrap runs in {@code premain}, before the application's {@code main}: it must not refer to AWT at all
 * (contract amendment A4), so loading it can never initialise the toolkit or freeze the headless decision.
 */
class NoAwtTest {

    @Test
    void noBootstrapClassRefersToAwtOrSwing() throws Exception {
        File classes = new File(BootLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File[] files = new File(classes, "ai/skillroy/testjava/agent/bootstrap").listFiles();
        assertTrue(files != null && files.length >= 5, "compiled bootstrap classes in " + classes);
        List<String> referring = new ArrayList<String>();
        for (File file : files) {
            if (file.getName().endsWith(".class")) {
                String bytes = new String(Files.readAllBytes(file.toPath()), Charset.forName("ISO-8859-1"));
                if (bytes.contains("java/awt/") || bytes.contains("javax/swing/")) {
                    referring.add(file.getName());
                }
            }
        }
        assertTrue(referring.isEmpty(), referring.toString());
    }
}
