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
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicFilterTest {

    @Test
    void emptyFiltersMatchEverything() {
        TopicFilter[] filters = {
            TopicFilter.parse(null), TopicFilter.parse(""), TopicFilter.parse("   "), TopicFilter.parse(" , ,"),
            TopicFilter.of(null), TopicFilter.of(Collections.<String>emptyList())
        };
        for (TopicFilter filter : filters) {
            assertTrue(filter.matchesEverything());
            assertTrue(filter.matches("state.changed"));
            assertTrue(filter.matches(""));
            assertTrue(filter.matches(null));
            assertEquals("", filter.toString());
            assertTrue(filter.globs().isEmpty());
        }
    }

    @Test
    void aLoneStarMatchesEverything() {
        TopicFilter star = TopicFilter.parse("*");
        assertTrue(star.matchesEverything());
        assertTrue(star.matches("a.b.c"));
        assertEquals("*", star.toString());
        assertTrue(TopicFilter.parse("input.*, *").matches("anything"));
    }

    @Test
    void globWithoutStarIsAnExactCaseSensitiveMatch() {
        TopicFilter filter = TopicFilter.parse("state.changed");
        assertTrue(filter.matches("state.changed"));
        assertFalse(filter.matches("state.changed.x"));
        assertFalse(filter.matches("state"));
        assertFalse(filter.matches("xstate.changed"));
        assertFalse(filter.matches("State.changed"));
        assertFalse(filter.matches(null));
        assertFalse(filter.matchesEverything());
    }

    @Test
    void starMatchesAnyRunIncludingDotsAndNothing() {
        TopicFilter prefix = TopicFilter.parse("state.*");
        assertTrue(prefix.matches("state.changed"));
        assertTrue(prefix.matches("state.a.b"));
        assertTrue(prefix.matches("state."));
        assertFalse(prefix.matches("state"));
        assertFalse(prefix.matches("states.x"));

        TopicFilter suffix = TopicFilter.parse("*.opened");
        assertTrue(suffix.matches("window.opened"));
        assertTrue(suffix.matches("a.b.opened"));
        assertFalse(suffix.matches("window.opened.x"));

        TopicFilter middle = TopicFilter.parse("a*b*c");
        assertTrue(middle.matches("abc"));
        assertTrue(middle.matches("a.x.b.y.c"));
        assertTrue(middle.matches("abbbc"));
        assertTrue(middle.matches("abcbc"));
        assertFalse(middle.matches("acb"));
        assertFalse(middle.matches("abcd"));

        assertTrue(TopicFilter.parse("**").matches("x"));
        assertTrue(TopicFilter.parse("a**").matches("a"));
    }

    @Test
    void otherGlobCharactersAreLiteral() {
        TopicFilter filter = TopicFilter.parse("a?.[b]");
        assertTrue(filter.matches("a?.[b]"));
        assertFalse(filter.matches("ab.b"));
    }

    @Test
    void anyGlobOfSeveralMayMatch() {
        TopicFilter filter = TopicFilter.parse("input.*,prompt.*");
        assertTrue(filter.matches("input.acked"));
        assertTrue(filter.matches("prompt.waiting"));
        assertFalse(filter.matches("state.changed"));
    }

    @Test
    void trimsWhitespaceDropsDuplicatesAndRoundTripsThroughParse() {
        TopicFilter filter = TopicFilter.parse(" state.* ,  prompt.waiting ,state.*,");
        assertEquals(Arrays.asList("state.*", "prompt.waiting"), filter.globs());
        assertEquals("state.*,prompt.waiting", filter.toString());
        assertEquals(filter, TopicFilter.parse(filter.toString()));
        assertEquals(filter.hashCode(), TopicFilter.parse(filter.toString()).hashCode());
        assertTrue(filter.matches("state.changed"));
    }

    @Test
    void ofSplitsAndNormalisesLikeParse() {
        TopicFilter filter = TopicFilter.of(Arrays.asList("a.*", " b ", "c,d", null, ""));
        assertEquals(Arrays.asList("a.*", "b", "c", "d"), filter.globs());
        assertEquals(TopicFilter.parse("a.*,b,c,d"), filter);
        assertNotEquals(TopicFilter.parse("b,a.*,c,d"), filter);
    }
}
