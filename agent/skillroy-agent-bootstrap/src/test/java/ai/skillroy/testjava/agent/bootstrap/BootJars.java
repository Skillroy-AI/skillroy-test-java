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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Jar and fake-agent-home builders for the bootstrap tests. */
final class BootJars {

    static final String FAKE_RUNTIME = "ai/skillroy/testjava/agent/core/AgentRuntime.class";

    private BootJars() {
    }

    /** {@code <dir>/lib/fake-core.jar} holding the runtime double and its nested classes. */
    static File fakeHome(File dir) throws IOException {
        File lib = new File(dir, "lib");
        if (!lib.mkdirs() && !lib.isDirectory()) {
            throw new IOException("cannot create " + lib);
        }
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put(FAKE_RUNTIME, resourceBytes(FAKE_RUNTIME));
        // Nested classes must come from the same jar: a package-private member is not accessible across loaders.
        String nestedPrefix = FAKE_RUNTIME.substring(0, FAKE_RUNTIME.length() - ".class".length()) + "$";
        for (int i = 1; i < 10; i++) {
            String nested = nestedPrefix + i + ".class";
            if (BootJars.class.getClassLoader().getResource(nested) != null) {
                entries.put(nested, resourceBytes(nested));
            }
        }
        jar(new File(lib, "fake-core.jar"), entries);
        return dir;
    }

    static byte[] resourceBytes(String name) throws IOException {
        InputStream in = BootJars.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IOException("no resource " + name);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
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
