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

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A regular-expression search under the S24 budget (phase1-contracts.md 8.4), for a caller's pattern on text the
 * target or a page supplies (a page's URL or title in a browser selection; security fix S46): a pattern that
 * backtracks without end would otherwise hold an agent worker, or the CDP path's thread, for as long as it ran. The
 * technique of the swing locator and of the scenario's {@code SafeRegex}: the subject is wrapped in a
 * {@link CharSequence} that looks at the clock every 1024 character reads and stops the search past the deadline; a
 * {@link StackOverflowError} inside the search (alternation inside a repeated group recurses once per character) is
 * caught, which is safe because the matcher is discarded and no lock is held; a subject longer than
 * {@link #MAX_SUBJECT} is refused before the search, never cut. Java 8, JDK only.
 *
 * <p>Stateless; safe from any thread.
 */
public final class BoundedRegex {

    /** The budget of one selection's searches together (P1 8.4: 1000 ms). */
    public static final long BUDGET_MS = 1000;

    /** The longest subject searched: 1 Mi characters. */
    public static final int MAX_SUBJECT = 1 << 20;

    /** {@link TooSlow#reason()} of a search that ran out of time or recursed too deeply. */
    public static final String REGEX_TOO_SLOW = "regex too slow";

    /** {@link TooSlow#reason()} of a subject over {@link #MAX_SUBJECT}. */
    public static final String TEXT_TOO_LONG = "text too long";

    private static final int CHECK_EVERY = 1024;

    private BoundedRegex() {
    }

    /** Why a search was stopped or refused; carries a reason and a message, never the subject, and no stack trace. */
    public static final class TooSlow extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String reason;
        private final boolean recursion;

        TooSlow(String reason, String message) {
            this(reason, message, false);
        }

        TooSlow(String reason, String message, boolean recursion) {
            super(message, null, false, false);
            this.reason = reason;
            this.recursion = recursion;
        }

        /** {@link #REGEX_TOO_SLOW} or {@link #TEXT_TOO_LONG}: the {@code data.reason} of the caller's error. */
        public String reason() {
            return reason;
        }

        /**
         * The caller's message for a search of the regex given as {@code where} (for example {@code by.title}) under a
         * budget of {@code budgetMs}: "the regex in by.title ran longer than 1000 ms and was stopped; simplify it",
         * or why it recursed too deeply, or that the text was too long; never the subject.
         */
        public String describe(String where, long budgetMs) {
            if (TEXT_TOO_LONG.equals(reason)) {
                return "the text " + where + " is searched in is longer than " + MAX_SUBJECT + " characters; a regex"
                        + " is searched in at most that";
            }
            if (recursion) {
                return "the regex in " + where + " recurses too deeply; avoid alternation inside a repeated group,"
                        + " e.g. use (?s).* instead of (.|\\n)*";
            }
            return "the regex in " + where + " ran longer than " + budgetMs + " ms and was stopped; simplify it";
        }
    }

    /**
     * {@code pattern.matcher(subject).find()} within {@code budgetNanos}.
     *
     * @param budgetNanos the time the search may take; zero or less is refused at once
     * @throws TooSlow if the subject is longer than {@link #MAX_SUBJECT}, the budget runs out or the regex recurses
     *     too deeply
     */
    public static boolean find(Pattern pattern, CharSequence subject, long budgetNanos) throws TooSlow {
        if (subject.length() > MAX_SUBJECT) {
            throw new TooSlow(TEXT_TOO_LONG, "the text has " + subject.length() + " characters; a regex is searched"
                    + " in at most " + MAX_SUBJECT);
        }
        if (budgetNanos <= 0) {
            throw timedOut(budgetNanos);
        }
        long deadline = System.nanoTime() + budgetNanos;
        try {
            return pattern.matcher(new Bounded(subject, deadline, budgetNanos)).find();
        } catch (StackOverflowError e) {
            throw new TooSlow(REGEX_TOO_SLOW, "the regex recurses too deeply on a text of " + subject.length()
                    + " characters; avoid alternation inside a repeated group, e.g. use (?s).* instead of (.|\\n)*",
                    true);
        }
    }

    private static TooSlow timedOut(long budgetNanos) {
        return new TooSlow(REGEX_TOO_SLOW, "ran longer than " + Math.max(0, TimeUnit.NANOSECONDS.toMillis(budgetNanos))
                + " ms and was stopped; simplify it");
    }

    /** The subject, looking at the clock every {@link #CHECK_EVERY} character reads. */
    private static final class Bounded implements CharSequence {

        private final CharSequence text;
        private final long deadline;
        private final long budgetNanos;
        private int reads;

        Bounded(CharSequence text, long deadline, long budgetNanos) {
            this.text = text;
            this.deadline = deadline;
            this.budgetNanos = budgetNanos;
        }

        @Override
        public char charAt(int index) {
            if (++reads >= CHECK_EVERY) {
                reads = 0;
                if (System.nanoTime() - deadline > 0) {
                    throw timedOut(budgetNanos);
                }
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Bounded(text.subSequence(start, end), deadline, budgetNanos);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
