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

import ai.skillroy.testjava.protocol.ErrorCodes;
import ai.skillroy.testjava.protocol.RpcError;
import ai.skillroy.testjava.protocol.web.WebLib;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared receiving-side bounds of security fixes S43 and S46 (phase4-contracts.md 4.2 and 4.6, P1 8.4):
 * {@link TextBounds} cuts and sizes, {@link BoundedRegex} stops a search within its budget, and the {@code WebLib}
 * helpers both web paths use.
 */
class BoundsTest {

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    void cutNeverSplitsASurrogatePair() {
        assertEquals("abc", TextBounds.cut("abcdef", 3));
        assertEquals("ab", TextBounds.cut("ab", 3));
        assertNull(TextBounds.cut(null, 3));
        String emoji = "a😀b";
        assertEquals("a", TextBounds.cut(emoji, 2), "one fewer than a split pair");
        assertEquals("a😀", TextBounds.cut(emoji, 3));
        assertThrows(IllegalArgumentException.class, () -> TextBounds.cut("x", -1));
    }

    @Test
    void sizeEstimatesJsonAndStopsEarly() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("k", "vv");
        value.put("n", Long.valueOf(1));
        value.put("l", Arrays.<Object>asList("a", null));
        long size = TextBounds.size(value, Long.MAX_VALUE);
        assertTrue(size >= "{\"k\":\"vv\",\"n\":1,\"l\":[\"a\",null]}".length(), "never under-counts: " + size);
        List<Object> many = Collections.<Object>nCopies(1000000, repeat('x', 100));
        long early = TextBounds.size(many, 1000);
        assertTrue(early > 1000 && early < 10000, "stops soon after the bound: " + early);
        assertTrue(TextBounds.exceeds(many, 1000));
        assertFalse(TextBounds.exceeds("short", 1000));
    }

    @Test
    void cutStringsCopiesWithEveryStringCut() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("text", repeat('t', 100));
        value.put(repeat('k', 100), Arrays.<Object>asList(repeat('l', 100), Long.valueOf(3)));
        @SuppressWarnings("unchecked")
        Map<String, Object> cut = (Map<String, Object>) TextBounds.cutStrings(value, 10);
        assertEquals(repeat('t', 10), cut.get("text"));
        assertEquals(Arrays.<Object>asList(repeat('l', 10), Long.valueOf(3)), cut.get(repeat('k', 10)));
        assertEquals(100, ((String) value.get("text")).length(), "the argument is unchanged");
    }

    /** S46: the pattern of the finding, on a 78-character title, is stopped within its budget. */
    @Test
    void aBacktrackingRegexIsStoppedWithinItsBudget() {
        Pattern p = Pattern.compile("(.*){1,32000}#");
        long start = System.nanoTime();
        BoundedRegex.TooSlow e = assertThrows(BoundedRegex.TooSlow.class, () -> BoundedRegex.find(p,
                repeat('a', 78), 300000000L));
        long ms = (System.nanoTime() - start) / 1000000L;
        assertTrue(ms < 1500, "stopped: " + ms + " ms");
        assertEquals(BoundedRegex.REGEX_TOO_SLOW, e.reason());
        assertTrue(e.describe("by.title", 1000).startsWith("the regex in by.title "), e.describe("by.title", 1000));
        assertFalse(e.getMessage().contains("aaaa"), "never the subject");
        assertTrue(BoundedRegex.find(Pattern.compile("b$"), "aab", 1000000000L));
        assertFalse(BoundedRegex.find(Pattern.compile("^c"), "aab", 1000000000L));
    }

    @Test
    void aSubjectOverOneMiCharactersIsRefusedBeforeTheSearch() {
        BoundedRegex.TooSlow e = assertThrows(BoundedRegex.TooSlow.class, () -> BoundedRegex.find(
                Pattern.compile("x"), repeat('u', 2 << 20), 1000000000L));
        assertEquals(BoundedRegex.TEXT_TOO_LONG, e.reason());
        assertThrows(BoundedRegex.TooSlow.class, () -> BoundedRegex.find(Pattern.compile("x"), "x", 0L),
                "no budget left");
    }

    @Test
    void aRegexThatRecursesTooDeeplyIsStopped() {
        BoundedRegex.TooSlow e = assertThrows(BoundedRegex.TooSlow.class, () -> BoundedRegex.find(
                Pattern.compile("(a|b)*c"), repeat('a', 200000), 10000000000L));
        assertEquals(BoundedRegex.REGEX_TOO_SLOW, e.reason());
    }

    /** S43: the WebLib helpers both web paths use. */
    @Test
    void theWebLibraryHelpersBoundWhatAPageSends() {
        RpcError oversized = WebLib.oversized("the page's answer", 1000);
        assertEquals(ErrorCodes.INTERNAL_ERROR, oversized.code());
        assertEquals("oversized", oversized.data().get("reason"));
        assertEquals(Long.valueOf(1000), oversized.data().get("bound"));
        assertEquals(WebLib.MAX_ERROR_CHARS, WebLib.scriptMessage(repeat('m', 10000)).length());
        assertEquals("first", WebLib.scriptMessage("first\nsecond"));
        assertEquals(WebLib.MAX_STACK_CHARS, WebLib.scriptStack(repeat('s', 100000)).length());
        assertEquals("", WebLib.scriptStack(null));
        Map<String, Object> detail = Collections.<String, Object>singletonMap("blob", repeat('d', 70 * 1024));
        assertTrue(WebLib.detailTooLong(detail));
        assertEquals(WebLib.MAX_DETAIL_CHARS, WebLib.cutDetail(detail).length());
        assertTrue(WebLib.cutDetail(detail).startsWith("{\"blob\":\"ddd"), "the detail's JSON text, cut");
        assertFalse(WebLib.detailTooLong("small"));
        assertEquals(6 * WebLib.MAX_JSON_CHARS + 65536, WebLib.MAX_CHANNEL_CHARS);
        assertEquals(4096, WebLib.MAX_URL_CHARS);
    }
}
