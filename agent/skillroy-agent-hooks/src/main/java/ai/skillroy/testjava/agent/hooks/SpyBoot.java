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


package ai.skillroy.testjava.agent.hooks;

import ai.skillroy.testjava.agent.api.instrument.ClassFileRefs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Puts {@code Spy} on the bootstrap search path and looks at it, by name and reflection only (phase6-contracts.md
 * sections 2.1, 3.2 and 3.3 item 6): nothing here names a {@code Spy} type. The append is the agent's most privileged
 * act (whatever the jar held would be bootstrap-defined code for the JVM's life), so the jar's content is checked
 * first: Spy, its two nested classes, directory entries and {@code META-INF/{MANIFEST.MF,LICENSE,NOTICE}}, each class
 * a {@code --release 8} class file whose constant pool names no class outside {@code java/} but its own three.
 *
 * <p>Stateless; safe from any thread.
 */
final class SpyBoot {

    static final String SPY = "ai.skillroy.testjava.agent.spy.Spy";
    static final String PACKAGE = "ai/skillroy/testjava/agent/spy/";
    static final String RESOURCE = PACKAGE + "Spy.class";
    /** The {@code Spy.VERSION} this engine was built against (a test checks it equals the class's constant). */
    static final int VERSION = 1;

    private static final List<String> CLASSES = Arrays.asList(PACKAGE + "Spy", PACKAGE + "Spy$Handler",
            PACKAGE + "Spy$Stub");
    private static final Set<String> FILES = new HashSet<String>(Arrays.asList(PACKAGE + "Spy.class",
            PACKAGE + "Spy$Handler.class", PACKAGE + "Spy$Stub.class", "META-INF/MANIFEST.MF", "META-INF/LICENSE",
            "META-INF/NOTICE"));

    private SpyBoot() {
    }

    /** The bootstrap loader's {@code Spy}, or null while none is on the bootstrap path. Never loads or initialises. */
    static Class<?> bootSpy() {
        try {
            return Class.forName(SPY, false, null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (LinkageError e) {
            return null;
        }
    }

    /** The {@code VERSION} of a loaded {@code Spy}, or -1 when unreadable. */
    static int version(Class<?> spy) {
        try {
            return spy.getField("VERSION").getInt(null);
        } catch (Exception e) {
            return -1;
        } catch (LinkageError e) {
            return -1;
        }
    }

    /**
     * The content check of section 3.2 step 3.
     *
     * @return null when the jar may be appended, else the reason it may not
     */
    static String checkJar(File jar) {
        JarFile file;
        try {
            file = new JarFile(jar);
        } catch (IOException e) {
            return "spy jar cannot be read: " + jar + ": " + e.getMessage();
        }
        try {
            int classes = 0;
            for (Enumeration<JarEntry> e = file.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.endsWith("/")) {
                    continue;
                }
                if (!FILES.contains(name)) {
                    return "spy jar holds more than Spy: " + name;
                }
                if (!name.endsWith(".class")) {
                    continue;
                }
                classes++;
                byte[] bytes = read(file, entry);
                try {
                    int major = ClassFileRefs.majorVersion(bytes);
                    if (major != 52) {
                        return "spy jar class " + name + " is not a --release 8 class file (major " + major + ")";
                    }
                    for (String ref : ClassFileRefs.classReferences(bytes)) {
                        if (!ref.startsWith("java/") && !CLASSES.contains(ref)) {
                            return "spy jar class " + name + " references " + ref + ", outside java/";
                        }
                    }
                } catch (IllegalArgumentException malformed) {
                    return "spy jar class " + name + " is malformed: " + malformed.getMessage();
                }
            }
            if (file.getEntry(RESOURCE) == null) {
                return "spy jar holds no Spy: " + jar;
            }
            return classes == 0 ? "spy jar holds no class: " + jar : null;
        } catch (IOException e) {
            return "spy jar cannot be read: " + jar + ": " + e.getMessage();
        } finally {
            try {
                file.close();
            } catch (IOException ignored) {
                // read-only
            }
        }
    }

    /** Appends the jar and loads {@code Spy} from the bootstrap loader; at most once per JVM (the caller's rule). */
    static Class<?> append(Instrumentation inst, File jar) throws IOException, ClassNotFoundException {
        inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
        return Class.forName(SPY, true, null);
    }

    /**
     * Whether the loader resolves {@code Spy} to the bootstrap copy (section 3.3 item 6): VISIBLE, NOT_VISIBLE, or,
     * before any append, UNCHECKED unless the loader would shadow it (its {@code getResource} finds a copy).
     *
     * @return {@code {state, reason}}
     */
    static String[] visibility(ClassLoader loader, Class<?> boot) {
        if (loader == null) {
            return new String[] {"VISIBLE", null};
        }
        if (boot == null) {
            try {
                if (loader.getResource(RESOURCE) != null) {
                    return new String[] {"NOT_VISIBLE", "the class's loader shadows Spy with a copy of its own: "
                            + loader.getClass().getName()};
                }
            } catch (Throwable t) {
                return new String[] {"UNCHECKED", null};
            }
            return new String[] {"UNCHECKED", null};
        }
        try {
            Class<?> seen = Class.forName(SPY, false, loader);
            if (seen == boot) {
                return new String[] {"VISIBLE", null};
            }
            return new String[] {"NOT_VISIBLE", "the class's loader resolves another Spy: "
                    + loader.getClass().getName()};
        } catch (ClassNotFoundException e) {
            return new String[] {"NOT_VISIBLE", "the class's loader cannot see Spy: " + loader.getClass().getName()};
        } catch (LinkageError e) {
            return new String[] {"NOT_VISIBLE", "the class's loader cannot see Spy: " + loader.getClass().getName()};
        }
    }

    private static byte[] read(JarFile file, JarEntry entry) throws IOException {
        InputStream in = file.getInputStream(entry);
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
