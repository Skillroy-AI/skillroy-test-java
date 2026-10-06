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

package ai.skillroy.testjava.scenario;

import java.util.regex.Pattern;

/**
 * Bounded evaluation of scenario regexes ({@code matches} and {@code [field~regex]}) on text the target supplies
 * (security finding S24). A regex search can take exponential time with some patterns, or polynomial time on a
 * multi-megabyte value, and alternation inside a repeated group ({@code (.|\n)*}) recurses once per character and
 * overflows the stack at a few thousand characters. Without a bound a run would hang past its step timeout, or an
 * {@link StackOverflowError} would skip cleanup and recovery.
 *
 * <p>Three bounds: the subject is wrapped in a {@link CharSequence} that checks a deadline every 1024 reads and stops
 * the search with {@link Rejected}; a {@link StackOverflowError} inside the search is caught (safe: the matcher is
 * discarded and no lock is held) and reported as {@link Rejected}; a subject longer than {@link #MAX_SUBJECT} is
 * rejected before the search, never silently truncated. The deadline budget is shared by every search made with the
 * same {@link Budget}, so a long list cannot multiply it.
 *
 * <p>Stateless apart from the caller's {@link Budget}, which belongs to one thread.
 */
final class SafeRegex {

    /** How long all searches of one expectation attempt may take together, as for the agent's locator regexes. */
    static final long DEFAULT_BUDGET_MS = 1000L;

    /** The longest subject searched: 1 MiB characters. */
    static final int MAX_SUBJECT = 1 << 20;

    private static final int CHECK_EVERY = 1024;

    private SafeRegex() {
    }

    /** Why a search was stopped; carries a reason for the failure message and no stack trace. */
    static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Rejected(String reason) {
            super(reason, null, false, false);
        }
    }

    /** A deadline shared by the searches of one evaluation. Used by one thread. */
    static final class Budget {

        private final long budgetMs;
        private final long deadlineNanos;

        private Budget(long budgetMs) {
            this.budgetMs = budgetMs;
            this.deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
        }

        /** A budget of {@link #DEFAULT_BUDGET_MS} starting now. */
        static Budget start() {
            return new Budget(DEFAULT_BUDGET_MS);
        }

        /** A budget of {@code ms} milliseconds starting now (tests). */
        static Budget ofMillis(long ms) {
            return new Budget(Math.max(1L, ms));
        }

        boolean expired() {
            return System.nanoTime() - deadlineNanos >= 0;
        }

        long millis() {
            return budgetMs;
        }
    }

    /**
     * {@code pattern.matcher(subject).find()}, bounded as described on the class.
     *
     * @throws Rejected if the subject is too long, the budget runs out or the regex recurses too deeply
     */
    static boolean find(Pattern pattern, String subject, Budget budget) {
        if (subject.length() > MAX_SUBJECT) {
            throw new Rejected("the text has " + subject.length() + " characters; a regex is searched in at most "
                    + MAX_SUBJECT);
        }
        if (budget.expired()) {
            throw timedOut(budget, subject);
        }
        try {
            return pattern.matcher(new BoundedText(subject, budget)).find();
        } catch (StackOverflowError e) {
            throw new Rejected("the regex recurses too deeply on a text of " + subject.length() + " characters;"
                    + " avoid alternation inside a repeated group, e.g. use (?s).* instead of (.|\\n)*");
        }
    }

    private static Rejected timedOut(Budget budget, CharSequence subject) {
        return new Rejected("the regex ran longer than " + budget.millis() + " ms on a text of " + subject.length()
                + " characters and was stopped; simplify it");
    }

    /** The subject, checking the budget every {@link #CHECK_EVERY} character reads. */
    private static final class BoundedText implements CharSequence {

        private final String text;
        private final Budget budget;
        private int reads;

        BoundedText(String text, Budget budget) {
            this.text = text;
            this.budget = budget;
        }

        @Override
        public char charAt(int index) {
            if (++reads >= CHECK_EVERY) {
                reads = 0;
                if (budget.expired()) {
                    throw timedOut(budget, text);
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
            return new BoundedText(text.substring(start, end), budget);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
