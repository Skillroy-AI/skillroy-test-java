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

import ai.skillroy.testjava.agent.api.instrument.ClassFileRefs;
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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every class of every jar of the assembled agent home references, in its constant pool's {@code CONSTANT_Class}
 * entries, nothing outside {@code java/}, {@code javax/}, {@code sun/}, {@code com/sun/}, {@code jdk/} and
 * {@code ai/skillroy/}, and nothing under {@code net/bytebuddy/}, {@code org/objectweb/asm/} or {@code com/sun/jna/}
 * (phase6-contracts.md section 2.5, D152): the mechanical form of the licence audit's manual step and the proof that the
 * relocation of Byte Buddy missed no class (an unrelocated copy fails silently, BS3 RESULT section 9 fact 10). A type
 * that appears only in a method or field descriptor is a UTF-8 entry and is not read here; the Byteman module's sys jar
 * names Byteman's types that way only.
 */
class HomeConstantPoolIT {

    private static final String[] ALLOWED = {"java/", "javax/", "sun/", "com/sun/", "jdk/", "ai/skillroy/"};
    private static final String[] FORBIDDEN = {"net/bytebuddy/", "org/objectweb/asm/", "com/sun/jna/"};

    @Test
    void everyClassOfTheHomeReferencesTheJdkAndTheProjectOnly() throws IOException {
        String base = System.getProperty("basedir", System.getProperty("user.dir"));
        File home = new File(base, "target/skillroy-agent").getAbsoluteFile();
        assertTrue(new File(home, "skillroy-agent-bootstrap.jar").isFile(), "agent home not assembled: " + home);
        final List<File> jars = new ArrayList<File>();
        Files.walkFileTree(home.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".jar")) {
                    jars.add(file.toFile());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue(jars.size() >= 9, "every jar of the home: " + jars);
        List<String> problems = new ArrayList<String>();
        int classes = 0;
        for (File jar : jars) {
            ZipFile zip = new ZipFile(jar);
            try {
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry entry = e.nextElement();
                    if (!entry.getName().endsWith(".class")) {
                        continue;
                    }
                    classes++;
                    for (String ref : ClassFileRefs.classReferences(read(zip, entry))) {
                        if (!allowed(ref)) {
                            problems.add(jar.getName() + "!" + entry.getName() + " references " + ref);
                        }
                    }
                }
            } finally {
                zip.close();
            }
        }
        assertTrue(classes > 3000, "the relocated Byte Buddy is scanned too: " + classes);
        assertTrue(problems.isEmpty(), problems.size() + " references outside the allowed prefixes, first: "
                + problems.subList(0, Math.min(20, problems.size())));
    }

    static boolean allowed(String ref) {
        for (String prefix : FORBIDDEN) {
            if (ref.startsWith(prefix)) {
                return false;
            }
        }
        for (String prefix : ALLOWED) {
            if (ref.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theRuleNamesJnaAlthoughComSunIsAllowed() {
        assertTrue(allowed("com/sun/net/httpserver/HttpServer"));
        assertTrue(!allowed("com/sun/jna/Native"), "D152: com/sun/jna/ would pass the com/sun/ allowance unnamed");
        assertTrue(!allowed("net/bytebuddy/ByteBuddy"));
        assertTrue(!allowed("org/objectweb/asm/ClassReader"));
        assertTrue(!allowed("org/example/App"));
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
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
