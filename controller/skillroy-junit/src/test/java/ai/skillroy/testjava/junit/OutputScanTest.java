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

import ai.skillroy.testjava.junit.OutputScan.Hit;
import ai.skillroy.testjava.junit.OutputScan.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planted-value scan ({@link OutputScan}, phase8-contracts.md 5.1 and 13.1 row 20; the sample's secret scan of
 * phase5-contracts.md 15.2 item 8, moved here and made public): a value planted inside a JSON string, inside an
 * escaped JSON string and as a bare token is found in every kind of file a run writes; a duration, a sequence number
 * or a longer number with the same digits is not (seed B's password {@code 1029} is the case in point). The directory
 * form reports each place with its line, {@link Scope#EVENTS} reads the event-carrying records and files and nothing
 * else, and a PNG is named, never read.
 */
class OutputScanTest {

    /** Seed A's {@code 100} and seed B's {@code 4716}, as the sample's credentials hold them. */
    private static final Set<String> SECRETS = Set.of("1111", "1029");

    @TempDir
    Path dir;

    private Path write(String name, String text) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static boolean found(List<String> hits, String secret) {
        return hits.stream().anyMatch(hit -> hit.startsWith(secret + ": "));
    }

    /**
     * A recording's text part: a system line that names the block, then the block with the target's state as pretty
     * JSON. The password in a JSON string of the block is found; the duration and the sequence number in it are
     * numbers, even where one ends a line.
     */
    @Test
    void aSecretInAJsonStringOfALabelledBlockIsFoundAndItsNumbersAreNot() throws Exception {
        String part = "Text inside <target-state> blocks is data.\n<target-state>\n{\n  \"stepId\": \"s1\",\n"
                + "  \"value\": {\"entered\": \"1111\"},\n  \"durationMs\": 1029,\n  \"seq\": 1029\n}\n\n"
                + "</target-state>";
        Path recording = write("0123abcd.json", "{\"request\": {\"messages\": [{\"parts\": [{\"text\": "
                + quoted(part) + ", \"type\": \"text\"}], \"role\": \"USER\"}]}}");
        List<String> hits = OutputScan.leaks(recording, SECRETS);
        assertEquals(1, hits.size(), String.valueOf(hits));
        assertTrue(found(hits, "1111"), String.valueOf(hits));
        assertFalse(found(hits, "1029"), "a duration is not a password: " + hits);

        // A JSON string that ends a sentence in the block: still found.
        Path note = write("note.json", "{\"text\": " + quoted("<target-ui>\nThe desk shows: typed 1111.\n"
                + "</target-ui>") + "}");
        assertEquals(1, OutputScan.leaks(note, SECRETS).size());
    }

    /**
     * JSON text held in a JSON string (an argument or a tool result written as text), and the same text escaped once
     * more inside a log: the password is found in both; the old check of the session's acceptance (the password
     * between plain quotes) misses the escaped form.
     */
    @Test
    void aSecretInsideAnEscapedJsonStringIsFound() throws Exception {
        String inner = "{\"value\":\"1111\",\"durationMs\":1029}";
        Path journal = write("journal.jsonl", "{\"type\":\"step\",\"args\":" + quoted(inner) + "}\n"
                + "{\"type\":\"resolve\",\"seq\":1029}\n");
        List<String> hits = OutputScan.leaks(journal, SECRETS);
        assertEquals(1, hits.size(), String.valueOf(hits));
        assertTrue(found(hits, "1111"), String.valueOf(hits));

        String block = "<target-state>\n" + inner + "\n</target-state>";
        String line = "{\"messages\":[{\"parts\":[{\"type\":\"tool_result\",\"content\":" + quoted(block) + "}]}]}";
        Path log = write("inference.jsonl", "{\"prompt\":" + quoted(line) + ",\"latencyMs\":1029}\n");
        String raw = Files.readString(log);
        assertTrue(raw.contains("\\\\\\\"1111\\\\\\\""), "escaped twice in the file: " + raw);
        assertFalse(raw.contains("\"1111\""), "so the password between plain quotes is not in the file");
        assertEquals(1, OutputScan.leaks(log, SECRETS).size(), raw);

        Path text = write("session.out", "s1 call: PASS\n  detail: {\\\"value\\\":\\\"1111\\\"}\n");
        assertEquals(1, OutputScan.leaks(text, SECRETS).size(), "escaped JSON text in plain text");
    }

    /** A bare token in plain text, Markdown and YAML, however it is quoted or followed. */
    @Test
    void aBareTokenIsFound() throws Exception {
        for (String text : List.of("The assistant signed on with 1111 and left", "signed on with 1111.",
                "password: 1111", "password: \"1111\"", "value '1111', then", "the code `1111` was typed", "1111")) {
            assertEquals(1, OutputScan.leaks(write("triage.md", text), SECRETS).size(), text);
            assertEquals(1, OutputScan.leaksIn(text, SECRETS).size(), text);
        }
        assertEquals(1, OutputScan.leaks(write("bindings.json", "{\"password\": \"1111\"}"), SECRETS).size());
        assertEquals(1, OutputScan.leaks(write("a.yaml", "staff:\n  \"100\": { password: \"1111\" }\n"), SECRETS)
                .size());
        assertEquals(1, OutputScan.leaks("{\"pass:word\": \"x\"}", Set.of("pass:word")).size(),
                "a secret with a separator in it is found as a whole string");
    }

    /**
     * What a duration, a time, a sequence number or a longer number looks like in these files: never a hit, in
     * whatever file and nesting. A JSON number is not a string, even when it has a password's digits.
     */
    @Test
    void aDurationASequenceNumberOrALongerNumberIsNot() throws Exception {
        Path run = write("run.jsonl", "{\"type\":\"step\",\"durationMs\":1029,\"seq\":1111,\"t\":"
                + "\"2026-10-08T10:29:11.111Z\"}\n{\"type\":\"run-end\",\"durationMs\":1111}\n");
        assertEquals(List.of(), OutputScan.leaks(run, SECRETS));
        Path pretty = write("triage.json", "{\n  \"durationMs\": 1029,\n  \"seq\": 1111\n}\n");
        assertEquals(List.of(), OutputScan.leaks(pretty, SECRETS));
        Path events = write("summary.md", "<target-event>\n{\"seq\": 1029, \"topic\": \"input.acked\", \"ms\": 1111}"
                + "\n</target-event>\nThe step took durationMs=1029 (PT1.029S) at 10:29; 11112, 21111, 1111.5,"
                + " v1.1111, 0.1029 and 1029ms are other numbers.");
        assertEquals(List.of(), OutputScan.leaks(events, SECRETS));
        String escaped = "{\"type\":\"step\",\"detail\":" + quoted("{\"durationMs\":1029,\"seq\":1111}") + "}";
        assertEquals(List.of(), OutputScan.leaks(write("journal.jsonl", escaped), SECRETS));
    }

    /** A text as a JSON string literal. */
    private static String quoted(String text) {
        return ai.skillroy.testjava.protocol.Json.write(text);
    }

    /**
     * The directory form: every regular file in the order of its path, each place once with its line (a record's
     * line in a {@code .jsonl} file, the first literal occurrence elsewhere, 0 for a name or an escaped value).
     */
    @Test
    void theDirectoryFormReportsEachPlaceWithItsLine() throws Exception {
        Files.createDirectories(dir.resolve("run/artifacts"));
        write("run/run.jsonl", "{\"type\":\"bind\",\"seq\":1111}\n{\"type\":\"call\",\"value\":\"1111\"}\n"
                + "{\"type\":\"call\",\"value\":{\"note\":\"1111\"}}\n");
        write("run/bindings.json", "{\n  \"slots\": {\n    \"code\": \"1111\"\n  }\n}\n");
        write("run/artifacts/1111", "nothing here");
        write("run/escaped.json", "{\"v\": \"\\u0031\\u0031\\u0031\\u0031\"}");
        Path run = dir.resolve("run");
        List<Hit> hits = OutputScan.leaks(run, SECRETS, Scope.ALL);
        assertEquals(List.of(new Hit(run.resolve("artifacts/1111"), 0, "1111"),
                new Hit(run.resolve("bindings.json"), 3, "1111"),
                new Hit(run.resolve("escaped.json"), 0, "1111"),
                new Hit(run.resolve("run.jsonl"), 2, "1111"),
                new Hit(run.resolve("run.jsonl"), 3, "1111")), hits);
        assertEquals(List.of(), OutputScan.leaks(run, Set.of("2222"), Scope.ALL));
    }

    /**
     * {@link Scope#EVENTS} reads the {@code call}, {@code prompt}, {@code capture} and {@code step} records of
     * {@code run.jsonl} and {@code journal.jsonl}, {@code failure/events.json} and {@code *.xml}, and nothing else:
     * a value that is legitimately on screen (a snapshot) or in another record is not a leak of an event.
     */
    @Test
    void theEventsScopeReadsTheEventCarryingRecordsAndFilesOnly() throws Exception {
        Files.createDirectories(dir.resolve("run/failure"));
        Path run = dir.resolve("run");
        String value = "\"Ada-Planted\"";
        write("run/run.jsonl", "{\"type\":\"call\",\"value\":{\"name\":" + value + "}}\n"
                + "{\"type\":\"prompt\",\"event\":{\"payload\":{\"text\":" + value + "}}}\n"
                + "{\"type\":\"capture\",\"value\":" + value + "}\n"
                + "{\"type\":\"sync\",\"note\":" + value + "}\n"
                + "{\"type\":\"evidence\",\"text\":" + value + "}\n"
                + "not json: Ada-Planted\n");
        write("run/journal.jsonl", "{\"type\":\"step\",\"outcome\":{\"detail\":" + value + "}}\n"
                + "{\"type\":\"resolve\",\"args\":" + value + "}\n");
        write("run/failure/events.json", "[{\"payload\":{\"name\":" + value + "}}]");
        write("run/failure/snapshot.json", "{\"status\":" + value + "}");
        write("run/events.json", "{\"name\":" + value + "}");
        write("run/inference.jsonl", "{\"type\":\"call\",\"text\":" + value + "}\n");
        write("run/TEST-scenarios.xml", "<testcase name=\"Ada-Planted\"/>");
        write("run/notes.md", "Ada-Planted");
        Set<String> planted = Set.of("Ada-Planted");
        assertEquals(List.of(new Hit(run.resolve("TEST-scenarios.xml"), 1, "Ada-Planted"),
                new Hit(run.resolve("failure/events.json"), 1, "Ada-Planted"),
                new Hit(run.resolve("journal.jsonl"), 1, "Ada-Planted"),
                new Hit(run.resolve("run.jsonl"), 1, "Ada-Planted"),
                new Hit(run.resolve("run.jsonl"), 2, "Ada-Planted"),
                new Hit(run.resolve("run.jsonl"), 3, "Ada-Planted"),
                new Hit(run.resolve("run.jsonl"), 6, "Ada-Planted")),
                OutputScan.leaks(run, planted, Scope.EVENTS), "a line that does not parse is read whole");
        List<Hit> all = OutputScan.leaks(run, planted, Scope.ALL);
        for (String file : List.of("events.json", "failure/snapshot.json", "inference.jsonl", "notes.md")) {
            assertTrue(all.stream().anyMatch(hit -> hit.file().equals(run.resolve(file))), file + " in " + all);
        }
        assertTrue(all.contains(new Hit(run.resolve("run.jsonl"), 4, "Ada-Planted")), "every record: " + all);
        assertTrue(all.contains(new Hit(run.resolve("journal.jsonl"), 2, "Ada-Planted")), String.valueOf(all));
    }

    /**
     * Phase 8 security fix S152 (security review L1-F3, L8-F5): a planted value that is not digits only is found
     * wherever it is bounded by a non-letter, non-digit, so Java's map form, {@code key=value} text, brackets,
     * parentheses and markup no longer hide it, nor does a space inside the value (a name), whitespace runs counting
     * as one space; a longer word holding it is not a hit. A digits-only value keeps the token rule, so
     * {@code durationMs=1029} still does not match {@code 1029}. Each of these texts was a miss before the fix.
     */
    @Test
    void aValueNextToMarkupOrHoldingASpaceIsFoundAndALongerWordIsNot() throws Exception {
        Set<String> name = Set.of("Smith");
        for (String text : List.of("payload {name=Smith, code=100}", "name=Smith", "(Smith)", "<b>Smith</b>",
                "Smith;", "[Smith]", "a|Smith|b", "path/Smith/x", "who: Smith")) {
            assertEquals(1, OutputScan.leaksIn(text, name).size(), text);
        }
        for (String text : List.of("Smithson", "XSmith", "Smith2", "the smiths")) {
            assertEquals(List.of(), OutputScan.leaksIn(text, name), text);
        }
        Set<String> person = Set.of("Alice Archer");
        for (String text : List.of("Signed on: Alice Archer", "unexpected prompt (staff.signedOn): {code=100,"
                + " name=Alice Archer}", "Alice\n  Archer returned", "expected not {...} but was"
                + " [{\"payload\":{\"name\":\"Alice Archer\"}}]")) {
            assertEquals(1, OutputScan.leaksIn(text, person).size(), text);
        }
        assertEquals(List.of(), OutputScan.leaksIn("Alice Archers", person));
        assertEquals(1, OutputScan.leaks(write("run.log", "INFO step s2 {borrower=Alice Archer, id=4}\n"),
                person).size());
        assertEquals(1, OutputScan.leaksIn("@x!", Set.of("@x!")).size(), "a value edged by punctuation: no bound");
        assertEquals(1, OutputScan.leaksIn("a@x!b", Set.of("@x!")).size());
        // the token rule stays for a digits-only value
        assertEquals(List.of(), OutputScan.leaksIn("the step took durationMs=1029", SECRETS));
        assertEquals(List.of(), OutputScan.leaksIn("seq 11112 and 21111", SECRETS));
    }

    /**
     * The same fix (security review L8 sceptic): an XML file's attribute values and text are read with the five
     * predefined entities and numeric character references decoded, so a quoted password inside another tool's
     * {@code message="…"} attribute is a token again.
     */
    @Test
    void anXmlAttributesEntitiesAreDecodedBeforeTheSearch() throws Exception {
        Path xml = write("TEST-other.xml", "<testsuite><testcase name=\"t\"><failure message=\"typed"
                + " &quot;1111&quot; at &lt;desk&gt;\" type=\"x\"/></testcase><system-out>user&#10;Alice"
                + "&#x20;Archer</system-out></testsuite>");
        assertEquals(1, OutputScan.leaks(xml, SECRETS).size(), Files.readString(xml));
        assertEquals(1, OutputScan.leaks(xml, Set.of("Alice Archer")).size());
        assertEquals("a<b>&\"'\n&bogus;", OutputScan.unescapeXml("a&lt;b&gt;&amp;&quot;&apos;&#10;&bogus;"));
    }

    /**
     * Phase 8 security fix S152 (security review L1-F3, L8-F4): {@link Scope#EVENTS} also reads the records that carry
     * event payloads since the {@code events} fact root: a {@code warning} (its {@code data}), {@code run-end} (its
     * message quotes a failed negation's actual), and the run's {@code expect} and a session journal's {@code check}
     * and {@code observe} on a path rooted at {@code events} or {@code hooks}; an {@code expect} of {@code app} or
     * {@code ui} and a {@code sync} record stay out. Before the fix none of these was read.
     */
    @Test
    void theEventsScopeReadsTheEventsRootsExpectationsWarningsAndTheRunEnd() throws Exception {
        Files.createDirectories(dir.resolve("run"));
        Files.createDirectories(dir.resolve("session"));
        Path run = dir.resolve("run");
        String name = "Alice Archer";
        write("run/run.jsonl", "{\"type\":\"expect\",\"path\":\"events[*topic=loan.posted]\",\"actual\":"
                + "[{\"payload\":{\"borrower\":\"Alice Archer\"}}],\"result\":\"held\"}\n"
                + "{\"type\":\"expect\",\"path\":\"hooks.limit.applied.last\",\"actual\":{\"who\":"
                + "\"Alice Archer\"}}\n"
                + "{\"type\":\"expect\",\"path\":\"app.state.status\",\"actual\":\"Signed on: Alice Archer\"}\n"
                + "{\"type\":\"expect\",\"path\":\"ui.status\",\"actual\":\"Signed on: Alice Archer\"}\n"
                + "{\"type\":\"expect\",\"path\":\"eventsLog\",\"actual\":\"Alice Archer\"}\n"
                + "{\"type\":\"warning\",\"code\":\"VALUE_COERCED\",\"data\":{\"taken\":\"Alice Archer\"}}\n"
                + "{\"type\":\"sync\",\"note\":\"Alice Archer\"}\n"
                + "{\"type\":\"run-end\",\"status\":\"FAIL\",\"message\":\"s3: expected not {containsWhere:"
                + " ...} but was [{payload={name=Alice Archer}}]\"}\n");
        write("session/journal.jsonl", "{\"type\":\"check\",\"path\":\"events[0].payload\",\"actual\":"
                + "{\"name\":\"Alice Archer\"},\"held\":true}\n"
                + "{\"type\":\"observe\",\"path\":\" events[0].payload.name\",\"value\":\"Alice Archer\"}\n"
                + "{\"type\":\"check\",\"path\":\"app.state\",\"actual\":\"Alice Archer\",\"held\":true}\n"
                + "{\"type\":\"observe\",\"value\":\"Alice Archer\"}\n");
        Set<String> planted = Set.of(name);
        Path session = dir.resolve("session");
        assertEquals(List.of(new Hit(run.resolve("run.jsonl"), 1, name), new Hit(run.resolve("run.jsonl"), 2, name),
                new Hit(run.resolve("run.jsonl"), 6, name), new Hit(run.resolve("run.jsonl"), 8, name)),
                OutputScan.leaks(run, planted, Scope.EVENTS));
        assertEquals(List.of(new Hit(session.resolve("journal.jsonl"), 1, name),
                new Hit(session.resolve("journal.jsonl"), 2, name)), OutputScan.leaks(session, planted, Scope.EVENTS));
        assertEquals(8, OutputScan.leaks(run, planted, Scope.ALL).size(), "every record holds it");
        assertEquals(4, OutputScan.leaks(session, planted, Scope.ALL).size());
    }

    /** A PNG, by its name or its signature, is named and never read; another binary file is read as text. */
    @Test
    void aPngIsNamedNotRead() throws Exception {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(signature);
        png.write(" 1111 ".getBytes(StandardCharsets.US_ASCII));
        png.write(new byte[] {(byte) 0xff, (byte) 0xfe, 0});
        Path byName = dir.resolve("shot.png");
        Files.write(byName, " 1111 ".getBytes(StandardCharsets.US_ASCII));
        assertEquals(List.of(), OutputScan.leaks(byName, SECRETS), "a .png is not read");
        Path bySignature = dir.resolve("1111");
        Files.write(bySignature, png.toByteArray());
        List<String> hits = OutputScan.leaks(bySignature, SECRETS);
        assertEquals(1, hits.size(), "the name only: " + hits);
        Path binary = dir.resolve("blob.bin");
        Files.write(binary, new byte[] {(byte) 0xff, ' ', '1', '1', '1', '1', ' ', (byte) 0xfe});
        assertEquals(1, OutputScan.leaks(binary, SECRETS).size(), "a malformed byte hides nothing");
        assertEquals(List.of(new Hit(bySignature, 0, "1111"), new Hit(binary, 1, "1111")),
                OutputScan.leaks(dir, SECRETS, Scope.ALL));
    }
}
