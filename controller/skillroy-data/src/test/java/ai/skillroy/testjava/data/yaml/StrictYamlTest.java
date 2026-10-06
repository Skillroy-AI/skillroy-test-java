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

package ai.skillroy.testjava.data.yaml;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reading rules of the strict YAML reader: the YAML cases the scenario loader's tests had for its package-private
 * reader, now against the reader itself (phase2-contracts.md 14.1 item 2), plus the line bookkeeping and the
 * {@code quotesInput} flag credentials rely on.
 */
class StrictYamlTest {

    private static StrictYaml.Doc read(String yaml) throws YamlException {
        return StrictYaml.read(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static YamlException fails(String yaml) {
        return assertThrows(YamlException.class, () -> read(yaml), yaml);
    }

    @Test
    void readsMapsListsAndScalarsWithLines() throws Exception {
        StrictYaml.Doc doc = read("""
                scenario: a
                steps:
                  - enter: { value: x }
                  - key: [1, 2]
                """);
        Map<?, ?> root = (Map<?, ?>) doc.root();
        assertEquals("a", root.get("scenario"));
        List<?> steps = (List<?>) root.get("steps");
        assertEquals(2, steps.size());
        assertEquals(1, doc.keyLine(root, "scenario"));
        assertEquals(2, doc.keyLine(root, "steps"));
        assertEquals(3, doc.lineOf(steps.get(0)));
        assertEquals(4, doc.keyLine(steps.get(1), "key"));
        assertEquals(0, doc.keyLine(root, "missing"));
        assertEquals(0, doc.lineOf("a scalar"));
        assertEquals(0, doc.lineOf(null));
    }

    @Test
    void malformedYamlReportsLine() {
        YamlException e = fails("scenario: a\nsteps:\n  - enter: {value: [1, 2}\n");
        assertTrue(e.reason().contains("malformed YAML"), e.reason());
        assertTrue(e.line() >= 3, "line " + e.line());
        assertTrue(e.quotesInput(), "the parser's message can quote the document");
    }

    @Test
    void duplicateKeysAreErrors() {
        YamlException e = fails("scenario: a\nsteps: []\nscenario: b\n");
        assertEquals("duplicate key 'scenario'", e.reason());
        assertEquals(3, e.line());
        assertFalse(e.quotesInput());
        assertEquals("line 3: duplicate key 'scenario'", e.getMessage());
    }

    @Test
    void emptyAndMultiDocumentFilesAreErrors() {
        assertTrue(fails("").reason().contains("empty"));
        assertTrue(fails("# only a comment\n").reason().contains("empty"));
        assertTrue(fails("scenario: a\nsteps: []\n---\nscenario: b\nsteps: []\n").reason()
                .contains("more than one YAML document"));
    }

    @Test
    void aliasesAndInfinityAreRejected() {
        assertTrue(fails("scenario: a\nresolve:\n  x: {fixture: &v {a: 1}}\n  y: {fixture: *v}\n").reason()
                .contains("aliases"));
        YamlException inf = fails("scenario: a\nsteps:\n  - enter: {value: .inf}\n");
        assertEquals(3, inf.line());
        assertTrue(inf.reason().contains(".inf"), inf.reason());
        assertEquals(1, fails("x: .nan\n").line(), "rejected, as ambiguous or as not finite");
    }

    @Test
    void yamlValuesUseJsonNormalForm() throws Exception {
        Map<?, ?> args = (Map<?, ?>) read(
                "{ a: yes, b: no, c: on, d: true, e: 3.50, f: 12, g: \"007\", h: ~, i: \"\", j: 99999999999 }\n")
                .root();
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
        Map<?, ?> block = (Map<?, ?>) read("k:\nl: x\n").root();
        assertTrue(block.containsKey("k"));
        assertNull(block.get("k"), "an empty plain value is null");
    }

    @Test
    void ambiguousNumbersAreRejectedAndFlaggedAsQuotingTheInput() {
        for (String number : List.of("0100", "0x1F", "1_000")) {
            YamlException e = fails("code: " + number + "\n");
            assertEquals(1, e.line(), number);
            assertTrue(e.reason().startsWith("ambiguous number") || e.reason().startsWith("malformed"),
                    number + ": " + e.reason());
            assertTrue(e.quotesInput(), number);
        }
        YamlException big = fails("n: 123456789012345678901234567890\n");
        assertTrue(big.reason().contains("does not fit 64 bits"), big.reason());
        assertTrue(big.quotesInput());
    }

    @Test
    void nestingDeeperThanTheLimitIsAnError() throws Exception {
        String ok = "[".repeat(StrictYaml.MAX_DEPTH) + "]".repeat(StrictYaml.MAX_DEPTH);
        read(ok);
        String deep = "[".repeat(StrictYaml.MAX_DEPTH + 1) + "]".repeat(StrictYaml.MAX_DEPTH + 1);
        assertTrue(fails(deep).reason().contains("nesting deeper than 100"));
    }

    @Test
    void binaryScalarsAreRejected() {
        YamlException e = fails("b: !!binary aGVsbG8=\n");
        assertTrue(e.reason().contains("unsupported YAML value"), e.reason());
    }

    @Test
    void theExceptionKeepsItsParts() {
        YamlException e = new YamlException(0, "no line");
        assertEquals("no line", e.getMessage());
        assertEquals(0, e.line());
        assertEquals(0, new YamlException(-3, "x").line());
    }
}
