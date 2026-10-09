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

package ai.skillroy.testjava.agent.dist;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exact content of the assembled agent home, as a directory and as a zip: the jars, and the licence files at the
 * top level. Every jar carries the project's licence files in {@code META-INF} and holds nothing outside
 * {@code ai/skillroy/} and {@code META-INF/}. The agent contains no third-party code, except that its optional hooks
 * module ({@code optional/skillroy-agent-hooks.jar}, loaded only when the agent option {@code optionalModules} names
 * it) carries a relocated copy of Byte Buddy (Apache-2.0) and the ASM it bundles (BSD-3-Clause), whose notices travel
 * inside that jar under {@code META-INF/third-party/} (phase6-contracts.md sections 2.1 and 2.5, D114, D115). Two
 * rules keep it so: no jar but {@code skillroy-agent-spy.jar} holds an entry of {@code Spy}'s package (D116: the
 * package exists once in a JVM), and the hooks jar holds no entry under {@code net/} (the relocation missed nothing).
 */
class DistributionContentIT {

    /** Everything the agent home holds, relative to its root. A new file must be added here on purpose. */
    private static final List<String> EXPECTED = Collections.unmodifiableList(Arrays.asList(
            "LICENSE",
            "NOTICE",
            "lib/skillroy-agent-api.jar",
            "lib/skillroy-agent-core.jar",
            "lib/skillroy-protocol.jar",
            "modules/skillroy-agent-data.jar",
            "modules/skillroy-agent-swing.jar",
            "modules/skillroy-agent-web.jar",
            // Phase 6 (phase6-contracts.md 2.1, 2.5; BW): the optional Byteman module and its system-class-path jar.
            "optional/skillroy-agent-byteman-sys.jar",
            "optional/skillroy-agent-byteman.jar",
            "optional/skillroy-agent-hooks.jar",
            "skillroy-agent-bootstrap.jar",
            "skillroy-agent-spy.jar"));

    private static File base;
    private static File home;
    private static File rootDir;

    @BeforeAll
    static void locate() {
        base = new File(System.getProperty("basedir", System.getProperty("user.dir"))).getAbsoluteFile();
        home = new File(base, "target/skillroy-agent");
        assertTrue(new File(home, "skillroy-agent-bootstrap.jar").isFile(), "agent home not assembled: " + home);
        rootDir = new File(System.getProperty("skillroy.root.dir", new File(base, "../..").getPath()));
        assertTrue(new File(rootDir, "LICENSE").isFile(), "no LICENSE in the reactor root " + rootDir);
        assertTrue(new File(rootDir, "NOTICE").isFile(), "no NOTICE in the reactor root " + rootDir);
    }

    @Test
    void theAgentHomeDirectoryHoldsExactlyTheseFiles() throws IOException {
        assertEquals(new TreeSet<String>(EXPECTED), filesUnder(home.toPath()));
    }

    @Test
    void theZipHoldsExactlyTheseFilesUnderOneTopDirectory() throws IOException {
        Set<String> inZip = new TreeSet<String>();
        ZipFile zip = new ZipFile(new File(base, "target/skillroy-agent.zip"));
        try {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory()) {
                    String name = entry.getName();
                    assertTrue(name.startsWith("skillroy-agent/"), "entry outside the top directory: " + name);
                    inZip.add(name.substring("skillroy-agent/".length()));
                }
            }
            assertEquals(new TreeSet<String>(EXPECTED), inZip);
            for (String file : new String[] {"LICENSE", "NOTICE"}) {
                assertArrayEquals(Files.readAllBytes(new File(rootDir, file).toPath()),
                        read(zip, zip.getEntry("skillroy-agent/" + file)), file + " in the zip");
            }
        } finally {
            zip.close();
        }
    }

    @Test
    void theTopLevelLicenceAndNoticeAreCopiesOfTheRootFiles() throws IOException {
        for (String file : new String[] {"LICENSE", "NOTICE"}) {
            assertArrayEquals(Files.readAllBytes(new File(rootDir, file).toPath()),
                    Files.readAllBytes(new File(home, file).toPath()), file + " in the agent home");
        }
        assertTrue(new String(Files.readAllBytes(new File(home, "LICENSE").toPath()), ChildJvm.UTF8)
                .contains("Apache License"), "LICENSE is the Apache licence text");
    }

    /** D116: the spy package exists in one jar only, the one the hooks module appends to the bootstrap path. */
    @Test
    void noJarButTheSpyJarHoldsAnEntryOfTheSpyPackage() throws IOException {
        for (String jarName : EXPECTED) {
            if (!jarName.endsWith(".jar")) {
                continue;
            }
            int spyEntries = 0;
            ZipFile jar = new ZipFile(new File(home, jarName));
            try {
                for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    if (name.startsWith("ai/skillroy/testjava/agent/spy/") && !name.endsWith("/")) {
                        spyEntries++;
                        assertEquals("skillroy-agent-spy.jar", jarName, name + " in " + jarName);
                    }
                }
            } finally {
                jar.close();
            }
            if ("skillroy-agent-spy.jar".equals(jarName)) {
                assertEquals(3, spyEntries, "Spy and its two nested types");
            }
        }
    }

    /** D115: the relocation missed nothing, so no class of the dependency's own package ships. */
    @Test
    void theHooksJarHoldsNothingUnderNet() throws IOException {
        ZipFile jar = new ZipFile(new File(home, "optional/skillroy-agent-hooks.jar"));
        try {
            int shaded = 0;
            for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                assertTrue(!name.startsWith("net/"), "unrelocated entry " + name);
                if (name.startsWith("ai/skillroy/testjava/agent/hooks/shaded/bytebuddy/")) {
                    shaded++;
                }
            }
            assertTrue(shaded > 3000, "the relocated Byte Buddy is merged in: " + shaded);
            for (String notice : new String[] {"META-INF/third-party/byte-buddy/LICENSE",
                "META-INF/third-party/byte-buddy/NOTICE", "META-INF/third-party/asm/LICENSE"}) {
                assertTrue(jar.getEntry(notice) != null, notice);
            }
            assertTrue(jar.getEntry("META-INF/services/ai.skillroy.testjava.agent.api.AgentModule") != null);
        } finally {
            jar.close();
        }
    }

    @Test
    void everyJarCarriesTheLicenceFilesAndOnlyTheProjectsPrefix() throws IOException {
        for (String jarName : EXPECTED) {
            if (!jarName.endsWith(".jar")) {
                continue;
            }
            ZipFile jar = new ZipFile(new File(home, jarName));
            try {
                for (String file : new String[] {"LICENSE", "NOTICE"}) {
                    ZipEntry entry = jar.getEntry("META-INF/" + file);
                    assertTrue(entry != null, jarName + " has no META-INF/" + file);
                    assertArrayEquals(Files.readAllBytes(new File(rootDir, file).toPath()), read(jar, entry),
                            "META-INF/" + file + " in " + jarName);
                }
                for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    assertTrue(name.startsWith("ai/skillroy/") || name.startsWith("META-INF/")
                            || "ai/".equals(name) || "META-INF/".equals(name), "foreign entry " + name + " in " + jarName);
                }
            } finally {
                jar.close();
            }
        }
    }

    private static Set<String> filesUnder(final Path root) throws IOException {
        final Set<String> files = new TreeSet<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                files.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
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
