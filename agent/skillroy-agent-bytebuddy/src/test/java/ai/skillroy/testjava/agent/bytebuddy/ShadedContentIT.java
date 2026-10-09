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

package ai.skillroy.testjava.agent.bytebuddy;

import ai.skillroy.testjava.agent.api.instrument.ClassFileRefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relocated Byte Buddy as it ships (phase6-contracts.md 2.1, D115, section 13.1 item 2): a missing relocation fails
 * silently (BS3 RESULT section 9 fact 10: an unrelocated engine runs on the host's copy and still works), so this
 * test reads the shaded jar itself. No entry under {@code net/}, every class under the relocated prefix, no
 * constant-pool class reference to {@code net/bytebuddy/}, {@code org/objectweb/asm/} or {@code com/sun/jna/}, the
 * three third-party notice files and no other licence file of the dependency, the project's own LICENSE and NOTICE,
 * no {@code META-INF/versions} and no {@code module-info.class}.
 */
class ShadedContentIT {

    private static final String PREFIX = "ai/skillroy/testjava/agent/hooks/shaded/";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static File jar;
    private static File root;

    @BeforeAll
    static void locate() {
        jar = new File(System.getProperty("skillroy.bytebuddy.jar", "target/skillroy-agent-bytebuddy.jar"));
        assertTrue(jar.isFile(), "the shaded jar: " + jar);
        root = new File(System.getProperty("skillroy.root.dir", "../.."));
        assertTrue(new File(root, "LICENSE").isFile(), "the reactor root: " + root);
    }

    @Test
    void everyClassIsRelocatedAndReferencesNoUnrelocatedName() throws IOException {
        ZipFile zip = new ZipFile(jar);
        try {
            int classes = 0;
            List<String> problems = new ArrayList<String>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.startsWith("net/") || name.startsWith("com/") || name.startsWith("org/")) {
                    problems.add("entry outside the project's prefix: " + name);
                }
                if (name.startsWith("META-INF/versions") || name.endsWith("module-info.class")) {
                    problems.add("dropped entry present: " + name);
                }
                if (!name.endsWith(".class")) {
                    continue;
                }
                classes++;
                if (!name.startsWith(PREFIX)) {
                    problems.add("class outside " + PREFIX + ": " + name);
                }
                for (String ref : ClassFileRefs.classReferences(read(zip, entry))) {
                    if (ref.startsWith("net/bytebuddy/") || ref.startsWith("org/objectweb/asm/")
                            || ref.startsWith("com/sun/jna/")) {
                        problems.add(name + " references " + ref);
                    }
                }
            }
            assertTrue(problems.isEmpty(), problems.size() + " problems, first: "
                    + problems.subList(0, Math.min(20, problems.size())));
            assertTrue(classes > 3000, "Byte Buddy's classes are all here: " + classes);
            assertTrue(zip.getEntry(PREFIX + "bytebuddy/agent/builder/AgentBuilder.class") != null);
            assertTrue(zip.getEntry(PREFIX + "bytebuddy/jar/asm/ClassReader.class") != null, "ASM travels inside");
        } finally {
            zip.close();
        }
    }

    @Test
    void theNoticesAreTheThreeThirdPartyFilesAndTheProjectsOwn() throws IOException {
        ZipFile zip = new ZipFile(jar);
        try {
            List<String> legal = new ArrayList<String>();
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                String upper = name.toUpperCase(Locale.ROOT);
                if (!name.endsWith("/") && (upper.contains("LICENSE") || upper.contains("NOTICE")
                        || upper.contains("LICENCE"))) {
                    legal.add(name);
                }
            }
            java.util.Collections.sort(legal);
            assertEquals(java.util.Arrays.asList("META-INF/LICENSE", "META-INF/NOTICE",
                    "META-INF/third-party/asm/LICENSE", "META-INF/third-party/byte-buddy/LICENSE",
                    "META-INF/third-party/byte-buddy/NOTICE"), legal);
            for (String file : new String[] {"LICENSE", "NOTICE"}) {
                assertArrayEquals(Files.readAllBytes(new File(root, file).toPath()),
                        read(zip, zip.getEntry("META-INF/" + file)), "META-INF/" + file + " is the project's");
            }
            assertTrue(new String(read(zip, zip.getEntry("META-INF/third-party/byte-buddy/NOTICE")), UTF8)
                    .contains("Rafael Winterhalter"));
            assertTrue(new String(read(zip, zip.getEntry("META-INF/third-party/asm/LICENSE")), UTF8)
                    .contains("INRIA"));
            assertTrue(new String(read(zip, zip.getEntry("META-INF/third-party/byte-buddy/LICENSE")), UTF8)
                    .contains("Apache License"));
            assertTrue(zip.getEntry("META-INF/licenses/ASM") == null);
        } finally {
            zip.close();
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        assertTrue(entry != null, "missing entry");
        InputStream in = zip.getInputStream(entry);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
