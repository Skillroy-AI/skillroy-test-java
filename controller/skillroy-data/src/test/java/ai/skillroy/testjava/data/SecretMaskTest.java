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

package ai.skillroy.testjava.data;

import ai.skillroy.testjava.protocol.Json;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** phase2-contracts.md 4 and 14.1 item 4: the value mask (D26). */
class SecretMaskTest {

    private static final String LONG = "Zq7-Lantern-42x";   // 15 characters
    private static final String TWELVE = "abcdEFGH1234";    // 12 characters
    private static final String SHORT = "4711";

    private final SecretMask mask = SecretMask.of(List.of(LONG, TWELVE, SHORT));

    @Test
    void wholeValuesAreMaskedWhateverTheirLength() {
        assertEquals("<redacted>", mask.apply(SHORT));
        assertEquals("<redacted>", mask.apply(LONG));
        assertEquals("<redacted>", mask.apply((Object) TWELVE));
        assertEquals("<redacted>", mask.apply(4711L), "a number whose text equals a secret");
        assertEquals("<redacted>", mask.apply(new BigDecimal("4711")));
        assertEquals(4712L, mask.apply(4712L));
        assertEquals(Boolean.TRUE, mask.apply(Boolean.TRUE));
        assertEquals("other", mask.apply("other"));
        assertEquals(null, mask.apply((String) null));
    }

    @Test
    void aTwelveCharacterSecretIsMaskedInsideLongerTexts() {
        // An error message, an event payload and a snapshot text, as a run writes them.
        assertEquals("sign-on refused for password <redacted> (attempt 2)",
                mask.apply("sign-on refused for password " + TWELVE + " (attempt 2)"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("topic", "input.acked");
        event.put("payload", Map.of("text", "typed " + TWELVE, "list", List.of("x" + TWELVE + "y")));
        Object masked = mask.apply(event);
        String json = Json.write(masked);
        assertFalse(json.contains(TWELVE), json);
        assertTrue(json.contains("typed <redacted>"), json);
        assertTrue(json.contains("x<redacted>y"), json);
        String snapshot = "window \"Sign on\"\n  textbox \"entry\" = " + TWELVE + "\n";
        assertEquals("window \"Sign on\"\n  textbox \"entry\" = <redacted>\n", mask.apply(snapshot));
        assertEquals("<redacted><redacted>", mask.apply(TWELVE + TWELVE), "every occurrence");
    }

    @Test
    void aShortSecretInsideALongerTextIsNotMaskedUnlessQuoted() {
        assertEquals("pin " + SHORT + " at 14:47:11", mask.apply("pin " + SHORT + " at 14:47:11"));
        assertEquals("expected \"<redacted>\" but was \"2\"", mask.apply("expected \"" + SHORT + "\" but was \"2\""));
        assertEquals("{\"pin\":\"<redacted>\"}", mask.apply("{\"pin\":\"" + SHORT + "\"}"));
    }

    @Test
    void keysAreNotLookedAt() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("credential", Map.of("realm", "staff", "id", "100"));
        record.put("inputTokens", 1200L);
        record.put("password", "not-a-known-secret");
        record.put(SHORT, "a key equal to a secret stays");
        assertEquals(record, mask.apply(record), "nothing here is a secret value");
        record.put("password", SHORT);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) mask.apply(record);
        assertEquals("<redacted>", out.get("password"), "only because the value is a secret");
        assertEquals(Map.of("realm", "staff", "id", "100"), out.get("credential"));
        assertEquals(1200L, out.get("inputTokens"));
        assertTrue(out.containsKey(SHORT));
        assertEquals(SHORT, record.get("password"), "the original is not changed");
    }

    @Test
    void urlUserInfoFromCredentialsIsKnownToTheMask() throws Exception {
        Credentials credentials = Credentials.of(Map.of("version", 1, "jdbc", Map.of("reporting", Map.of(
                "url", "jdbc:skillroy-fake://reader:Url-Pa55word@db.test.example:5432/app?ssl=true&password=Q-pw-7777",
                "user", "reader"))), "test");
        SecretMask fromFile = credentials.mask();
        assertEquals("jdbc:skillroy-fake://reader:<redacted>@db.test.example:5432/app?ssl=true&password=<redacted>",
                fromFile.apply("jdbc:skillroy-fake://reader:Url-Pa55word@db.test.example:5432/app?ssl=true"
                        + "&password=Q-pw-7777"));
        assertEquals("reader", fromFile.apply("reader"), "the user is not a secret");
    }

    @Test
    void plusNoneAndEmptyValues() {
        assertTrue(SecretMask.NONE.isEmpty());
        Object value = new ArrayList<>(List.of("a"));
        assertSame(value, SecretMask.NONE.apply(value));
        assertSame(SecretMask.NONE, SecretMask.of(Arrays.asList(null, "")), "null and empty texts are no secrets");
        SecretMask more = mask.plus(Set.of("another-secret"));
        assertEquals("x <redacted>", more.apply("x another-secret"));
        assertEquals("x another-secret", mask.apply("x another-secret"), "plus does not change the original");
        assertSame(mask, mask.plus(List.of()));
        assertSame(mask, mask.plus(List.of(SHORT)), "nothing new");
        assertFalse(mask.toString().contains(SHORT));
        assertEquals("SecretMask[3 secrets]", mask.toString());
        assertEquals(SecretMask.REDACTED, "<redacted>");
    }

    @Test
    void aValueNestedTooDeepToBeLookedAtIsNotPassedOn() {
        Object deep = LONG;
        for (int i = 0; i < 250; i++) {
            deep = List.of(deep);
        }
        Object leaf = mask.apply(deep);
        int levels = 0;
        while (leaf instanceof List<?> list) {
            leaf = list.get(0);
            levels++;
        }
        assertEquals("<too deep>", leaf, "a value below the depth limit is replaced, not passed on");
        assertEquals(201, levels);
        assertEquals(List.of(List.of("<redacted>")), mask.apply(List.of(List.of(LONG))));
    }

    @Test
    void aSecretContainingAnotherIsReplacedWhole() {
        SecretMask nested = SecretMask.of(List.of("Lantern-Long-1", "Lantern-Long-1-and-more"));
        assertEquals("a <redacted> b", nested.apply("a Lantern-Long-1-and-more b"));
    }

    @Test
    void theThresholdIsPerMask() {
        SecretMask low = SecretMask.of(List.of(SHORT, "ab"), 4);
        assertEquals(4, low.minLengthInText());
        assertEquals("pin <redacted> at 14:47:11", low.apply("pin " + SHORT + " at 14:47:11"),
                "a secret of the threshold's length is replaced wherever it occurs");
        assertEquals("ab at 14:47:11", low.apply("ab at 14:47:11"), "a shorter one still only in double quotes");
        assertEquals("\"<redacted>\" at", low.apply("\"ab\" at"));
        assertEquals(4, low.plus(List.of(LONG)).minLengthInText(), "plus keeps the threshold");
        assertEquals(SecretMask.MIN_SUBSTRING, SecretMask.of(List.of(SHORT)).minLengthInText());
        assertEquals(SecretMask.MIN_SUBSTRING, SecretMask.NONE.minLengthInText());
        assertThrows(IllegalArgumentException.class, () -> SecretMask.of(List.of(SHORT), 0));
        assertThrows(IllegalArgumentException.class,
                () -> SecretMask.of(List.of(SHORT), SecretMask.MAX_MIN_SUBSTRING + 1));
        SecretMask wholeOnly = SecretMask.of(List.of(SHORT), SecretMask.MAX_MIN_SUBSTRING);
        assertEquals("x" + SHORT + "y", wholeOnly.apply("x" + SHORT + "y"), "the largest threshold: whole values only");
        assertEquals("<redacted>", wholeOnly.apply(SHORT));
    }
}
