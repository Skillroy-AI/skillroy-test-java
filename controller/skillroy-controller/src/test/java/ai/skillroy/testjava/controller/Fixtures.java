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

package ai.skillroy.testjava.controller;

import ai.skillroy.testjava.protocol.Auth;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Child-JVM fixtures for the launcher and attach tests. The sources (in {@code src/test/resources/fixture}) are
 * compiled once per test JVM with {@code --release 8}, so that the same classes run on a Java 8 target named by
 * {@code -Dskillroy.it.targetJava} and on a current one. Builds a fake agent home whose bootstrap jar is the fake
 * agent. The fake agent authenticates with the shared {@link Auth} class (Java 8 API only), so it is compiled against
 * the protocol classes and {@code Auth} and its nested classes are copied into its jar.
 */
final class Fixtures {

    static final String CHILD_MAIN = "ai.skillroy.testjava.controller.fixture.ChildMain";
    static final String FAKE_AGENT = "ai.skillroy.testjava.controller.fixture.FakeTargetAgent";

    private static Fixtures instance;

    /** Classes of {@link #CHILD_MAIN} only. */
    final Path childClasses;
    /** The fake agent jar (also usable on a class path for EMBEDDED). */
    final Path agentJar;
    /** A fake agent home: the fake agent jar as {@code skillroy-agent-bootstrap.jar}, plus {@code lib/}. */
    final AgentHome home;

    private Fixtures(Path childClasses, Path agentJar, AgentHome home) {
        this.childClasses = childClasses;
        this.agentJar = agentJar;
        this.home = home;
    }

    static synchronized Fixtures get() throws IOException {
        if (instance == null) {
            instance = build();
        }
        return instance;
    }

    /** The Java executable for target JVMs: {@code -Dskillroy.it.targetJava}, else the running JVM's. */
    static Path targetJava() {
        String configured = System.getProperty("skillroy.it.targetJava");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip());
        }
        return currentJava();
    }

    /** The running JVM's Java executable (for children that run controller classes). */
    static Path currentJava() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    /** JVM options that let a current JDK accept a dynamically loaded agent quietly; ignored by Java 8. */
    static List<String> dynamicAgentOptions() {
        return List.of("-XX:+IgnoreUnrecognizedVMOptions", "-XX:+EnableDynamicAgentLoading");
    }

    /** Reads a report written by ChildMain, waiting until it exists. */
    static Properties report(Path file) throws IOException {
        Poll.until("child report " + file.getFileName(), java.time.Duration.ofSeconds(120), () -> Files.exists(file));
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private static Fixtures build() throws IOException {
        Path root = targetDir().resolve("skillroy-fixtures");
        deleteTree(root);
        Path childClasses = root.resolve("child-classes");
        Path agentClasses = root.resolve("agent-classes");
        compile("fixture/ChildMain.java.txt", CHILD_MAIN, childClasses, null);
        Path protocol = protocolLocation();
        compile("fixture/FakeTargetAgent.java.txt", FAKE_AGENT, agentClasses, protocol);
        for (Map.Entry<String, byte[]> c : authClasses(protocol).entrySet()) {
            Path target = agentClasses.resolve(c.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, c.getValue());
        }

        Path homeDir = root.resolve("home");
        Files.createDirectories(homeDir.resolve("lib"));
        Files.createDirectories(homeDir.resolve("modules"));
        Path agentJar = homeDir.resolve(AgentHome.BOOTSTRAP_JAR);
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Premain-Class", FAKE_AGENT);
        main.putValue("Agent-Class", FAKE_AGENT);
        try (OutputStream file = Files.newOutputStream(agentJar);
             JarOutputStream jar = new JarOutputStream(file, manifest);
             Stream<Path> classes = Files.walk(agentClasses)) {
            for (Path c : classes.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(agentClasses.relativize(c).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(c));
                jar.closeEntry();
            }
        }
        return new Fixtures(childClasses, agentJar, AgentHome.of(homeDir));
    }

    /** Where the protocol classes are: a class directory in a reactor build, or a jar. */
    private static Path protocolLocation() throws IOException {
        try {
            return Path.of(Auth.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            throw new IOException("cannot locate the protocol classes", e);
        }
    }

    /** {@code Auth.class} and its nested classes, by jar entry name. */
    private static Map<String, byte[]> authClasses(Path location) throws IOException {
        Pattern name = Pattern.compile("ai/skillroy/testjava/protocol/Auth(\\$[A-Za-z0-9_$]+)?\\.class");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        if (Files.isDirectory(location)) {
            try (Stream<Path> files = Files.walk(location)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String entry = location.relativize(f).toString().replace('\\', '/');
                    if (name.matcher(entry).matches()) {
                        classes.put(entry, Files.readAllBytes(f));
                    }
                }
            }
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                List<JarEntry> entries = new ArrayList<>();
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    entries.add(e.nextElement());
                }
                for (JarEntry entry : entries) {
                    if (name.matcher(entry.getName()).matches()) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            classes.put(entry.getName(), in.readAllBytes());
                        }
                    }
                }
            }
        }
        if (!classes.containsKey("ai/skillroy/testjava/protocol/Auth.class")) {
            throw new IOException("Auth.class not found in " + location);
        }
        return classes;
    }

    private static void compile(String resource, String className, Path out, Path classPath) throws IOException {
        String source;
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing test resource " + resource);
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IOException("tests need a JDK with javac (java.home=" + System.getProperty("java.home") + ")");
        }
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/')
                + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        StringWriter diagnostics = new StringWriter();
        try (StandardJavaFileManager files = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            List<String> options = new ArrayList<>(List.of("--release", "8", "-Xlint:-options", "-nowarn"));
            if (classPath != null) {
                options.add("-classpath");
                options.add(classPath.toString());
            }
            Boolean ok = javac.getTask(diagnostics, files, null, options, null, List.of(file)).call();
            if (!Boolean.TRUE.equals(ok)) {
                throw new IOException("compiling " + resource + " failed:\n" + diagnostics);
            }
        }
    }

    private static Path targetDir() {
        try {
            Path testClasses = Path.of(Fixtures.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return testClasses.getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
