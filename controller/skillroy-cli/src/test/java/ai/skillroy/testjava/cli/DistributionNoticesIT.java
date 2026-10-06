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
import java.util.Properties;
import java.util.Set;
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
 */
class DistributionNoticesIT {

    /** The licences a redistributed third-party jar may carry. A new one is a decision, not a test edit. */
    private static final Set<String> ALLOWED_LICENCES = Set.of("Apache-2.0");

    /** Licences the components embedded in a third-party jar may carry (all permissive). */
    private static final Set<String> ALLOWED_EMBEDDED = Set.of("MIT", "BSL-1.0", "BSD-2-Clause");

    /** {@code group:artifact:version  SPDX-licence  lib/file.jar} */
    private static final Pattern ARTIFACT_LINE =
            Pattern.compile("^(\\S+):(\\S+):(\\S+)\\s+(\\S+)\\s+(lib/\\S+\\.jar)$");

    /** {@code  component name  SPDX-licence  META-INF/file} (two or more spaces between the columns). */
    private static final Pattern COMPONENT_LINE =
            Pattern.compile("^\\s+(\\S.*?)\\s{2,}(\\S+)\\s+(META-INF/\\S+)$");

    private static final String PROJECT_GROUP = "ai.skillroy";

    private static Path dist;
    private static Path rootDir;

    /** One entry of the "Redistributed artifacts" table. */
    private record Listed(String group, String artifact, String version, String licence, String file) {
        String coordinates() {
            return group + ":" + artifact + ":" + version;
        }
    }

    /** One jar in lib/, identified by the Maven coordinates the jar itself states. */
    private record LibJar(Path path, String group, String artifact, String version) {
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
        // Phase 2 (phase2-contracts.md 2.1): skillroy-data is the fifth project jar.
        for (String module : List.of("cli", "controller", "data", "protocol", "scenario")) {
            assertEquals(1, projectJars.stream().filter(f -> f.matches("lib/skillroy-" + module + "-\\d.*\\.jar"))
                    .count(), "lib/skillroy-" + module + "-<version>.jar in " + projectJars);
        }
        assertEquals(5, projectJars.size(), "no other project jar: " + projectJars);
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
        assertEquals(5, projectJars.size(), projectJars.toString());
        for (LibJar jar : projectJars) {
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
        }
        assertEquals(inLib, inFile, "THIRD-PARTY.txt and lib/ list different artifacts or versions");
        assertEquals(filesInLib, filesInFile, "THIRD-PARTY.txt names other files than lib/ holds");
        for (Listed entry : listed) {
            LibJar jar = libJars().stream().filter(j -> j.file().equals(entry.file())).findFirst().orElseThrow();
            assertEquals(jar.coordinates(), entry.coordinates(), entry.file());
        }
    }

    @Test
    void everyListedLicenceIsAllowedAndEvidencedInsideItsJar() throws IOException {
        for (Listed entry : listedArtifacts()) {
            assertTrue(ALLOWED_LICENCES.contains(entry.licence()),
                    entry.coordinates() + " is " + entry.licence() + "; allowed: " + ALLOWED_LICENCES);
            try (JarFile jar = new JarFile(dist.resolve(entry.file()).toFile())) {
                assertTrue(evidencesApache2(jar), entry.file() + " states " + entry.licence() + " nowhere inside");
            }
        }
    }

    @Test
    void everyLicenceOrNoticeFileInsideAThirdPartyJarIsMentionedInThirdPartyTxt() throws IOException {
        String text = Files.readString(dist.resolve("THIRD-PARTY.txt"), StandardCharsets.UTF_8);
        Set<String> extraFilesInJars = new TreeSet<>();
        for (LibJar lib : libJars()) {
            if (!lib.isThirdParty()) {
                continue;
            }
            try (ZipFile zip = new ZipFile(lib.path().toFile())) {
                zip.stream().map(ZipEntry::getName).filter(DistributionNoticesIT::isLicenceLike)
                        .filter(n -> !n.equals("META-INF/LICENSE") && !n.equals("META-INF/NOTICE"))
                        .forEach(n -> extraFilesInJars.add(lib.coordinates() + " " + n));
            }
        }
        // The embedded components of jackson-core: the file names in the table must be the ones in the jar.
        Set<String> named = new TreeSet<>();
        for (String line : text.lines().toList()) {
            Matcher m = COMPONENT_LINE.matcher(line);
            if (m.matches()) {
                assertTrue(ALLOWED_EMBEDDED.contains(m.group(2)), "licence not expected for an embedded component: "
                        + line.strip());
                named.add(m.group(3));
            }
        }
        assertFalse(named.isEmpty(), "THIRD-PARTY.txt lists the components embedded in jackson-core");
        Set<String> notMentioned = new TreeSet<>();
        for (String entry : extraFilesInJars) {
            String file = entry.substring(entry.indexOf(' ') + 1);
            if (!named.contains(file)) {
                notMentioned.add(entry);
            }
        }
        assertEquals(Collections.emptySet(), notMentioned, "licence or notice files inside the jars that "
                + "THIRD-PARTY.txt does not name");
        LibJar core = libJars().stream().filter(j -> j.artifact().equals("jackson-core")).findFirst().orElseThrow();
        try (ZipFile zip = new ZipFile(core.path().toFile())) {
            for (String file : named) {
                assertNotNull(zip.getEntry(file), "THIRD-PARTY.txt names " + file + ", not in " + core.file());
            }
        }
    }

    private static boolean isLicenceLike(String entryName) {
        if (!entryName.startsWith("META-INF/") || entryName.startsWith("META-INF/maven/")
                || entryName.startsWith("META-INF/services/")) {
            return false;
        }
        String upper = entryName.toUpperCase(Locale.ROOT);
        return upper.contains("LICENSE") || upper.contains("LICENCE") || upper.contains("NOTICE");
    }

    /** Apache-2.0 is evidenced by a META-INF/LICENSE holding its text, or by an OSGi Bundle-License naming it. */
    private static boolean evidencesApache2(JarFile jar) throws IOException {
        ZipEntry licence = jar.getEntry("META-INF/LICENSE");
        if (licence != null) {
            try (InputStream in = jar.getInputStream(licence)) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (text.contains("Apache License") && text.contains("Version 2.0")) {
                    return true;
                }
            }
        }
        if (jar.getManifest() != null) {
            String bundleLicence = jar.getManifest().getMainAttributes().getValue("Bundle-License");
            return bundleLicence != null && bundleLicence.contains("apache.org/licenses/LICENSE-2.0");
        }
        return false;
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

    /** The jars in lib/, each with the coordinates its own META-INF/maven/.../pom.properties states. */
    private static List<LibJar> libJars() throws IOException {
        List<LibJar> jars = new ArrayList<>();
        try (Stream<Path> files = Files.list(dist.resolve("lib"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    ZipEntry props = zip.stream()
                            .filter(e -> e.getName().matches("META-INF/maven/[^/]+/[^/]+/pom\\.properties"))
                            .findFirst().orElse(null);
                    assertNotNull(props, path.getFileName() + " carries no META-INF/maven pom.properties");
                    Properties p = new Properties();
                    try (InputStream in = zip.getInputStream(props)) {
                        p.load(in);
                    }
                    jars.add(new LibJar(path, p.getProperty("groupId"), p.getProperty("artifactId"),
                            p.getProperty("version")));
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
