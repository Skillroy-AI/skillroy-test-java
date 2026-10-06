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

package ai.skillroy.testjava.agent.core;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract amendment A4, statically: every reference of the core to AWT or Swing lives in {@link Awt}, whose AWT
 * calls are guarded or belong to the EDT thread policies. With that, a start-up path can only reach AWT through
 * {@code Awt}, which the child-JVM test in the dist module checks at run time (no AWT class is loaded, no
 * event-dispatch thread appears, the headless decision stays the application's).
 */
class AwtIsolationTest {

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    @Test
    void onlyTheAwtClassRefersToAwt() throws IOException, URISyntaxException {
        File classes = new File(Awt.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(classes.isDirectory(), "main classes are a directory under surefire: " + classes);
        File pkg = new File(classes, "ai/skillroy/testjava/agent/core");
        File[] files = pkg.listFiles();
        assertTrue(files != null && files.length > 10, "compiled core classes in " + pkg);
        List<String> referring = new ArrayList<String>();
        for (File file : files) {
            if (!file.getName().endsWith(".class")) {
                continue;
            }
            String bytes = new String(Files.readAllBytes(file.toPath()), LATIN1);
            if (bytes.contains("java/awt/") || bytes.contains("javax/swing/")) {
                referring.add(file.getName());
            }
        }
        assertEquals(java.util.Collections.singletonList("Awt.class"), referring);
    }

    /**
     * Security findings S12 and S16, statically: the core neither uses the JDK's HTTP server (which on Java 8
     * initialises {@code java.util.logging} when it is created) nor {@code java.util.logging} itself, so that no
     * start path can fix the host's log manager before the application's {@code main}. The child-JVM test in the
     * dist module checks the same at run time.
     */
    @Test
    void noCoreClassRefersToJavaUtilLoggingOrTheJdkHttpServer() throws IOException, URISyntaxException {
        File classes = new File(Awt.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File[] files = new File(classes, "ai/skillroy/testjava/agent/core").listFiles();
        assertTrue(files != null && files.length > 10);
        List<String> referring = new ArrayList<String>();
        for (File file : files) {
            if (!file.getName().endsWith(".class")) {
                continue;
            }
            String bytes = new String(Files.readAllBytes(file.toPath()), LATIN1);
            if (bytes.contains("java/util/logging/") || bytes.contains("com/sun/net/httpserver/")
                    || bytes.contains("sun/net/httpserver")) {
                referring.add(file.getName());
            }
        }
        assertEquals(java.util.Collections.<String>emptyList(), referring);
    }

    @Test
    void askingWhetherTheAgentIsOnTheEdtNeedsNoAwtOffIt() {
        assertFalse(Thread.currentThread().getName().startsWith(Awt.EDT_PREFIX));
        assertFalse(Awt.onEventDispatchThread(), "decided by the thread name alone");
    }
}
