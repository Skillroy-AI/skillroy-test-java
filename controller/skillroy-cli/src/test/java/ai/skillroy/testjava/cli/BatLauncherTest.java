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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows launcher {@code src/main/bat/skillroy.bat} checked as text (phase6-contracts.md 9.1, D137): no Windows
 * host exists in Phase 6, so what a host would prove about the text is proved here, on every platform. Running it
 * waits for the host (9.5). Security fix P2-L7-F2 (pass 2): no command of the launcher or of
 * {@code ci/run-on-windows.cmd} is looked for in the current directory.
 */
class BatLauncherTest {

    private static final Path BAT = Path.of("src/main/bat/skillroy.bat");
    private static final Path SH = Path.of("src/main/sh/skillroy");
    private static final Path GITATTRIBUTES = Path.of("../../.gitattributes");
    private static final Path RUNNER = Path.of("../../ci/run-on-windows.cmd");
    /** The full path every command outside {@code cmd.exe} must have (security fix P2-L7-F2). */
    private static final String SYSTEM32 = "%systemroot%\\system32\\";
    /** The {@code cmd.exe} built-ins the two files may use. */
    private static final Set<String> BUILT_INS = Set.of("if", "for", "set", "setlocal", "endlocal", "echo", "exit",
            "goto", "call", "cd", "rem", "shift", "pushd", "popd");

    @Test
    void theFileIsCrlfThroughoutAndStartsWithEchoOff() throws IOException {
        byte[] bytes = Files.readAllBytes(BAT);
        assertTrue(bytes.length > 0);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                assertTrue(i > 0 && bytes[i - 1] == '\r', "a LF without CR at byte " + i);
            }
            if (bytes[i] == '\r') {
                assertTrue(i + 1 < bytes.length && bytes[i + 1] == '\n', "a CR without LF at byte " + i);
            }
        }
        assertEquals('\n', bytes[bytes.length - 1], "the last line ends with CRLF");
        assertTrue(text().startsWith("@echo off\r\n"), "the first line is @echo off");
        assertTrue(code().contains("setlocal"), "setlocal");
    }

    @Test
    void theApacheHeaderIsInTheFirst25LinesAsRemLines() throws IOException {
        List<String> first = lines().subList(0, 25);
        assertTrue(first.contains("rem Licensed under the Apache License, Version 2.0 (the \"License\");"),
                first.toString());
        assertTrue(first.contains("rem Copyright 2026 Mike Mills."), first.toString());
    }

    @Test
    void everyGotoLabelIsDefined() throws IOException {
        Set<String> used = new TreeSet<>();
        Matcher goTo = Pattern.compile("(?i)\\bgoto\\s+:?([A-Za-z_][A-Za-z0-9_]*)").matcher(code());
        while (goTo.find()) {
            used.add(goTo.group(1).toLowerCase());
        }
        Set<String> defined = new TreeSet<>();
        for (String line : lines()) {
            if (line.startsWith(":") && !line.startsWith("::")) {
                defined.add(line.substring(1).trim().toLowerCase());
            }
        }
        used.remove("eof");
        assertFalse(used.isEmpty());
        assertTrue(defined.containsAll(used), "used " + used + ", defined " + defined);
    }

    @Test
    void theThreeExit69MessagesAreTheShellLaunchersByteForByteButForTheVariables() throws IOException {
        List<String> shMessages = new ArrayList<>();
        Matcher sh = Pattern.compile("(?m)^\\s*echo \"(skillroy: [^\"]*)\" >&2$").matcher(Files.readString(SH));
        while (sh.find()) {
            shMessages.add(sh.group(1).replace("$JAVA_HOME", "%JAVA_HOME%").replace("$java", "%java%")
                    .replace("$version", "%version%"));
        }
        assertEquals(3, shMessages.size(), "the shell launcher's three messages: " + shMessages);
        List<String> batMessages = new ArrayList<>();
        List<String> lines = lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(">&2 echo ")) {
                batMessages.add(line.substring(">&2 echo ".length()));
                assertEquals("exit /b 69", lines.get(i + 1), "each message is followed by exit 69: " + line);
            }
        }
        assertEquals(shMessages, batMessages);
    }

    @Test
    void theJavaCommandLineMirrorsTheShellLaunchers() throws IOException {
        String code = code();
        assertTrue(code.contains("ai.skillroy.testjava.cli.Main %*"), code);
        assertTrue(code.contains("-cp \"%home%\\lib\\*\""), code);
        assertTrue(code.contains("\"-Dskillroy.launcher=%home%\\bin\\skillroy.bat\""), code);
        assertTrue(code.contains("%SKILLROY_JAVA_OPTS%"), code);
        assertTrue(code.contains("LSS 25"), code);
        assertTrue(code.contains("exit /b %ERRORLEVEL%"), code);
        assertTrue(code.contains("for %%i in (\"%~dp0..\") do set \"home=%%~fi\""), code);
        assertTrue(code.contains("%%~$PATH:i"), "java.exe from the PATH");
        assertTrue(code.contains("%JAVA_HOME%\\bin\\java.exe"), "java.exe under JAVA_HOME");
        assertTrue(code.contains("findstr.exe /r /c:\"^MODULES=.*[\\\" ]jdk\\.attach[\\\" ]\""), "the MODULES test");
        assertTrue(code.contains("--list-modules"), "the --list-modules fallback");
    }

    @Test
    void theAttachModuleIsAddedOnlyAfterTheJvmsAndAttachTest() throws IOException {
        List<String> code = codeLines();
        int jvms = indexOf(code, "if /i \"%~1\"==\"jvms\"");
        int attach = indexOf(code, "if /i \"%~1\"==\"attach\"");
        assertTrue(jvms >= 0 && attach >= 0, code.toString());
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).contains("--add-modules jdk.attach")) {
                assertTrue(i > jvms && i > attach, "--add-modules before the command test: " + code.get(i));
            }
        }
    }

    @Test
    void gitattributesKeepsTheCrlfLinesBelowTheLfRule() throws IOException {
        List<String> attributes = Files.readAllLines(GITATTRIBUTES);
        int lf = attributes.indexOf("* text=auto eol=lf");
        int bat = attributes.indexOf("*.bat text eol=crlf");
        int cmd = attributes.indexOf("*.cmd text eol=crlf");
        assertTrue(lf >= 0 && bat >= 0 && cmd >= 0, attributes.toString());
        assertTrue(bat > lf && cmd > lf, "a later line wins: the CRLF lines must follow the LF rule");
    }

    /**
     * Security fix P2-L7-F2 (pass 2): {@code cmd.exe} looks for a command named without a path in the current
     * directory first, so every command word of the launcher is a built-in, a label, {@code "%java%"} (found through
     * {@code PATH} only) or {@code %SystemRoot%\System32\<name>.exe}, and {@code NoDefaultCurrentDirectoryInExePath}
     * is set directly after {@code setlocal}, before the first command that is not a built-in.
     */
    @Test
    void noCommandOfTheLauncherIsTakenFromTheCurrentDirectory() throws IOException {
        List<String> words = checkCommandWords(codeLines(), Set.of("\"%java%\""), Set.of());
        assertTrue(words.stream().filter(w -> w.startsWith(SYSTEM32)).count() >= 5, "findstr in all five places: "
                + words);
        assertTrue(words.contains("\"%java%\""), words.toString());
    }

    /** Security fix P2-L7-F2: the Windows runner's {@code call mvn} stays a PATH lookup that skips the checkout. */
    @Test
    void theWindowsRunnerTakesNoCommandFromTheCheckout() throws IOException {
        List<String> code = new ArrayList<>();
        for (String line : new String(Files.readAllBytes(RUNNER), StandardCharsets.US_ASCII).split("\r\n")) {
            String t = line.trim();
            if (!t.equals("rem") && !t.startsWith("rem ")) {
                code.add(line);
            }
        }
        List<String> words = checkCommandWords(code, Set.of(), Set.of("mvn"));
        assertTrue(words.contains("call mvn"), words.toString());
    }

    /**
     * Every command word of {@code code} (the lines that are not comments), checked: a built-in, a label, one of
     * {@code allowed}, {@code call} with a label or one of {@code callable}, or a full path under
     * {@code %SystemRoot%\System32\} ending {@code .exe}; and {@code set "NoDefaultCurrentDirectoryInExePath=1"} the
     * first command after {@code setlocal}, before any command that is not a built-in.
     *
     * @return the command words, in order, lower-case, a {@code call} as {@code call <target>}
     */
    private static List<String> checkCommandWords(List<String> code, Set<String> allowed, Set<String> callable) {
        List<String> words = new ArrayList<>();
        int setlocal = -1;
        int variable = -1;
        int firstExternal = -1;
        for (int i = 0; i < code.size(); i++) {
            String line = code.get(i).trim();
            if (line.isEmpty() || line.startsWith(":")) {
                continue;
            }
            if (line.equalsIgnoreCase("setlocal") && setlocal < 0) {
                setlocal = i;
            }
            if (line.equals("set \"NoDefaultCurrentDirectoryInExePath=1\"") && variable < 0) {
                variable = i;
            }
            for (String segment : segments(line)) {
                for (String word : commandWords(segment)) {
                    String lower = word.toLowerCase(java.util.Locale.ROOT);
                    if (BUILT_INS.contains(lower) || lower.startsWith("call ")) {
                        if (lower.startsWith("call ")) {
                            String target = lower.substring(5);
                            assertTrue(target.startsWith(":") || callable.contains(target), "call " + target + " in: "
                                    + line);
                        }
                    } else {
                        assertTrue(allowed.contains(word) || lower.startsWith(SYSTEM32) && lower.endsWith(".exe")
                                && !lower.substring(SYSTEM32.length()).contains("\\"), "the command '" + word
                                + "' would be looked for in the current directory first: " + line);
                        if (firstExternal < 0) {
                            firstExternal = i;
                        }
                    }
                    if (lower.startsWith("call ") && !lower.startsWith("call :") && firstExternal < 0) {
                        firstExternal = i;
                    }
                    words.add(lower.startsWith(SYSTEM32) || BUILT_INS.contains(lower) || lower.startsWith("call ")
                            ? lower : word);
                }
            }
        }
        assertTrue(setlocal >= 0 && variable == setlocal + 1, "NoDefaultCurrentDirectoryInExePath is set directly after"
                + " setlocal (code line " + variable + ", setlocal at " + setlocal + ")");
        assertTrue(firstExternal > variable, "set before the first command that is not a built-in");
        return words;
    }

    /** A code line split at {@code |}, {@code ||}, {@code &&} and {@code &} outside quotes and parentheses. */
    private static List<String> segments(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && depth > 0) {
                depth--;
            } else if (!quoted && depth == 0 && (c == '|' || c == '&')
                    && !(c == '&' && i > 0 && (line.charAt(i - 1) == '>' || line.charAt(i - 1) == '<'))) {
                out.add(current.toString());
                current.setLength(0);
                if (i + 1 < line.length() && line.charAt(i + 1) == c) {
                    i++;
                }
                continue;
            }
            current.append(c);
        }
        out.add(current.toString());
        return out;
    }

    private static final Pattern REDIRECTION = Pattern.compile("^(\\d?>&\\d|\\d?>>?\\s*nul|<\\s*nul)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IF = Pattern.compile("^if\\s+(/i\\s+)?(not\\s+)?(defined\\s+\\S+|exist\\s+(\"[^\"]*\"|\\S+)"
            + "|errorlevel\\s+\\d+|(\"[^\"]*\"|\\S+)\\s*==\\s*(\"[^\"]*\"|\\S+)|\\S+\\s+(LSS|LEQ|GTR|GEQ|EQU|NEQ)\\s+\\S+)"
            + "\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOR = Pattern.compile("^for\\s+(/[a-z]\\s+(\"[^\"]*\"\\s+)?)?%%~?\\w\\s+in\\s+\\((.*)\\)"
            + "\\s+do\\s+(.*)$", Pattern.CASE_INSENSITIVE);

    /** The command words of one segment: an {@code if}'s command and a {@code for}'s inner command and body. */
    private static List<String> commandWords(String segment) {
        String s = segment.trim();
        Matcher redirection = REDIRECTION.matcher(s);
        while (redirection.find()) {
            s = s.substring(redirection.end()).trim();
            redirection = REDIRECTION.matcher(s);
        }
        if (s.startsWith("@")) {
            s = s.substring(1).trim();
        }
        if (s.isEmpty()) {
            return List.of();
        }
        Matcher m = IF.matcher(s);
        if (m.matches()) {
            List<String> words = new ArrayList<>(List.of("if"));
            for (String inner : segments(m.group(m.groupCount()))) {
                words.addAll(commandWords(inner));
            }
            return words;
        }
        m = FOR.matcher(s);
        if (m.matches()) {
            List<String> words = new ArrayList<>(List.of("for"));
            String set = m.group(3).trim();
            if (set.startsWith("'") && set.endsWith("'")) {
                for (String inner : segments(set.substring(1, set.length() - 1))) {
                    words.addAll(commandWords(inner));
                }
            }
            for (String inner : segments(m.group(4))) {
                words.addAll(commandWords(inner));
            }
            return words;
        }
        String first = s.startsWith("\"") ? s.substring(0, s.indexOf('"', 1) + 1) : s.split("\\s+")[0];
        if (first.equalsIgnoreCase("call")) {
            String[] parts = s.split("\\s+");
            return List.of("call " + (parts.length > 1 ? parts[1] : ""));
        }
        return List.of(first);
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String text() throws IOException {
        return new String(Files.readAllBytes(BAT), StandardCharsets.US_ASCII);
    }

    private static List<String> lines() throws IOException {
        return List.of(text().split("\r\n"));
    }

    /** The lines that are not comments. */
    private static List<String> codeLines() throws IOException {
        List<String> code = new ArrayList<>();
        for (String line : lines()) {
            String t = line.trim();
            if (!t.equals("rem") && !t.startsWith("rem ")) {
                code.add(line);
            }
        }
        return code;
    }

    private static String code() throws IOException {
        return String.join("\n", codeLines());
    }
}
