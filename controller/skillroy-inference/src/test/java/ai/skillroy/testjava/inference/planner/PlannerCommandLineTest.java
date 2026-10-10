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

package ai.skillroy.testjava.inference.planner;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security fix P2-L7-F3 (pass 2), on the planner's generated command line, on any platform: no Windows host exists, so
 * the Windows command line is built here as the JDK's default ("legacy") argument handling builds it, and read back as
 * the Microsoft C runtime's {@code CommandLineToArgvW} reads it (both modelled from their published rules; not run on
 * a Windows host, phase6-contracts.md 9.5). The prompt is never an argument; on Windows the schema, whatever its texts
 * hold, comes back as the one argument it is, where unquoted it would have been split into flags of the CLI.
 */
class PlannerCommandLineTest {

    private static final Path CLAUDE = Path.of("C:\\Users\\Desk Clerk\\.local\\bin\\claude.exe");
    private static final Path CONFIG = Path.of("C:\\Users\\Desk Clerk\\AppData\\Local\\Temp\\skillroy-mcp-0123.json");

    /** A schema whose texts (a pack's description, a target's capability id) hold what a command line acts on. */
    private static final String SCHEMA = "{\"type\":\"object\",\"description\":\"a tool \\\"kit\\\" x\\\" "
            + "--dangerously-skip-permissions --allowedTools \\\"Bash\\\" & calc.exe & %ANTHROPIC_API_KEY% | more"
            + " C:\\\\deposits\\\\\",\"$defs\":{}}";

    private static List<String> command(String resume, boolean windows) {
        return Planner.command(CLAUDE, SCHEMA, CONFIG, "claude-opus-5-5", 40, new BigDecimal("1.70"), resume,
                windows);
    }

    /** What the CLI is meant to receive: the schema as its own text, one argument. */
    private static List<String> intended(String resume) {
        List<String> argv = new ArrayList<>(command(resume, false));
        return argv;
    }

    @Test
    void theCommandLineIsTheFixedFlagsTheSchemaThePathsAndTwoNumbersAndNothingElse() {
        assertEquals(List.of(CLAUDE.toString(), "-p", "--bare", "--output-format", "json", "--json-schema", SCHEMA,
                "--mcp-config", CONFIG.toString(), "--strict-mcp-config", "--tools", "", "--allowedTools",
                "mcp__skillroy__*", "--permission-mode", "dontAsk", "--model", "claude-opus-5-5", "--max-turns", "40",
                "--max-budget-usd", "1.70"), command(null, false), "no prompt: it goes on stdin");
        List<String> repair = command("4f1c2a9e-0b7d-4c55-9a51-3e2d7f6b8c10", false);
        assertEquals(List.of("-p", "--resume", "4f1c2a9e-0b7d-4c55-9a51-3e2d7f6b8c10", "--bare"), repair.subList(1, 5));
        assertEquals("1.70", repair.get(repair.size() - 1));
        assertEquals("0.01", Planner.command(CLAUDE, "{}", CONFIG, "m", 1, new BigDecimal("0.004"), null, false)
                .get(21), "the budget floored at 0.01");
    }

    @Test
    void aSessionIdOfAnotherFormIsNeverAnArgument() {
        for (String id : List.of("sess-41\" & calc.exe & \"", "a b", "%ANTHROPIC_API_KEY%", "", "x".repeat(129),
                "id\n--tools")) {
            assertThrows(IllegalArgumentException.class, () -> command(id, true), id);
        }
        assertEquals("sess-41", command("sess-41", true).get(3));
    }

    @Test
    void onWindowsTheSchemaComesBackAsTheOneArgumentItIs() {
        List<String> argv = command(null, true);
        String line = legacyCommandLine(argv);
        assertEquals(intended(null), parse(line), "the JDK's command line, read back by the C runtime: " + line);
        List<String> repair = command("sess-41", true);
        assertEquals(intended("sess-41"), parse(legacyCommandLine(repair)));
        assertTrue(Planner.windowsCommandLineLength(argv) >= line.length(), "the bound is an upper bound");
    }

    /** The test's own control: without the fix's quoting, the same schema is split into flags of the CLI. */
    @Test
    void unquotedTheSchemaWouldHaveBeenSplitIntoFlags() {
        List<String> parsed = parse(legacyCommandLine(command(null, false)));
        assertNotEquals(intended(null), parsed);
        assertTrue(parsed.contains("--dangerously-skip-permissions"), parsed.toString());
    }

    @Test
    void windowsQuotedRoundTripsWhateverTheTextHolds() {
        for (String text : List.of("", "plain", "two words", "a\"b", "a\\\"b", "ends with \\", "ends with \\\\",
                "\\\\server\\share\\", "\"\"", "tab\there", "%PATH% & | < > ^ !", "\\\"\\\\\"")) {
            String quoted = Planner.windowsQuoted(text);
            assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""), quoted);
            assertEquals(List.of("x.exe", text), parse("x.exe " + quoted), quoted);
        }
    }

    @Test
    void aBatchFileIsKnownByItsNameWhateverTheCase() {
        for (String name : List.of("claude.cmd", "CLAUDE.CMD", "claude.Bat", "C:\\npm\\claude.bat")) {
            assertTrue(Planner.isBatchFile(Path.of(name)), name);
        }
        for (String name : List.of("claude", "claude.exe", "claude.cmd.exe", "claude.ps1", "cmd")) {
            assertFalse(Planner.isBatchFile(Path.of(name)), name);
        }
        assertFalse(Planner.isBatchFile(null));
    }

    // ---- the models: the JDK's legacy command line and the C runtime's parser ----

    /**
     * The command line the JDK builds for {@code CreateProcess} by default on Windows ({@code ProcessImpl}, legacy
     * verification, {@code jdk.lang.Process.allowAmbiguousCommands} unset): the executable quoted when it holds white
     * space; an argument that is empty or holds a space or a tab, and does not already start and end with a quote (a
     * trailing {@code \"} does not count as one), wrapped in quotes with a trailing run of backslashes doubled; an inner
     * quote neither escaped nor refused; every other argument as it is.
     */
    static String legacyCommandLine(List<String> command) {
        StringBuilder b = new StringBuilder();
        String exe = command.get(0);
        b.append(exe.indexOf(' ') >= 0 || exe.indexOf('\t') >= 0 ? "\"" + exe + "\"" : exe);
        for (String arg : command.subList(1, command.size())) {
            b.append(' ');
            boolean quoted = arg.length() >= 2 && arg.startsWith("\"") && arg.endsWith("\"")
                    && !arg.endsWith("\\\"");
            if (arg.isEmpty() || !quoted && (arg.indexOf(' ') >= 0 || arg.indexOf('\t') >= 0)) {
                int trailing = 0;
                while (trailing < arg.length() && arg.charAt(arg.length() - 1 - trailing) == '\\') {
                    trailing++;
                }
                b.append('"').append(arg).append("\\".repeat(trailing)).append('"');
            } else {
                b.append(arg);
            }
        }
        return b.toString();
    }

    /**
     * A command line as {@code CommandLineToArgvW} (and the C runtime since 2008) splits it: the program name up to the
     * first white space outside quotes, quotes toggling and no escape; then arguments at white space outside quotes,
     * where {@code 2n} backslashes before a quote are {@code n} backslashes and the quote toggles, {@code 2n+1} are
     * {@code n} backslashes and a literal quote, backslashes elsewhere are literal, and {@code ""} inside quotes is a
     * literal quote.
     */
    static List<String> parse(String line) {
        List<String> args = new ArrayList<>();
        int i = 0;
        int n = line.length();
        StringBuilder program = new StringBuilder();
        boolean inQuotes = false;
        while (i < n) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && (c == ' ' || c == '\t')) {
                break;
            } else {
                program.append(c);
            }
            i++;
        }
        args.add(program.toString());
        while (true) {
            while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
            if (i >= n) {
                return args;
            }
            StringBuilder arg = new StringBuilder();
            inQuotes = false;
            while (i < n) {
                char c = line.charAt(i);
                if (c == '\\') {
                    int k = 0;
                    while (i < n && line.charAt(i) == '\\') {
                        k++;
                        i++;
                    }
                    if (i < n && line.charAt(i) == '"') {
                        arg.append("\\".repeat(k / 2));
                        if (k % 2 == 1) {
                            arg.append('"');
                            i++;
                        }
                    } else {
                        arg.append("\\".repeat(k));
                    }
                    continue;
                }
                if (c == '"') {
                    if (inQuotes && i + 1 < n && line.charAt(i + 1) == '"') {
                        arg.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = !inQuotes;
                    i++;
                    continue;
                }
                if (!inQuotes && (c == ' ' || c == '\t')) {
                    break;
                }
                arg.append(c);
                i++;
            }
            args.add(arg.toString());
        }
    }
}
