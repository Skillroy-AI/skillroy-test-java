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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @TempDir
    Path tmp;

    @Test
    void noArgumentsIsAUsageErrorWithTheOverviewOnStderr() {
        CliHarness.Outcome outcome = CliHarness.run(tmp).expectExit(Exit.USAGE);
        assertEquals("", outcome.out());
        assertTrue(outcome.err().startsWith("Usage: skillroy <command> [options]"), outcome.err());
    }

    @Test
    void overviewListsEveryCommandAndTheExitCodes() {
        String out = CliHarness.run(tmp, "--help").expectExit(Exit.OK).out();
        for (String command : new String[] {"jvms", "attach", "info", "caps", "schema", "call", "snapshot", "events",
                "compile", "run", "resolve", "diff-bindings", "re-resolve", "mcp", "author", "triage", "session"}) {
            assertTrue(out.contains("\n  " + command + " "), command + " in\n" + out);
        }
        assertTrue(out.contains("3 agent unreachable; 64 usage error"), out);
        assertTrue(out.contains("ANTHROPIC_API_KEY (never an option or a file)"), "Phase 5 (2.3): where the key"
                + " comes from: " + out);
        assertEquals(out, CliHarness.run(tmp, "-h").expectExit(Exit.OK).out());
        assertEquals(out, CliHarness.run(tmp, "help").expectExit(Exit.OK).out());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jvms", "attach", "info", "caps", "schema", "call", "snapshot", "events", "compile",
            "run", "resolve", "diff-bindings", "re-resolve", "mcp", "author", "triage", "session"})
    void everyCommandHasHelp(String command) {
        String help = CliHarness.run(tmp, command, "--help").expectExit(Exit.OK).out();
        assertTrue(help.startsWith("Usage: skillroy " + command), help);
        assertTrue(help.contains("-h, --help"), help);
        assertEquals(help, CliHarness.run(tmp, "help", command).expectExit(Exit.OK).out());
        assertEquals(help, CliHarness.run(tmp, command, "-h", "--no-such-option").expectExit(Exit.OK).out());
    }

    @Test
    void unknownCommandsAndOptionsAreUsageErrors() {
        CliHarness.Outcome unknown = CliHarness.run(tmp, "frobnicate").expectExit(Exit.USAGE);
        assertTrue(unknown.err().contains("unknown command 'frobnicate'"), unknown.err());
        assertTrue(unknown.err().contains("Run 'skillroy --help' for usage."), unknown.err());
        CliHarness.Outcome option = CliHarness.run(tmp, "caps", "--frobnicate").expectExit(Exit.USAGE);
        assertTrue(option.err().contains("skillroy caps: unknown option '--frobnicate'"), option.err());
        assertTrue(option.err().contains("Run 'skillroy caps --help' for usage."), option.err());
        CliHarness.run(tmp, "help", "frobnicate").expectExit(Exit.USAGE);
        CliHarness.run(tmp, "--frobnicate").expectExit(Exit.USAGE);
        CliHarness.run(tmp, "caps", "extra").expectExit(Exit.USAGE);
        CliHarness.run(tmp, "call").expectExit(Exit.USAGE);
    }

    @Test
    void versionIsPrinted() {
        String out = CliHarness.run(tmp, "--version").expectExit(Exit.OK).out();
        assertTrue(out.startsWith("skillroy "), out);
    }

    @Test
    void publicEntryPointUsesTheGivenStreams() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Main.run(new String[] {"schema", "--help"}, new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(Exit.OK, exit);
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("Usage: skillroy schema"));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }
}
