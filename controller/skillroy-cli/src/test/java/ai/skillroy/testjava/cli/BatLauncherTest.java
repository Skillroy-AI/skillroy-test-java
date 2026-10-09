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
 * waits for the host (9.5).
 */
class BatLauncherTest {

    private static final Path BAT = Path.of("src/main/bat/skillroy.bat");
    private static final Path SH = Path.of("src/main/sh/skillroy");
    private static final Path GITATTRIBUTES = Path.of("../../.gitattributes");

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
        assertTrue(code.contains("findstr /r /c:\"^MODULES=.*[\\\" ]jdk\\.attach[\\\" ]\""), "the MODULES test");
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
