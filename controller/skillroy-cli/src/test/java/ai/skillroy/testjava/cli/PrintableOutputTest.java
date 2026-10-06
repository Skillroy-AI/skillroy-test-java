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

import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Security finding S26: the CLI's print boundary makes control characters visible. */
class PrintableOutputTest {

    private static final String HOSTILE = "a\u001b]52;c;aGk=\u0007b\u001b[2K\rc\u0008d\u009be\u007ff‮g⁦h i";
    private static final String VISIBLE = "a\\u001b]52;c;aGk=\\u0007b\\u001b[2K\\u000dc\\u0008d\\u009be\\u007ff"
            + "\\u202eg\\u2066h\\u2028i";

    @TempDir
    Path tmp;

    @Test
    void printableKeepsLinesAndTabsAndShowsEverythingElse() {
        assertEquals(VISIBLE, Text.printable(HOSTILE));
        assertEquals("line\none\r\ntwo\tcol", Text.printable("line\none\r\ntwo\tcol"), "CRLF and tab stay");
        assertEquals("end\\u000d", Text.printable("end\r"));
        assertEquals("plain ASCII and éü中 😀", Text.printable("plain ASCII and éü中 😀"));
        assertNull(Text.printable(null));
        String same = "unchanged";
        assertSame(same, Text.printable(same));
        assertEquals("x y\\u001bz", Text.oneLine("x\ny\u001bz", 80));
    }

    @Test
    void theStreamFiltersEveryCharacterPath() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream raw = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream out = SafePrintStream.wrap(raw);
        assertSame(out, SafePrintStream.wrap(out));
        out.print(HOSTILE);
        out.println(HOSTILE);
        out.print((Object) HOSTILE);
        out.println((Object) HOSTILE);
        out.print(HOSTILE.toCharArray());
        out.println(HOSTILE.toCharArray());
        out.print('\u001b');
        out.println('\u0007');
        out.printf("%s|%n", HOSTILE);
        out.format("%s|", HOSTILE);
        out.append(HOSTILE).append(HOSTILE, 0, 3).append('\u009b');
        out.println();
        new IllegalStateException("boom " + HOSTILE).printStackTrace(out);
        out.flush();
        String text = bytes.toString(StandardCharsets.UTF_8);
        RunCommandTest.assertNoRawControls(text);
        assertTrue(text.startsWith(VISIBLE + VISIBLE + System.lineSeparator()), text);
        assertTrue(text.contains("boom " + VISIBLE), text);
        assertTrue(text.contains("\\u001b\\u0007" + System.lineSeparator()), text);
    }

    @Test
    void jsonOutputStillParsesToTheOriginalText() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = SafePrintStream.wrap(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        out.print(Json.writePretty(Map.of("message", HOSTILE)));
        out.flush();
        String text = bytes.toString(StandardCharsets.UTF_8);
        RunCommandTest.assertNoRawControls(text);
        assertEquals(HOSTILE, Json.parseObject(text).get("message"));
    }

    @Test
    void errorsFromTheCommandLineAreShownNeutralised() {
        CliHarness.Outcome outcome = CliHarness.run(tmp, "compile", tmp.resolve("x\u001b[2K\rPASS.yaml").toString());
        RunCommandTest.assertNoRawControls(outcome.out());
        RunCommandTest.assertNoRawControls(outcome.err());
        assertTrue(outcome.err().contains("x\\u001b[2K\\u000dPASS.yaml"), outcome.err());
        CliHarness.Outcome unknown = CliHarness.run(tmp, "\u001b]0;title\u0007");
        assertEquals(Exit.USAGE, unknown.exit());
        RunCommandTest.assertNoRawControls(unknown.err());
    }
}
