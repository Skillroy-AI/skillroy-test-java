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

package ai.skillroy.testjava.protocol.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A class pattern of the hook deny-list (phase8-contracts.md section 6.3, gap 114, D561): the one grammar the
 * controller's catalogue reader, the agent option {@code hooks.deny}, {@code hook.install}'s {@code catalogue.deny} and
 * the Byteman module apply.
 *
 * <p>The grammar: the characters {@code [A-Za-z0-9_$*.]} only; segments separated by {@code .}, none empty; every
 * segment but the last a Java identifier; the last segment an identifier, {@code *}, {@code **}, or an identifier with
 * {@code *} wildcards; at most {@link #MAX_LENGTH} characters.
 *
 * <p>Matching a binary name ({@code last} the pattern's last segment, {@code prefix} the rest):
 * <ul>
 *   <li>{@code a.b.C} matches exactly {@code a.b.C} and its nested classes ({@code a.b.C$X...});</li>
 *   <li>{@code a.b.*} every class whose package is exactly {@code a.b}, nested classes included (the remainder after
 *       {@code a.b.} holds no {@code .});</li>
 *   <li>{@code a.b.**} every class under {@code a.b.};</li>
 *   <li>{@code a.b.Input*} every class of {@code a.b} whose last segment (the simple name with any {@code $nested}
 *       suffix) matches the glob, each {@code *} any run of characters other than {@code .};</li>
 *   <li>a one-segment pattern applies to the default package by the same rules ({@code **} alone matches every
 *       class).</li>
 * </ul>
 * Linear in the name's length for a pattern of bounded length: no regular expression, no backtracking. Java 8, JDK
 * only; immutable and safe to share between threads.
 */
public final class ClassPattern {

    /** The longest pattern. */
    public static final int MAX_LENGTH = 200;
    /** The most patterns one source (a catalogue, the agent option, one {@code hook.install}) may give. */
    public static final int MAX_PER_SOURCE = 64;

    private enum Kind { EXACT, PACKAGE, TREE, GLOB }

    private final String pattern;
    private final Kind kind;
    /** The package part with its trailing dot ({@code "a.b."}), empty for a one-segment pattern. */
    private final String prefix;
    /** The last segment. */
    private final String last;
    /** For a glob: the literal pieces between the stars, in order. */
    private final String[] pieces;

    private ClassPattern(String pattern) {
        this.pattern = pattern;
        int dot = pattern.lastIndexOf('.');
        this.prefix = dot < 0 ? "" : pattern.substring(0, dot + 1);
        this.last = pattern.substring(dot + 1);
        if ("**".equals(last)) {
            this.kind = Kind.TREE;
            this.pieces = null;
        } else if ("*".equals(last)) {
            this.kind = Kind.PACKAGE;
            this.pieces = null;
        } else if (last.indexOf('*') >= 0) {
            this.kind = Kind.GLOB;
            this.pieces = splitStars(last);
        } else {
            this.kind = Kind.EXACT;
            this.pieces = null;
        }
    }

    /** Null, or why {@code pattern} is not a class pattern (the texts of phase8-contracts.md section 6.3). */
    public static String violation(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "empty";
        }
        if (pattern.length() > MAX_LENGTH) {
            return "longer than " + MAX_LENGTH + " characters";
        }
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (!allowed(c)) {
                return "character '" + printable(c) + "' is not allowed";
            }
        }
        String[] segments = pattern.split("\\.", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return "an empty segment";
            }
        }
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean isLast = i == segments.length - 1;
            if (segment.contains("**") && !(isLast && "**".equals(segment))) {
                return "'**' is allowed only as the whole last segment";
            }
            if (!isLast && segment.indexOf('*') >= 0) {
                return "'*' is allowed only in the last segment";
            }
        }
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if ("*".equals(segment) || "**".equals(segment)) {
                continue;
            }
            // a glob's stars stand for identifier characters: the rest must still read as an identifier
            if (!isIdentifier(segment.replace('*', 'a'))) {
                return "segment '" + segment + "' is not an identifier";
            }
        }
        return null;
    }

    /**
     * The compiled pattern.
     *
     * @throws IllegalArgumentException with {@link #violation}'s text when it is not a class pattern
     */
    public static ClassPattern compile(String pattern) {
        String violation = violation(pattern);
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
        return new ClassPattern(pattern);
    }

    /** True when {@code binaryName} (a class's binary name, {@code a.b.C$D}) is one the pattern names; null: false. */
    public boolean matches(String binaryName) {
        if (binaryName == null) {
            return false;
        }
        switch (kind) {
            case TREE:
                return binaryName.startsWith(prefix);
            case PACKAGE:
                return binaryName.startsWith(prefix) && binaryName.length() > prefix.length()
                        && binaryName.indexOf('.', prefix.length()) < 0;
            case GLOB:
                return binaryName.startsWith(prefix) && binaryName.indexOf('.', prefix.length()) < 0
                        && globMatches(binaryName, prefix.length());
            default:
                return binaryName.equals(pattern) || (binaryName.length() > pattern.length()
                        && binaryName.startsWith(pattern) && binaryName.charAt(pattern.length()) == '$');
        }
    }

    /** The pattern as given. */
    public String pattern() {
        return pattern;
    }

    /**
     * The first of {@code patterns} that matches {@code binaryName}, or null; each is compiled once per call.
     *
     * @throws IllegalArgumentException when one of them is not a class pattern
     */
    public static String firstMatch(List<String> patterns, String binaryName) {
        if (patterns == null) {
            return null;
        }
        for (String p : patterns) {
            if (compile(p).matches(binaryName)) {
                return p;
            }
        }
        return null;
    }

    /**
     * The last segment from {@code from} on against the glob: the pieces between the stars matched greedily left to
     * right (correct for {@code *} alone, and linear in the name's length times the pattern's).
     */
    private boolean globMatches(String name, int from) {
        int pos = from;
        int end = name.length();
        boolean leadingStar = last.charAt(0) == '*';
        boolean trailingStar = last.charAt(last.length() - 1) == '*';
        for (int i = 0; i < pieces.length; i++) {
            String piece = pieces[i];
            boolean first = i == 0;
            boolean lastPiece = i == pieces.length - 1;
            if (first && !leadingStar) {
                if (!name.startsWith(piece, pos)) {
                    return false;
                }
                pos += piece.length();
                if (lastPiece && !trailingStar) {
                    return pos == end;
                }
                continue;
            }
            if (lastPiece && !trailingStar) {
                int at = end - piece.length();
                return at >= pos && name.startsWith(piece, at);
            }
            int at = name.indexOf(piece, pos);
            if (at < 0) {
                return false;
            }
            pos = at + piece.length();
        }
        return true;
    }

    private static String[] splitStars(String glob) {
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i <= glob.length(); i++) {
            if (i == glob.length() || glob.charAt(i) == '*') {
                if (i > start) {
                    out.add(glob.substring(start, i));
                }
                start = i + 1;
            }
        }
        return out.toArray(new String[0]);
    }

    private static boolean allowed(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '$'
                || c == '*' || c == '.';
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || (s.charAt(0) >= '0' && s.charAt(0) <= '9')) {
            return false;
        }
        return true;
    }

    private static String printable(char c) {
        if (c < 0x20 || c == 0x7F) {
            return String.format("\\u%04x", Integer.valueOf(c));
        }
        return String.valueOf(c);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClassPattern && ((ClassPattern) o).pattern.equals(pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(pattern);
    }

    @Override
    public String toString() {
        return pattern;
    }
}
