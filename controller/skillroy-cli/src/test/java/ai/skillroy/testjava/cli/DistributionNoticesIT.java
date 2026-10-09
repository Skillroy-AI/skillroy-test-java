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

package ai.skillroy.testjava.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legal content of the assembled distribution: exactly which files it holds, the licence files at its top
 * level and in the project's own jars, and {@code THIRD-PARTY.txt}, which is checked against the jars that are
 * really in {@code lib/} so that it cannot go stale.
 *
 * <p>Phase 5 (phase5-contracts.md 2.6): the distribution carries {@code skillroy-inference} and {@code skillroy-mcp}
 * with the Anthropic Java SDK, JSqlParser and the MCP Java SDK and their trees, and {@code slf4j-nop}. Several of those
 * jars (built with Gradle) carry no {@code META-INF/maven/.../pom.properties}: such a jar is identified by its file
 * name ({@code <artifact>-<version>.jar}) against the line of {@code THIRD-PARTY.txt} that names it. Their licences are
 * Apache-2.0, MIT or MIT-0; a licence is evidenced inside the jar (a licence file, the manifest's
 * {@code Bundle-License}, the embedded POM's licence), or, for a jar that carries none of these, by
 * {@code THIRD-PARTY.txt}: its attributions name the artifact, and the text of the licence is the top-level
 * {@code LICENSE} (Apache-2.0) or in the file's "Licence texts" (MIT, MIT-0).
 */
class DistributionNoticesIT {

    /** The licences a redistributed third-party jar may carry. A new one is a decision, not a test edit. */
    private static final Set<String> ALLOWED_LICENCES = Set.of("Apache-2.0", "MIT", "MIT-0");

    /** Licences the components embedded in a third-party jar may carry (all permissive). */
    private static final Set<String> ALLOWED_EMBEDDED = Set.of("MIT", "BSL-1.0", "BSD-2-Clause");

    /**
     * The project's own jars in lib/: Phase 2 added skillroy-data, Phase 5 skillroy-inference and skillroy-mcp, Phase 6
     * skillroy-telemetry (phase6-contracts.md 2.6).
     */
    private static final List<String> PROJECT_MODULES = List.of("cli", "controller", "data", "inference", "mcp",
            "protocol", "scenario", "telemetry");

    /**
     * The third-party artifacts the distribution carries, by artifact id (their versions are THIRD-PARTY.txt's): the
     * Jackson set and SnakeYAML; since Phase 5 the Anthropic Java SDK's tree, JSqlParser, the MCP Java SDK's tree and
     * the SLF4J no-operation provider; since Phase 6 the OpenTelemetry set of skillroy-telemetry (phase6-contracts.md
     * 2.2, 2.6: the API, context and common jars, the SDK's common, trace, metrics and logs jars, the OTLP exporter with
     * its common jars and the JDK sender; no OkHttp sender, no autoconfiguration). A jar that comes or goes is a
     * decision, recorded here and in the audit.
     */
    private static final Set<String> THIRD_PARTY = Set.of(
            "jackson-annotations", "jackson-core", "jackson-databind", "jackson-dataformat-yaml", "snakeyaml",
            "anthropic-java", "anthropic-java-client-okhttp", "anthropic-java-core", "jackson-datatype-jdk8",
            "jackson-datatype-jsr310", "jackson-module-kotlin", "kotlin-stdlib", "kotlin-stdlib-common",
            "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8", "kotlin-reflect", "annotations", "okhttp", "okio", "okio-jvm",
            "jsonschema-generator", "jsonschema-module-jackson", "jsonschema-module-swagger-2", "classmate",
            "swagger-annotations", "error_prone_annotations", "standardwebhooks", "jsqlparser", "mcp-core",
            "mcp-json-jackson2", "reactor-core", "reactive-streams", "json-schema-validator", "itu", "slf4j-api",
            "slf4j-nop", "opentelemetry-api", "opentelemetry-common", "opentelemetry-context",
            "opentelemetry-exporter-common", "opentelemetry-exporter-otlp", "opentelemetry-exporter-otlp-common",
            "opentelemetry-exporter-sender-jdk", "opentelemetry-sdk-common", "opentelemetry-sdk-logs",
            "opentelemetry-sdk-metrics", "opentelemetry-sdk-trace");

    /** {@code group:artifact:version  SPDX-licence  lib/file.jar} */
    private static final Pattern ARTIFACT_LINE =
            Pattern.compile("^(\\S+):(\\S+):(\\S+)\\s+(\\S+)\\s+(lib/\\S+\\.jar)$");

    /** {@code  component name  SPDX-licence  META-INF/file} (two or more spaces between the columns). */
    private static final Pattern COMPONENT_LINE =
            Pattern.compile("^\\s+(\\S.*?)\\s{2,}(\\S+)\\s+(META-INF/\\S+)$");

    /** {@code  group:artifact:version  path/inside/the/jar}: another licence or notice file inside a jar. */
    private static final Pattern OTHER_FILE_LINE = Pattern.compile("^\\s+(\\S+:\\S+:\\S+)\\s{2,}(\\S+)$");

    private static final String PROJECT_GROUP = "ai.skillroy";

    private static Path dist;
    private static Path rootDir;

    /** One entry of the "Redistributed artifacts" table. */
    private record Listed(String group, String artifact, String version, String licence, String file) {
        String coordinates() {
            return group + ":" + artifact + ":" + version;
        }
    }

    /**
     * One jar in lib/, identified by the Maven coordinates the jar itself states, or, for a jar without
     * {@code pom.properties}, by the listed line that names its file ({@code byFileName}).
     */
    private record LibJar(Path path, String group, String artifact, String version, boolean byFileName) {
        String file() {
            return "lib/" + path.getFileName();
        }

        String coordinates() {
            return group + ":" + artifact + ":" + version;
        }

        boolean isThirdParty() {
            return !PROJECT_GROUP.equals(group);
        }
    }

    @BeforeAll
    static void locate() {
        dist = Launcher.dist();
        assertTrue(Files.isRegularFile(Launcher.script()), "no " + Launcher.script() + ": run after 'package'");
        rootDir = Path.of(System.getProperty("skillroy.root.dir", "../..")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(rootDir.resolve("LICENSE")), "no LICENSE in the reactor root " + rootDir);
        assertTrue(Files.isRegularFile(rootDir.resolve("NOTICE")), "no NOTICE in the reactor root " + rootDir);
    }

    @Test
    void theDistributionHoldsExactlyTheLauncherTheLegalFilesAndTheJarsInLib() throws IOException {
        Set<String> outsideLib = new TreeSet<>();
        Set<String> libFiles = new TreeSet<>();
        for (String file : filesUnder(dist)) {
            (file.startsWith("lib/") ? libFiles : outsideLib).add(file);
        }
        assertEquals(Set.of("LICENSE", "NOTICE", "THIRD-PARTY.txt", "bin/skillroy"), outsideLib);
        for (String file : libFiles) {
            assertTrue(file.matches("lib/[^/]+\\.jar"), "only jars directly in lib/: " + file);
        }
        // The project's own jars, once each; the rest is third-party and is listed in THIRD-PARTY.txt.
        List<String> projectJars = libFiles.stream().filter(f -> f.startsWith("lib/skillroy-")).toList();
        for (String module : PROJECT_MODULES) {
            assertEquals(1, projectJars.stream().filter(f -> f.matches("lib/skillroy-" + module + "-\\d.*\\.jar"))
                    .count(), "lib/skillroy-" + module + "-<version>.jar in " + projectJars);
        }
        assertEquals(PROJECT_MODULES.size(), projectJars.size(), "no other project jar: " + projectJars);
        Set<String> thirdParty = new TreeSet<>();
        for (LibJar jar : libJars()) {
            if (jar.isThirdParty()) {
                thirdParty.add(jar.artifact());
            }
        }
        assertEquals(new TreeSet<>(THIRD_PARTY), thirdParty, "the third-party jars in lib/");
        assertFalse(libFiles.stream().anyMatch(f -> f.contains("playwright") || f.contains("junit")
                || f.contains("hsqldb")), "no optional, test or sample dependency: " + libFiles);
        // phase6-contracts.md 2.2 (D140, D141): exactly one OTLP sender, the JDK's, and no autoconfiguration.
        assertFalse(libFiles.stream().anyMatch(f -> f.contains("sender-okhttp") || f.contains("autoconfigure")
                || f.contains("opentelemetry-sdk-testing")), "no OkHttp sender, no autoconfiguration: " + libFiles);
    }

    @Test
    void theLicenceAndNoticeAtTheTopLevelAreCopiesOfTheRootFiles() throws IOException {
        for (String file : List.of("LICENSE", "NOTICE")) {
            assertArrayEquals(Files.readAllBytes(rootDir.resolve(file)), Files.readAllBytes(dist.resolve(file)),
                    file + " in the distribution");
        }
        String licence = Files.readString(dist.resolve("LICENSE"), StandardCharsets.UTF_8);
        assertTrue(licence.contains("Apache License") && licence.contains("Version 2.0"), "the Apache-2.0 text");
        assertTrue(Files.readString(dist.resolve("NOTICE"), StandardCharsets.UTF_8).contains("THIRD-PARTY.txt"),
                "the NOTICE points at the third-party list");
    }

    @Test
    void theZipCarriesTheSameLegalFiles() throws IOException {
        Path zip = Path.of(System.getProperty("skillroy.cli.it.zip", "target/skillroy-cli.zip"));
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (String name : List.of("LICENSE", "NOTICE", "THIRD-PARTY.txt")) {
                ZipEntry entry = file.getEntry("skillroy-cli/" + name);
                assertNotNull(entry, "skillroy-cli/" + name + " in the zip");
                try (InputStream in = file.getInputStream(entry)) {
                    assertArrayEquals(Files.readAllBytes(dist.resolve(name)), in.readAllBytes(), name + " in the zip");
                }
            }
        }
    }

    @Test
    void everyProjectJarInLibCarriesTheLicenceAndNoticeInMetaInf() throws IOException {
        List<LibJar> projectJars = libJars().stream().filter(j -> !j.isThirdParty()).toList();
        assertEquals(PROJECT_MODULES.size(), projectJars.size(), projectJars.toString());
        for (LibJar jar : projectJars) {
            assertFalse(jar.byFileName(), jar.file() + " states its coordinates");
            try (ZipFile zip = new ZipFile(jar.path().toFile())) {
                for (String name : List.of("LICENSE", "NOTICE")) {
                    ZipEntry entry = zip.getEntry("META-INF/" + name);
                    assertNotNull(entry, jar.file() + " has no META-INF/" + name);
                    try (InputStream in = zip.getInputStream(entry)) {
                        assertArrayEquals(Files.readAllBytes(rootDir.resolve(name)), in.readAllBytes(),
                                "META-INF/" + name + " in " + jar.file());
                    }
                }
            }
        }
    }

    @Test
    void thirdPartyTxtListsExactlyTheThirdPartyJarsInLibWithTheirVersions() throws IOException {
        Set<String> inLib = new TreeSet<>();
        Set<String> filesInLib = new TreeSet<>();
        for (LibJar jar : libJars()) {
            if (jar.isThirdParty()) {
                inLib.add(jar.coordinates());
                filesInLib.add(jar.file());
            }
        }
        assertFalse(inLib.isEmpty(), "the CLI redistributes third-party jars");
        List<Listed> listed = listedArtifacts();
        Set<String> inFile = new TreeSet<>();
        Set<String> filesInFile = new TreeSet<>();
        for (Listed entry : listed) {
            assertTrue(inFile.add(entry.coordinates()), "listed twice: " + entry.coordinates());
            filesInFile.add(entry.file());
            assertEquals("lib/" + entry.artifact() + "-" + entry.version() + ".jar", entry.file(),
                    "the file of " + entry.coordinates() + " is <artifact>-<version>.jar");
        }
        assertEquals(inLib, inFile, "THIRD-PARTY.txt and lib/ list different artifacts or versions");
        assertEquals(filesInLib, filesInFile, "THIRD-PARTY.txt names other files than lib/ holds");
        for (Listed entry : listed) {
            LibJar jar = libJars().stream().filter(j -> j.file().equals(entry.file())).findFirst().orElseThrow();
            assertEquals(jar.coordinates(), entry.coordinates(), entry.file());
        }
    }

    @Test
    void everyListedLicenceIsAllowedAndEvidencedInsideItsJarOrInThisFile() throws IOException {
        String text = Files.readString(dist.resolve("THIRD-PARTY.txt"), StandardCharsets.UTF_8);
        String attributions = section(text, "Attributions", "Components embedded in jackson-core");
        String licenceTexts = text.substring(text.indexOf("\nLicence texts\n"));
        String topLevel = Files.readString(dist.resolve("LICENSE"), StandardCharsets.UTF_8);
        for (Listed entry : listedArtifacts()) {
            assertTrue(ALLOWED_LICENCES.contains(entry.licence()),
                    entry.coordinates() + " is " + entry.licence() + "; allowed: " + ALLOWED_LICENCES);
            boolean inJar;
            try (JarFile jar = new JarFile(dist.resolve(entry.file()).toFile())) {
                inJar = evidences(jar, entry.licence());
            }
            if (inJar) {
                continue;
            }
            // No evidence inside the jar: THIRD-PARTY.txt names the artifact and the licence text is distributed.
            assertTrue(Pattern.compile("[\\s(,]" + Pattern.quote(entry.artifact()) + "[\\s),]").matcher(attributions)
                    .find(), entry.coordinates() + " states " + entry.licence() + " nowhere inside its jar, and the"
                    + " attributions of THIRD-PARTY.txt do not name " + entry.artifact());
            switch (entry.licence()) {
                case "Apache-2.0" -> assertTrue(topLevel.contains("Apache License") && topLevel.contains("Version"
                        + " 2.0"), "the Apache-2.0 text at the top level");
                case "MIT" -> assertTrue(licenceText(licenceTexts, "MIT License: ", entry.artifact()),
                        "the MIT text with the copyright line of " + entry.artifact() + " in THIRD-PARTY.txt");
                case "MIT-0" -> assertTrue(licenceText(licenceTexts, "MIT No Attribution: ", entry.artifact()),
                        "the MIT-0 text of " + entry.artifact() + " in THIRD-PARTY.txt");
                default -> throw new AssertionError("no rule for " + entry.licence());
            }
        }
        assertTrue(licenceTexts.contains("Permission is hereby granted, free of charge"), "the MIT text");
    }

    @Test
    void everyLicenceOrNoticeFileInsideAThirdPartyJarIsNamedInThirdPartyTxt() throws IOException {
        String text = Files.readString(dist.resolve("THIRD-PARTY.txt"), StandardCharsets.UTF_8);
        Map<String, Set<String>> filesInJars = new TreeMap<>();
        for (LibJar lib : libJars()) {
            if (!lib.isThirdParty()) {
                continue;
            }
            try (ZipFile zip = new ZipFile(lib.path().toFile())) {
                zip.stream().map(ZipEntry::getName).filter(DistributionNoticesIT::isLicenceLike)
                        .filter(n -> !n.equals("META-INF/LICENSE") && !n.equals("META-INF/NOTICE"))
                        .forEach(n -> filesInJars.computeIfAbsent(lib.coordinates(), k -> new TreeSet<>()).add(n));
            }
        }
        // The embedded components of jackson-core: the file names in the table must be the ones in the jar.
        Set<String> components = new TreeSet<>();
        Map<String, Set<String>> others = new TreeMap<>();
        for (String line : text.lines().toList()) {
            Matcher m = COMPONENT_LINE.matcher(line);
            if (m.matches()) {
                assertTrue(ALLOWED_EMBEDDED.contains(m.group(2)), "licence not expected for an embedded component: "
                        + line.strip());
                components.add(m.group(3));
                continue;
            }
            Matcher o = OTHER_FILE_LINE.matcher(line);
            if (o.matches()) {
                others.computeIfAbsent(o.group(1), k -> new TreeSet<>()).add(o.group(2));
            }
        }
        assertFalse(components.isEmpty(), "THIRD-PARTY.txt lists the components embedded in jackson-core");
        LibJar core = libJars().stream().filter(j -> j.artifact().equals("jackson-core")).findFirst().orElseThrow();
        try (ZipFile zip = new ZipFile(core.path().toFile())) {
            for (String file : components) {
                assertNotNull(zip.getEntry(file), "THIRD-PARTY.txt names " + file + ", not in " + core.file());
            }
        }
        Map<String, Set<String>> notNamed = new TreeMap<>();
        filesInJars.forEach((coordinates, files) -> {
            for (String file : files) {
                boolean named = coordinates.equals(core.coordinates()) ? components.contains(file)
                        : others.getOrDefault(coordinates, Set.of()).contains(file);
                if (!named) {
                    notNamed.computeIfAbsent(coordinates, k -> new TreeSet<>()).add(file);
                }
            }
        });
        assertEquals(Collections.emptyMap(), notNamed, "licence or notice files inside the jars that THIRD-PARTY.txt"
                + " does not name");
        for (Map.Entry<String, Set<String>> named : others.entrySet()) {
            assertEquals(named.getValue(), filesInJars.getOrDefault(named.getKey(), Set.of()), "THIRD-PARTY.txt"
                    + " names files of " + named.getKey() + " that its jar does not hold");
        }
    }

    /** A licence or notice file: a {@code META-INF} entry naming one, or such a file anywhere else (not a class). */
    private static boolean isLicenceLike(String entryName) {
        if (entryName.startsWith("META-INF/maven/") || entryName.startsWith("META-INF/services/")
                || entryName.endsWith("/") || entryName.endsWith(".class")) {
            return false;
        }
        String upper = entryName.toUpperCase(Locale.ROOT);
        if (entryName.startsWith("META-INF/")) {
            return upper.contains("LICENSE") || upper.contains("LICENCE") || upper.contains("NOTICE");
        }
        String name = upper.substring(upper.lastIndexOf('/') + 1);
        return name.startsWith("LICENSE") || name.startsWith("LICENCE") || name.startsWith("NOTICE");
    }

    /**
     * True when the jar itself states the licence: a licence file under {@code META-INF} with its text, the manifest's
     * {@code Bundle-License}, or a licence name in its embedded POM.
     */
    private static boolean evidences(JarFile jar, String licence) throws IOException {
        List<String> texts = new ArrayList<>();
        for (String name : List.of("META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md")) {
            ZipEntry entry = jar.getEntry(name);
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    texts.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        String bundle = jar.getManifest() == null ? null
                : jar.getManifest().getMainAttributes().getValue("Bundle-License");
        if (bundle != null) {
            texts.add(bundle);
        }
        jar.stream().filter(e -> e.getName().matches("META-INF/maven/[^/]+/[^/]+/pom\\.xml")).findFirst()
                .ifPresent(e -> {
                    try (InputStream in = jar.getInputStream(e)) {
                        Matcher m = Pattern.compile("<license>\\s*<name>(.*?)</name>", Pattern.DOTALL)
                                .matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        while (m.find()) {
                            texts.add(m.group(1));
                        }
                    } catch (IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                });
        for (String t : texts) {
            boolean match = switch (licence) {
                case "Apache-2.0" -> t.contains("apache.org/licenses/LICENSE-2.0")
                        || (t.contains("Apache") && (t.contains("Version 2.0") || t.contains("2.0")));
                case "MIT" -> t.contains("mit-license") || t.contains("Permission is hereby granted, free of charge")
                        || t.matches("(?s).*\\bMIT\\b.*") && !t.contains("No Attribution");
                case "MIT-0" -> t.contains("MIT-0") || t.contains("MIT No Attribution");
                default -> false;
            };
            if (match) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a licence text of THIRD-PARTY.txt is headed for {@code artifact}: a line {@code <header><project>
     * (<artifact>, <artifact>…)} followed by its copyright line and the text.
     */
    private static boolean licenceText(String licenceTexts, String header, String artifact) {
        for (String line : licenceTexts.lines().toList()) {
            int open = line.indexOf('(');
            int close = line.lastIndexOf(')');
            if (line.startsWith(header) && open >= 0 && close > open
                    && List.of(line.substring(open + 1, close).split(",\\s*")).contains(artifact)) {
                return true;
            }
        }
        return false;
    }

    /** The text between two section titles of THIRD-PARTY.txt. */
    private static String section(String text, String from, String to) {
        int start = text.indexOf("\n" + from + "\n");
        int end = text.indexOf("\n" + to + "\n");
        assertTrue(start >= 0 && end > start, "THIRD-PARTY.txt has the sections " + from + " and " + to);
        return text.substring(start, end);
    }

    private static List<Listed> listedArtifacts() throws IOException {
        List<Listed> listed = new ArrayList<>();
        for (String line : Files.readAllLines(dist.resolve("THIRD-PARTY.txt"), StandardCharsets.UTF_8)) {
            Matcher m = ARTIFACT_LINE.matcher(line.strip());
            if (m.matches()) {
                listed.add(new Listed(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)));
            }
        }
        assertFalse(listed.isEmpty(), "no artifact line in THIRD-PARTY.txt (group:artifact:version licence file)");
        return listed;
    }

    /**
     * The jars in lib/, each with the coordinates its own META-INF/maven/.../pom.properties states; a jar without one
     * by the THIRD-PARTY.txt line that names its file, whose file name must then be {@code <artifact>-<version>.jar}.
     */
    private static List<LibJar> libJars() throws IOException {
        Map<String, Listed> byFile = new TreeMap<>();
        for (Listed entry : listedArtifacts()) {
            byFile.put(entry.file(), entry);
        }
        List<LibJar> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(dist.resolve("lib"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    ZipEntry props = zip.stream()
                            .filter(e -> e.getName().matches("META-INF/maven/[^/]+/[^/]+/pom\\.properties"))
                            .findFirst().orElse(null);
                    if (props == null) {
                        Listed entry = byFile.get("lib/" + path.getFileName());
                        assertNotNull(entry, path.getFileName() + " carries no META-INF/maven pom.properties and"
                                + " THIRD-PARTY.txt names no artifact with this file");
                        assertEquals(entry.artifact() + "-" + entry.version() + ".jar", path.getFileName().toString(),
                                "a jar without pom.properties is known by its file name");
                        jars.add(new LibJar(path, entry.group(), entry.artifact(), entry.version(), true));
                        continue;
                    }
                    Properties p = new Properties();
                    try (InputStream in = zip.getInputStream(props)) {
                        p.load(in);
                    }
                    jars.add(new LibJar(path, p.getProperty("groupId"), p.getProperty("artifactId"),
                            p.getProperty("version"), false));
                }
            }
        }
        return jars;
    }

    private static Set<String> filesUnder(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            Set<String> names = new TreeSet<>();
            files.filter(Files::isRegularFile)
                    .forEach(p -> names.add(root.relativize(p).toString().replace('\\', '/')));
            return names;
        }
    }
}
