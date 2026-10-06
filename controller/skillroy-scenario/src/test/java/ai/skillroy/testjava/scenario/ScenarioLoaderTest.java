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

package ai.skillroy.testjava.scenario;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLoaderTest {

    private static Scenario load(String yaml) throws ScenarioException {
        return ScenarioLoader.load(yaml.getBytes(StandardCharsets.UTF_8), "test.yaml");
    }

    private static ScenarioException fails(String yaml) {
        return assertThrows(ScenarioException.class, () -> load(yaml));
    }

    @Test
    void readsEveryKeyWithLines() throws Exception {
        Scenario s = load("""
                scenario: basic-loan
                pack: toy
                tags: [smoke, fast]
                requires: [toy.input, "toy.a | toy.b"]
                launchProperties: { toy.seed: A, toy.level: 3 }
                resolve:
                  assistant: { fixture: { code: "100", password: "1111" } }
                  item:      { fixture: { code: YRD-001, deposit: "3.50" } }
                steps:
                  - signOn: { staff: $assistant }
                  - enter: { value: $item.code }

                  - expect: { "loan.lines[code=$item.code].qty": 2 }
                verify:
                  - { "state.name": IDLE }
                on_prompt: { unexpected: fail }
                artifacts: [capture]
                timeouts: { step: 2s }
                """);
        assertEquals("basic-loan", s.id());
        assertEquals("toy", s.pack());
        assertEquals(List.of("smoke", "fast"), s.tags());
        assertEquals(List.of("toy.input", "toy.a | toy.b"), s.requires());
        assertEquals(Map.of("toy.seed", "A", "toy.level", "3"), s.launchProperties());
        assertEquals(2, s.slots().size());
        assertEquals("assistant", s.slots().get(0).name());
        assertEquals(Map.of("code", "100", "password", "1111"), s.slots().get(0).value());
        assertEquals(List.of(10, 11, 13), s.steps().stream().map(Scenario.Step::line).toList());
        assertEquals("signOn", s.steps().get(0).verb());
        assertEquals(Map.of("staff", "$assistant"), s.steps().get(0).args());
        assertEquals(1, s.verify().size());
        assertEquals(15, s.verify().get(0).line());
        assertEquals(Map.of("unexpected", "fail"), s.onPrompt());
        assertEquals(List.of("capture"), s.artifacts());
        assertEquals(2000, s.stepTimeoutMs());
        assertTrue(s.sourceHash().matches("sha256:[0-9a-f]{64}"), s.sourceHash());
    }

    @Test
    void defaults() throws Exception {
        Scenario s = load("scenario: a\nsteps: []\n");
        assertNull(s.pack());
        assertEquals(Scenario.DEFAULT_STEP_TIMEOUT_MS, s.stepTimeoutMs());
        assertEquals(Map.of(), s.onPrompt());
        assertEquals(List.of(), s.requires());
        assertEquals(List.of(), s.steps());
    }

    @Test
    void unknownTopLevelKeyNamesKeyAndLine() {
        ScenarioException e = fails("scenario: a\nsteps: []\n\nrequirez: [x]\n");
        assertEquals(4, e.line());
        assertTrue(e.getMessage().contains("unknown top-level key 'requirez'"), e.getMessage());
        assertTrue(e.getMessage().contains("line 4"), e.getMessage());
    }

    @Test
    void stepMustBeSingleKeyMap() {
        ScenarioException e = fails("scenario: a\nsteps:\n  - enter: {value: 1}\n    key: {action: X}\n");
        assertEquals(3, e.line());
        assertEquals("a", e.scenarioId());
        assertTrue(e.getMessage().contains("single-key"), e.getMessage());
        ScenarioException scalar = fails("scenario: a\nsteps:\n  - just-a-string\n");
        assertTrue(scalar.getMessage().contains("single-key"), scalar.getMessage());
    }

    @Test
    void idIsValidated() {
        assertTrue(fails("scenario: Bad_Id\nsteps: []\n").getMessage().contains("[a-z0-9-]+"));
        assertTrue(fails("steps: []\n").getMessage().contains("(missing)"));
        assertEquals("test.yaml", fails("steps: []\n").scenarioId());
    }

    @Test
    void stepsAreRequired() {
        assertTrue(fails("scenario: a\n").getMessage().contains("'steps' is required"));
        assertTrue(fails("scenario: a\nsteps: {x: 1}\n").getMessage().contains("must be a list"));
    }

    @Test
    void malformedYamlReportsLine() {
        ScenarioException e = fails("scenario: a\nsteps:\n  - enter: {value: [1, 2}\n");
        assertTrue(e.getMessage().contains("malformed YAML"), e.getMessage());
        assertTrue(e.line() >= 3, "line " + e.line());
    }

    @Test
    void duplicateKeysAreErrors() {
        ScenarioException e = fails("scenario: a\nsteps: []\nscenario: b\n");
        assertTrue(e.getMessage().contains("duplicate key 'scenario'"), e.getMessage());
        assertEquals(3, e.line());
    }

    @Test
    void emptyAndMultiDocumentFilesAreErrors() {
        assertTrue(fails("").getMessage().contains("empty"));
        assertTrue(fails("# only a comment\n").getMessage().contains("empty"));
        assertTrue(fails("scenario: a\nsteps: []\n---\nscenario: b\nsteps: []\n").getMessage()
                .contains("more than one YAML document"));
        assertTrue(fails("- a\n- b\n").getMessage().contains("must be a YAML mapping"));
    }

    @Test
    void aliasesAndInfinityAreRejected() {
        assertTrue(fails("scenario: a\nresolve:\n  x: {fixture: &v {a: 1}}\n  y: {fixture: *v}\nsteps: []\n")
                .getMessage().contains("aliases"));
        ScenarioException inf = fails("scenario: a\nsteps:\n  - enter: {value: .inf}\n");
        assertEquals(3, inf.line());
        assertTrue(inf.getMessage().contains(".inf"), inf.getMessage());
    }

    @Test
    void yamlValuesUseJsonNormalForm() throws Exception {
        Scenario s = load("""
                scenario: a
                steps:
                  - enter: { a: yes, b: no, c: on, d: true, e: 3.50, f: 12, g: "007", h: ~, i: "", j: 99999999999 }
                """);
        Map<?, ?> args = (Map<?, ?>) s.steps().get(0).args();
        assertEquals("yes", args.get("a"));
        assertEquals("no", args.get("b"));
        assertEquals("on", args.get("c"));
        assertEquals(Boolean.TRUE, args.get("d"));
        assertEquals(3.5, args.get("e"));
        assertEquals(12L, args.get("f"));
        assertEquals("007", args.get("g"));
        assertTrue(args.containsKey("h"));
        assertNull(args.get("h"));
        assertEquals("", args.get("i"));
        assertEquals(99999999999L, args.get("j"));
    }

    @Test
    void slotsAreFixturesOrQueries() throws Exception {
        // Phase 2 (3.1): a slot has exactly one of fixture and query.
        Scenario query = ScenarioLoader.load(("scenario: a\nresolve:\n  item: { query: Item, args: { n: 1 },"
                + " strategy: [sql], verify: v.one, credential: { realm: staff, key: code } }\nsteps: []\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "a.yaml");
        Scenario.Slot slot = query.slots().get(0);
        assertEquals("query", slot.kind());
        assertEquals("Item", slot.query());
        assertEquals(Map.of("n", 1L), slot.args());
        assertEquals(List.of("sql"), slot.strategy());
        assertEquals(List.of("v.one"), slot.verify());
        assertEquals("staff", slot.credential().realm());
        ScenarioException e = fails("scenario: a\nresolve:\n  item: { fixture: 1, query: Item }\nsteps: []\n");
        assertEquals(3, e.line());
        assertTrue(e.getMessage().contains("exactly one of 'fixture' and 'query'"), e.getMessage());
        assertTrue(fails("scenario: a\nresolve:\n  item: {}\nsteps: []\n").getMessage().contains("exactly one"));
        assertTrue(fails("scenario: a\nresolve:\n  item: { fixture: 1, args: {} }\nsteps: []\n").getMessage()
                .contains("'args' belongs to a query slot"));
        assertTrue(fails("scenario: a\nresolve:\n  9x: { fixture: 1 }\nsteps: []\n").getMessage()
                .contains("slot name"));
    }

    @Test
    void durations() throws Exception {
        assertEquals(10_000, Durations.toMillis("10s", "t"));
        assertEquals(500, Durations.toMillis("500ms", "t"));
        assertEquals(120_000, Durations.toMillis("2m", "t"));
        assertEquals(1500, Durations.toMillis(1500L, "t"));
        assertEquals(1500, Durations.toMillis("1500", "t"));
        assertEquals(3000, Durations.toMillis(3000.0, "t"));
        for (Object bad : new Object[] {"x", "10h", "-5s", 0L, -1L, "0s", 1.5, true, null, "1.5s"}) {
            assertThrows(ScenarioException.class, () -> Durations.toMillis(bad, "t"), String.valueOf(bad));
        }
        ScenarioException e = fails("scenario: a\nsteps: []\ntimeouts: { step: soon }\n");
        assertEquals(3, e.line());
        assertTrue(e.getMessage().contains("timeouts.step"), e.getMessage());
        assertTrue(fails("scenario: a\nsteps: []\ntimeouts: { total: 1s }\n").getMessage()
                .contains("timeouts.total"));
    }

    @Test
    void onPromptAndListsAreChecked() {
        assertTrue(fails("scenario: a\nsteps: []\non_prompt: { expected: fail }\n").getMessage()
                .contains("on_prompt.expected"));
        assertTrue(fails("scenario: a\nsteps: []\non_prompt: { unexpected: 3 }\n").getMessage()
                .contains("on_prompt.unexpected"));
        assertTrue(fails("scenario: a\nsteps: []\nrequires: toy.input\n").getMessage().contains("list"));
        assertTrue(fails("scenario: a\nsteps: []\ntags: [1, {}]\n").getMessage().contains("tags"));
        assertTrue(fails("scenario: a\nsteps: []\nlaunchProperties: { a: [1] }\n").getMessage()
                .contains("scalar"));
        assertTrue(fails("scenario: a\nsteps: []\nverify: [ x ]\n").getMessage().contains("verify"));
    }

    @Test
    void loadsFromFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("from-file.yaml");
        Files.writeString(file, "scenario: from-file\nsteps: []\n");
        assertEquals("from-file", ScenarioLoader.load(file).id());
        assertEquals("from-file.yaml", ScenarioLoader.load(file).sourceName());
        ScenarioException missing = assertThrows(ScenarioException.class,
                () -> ScenarioLoader.load(dir.resolve("missing.yaml")));
        assertTrue(missing.getMessage().contains("cannot read"), missing.getMessage());
    }

    @Test
    void scenarioExceptionLocate() {
        ScenarioException bare = new ScenarioException("bad args");
        assertEquals("bad args", bare.getMessage());
        ScenarioException located = bare.locate("s-1", 7);
        assertEquals("s-1 line 7: bad args", located.getMessage());
        assertEquals("bad args", located.reason());
        assertEquals(located, located.locate("other", 9));
    }
}
