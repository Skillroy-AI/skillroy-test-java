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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgParserTest {

    private static final CommandSpec SPEC = new CommandSpec("demo", "a demo", "<a> [<b>]", "Demo description.",
            List.of(OptionSpec.flag("--json", "json output"),
                    OptionSpec.value("--since", "seq", "since"),
                    OptionSpec.value("--timeout", "duration", "timeout"),
                    OptionSpec.value("--dir", "dir", "a directory"),
                    OptionSpec.repeatable("--pack", "jar", "a pack\nsecond help line")),
            1, 2);

    private static ParsedArgs parse(String... args) throws UsageException {
        return ArgParser.parse(SPEC, List.of(args));
    }

    private static String usageError(String... args) {
        return assertThrows(UsageException.class, () -> parse(args)).getMessage();
    }

    @Test
    void readsFlagsValuesAndPositionalsInAnyOrder() throws UsageException {
        ParsedArgs parsed = parse("--json", "first", "--since", "5", "--pack", "a.jar", "second", "--pack=b.jar");
        assertTrue(parsed.flag("--json"));
        assertEquals("5", parsed.value("--since"));
        assertEquals(List.of("a.jar", "b.jar"), parsed.values("--pack"));
        assertEquals(List.of("first", "second"), parsed.positionals());
        assertFalse(parsed.help());
        assertNull(parsed.value("--dir"));
        assertEquals(List.of(), parse("x").values("--pack"));
    }

    @Test
    void theWordAfterAValuedOptionIsItsValueEvenWithADash() throws UsageException {
        assertEquals("-1", parse("x", "--since", "-1").value("--since"));
        assertEquals("--json", parse("x", "--dir", "--json").value("--dir"));
        assertEquals("", parse("x", "--dir=").value("--dir"));
        assertEquals("a=b", parse("x", "--dir=a=b").value("--dir"));
    }

    @Test
    void doubleDashEndsOptionsAndALoneDashIsPositional() throws UsageException {
        ParsedArgs parsed = parse("--", "--json", "-x");
        assertEquals(List.of("--json", "-x"), parsed.positionals());
        assertFalse(parsed.flag("--json"));
        assertEquals(List.of("-"), parse("-").positionals());
        assertFalse(parse("--", "--help").help());
        assertEquals(List.of("--help"), parse("--", "--help").positionals());
    }

    @Test
    void helpWinsOverEverythingElse() throws UsageException {
        assertTrue(parse("--help").help());
        assertTrue(parse("-h", "--bogus", "1", "2", "3").help());
        assertTrue(parse("x", "--since", "--help").help());
    }

    @Test
    void rejectsWhatTheSpecDoesNotAllow() {
        assertTrue(usageError("x", "--bogus").contains("unknown option '--bogus'"));
        assertTrue(usageError("x", "--bogus=1").contains("unknown option '--bogus'"));
        assertTrue(usageError("x", "-j").contains("unknown option '-j'"));
        assertTrue(usageError("x", "--since").contains("needs a value <seq>"));
        assertTrue(usageError("x", "--json=yes").contains("takes no value"));
        assertTrue(usageError("x", "--since", "1", "--since", "2").contains("more than once"));
        assertTrue(usageError("x", "--json", "--json").contains("more than once"));
        assertTrue(usageError().contains("missing argument"));
        assertTrue(usageError("a", "b", "c").contains("unexpected argument 'c'"));
    }

    @Test
    void typedGettersNameTheOptionOnBadValues() throws UsageException {
        assertEquals(5L, parse("x", "--since", "5").longValue("--since", 0, 10));
        assertNull(parse("x").longValue("--since", 0, 10));
        assertTrue(assertThrows(UsageException.class, () -> parse("x", "--since", "five").longValue("--since", 0,
                10)).getMessage().contains("--since needs a whole number"));
        assertTrue(assertThrows(UsageException.class, () -> parse("x", "--since", "11").longValue("--since", 0,
                10)).getMessage().contains("between 0 and 10"));
        assertEquals(10_000L, parse("x", "--timeout", "10s").durationMs("--timeout"));
        assertEquals(250L, parse("x", "--timeout", "250ms").durationMs("--timeout"));
        assertEquals(120_000L, parse("x", "--timeout", "2m").durationMs("--timeout"));
        assertTrue(assertThrows(UsageException.class, () -> parse("x", "--timeout", "soon").durationMs("--timeout"))
                .getMessage().contains("--timeout"));
        assertTrue(assertThrows(UsageException.class, () -> parse("x", "--timeout", "0").durationMs("--timeout"))
                .getMessage().contains("positive"));
        assertEquals(Path.of("some/dir"), parse("x", "--dir", "some/dir").path("--dir"));
        assertTrue(assertThrows(UsageException.class, () -> parse("x", "--dir", " ").path("--dir")).getMessage()
                .contains("needs a path"));
    }

    @Test
    void askingForAnUndeclaredOptionIsAProgrammingError() throws UsageException {
        ParsedArgs parsed = parse("x");
        assertThrows(IllegalArgumentException.class, () -> parsed.flag("--nope"));
    }

    @Test
    void helpTextListsUsageDescriptionAndEveryOption() {
        String help = SPEC.help();
        assertTrue(help.startsWith("Usage: skillroy demo <a> [<b>] [options]\n\nDemo description.\n\nOptions:\n"),
                help);
        for (String option : List.of("--json", "--since <seq>", "--timeout <duration>", "--dir <dir>",
                "--pack <jar>...", "-h, --help")) {
            assertTrue(help.contains("  " + option), option + " in\n" + help);
        }
        assertTrue(help.contains("\n" + " ".repeat(30) + "second help line\n"), help);
    }

    @Test
    void specRejectsDuplicateOptionsAndBadNames() {
        assertThrows(IllegalArgumentException.class, () -> new CommandSpec("x", "", "", "", List.of(
                OptionSpec.flag("--a", ""), OptionSpec.flag("--a", "")), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> OptionSpec.flag("-a", ""));
        assertThrows(IllegalArgumentException.class, () -> OptionSpec.flag("--a=b", ""));
        assertThrows(IllegalArgumentException.class, () -> new CommandSpec("x", "", "", "", List.of(), 2, 1));
    }
}
