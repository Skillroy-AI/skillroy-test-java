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

package ai.skillroy.testjava.protocol;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Amendment A17: the secret-key rule shared by the agent's redaction and the controller's fixture masking. */
class SecretKeysTest {

    @Test
    void theRuleIsTheContractsRule() {
        // phase1-contracts.md, "Secret redaction"; agent-core's Redactor used exactly this text before A17
        assertEquals("password|passwd|pwd|secret|token|credential|licen[cs]e|api[-_.]?key|private[-_.]?key",
                SecretKeys.DEFAULT_KEYS);
        assertEquals("<redacted>", SecretKeys.REDACTED);
    }

    @Test
    void secretKeysAnywhereInTheKeyAnyCase() {
        for (String key : Arrays.asList("password", "db.password", "PASSWORD", "userPassword", "passwd", "pwd",
                "app.secret", "clientSecretValue", "token", "skillroy.agent.tokenFile", "credentials",
                "license", "licence.key", "LICENSE_KEY", "apikey", "api-key", "api_key", "api.key", "API_KEY",
                "privatekey", "private-key", "private.key", "ssh.Private_Key", "lending.pin.password")) {
            assertTrue(SecretKeys.looksSecret(key), key);
        }
    }

    @Test
    void ordinaryKeysAreNotSecret() {
        for (String key : Arrays.asList(null, "", "user.name", "java.version", "port", "nonce", "session",
                "api", "api key", "private", "key", "pass", "lending.pin", "tok_en")) {
            assertFalse(SecretKeys.looksSecret(key), String.valueOf(key));
        }
    }
}
