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

package ai.skillroy.testjava.junit;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** The {@link ScenarioSource} implementations. Each is immutable; reading happens in {@code entries()}. */
final class Sources {

    private Sources() {
    }

    static boolean isScenarioFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    /** A directory of scenario files. */
    record Directory(Path dir) implements ScenarioSource {

        @Override
        public List<Entry> entries() throws IOException {
            if (!Files.isDirectory(dir)) {
                throw new NoSuchFileException(dir.toString(), null, "not a directory of scenarios");
            }
            List<Entry> out = new ArrayList<>();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.sorted().toList()) {
                    String name = String.valueOf(file.getFileName());
                    if (Files.isRegularFile(file) && isScenarioFile(name)) {
                        out.add(new Entry(name, file.toAbsolutePath().toUri(), Files.readAllBytes(file)));
                    }
                }
            }
            out.sort(Comparator.comparing(Entry::name));
            return out;
        }

        @Override
        public Path scenarioDirectory() {
            return dir;
        }

        @Override
        public String toString() {
            return "directory " + dir;
        }
    }

    /** Class path resources and resource directories. */
    record Classpath(ClassLoader loader, List<String> resources) implements ScenarioSource {

        Classpath {
            resources = List.copyOf(resources);
        }

        @Override
        public List<Entry> entries() throws IOException {
            ClassLoader cl = loader != null ? loader : Sources.class.getClassLoader();
            Map<String, Entry> byName = new LinkedHashMap<>();
            for (String raw : resources) {
                String resource = strip(raw);
                if (isScenarioFile(resource)) {
                    byName.putIfAbsent(resource, read(cl, resource));
                } else {
                    List<String> names = list(cl, resource);
                    if (names.isEmpty() && cl.getResource(resource) == null) {
                        throw new NoSuchFileException(resource, null, "no class path resource or directory");
                    }
                    for (String name : names) {
                        byName.putIfAbsent(name, read(cl, name));
                    }
                }
            }
            List<Entry> out = new ArrayList<>(byName.values());
            out.sort(Comparator.comparing(Entry::name));
            return out;
        }

        private static String strip(String resource) {
            String r = resource.startsWith("/") ? resource.substring(1) : resource;
            return r.endsWith("/") ? r.substring(0, r.length() - 1) : r;
        }

        private static Entry read(ClassLoader cl, String resource) throws IOException {
            try (InputStream in = cl.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new NoSuchFileException(resource, null, "no class path resource");
                }
                String name = resource.substring(resource.lastIndexOf('/') + 1);
                // The multi-argument constructor quotes spaces and other characters a URI path cannot hold.
                return new Entry(name, new URI("classpath", null, "/" + resource, null), in.readAllBytes());
            } catch (URISyntaxException e) {
                throw new IOException("class path resource '" + resource + "' has no URI form", e);
            }
        }

        /** Resource names of the scenario files directly inside a class path directory, in every class path root. */
        private static List<String> list(ClassLoader cl, String dir) throws IOException {
            List<String> names = new ArrayList<>();
            Enumeration<URL> roots = cl.getResources(dir);
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                if ("file".equals(url.getProtocol())) {
                    Path path;
                    try {
                        path = Path.of(url.toURI());
                    } catch (URISyntaxException e) {
                        throw new IOException("bad class path URL " + url, e);
                    }
                    if (Files.isDirectory(path)) {
                        try (Stream<Path> files = Files.list(path)) {
                            for (Path file : files.sorted().toList()) {
                                String name = String.valueOf(file.getFileName());
                                if (Files.isRegularFile(file) && isScenarioFile(name)) {
                                    names.add(dir.isEmpty() ? name : dir + "/" + name);
                                }
                            }
                        }
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    URLConnection connection = url.openConnection();
                    connection.setUseCaches(false);
                    if (connection instanceof JarURLConnection jar) {
                        try (JarFile file = jar.getJarFile()) {
                            String prefix = dir.isEmpty() ? "" : dir + "/";
                            Enumeration<JarEntry> entries = file.entries();
                            while (entries.hasMoreElements()) {
                                String name = entries.nextElement().getName();
                                if (name.startsWith(prefix) && name.indexOf('/', prefix.length()) < 0
                                        && isScenarioFile(name)) {
                                    names.add(name);
                                }
                            }
                        }
                    }
                }
            }
            return names;
        }

        @Override
        public String toString() {
            return "class path " + resources;
        }
    }
}
