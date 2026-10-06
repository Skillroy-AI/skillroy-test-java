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

import ai.skillroy.testjava.controller.AgentConnection;
import ai.skillroy.testjava.scenario.FailureKind;
import ai.skillroy.testjava.scenario.RunOptions;
import ai.skillroy.testjava.scenario.ScenarioException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class ScenarioTestsTest {

    private final MiniAgent agent = new MiniAgent();

    private static List<DynamicTest> tests(ScenarioSource source, Supplier<AgentConnection> connection,
                                           RunOptions options) {
        return ScenarioTests.dynamicTests(source, connection, options).toList();
    }

    private static DynamicTest named(List<DynamicTest> tests, String name) {
        return tests.stream().filter(t -> t.getDisplayName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void directorySourceListsYamlFilesSorted(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("b.yaml"), "scenario: b\nsteps: []\n");
        Files.writeString(dir.resolve("a.yml"), "scenario: a\nsteps: []\n");
        Files.writeString(dir.resolve("c.txt"), "no");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/d.yaml"), "scenario: d\nsteps: []\n");
        List<ScenarioSource.Entry> entries = ScenarioSource.directory(dir).entries();
        assertEquals(List.of("a.yml", "b.yaml"), entries.stream().map(ScenarioSource.Entry::name).toList());
        assertEquals(dir.resolve("a.yml").toUri(), entries.get(0).uri());
        assertEquals("scenario: a\nsteps: []\n", new String(entries.get(0).content(), StandardCharsets.UTF_8));
        assertThrows(NoSuchFileException.class, () -> ScenarioSource.directory(dir.resolve("missing")).entries());
    }

    @Test
    void classpathSourceReadsDirectoriesAndFiles() throws Exception {
        List<ScenarioSource.Entry> entries = ScenarioSource.classpath("junit-scenarios").entries();
        assertEquals(List.of("a-pass.yaml", "b-fail.yaml", "c-error.yaml", "d-degraded.yaml", "e-broken.yaml"),
                entries.stream().map(ScenarioSource.Entry::name).toList());
        assertEquals(URI.create("classpath:/junit-scenarios/a-pass.yaml"), entries.get(0).uri());
        List<ScenarioSource.Entry> one = ScenarioSource.classpath("/junit-scenarios/c-error.yaml",
                "junit-scenarios/c-error.yaml").entries();
        assertEquals(1, one.size());
        assertThrows(NoSuchFileException.class, () -> ScenarioSource.classpath("nope/x.yaml").entries());
        assertThrows(NoSuchFileException.class, () -> ScenarioSource.classpath("nope-dir").entries());
    }

    @Test
    void classpathSourceReadsJars(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("scenarios.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("pack/scenarios/", "pack/scenarios/x.yaml", "pack/scenarios/y.yml",
                    "pack/scenarios/deeper/z.yaml", "pack/other.yaml", "pack/scenarios/readme.md")) {
                out.putNextEntry(new JarEntry(name));
                if (!name.endsWith("/")) {
                    out.write(("scenario: " + name.replaceAll("\\W", "-").toLowerCase() + "\nsteps: []\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            List<ScenarioSource.Entry> entries = ScenarioSource.classpath(loader, "pack/scenarios").entries();
            assertEquals(List.of("x.yaml", "y.yml"), entries.stream().map(ScenarioSource.Entry::name).toList());
            assertEquals(URI.create("classpath:/pack/scenarios/x.yaml"), entries.get(0).uri());
            assertTrue(new String(entries.get(1).content(), StandardCharsets.UTF_8).contains("y-yml"));
        }
    }

    @Test
    void namesWithSpacesAndOtherCharactersAreRead(@TempDir Path dir) throws Throwable {
        Path scenarios = Files.createDirectories(dir.resolve("my scenarios"));
        Files.writeString(scenarios.resolve("basic loan #1 [draft].yaml"), "scenario: spaced\nsteps: []\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            List<ScenarioSource.Entry> entries = ScenarioSource.classpath(loader, "my scenarios").entries();
            assertEquals(List.of("basic loan #1 [draft].yaml"),
                    entries.stream().map(ScenarioSource.Entry::name).toList());
            assertEquals("classpath", entries.get(0).uri().getScheme());
            assertEquals("/my scenarios/basic loan #1 [draft].yaml", entries.get(0).uri().getPath());
        }
        List<DynamicTest> tests = ScenarioTests.dynamicTests(ScenarioSource.directory(scenarios), () -> agent,
                RunOptions.defaults(), new MiniPack()).toList();
        assertEquals(List.of("spaced"), tests.stream().map(DynamicTest::getDisplayName).toList());
        tests.get(0).getExecutable().execute();
    }

    @Test
    void aSourceThatFailsUncheckedIsOneFailingTest() {
        ScenarioSource broken = () -> {
            throw new IllegalStateException("listing failed");
        };
        List<DynamicTest> tests = tests(broken, () -> agent, null);
        assertEquals(1, tests.size());
        assertEquals("listing failed", assertThrows(IllegalStateException.class,
                () -> tests.get(0).getExecutable().execute()).getMessage());
    }

    @Test
    void oneTestPerScenarioWithMappedOutcomes() throws Throwable {
        List<DynamicTest> tests = tests(ScenarioSource.classpath("junit-scenarios"), () -> agent,
                RunOptions.defaults());
        assertEquals(List.of("mini-pass", "mini-fail", "mini-error", "mini-degraded", "e-broken.yaml"),
                tests.stream().map(DynamicTest::getDisplayName).toList());
        assertEquals(URI.create("classpath:/junit-scenarios/a-pass.yaml"),
                named(tests, "mini-pass").getTestSourceUri().orElseThrow());

        named(tests, "mini-pass").getExecutable().execute();

        AssertionFailedError fail = assertThrows(AssertionFailedError.class,
                () -> named(tests, "mini-fail").getExecutable().execute());
        assertTrue(fail.getMessage().startsWith("mini-fail FAIL ASSERTION at s1: expectation failed: app.mini.state"),
                fail.getMessage());
        assertTrue(fail.getMessage().contains("\"path\": \"app.mini.state\""), fail.getMessage());
        assertTrue(fail.getMessage().contains("steps:\n  s1 expect FAIL (line 4)"), fail.getMessage());

        ScenarioRunException error = assertThrows(ScenarioRunException.class,
                () -> named(tests, "mini-error").getExecutable().execute());
        assertEquals(FailureKind.PRECONDITION_FAILED, error.result().failureKind());
        assertTrue(error.getMessage().contains("mini.missing: missing"), error.getMessage());

        TestAbortedException degraded = assertThrows(TestAbortedException.class,
                () -> named(tests, "mini-degraded").getExecutable().execute());
        assertTrue(degraded.getMessage().contains("mini-degraded DEGRADED: skipped expectations: s1: app.mini.state"),
                degraded.getMessage());

        ScenarioException broken = assertThrows(ScenarioException.class,
                () -> named(tests, "e-broken.yaml").getExecutable().execute());
        assertTrue(broken.getMessage().contains("unknown top-level key 'bogus'"), broken.getMessage());
        assertFalse(agent.closed, "the glue never closes the caller's connection");
    }

    @Test
    void degradedCanFailOrPass() {
        ScenarioSource source = ScenarioSource.classpath("junit-scenarios/d-degraded.yaml");
        DynamicTest asFailure = tests(source, () -> agent, RunOptions.defaults()
                .withDegradedAs(RunOptions.DegradedAs.FAILURE)).get(0);
        assertThrows(AssertionFailedError.class, () -> asFailure.getExecutable().execute());
        DynamicTest asPass = tests(source, () -> agent, RunOptions.defaults()
                .withDegradedAs(RunOptions.DegradedAs.PASS)).get(0);
        assertDoesNotThrow(() -> asPass.getExecutable().execute());
    }

    @Test
    void aClickThatFellBackIsDegradedAndAStrictOneFails(@TempDir Path dir) throws Exception {
        // Amendment A29 through the glue: the fall-back is listed in the DEGRADED message and follows degradedAs.
        String yaml = "scenario: mini-click\npack: mini\nui: { clickRung: COMPONENT }\nsteps:\n"
                + "  - click: { target: h1 }\n";
        Files.writeString(dir.resolve("click.yaml"), yaml);
        ScenarioSource source = ScenarioSource.directory(dir);
        TestAbortedException degraded = assertThrows(TestAbortedException.class,
                () -> tests(source, () -> agent, RunOptions.defaults()).get(0).getExecutable().execute());
        assertEquals("mini-click DEGRADED: degraded fidelity: s1: click fell back from COMPONENT to ACTION"
                + " (FOCUS_FAILED: x)", degraded.getMessage());
        assertThrows(AssertionFailedError.class, () -> tests(source, () -> agent, RunOptions.defaults()
                .withDegradedAs(RunOptions.DegradedAs.FAILURE)).get(0).getExecutable().execute());

        Files.writeString(dir.resolve("click.yaml"), yaml.replace("COMPONENT }", "COMPONENT, strict: true }"));
        AssertionFailedError strict = assertThrows(AssertionFailedError.class,
                () -> tests(source, () -> agent, RunOptions.defaults()).get(0).getExecutable().execute());
        assertTrue(strict.getMessage().startsWith("mini-click FAIL ASSERTION at s1: click did not fire:"
                + " FOCUS_FAILED: FOCUS_FAILED: x"), strict.getMessage());
    }

    @Test
    void eachScenarioWritesIntoItsOwnDirectory(@TempDir Path dir) throws Throwable {
        List<DynamicTest> tests = tests(ScenarioSource.classpath("junit-scenarios/a-pass.yaml",
                "junit-scenarios/c-error.yaml"), () -> agent, RunOptions.defaults().withOutputDir(dir));
        for (DynamicTest test : tests) {
            try {
                test.getExecutable().execute();
            } catch (ScenarioRunException expected) {
                assertEquals("mini-error", expected.result().scenarioId());
            }
        }
        assertTrue(Files.exists(dir.resolve("mini-pass/run.jsonl")));
        assertTrue(Files.exists(dir.resolve("mini-pass/bindings.json")));
        assertTrue(Files.exists(dir.resolve("mini-error/run.jsonl")));
    }

    @Test
    void theConnectionIsObtainedInsideEachTest() throws Throwable {
        AtomicInteger asked = new AtomicInteger();
        List<DynamicTest> tests = tests(ScenarioSource.classpath("junit-scenarios/a-pass.yaml"), () -> {
            asked.incrementAndGet();
            return agent;
        }, null);
        assertEquals(0, asked.get());
        tests.get(0).getExecutable().execute();
        tests.get(0).getExecutable().execute();
        assertEquals(2, asked.get());

        DynamicTest unreachable = tests(ScenarioSource.classpath("junit-scenarios/a-pass.yaml"), () -> {
            throw new IllegalStateException("attach failed");
        }, null).get(0);
        assertEquals("attach failed", assertThrows(IllegalStateException.class,
                () -> unreachable.getExecutable().execute()).getMessage());
        DynamicTest nothing = tests(ScenarioSource.classpath("junit-scenarios/a-pass.yaml"), () -> null, null).get(0);
        assertThrows(IllegalStateException.class, () -> nothing.getExecutable().execute());
    }

    @Test
    void emptyOrUnreadableSourcesFailVisibly(@TempDir Path dir) {
        List<DynamicTest> empty = tests(ScenarioSource.directory(dir), () -> agent, null);
        assertEquals(1, empty.size());
        assertThrows(AssertionFailedError.class, () -> empty.get(0).getExecutable().execute());
        List<DynamicTest> missing = tests(ScenarioSource.directory(dir.resolve("gone")), () -> agent, null);
        assertThrows(NoSuchFileException.class, () -> missing.get(0).getExecutable().execute());
    }

    @Test
    void packCanBeGivenOrMustBeFound(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("other.yaml"), "scenario: other\npack: elsewhere\nsteps: []\n");
        DynamicTest unknownPack = ScenarioTests.dynamicTests(ScenarioSource.directory(dir), () -> agent,
                RunOptions.defaults(), null).toList().get(0);
        ScenarioException e = assertThrows(ScenarioException.class, () -> unknownPack.getExecutable().execute());
        assertTrue(e.getMessage().contains("domain pack 'elsewhere' is not on the class path"), e.getMessage());

        Files.writeString(dir.resolve("other.yaml"), "scenario: other\nsteps:\n  - call: { primitive: mini.poke }\n");
        DynamicTest explicit = ScenarioTests.dynamicTests(ScenarioSource.directory(dir), () -> agent,
                RunOptions.defaults(), new MiniPack()).toList().get(0);
        assertDoesNotThrow(() -> explicit.getExecutable().execute());
    }

    /** Run through the JUnit Platform by {@link #theLauncherReportsEachOutcome}; inert otherwise. */
    static class Factory {

        static volatile boolean enabled;

        @TestFactory
        Stream<DynamicTest> scenarios() {
            Assumptions.assumeTrue(enabled, "only run from ScenarioTestsTest");
            return ScenarioTests.dynamicTests(ScenarioSource.classpath("junit-scenarios"), MiniAgent::new,
                    RunOptions.defaults().withStepTimeout(Duration.ofMillis(100)));
        }
    }

    @Test
    void theLauncherReportsEachOutcome() {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(Factory.class)).build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Factory.enabled = true;
        try {
            launcher.execute(request, listener);
        } finally {
            Factory.enabled = false;
        }
        TestExecutionSummary summary = listener.getSummary();
        assertEquals(5, summary.getTestsStartedCount());
        assertEquals(1, summary.getTestsSucceededCount());
        assertEquals(3, summary.getTestsFailedCount(), "fail, error and the broken file");
        assertEquals(1, summary.getTestsAbortedCount(), "DEGRADED is shown as skipped (aborted)");
        assertEquals(List.of(AssertionFailedError.class, ScenarioRunException.class, ScenarioException.class),
                summary.getFailures().stream().map(f -> (Class<?>) f.getException().getClass()).toList());
    }
}
