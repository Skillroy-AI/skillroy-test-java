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

package ai.skillroy.testjava.agent.core;

import ai.skillroy.testjava.agent.core.fixture.TestState;
import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** agent.info.options, jvm.properties and jvm.info through the redaction of contract amendment A5. */
class RedactionPrimitivesTest {

    private static final String R = Redactor.REDACTED;

    @TempDir
    Path tmp;

    private AgentHarness agent;

    @BeforeEach
    void start() throws Exception {
        TestState.reset();
        System.setProperty("skillroy.test.db.password", "pw-value-1");
        System.setProperty("skillroy.test.sessionKey", "sk-value-2");
        System.setProperty("skillroy.test.url", "jdbc:x:mem?user=u&password=pw-value-3&ssl=true");
        System.setProperty("skillroy.test.plain", "plain-value");
        agent = AgentHarness.start(tmp, "redact", "sessionKey", "app.licenseKey", "lic-value-4",
                "app.sessionKey", "sk-value-5", "app.jdbc", "jdbc:y:?password=pw-value-6", "app.plain", "kept");
    }

    @AfterEach
    void stop() {
        agent.close();
        for (String name : new String[] {"db.password", "sessionKey", "url", "plain"}) {
            System.clearProperty("skillroy.test." + name);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentInfoOptionsHideSecretValues() throws Exception {
        AgentHarness.Reply reply = agent.send("POST", "/rpc",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"agent.info\"}", true);
        assertEquals(200, reply.status);
        Map<String, Object> options = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                reply.json().get("result")).get("value")).get("options");
        assertEquals(R, options.get("app.licenseKey"));
        assertEquals(R, options.get("app.sessionKey"), "the redact option's own regex");
        assertEquals("jdbc:y:?password=" + R, options.get("app.jdbc"));
        assertEquals("kept", options.get("app.plain"));
        assertEquals("sessionKey", options.get("redact"));
        for (String secret : new String[] {"lic-value-4", "sk-value-5", "pw-value-6"}) {
            assertFalse(reply.body.contains(secret), secret + " leaked: " + reply.body);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void jvmPropertiesReportASecretAsPresentButHidden() throws Exception {
        List<String> names = Arrays.asList("skillroy.test.db.password", "skillroy.test.sessionKey",
                "skillroy.test.url", "skillroy.test.plain", "skillroy.test.absent.password");
        AgentHarness.Reply reply = agent.send("POST", "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":1,"
                + "\"method\":\"jvm.properties\",\"params\":{\"names\":" + Json.write(names) + "}}", true);
        assertEquals(200, reply.status);
        Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                reply.json().get("result")).get("value")).get("properties");
        assertEquals(R, props.get("skillroy.test.db.password"));
        assertEquals(R, props.get("skillroy.test.sessionKey"));
        assertEquals("jdbc:x:mem?user=u&password=" + R + "&ssl=true", props.get("skillroy.test.url"));
        assertEquals("plain-value", props.get("skillroy.test.plain"));
        assertTrue(props.containsKey("skillroy.test.absent.password"));
        assertNull(props.get("skillroy.test.absent.password"), "an absent secret is still reported absent");
        for (String secret : new String[] {"pw-value-1", "sk-value-2", "pw-value-3"}) {
            assertFalse(reply.body.contains(secret), secret + " leaked: " + reply.body);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void jvmInfoPassesArgumentsAndTheMainCommandThroughTheRedactor() throws Exception {
        Map<String, Object> info = agent.valueMap("jvm.info", null);
        Redactor redactor = Redactor.withExtraKeys("sessionKey");
        List<Object> arguments = (List<Object>) info.get("inputArguments");
        List<String> raw = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertEquals(raw.size(), arguments.size());
        for (int i = 0; i < raw.size(); i++) {
            assertEquals(redactor.argument(raw.get(i)), arguments.get(i));
        }
        assertEquals(redactor.argument(JvmIds.mainCommand()), info.get("mainCommand"));
    }
}
