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

package ai.skillroy.testjava.protocol.text;

import ai.skillroy.testjava.protocol.Json;
import ai.skillroy.testjava.protocol.SecretKeys;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key rule inside a text (Phase 8 security fix S175): the pairs the earlier rules hid, unchanged, and the three
 * shapes they missed: a JSON text inside a JSON text (escaped quotes, one and two levels deep), an object value and a
 * list value, each replaced up to its end so that a JSON text stays JSON.
 */
class SecretPairsTest {

    private static final SecretPairs.KeyTest DEFAULT = new SecretPairs.KeyTest() {
        @Override
        public boolean secret(String key) {
            return SecretKeys.looksSecret(key);
        }
    };

    private static String redact(String text) {
        return SecretPairs.redact(text, DEFAULT);
    }

    @Test
    void theShapesTheEarlierRulesHid() {
        assertEquals("login password=<redacted> ok", redact("login password=hunter-two ok"));
        assertEquals("token: <redacted>, user: desk", redact("token: abc123, user: desk"));
        assertEquals("{\"api_key\": \"<redacted>\", \"x\": 1}", redact("{\"api_key\": \"k-9\", \"x\": 1}"));
        assertEquals("'secret'='<redacted>'", redact("'secret'='s'"));
        assertEquals("login password=<redacted>&user=bob {\"token\":\"<redacted>\"} apiKey: <redacted>",
                redact("login password=abc&user=bob {\"token\":\"xyz\"} apiKey: k9"));
        assertEquals("password-reset-url: <redacted>", redact("password-reset-url: http://x/y"));
        assertEquals("{\"note\":\"<redacted>\", \"n\": 1}",
                SecretPairs.redact("{\"note\":\"a \\\"quoted\\\" pass\", \"n\": 1}", new SecretPairs.KeyTest() {
                    @Override
                    public boolean secret(String key) {
                        return key.equals("note");
                    }
                }), "a quoted value's escaped quotes stay inside it");
    }

    @Test
    void whatIsNotAPairOrNotSecretIsKept() {
        String plain = "pin: 1234, the token count is 5, https://desk/x?y=1";
        assertSame(plain, redact(plain));
        assertEquals("password:", redact("password:"), "no value");
        assertNull(redact(null));
        assertEquals("user=bob", SecretPairs.redact("user=bob", null));
    }

    @Test
    void anEscapedJsonTextInsideAJsonText() {
        String once = "{\"body\":\"{\\\"user\\\":\\\"desk\\\",\\\"token\\\":\\\"opensesame-77\\\"}\"}";
        String masked = redact(once);
        assertFalse(masked.contains("opensesame"), masked);
        assertEquals("{\"body\":\"{\\\"user\\\":\\\"desk\\\",\\\"token\\\":\\\"<redacted>\\\"}\"}", masked);
        Map<String, Object> outer = Json.parseObject(masked);
        assertEquals("<redacted>", Json.parseObject((String) outer.get("body")).get("token"), "still JSON");
        String inner = "{\"token\":\"s3cr3t\\\"x\"}";
        String twice = Json.write(java.util.Collections.singletonMap("body", Json.write(
                java.util.Collections.singletonMap("body", inner))));
        String twiceMasked = redact(twice);
        assertFalse(twiceMasked.contains("s3cr3t") || twiceMasked.contains("x\\"), twiceMasked);
        Object level2 = Json.parseObject((String) Json.parseObject((String) Json.parseObject(twiceMasked)
                .get("body")).get("body")).get("token");
        assertEquals("<redacted>", level2, "two levels deep, an escaped quote inside the value included");
    }

    @Test
    void anObjectOrAListValueIsReplacedWhole() {
        assertEquals("{\"token\": \"<redacted>\", \"x\": 1}", redact("{\"token\": {\"value\": \"t-1\", \"n\": [1]},"
                + " \"x\": 1}"));
        assertEquals("{\"credentials\":\"<redacted>\"}", redact("{\"credentials\":[\"a]\",\"b\"]}"),
                "a bracket inside a quoted text does not close it");
        assertEquals("token: <redacted>", redact("token: {a: [1, 2], b: {c: 3}}"));
        assertEquals("{\"secret\": \"<redacted>\"", redact("{\"secret\": {\"never\": \"closed\""),
                "an object that does not close is replaced to the end");
        assertEquals("{\"pin\": 4711, \"password\": \"<redacted>\"}", redact("{\"pin\": 4711, \"password\": 4711}"),
                "a bare value of a double-quoted key becomes a quoted marker");
        String escapedObject = "{\"body\":\"{\\\"token\\\":{\\\"v\\\":\\\"x}\\\"},\\\"n\\\":1}\"}";
        String masked = redact(escapedObject);
        assertEquals("{\"body\":\"{\\\"token\\\":\\\"<redacted>\\\",\\\"n\\\":1}\"}", masked);
        assertEquals(1L, Json.parseObject((String) Json.parseObject(masked).get("body")).get("n"));
    }

    @Test
    void theRuleIsIdempotentOnMaskedText() {
        String[] texts = {"{\"token\": {\"value\": \"t-1\"}, \"x\": 1}", "login password=hunter-two ok",
                "{\"body\":\"{\\\"token\\\":\\\"opensesame-77\\\"}\"}", "{\"pin\": 1, \"password\": 4711}"};
        for (String text : texts) {
            String once = redact(text);
            assertSame(once, redact(once), text);
        }
        String earlier = "\"credential\":<redacted>\"realm\":\"staff\",\"key\":\"code\"}";
        assertSame(earlier, redact(earlier), "what the earlier key rule wrote is kept as it is");
    }

    @Test
    void theLevelsOfAnEscapedQuote() {
        assertEquals(0, SecretPairs.level(0));
        assertEquals(1, SecretPairs.level(1));
        assertEquals(2, SecretPairs.level(3));
        assertEquals(3, SecretPairs.level(7));
        assertEquals(-1, SecretPairs.level(2));
        assertTrue(SecretPairs.delimits(1, 1));
        assertFalse(SecretPairs.delimits(3, 1), "a quote inside a level-1 text");
        assertTrue(SecretPairs.delimits(5, 1), "a backslash at the end of a level-1 text, then its quote");
        assertTrue(SecretPairs.delimits(3, 2));
        assertFalse(SecretPairs.delimits(7, 2));
    }

    @Test
    void linearOnAHostileText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            text.append("password:");
        }
        StringBuilder brackets = new StringBuilder("token: ");
        for (int i = 0; i < 200_000; i++) {
            brackets.append('[');
        }
        long t0 = System.nanoTime();
        redact(text.toString());
        redact(brackets.toString());
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < 100_000; i++) {
            keys.append("aaaaaaaaaa \\\\\\\"");
        }
        keys.append(':');
        redact(keys.toString());
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L, "linear: well under five seconds");
    }
}
