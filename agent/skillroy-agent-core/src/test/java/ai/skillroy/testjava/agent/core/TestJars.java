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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds jars from compiled test classes at run time. */
final class TestJars {

    private TestJars() {
    }

    /** A jar with every class of the provider's package and a service file naming the provider. */
    static void moduleJar(File jar, String providerClass) throws IOException {
        Map<String, byte[]> entries = packageClasses(providerClass.substring(0, providerClass.lastIndexOf('.')));
        entries.put(ModuleLoader.SERVICE_FILE, (providerClass + "\n").getBytes(AgentHarness.UTF8));
        jar(jar, entries);
    }

    /** All .class files of a package from the test class path. */
    static Map<String, byte[]> packageClasses(String packageName) throws IOException {
        String path = packageName.replace('.', '/');
        URL url = TestJars.class.getClassLoader().getResource(path);
        if (url == null || !"file".equals(url.getProtocol())) {
            throw new IOException("package not found as a directory: " + packageName);
        }
        File dir;
        try {
            dir = new File(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".class")) {
                    entries.put(path + "/" + f.getName(), Files.readAllBytes(f.toPath()));
                }
            }
        }
        return entries;
    }

    /** The bytes of a class-path resource (from a directory or a jar). */
    static byte[] resourceBytes(String name) throws IOException {
        java.io.InputStream in = TestJars.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IOException("no resource " + name);
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static void jar(File jar, Map<String, byte[]> entries) throws IOException {
        JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
        try {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }
}
