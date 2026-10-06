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

package ai.skillroy.testjava.agent.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Option parsing, precedence and the enable rule (phase1-contracts.md 2.4). */
class BootOptionsTest {

    @TempDir
    Path tmp;

    private static Map<String, String> env(String value) {
        return value == null ? Collections.<String, String>emptyMap()
                : Collections.singletonMap(BootOptions.ENV_ENABLED, value);
    }

    @Test
    void parsesTheArgumentString() {
        Map<String, String> args = BootOptions.parseArgs(" a=1 ; b = two=2;;flag; =x;c=;a=3");
        assertEquals("3", args.get("a"), "later keys win");
        assertEquals("two=2", args.get("b"), "only the first '=' splits");
        assertEquals("true", args.get("flag"));
        assertEquals("", args.get("c"));
        assertFalse(args.containsKey(""));
        assertEquals(4, args.size());
        assertTrue(BootOptions.parseArgs(null).isEmpty());
        assertTrue(BootOptions.parseArgs("").isEmpty());
    }

    @Test
    void systemPropertiesUnderThePrefixAreOptionsExceptTheOnesTheAgentPublishes() {
        Properties sys = new Properties();
        sys.setProperty("skillroy.agent.port", "4000");
        sys.setProperty("skillroy.agent.modules", "/m.jar");
        sys.setProperty("skillroy.agent.enabled", "true");
        sys.setProperty("skillroy.agent.error", "old");
        sys.setProperty("skillroy.agent.loadMode", "JAVAAGENT");
        sys.setProperty("skillroy.agent.protocol", "0.2");
        sys.setProperty("skillroy.agent.discoveryFile", "/d.json");
        sys.setProperty("skillroy.agent.session", "00112233445566778899aabbccddeeff");
        sys.setProperty("skillroy.agent.", "empty key");
        sys.setProperty("other.prop", "x");
        Map<String, String> options = BootOptions.fromSystemProperties(sys);
        assertEquals("4000", options.get("port"));
        assertEquals("/m.jar", options.get("modules"));
        assertEquals(2, options.size(), String.valueOf(options));
    }

    @Test
    void precedenceIsSystemPropertiesThenFileThenArguments() throws IOException {
        File file = tmp.resolve("opts.properties").toFile();
        write(file, "port=2000\nreadOnly=true\nnonce=from-file\n# comment\nhome=/hé\n");
        Properties sys = new Properties();
        sys.setProperty("skillroy.agent.port", "1000");
        sys.setProperty("skillroy.agent.readOnly", "false");
        sys.setProperty("skillroy.agent.eventRing", "500");
        Map<String, String> args = BootOptions.parseArgs("t=" + file + ";port=3000");
        Map<String, String> merged = BootOptions.resolve(BootOptions.ATTACH, args, sys, env(null));
        assertEquals("3000", merged.get("port"), "arguments beat the file");
        assertEquals("true", merged.get("readOnly"), "the file beats system properties");
        assertEquals("500", merged.get("eventRing"), "system properties beat defaults");
        assertEquals("from-file", merged.get("nonce"));
        assertEquals("/hé", merged.get("home"), "UTF-8 file");
        assertEquals("false", merged.get("enabled"));
    }

    @Test
    void theOptionsFileCanBeNamedByConfigOrBySystemProperty() throws IOException {
        File file = tmp.resolve("c.properties").toFile();
        write(file, "token=abc\n");
        Map<String, String> viaConfig = BootOptions.resolve(BootOptions.ATTACH,
                BootOptions.parseArgs("config=" + file), new Properties(), env(null));
        assertEquals("abc", viaConfig.get("token"));
        Properties sys = new Properties();
        sys.setProperty("skillroy.agent.t", file.getPath());
        assertEquals("abc", BootOptions.resolve(BootOptions.JAVAAGENT, new LinkedHashMap<String, String>(), sys,
                env(null)).get("token"));
        IOException missing = assertThrows(IOException.class, () -> BootOptions.resolve(BootOptions.ATTACH,
                BootOptions.parseArgs("t=" + tmp.resolve("nope")), new Properties(), env(null)));
        assertTrue(missing.getMessage().contains("nope"));
    }

    @Test
    void javaAgentIsEnabledOnlyBySystemPropertyOrEnvironment() throws IOException {
        File file = tmp.resolve("e.properties").toFile();
        write(file, "enabled=true\n");
        Map<String, String> args = BootOptions.parseArgs("enabled=true;t=" + file);
        assertEquals("false", BootOptions.resolve(BootOptions.JAVAAGENT, args, new Properties(), env(null))
                .get("enabled"), "neither the argument string nor its file may enable JAVAAGENT");
        Properties sys = new Properties();
        sys.setProperty("skillroy.agent.enabled", "TRUE");
        assertEquals("true", BootOptions.resolve(BootOptions.JAVAAGENT, args, sys, env(null)).get("enabled"));
        assertEquals("true", BootOptions.resolve(BootOptions.JAVAAGENT, args, new Properties(), env("true"))
                .get("enabled"));
        assertEquals("false", BootOptions.resolve(BootOptions.JAVAAGENT, args, new Properties(), env("1"))
                .get("enabled"), "only the word true counts");
        Properties disabled = new Properties();
        disabled.setProperty("skillroy.agent.enabled", "false");
        assertEquals("false", BootOptions.resolve(BootOptions.JAVAAGENT,
                BootOptions.parseArgs("enabled=true"), disabled, env(null)).get("enabled"));
    }

    @Test
    void attachAcceptsEnabledFromItsArgumentsOrFile() throws IOException {
        File file = tmp.resolve("a.properties").toFile();
        write(file, "enabled=true\n");
        assertEquals("true", BootOptions.resolve(BootOptions.ATTACH, BootOptions.parseArgs("t=" + file),
                new Properties(), env(null)).get("enabled"));
        assertEquals("true", BootOptions.resolve(BootOptions.ATTACH, BootOptions.parseArgs("enabled=true"),
                new Properties(), env(null)).get("enabled"));
        assertEquals("false", BootOptions.resolve(BootOptions.ATTACH, BootOptions.parseArgs("port=0"),
                new Properties(), env(null)).get("enabled"));
    }

    @Test
    void embeddedAcceptsEnabledFromTheHostsPropertiesButNotFromTheirFile() throws IOException {
        File file = tmp.resolve("x.properties").toFile();
        write(file, "enabled=true\n");
        Properties host = new Properties();
        host.setProperty("enabled", " true ");
        assertEquals("true", BootOptions.resolve(BootOptions.EMBEDDED, BootOptions.fromProperties(host),
                new Properties(), env(null)).get("enabled"));
        Properties viaFile = new Properties();
        viaFile.setProperty("t", file.getPath());
        assertEquals("false", BootOptions.resolve(BootOptions.EMBEDDED, BootOptions.fromProperties(viaFile),
                new Properties(), env(null)).get("enabled"));
        assertTrue(BootOptions.fromProperties(null).isEmpty());
    }

    @Test
    void helpers() {
        assertTrue(BootOptions.isTrue(" True "));
        assertFalse(BootOptions.isTrue("yes"));
        assertFalse(BootOptions.isTrue(null));
        assertEquals("b", BootOptions.firstNonEmpty(null, " ", " b ", "c"));
        assertNull(BootOptions.firstNonEmpty(null, ""));
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(Charset.forName("UTF-8")));
    }
}
