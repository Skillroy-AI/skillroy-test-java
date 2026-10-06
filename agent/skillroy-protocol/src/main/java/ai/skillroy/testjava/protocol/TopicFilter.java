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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Which event topics a subscriber, an {@code evt.query} or a {@code sync.await} wants. Shared so that the agent and
 * the controller interpret {@code topics=} parameters identically.
 *
 * <p>Syntax: comma-separated globs; whitespace around each glob is ignored; {@code '*'} matches any run of characters
 * including dots and the empty run; every other character matches itself (case-sensitive). A null, empty or blank
 * filter, or one containing the glob {@code "*"}, matches every topic.
 *
 * <p>Immutable; safe to share between threads.
 */
public final class TopicFilter {

    private final List<String> globs;
    private final boolean matchesEverything;

    private TopicFilter(List<String> globs) {
        this.globs = Collections.unmodifiableList(globs);
        this.matchesEverything = globs.isEmpty() || globs.contains("*");
    }

    /** Parses comma-separated globs; null or blank gives the match-all filter. Duplicates are dropped. */
    public static TopicFilter parse(String commaSeparatedGlobs) {
        List<String> globs = new ArrayList<String>();
        add(globs, commaSeparatedGlobs);
        return new TopicFilter(globs);
    }

    /** Same as {@link #parse} of the globs joined with commas; null or empty gives the match-all filter. */
    public static TopicFilter of(Collection<String> globs) {
        List<String> list = new ArrayList<String>();
        if (globs != null) {
            for (String glob : globs) {
                add(list, glob);
            }
        }
        return new TopicFilter(list);
    }

    private static void add(List<String> into, String commaSeparated) {
        if (commaSeparated == null) {
            return;
        }
        for (String part : commaSeparated.split(",", -1)) {
            String glob = part.trim();
            if (!glob.isEmpty() && !into.contains(glob)) {
                into.add(glob);
            }
        }
    }

    /** True if any glob matches the whole topic. A null topic matches only the match-all filter. */
    public boolean matches(String topic) {
        if (matchesEverything) {
            return true;
        }
        if (topic == null) {
            return false;
        }
        for (String glob : globs) {
            if (globMatches(glob, topic)) {
                return true;
            }
        }
        return false;
    }

    /** The normalised globs in order of first appearance; empty for the match-all filter built from nothing. */
    public List<String> globs() {
        return globs;
    }

    /** True if this filter accepts every topic, which lets callers skip matching. */
    public boolean matchesEverything() {
        return matchesEverything;
    }

    /** Wildcard match that backtracks only to the last '*' (no recursion; O(glob x topic) worst case). */
    private static boolean globMatches(String glob, String text) {
        int g = 0;
        int t = 0;
        int star = -1;
        int mark = 0;
        while (t < text.length()) {
            if (g < glob.length() && glob.charAt(g) == '*') {
                star = g++;
                mark = t;
            } else if (g < glob.length() && glob.charAt(g) == text.charAt(t)) {
                g++;
                t++;
            } else if (star >= 0) {
                g = star + 1;
                t = ++mark;
            } else {
                return false;
            }
        }
        while (g < glob.length() && glob.charAt(g) == '*') {
            g++;
        }
        return g == glob.length();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TopicFilter && globs.equals(((TopicFilter) o).globs);
    }

    @Override
    public int hashCode() {
        return globs.hashCode();
    }

    /** The globs joined with "," (empty string for match-all built from nothing); round-trips through parse. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String glob : globs) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(glob);
        }
        return sb.toString();
    }
}
