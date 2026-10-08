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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assembled distribution: {@code bin/skillroy} and {@code lib/}, as a directory and as a zip. The exact file
 * list and the licence files are checked in {@link DistributionNoticesIT}.
 */
class LauncherIT {

    @TempDir
    Path tmp;

    @BeforeAll
    static void distributionWasAssembled() {
        assertTrue(Files.isRegularFile(Launcher.script()), "no " + Launcher.script() + ": run after 'package'");
    }

    @Test
    void helpRunsFromTheAssembledDirectory() throws Exception {
        assertTrue(Files.isExecutable(Launcher.script()), "bin/skillroy must be executable");
        Launcher.Result result = Launcher.run(tmp, "--help").expectExit(0);
        assertTrue(result.out().startsWith("Usage: skillroy <command> [options]"), result.out());
        assertTrue(result.out().contains("  run        run scenarios or plans against the agent"), result.out());
        assertEquals("", result.err());
        Launcher.Result commandHelp = Launcher.run(tmp, "compile", "--help").expectExit(0);
        assertTrue(commandHelp.out().startsWith("Usage: skillroy compile"), commandHelp.out());
    }

    @Test
    void exitCodesPassThroughTheLauncher() throws Exception {
        Launcher.Result unknown = Launcher.run(tmp, "frobnicate").expectExit(Exit.USAGE);
        assertTrue(unknown.err().contains("unknown command 'frobnicate'"), unknown.err());
        Launcher.Result noAgent = Launcher.run(tmp, "info", "--discovery-dir", tmp.resolve("none").toString())
                .expectExit(Exit.UNREACHABLE);
        assertTrue(noAgent.err().contains("no live agent"), noAgent.err());
    }

    @Test
    void jvmsRunsThroughTheLauncherWithTheAttachModule() throws Exception {
        Launcher.Result result = Launcher.run(tmp, "jvms", "--discovery-dir", tmp.toString()).expectExit(0);
        assertTrue(result.out().startsWith("PID"), result.out());
        assertFalse(result.err().contains("jdk.attach"), result.err());
    }

    @Test
    void javaIsFoundOnThePathWithoutJavaHome() throws Exception {
        Launcher.Result result = Launcher.run(tmp, env -> {
            env.remove("JAVA_HOME");
            env.put("PATH", Path.of(Launcher.javaHome(), "bin") + ":/usr/bin:/bin");
        }, Launcher.script(), "--version").expectExit(0);
        assertTrue(result.out().startsWith("skillroy "), result.out());
    }

    @Test
    void aSymbolicLinkToTheLauncherFindsTheInstallation() throws Exception {
        Path link = Files.createSymbolicLink(tmp.resolve("skillroy"), Launcher.script());
        try {
            Launcher.Result result = Launcher.run(tmp, env -> env.put("JAVA_HOME", Launcher.javaHome()), link,
                    "--version").expectExit(0);
            assertTrue(result.out().startsWith("skillroy "), result.out());
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void anUnusableJavaIsExitCode69() throws Exception {
        Launcher.Result noJava = Launcher.run(tmp, env -> env.put("JAVA_HOME", tmp.resolve("nowhere").toString()),
                Launcher.script(), "--help").expectExit(69);
        assertTrue(noJava.err().contains("has no executable bin/java"), noJava.err());

        Path old = fakeJavaHome("old", "1.8.0_402", "java.base jdk.attach");
        Launcher.Result tooOld = Launcher.run(tmp, env -> env.put("JAVA_HOME", old.toString()), Launcher.script(),
                "--help").expectExit(69);
        assertTrue(tooOld.err().contains("is Java 1.8.0_402; the CLI needs Java 25 or newer"), tooOld.err());

        // Java 21 and the release just below the threshold are too old as well; Java 25, the first release that
        // is new enough, is accepted.
        Path java21 = fakeJavaHome("java21", "21.0.4", "java.base jdk.attach");
        Launcher.Result tooOld21 = Launcher.run(tmp, env -> env.put("JAVA_HOME", java21.toString()), Launcher.script(),
                "--help").expectExit(69);
        assertTrue(tooOld21.err().contains("is Java 21.0.4; the CLI needs Java 25 or newer"), tooOld21.err());
        Path java24 = fakeJavaHome("java24", "24.0.2", "java.base jdk.attach");
        Launcher.Result tooOld24 = Launcher.run(tmp, env -> env.put("JAVA_HOME", java24.toString()), Launcher.script(),
                "--help").expectExit(69);
        assertTrue(tooOld24.err().contains("is Java 24.0.2; the CLI needs Java 25 or newer"), tooOld24.err());
        Path java25 = fakeJavaHome("java25", "25", "java.base jdk.attach");
        Launcher.run(tmp, env -> env.put("JAVA_HOME", java25.toString()), Launcher.script(), "--help").expectExit(0);

        // A PATH with the tools the script uses but no java (small wrapper scripts, not links, so that the temp
        // directory holds nothing that points outside it).
        Path tools = Files.createDirectories(tmp.resolve("tools"));
        for (String tool : List.of("dirname", "sed", "ls", "grep")) {
            Path found = Stream.of("/usr/bin/", "/bin/").map(dir -> Path.of(dir + tool)).filter(Files::isExecutable)
                    .findFirst().orElseThrow();
            Path wrapper = Files.writeString(tools.resolve(tool), "#!/bin/sh\nexec " + found + " \"$@\"\n");
            Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Launcher.Result noPath = Launcher.run(tmp, env -> {
            env.remove("JAVA_HOME");
            env.put("PATH", tools.toString());
        }, Launcher.script(), "--help").expectExit(69);
        assertTrue(noPath.err().contains("no java on the PATH"), noPath.err());
    }

    @Test
    void theAttachModuleIsAddedOnlyForAttachCommandsAndOnlyWhenTheRuntimeHasIt() throws Exception {
        Path withAttach = fakeJavaHome("with-attach", "25.0.1", "java.base java.desktop jdk.attach jdk.jfr");
        assertTrue(argsSeenBy(withAttach, "jvms").contains("--add-modules jdk.attach"));
        assertTrue(argsSeenBy(withAttach, "attach", "42").contains("--add-modules jdk.attach"));
        assertFalse(argsSeenBy(withAttach, "info").contains("--add-modules"));
        assertFalse(argsSeenBy(withAttach, "--help").contains("--add-modules"));

        Path withoutAttach = fakeJavaHome("without-attach", "25", "java.base java.desktop");
        assertFalse(argsSeenBy(withoutAttach, "jvms").contains("--add-modules"));

        String seen = Launcher.run(tmp, env -> {
            env.put("JAVA_HOME", withAttach.toString());
            env.put("SKILLROY_JAVA_OPTS", "-Xmx64m -Dskillroy.test=1");
        }, Launcher.script(), "run", "a b.yaml").expectExit(0).out();
        List<String> args = seen.lines().toList();
        assertEquals("-Xmx64m", args.get(0), seen);
        assertEquals("-Dskillroy.test=1", args.get(1), seen);
        // Phase 5 (10.2 step 3): the launcher names itself, for the MCP configuration skillroy author writes.
        assertEquals("-Dskillroy.launcher=" + Launcher.dist().toRealPath().resolve("bin").resolve("skillroy"),
                args.get(2), seen);
        assertEquals("-cp", args.get(3), seen);
        assertEquals(Launcher.dist().toRealPath().resolve("lib") + "/*", args.get(4), seen);
        assertEquals("ai.skillroy.testjava.cli.Main", args.get(5), seen);
        assertEquals(List.of("run", "a b.yaml"), args.subList(6, args.size()), "arguments keep their spaces");
    }

    @Test
    void theZipHoldsTheSameDistributionUnderOneTopDirectory() throws IOException {
        Path zip = Path.of(System.getProperty("skillroy.cli.it.zip", "target/skillroy-cli.zip"));
        Set<String> inZip = new TreeSet<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            file.stream().filter(e -> !e.isDirectory()).map(ZipEntry::getName).forEach(inZip::add);
        }
        Set<String> inDir = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Launcher.dist())) {
            files.filter(Files::isRegularFile).forEach(p -> inDir.add("skillroy-cli/"
                    + Launcher.dist().relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(inDir, inZip);
        assertTrue(inZip.contains("skillroy-cli/bin/skillroy"), inZip.toString());
        for (String legal : List.of("LICENSE", "NOTICE", "THIRD-PARTY.txt")) {
            assertTrue(inZip.contains("skillroy-cli/" + legal), legal + " at the top level: " + inZip);
        }
        assertTrue(inZip.stream().anyMatch(n -> n.matches("skillroy-cli/lib/skillroy-cli-.*\\.jar")), inZip.toString());
        assertTrue(inZip.stream().anyMatch(n -> n.matches("skillroy-cli/lib/skillroy-scenario-.*\\.jar")));
        assertFalse(inZip.stream().anyMatch(n -> n.contains("junit")), "no test dependency in the distribution");
    }

    /** A fake Java home whose bin/java prints its arguments one per line, with a release file. */
    private Path fakeJavaHome(String name, String version, String modules) throws IOException {
        Path home = Files.createDirectories(tmp.resolve(name));
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\n' \"$a\"; done\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Test\"\nJAVA_VERSION=\"" + version + "\"\n"
                + "MODULES=\"" + modules + "\"\n", StandardCharsets.UTF_8);
        return home;
    }

    private String argsSeenBy(Path javaHome, String... args) throws Exception {
        return Launcher.run(tmp, env -> env.put("JAVA_HOME", javaHome.toString()), Launcher.script(), args)
                .expectExit(0).out().lines().reduce((a, b) -> a + " " + b).orElse("");
    }
}
